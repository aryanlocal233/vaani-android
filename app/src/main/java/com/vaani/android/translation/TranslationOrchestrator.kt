package com.vaani.android.translation

import com.vaani.android.audio.AudioChunkBuffer
import com.vaani.android.audio.AudioConfig
import com.vaani.android.audio.AudioPlaybackManager
import com.vaani.android.audio.AudioRecordManager
import com.vaani.android.audio.ConversationState
import com.vaani.android.audio.VADManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires together VAD, audio capture/playback, and the WebSocket connection into a single
 * hands-free turn-taking state machine:
 *
 * IDLE --(speech onset)--> LISTENING
 * LISTENING --(400ms silence AND utterance >= 1.5s)--> PROCESSING (sends end-of-utterance)
 * LISTENING --(400ms silence AND utterance < 1.5s)--> IDLE (discarded as filler/noise)
 * PROCESSING --(first TTS chunk arrives)--> SPEAKING
 * SPEAKING --(TTS playback finishes)--> IDLE
 * SPEAKING --(speech onset / barge-in)--> cancel TTS playback, mic unmuted --> LISTENING
 */
@Singleton
class TranslationOrchestrator @Inject constructor(
    private val audioRecordManager: AudioRecordManager,
    private val audioPlaybackManager: AudioPlaybackManager,
    private val audioChunkBuffer: AudioChunkBuffer,
    private val vadManager: VADManager,
    private val webSocketManager: WebSocketManager
) {
    private var scope = CoroutineScope(Dispatchers.Default + Job())
    private var silenceMs = 0
    private var isUtteranceActive = false
    private var isFirstChunkOfUtterance = false

    private val _conversationState = MutableStateFlow(ConversationState.IDLE)
    val conversationState: StateFlow<ConversationState> = _conversationState.asStateFlow()

    private val _currentTranscript = MutableStateFlow("")
    val currentTranscript: StateFlow<String> = _currentTranscript.asStateFlow()

    private val _currentTranslation = MutableStateFlow("")
    val currentTranslation: StateFlow<String> = _currentTranslation.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var isSessionActive = false

    fun startSession(srcLang: String, tgtLang: String) {
        if (isSessionActive) return
        isSessionActive = true
        scope = CoroutineScope(Dispatchers.Default + Job())

        vadManager.reset()
        _conversationState.value = ConversationState.IDLE
        _currentTranscript.value = ""
        _currentTranslation.value = ""
        _error.value = null

        audioPlaybackManager.onPlaybackStarted = {
            Timber.d("onPlaybackStarted fired (state=${_conversationState.value})")
            if (_conversationState.value == ConversationState.PROCESSING) {
                transitionTo(ConversationState.SPEAKING)
                audioRecordManager.setMuted(true)
                Timber.d("State: PROCESSING -> SPEAKING, mic muted for TTS playback")
            }
        }
        audioPlaybackManager.onPlaybackFinished = {
            Timber.d("onPlaybackFinished fired (state=${_conversationState.value})")
            if (_conversationState.value == ConversationState.SPEAKING) {
                audioRecordManager.setMuted(false)
                transitionTo(ConversationState.IDLE)
                Timber.d("State: SPEAKING -> IDLE, mic unmuted, ready for next utterance")
            }
        }

        webSocketManager.connect(srcLang, tgtLang)
        observeWebSocketEvents()
        observeAudioFrames()
        audioRecordManager.start()

        Timber.i("TranslationOrchestrator: session started ($srcLang -> $tgtLang)")
    }

    fun stopSession() {
        if (!isSessionActive) return
        isSessionActive = false

        audioRecordManager.stop()
        audioPlaybackManager.stopPlayback()
        webSocketManager.disconnect()
        scope.cancel()

        isUtteranceActive = false
        isFirstChunkOfUtterance = false
        silenceMs = 0
        _conversationState.value = ConversationState.IDLE

        Timber.i("TranslationOrchestrator: session stopped")
    }

    private fun observeAudioFrames() {
        scope.launch {
            audioRecordManager.frames.collect { frame ->
                handleFrame(frame)
            }
        }
    }

    private fun handleFrame(frame: ByteArray) {
        val vadResult = vadManager.processFrame(frame)
        val state = _conversationState.value

        when (state) {
            ConversationState.IDLE -> {
                if (vadResult.isSpeech) {
                    beginUtterance()
                }
            }
            ConversationState.LISTENING -> {
                if (vadResult.isSpeech) {
                    silenceMs = 0
                } else {
                    silenceMs += AudioConfig.FRAME_SIZE_MS
                }
            }
            ConversationState.SPEAKING -> {
                if (vadResult.isSpeech) {
                    handleBargeIn()
                }
            }
            ConversationState.PROCESSING -> {
                // Ignore mic input while waiting for the server response.
            }
        }

        if (state == ConversationState.LISTENING || (state == ConversationState.IDLE && isUtteranceActive)) {
            val chunk = audioChunkBuffer.addFrame(
                frame = frame,
                isUtteranceActive = isUtteranceActive,
                isFirstChunk = isFirstChunkOfUtterance
            )
            if (chunk != null) {
                isFirstChunkOfUtterance = false
                webSocketManager.sendAudioChunk(chunk.data)
            }

            if (state == ConversationState.LISTENING && silenceMs >= AudioConfig.SILENCE_THRESHOLD_MS) {
                endUtterance()
            }
        }
    }

    private fun beginUtterance() {
        isUtteranceActive = true
        isFirstChunkOfUtterance = true
        silenceMs = 0
        transitionTo(ConversationState.LISTENING)
    }

    private fun endUtterance() {
        val (finalChunk, meetsMinDuration) = audioChunkBuffer.flush()
        isUtteranceActive = false
        silenceMs = 0

        if (!meetsMinDuration) {
            Timber.d("Utterance discarded: below MIN_UTTERANCE_MS")
            audioChunkBuffer.discardUtterance()
            transitionTo(ConversationState.IDLE)
            return
        }

        webSocketManager.sendEndOfUtterance(finalChunk?.data ?: ByteArray(0))
        transitionTo(ConversationState.PROCESSING)
    }

    private fun handleBargeIn() {
        Timber.i("Barge-in detected: cancelling TTS playback")
        audioPlaybackManager.stopPlayback()
        audioRecordManager.setMuted(false)
        _currentTranslation.value = ""
        beginUtterance()
    }

    private fun observeWebSocketEvents() {
        scope.launch {
            webSocketManager.events.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> _isConnected.value = true
                    is WebSocketEvent.Disconnected -> _isConnected.value = false
                    is WebSocketEvent.ConnectionFailed -> {
                        _isConnected.value = false
                        _error.value = event.throwable.message ?: "Connection failed"
                    }
                    is WebSocketEvent.TtsAudioReceived -> {
                        audioPlaybackManager.queueAudioChunk(event.pcm)
                    }
                    is WebSocketEvent.TranscriptReceived -> {
                        _currentTranscript.value = event.text
                    }
                    is WebSocketEvent.TranslationReceived -> {
                        _currentTranslation.value = event.text
                    }
                    is WebSocketEvent.ServerError -> {
                        _error.value = event.message
                        audioRecordManager.setMuted(false)
                        transitionTo(ConversationState.IDLE)
                    }
                    is WebSocketEvent.NoSpeech -> {
                        audioRecordManager.setMuted(false)
                        transitionTo(ConversationState.IDLE)
                    }
                }
            }
        }
    }

    private fun transitionTo(newState: ConversationState) {
        Timber.d("State transition: ${_conversationState.value} -> $newState")
        _conversationState.value = newState
    }
}

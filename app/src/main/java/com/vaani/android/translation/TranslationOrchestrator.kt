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
    private var idleMs = 0

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

    /**
     * Language actually detected for the most recent utterance/response, as reported by the
     * backend's "lang" field on the transcript/translation events. The session's WebSocket
     * connects with a (langA, langB) pair, and the backend now auto-detects which one was
     * actually spoken per utterance and translates to the other -- direction can flip from one
     * utterance to the next on the *same* connection, no reconnect needed. These are purely for
     * display (e.g. labelling the transcript/translation with which language they're in); they
     * don't drive any session/connection logic.
     */
    private val _detectedSrcLang = MutableStateFlow<String?>(null)
    val detectedSrcLang: StateFlow<String?> = _detectedSrcLang.asStateFlow()

    private val _detectedTgtLang = MutableStateFlow<String?>(null)
    val detectedTgtLang: StateFlow<String?> = _detectedTgtLang.asStateFlow()

    /**
     * Fired when the session has sat with no detected speech for [IDLE_TIMEOUT_MS] -- "detected
     * speech" is VAD's speech/non-speech call, which is language-agnostic and already tuned to
     * reject background noise (see [com.vaani.android.audio.VADManager]), so this naturally
     * covers "check if it's noise or any language" without needing separate logic here: ambient
     * noise alone never resets [idleMs], only VAD actually classifying a frame as speech does,
     * regardless of which of the 12 supported languages it turns out to be.
     *
     * This only notifies -- it deliberately does not call [stopSession] itself, since that would
     * cancel [scope] from inside a coroutine running on that same [scope] (the frame-handling
     * collector). The caller (ViewModel) calls [stopSession] from its own, unrelated scope,
     * exactly like a manual stop-button press.
     */
    var onIdleTimeout: (() -> Unit)? = null

    private var isSessionActive = false

    fun startSession(srcLang: String, tgtLang: String) {
        if (isSessionActive) return
        isSessionActive = true
        scope = CoroutineScope(Dispatchers.Default + Job())

        vadManager.reset()
        audioChunkBuffer.reset()
        audioPlaybackManager.reset()
        idleMs = 0
        _conversationState.value = ConversationState.IDLE
        _currentTranscript.value = ""
        _currentTranslation.value = ""
        _detectedSrcLang.value = null
        _detectedTgtLang.value = null
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

        observeWebSocketEvents()
        observeAudioFrames()
        webSocketManager.connect(srcLang, tgtLang)
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
                } else {
                    idleMs += AudioConfig.FRAME_SIZE_MS
                    if (idleMs >= IDLE_TIMEOUT_MS) {
                        idleMs = 0 // guard against re-firing every frame if the caller is slow to stop
                        Timber.i("No speech detected for ${IDLE_TIMEOUT_MS}ms -- ending conversation due to inactivity")
                        _error.value = "Conversation ended due to inactivity"
                        onIdleTimeout?.invoke()
                    }
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
        _detectedSrcLang.value = null
        _detectedTgtLang.value = null
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
                    is WebSocketEvent.Disconnected -> {
                        _isConnected.value = false
                        resetToIdleIfMidUtterance("WebSocket disconnected unexpectedly")
                    }
                    is WebSocketEvent.ConnectionFailed -> {
                        _isConnected.value = false
                        _error.value = event.throwable.message ?: "Connection failed"
                        resetToIdleIfMidUtterance("WebSocket connection failed")
                    }
                    is WebSocketEvent.TtsAudioReceived -> {
                        audioPlaybackManager.queueAudioChunk(event.pcm)
                    }
                    is WebSocketEvent.TranscriptReceived -> {
                        _currentTranscript.value = event.text
                        if (event.lang.isNotBlank()) _detectedSrcLang.value = event.lang
                    }
                    is WebSocketEvent.TranslationReceived -> {
                        _currentTranslation.value = event.text
                        if (event.lang.isNotBlank()) _detectedTgtLang.value = event.lang
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

    /**
     * Recovers the state machine after the WebSocket drops unexpectedly (server crash,
     * network loss, etc.) while mid-utterance. Without this, a disconnect during
     * PROCESSING/SPEAKING left the UI stuck forever with the mic muted and no way to
     * start a new turn, since only [WebSocketEvent.ServerError]/[WebSocketEvent.NoSpeech]
     * used to reset state -- an unexpected disconnect fell through with no recovery.
     */
    private fun resetToIdleIfMidUtterance(reason: String) {
        val state = _conversationState.value
        if (state == ConversationState.IDLE) return

        Timber.w("Resetting state machine to IDLE ($reason), was: $state")
        audioPlaybackManager.stopPlayback()
        audioRecordManager.setMuted(false)
        isUtteranceActive = false
        isFirstChunkOfUtterance = false
        silenceMs = 0
        audioChunkBuffer.discardUtterance()
        transitionTo(ConversationState.IDLE)
    }

    private fun transitionTo(newState: ConversationState) {
        Timber.d("State transition: ${_conversationState.value} -> $newState")
        if (newState == ConversationState.IDLE) idleMs = 0
        _conversationState.value = newState
    }

    companion object {
        /**
         * How long the session can sit idle (no speech detected, in any supported language --
         * see [onIdleTimeout]) before it's considered abandoned and ended automatically. Picked
         * as the middle of the requested 30-40s window.
         */
        private const val IDLE_TIMEOUT_MS = 35_000
    }
}

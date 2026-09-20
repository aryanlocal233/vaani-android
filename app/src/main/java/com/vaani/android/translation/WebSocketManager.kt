package com.vaani.android.translation

import com.vaani.android.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/** Frame type flags for the binary WebSocket protocol (client<->server). */
object WsFrameType {
    const val HEARTBEAT: Byte = 0x00
    const val AUDIO_CHUNK: Byte = 0x01
    const val END_OF_UTTERANCE: Byte = 0x02
    const val TTS_AUDIO: Byte = 0x03
}

sealed class WebSocketEvent {
    data object Connected : WebSocketEvent()
    data class Disconnected(val code: Int, val reason: String) : WebSocketEvent()
    data class TtsAudioReceived(val pcm: ByteArray) : WebSocketEvent()
    data class TranscriptReceived(val text: String, val isFinal: Boolean, val lang: String) : WebSocketEvent()
    data class TranslationReceived(val text: String, val lang: String) : WebSocketEvent()
    data class ServerError(val message: String) : WebSocketEvent()
    data class ConnectionFailed(val throwable: Throwable) : WebSocketEvent()
    data class NoSpeech(val message: String) : WebSocketEvent()
}

/**
 * Manages the WebSocket connection to the translation backend using a compact binary protocol
 * for audio and JSON text frames for transcript/translation/error metadata.
 *
 * Reconnects with exponential backoff (capped at [MAX_RECONNECT_ATTEMPTS] attempts, delay capped
 * at [MAX_BACKOFF_MS]), and sends a heartbeat frame every [HEARTBEAT_INTERVAL_MS] to keep NATed
 * connections alive.
 */
@Singleton
class WebSocketManager @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false
    private var lastSrcLang: String = ""
    private var lastTgtLang: String = ""

    private val _events = MutableSharedFlow<WebSocketEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WebSocketEvent> = _events.asSharedFlow()

    fun connect(srcLang: String, tgtLang: String) {
        lastSrcLang = srcLang
        lastTgtLang = tgtLang
        shouldReconnect = true
        reconnectAttempts = 0
        openSocket(srcLang, tgtLang)
    }

    private fun openSocket(srcLang: String, tgtLang: String) {
        val url = "${BuildConfig.WS_BASE_URL}/ws/translate/$srcLang/$tgtLang"
        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("WebSocket connected: $url")
                reconnectAttempts = 0
                startHeartbeat()
                scope.launch { _events.emit(WebSocketEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.w("WebSocket closing: code=$code reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.w("WebSocket closed: code=$code reason=$reason")
                stopHeartbeat()
                scope.launch { _events.emit(WebSocketEvent.Disconnected(code, reason)) }
                maybeReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "WebSocket failure")
                stopHeartbeat()
                scope.launch { _events.emit(WebSocketEvent.ConnectionFailed(t)) }
                maybeReconnect()
            }
        })
    }

    private fun maybeReconnect() {
        if (!shouldReconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Timber.e("WebSocket: max reconnect attempts reached, giving up")
            return
        }
        reconnectAttempts++
        val backoffMs = min(
            INITIAL_BACKOFF_MS * 2.0.pow(reconnectAttempts - 1).toLong(),
            MAX_BACKOFF_MS
        )
        Timber.i("WebSocket: reconnecting in ${backoffMs}ms (attempt $reconnectAttempts)")
        scope.launch {
            delay(backoffMs)
            if (shouldReconnect) openSocket(lastSrcLang, lastTgtLang)
        }
    }

    private fun handleBinaryMessage(bytes: ByteString) {
        if (bytes.size < 1) return
        val flag = bytes[0]
        val payload = bytes.substring(1).toByteArray()
        when (flag) {
            WsFrameType.TTS_AUDIO -> scope.launch { _events.emit(WebSocketEvent.TtsAudioReceived(payload)) }
            WsFrameType.HEARTBEAT -> Timber.v("Heartbeat ack received")
            else -> Timber.w("Unknown binary frame flag: $flag")
        }
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "transcript" -> scope.launch {
                    _events.emit(
                        WebSocketEvent.TranscriptReceived(
                            text = json.optString("text"),
                            isFinal = json.optBoolean("final", false),
                            lang = json.optString("lang")
                        )
                    )
                }
                "translation" -> scope.launch {
                    _events.emit(
                        WebSocketEvent.TranslationReceived(
                            text = json.optString("text"),
                            lang = json.optString("lang")
                        )
                    )
                }
                "error" -> scope.launch {
                    _events.emit(WebSocketEvent.ServerError(json.optString("message")))
                }
                "no_speech" -> scope.launch {
                    _events.emit(WebSocketEvent.NoSpeech(json.optString("message")))
                }
                else -> Timber.w("Unknown text frame type: $text")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse text frame: $text")
        }
    }

    /** Sends a 0x01-tagged raw PCM audio chunk. */
    fun sendAudioChunk(pcm: ByteArray) {
        send(WsFrameType.AUDIO_CHUNK, pcm)
    }

    /** Sends a 0x02-tagged end-of-utterance frame with any trailing PCM data. */
    fun sendEndOfUtterance(remainingPcm: ByteArray = ByteArray(0)) {
        send(WsFrameType.END_OF_UTTERANCE, remainingPcm)
    }

    private fun send(flag: Byte, payload: ByteArray) {
        val frame = ByteArray(payload.size + 1)
        frame[0] = flag
        System.arraycopy(payload, 0, frame, 1, payload.size)
        val sent = webSocket?.send(frame.toByteString(0, frame.size)) ?: false
        if (!sent) {
            Timber.w("Failed to send WS frame (flag=$flag), socket not open")
        }
    }

    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                send(WsFrameType.HEARTBEAT, ByteArray(0))
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun disconnect() {
        shouldReconnect = false
        stopHeartbeat()
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
}

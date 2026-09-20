package com.vaani.android.audio

/**
 * Central tuning constants for the audio capture / VAD / streaming pipeline.
 * All timing values are in milliseconds unless stated otherwise.
 */
object AudioConfig {
    /** PCM sample rate used end-to-end (capture, VAD, playback, WS protocol). */
    const val SAMPLE_RATE = 16000

    /** Mono 16-bit PCM. */
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /** Size of a single VAD analysis frame. */
    const val FRAME_SIZE_MS = 30

    /** Size of a network-bound audio chunk (must be >= FRAME_SIZE_MS, batched). */
    const val CHUNK_SIZE_MS = 200

    /** Continuous silence required to end an utterance. */
    const val SILENCE_THRESHOLD_MS = 400

    /** Utterances shorter than this are discarded as noise / filler. */
    const val MIN_UTTERANCE_MS = 1500

    /** Rolling pre-speech buffer retained so utterance onset isn't clipped. */
    const val RING_BUFFER_MS = 2000

    /** Amount of ring-buffer audio prepended to the first chunk of an utterance. */
    const val PRE_SPEECH_CONTEXT_MS = 300

    /** Heartbeat cadence for the WebSocket connection. */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    const val BYTES_PER_MS = (SAMPLE_RATE * BYTES_PER_SAMPLE) / 1000

    fun msToBytes(ms: Int): Int = ms * BYTES_PER_MS

    val FRAME_SIZE_BYTES = msToBytes(FRAME_SIZE_MS)
    val CHUNK_SIZE_BYTES = msToBytes(CHUNK_SIZE_MS)
    val RING_BUFFER_SIZE_BYTES = msToBytes(RING_BUFFER_MS)
    val MIN_UTTERANCE_SIZE_BYTES = msToBytes(MIN_UTTERANCE_MS)
}

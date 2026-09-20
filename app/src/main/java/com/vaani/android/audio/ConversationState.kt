package com.vaani.android.audio

/**
 * High-level turn-taking state of a conversation session.
 */
enum class ConversationState {
    /** No active session, or session connected but nobody speaking. */
    IDLE,

    /** Speech onset detected, actively capturing an utterance. */
    LISTENING,

    /** Utterance sent, waiting for translation + TTS to arrive. */
    PROCESSING,

    /** Playing back translated audio through the speaker. */
    SPEAKING
}

/**
 * Result of running VAD on a single audio frame.
 *
 * @param isSpeech whether this frame was classified as speech.
 * @param energy the raw (or smoothed) energy level of the frame, useful for UI meters.
 * @param noiseFloor the current adaptive noise floor estimate.
 */
data class VADResult(
    val isSpeech: Boolean,
    val energy: Double,
    val noiseFloor: Double
)

/**
 * A chunk of raw PCM audio, batched to [com.vaani.android.audio.AudioConfig.CHUNK_SIZE_MS].
 *
 * @param data raw 16-bit PCM samples, little-endian.
 * @param timestampMs wall-clock time (System.currentTimeMillis) the chunk was finalized.
 * @param isFirstChunkOfUtterance true when pre-speech context has been prepended.
 */
data class AudioChunk(
    val data: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
    val isFirstChunkOfUtterance: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return data.contentEquals(other.data) &&
            timestampMs == other.timestampMs &&
            isFirstChunkOfUtterance == other.isFirstChunkOfUtterance
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + isFirstChunkOfUtterance.hashCode()
        return result
    }
}

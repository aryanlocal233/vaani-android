package com.vaani.android.audio

import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Batches 30ms PCM frames from [AudioRecordManager] into 200ms chunks suitable for network
 * transmission (avoids flooding the WebSocket with 20-30ms micro-packets), and maintains a
 * rolling ring buffer so pre-speech context can be recovered once VAD confirms an utterance
 * has started (speech onset detection always lags true speech start by a frame or two).
 */
@Singleton
class AudioChunkBuffer @Inject constructor() {

    private val chunkAccumulator = ByteArrayOutputStream()
    private val ringBuffer = ArrayDeque<ByteArray>()
    private var ringBufferBytes = 0

    private val utteranceAccumulator = ByteArrayOutputStream()
    private var utteranceBytes = 0

    /**
     * Feeds a raw frame in. Always mirrors it into the ring buffer (for pre-speech context)
     * and, when [isUtteranceActive] is true, into the current utterance accumulator so total
     * utterance duration can be checked against [AudioConfig.MIN_UTTERANCE_MS].
     *
     * Returns a completed [AudioChunk] once enough data has accumulated for a network chunk,
     * or null otherwise.
     */
    fun addFrame(frame: ByteArray, isUtteranceActive: Boolean, isFirstChunk: Boolean = false): AudioChunk? {
        addToRingBuffer(frame)

        if (isUtteranceActive) {
            utteranceAccumulator.write(frame)
            utteranceBytes += frame.size
        }

        chunkAccumulator.write(frame)

        return if (chunkAccumulator.size() >= AudioConfig.CHUNK_SIZE_BYTES) {
            val data = if (isFirstChunk) {
                getPreSpeechContext(AudioConfig.PRE_SPEECH_CONTEXT_MS) + chunkAccumulator.toByteArray()
            } else {
                chunkAccumulator.toByteArray()
            }
            chunkAccumulator.reset()
            AudioChunk(data = data, isFirstChunkOfUtterance = isFirstChunk)
        } else {
            null
        }
    }

    /** Returns the last [ms] milliseconds of audio from the ring buffer (oldest-first). */
    fun getPreSpeechContext(ms: Int): ByteArray {
        val targetBytes = AudioConfig.msToBytes(ms)
        val out = ByteArrayOutputStream()
        var collected = 0
        val snapshot = ringBuffer.toList()
        for (frame in snapshot.asReversed()) {
            if (collected >= targetBytes) break
            collected += frame.size
        }
        val startIndex = maxOf(0, snapshot.size - (collected / AudioConfig.FRAME_SIZE_BYTES).coerceAtLeast(1))
        for (i in startIndex until snapshot.size) {
            out.write(snapshot[i])
        }
        return out.toByteArray()
    }

    /**
     * Flushes any buffered (but not yet chunk-sized) audio as a final [AudioChunk], and returns
     * whether the just-completed utterance met [AudioConfig.MIN_UTTERANCE_MS]. Call at end of
     * utterance (silence timeout).
     */
    fun flush(): Pair<AudioChunk?, Boolean> {
        val meetsMinDuration = utteranceBytes >= AudioConfig.MIN_UTTERANCE_SIZE_BYTES
        val remaining = chunkAccumulator.toByteArray()
        chunkAccumulator.reset()

        Timber.d("Utterance flush: bytes=$utteranceBytes meetsMin=$meetsMinDuration")
        utteranceAccumulator.reset()
        utteranceBytes = 0

        val chunk = if (remaining.isNotEmpty()) AudioChunk(data = remaining) else null
        return chunk to meetsMinDuration
    }

    /** Clears all buffered utterance state without emitting (e.g. discarded short utterance). */
    fun discardUtterance() {
        chunkAccumulator.reset()
        utteranceAccumulator.reset()
        utteranceBytes = 0
    }

    fun currentUtteranceDurationMs(): Int = utteranceBytes / AudioConfig.BYTES_PER_MS

    private fun addToRingBuffer(frame: ByteArray) {
        ringBuffer.addLast(frame)
        ringBufferBytes += frame.size
        while (ringBufferBytes > AudioConfig.RING_BUFFER_SIZE_BYTES && ringBuffer.isNotEmpty()) {
            val removed = ringBuffer.removeFirst()
            ringBufferBytes -= removed.size
        }
    }
}

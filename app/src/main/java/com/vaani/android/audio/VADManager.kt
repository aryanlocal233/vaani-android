package com.vaani.android.audio

import kotlin.math.abs
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Energy-based Voice Activity Detector with an adaptive noise floor.
 *
 * This is intentionally lightweight (no native deps) so it works everywhere and can run
 * per-frame on the audio thread. It classifies a 30ms PCM16 frame as speech/non-speech by
 * comparing short-term RMS energy against a slowly-adapting noise floor estimate.
 *
 * To upgrade to a neural VAD (recommended for production): replace [processFrame]'s energy
 * comparison with an inference call to a Silero VAD ONNX model (see
 * https://github.com/snakers4/silero-vad). Silero expects 16kHz mono PCM float32 frames of
 * 30/60/90ms and returns a speech probability in [0,1]; swap [computeEnergy] for the model's
 * forward pass and threshold on probability instead of dB above noise floor.
 */
@Singleton
class VADManager @Inject constructor() {

    private var noiseFloor: Double = INITIAL_NOISE_FLOOR
    private var speechFrameCount = 0
    private var silenceFrameCount = 0

    /**
     * Processes a single ~30ms PCM16 little-endian frame.
     */
    fun processFrame(frame: ByteArray): VADResult {
        val energy = computeEnergy(frame)

        val isAboveThreshold = energy > noiseFloor * SPEECH_TO_NOISE_RATIO + MIN_ENERGY_FLOOR

        if (isAboveThreshold) {
            speechFrameCount++
            silenceFrameCount = 0
        } else {
            silenceFrameCount++
            speechFrameCount = 0
            // Only adapt the noise floor during confirmed non-speech, and adapt slowly.
            noiseFloor = noiseFloor * (1 - NOISE_ADAPT_RATE) + energy * NOISE_ADAPT_RATE
            noiseFloor = max(noiseFloor, MIN_NOISE_FLOOR)
        }

        // Require a couple of consecutive frames above threshold to declare speech onset,
        // which suppresses single-frame clicks/pops from triggering a false positive.
        val isSpeech = speechFrameCount >= SPEECH_ONSET_FRAMES

        return VADResult(isSpeech = isSpeech, energy = energy, noiseFloor = noiseFloor)
    }

    /** Resets adaptive state, e.g. when starting a new session. */
    fun reset() {
        noiseFloor = INITIAL_NOISE_FLOOR
        speechFrameCount = 0
        silenceFrameCount = 0
    }

    private fun computeEnergy(frame: ByteArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        var i = 0
        while (i + 1 < frame.size) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort()
            sum += abs(sample.toInt()).toDouble()
            i += 2
        }
        val sampleCount = frame.size / 2
        return if (sampleCount > 0) sum / sampleCount else 0.0
    }

    companion object {
        private const val INITIAL_NOISE_FLOOR = 80.0
        private const val MIN_NOISE_FLOOR = 40.0
        private const val MIN_ENERGY_FLOOR = 60.0
        private const val SPEECH_TO_NOISE_RATIO = 2.2
        private const val NOISE_ADAPT_RATE = 0.05
        private const val SPEECH_ONSET_FRAMES = 2
    }
}

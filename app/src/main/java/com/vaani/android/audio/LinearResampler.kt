package com.vaani.android.audio

/**
 * Streaming linear-interpolation PCM16 mono resampler, from [fromRate] to [toRate].
 *
 * Sarvam TTS returns 16kHz audio, but most Android audio HALs run their mixer at 48kHz (or
 * 44.1kHz) natively. Handing AudioTrack a 16kHz-configured track forces the *platform's own*
 * resampler to upsample on every write, and on a number of real devices/emulators that path is
 * what actually produces the audible crackle -- not the app's buffering. Resampling once,
 * explicitly, to the device's native rate before the data ever reaches AudioTrack sidesteps
 * whatever quality/robustness issues exist in that platform resampler.
 *
 * Chunks arrive one at a time from the WebSocket, so this keeps interpolation state (fractional
 * phase + the trailing edge sample of the previous chunk) across calls -- resampling each chunk
 * in isolation would reintroduce exactly the kind of boundary discontinuity ([apply_edge_fade] on
 * the server) this whole exercise is trying to avoid, at every 8KB chunk seam instead of just
 * sentence seams.
 */
class LinearResampler(fromRate: Int, toRate: Int) {
    private val ratio = fromRate.toDouble() / toRate.toDouble()
    private var prevSample = 0
    private var pos = 0.0

    val isIdentity: Boolean = fromRate == toRate

    /** Resets interpolation state -- call whenever playback is discontinuous (new utterance, barge-in). */
    fun reset() {
        prevSample = 0
        pos = 0.0
    }

    fun process(input: ShortArray): ShortArray {
        if (isIdentity) return input
        if (input.isEmpty()) return input

        val estimatedOutputSize = (input.size / ratio).toInt() + 2
        val output = ShortArray(estimatedOutputSize)
        var outCount = 0
        var p = pos

        while (p < input.size) {
            var idx = p.toInt()
            if (idx >= input.size) idx = input.size - 1
            val frac = p - idx
            val s0 = if (idx == 0) prevSample else input[idx - 1].toInt()
            val s1 = input[idx].toInt()
            val sample = s0 + ((s1 - s0) * frac)
            output[outCount++] = sample.toInt().coerceIn(-32768, 32767).toShort()
            p += ratio
        }

        pos = p - input.size
        prevSample = input[input.size - 1].toInt()
        return if (outCount == output.size) output else output.copyOf(outCount)
    }
}

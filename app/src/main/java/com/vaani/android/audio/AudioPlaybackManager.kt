package com.vaani.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays back streamed TTS audio in STREAM mode using [AudioTrack] configured with
 * [AudioAttributes.USAGE_MEDIA]. Deliberately not USAGE_VOICE_COMMUNICATION: that profile
 * applies the platform's voice-call noise suppression/AGC chain to *output* too, which
 * distorts synthesized speech (muffled, crackly). It isn't needed for its usual purpose here
 * either -- feedback prevention during playback comes from [AudioRecordManager.setMuted],
 * which zeroes captured frames in software during SPEAKING regardless of AEC pairing, not from
 * echo-cancelling the mic against this output.
 *
 * Supports low-latency queuing of incoming PCM chunks as they arrive from the WebSocket, and
 * immediate [stopPlayback] for barge-in (user starts speaking while TTS is still playing).
 */
@Singleton
class AudioPlaybackManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var outputSampleRate: Int = AudioConfig.SAMPLE_RATE
    private var resampler: LinearResampler = LinearResampler(AudioConfig.SAMPLE_RATE, AudioConfig.SAMPLE_RATE)

    // Dedicated urgent-audio-priority thread instead of the shared Dispatchers.IO pool -- same
    // reasoning as AudioRecordManager's recordingExecutor: this loop's job is feeding
    // AudioTrack.write() promptly enough to avoid underrun, and a shared pool gives no priority
    // guarantee against whatever else the app schedules there. See AudioRecordManager for why
    // the priority is set as the executor's first task rather than via the thread factory.
    private val playbackExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "AudioPlaybackThread") }.apply {
        execute { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    }
    private val scope = CoroutineScope(playbackExecutor.asCoroutineDispatcher() + Job())

    private val chunkChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val isPlaying = AtomicBoolean(false)

    var onPlaybackStarted: (() -> Unit)? = null
    var onPlaybackFinished: (() -> Unit)? = null

    fun start() {
        if (isPlaying.get()) return

        // Resample TTS audio (16kHz) up to the device's native mixer rate ourselves, rather
        // than configuring AudioTrack at 16kHz and letting the platform resample on every
        // write. On a number of real devices/emulators that platform resampling path is what
        // actually produces audible crackle during playback, independent of buffering/jitter.
        val nativeRate = resolveNativeOutputSampleRate()
        outputSampleRate = nativeRate
        resampler = LinearResampler(AudioConfig.SAMPLE_RATE, nativeRate)

        val minBufferSize = AudioTrack.getMinBufferSize(
            nativeRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, (AudioConfig.CHUNK_SIZE_BYTES.toLong() * nativeRate / AudioConfig.SAMPLE_RATE).toInt() * 8)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(nativeRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        isPlaying.set(true)

        // The wire protocol has no explicit "end of TTS stream" marker -- chunks just stop
        // arriving. We treat a brief quiet period on the channel as "this utterance's audio
        // is done" rather than waiting on a signal that will never come, which is what
        // previously left the coroutine parked on chunkChannel.receive() forever and
        // onPlaybackFinished never firing (state stuck at SPEAKING, mic muted forever).
        playbackJob = scope.launch {
            while (isActive && isPlaying.get()) {
                val firstChunk = chunkChannel.receive()
                if (!isPlaying.get()) break

                // Jitter buffer: accumulate a little audio before starting playback instead of
                // writing the very first chunk straight to the track. Playing immediately on
                // chunk 1 left AudioTrack's buffer starved the moment the server's next chunk
                // was even slightly late (TTS generation/network jitter), which is audible as
                // crackling/static partway through an utterance. One wait window here trades a
                // small amount of startup latency for that headroom.
                val prebuffered = mutableListOf(firstChunk)
                var prebufferedBytes = firstChunk.size
                if (prebufferedBytes < PREBUFFER_TARGET_BYTES) {
                    val next = withTimeoutOrNull(PREBUFFER_WAIT_MS) { chunkChannel.receive() }
                    if (next != null) {
                        prebuffered += next
                        prebufferedBytes += next.size
                    }
                }

                try {
                    track.play()
                } catch (e: IllegalStateException) {
                    Timber.w(e, "AudioTrack resume failed")
                }
                withContext(Dispatchers.Main) { onPlaybackStarted?.invoke() }
                var framesWrittenThisUtterance = 0L
                prebuffered.forEach { framesWrittenThisUtterance += writeChunk(track, it) }

                // "No more chunks" can be learned two ways: an explicit end-of-stream marker
                // (see markStreamEnded -- the server now sends a "tts_end" JSON frame once all
                // of a reply's audio is out, not a guess) or, as a fallback for anything that
                // slips through without one, this quiet-period timeout. Either way, this loop
                // only decides chunks have stopped *arriving* -- it does NOT mean the audio
                // already handed to AudioTrack has finished *playing*; that's checked separately
                // below via waitForPlaybackDrain, which is the actual fix for audio cutting off
                // early (see END_OF_STREAM_GRACE_MS's and waitForPlaybackDrain's docs).
                while (isActive && isPlaying.get()) {
                    val chunk = withTimeoutOrNull(END_OF_STREAM_GRACE_MS) { chunkChannel.receive() } ?: break
                    if (chunk === END_OF_STREAM_MARKER) break
                    framesWrittenThisUtterance += writeChunk(track, chunk)
                }

                if (!isPlaying.get()) break

                waitForPlaybackDrain(track, framesWrittenThisUtterance)
                if (!isPlaying.get()) break

                // Only now -- once actual playback has caught up to the last frame we wrote --
                // is it safe to flush. Doing this immediately after the loop above (the old
                // behavior) discarded whatever was still sitting in AudioTrack's buffer waiting
                // to be rendered, which is exactly what was cutting replies off before they
                // finished: chunks for a reply (especially a multi-sentence one) can be written
                // faster than they play back, so "no new chunk arrived" does not mean "nothing
                // left to play."
                try {
                    track.pause()
                    track.flush()
                } catch (e: IllegalStateException) {
                    Timber.w(e, "AudioTrack pause/flush between utterances failed")
                }
                // The flush above is itself a discontinuity, so there's nothing to preserve
                // continuity with -- start the next utterance's resampling fresh rather than
                // carrying over interpolation state across a gap that was never continuous audio.
                resampler.reset()
                Timber.d("AudioPlaybackManager: utterance audio drained, onPlaybackFinished firing")
                withContext(Dispatchers.Main) { onPlaybackFinished?.invoke() }
            }
        }
        Timber.i("AudioPlaybackManager started (bufferSize=$bufferSize, nativeRate=$nativeRate)")
    }

    private fun resolveNativeOutputSampleRate(): Int {
        return try {
            val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
            val rate = am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (rate != null && rate > 0) rate else FALLBACK_NATIVE_SAMPLE_RATE
        } catch (e: Exception) {
            Timber.w(e, "Failed to resolve native output sample rate, falling back to $FALLBACK_NATIVE_SAMPLE_RATE")
            FALLBACK_NATIVE_SAMPLE_RATE
        }
    }

    /** Writes a chunk to the track and returns how many output frames were actually written
     * (16-bit mono, so 2 bytes/frame) -- used by [waitForPlaybackDrain] to know how much
     * audio still has to physically play out before this utterance is really done. */
    private fun writeChunk(track: AudioTrack, chunk: ByteArray): Long {
        val resampled = resampleToOutputRate(chunk)
        var offset = 0
        while (offset < resampled.size && isPlaying.get()) {
            val written = track.write(resampled, offset, resampled.size - offset)
            if (written < 0) {
                Timber.e("AudioTrack.write error: $written")
                break
            }
            offset += written
        }
        return offset / 2L
    }

    /**
     * Blocks (suspending, not the playback thread) until [track] has actually rendered
     * [framesWritten] frames, or [DRAIN_MAX_WAIT_MS] has passed -- whichever comes first. This is
     * the fix for TTS audio cutting off before a reply finishes: `AudioTrack.write()` in
     * MODE_STREAM only blocks once its internal buffer is full, so a burst of chunks (the norm
     * here -- the backend synthesizes sentences concurrently but can still deliver several in
     * quick succession) can all be *written* well before they're actually *played*. The old code
     * called pause()+flush() the moment no new chunk arrived for a while, which silently discards
     * anything still sitting in that buffer unplayed -- exactly the reported symptom. The timeout
     * is a safety net against a device/AudioTrack quirk where playbackHeadPosition never quite
     * catches up (better to cut a few ms early after 3s than hang the state machine forever).
     */
    private suspend fun CoroutineScope.waitForPlaybackDrain(track: AudioTrack, framesWritten: Long) {
        val deadline = System.currentTimeMillis() + DRAIN_MAX_WAIT_MS
        while (isActive && isPlaying.get()) {
            val playedFrames = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (playedFrames >= framesWritten) return
            if (System.currentTimeMillis() >= deadline) {
                Timber.w(
                    "AudioPlaybackManager: drain wait exceeded ${DRAIN_MAX_WAIT_MS}ms " +
                        "(played=$playedFrames, written=$framesWritten) -- proceeding anyway"
                )
                return
            }
            delay(DRAIN_POLL_INTERVAL_MS)
        }
    }

    /** Converts a PCM16LE byte chunk to samples, resamples to [outputSampleRate], and back to bytes. */
    private fun resampleToOutputRate(chunk: ByteArray): ByteArray {
        if (resampler.isIdentity) return chunk

        val sampleCount = chunk.size / 2
        val input = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = chunk[i * 2].toInt() and 0xFF
            val hi = chunk[i * 2 + 1].toInt()
            input[i] = ((hi shl 8) or lo).toShort()
        }

        val output = resampler.process(input)

        val bytes = ByteArray(output.size * 2)
        for (i in output.indices) {
            val sample = output[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Queues a chunk of TTS PCM audio for immediate playback. */
    fun queueAudioChunk(data: ByteArray) {
        if (!isPlaying.get()) start()
        chunkChannel.trySend(data)
    }

    /**
     * Signals that the server has finished sending audio for the current reply (the "tts_end"
     * WS message -- see WebSocketEvent.TtsStreamEnded), so the playback loop can stop waiting for
     * more chunks immediately instead of only via [END_OF_STREAM_GRACE_MS]'s quiet-period guess.
     * Guarded on [isPlaying] so a signal that arrives with nothing in flight (nothing was ever
     * queued, or a previous utterance already finished) can't contaminate a future utterance's
     * channel.
     */
    fun markStreamEnded() {
        if (isPlaying.get()) chunkChannel.trySend(END_OF_STREAM_MARKER)
    }

    /**
     * Cancels any in-flight playback job and drains leftover queued chunks, without touching
     * the AudioTrack. Idempotent and safe to call even when nothing is playing -- meant to be
     * called defensively at the start of a new session, guarding against stale channel/job
     * state from a previous session (e.g. one stopped mid-utterance) bleeding into a new one.
     */
    fun reset() {
        playbackJob?.cancel()
        playbackJob = null
        while (chunkChannel.tryReceive().isSuccess) {
            // drain leftover chunks from a previous session
        }
        isPlaying.set(false)
    }

    /** Immediately halts playback and discards any queued audio — used for barge-in. */
    fun stopPlayback() {
        if (!isPlaying.getAndSet(false)) return
        reset()

        audioTrack?.apply {
            try {
                pause()
                flush()
                stop()
            } catch (e: IllegalStateException) {
                Timber.w(e, "AudioTrack stop failed")
            }
            release()
        }
        audioTrack = null
        Timber.i("AudioPlaybackManager: playback stopped (barge-in or end)")
    }

    companion object {
        /**
         * How long to wait for the next TTS chunk before considering an utterance's audio
         * fully drained. The binary protocol has no explicit "end of stream" frame, so this
         * debounce is how we detect completion -- and until it fires, onPlaybackFinished
         * doesn't fire either, so the mic stays muted and the state machine stuck in SPEAKING.
         * That makes this a dead-air tax on every turn, so it's worth keeping short, but it
         * also has to survive real jitter: a translated reply may now be synthesized as several
         * concurrently-fired per-sentence TTS calls (see sarvam_server.py) queued together, and
         * on a real mobile/WiFi connection at a crowded venue -- not the clean localhost link
         * this was last tuned against -- gaps between chunks can be materially larger than on a
         * dev machine. Firing this too early mid-response is worse than the latency it saves:
         * it prematurely unmutes the mic while the speaker is still playing the tail of the
         * response, and without hardware AEC pairing (playback uses USAGE_MEDIA, not
         * VOICE_COMMUNICATION -- see AudioRecordManager) that leaked audio isn't cancelled,
         * so it's picked up as an audible burst of noise. 900ms is a middle ground: still ~40%
         * faster than the original 1500ms, with real headroom over the ~350-400ms inter-sentence
         * gaps measured locally.
         *
         * Now mainly a fallback: the server sends an explicit "tts_end" message once a reply's
         * audio is fully sent (see [markStreamEnded]), so this timeout only matters if that
         * signal is ever lost. Either way, "no more chunks are arriving" was never the same
         * question as "has everything already written actually finished playing" -- that's
         * [waitForPlaybackDrain]'s job, and skipping it was the actual cause of replies cutting
         * off early.
         */
        private const val END_OF_STREAM_GRACE_MS = 900L

        /** Sentinel pushed through [chunkChannel] by [markStreamEnded] -- compared by reference,
         * never mistaken for a real (possibly also empty) audio chunk. */
        private val END_OF_STREAM_MARKER = ByteArray(0)

        /** Max time [waitForPlaybackDrain] will wait for AudioTrack to finish rendering
         * already-written audio before giving up and proceeding anyway (safety net against a
         * device/AudioTrack quirk where playbackHeadPosition never quite reaches the target). */
        private const val DRAIN_MAX_WAIT_MS = 3_000L

        /** Poll interval for [waitForPlaybackDrain]. */
        private const val DRAIN_POLL_INTERVAL_MS = 20L

        /** Minimum amount of TTS audio to buffer before playback starts (see jitter buffer above). */
        private val PREBUFFER_TARGET_BYTES = AudioConfig.CHUNK_SIZE_BYTES * 2

        /**
         * Max time to wait for a second chunk to arrive before starting playback anyway.
         * Shrinking this below the original 250ms traded startup latency for a smaller
         * pre-roll buffer -- on real network jitter (vs. the clean localhost link it was tuned
         * against) that smaller buffer underruns more easily, which is audible as crackling at
         * the start of playback. Restored to 250ms: avoiding that crackle matters more than the
         * 100ms of startup latency it costs.
         */
        private const val PREBUFFER_WAIT_MS = 250L

        /** Used only if [AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE] is unavailable -- 48kHz is the near-universal native rate on modern Android audio HALs. */
        private const val FALLBACK_NATIVE_SAMPLE_RATE = 48000
    }
}

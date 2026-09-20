package com.vaani.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
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
class AudioPlaybackManager @Inject constructor() {

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

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

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, AudioConfig.CHUNK_SIZE_BYTES * 8)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConfig.SAMPLE_RATE)
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
                prebuffered.forEach { writeChunk(track, it) }

                while (isActive && isPlaying.get()) {
                    val chunk = withTimeoutOrNull(END_OF_STREAM_GRACE_MS) { chunkChannel.receive() } ?: break
                    writeChunk(track, chunk)
                }

                if (!isPlaying.get()) break

                // Utterance's audio has fully drained. Quiesce playback but keep the
                // AudioTrack alive (not released) so the next utterance can reuse it.
                try {
                    track.pause()
                    track.flush()
                } catch (e: IllegalStateException) {
                    Timber.w(e, "AudioTrack pause/flush between utterances failed")
                }
                Timber.d("AudioPlaybackManager: utterance audio drained, onPlaybackFinished firing")
                withContext(Dispatchers.Main) { onPlaybackFinished?.invoke() }
            }
        }
        Timber.i("AudioPlaybackManager started (bufferSize=$bufferSize)")
    }

    private fun writeChunk(track: AudioTrack, chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size && isPlaying.get()) {
            val written = track.write(chunk, offset, chunk.size - offset)
            if (written < 0) {
                Timber.e("AudioTrack.write error: $written")
                break
            }
            offset += written
        }
    }

    /** Queues a chunk of TTS PCM audio for immediate playback. */
    fun queueAudioChunk(data: ByteArray) {
        if (!isPlaying.get()) start()
        chunkChannel.trySend(data)
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
         */
        private const val END_OF_STREAM_GRACE_MS = 900L

        /** Minimum amount of TTS audio to buffer before playback starts (see jitter buffer above). */
        private const val PREBUFFER_TARGET_BYTES = AudioConfig.CHUNK_SIZE_BYTES * 2

        /**
         * Max time to wait for a second chunk to arrive before starting playback anyway.
         * Shrinking this below the original 250ms traded startup latency for a smaller
         * pre-roll buffer -- on real network jitter (vs. the clean localhost link it was tuned
         * against) that smaller buffer underruns more easily, which is audible as crackling at
         * the start of playback. Restored to 250ms: avoiding that crackle matters more than the
         * 100ms of startup latency it costs.
         */
        private const val PREBUFFER_WAIT_MS = 250L
    }
}

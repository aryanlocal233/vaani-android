package com.vaani.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
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
    private val scope = CoroutineScope(Dispatchers.IO + Job())

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

                try {
                    track.play()
                } catch (e: IllegalStateException) {
                    Timber.w(e, "AudioTrack resume failed")
                }
                withContext(Dispatchers.Main) { onPlaybackStarted?.invoke() }
                writeChunk(track, firstChunk)

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

    /** Immediately halts playback and discards any queued audio — used for barge-in. */
    fun stopPlayback() {
        if (!isPlaying.getAndSet(false)) return
        playbackJob?.cancel()
        playbackJob = null

        var drained = chunkChannel.tryReceive().isSuccess
        while (drained) {
            drained = chunkChannel.tryReceive().isSuccess
        }

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
         * debounce is how we detect completion.
         */
        private const val END_OF_STREAM_GRACE_MS = 1500L
    }
}

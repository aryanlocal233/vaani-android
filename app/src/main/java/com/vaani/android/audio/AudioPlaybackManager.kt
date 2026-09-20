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
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays back streamed TTS audio in STREAM mode using [AudioTrack] configured with
 * [AudioAttributes.USAGE_VOICE_COMMUNICATION], which must pair with [AudioRecordManager]'s
 * VOICE_COMMUNICATION source for the platform AEC to cancel this output from the mic input.
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
        val bufferSize = maxOf(minBufferSize, AudioConfig.CHUNK_SIZE_BYTES * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        track.play()
        isPlaying.set(true)

        playbackJob = scope.launch {
            var started = false
            try {
                for (chunk in chunkChannel) {
                    if (!isActive || !isPlaying.get()) break
                    if (!started) {
                        started = true
                        withContext(Dispatchers.Main) { onPlaybackStarted?.invoke() }
                    }
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
            } finally {
                withContext(Dispatchers.Main) { onPlaybackFinished?.invoke() }
            }
        }
        Timber.i("AudioPlaybackManager started (bufferSize=$bufferSize)")
    }

    /** Queues a chunk of TTS PCM audio for immediate playback. */
    fun queueAudioChunk(data: ByteArray) {
        if (!isPlaying.get()) start()
        chunkChannel.trySend(data)
    }

    /** Signals that no more chunks are coming for the current utterance; drains and stops. */
    fun finishUtterance() {
        // Playback naturally ends once the channel drains; nothing further queued means
        // onPlaybackFinished fires once the consuming loop catches up. No-op placeholder for
        // explicitness at call sites.
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
}

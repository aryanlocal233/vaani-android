package com.vaani.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures microphone audio using [MediaRecorder.AudioSource.VOICE_COMMUNICATION], which routes
 * through the device's hardware Acoustic Echo Canceller (AEC) and Noise Suppressor. This is
 * critical: pairing this source with an [android.media.AudioTrack] configured for
 * [android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION] lets the platform cancel the
 * speaker's own TTS output from the mic signal, preventing feedback loops.
 *
 * Emits fixed-size 30ms PCM16 mono frames via [frames].
 */
@Singleton
class AudioRecordManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _frames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val isRecording = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    /**
     * When true, captured frames are still read from hardware (keeping the AEC reference stream
     * alive) but are replaced with silence before being emitted, preventing the app's own TTS
     * playback from being re-ingested as user speech.
     */
    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        Timber.d("Mic muted=$muted")
    }

    val isCurrentlyRecording: Boolean
        get() = isRecording.get()

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (isRecording.get()) {
            Timber.w("AudioRecordManager already recording")
            return
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Timber.e("Unable to get min buffer size for AudioRecord")
            return
        }

        val bufferSize = max(minBufferSize, AudioConfig.FRAME_SIZE_BYTES * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Timber.e("AudioRecord failed to initialize")
            record.release()
            return
        }

        audioRecord = record
        record.startRecording()
        isRecording.set(true)

        recordingJob = scope.launch {
            val frameBuffer = ByteArray(AudioConfig.FRAME_SIZE_BYTES)
            val silence = ByteArray(AudioConfig.FRAME_SIZE_BYTES)
            while (isActive && isRecording.get()) {
                val read = record.read(frameBuffer, 0, frameBuffer.size)
                if (read <= 0) continue

                val frameToEmit = if (isMuted.get()) {
                    silence
                } else if (read == frameBuffer.size) {
                    frameBuffer
                } else {
                    frameBuffer.copyOf(read)
                }
                _frames.emit(frameToEmit.copyOf())
            }
        }
        Timber.i("AudioRecordManager started (bufferSize=$bufferSize)")
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.apply {
            try {
                stop()
            } catch (e: IllegalStateException) {
                Timber.w(e, "AudioRecord.stop() failed")
            }
            release()
        }
        audioRecord = null
        Timber.i("AudioRecordManager stopped")
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}

package com.vaani.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures microphone audio using [MediaRecorder.AudioSource.VOICE_COMMUNICATION], which routes
 * through the device's hardware Acoustic Echo Canceller (AEC) and Noise Suppressor for cleaner
 * input generally. Feedback prevention during TTS playback does *not* depend on pairing this
 * with a VOICE_COMMUNICATION-usage [android.media.AudioTrack]: [AudioPlaybackManager] uses
 * USAGE_MEDIA instead (VOICE_COMMUNICATION output distorts synthesized speech), and feedback is
 * prevented in software instead, via [setMuted] zeroing captured frames during playback.
 *
 * Emits fixed-size 30ms PCM16 mono frames via [frames].
 */
@Singleton
class AudioRecordManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // A dedicated single thread at THREAD_PRIORITY_URGENT_AUDIO, not the shared Dispatchers.IO
    // pool. IO's pool is sized/scheduled for blocking I/O work generally, with no priority
    // guarantee against everything else the app schedules there -- for a 30ms-cadence capture
    // loop that directly feeds VAD/network chunking, any scheduling delay risks a dropped or
    // late frame (perceived as the same crackling/dropout family of bugs already chased this
    // session). THREAD_PRIORITY_URGENT_AUDIO is the same priority class Android's own audio
    // subsystem uses for its mixer thread. It must be set via Process.setThreadPriority() from
    // inside the target thread itself (it's a Linux nice-value adjustment, not the unrelated
    // java.lang.Thread.priority field) -- executing it as the executor's first queued task
    // works because a single-thread executor guarantees every later task, including this
    // dispatcher's coroutine continuations, runs on that same one now-elevated thread.
    private val recordingExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "AudioRecordThread") }.apply {
        execute { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
    }
    private val scope = CoroutineScope(recordingExecutor.asCoroutineDispatcher() + Job())

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null

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
        // isMuted is a singleton field that outlives stop()/start(). If the previous session
        // was stopped mid-SPEAKING (TTS still playing), stopPlayback() cancels the playback job
        // before it ever reaches the natural "audio drained" point that calls setMuted(false) --
        // so without this, a fresh session's mic stays permanently muted, VAD never sees real
        // speech, and the app looks like it's "not listening".
        isMuted.set(false)

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
        attachNoiseEffects(record.audioSessionId)
        record.startRecording()
        isRecording.set(true)

        recordingJob = scope.launch {
            val frameBuffer = ByteArray(AudioConfig.FRAME_SIZE_BYTES)
            // Reused for every muted frame rather than allocated per-frame: its content (all
            // zero) never changes and nothing downstream mutates a received frame, so sharing
            // this one reference is safe. This is also the *common* case duration-wise -- the
            // mic sits muted for the entire SPEAKING phase of every turn -- so it's the
            // allocation most worth avoiding entirely, not just doing more cheaply.
            val silence = ByteArray(AudioConfig.FRAME_SIZE_BYTES)
            while (isActive && isRecording.get()) {
                val read = record.read(frameBuffer, 0, frameBuffer.size)
                if (read <= 0) continue

                // frameBuffer is reused every loop iteration, so any non-silence path must hand
                // out a fresh copy -- but exactly one copy, not two: copyOf(read) below already
                // allocates a new array, so re-copying it afterwards (the previous version of
                // this code did) was pure waste on a loop that runs ~33 times/sec for the
                // lifetime of every session.
                val frameToEmit = when {
                    isMuted.get() -> silence
                    read == frameBuffer.size -> frameBuffer.copyOf()
                    else -> frameBuffer.copyOf(read)
                }
                _frames.emit(frameToEmit)
            }
        }
        Timber.i("AudioRecordManager started (bufferSize=$bufferSize)")
    }

    fun stop() {
        if (!isRecording.getAndSet(false)) return
        recordingJob?.cancel()
        recordingJob = null
        releaseNoiseEffects()
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

    /**
     * Explicitly attaches the platform's noise/echo/gain audio effects to this recording
     * session. VOICE_COMMUNICATION as an audio *source* is a hint that the HAL often honors
     * automatically, but it isn't guaranteed on every device/OEM -- explicitly creating these
     * effects is what actually turns them on where the source hint alone doesn't. This matters
     * a lot for a hands-free kiosk-style deployment (a mela/fair stall): loud, constant crowd
     * noise otherwise both degrades STT accuracy and confuses the energy-based VAD in
     * [VADManager] into false speech triggers. Each effect no-ops safely if unsupported on the
     * device -- never assume availability.
     */
    private fun attachNoiseEffects(audioSessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            Timber.i("NoiseSuppressor attached: ${noiseSuppressor != null}")
        } else {
            Timber.w("NoiseSuppressor not available on this device")
        }

        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            Timber.i("AcousticEchoCanceler attached: ${echoCanceler != null}")
        }

        if (AutomaticGainControl.isAvailable()) {
            agc = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            Timber.i("AutomaticGainControl attached: ${agc != null}")
        }
    }

    private fun releaseNoiseEffects() {
        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceler?.release()
        echoCanceler = null
        agc?.release()
        agc = null
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}

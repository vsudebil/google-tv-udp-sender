package com.wled.tvaudiosync.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import com.wled.tvaudiosync.dsp.ProcessedAudioFrame
import com.wled.tvaudiosync.dsp.WledFftProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface AudioCaptureSource {
    fun start(
        gainProvider: () -> Float,
        onFrame: (ProcessedAudioFrame) -> Unit,
        onError: (String) -> Unit
    )

    fun stop()
}

/**
 * 1. Primární metoda: Android Visualizer API navázaný na global output mix (audioSessionId = 0).
 * Nevyžaduje systémový potvrzovací dialog při každém spuštění, má nízkou režii a podporuje autostart.
 */
class VisualizerCaptureSource(
    private val processor: WledFftProcessor
) : AudioCaptureSource {

    private var visualizer: Visualizer? = null

    @Volatile
    private var latestWaveform: ByteArray? = null

    override fun start(
        gainProvider: () -> Float,
        onFrame: (ProcessedAudioFrame) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        try {
            val viz = Visualizer(0).apply {
                val range = Visualizer.getCaptureSizeRange()
                captureSize = 1024.coerceIn(range[0], range[1])
                scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            }

            val rateMilliHz = Visualizer.getMaxCaptureRate().coerceAtMost(50_000)

            viz.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (waveform != null) {
                            latestWaveform = waveform.copyOf()
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null) return
                        val srHz = (samplingRate / 1000).coerceIn(8000, 96000)
                        val frame = processor.processVisualizerData(
                            fftBytes = fft,
                            waveformBytes = latestWaveform,
                            samplingRateHz = srHz,
                            gain = gainProvider()
                        )
                        onFrame(frame)
                    }
                },
                rateMilliHz,
                true,
                true
            )

            viz.enabled = true
            visualizer = viz
        } catch (ex: Exception) {
            onError("Visualizer(0) se nepodařilo spustit: ${ex.localizedMessage}")
        }
    }

    override fun stop() {
        visualizer?.let { viz ->
            runCatching {
                viz.enabled = false
                viz.release()
            }
        }
        visualizer = null
        latestWaveform = null
    }
}

/**
 * 2. Sekundární (fallback) metoda: AudioPlaybackCaptureConfiguration (MediaProjection API).
 * Zachytává PCM stream z aplikací (USAGE_MEDIA, USAGE_GAME) i tam, kde Visualizer(0) vrací nuly.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlaybackCaptureSource(
    private val mediaProjection: MediaProjection,
    private val processor: WledFftProcessor,
    private val scope: CoroutineScope
) : AudioCaptureSource {

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun start(
        gainProvider: () -> Float,
        onFrame: (ProcessedAudioFrame) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        try {
            val sampleRate = 44100
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferBytes = maxOf(minBuf, 2048 * 2)

            val record = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError("AudioRecord se nepodařilo inicializovat pro Playback Capture.")
                record.release()
                return
            }

            audioRecord = record
            record.startRecording()

            captureJob = scope.launch(Dispatchers.Default) {
                val pcmBuffer = ShortArray(1024)
                while (isActive) {
                    val read = record.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read > 0) {
                        val frame = processor.processPcm16(
                            pcm = pcmBuffer,
                            count = read,
                            sampleRateHz = sampleRate,
                            gain = gainProvider()
                        )
                        onFrame(frame)
                    }
                }
            }
        } catch (ex: Exception) {
            onError("Playback Capture selhal: ${ex.localizedMessage}")
        }
    }

    override fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.let { rec ->
            runCatching {
                rec.stop()
                rec.release()
            }
        }
        audioRecord = null
    }
}

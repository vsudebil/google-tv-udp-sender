package com.wled.tvaudiosync.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ProcessedAudioFrame(
    val sampleRaw: Float,
    val sampleSmth: Float,
    val samplePeak: Int,
    val fftBins: ByteArray,
    val zeroCrossingCount: Int,
    val fftMagnitude: Float,
    val fftMajorPeakHz: Float,
    val vuDb: Float
)

/**
 * Nízkolatenční DSP jádro bez externích NDK knihoven.
 * Převádí výstup z android.media.audiofx.Visualizer i 16bit PCM stream z AudioRecord
 * do 16 psychoakustických pásem WLED GEQ (43 Hz – 9.3 kHz).
 */
class WledFftProcessor {

    private val fftSize = 1024
    private val halfSize = fftSize / 2
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val magnitudes = FloatArray(halfSize)
    private val hannWindow = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }

    private val smoothBins = FloatArray(16)
    private var sampleSmth = 0f
    private var longTermAvg = 10f
    private var lastPeakMs = 0L

    private val bandRangesHz = arrayOf(
        43f to 86f,
        86f to 129f,
        129f to 216f,
        216f to 301f,
        301f to 430f,
        430f to 560f,
        560f to 818f,
        818f to 1120f,
        1120f to 1421f,
        1421f to 1895f,
        1895f to 2412f,
        2412f to 3015f,
        3015f to 3704f,
        3704f to 4479f,
        4479f to 7106f,
        7106f to 9259f
    )

    private val pinkCompensation = floatArrayOf(
        0.78f, 0.85f, 0.92f, 0.98f,
        1.04f, 1.10f, 1.16f, 1.22f,
        1.28f, 1.36f, 1.44f, 1.54f,
        1.66f, 1.78f, 1.92f, 2.05f
    )

    @Synchronized
    fun processVisualizerData(
        fftBytes: ByteArray,
        waveformBytes: ByteArray?,
        samplingRateHz: Int,
        gain: Float
    ): ProcessedAudioFrame {
        val binsAvailable = min(halfSize, fftBytes.size / 2)
        magnitudes.fill(0f)
        magnitudes[0] = abs(fftBytes.getOrNull(0)?.toInt() ?: 0).toFloat()

        for (k in 1 until binsAvailable) {
            val re = fftBytes[2 * k].toFloat()
            val im = fftBytes[2 * k + 1].toFloat()
            magnitudes[k] = min(255f, hypot(re, im) * 2.1f)
        }

        var rms = 0f
        var zcr = 0
        if (waveformBytes != null && waveformBytes.isNotEmpty()) {
            var sumSq = 0.0
            var prevSign = 0
            for (i in waveformBytes.indices) {
                val centered = ((waveformBytes[i].toInt() and 0xFF) - 128) / 128.0
                sumSq += centered * centered
                val sign = if (centered >= 0.0) 1 else -1
                if (i > 0 && sign != prevSign) zcr++
                prevSign = sign
            }
            rms = sqrt(sumSq / waveformBytes.size).toFloat()
        } else {
            var sumMag = 0f
            for (k in 1 until binsAvailable) sumMag += magnitudes[k]
            rms = (sumMag / max(1, binsAvailable)) / 255f
        }

        val binWidthHz = samplingRateHz.toFloat() / (binsAvailable * 2).coerceAtLeast(2)
        return finalizeFrame(rms, zcr, binWidthHz, binsAvailable, gain)
    }

    @Synchronized
    fun processPcm16(
        pcm: ShortArray,
        count: Int,
        sampleRateHz: Int,
        gain: Float
    ): ProcessedAudioFrame {
        val n = min(count, fftSize)
        var sumSq = 0.0
        var zcr = 0
        var prevSign = 0

        for (i in 0 until fftSize) {
            val sample = if (i < n) pcm[i] / 32768f else 0f
            if (i < n) {
                sumSq += (sample * sample).toDouble()
                val sign = if (sample >= 0f) 1 else -1
                if (i > 0 && sign != prevSign) zcr++
                prevSign = sign
            }
            real[i] = sample * hannWindow[i]
            imag[i] = 0f
        }

        fftRadix2(real, imag)

        for (k in 0 until halfSize) {
            val mag = hypot(real[k], imag[k]) / (fftSize * 0.14f) * 255f
            magnitudes[k] = min(255f, mag)
        }

        val rms = sqrt(sumSq / max(1, n)).toFloat()
        val binWidthHz = sampleRateHz.toFloat() / fftSize.toFloat()
        return finalizeFrame(rms, zcr, binWidthHz, halfSize, gain)
    }

    private fun finalizeFrame(
        rms: Float,
        zeroCrossings: Int,
        binWidthHz: Float,
        usableBins: Int,
        gain: Float
    ): ProcessedAudioFrame {
        val scaledRms = min(1f, rms * gain * 2.4f)
        val sampleRaw = (scaledRms * 255f).coerceIn(0f, 255f)
        val vuDb = (20.0 * log10(max(0.001f, scaledRms).toDouble())).toFloat().coerceIn(-60f, 0f)

        sampleSmth = sampleSmth * 0.72f + sampleRaw * 0.28f
        longTermAvg = longTermAvg * 0.96f + sampleRaw * 0.04f

        val nowMs = System.currentTimeMillis()
        var samplePeak = 0
        if (sampleRaw > longTermAvg * 1.38f && sampleRaw > 36f && (nowMs - lastPeakMs) > 140L) {
            samplePeak = if (sampleRaw > longTermAvg * 1.75f) 2 else 1
            lastPeakMs = nowMs
        }

        val outBins = ByteArray(16)
        for (b in 0 until 16) {
            val (lowHz, highHz) = bandRangesHz[b]
            val startIdx = (lowHz / binWidthHz).toInt().coerceIn(1, usableBins - 1)
            val endIdx = (highHz / binWidthHz).toInt().coerceIn(startIdx, usableBins - 1)

            var sum = 0f
            var peak = 0f
            for (k in startIdx..endIdx) {
                val v = magnitudes[k]
                sum += v
                if (v > peak) peak = v
            }
            val avg = sum / (endIdx - startIdx + 1)
            val boosted = ((avg * 0.65f + peak * 0.35f) * pinkCompensation[b] * gain)
                .coerceIn(0f, 255f)

            smoothBins[b] = if (boosted > smoothBins[b]) {
                smoothBins[b] * 0.25f + boosted * 0.75f
            } else {
                smoothBins[b] * 0.78f + boosted * 0.22f
            }
            outBins[b] = smoothBins[b].toInt().coerceIn(0, 255).toByte()
        }

        var maxMag = 0f
        var maxIdx = 1
        val maxSearchIdx = min(usableBins - 2, (11025f / binWidthHz).toInt()).coerceAtLeast(1)
        for (k in 1..maxSearchIdx) {
            if (magnitudes[k] > maxMag) {
                maxMag = magnitudes[k]
                maxIdx = k
            }
        }
        val y0 = magnitudes[max(0, maxIdx - 1)]
        val y1 = magnitudes[maxIdx]
        val y2 = magnitudes[min(usableBins - 1, maxIdx + 1)]
        val denom = y0 - 2f * y1 + y2
        val delta = if (denom != 0f) (0.5f * (y0 - y2) / denom).coerceIn(-0.5f, 0.5f) else 0f
        val majorPeakHz = ((maxIdx + delta) * binWidthHz).coerceIn(1f, 11025f)

        return ProcessedAudioFrame(
            sampleRaw = sampleRaw,
            sampleSmth = sampleSmth,
            samplePeak = samplePeak,
            fftBins = outBins,
            zeroCrossingCount = zeroCrossings,
            fftMagnitude = (maxMag * gain * 1.8f).coerceAtMost(1020f),
            fftMajorPeakHz = majorPeakHz,
            vuDb = vuDb
        )
    }

    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wLenRe = cos(angle).toFloat()
            val wLenIm = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

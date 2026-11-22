package com.wled.tvaudiosync.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class SpectrumMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bins = IntArray(16)
    private var vuNormalized = 0f
    private var isPeak = false

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#162032")
    }
    private val rect = RectF()

    fun updateFrame(fftBins: IntArray, vuDb: Float, peak: Boolean) {
        fftBins.copyInto(bins, endIndex = minOf(16, fftBins.size))
        vuNormalized = ((vuDb + 60f) / 60f).coerceIn(0f, 1f)
        isPeak = peak
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val vuHeight = 14f
        val gap = 8f
        val specHeight = (h - vuHeight - 16f).coerceAtLeast(24f)

        val barWidth = (w - gap * 15f) / 16f
        for (i in 0 until 16) {
            val left = i * (barWidth + gap)
            rect.set(left, 0f, left + barWidth, specHeight)
            canvas.drawRoundRect(rect, 4f, 4f, bgPaint)

            val level = (bins[i] / 255f).coerceIn(0f, 1f)
            val barH = level * specHeight
            barPaint.color = when {
                level > 0.85f -> Color.parseColor("#EF4444")
                level > 0.62f -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#38BDF8")
            }
            rect.set(left, specHeight - barH, left + barWidth, specHeight)
            canvas.drawRoundRect(rect, 4f, 4f, barPaint)
        }

        val vuTop = specHeight + 12f
        rect.set(0f, vuTop, w, vuTop + vuHeight)
        canvas.drawRoundRect(rect, 4f, 4f, bgPaint)

        barPaint.color = if (isPeak) Color.parseColor("#F59E0B") else Color.parseColor("#10B981")
        rect.set(0f, vuTop, w * vuNormalized, vuTop + vuHeight)
        canvas.drawRoundRect(rect, 4f, 4f, barPaint)
    }
}

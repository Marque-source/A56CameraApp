package com.a56camera.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Canlı RGB + luma histogramı.
 * [updateHistogram] ile 256 kutuluk kanal dizileri beslenir.
 */
class HistogramView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
    }
    private val lumaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF"); style = Paint.Style.FILL
    }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88FF5252") }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8869F0AE") }
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88448AFF") }

    private var luma = IntArray(BINS)
    private var red = IntArray(BINS)
    private var green = IntArray(BINS)
    private var blue = IntArray(BINS)
    private var maxVal = 1

    fun updateHistogram(r: IntArray, g: IntArray, b: IntArray, l: IntArray) {
        red = r; green = g; blue = b; luma = l
        maxVal = maxOf(1, r.max(), g.max(), b.max(), l.max())
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 12f, 12f, bgPaint)
        drawChannel(canvas, luma, lumaPaint, w, h)
        drawChannel(canvas, red, redPaint, w, h)
        drawChannel(canvas, green, greenPaint, w, h)
        drawChannel(canvas, blue, bluePaint, w, h)
    }

    private fun drawChannel(canvas: Canvas, data: IntArray, paint: Paint, w: Float, h: Float) {
        val barW = w / BINS
        for (i in data.indices) {
            val barH = (data[i].toFloat() / maxVal) * h
            canvas.drawRect(i * barW, h - barH, (i + 1) * barW, h, paint)
        }
    }

    companion object { const val BINS = 256 }
}

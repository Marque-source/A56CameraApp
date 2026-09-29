package com.a56camera.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Kaydırmalı zoom çubuğu (Pixel tarzı).
 * minZoom..maxZoom aralığında dikey/yatay kaydırma ile zoom değeri üretir.
 */
class ZoomSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var minZoom = 0.6f
    var maxZoom = 10f
    var currentZoom = 1f
        private set

    var onZoomChanged: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55FFFFFF"); strokeWidth = 6f
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFCC00"); textSize = 32f; textAlign = Paint.Align.CENTER
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom.coerceIn(minZoom, maxZoom)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                val fraction = 1f - (event.y / height).coerceIn(0f, 1f)
                currentZoom = minZoom + fraction * (maxZoom - minZoom)
                currentZoom = max(minZoom, min(maxZoom, currentZoom))
                onZoomChanged?.invoke(currentZoom)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        canvas.drawLine(cx, 20f, cx, height - 20f, trackPaint)
        val fraction = (currentZoom - minZoom) / (maxZoom - minZoom)
        val thumbY = (height - 20f) - fraction * (height - 40f)
        canvas.drawCircle(cx, thumbY, 14f, thumbPaint)
        canvas.drawText("%.1fx".format(currentZoom), cx, thumbY - 24f, textPaint)
    }
}

package io.github.marquesource.pixora.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.github.marquesource.pixora.camera.GridType

/**
 * Kompozisyon ızgarası katmanı.
 * 3x3, kare ve altın oran ızgaralarını destekler.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    var gridType: GridType = GridType.OFF
        set(value) {
            field = value
            visibility = if (value == GridType.OFF) GONE else VISIBLE
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        when (gridType) {
            GridType.OFF -> return
            GridType.GRID_3X3 -> drawThirds(canvas, w, h)
            GridType.SQUARE -> drawSquare(canvas, w, h)
            GridType.GOLDEN -> drawGolden(canvas, w, h)
        }
    }

    private fun drawThirds(canvas: Canvas, w: Float, h: Float) {
        for (i in 1..2) {
            val x = w * i / 3f
            val y = h * i / 3f
            canvas.drawLine(x, 0f, x, h, paint)
            canvas.drawLine(0f, y, w, y, paint)
        }
    }

    private fun drawSquare(canvas: Canvas, w: Float, h: Float) {
        val side = minOf(w, h)
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        canvas.drawRect(left, top, left + side, top + side, paint)
    }

    private fun drawGolden(canvas: Canvas, w: Float, h: Float) {
        val phi = 0.618f
        canvas.drawLine(w * phi, 0f, w * phi, h, paint)
        canvas.drawLine(w * (1 - phi), 0f, w * (1 - phi), h, paint)
        canvas.drawLine(0f, h * phi, w, h * phi, paint)
        canvas.drawLine(0f, h * (1 - phi), w, h * (1 - phi), paint)
    }
}

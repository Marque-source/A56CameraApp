package com.a56camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.max

/**
 * Yumuşak tonemap operatörü.
 *
 * Samsung stok kameranın gece moduna benzer:
 *  - "Lifted blacks": gölgeler ezilmez, karanlık alanlarda detay korunur.
 *  - S-curve kontrastı düşük tutulur.
 *  - Highlights yumuşak şekilde sıkıştırılır.
 */
class SoftTonemapper(
    private val shadowLift: Float = 0.06f,
    private val highlightCompression: Float = 0.75f,
    private val contrast: Float = 1.05f,
    private val saturation: Float = 1.0f
) {

    private val lutSize = 256
    private val lutR = FloatArray(lutSize)
    private val lutG = FloatArray(lutSize)
    private val lutB = FloatArray(lutSize)
    private var lutReady = false

    /**
     * Per-pixel yumuşak tonemap (detaylı).
     */
    fun applyDetailed(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        pixels.indices.forEach { i ->
            val px = pixels[i]
            var r = Color.red(px) / 255f
            var g = Color.green(px) / 255f
            var b = Color.blue(px) / 255f

            val maxC = max(r, max(g, b))

            // 1) Highlight compression
            val mappedMax = softCurve(maxC)

            // 2) Scale channels
            if (maxC > 0.001f) {
                val scale = mappedMax / maxC
                r *= scale
                g *= scale
                b *= scale
            }

            // 3) Shadow lift
            r = lifted(r, shadowLift)
            g = lifted(g, shadowLift)
            b = lifted(b, shadowLift)

            // 4) Contrast
            r = applyContrast(r, contrast)
            g = applyContrast(g, contrast)
            b = applyContrast(b, contrast)

            // 5) Saturation
            val avg = (r + g + b) / 3f
            r = avg + (r - avg) * saturation
            g = avg + (g - avg) * saturation
            b = avg + (b - avg) * saturation

            pixels[i] = Color.rgb(
                (r * 255f).toInt().coerceIn(0, 255),
                (g * 255f).toInt().coerceIn(0, 255),
                (b * 255f).toInt().coerceIn(0, 255)
            )
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /**
     * Hızlı LUT tabanlı uygulama (donanım hızı).
     */
    fun applyFast(bitmap: Bitmap) {
        Log.i("SoftTonemapper", "⚡ [applyFast] ÇAĞRILDI")
        buildLut()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        pixels.indices.forEach { i ->
            val px = pixels[i]
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)

            val nr = (lutR[r] * 255f).toInt().coerceIn(0, 255)
            val ng = (lutG[g] * 255f).toInt().coerceIn(0, 255)
            val nb = (lutB[b] * 255f).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(nr, ng, nb)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun softCurve(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        val compressed = v * (1f + v / (highlightCompression * highlightCompression)) / (1f + v)
        return compressed.coerceIn(0f, 1f)
    }

    private fun lifted(x: Float, lift: Float): Float {
        return if (x < 0.25f) {
            x + lift * (1f - x * 4f)
        } else {
            x
        }.coerceIn(0f, 1f)
    }

    private fun applyContrast(x: Float, c: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return ((v - 0.5f) * c + 0.5f).coerceIn(0f, 1f)
    }

    private fun buildLut() {
        if (lutReady) return
        for (i in 0 until lutSize) {
            val v = i / 255f
            val maxC = v
            val mappedMax = softCurve(maxC)
            val scale = if (maxC > 0.001f) mappedMax / maxC else 1f
            var c = v * scale
            c = lifted(c, shadowLift)
            c = applyContrast(c, contrast)
            lutR[i] = c
            lutG[i] = c
            lutB[i] = c
        }
        lutReady = true
    }

    companion object {
        fun nightPreset(): SoftTonemapper = SoftTonemapper(
            shadowLift = 0.12f,
            highlightCompression = 0.85f,
            contrast = 1.02f,
            saturation = 0.95f
        )

        fun dayPreset(): SoftTonemapper = SoftTonemapper(
            shadowLift = 0.04f,
            highlightCompression = 0.70f,
            contrast = 1.08f,
            saturation = 1.05f
        )
    }
}

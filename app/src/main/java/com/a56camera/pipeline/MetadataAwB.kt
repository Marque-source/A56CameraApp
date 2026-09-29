package com.a56camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CaptureResult
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * CaptureResult metadata ve Bitmap histogramından WB düzeltmesi üreten modül.
 *
 * Özellikle karmaşık aydınlatmalı gece sahnelerinde (sokak lambası + mavi cam,
 * tungsten + floresan) tek AWB modu yetersiz kalır.
 */
class MetadataAwB {

    data class WbEstimate(
        val rGain: Float,
        val gGain: Float = 1.0f,
        val bGain: Float,
        val temperature: Float, // Kelvin
        val tint: Float         // [-1, +1] yeşil-magenta ekseni
    )

    private val tag = "MetadataAwB"

    /**
     * Ana API: önce metadata’yı kullan, sonra histogramdan düzelt.
     */
    fun estimate(
        bitmap: Bitmap,
        result: CaptureResult? = null,
        metadataWeight: Float = 0.45f
    ): WbEstimate {
        val metaWb = result?.let { extractMetadataWb(it) }
        val histWb = estimateFromHistogram(bitmap)

        return if (metaWb == null) {
            histWb
        } else {
            blend(metaWb, histWb, metadataWeight)
        }
    }

    /**
     * CaptureResult’dan color correction gains ve transform kullanır.
     */
    private fun extractMetadataWb(result: CaptureResult): WbEstimate {
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)

        val r = gains?.getComponent(0) ?: 1.0f
        val g = gains?.getComponent(1) ?: 1.0f
        val b = gains?.getComponent(2) ?: 1.0f

        val temp = estimateTemperatureFromCct(gains)
        val tint = 0f

        Log.d(tag, "Metadata WB: R=$r, G=$g, B=$b, T=$temp, tint=$tint")
        return WbEstimate(r / g.coerceAtLeast(0.001f), 1.0f, b / g.coerceAtLeast(0.001f), temp, tint)
    }

    /**
     * Histogram tabanlı gri dünya tahmini (grid örneklemeli, sıfır bellek tahsisi).
     */
    fun estimateFromHistogram(bitmap: Bitmap): WbEstimate {
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var count = 0

        val w = bitmap.width
        val h = bitmap.height
        val stepX = maxOf(1, w / 64)
        val stepY = maxOf(1, h / 64)

        for (y in 0 until h step stepY) {
            for (x in 0 until w step stepX) {
                val px = bitmap.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                val saturation = if (maxC == 0) 0f else (maxC - minC) / maxC.toFloat()

                if (maxC in 16..240 && saturation < 0.85f) {
                    rSum += r
                    gSum += g
                    bSum += b
                    count++
                }
            }
        }

        if (count == 0) return WbEstimate(1.0f, 1.0f, 1.0f, 5000f, 0f)

        val rAvg = (rSum / count).toFloat()
        val gAvg = (gSum / count).toFloat()
        val bAvg = (bSum / count).toFloat()

        val gainR = gAvg / rAvg.coerceAtLeast(1f)
        val gainB = gAvg / bAvg.coerceAtLeast(1f)

        val temp = temperatureFromGains(gainR, gainB)
        val tint = (gAvg - (rAvg + bAvg) / 2f) / 255f * 0.5f

        Log.d(tag, "Histogram WB: R=$gainR, B=$gainB, T=$temp, tint=$tint")
        return WbEstimate(gainR, 1.0f, gainB, temp, tint)
    }

    private fun blend(
        a: WbEstimate,
        b: WbEstimate,
        weightA: Float
    ): WbEstimate {
        val w = weightA.coerceIn(0f, 1f)
        return WbEstimate(
            rGain = a.rGain * w + b.rGain * (1 - w),
            gGain = 1.0f,
            bGain = a.bGain * w + b.bGain * (1 - w),
            temperature = a.temperature * w + b.temperature * (1 - w),
            tint = a.tint * w + b.tint * (1 - w)
        )
    }

    fun apply(bitmap: Bitmap, wb: WbEstimate) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        pixels.indices.forEach { i ->
            val px = pixels[i]
            val r = Color.red(px)
            val g = Color.green(px)
            val b = Color.blue(px)

            val nr = (r * wb.rGain).toInt().coerceIn(0, 255)
            val ng = (g * wb.gGain).toInt().coerceIn(0, 255)
            val nb = (b * wb.bGain).toInt().coerceIn(0, 255)

            pixels[i] = Color.rgb(nr, ng, nb)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun temperatureFromGains(rGain: Float, bGain: Float): Float {
        val ratio = rGain / bGain.coerceAtLeast(0.001f)
        return (6500f - (ratio - 1f) * 2500f).coerceIn(2200f, 10000f)
    }

    private fun estimateTemperatureFromCct(gains: android.hardware.camera2.params.RggbChannelVector?): Float {
        if (gains == null) return 5000f
        val r = gains.getComponent(0)
        val b = gains.getComponent(2)
        return temperatureFromGains(r, b)
    }
}

package com.a56camera.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import kotlin.math.log2

/**
 * AE ön ölçüm sonucundan EV (Exposure Value) hesaplar ve sahneye özel
 * ISO, pozlama süresi (nanosaniye) ve burst kare sayısını belirler.
 */
object SceneExposureStrategy {

    data class Settings(
        val iso: Int,
        val exposureNs: Long,
        val frameCount: Int
    )

    private val DEFAULT_DAY = Settings(100, 10_000_000L, 9)

    fun estimateFromPreview(result: TotalCaptureResult?): Settings {
        if (result == null) return DEFAULT_DAY

        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val aperture = 1.8f // A56 ana kamera f/1.8

        val ev = calculateEV(iso, exp, aperture)

        return when {
            ev > 12 -> Settings(50, 5_000_000L, 7)    // parlak gündüz
            ev > 8 -> Settings(100, 10_000_000L, 9)   // normal gündüz
            ev > 5 -> Settings(200, 20_000_000L, 11)  // loş / gün batımı
            ev > 2 -> Settings(400, 40_000_000L, 13)  // akşam / iç mekan
            else -> Settings(800, 80_000_000L, 15)     // gece
        }
    }

    private fun calculateEV(iso: Int, exposureNs: Long, aperture: Float): Float {
        val t = exposureNs / 1_000_000_000.0
        val n2 = aperture * aperture
        val s = iso.toFloat()
        return (log2(n2 / t) - log2(s / 100f)).toFloat()
    }
}

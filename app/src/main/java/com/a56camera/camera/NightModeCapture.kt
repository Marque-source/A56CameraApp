package com.a56camera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import android.view.Surface

/**
 * Gece modu — düşük ışıkta çok kareli uzun pozlamalı çekim.
 *
 * Yaklaşım:
 *  1. Sahne parlaklığını AE sonucundan (SENSOR_SENSITIVITY + EXPOSURE_TIME) okuyup
 *     düşük ışık olup olmadığına karar verilir.
 *  2. Düşük ışıkta manuel uzun pozlama + yüksek ISO ile bir dizi (burst) kare çekilir.
 *  3. Gürültü azaltma, kenar ve sıcak piksel düzeltmesi HIGH_QUALITY yapılır.
 *
 * Kareler JPEG olarak alınır; en son (en uzun birikimli) kare kaydedilir. Gerçek
 * çok kare birleştirme (stacking) donanım hızlandırma gerektirdiğinden burada
 * ardışık uzun pozlama ile parlaklık artışı hedeflenir.
 */
class NightModeCapture {

    /** Son önizleme sonucundan düşük ışık olup olmadığını tahmin eder. */
    fun isLowLight(result: TotalCaptureResult?): Boolean {
        val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: return false
        val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return false
        // Yüksek ISO ve uzun pozlama → karanlık sahne
        return iso >= 800 || exp >= 33_000_000L // ~1/30 sn'den yavaş
    }

    /**
     * Gece modu burst çekimi yapar.
     * @param frameCount birleştirilecek kare sayısı
     * @param exposureNanos kare başına pozlama süresi
     * @param iso sensör hassasiyeti
     */
    fun capture(
        device: CameraDevice,
        ch: CameraCharacteristics,
        session: CaptureSessionManager,
        jpegSurface: Surface,
        frameCount: Int = 6,
        exposureNanos: Long = 250_000_000L, // 1/4 sn
        iso: Int = 1600,
        jpegOrientation: Int,
        onProgress: (Int, Int) -> Unit,
        onCompleted: () -> Unit,
        onFailed: () -> Unit
    ) {
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val safeExp = expRange?.let { exposureNanos.coerceIn(it.lower, it.upper) } ?: exposureNanos
        val safeIso = isoRange?.let { iso.coerceIn(it.lower, it.upper) } ?: iso

        val requests = ArrayList<CaptureRequest>(frameCount)
        repeat(frameCount) {
            val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(jpegSurface)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, safeExp)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            requests.add(b.build())
        }

        var done = 0
        try {
            session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
                ) {
                    done++
                    onProgress(done, frameCount)
                    if (done >= frameCount) onCompleted()
                }
                override fun onCaptureFailed(
                    s: CameraCaptureSession, r: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    Log.e(TAG, "Gece modu kare hatası")
                    onFailed()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Gece modu burst hatası", e)
            onFailed()
        }
    }

    companion object { private const val TAG = "NightModeCapture" }
}

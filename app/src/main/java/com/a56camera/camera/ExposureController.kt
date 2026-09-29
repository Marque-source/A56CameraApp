package com.a56camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import com.a56camera.utils.Camera2Utils

/**
 * Pozlama kontrolü — otomatik (AE) ve manuel (ISO + perde hızı).
 */
class ExposureController {

    /** null = otomatik. */
    var manualIso: Int? = null
    var manualExposureNanos: Long? = null

    /** AE pozlama telafisi (adım cinsinden index). */
    var exposureCompensation: Int = 0

    /** AE kilidi. */
    var aeLocked: Boolean = false

    // Cihaz aralıkları
    var isoRange: Range<Int> = Range(50, 3200)
        private set
    var exposureRange: Range<Long> = Range(250_000L, 30_000_000_000L)
        private set
    var evRange: Range<Int> = Range(-3, 3)
        private set
    var evStep: Double = 1.0
        private set

    fun readRanges(ch: CameraCharacteristics) {
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { exposureRange = it }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evRange = it }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
            evStep = it.numerator.toDouble() / it.denominator
        }
    }

    fun isManual(): Boolean = manualIso != null && manualExposureNanos != null

    fun reset() {
        manualIso = null; manualExposureNanos = null; exposureCompensation = 0; aeLocked = false
    }

    /** Verilen CaptureRequest.Builder'a pozlama ayarlarını korumalı olarak uygular. */
    fun applyTo(builder: CaptureRequest.Builder) {
        try {
            if (isManual()) {
                try { builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF) } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso!!.coerceIn(isoRange.lower, isoRange.upper)) } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureNanos!!.coerceIn(exposureRange.lower, exposureRange.upper)) } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, manualExposureNanos!!) } catch (_: Throwable) {}
            } else {
                try { builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) } catch (_: Throwable) {}
                try {
                    builder.set(
                        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                        exposureCompensation.coerceIn(evRange.lower, evRange.upper)
                    )
                } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.w("ExposureController", "Pozlama ayarı uygulanamadı: ${e.message}")
        }
    }

    // ---------- Etiketler ----------
    fun isoLabel(): String = manualIso?.toString() ?: "OTO"
    fun shutterLabel(): String =
        manualExposureNanos?.let { Camera2Utils.formatExposureTime(it) } ?: "OTO"
    fun evLabel(): String {
        val ev = exposureCompensation * evStep
        return if (ev == 0.0) "0" else (if (ev > 0) "+%.1f" else "%.1f").format(ev)
    }

    // ---------- Slider dönüşümleri ----------
    fun setIsoFromProgress(p: Float) {
        manualIso = if (p <= 0f) null else Camera2Utils.progressToIso(p, isoRange)
    }
    fun isoProgress(): Float = manualIso?.let { Camera2Utils.isoToProgress(it, isoRange) } ?: 0f

    fun setShutterFromProgress(p: Float) {
        manualExposureNanos = if (p <= 0f) null else Camera2Utils.progressToExposure(p, exposureRange)
    }
    fun shutterProgress(): Float =
        manualExposureNanos?.let { Camera2Utils.exposureToProgress(it, exposureRange) } ?: 0f
}

package com.a56camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
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

    /**
     * Telefon üçüncü taraf uygulamalara elle ISO/enstantane ayarı izni veriyor mu?
     * Galaxy A56 gibi "LIMITED" donanımlarda SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME istek
     * anahtarları hiç sunulmuyor; o durumda AE'yi kapatmak görüntüyü bozar, bu yüzden kapatılmaz.
     */
    var manualSupported: Boolean = false
        internal set

    fun readRanges(ch: CameraCharacteristics) {
        val keys = ch.availableCaptureRequestKeys
        manualSupported = keys.contains(CaptureRequest.SENSOR_SENSITIVITY) &&
            keys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME)
        if (!manualSupported) { manualIso = null; manualExposureNanos = null }
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { isoRange = it }
        ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { exposureRange = it }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { evRange = it }
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.let {
            evStep = it.numerator.toDouble() / it.denominator
        }
    }

    /**
     * ISO veya perde hızından en az biri elle ayarlandıysa manuel pozlama devrededir.
     * Eskiden ikisi birden ayarlanmadıkça manuel değer tamamen yok sayılıyordu (ör. yalnızca ISO
     * seçmek hiçbir şey değiştirmiyordu). Ayarlanmayan değer, otomatik pozlamanın son ölçtüğü
     * ışık miktarını koruyacak şekilde hesaplanır.
     */
    fun isManual(): Boolean = manualSupported && (manualIso != null || manualExposureNanos != null)

    /** Otomatik pozlamanın son ölçümü (manuel moda geçmeden önceki sahne parlaklığı). */
    private var meteredIso: Int? = null
    private var meteredExposureNs: Long? = null

    /** Önizleme sonuçlarından otomatik pozlama ölçümünü kaydeder (yalnızca otomatik moddayken). */
    fun updateMetering(result: CaptureResult) {
        if (isManual()) return
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { meteredIso = it }
        result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { meteredExposureNs = it }
    }

    /** Uygulanacak (ISO, süre) çifti; ayarlanmamış olan ölçümden türetilir. */
    fun effectiveManualValues(): Pair<Int, Long> {
        val targetLight = (meteredIso ?: 100).toDouble() * (meteredExposureNs ?: 16_666_666L).toDouble()
        val iso = manualIso
        val exp = manualExposureNanos
        return when {
            iso != null && exp != null -> iso to exp
            iso != null -> iso to (targetLight / iso).toLong().coerceIn(exposureRange.lower, exposureRange.upper)
            exp != null -> (targetLight / exp).toInt().coerceIn(isoRange.lower, isoRange.upper) to exp
            else -> (meteredIso ?: 100) to (meteredExposureNs ?: 16_666_666L)
        }.let { (i, e) -> i.coerceIn(isoRange.lower, isoRange.upper) to e.coerceIn(exposureRange.lower, exposureRange.upper) }
    }

    fun reset() {
        manualIso = null; manualExposureNanos = null; exposureCompensation = 0; aeLocked = false
    }

    /**
     * Verilen CaptureRequest.Builder'a pozlama ayarlarını korumalı olarak uygular.
     * @param forPreview önizleme isteği mi: uzun pozlamalar (ör. 30 sn) önizlemeyi dondurmasın diye
     *   önizlemede süre 1/10 sn ile sınırlanır ve parlaklık ISO ile telafi edilir. Çekim gerçek değeri kullanır.
     */
    fun applyTo(builder: CaptureRequest.Builder, forPreview: Boolean = false) {
        try {
            if (isManual()) {
                var (iso, exp) = effectiveManualValues()
                if (forPreview && exp > PREVIEW_MAX_EXPOSURE_NS) {
                    iso = (iso.toDouble() * exp / PREVIEW_MAX_EXPOSURE_NS).toInt().coerceIn(isoRange.lower, isoRange.upper)
                    exp = PREVIEW_MAX_EXPOSURE_NS
                }
                try { builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF) } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso) } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp) } catch (_: Throwable) {}
                try { builder.set(CaptureRequest.SENSOR_FRAME_DURATION, exp) } catch (_: Throwable) {}
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
    fun isoLabel(): String = when {
        manualIso != null -> manualIso.toString()
        isManual() -> "OTO (${effectiveManualValues().first})"
        else -> "OTO"
    }
    fun shutterLabel(): String = when {
        manualExposureNanos != null -> Camera2Utils.formatExposureTime(manualExposureNanos!!)
        isManual() -> "OTO (${Camera2Utils.formatExposureTime(effectiveManualValues().second)})"
        else -> "OTO"
    }
    fun evLabel(): String {
        val ev = exposureCompensation * evStep
        return if (ev == 0.0) "0" else (if (ev > 0) "+%.1f" else "%.1f").format(ev)
    }

    // ---------- Slider dönüşümleri ----------
    fun setIsoFromProgress(p: Float) {
        manualIso = if (p <= 0f || !manualSupported) null else Camera2Utils.progressToIso(p, isoRange)
    }
    fun isoProgress(): Float = manualIso?.let { Camera2Utils.isoToProgress(it, isoRange) } ?: 0f

    fun setShutterFromProgress(p: Float) {
        manualExposureNanos = if (p <= 0f || !manualSupported) null else Camera2Utils.progressToExposure(p, exposureRange)
    }
    fun shutterProgress(): Float =
        manualExposureNanos?.let { Camera2Utils.exposureToProgress(it, exposureRange) } ?: 0f

    companion object {
        /** Pro modda önizlemenin kullanabileceği en uzun pozlama (1/10 sn). */
        const val PREVIEW_MAX_EXPOSURE_NS = 100_000_000L
    }
}

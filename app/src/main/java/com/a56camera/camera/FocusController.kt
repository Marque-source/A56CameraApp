package com.a56camera.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import com.a56camera.utils.Camera2Utils

/**
 * Odak kontrolü — otomatik odak (AF), dokunarak odak/pozlama ölçümü ve manuel odak.
 *
 * AF durum makinesi CaptureResult.CONTROL_AF_STATE üzerinden izlenir:
 * INACTIVE → ACTIVE_SCAN → FOCUSED_LOCKED / NOT_FOCUSED_LOCKED
 */
class FocusController {

    /** null = otomatik odak. 0f = sonsuz, artan değer = daha yakın. */
    var manualFocusDistance: Float? = null

    /** AF/AE kilidi (uzun basma): dokunulan noktada odak ve pozlama sabit kalır. */
    var afLocked: Boolean = false

    /**
     * Dokunarak seçilen ölçüm bölgesi. null değilken tekrarlayan istek AUTO odak + bu bölgeyle
     * gönderilir; böylece odak dokunulan yerde kalır. Eskiden tetikten hemen sonra sürekli
     * odağa (bölgesiz) dönülüyor ve dokunmanın etkisi anında kayboluyordu.
     */
    var tapRegion: MeteringRectangle? = null
        private set

    var minFocusDistance: Float = 0f
        private set
    private var maxRegionsAf: Int = 0
    private var maxRegionsAe: Int = 0
    var activeArray: Rect? = null
        private set
    var sensorOrientation: Int = 90
        private set
    var isFront: Boolean = false
        private set

    /** Kamerada otomatik odak var mı? (A56'da ön ve ultra geniş kamera sabit odaklı.) */
    var hasAf: Boolean = true
        private set

    /** Elle odak mesafesi (LENS_FOCUS_DISTANCE) uygulamalara açık mı? */
    var manualFocusSupported: Boolean = false
        private set

    fun readCharacteristics(ch: CameraCharacteristics) {
        minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        maxRegionsAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxRegionsAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        isFront = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        hasAf = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) == true
        manualFocusSupported = hasAf && minFocusDistance > 0f &&
            ch.availableCaptureRequestKeys.contains(CaptureRequest.LENS_FOCUS_DISTANCE)
        if (!manualFocusSupported) manualFocusDistance = null
        clearTap()
    }

    fun isManual(): Boolean = manualFocusSupported && manualFocusDistance != null

    fun reset() { manualFocusDistance = null; clearTap() }

    fun clearTap() { tapRegion = null; afLocked = false }

    /** Sürekli/tekrarlayan isteğe odak modunu uygular. */
    fun applyTo(builder: CaptureRequest.Builder, forVideo: Boolean = false) {
        val tap = tapRegion
        if (!hasAf) {
            // Sabit odaklı kamera: desteklenmeyen AF modu istemek yerine AF kapalı; dokunma yalnızca pozlamayı ölçer.
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            if (tap != null && maxRegionsAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(tap))
            return
        }
        when {
            isManual() -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance!!.coerceIn(0f, minFocusDistance))
            }
            tap != null -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (maxRegionsAf > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(tap))
                if (maxRegionsAe > 0) builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(tap))
            }
            else -> builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (forVideo) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    /**
     * Dokunulan ekran noktasından AF+AE ölçüm bölgesini hesaplar ve saklar.
     * @return bölge kurulabildiyse true
     */
    fun setTapPoint(
        x: Float, y: Float,
        content: android.graphics.RectF,
        zoomRatio: Float,
        streamAspect: Float,
        lock: Boolean
    ): Boolean {
        val array = activeArray ?: return false
        if (maxRegionsAf <= 0 && maxRegionsAe <= 0) return false
        val point = Camera2Utils.mapViewToSensor(
            x, y, content, sensorOrientation, isFront, array, zoomRatio, streamAspect
        )
        // Görünen alanın ~%10'u kadar kare bölge
        val half = (minOf(array.width(), array.height()) * 0.10f / zoomRatio.coerceAtLeast(1f) / 2f).toInt()
            .coerceAtLeast(16)
        val region = Rect(
            (point.x - half).coerceAtLeast(array.left),
            (point.y - half).coerceAtLeast(array.top),
            (point.x + half).coerceAtMost(array.right - 1),
            (point.y + half).coerceAtMost(array.bottom - 1)
        )
        tapRegion = MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)
        afLocked = lock
        return true
    }

    /** AF taramasını başlatır (tek seferlik tetik). */
    fun triggerAf(builder: CaptureRequest.Builder) {
        if (!hasAf) return
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
    }

    /** AF tetiğini boşa alır (repeating isteğe geri dönmeden önce). */
    fun clearAfTrigger(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
    }

    /** AF durumundan odaklanmanın tamamlanıp tamamlanmadığını döndürür. */
    fun isFocusLocked(afState: Int?): Boolean =
        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED

    // ---------- Etiketler / slider ----------
    fun focusLabel(): String = if (isManual()) "MF" else "AF"

    /** 0..1 ilerleme → odak mesafesi (0=sonsuz, min=makro). */
    fun setFocusFromProgress(p: Float) {
        manualFocusDistance = if (p <= 0f || !manualFocusSupported) null else p * minFocusDistance
    }
    fun focusProgress(): Float =
        manualFocusDistance?.let { if (minFocusDistance > 0) it / minFocusDistance else 0f } ?: 0f
}

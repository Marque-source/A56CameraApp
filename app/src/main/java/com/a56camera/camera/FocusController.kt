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

    /** AF kilidi (uzun basma). */
    var afLocked: Boolean = false

    var minFocusDistance: Float = 0f
        private set
    private var maxRegions: Int = 0
    private var activeArray: Rect? = null

    fun readCharacteristics(ch: CameraCharacteristics) {
        minFocusDistance = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        maxRegions = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    }

    fun isManual(): Boolean = manualFocusDistance != null

    fun reset() { manualFocusDistance = null; afLocked = false }

    /** Sürekli önizleme isteğine odak modunu uygular. */
    fun applyTo(builder: CaptureRequest.Builder, forVideo: Boolean = false) {
        if (isManual()) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance!!.coerceIn(0f, minFocusDistance))
        } else {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                if (forVideo) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
        }
    }

    /**
     * Dokunulan ekran noktasından AF+AE ölçüm bölgeleri oluşturur ve isteğe uygular.
     * AF tetiklemesi ayrıca [triggerAf] ile yapılmalıdır.
     */
    fun setMeteringPoint(
        builder: CaptureRequest.Builder,
        x: Float, y: Float,
        previewWidth: Int, previewHeight: Int,
        cropRegion: Rect?
    ) {
        val array = activeArray ?: return
        if (maxRegions <= 0) return
        val point = Camera2Utils.mapPreviewToSensor(x, y, previewWidth, previewHeight, array, cropRegion)
        val half = (array.width() * 0.075).toInt()
        val region = Rect(
            (point.x - half).coerceAtLeast(array.left),
            (point.y - half).coerceAtLeast(array.top),
            (point.x + half).coerceAtMost(array.right),
            (point.y + half).coerceAtMost(array.bottom)
        )
        val mr = arrayOf(MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX))
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, mr)
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, mr)
    }

    /** AF taramasını başlatır (tek seferlik tetik). */
    fun triggerAf(builder: CaptureRequest.Builder) {
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
        manualFocusDistance = if (p <= 0f) null else p * minFocusDistance
    }
    fun focusProgress(): Float =
        manualFocusDistance?.let { if (minFocusDistance > 0) it / minFocusDistance else 0f } ?: 0f
}

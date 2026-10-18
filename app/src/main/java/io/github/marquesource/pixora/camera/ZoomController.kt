package io.github.marquesource.pixora.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range

/**
 * Zoom kontrolü.
 *
 * API 30+ cihazlarda CONTROL_ZOOM_RATIO ile pürüzsüz oransal zoom kullanılır
 * (0.6x ultra-geniş dahil). Daha eski cihazlarda SCALER_CROP_REGION ile dijital
 * kırpma yapılır.
 */
class ZoomController {

    private var activeArray: Rect? = null
    private var maxDigitalZoom: Float = 1f
    private var zoomRatioRange: Range<Float>? = null

    var currentRatio: Float = 1f
        private set

    fun readCharacteristics(ch: CameraCharacteristics) {
        activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxDigitalZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            zoomRatioRange = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        }
    }

    fun minZoom(): Float = zoomRatioRange?.lower ?: 1f
    fun maxZoom(): Float = zoomRatioRange?.upper ?: maxDigitalZoom

    fun setZoom(ratio: Float) {
        currentRatio = ratio.coerceIn(minZoom(), maxZoom())
    }

    /** Pinch jestinden gelen ölçek faktörüyle zoom'u artır/azalt. */
    fun scale(factor: Float) {
        setZoom(currentRatio * factor)
    }

    fun applyTo(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioRange != null) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentRatio)
        } else {
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegionForRatio(currentRatio))
        }
    }

    /** SCALER_CROP_REGION için verilen orana karşılık gelen kırpma dikdörtgeni. */
    private fun cropRegionForRatio(ratio: Float): Rect? {
        val array = activeArray ?: return null
        val r = ratio.coerceIn(1f, maxDigitalZoom)
        val cropW = (array.width() / r).toInt()
        val cropH = (array.height() / r).toInt()
        val left = (array.width() - cropW) / 2
        val top = (array.height() - cropH) / 2
        return Rect(left, top, left + cropW, top + cropH)
    }

    /** Geçerli kırpma bölgesi (metering hesaplamalarında kullanılır). */
    fun currentCropRegion(): Rect? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatioRange != null) null
        else cropRegionForRatio(currentRatio)

    fun label(): String = "%.1fx".format(currentRatio)
}

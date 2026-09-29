package com.a56camera.camera

import android.hardware.camera2.CaptureRequest
import com.a56camera.utils.Camera2Utils

/**
 * Beyaz dengesi kontrolü — otomatik (AWB), hazır ayarlar ve manuel (Kelvin).
 *
 * Manuel modda AWB kapatılır ve COLOR_CORRECTION_MODE_TRANSFORM_MATRIX ile
 * Kelvin'den hesaplanan RGGB kazançları uygulanır.
 */
class WhiteBalanceController {

    /** Hazır AWB modları. */
    enum class Preset(val awbMode: Int, val label: String) {
        AUTO(CaptureRequest.CONTROL_AWB_MODE_AUTO, "OTO"),
        INCANDESCENT(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "Ampul"),
        FLUORESCENT(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, "Floresan"),
        WARM_FLUORESCENT(CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT, "Sıcak Flrs"),
        DAYLIGHT(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "Gün Işığı"),
        CLOUDY(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "Bulutlu"),
        TWILIGHT(CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, "Alacakaranlık"),
        SHADE(CaptureRequest.CONTROL_AWB_MODE_SHADE, "Gölge")
    }

    var preset: Preset = Preset.AUTO
    /** null = hazır ayar/otomatik kullan; değer = manuel Kelvin. */
    var manualKelvin: Int? = null

    val kelvinRange = 2300..7500

    fun isManual(): Boolean = manualKelvin != null

    fun reset() { preset = Preset.AUTO; manualKelvin = null }

    fun applyTo(builder: CaptureRequest.Builder) {
        val kelvin = manualKelvin
        if (kelvin != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            builder.set(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
            )
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, Camera2Utils.kelvinToRggb(kelvin))
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, preset.awbMode)
        }
    }

    fun label(): String = manualKelvin?.let { "${it}K" } ?: preset.label

    fun setKelvinFromProgress(p: Float) {
        manualKelvin = if (p <= 0f) null else
            (kelvinRange.first + p * (kelvinRange.last - kelvinRange.first)).toInt()
    }
    fun kelvinProgress(): Float = manualKelvin?.let {
        (it - kelvinRange.first).toFloat() / (kelvinRange.last - kelvinRange.first)
    } ?: 0f
}

package io.github.marquesource.pixora.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import kotlin.math.abs

/**
 * Beyaz dengesi kontrolü — otomatik (AWB) ve Kelvin kaydırıcısı.
 *
 * Kaydırıcıdaki Kelvin değeri, telefonun kendi kalibre ettiği en yakın AWB hazır ayarına
 * (Ampul, Floresan, Gün ışığı, Bulutlu, Gölge...) eşlenir.
 *
 * Eskiden Kelvin'den "kara cisim" formülüyle RGGB kazancı hesaplanıp COLOR_CORRECTION_GAINS'e
 * doğrudan yazılıyordu. Ama bu alan ham sensör kazancıdır (gün ışığında kırmızı ≈ 2); formül
 * ~1.0 veriyordu ve renk dönüşüm matrisi de hiç ayarlanmıyordu → fotoğraflar yeşil çıkıyordu.
 */
class WhiteBalanceController {

    /** Hazır AWB modları ve yaklaşık renk sıcaklıkları. */
    enum class Preset(val awbMode: Int, val label: String, val kelvin: Int) {
        AUTO(CaptureRequest.CONTROL_AWB_MODE_AUTO, "OTO", 0),
        INCANDESCENT(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "Ampul", 2700),
        WARM_FLUORESCENT(CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT, "Sıcak Flrs", 3000),
        FLUORESCENT(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, "Floresan", 4000),
        DAYLIGHT(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "Gün Işığı", 5500),
        CLOUDY(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "Bulutlu", 6500),
        TWILIGHT(CaptureRequest.CONTROL_AWB_MODE_TWILIGHT, "Alacakaranlık", 7000),
        SHADE(CaptureRequest.CONTROL_AWB_MODE_SHADE, "Gölge", 7500)
    }

    var preset: Preset = Preset.AUTO
    /** null = otomatik; değer = kullanıcının seçtiği Kelvin (en yakın hazır ayara eşlenir). */
    var manualKelvin: Int? = null

    val kelvinRange = 2300..7500

    /** Kameranın desteklediği AWB modları (bilinmiyorsa hepsi varsayılır). */
    private var availableModes: IntArray? = null

    fun readCharacteristics(ch: CameraCharacteristics) {
        availableModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
    }

    fun isManual(): Boolean = manualKelvin != null

    fun reset() { preset = Preset.AUTO; manualKelvin = null }

    /** Kelvin değerine en yakın, kamerada desteklenen hazır ayar. */
    fun presetForKelvin(kelvin: Int): Preset {
        val supported = Preset.values().filter { it != Preset.AUTO &&
            (availableModes?.contains(it.awbMode) ?: true) }
        return supported.minByOrNull { abs(it.kelvin - kelvin) } ?: Preset.AUTO
    }

    fun applyTo(builder: CaptureRequest.Builder) {
        val mode = manualKelvin?.let { presetForKelvin(it).awbMode } ?: preset.awbMode
        builder.set(CaptureRequest.CONTROL_AWB_MODE, mode)
    }

    fun label(): String = manualKelvin?.let { "${it}K · ${presetForKelvin(it).label}" } ?: preset.label

    fun setKelvinFromProgress(p: Float) {
        manualKelvin = if (p <= 0f) null else
            (kelvinRange.first + p * (kelvinRange.last - kelvinRange.first)).toInt()
    }
    fun kelvinProgress(): Float = manualKelvin?.let {
        (it - kelvinRange.first).toFloat() / (kelvinRange.last - kelvinRange.first)
    } ?: 0f
}

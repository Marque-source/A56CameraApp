package com.a56camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.util.Rational
import kotlin.math.roundToInt

object ExposureBracketingStrategy {

    data class BracketSettings(
        val baseIso: Int,
        val baseExposureNs: Long,
        val evShifts: List<Float>
    )

    fun computeForScene(result: TotalCaptureResult?, forceNight: Boolean = false): BracketSettings {
        val rawIso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val rawExp = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val isNight = forceNight || rawIso > 800 || rawExp > 40_000_000L

        // Sony IMX906 2.0µm süper pikselleri ve OIS için optimize edilmiş taban:
        // Karanlık sahnelerde ağaçları ve binaları beslemek için yeterli foton toplansın (45ms - 90ms).
        // ISO'yu 800 - 2400 aralığında tutarak yeterli analog kazanç sağla.
        val iso = if (isNight) rawIso.coerceIn(800, 2400) else rawIso
        val exp = if (isNight) rawExp.coerceIn(45_000_000L, 90_000_000L) else rawExp

        return BracketSettings(iso, exp, getEvShifts(isNight))
    }

    // Google Pixel Kasıtlı Alt-Pozlama (Highlight Protection) + Foton Biriktirme:
    // -1.0 EV: Sokak lambaları, pencereler ve neonların patlamasını %100 önler.
    // -0.4 EV: Geçiş aydınlatması ve orta ton detayları.
    // 0.0 EV x 3: Gölgeler ve binalar için foton istifleme (temiz düşük gürültü).
    fun getEvShifts(isNight: Boolean): List<Float> =
        if (isNight) listOf(-1.0f, -0.4f, 0.0f, 0.0f, 0.0f)
        else listOf(-0.5f, 0.0f, 0.0f)

    fun frameCount(isNight: Boolean) = getEvShifts(isNight).size

    /** AE compensation ile EV kaydır. A56'da adım 0.1 EV, aralık [-20,+20]. */
    fun applyEvShift(
        builder: CaptureRequest.Builder,
        ch: CameraCharacteristics,
        evShift: Float
    ) {
        val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 10)
        val range = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: Range(-20, 20)

        val units = (evShift / step.toFloat()).roundToInt()
        val clamped = units.coerceIn(range.lower, range.upper)

        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clamped)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    }
}

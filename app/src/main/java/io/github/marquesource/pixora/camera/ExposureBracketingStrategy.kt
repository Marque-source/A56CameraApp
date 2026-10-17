package io.github.marquesource.pixora.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.util.Rational
import kotlin.math.pow
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

    // Gece: 5 kare AYNI pozlamayla çekilir ve ortalanır. Gece birleştirmesi (TemporalMerge)
    // kareleri doğrudan ortaladığı için pozlama farkı olan kareler (-1 / -0.4 EV) gürültüyü
    // azaltmak yerine görüntüyü karartıyor ve en karanlık kare referans seçiliyordu.
    // Gündüz: -0.5 EV kare Mertens füzyonunda parlak alanları kurtarmak için kullanılır.
    fun getEvShifts(isNight: Boolean): List<Float> =
        if (isNight) listOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
        else listOf(-0.5f, 0.0f, 0.0f)

    fun frameCount(isNight: Boolean) = getEvShifts(isNight).size

    /** Cihaz manuel sensör kontrolünü (ISO + perde) destekliyor mu? */
    fun supportsManualSensor(ch: CameraCharacteristics): Boolean =
        ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

    /**
     * Gerçek pozlama braketi: AE'nin önizlemede ölçtüğü pozlamayı (ISO × süre) temel alır ve
     * her kareye 2^ev katı uygular. AE telafisiyle (applyEvShift) yapılan braket, art arda
     * gelen burst karelerinde AE yakınsamaya yetişemediği için çoğu zaman aynı pozlamayı verir;
     * bu fonksiyon AE'yi kapatıp değerleri doğrudan sensöre yazar.
     *
     * Gece modunda toplam pozlama korunarak süre 90ms'ye kadar uzatılır, ISO o oranda düşürülür
     * (aynı parlaklık, daha az gürültü).
     */
    fun applyManualEvShift(
        builder: CaptureRequest.Builder,
        ch: CameraCharacteristics,
        meteredIso: Int,
        meteredExposureNs: Long,
        evShift: Float,
        isNight: Boolean
    ) {
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 3200)
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(100_000L, 1_000_000_000L)

        // Toplam ışık miktarı (ISO·ns) — EV kayması burada uygulanır
        val target = meteredIso.toDouble() * meteredExposureNs.toDouble() * 2.0.pow(evShift.toDouble())

        val maxHandheldNs = if (isNight) 90_000_000L else 33_000_000L
        val maxExp = minOf(expRange.upper, maxHandheldNs)

        var exp = meteredExposureNs * 2.0.pow(evShift.toDouble())
        if (isNight && exp < maxExp) {
            // Süreyi uzatıp ISO'yu düşür: önce sensörün minimum ISO'suna kadar
            val expForMinIso = target / isoRange.lower
            exp = minOf(expForMinIso, maxExp.toDouble())
        }
        exp = exp.coerceIn(expRange.lower.toDouble(), maxExp.toDouble())
        val iso = (target / exp).roundToInt().coerceIn(isoRange.lower, isoRange.upper)

        val expNs = exp.toLong()
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, expNs)
    }

    /** AE compensation ile EV kaydır (manuel sensör desteklenmeyen cihazlar için yedek yol). */
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

package io.github.marquesource.pixora.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.location.Location
import android.os.Build
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

data class ProcessingSettings(
    var noiseReduction: Int = CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL, // Samsung UniHAL pikselleri eritip pastel boya yapmasın!
    var edgeMode: Int = CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG,         // Yapay dijital kenar halelerini kapat
    var hotPixelMode: Int = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
    var opticalStabilization: Boolean = true,
    var videoStabilization: Boolean = false, // A56 sadece OFF
    var faceDetection: Boolean = false,
    var tonemapPreset: Int = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
    var jpegQuality: Byte = 95,
    var effectMode: Int = CaptureRequest.CONTROL_EFFECT_MODE_OFF
)

class CaptureRequestBuilder(
    private val exposure: ExposureController,
    private val focus: FocusController,
    private val whiteBalance: WhiteBalanceController,
    private val zoom: ZoomController,
    private val processing: ProcessingSettings
) {
    companion object {
        private const val TAG = "CaptureRequestBuilder"
    }

    fun build(
        device: CameraDevice,
        ch: CameraCharacteristics,
        template: Int,
        surfaces: List<Surface>,
        flashMode: FlashMode = FlashMode.OFF,
        forVideo: Boolean = false,
        jpegOrientation: Int = 0,
        location: Location? = null,
        forYuvBurst: Boolean = false,
        /** Önizleme isteği mi? Pro modda uzun pozlamalar önizlemeyi dondurmasın diye sınırlandırılır. */
        forPreview: Boolean = false
    ): CaptureRequest.Builder? {
        if (surfaces.isEmpty()) {
            Log.w(TAG, "Surface listesi boş, request iptal")
            return null
        }

        val builder = try {
            device.createCaptureRequest(template)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureRequest failed: ${e.message}")
            return null
        }

        surfaces.forEach { builder.addTarget(it) }

        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)

        val intent = when (template) {
            CameraDevice.TEMPLATE_STILL_CAPTURE -> CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
            CameraDevice.TEMPLATE_RECORD -> CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD
            CameraDevice.TEMPLATE_VIDEO_SNAPSHOT -> CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
            else -> CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW
        }
        safeSet(builder, CaptureRequest.CONTROL_CAPTURE_INTENT, intent)

        // A56 sadece FACE_PRIORITY scene modu destekliyor
        val availableScenes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: intArrayOf()
        if (availableScenes.contains(CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)) {
            safeSet(builder, CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
        }

        safeSet(builder, CaptureRequest.CONTROL_EFFECT_MODE, processing.effectMode)

        exposure.applyTo(builder, forPreview)
        focus.applyTo(builder, forVideo)
        whiteBalance.applyTo(builder)
        zoom.applyTo(builder)

        applyFlash(builder, flashMode)
        applyProcessing(builder, forVideo, ch, forYuvBurst,
            stillJpeg = template == CameraDevice.TEMPLATE_STILL_CAPTURE && !forYuvBurst)

        if (template == CameraDevice.TEMPLATE_STILL_CAPTURE || template == CameraDevice.TEMPLATE_VIDEO_SNAPSHOT) {
            safeSet(builder, CaptureRequest.JPEG_QUALITY, processing.jpegQuality)
            safeSet(builder, CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            location?.let { safeSet(builder, CaptureRequest.JPEG_GPS_LOCATION, it) }

            // 1. APPLE & PIXEL SIFIR DEKLANŞÖR GECİKMESİ (ZSL - Zero Shutter Lag):
            // Donanım ISP tamponundan el titremesinden önceki en net kareyi seçmesini emret
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeSet(builder, CaptureRequest.CONTROL_ENABLE_ZSL, true)
            }

            // 2. GOOGLE PIXEL & APPLE HDR FERAH VE AYDINLIK DOĞAL POZLAMA:
            // Gündüz çekimlerinde sahneyi kasvetli ve karanlık yapmamak için ferah doğal pozlama (0.0 EV).
            // Kullanıcı manuel EV girmemişse doğal pozlamayı koru.
            if (!exposure.isManual() && exposure.exposureCompensation == 0) {
                safeSet(builder, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0)
            }
        }

        return builder
    }

    /** Burst çekim için custom iPhone/S25 tarzı S-curve tonemap isteği */
    fun buildForPipeline(
        device: CameraDevice,
        ch: CameraCharacteristics,
        surfaces: List<Surface>
    ): CaptureRequest.Builder {
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        surfaces.forEach { b.addTarget(it) }

        // Samsung ISP kirliliklerini KAPAT — ham YUV al
        safeSet(b, CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        safeSet(b, CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        safeSet(b, CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)

        // iPhone / Galaxy S25 tarzı canlı ama doğal S-curve tonemap eğrisi
        try {
            val curve = buildSCurve(gamma = 0.85f, contrast = 1.15f)
            b.set(CaptureRequest.TONEMAP_CURVE, curve)
        } catch (e: Throwable) {
            Log.w(TAG, "Tonemap curve atanamadı: ${e.message}")
        }

        return b
    }

    fun buildSCurve(gamma: Float, contrast: Float): TonemapCurve {
        val n = 64
        val points = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = i.toFloat() / (n - 1)
            val centered = (x - 0.5f) * contrast
            val sigmoid = 1f / (1f + exp(-centered * 5.0).toFloat())
            val y = sigmoid.pow(gamma).coerceIn(0f, 1f)
            points[i * 2] = x
            points[i * 2 + 1] = y
        }
        return TonemapCurve(points, points, points)
    }

    private fun applyFlash(builder: CaptureRequest.Builder, flashMode: FlashMode) {
        when (flashMode) {
            FlashMode.OFF -> {
                safeSet(builder, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                if (!exposure.isManual()) {
                    safeSet(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            }
            FlashMode.ON -> {
                if (!exposure.isManual()) {
                    safeSet(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                }
            }
            FlashMode.AUTO -> {
                if (!exposure.isManual()) {
                    safeSet(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                }
            }
            FlashMode.TORCH -> {
                safeSet(builder, CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            }
        }
    }

    private fun applyProcessing(
        builder: CaptureRequest.Builder,
        forVideo: Boolean,
        ch: CameraCharacteristics,
        forYuvBurst: Boolean,
        stillJpeg: Boolean = false
    ) {
        val nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        val edge = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        val hot = ch.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
        val tone = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        val shade = ch.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)
        val aberr = ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        val face = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)

        // Samsung UniHAL'in spatial denoiser'ını dizginlemek için MINIMAL / ZERO_SHUTTER_LAG önceliği:
        val bestNr = when {
            nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL) == true -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
            nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG) == true -> CaptureRequest.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG
            nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_OFF) == true -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
            else -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
        }
        val bestEdge = when {
            edge?.contains(CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG) == true -> CaptureRequest.EDGE_MODE_ZERO_SHUTTER_LAG
            edge?.contains(CaptureRequest.EDGE_MODE_OFF) == true -> CaptureRequest.EDGE_MODE_OFF
            else -> CaptureRequest.EDGE_MODE_FAST
        }

        if (forYuvBurst) {
            // YUV bracket burst için ISP processing’i minimal/ham yap
            safeSet(builder, CaptureRequest.NOISE_REDUCTION_MODE, bestNr)
            safeSet(builder, CaptureRequest.EDGE_MODE, bestEdge)
            setIfSupported(builder, CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF, hot)
            setIfSupported(builder, CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST, tone)
            setIfSupported(builder, CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST, shade)
            setIfSupported(builder, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF, aberr)
            safeSet(builder, CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            safeSet(builder, CaptureRequest.CONTROL_AE_LOCK, true)
            safeSet(builder, CaptureRequest.CONTROL_AWB_LOCK, true)
            safeSet(builder, CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            // Tek kare fotoğraf (ISP JPEG): uygulama bu JPEG'de parlaklık gürültüsünü ayrıca temizlemiyor,
            // bu yüzden ISP'nin yüksek kaliteli gürültü azaltması kullanılır. MINIMAL/OFF burada doğrudan kumlanma demekti.
            val stillNr = when {
                stillJpeg && nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) == true ->
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                stillJpeg && nr?.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) == true ->
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST
                else -> bestNr
            }
            safeSet(builder, CaptureRequest.NOISE_REDUCTION_MODE, stillNr)
            safeSet(builder, CaptureRequest.EDGE_MODE, bestEdge)
            setIfSupported(builder, CaptureRequest.HOT_PIXEL_MODE, processing.hotPixelMode, hot)
            setIfSupported(builder, CaptureRequest.TONEMAP_MODE, processing.tonemapPreset, tone)
            setIfSupported(builder, CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY, shade)
            setIfSupported(builder, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF, aberr)

            if (processing.faceDetection && face != null && face.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)) {
                safeSet(builder, CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)
            } else {
                safeSet(builder, CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
            }
        }

        // OIS
        if (ois != null && ois.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            safeSet(builder, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (processing.opticalStabilization) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }

        // Video stabilization A56’da sadece OFF
        if (forVideo) {
            safeSet(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
    }

    private fun <T> safeSet(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T?) {
        if (value == null) return
        try {
            builder.set(key, value)
        } catch (t: Throwable) {
            Log.w(TAG, "safeSet ${key.name}: ${t.message}")
        }
    }

    private fun setIfSupported(builder: CaptureRequest.Builder, key: CaptureRequest.Key<Int>, value: Int, supported: IntArray?) {
        if (supported != null && supported.contains(value)) {
            safeSet(builder, key, value)
        } else {
            Log.w(TAG, "$key value $value not supported")
        }
    }

}

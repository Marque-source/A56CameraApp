package io.github.marquesource.pixora.utils

import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * Camera2 API için genel yardımcılar.
 */
enum class AspectRatio(val widthRatio: Int, val heightRatio: Int, val label: String) {
    RATIO_4_3(4, 3, "4:3"),
    RATIO_16_9(16, 9, "16:9"),
    RATIO_1_1(1, 1, "1:1"),
    RATIO_FULL(20, 9, "Full");

    val targetRatio: Double get() = widthRatio.toDouble() / heightRatio.toDouble()
}

object Camera2Utils {

    /** Verilen format ve en-boy oranındaki en büyük çıktı boyutu. */
    fun largestOutputSizeWithAspect(
        ch: CameraCharacteristics,
        format: Int,
        aspect: AspectRatio,
        highResMode: Boolean = false
    ): Size? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val normalSizes = map.getOutputSizes(format) ?: emptyArray()
        val highResSizes = if (highResMode) {
            map.getHighResolutionOutputSizes(format) ?: emptyArray()
        } else emptyArray()

        val allSizes = (highResSizes + normalSizes).distinct()
        if (allSizes.isEmpty()) return null

        val matches = allSizes.filter { size ->
            val r = size.width.toDouble() / size.height.toDouble()
            abs(r - aspect.targetRatio) < 0.1 || abs((1.0 / r) - aspect.targetRatio) < 0.1
        }

        return matches.maxByOrNull { it.width.toLong() * it.height }
            ?: allSizes.maxByOrNull { it.width.toLong() * it.height }
    }

    enum class Facing { BACK, FRONT }

    data class CameraInfo(
        val id: String,
        val facing: Facing,
        val focalLength: Float,
        val isLogical: Boolean,
        val physicalIds: Set<String>,
        val hardwareLevel: Int,
        val supportsRaw: Boolean,
        val minFocusDistance: Float,
        val isoRange: Range<Int>?,
        val exposureRange: Range<Long>?,
        val maxZoom: Float,
        val activeArraySize: Rect?,
        val physicalSize: android.util.SizeF?,
        /** 35 mm eşdeğer odak uzaklığı (görüş açısının ölçüsü; küçük = geniş). 0 = bilinmiyor. */
        val equivFocalMm: Float = 0f,
        /** Sensör piksel sayısı (pixel array). */
        val pixelCount: Long = 0L,
        /** CONTROL_ZOOM_RATIO alt sınırı; < 1 ise kamera ultra genişi de kapsıyor demektir. */
        val minZoomRatio: Float = 1f,
        /** Normal (BACKWARD_COMPATIBLE) çekim yapabiliyor mu? (Ör. yalnızca güvenli yüz tanıma kamerası değil.) */
        val isUsable: Boolean = true
    )

    fun enumerateCameras(manager: CameraManager): List<CameraInfo> {
        val result = mutableListOf<CameraInfo>()
        for (id in manager.cameraIdList) {
            val ch = manager.getCameraCharacteristics(id)
            val lensFacing = ch.get(CameraCharacteristics.LENS_FACING)
            val facing = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) Facing.FRONT else Facing.BACK

            val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val isLogical = caps.contains(
                CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
            )
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val raw10Sizes = map?.getOutputSizes(ImageFormat.RAW10)
            val raw12Sizes = map?.getOutputSizes(ImageFormat.RAW12)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)

            val hasRawFormat = map != null && (
                !rawSizes.isNullOrEmpty() ||
                !raw10Sizes.isNullOrEmpty() ||
                !raw12Sizes.isNullOrEmpty()
            )
            val supportsRaw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) || hasRawFormat
            val physicalIds: Set<String> = try {
                ch.physicalCameraIds
            } catch (e: Throwable) { emptySet() }

            val hwLevel = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                ?: CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
            val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val maxZoom = ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            val activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val physicalSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val pixelArray = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val diag = physicalSize?.let { kotlin.math.hypot(it.width.toDouble(), it.height.toDouble()) } ?: 0.0
            val equiv = if (diag > 0.1 && focal > 0f) (focal * 43.27 / diag).toFloat() else 0f
            val minZoom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
            val usable = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)

            result.add(
                CameraInfo(
                    id = id, facing = facing, focalLength = focal, isLogical = isLogical,
                    physicalIds = physicalIds, hardwareLevel = hwLevel, supportsRaw = supportsRaw,
                    minFocusDistance = minFocus, isoRange = isoRange, exposureRange = expRange,
                    maxZoom = maxZoom, activeArraySize = activeArray, physicalSize = physicalSize,
                    equivFocalMm = equiv,
                    pixelCount = (pixelArray?.width?.toLong() ?: 0L) * (pixelArray?.height?.toLong() ?: 0L),
                    minZoomRatio = minZoom, isUsable = usable
                )
            )
        }
        return result
    }

    /**
     * Ana kamerayı seçer; her telefonda doğru lensi bulmak için:
     *  1. Ultra genişi de kapsayan (yakınlaştırma < 1x) mantıksal kamera varsa onu kullan (Pixel tarzı).
     *  2. Yoksa normal açılı (35 mm eşdeğeri 18–35 mm) kameralardan en çok piksele sahip olanı seç;
     *     eşitlikte RAW destekleyen ve donanım seviyesi yüksek olan kazanır.
     * Eskiden ilk mantıksal kamera seçiliyordu: Redmi Note 10S'de bu, RAW ve tam elle kontrolü olmayan
     * "ana + derinlik" birleşik kamerası çıktı; ana sensörün kendisi (RAW, LEVEL_3) kullanılmıyordu.
     */
    fun mainCamera(cameras: List<CameraInfo>, facing: Facing): CameraInfo? {
        val same = cameras.filter { it.facing == facing && it.isUsable }
        same.firstOrNull { it.isLogical && it.minZoomRatio < 0.99f }?.let { return it }
        val physical = same.filter { !it.isLogical }
        val normal = physical.filter { it.equivFocalMm in 18f..35f }.ifEmpty { physical }
        return normal.maxWithOrNull(
            compareBy<CameraInfo> { it.pixelCount }
                .thenBy { if (it.supportsRaw) 1 else 0 }
                .thenBy { levelRank(it.hardwareLevel) }
        ) ?: same.firstOrNull()
    }

    /** En geniş açılı arka kamera (ana kameradan belirgin şekilde geniş olmalı). */
    fun ultraWideCamera(cameras: List<CameraInfo>): CameraInfo? {
        val main = mainCamera(cameras, Facing.BACK) ?: return null
        val back = cameras.filter { it.facing == Facing.BACK && it.isUsable && !it.isLogical && it.id != main.id }
        return if (main.equivFocalMm > 0f && back.all { it.equivFocalMm > 0f }) {
            back.filter { it.equivFocalMm < main.equivFocalMm * 0.8f }.minByOrNull { it.equivFocalMm }
        } else {
            back.filter { it.focalLength < main.focalLength * 0.8f }.minByOrNull { it.focalLength }
        }
    }

    fun rawCamera(cameras: List<CameraInfo>, facing: Facing): CameraInfo? =
        cameras.firstOrNull { it.facing == facing && it.supportsRaw && it.isUsable }

    /** LEGACY < LIMITED < EXTERNAL < FULL < LEVEL_3 */
    private fun levelRank(level: Int): Int = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> 0
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> 1
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> 2
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> 3
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> 4
        else -> 1
    }

    fun hardwareLevelName(level: Int): String = when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    fun largestOutputSize(ch: CameraCharacteristics, format: Int): Size? {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        return map.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun checkCamera2Extensions(cameraManager: CameraManager, cameraId: String) {
        try {
            val extensionChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
            val supported = extensionChars.supportedExtensions

            supported.forEach { ext ->
                val name = when (ext) {
                    CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
                    CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
                    CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
                    CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTO"
                    else -> "UNKNOWN($ext)"
                }
                Log.d("CAM2_EXT", "✅ $name destekleniyor")

                val sizes = extensionChars.getExtensionSupportedSizes(ext, ImageFormat.JPEG)
                Log.d("CAM2_EXT", "  JPEG boyutları: $sizes")
            }
        } catch (e: Throwable) {
            Log.w("CAM2_EXT", "Extension özellikleri okunamadı: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun getExtensionJpegSize(manager: CameraManager, cameraId: String, mode: Int, aspect: AspectRatio): Size? {
        return try {
            val chars = manager.getCameraExtensionCharacteristics(cameraId)
            val sizes = chars.getExtensionSupportedSizes(mode, ImageFormat.JPEG)
            if (sizes.isEmpty()) return null
            val matches = sizes.filter { size ->
                val r = size.width.toDouble() / size.height.toDouble()
                abs(r - aspect.targetRatio) < 0.1 || abs((1.0 / r) - aspect.targetRatio) < 0.1
            }
            matches.maxByOrNull { it.width.toLong() * it.height } ?: sizes.maxByOrNull { it.width.toLong() * it.height }
        } catch (e: Throwable) {
            null
        }
    }

    fun chooseOptimalSize(
        map: StreamConfigurationMap,
        klass: Class<*>,
        targetWidth: Int,
        targetHeight: Int,
        maxWidth: Int = 1920,
        maxHeight: Int = 1080
    ): Size {
        val choices = map.getOutputSizes(klass) ?: return Size(maxWidth, maxHeight)
        val targetRatio = targetWidth.toDouble() / targetHeight
        val valid = choices.filter { it.width <= maxWidth && it.height <= maxHeight }
        if (valid.isEmpty()) return choices.first()
        return valid.minByOrNull { size ->
            val ratio = size.width.toDouble() / size.height
            abs(ratio - targetRatio) * 1000 + abs(size.width * size.height - targetWidth * targetHeight) / 1_000_000.0
        } ?: valid.first()
    }

    fun jpegSizes(ch: CameraCharacteristics): Array<Size> {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(ImageFormat.JPEG) ?: arrayOf(Size(1920, 1080))
    }

    fun formatExposureTime(nanos: Long): String {
        val seconds = nanos / 1_000_000_000.0
        return if (seconds >= 1.0) {
            "${"%.0f".format(seconds)}\""
        } else {
            val denom = (1.0 / seconds).toInt()
            "1/$denom"
        }
    }

    fun progressToIso(progress: Float, range: Range<Int>): Int {
        val lo = range.lower.toDouble()
        val hi = range.upper.toDouble()
        return (lo * (hi / lo).pow(progress.toDouble())).toInt().coerceIn(range.lower, range.upper)
    }

    fun isoToProgress(iso: Int, range: Range<Int>): Float {
        val lo = range.lower.toDouble()
        val hi = range.upper.toDouble()
        return (ln(iso / lo) / ln(hi / lo)).toFloat().coerceIn(0f, 1f)
    }

    fun progressToExposure(progress: Float, range: Range<Long>): Long {
        val lo = max(1.0, range.lower.toDouble())
        val hi = range.upper.toDouble()
        return (lo * (hi / lo).pow(progress.toDouble())).toLong().coerceIn(range.lower, range.upper)
    }

    fun exposureToProgress(nanos: Long, range: Range<Long>): Float {
        val lo = max(1.0, range.lower.toDouble())
        val hi = range.upper.toDouble()
        return (ln(nanos / lo) / ln(hi / lo)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Ekrandaki dokunma noktasını sensör (aktif dizi) koordinatına çevirir.
     *
     * Eskiden ekran x/y doğrudan sensör x/y kabul ediliyordu. Ama sensör yatay, ekran dikey:
     * arka kamerada (yön 90°) ekranın ÜSTÜ sensörün SOL kenarıdır. Ayrıca önizlemenin ekranda
     * kapladığı alan (letterbox), ön kameranın ayna görüntüsü ve zoom hesaba katılmıyordu.
     *
     * @param x,y dokunma noktası (görünüm pikselleri)
     * @param content önizleme görüntüsünün görünüm içinde kapladığı dikdörtgen
     * @param sensorOrientation SENSOR_ORIENTATION (0/90/180/270)
     * @param mirrored ön kamera (önizleme aynalanmış)
     * @param activeArray SENSOR_INFO_ACTIVE_ARRAY_SIZE
     * @param zoomRatio CONTROL_ZOOM_RATIO (1 = zoom yok)
     * @param streamAspect önizleme akışının en/boy oranı (yatay, ör. 4:3 = 1.333, 16:9 = 1.777)
     */
    fun mapViewToSensor(
        x: Float, y: Float,
        content: android.graphics.RectF,
        sensorOrientation: Int,
        mirrored: Boolean,
        activeArray: Rect,
        zoomRatio: Float,
        streamAspect: Float
    ): Point {
        // 1) Görüntü alanına göre 0..1
        var u = ((x - content.left) / content.width()).coerceIn(0f, 1f)
        val v = ((y - content.top) / content.height()).coerceIn(0f, 1f)
        if (mirrored) u = 1f - u

        // 2) Ekran yönünden sensör yönüne (sensör görüntüsü saat yönünde [sensorOrientation] döndürülerek gösterilir)
        val (sx, sy) = when (((sensorOrientation % 360) + 360) % 360) {
            90 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            270 -> (1f - v) to u
            else -> u to v
        }

        // 3) Zoom ve akış en/boy oranı: önizleme sensörün ortadaki bir bölümünü gösterir
        val r = zoomRatio.coerceAtLeast(1f)
        var visW = activeArray.width() / r
        var visH = activeArray.height() / r
        val arrayAspect = visW / visH
        if (streamAspect > arrayAspect) visH = visW / streamAspect   // ör. 16:9 akış, 4:3 sensör: üst-alt kırpılır
        else if (streamAspect < arrayAspect) visW = visH * streamAspect
        val left = activeArray.left + (activeArray.width() - visW) / 2f
        val top = activeArray.top + (activeArray.height() - visH) / 2f

        val px = (left + sx * visW).toInt().coerceIn(activeArray.left, activeArray.right - 1)
        val py = (top + sy * visH).toInt().coerceIn(activeArray.top, activeArray.bottom - 1)
        return Point(px, py)
    }
}

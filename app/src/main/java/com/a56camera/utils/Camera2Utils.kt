package com.a56camera.utils

import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.RggbChannelVector
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
        val physicalSize: android.util.SizeF?
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

            result.add(
                CameraInfo(
                    id = id, facing = facing, focalLength = focal, isLogical = isLogical,
                    physicalIds = physicalIds, hardwareLevel = hwLevel, supportsRaw = supportsRaw,
                    minFocusDistance = minFocus, isoRange = isoRange, exposureRange = expRange,
                    maxZoom = maxZoom, activeArraySize = activeArray, physicalSize = physicalSize
                )
            )
        }
        return result
    }

    fun mainCamera(cameras: List<CameraInfo>, facing: Facing): CameraInfo? {
        val sameFacing = cameras.filter { it.facing == facing }
        return sameFacing.firstOrNull { it.isLogical }
            ?: sameFacing.maxByOrNull { it.focalLength }
    }

    fun ultraWideCamera(cameras: List<CameraInfo>): CameraInfo? =
        cameras.filter { it.facing == Facing.BACK }.minByOrNull { it.focalLength }

    fun rawCamera(cameras: List<CameraInfo>, facing: Facing): CameraInfo? =
        cameras.firstOrNull { it.facing == facing && it.supportsRaw }

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

    fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val temp = kelvin / 100.0
        val red: Double
        val green: Double
        val blue: Double

        red = if (temp <= 66) 255.0 else (329.698727446 * (temp - 60).pow(-0.1332047592)).coerceIn(0.0, 255.0)
        green = if (temp <= 66)
            (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
        else
            (288.1221695283 * (temp - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0)
        blue = when {
            temp >= 66 -> 255.0
            temp <= 19 -> 0.0
            else -> (138.5177312231 * ln(temp - 10) - 305.0447927307).coerceIn(0.0, 255.0)
        }

        val rGain = (255.0 / max(1.0, red)).toFloat().coerceIn(1.0f, 4.0f)
        val gGain = 1.0f
        val bGain = (255.0 / max(1.0, blue)).toFloat().coerceIn(1.0f, 4.0f)
        return RggbChannelVector(rGain, gGain, gGain, bGain)
    }

    fun mapPreviewToSensor(
        x: Float, y: Float,
        previewWidth: Int, previewHeight: Int,
        activeArray: Rect,
        cropRegion: Rect?
    ): Point {
        val region = cropRegion ?: activeArray
        val sx = (x / previewWidth) * region.width() + region.left
        val sy = (y / previewHeight) * region.height() + region.top
        return Point(sx.toInt().coerceIn(activeArray.left, activeArray.right),
            sy.toInt().coerceIn(activeArray.top, activeArray.bottom))
    }
}

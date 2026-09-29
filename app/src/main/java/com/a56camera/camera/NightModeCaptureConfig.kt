package com.a56camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi

/**
 * Galaxy A56 için gece modu capture konfigürasyonu.
 *
 * Hedef: daha fazla frame, daha uzun exposure, düşük ISO ile
 * stok Samsung gece moduna yakın dinamik aralık ve gölge detayı elde etmek.
 */
object NightModeCaptureConfig {

    private const val TAG = "NightModeCaptureConfig"

    /**
     * Extension API ile NIGHT modu destekleniyor mu?
     * Camera2 Extensions >= API 31 (Android 12) gerekir.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun isNightExtensionAvailable(
        cameraManager: CameraManager,
        cameraId: String
    ): Boolean {
        val extChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
        return extChars.supportedExtensions.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
    }

    /**
     * NIGHT extension için uygun çıkış boyutunu döner.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun getNightExtensionOutputSize(
        cameraManager: CameraManager,
        cameraId: String,
        format: Int = android.graphics.ImageFormat.JPEG
    ): Size {
        val extChars = cameraManager.getCameraExtensionCharacteristics(cameraId)
        val sizes = extChars.getExtensionSupportedSizes(
            CameraExtensionCharacteristics.EXTENSION_NIGHT,
            format
        )
        return sizes.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
    }

    /**
     * Manuel gece burst için CaptureRequest ayarları.
     * Burst sayısı ve exposure, sensör ve lens yeteneklerine göre uyarlanır.
     */
    fun applyManualNightSettings(
        builder: CaptureRequest.Builder,
        characteristics: CameraCharacteristics,
        frameCount: Int = 8,
        targetExposureMs: Float = 80f,
        baseIso: Int = 400
    ) {
        val minIso = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )?.lower ?: 50
        val maxIso = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )?.upper ?: 6400

        val minExp = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )?.lower ?: 1000L
        val maxExp = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )?.upper ?: 100_000_000L

        // OIS varsa uzun exposure daha güvenli
        val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val hasOis = oisModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false

        // Hedef exposure: OIS yoksa 50 ms civarında tut, varsa 80-100 ms
        val safeExposureNs = when {
            hasOis -> (targetExposureMs * 1_000_000f).toLong()
            else -> (50f * 1_000_000f).toLong()
        }.coerceIn(minExp, maxExp)

        val currentIso = (baseIso * (80f / targetExposureMs)).toInt().coerceIn(minIso, maxIso)

        builder.apply {
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExposureNs)
            set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

            // AF: gece modunda odak sabit ya da AF lock sonrası
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            // AWB otomatik (MetadataAwB sonradan override edecek)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            // Gürültü azaltma: hardware minimal, yazılımsal işlem AI pipeline’a bırakılır
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)

            // Edge sharpening de minimal
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)

            // Lens shading düzeltme açık kalsın
            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)

            // OIS açık
            if (hasOis) {
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }

            // Flash kapalı
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }

        Log.d(TAG, "Manual night: frames=$frameCount, exp=${safeExposureNs}ns, iso=$currentIso, ois=$hasOis")
    }

    /**
     * AE ön ölçüm sonucundan optimum gece exposure/ISO çiftini seçer.
     */
    fun deriveNightExposureFromAe(
        characteristics: CameraCharacteristics,
        aeExposureNs: Long,
        aeSensitivity: Int,
        targetIso: Int = 400
    ): Pair<Long, Int> {
        val minIso = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )?.lower ?: 50
        val maxIso = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )?.upper ?: 6400
        val maxExp = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )?.upper ?: 100_000_000L

        val iso = targetIso.coerceIn(minIso, maxIso)
        val ratio = iso.toFloat() / aeSensitivity.toFloat().coerceAtLeast(1f)
        val exposure = (aeExposureNs / ratio).toLong().coerceIn(1, maxExp)
        return exposure to iso
    }

    /**
     * Frame sayısını sahne parlaklığına göre seçer.
     */
    fun pickFrameCount(luxEstimate: Float?): Int {
        return when {
            luxEstimate == null -> 8
            luxEstimate < 1.0f -> 12
            luxEstimate < 5.0f -> 8
            luxEstimate < 15.0f -> 6
            else -> 4
        }
    }
}

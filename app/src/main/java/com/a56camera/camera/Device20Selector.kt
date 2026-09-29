package com.a56camera.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * Samsung Galaxy A56 mantıksal çoklu kamera kimliğini (ID = "20") seçen CameraSelector.
 */
object Device20Selector {

    private const val TAG = "Device20Selector"
    private const val LOGICAL_BACK_CAMERA_ID = "20"

    @OptIn(ExperimentalCamera2Interop::class)
    fun create(): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { info ->
                    runCatching {
                        val id = Camera2CameraInfo.from(info).cameraId
                        Log.d(TAG, "Found camera id: $id")
                        id == LOGICAL_BACK_CAMERA_ID
                    }.getOrDefault(false)
                }
            }
            .build()
    }

    /** En yakın fallback: arka yüz + logical multi-camera capability */
    @OptIn(ExperimentalCamera2Interop::class)
    fun createFallback(): CameraSelector {
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { cameras ->
                cameras.filter { info ->
                    runCatching {
                        val cam2 = Camera2CameraInfo.from(info)
                        val caps = cam2.getCameraCharacteristic(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                        )
                        caps?.contains(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                        ) == true
                    }.getOrDefault(false)
                }
            }
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun getCameraId(info: CameraInfo?): String? = if (info != null) {
        runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull()
    } else null
}

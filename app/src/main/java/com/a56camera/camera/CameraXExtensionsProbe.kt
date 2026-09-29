package com.a56camera.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraXExtensionsProbe @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "EXT"

    suspend fun checkAvailability(): Map<Int, Boolean> {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val extensionsManager = ExtensionsManager.getInstanceAsync(context, provider).await()

        val modes = mapOf(
            ExtensionMode.HDR to "HDR",
            ExtensionMode.NIGHT to "Gece Modu",
            ExtensionMode.BOKEH to "Portre/Bokeh",
            ExtensionMode.FACE_RETOUCH to "Yüz Rötuşu",
            ExtensionMode.AUTO to "Otomatik"
        )

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        val result = mutableMapOf<Int, Boolean>()
        modes.forEach { (mode, name) ->
            val supported = extensionsManager.isExtensionAvailable(selector, mode)
            result[mode] = supported
            Log.d(TAG, "$name: ${if (supported) "✅ DESTEKLENIYOR" else "❌ YOK"}")
        }
        return result
    }

    suspend fun checkAndUseExtensions(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Boolean {
        val provider = ProcessCameraProvider.getInstance(context).await()
        val extManager = ExtensionsManager.getInstanceAsync(context, provider).await()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        val modes = mapOf(
            ExtensionMode.HDR to "HDR",
            ExtensionMode.NIGHT to "Gece Modu",
            ExtensionMode.BOKEH to "Portre/Bokeh",
            ExtensionMode.FACE_RETOUCH to "Yüz Rötuşu",
            ExtensionMode.AUTO to "Otomatik"
        )

        modes.forEach { (mode, name) ->
            val supported = extManager.isExtensionAvailable(selector, mode)
            Log.d(TAG, "$name: ${if (supported) "✅ DESTEKLENIYOR" else "❌ YOK"}")
        }

        val nightAvailable = extManager.isExtensionAvailable(selector, ExtensionMode.NIGHT)
        if (nightAvailable) {
            val nightSelector = extManager.getExtensionEnabledCameraSelector(
                selector, ExtensionMode.NIGHT
            )
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, nightSelector, preview, imageCapture)
            Log.i(TAG, "Samsung Nightography motoru başarıyla bağlandı!")
            return true
        }
        return false
    }
}

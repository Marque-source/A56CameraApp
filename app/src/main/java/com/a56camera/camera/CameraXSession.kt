package com.a56camera.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class CameraXSession @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var provider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        mode: Int = ExtensionMode.AUTO,
        enableAnalysis: Boolean = true,
        onAnalysis: ((ImageProxy) -> Unit)? = null
    ): Boolean {
        provider = awaitFuture(ProcessCameraProvider.getInstance(context))
        extensionsManager = awaitFuture(ExtensionsManager.getInstanceAsync(context, provider!!))

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        val extSelector = if (extensionsManager!!.isExtensionAvailable(selector, mode)) {
            extensionsManager!!.getExtensionEnabledCameraSelector(selector, mode)
        } else {
            Log.w(TAG, "Extension mode $mode desteklenmiyor, varsayılan kameraya geçiliyor")
            selector
        }

        // 1) PREVIEW: 1920x1080 (1080p Performance Mode)
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1080),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        // 2) IMAGE CAPTURE: Maximize quality
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()

        // 3) IMAGE ANALYSIS: 640x480 (Gece modunda bant genişliği tasarrufu için kapalı)
        imageAnalysis = if (enableAnalysis) {
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    if (onAnalysis != null) {
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                            try {
                                onAnalysis.invoke(imageProxy)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }
                }
        } else null

        try {
            provider!!.unbindAll()
            val useCases = listOfNotNull(preview, imageCapture, imageAnalysis)
            provider!!.bindToLifecycle(
                lifecycleOwner,
                extSelector,
                *useCases.toTypedArray()
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "CameraX bind başarısız", e)
            return false
        }
    }

    suspend fun enterNightMode(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        start(lifecycleOwner, previewView, mode = ExtensionMode.NIGHT, enableAnalysis = false)
    }

    suspend fun exitNightMode(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onAnalysis: ((ImageProxy) -> Unit)? = null
    ) {
        start(lifecycleOwner, previewView, mode = ExtensionMode.AUTO, enableAnalysis = true, onAnalysis = onAnalysis)
    }

    suspend fun capture(
        metadata: CaptureMetadata,
        onResult: (ImageProxy) -> Unit
    ) {
        val ic = imageCapture ?: return
        val executor = ContextCompat.getMainExecutor(context)

        ic.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    onResult(image)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture hatası", exception)
                }
            }
        )
    }

    fun shutdown() {
        provider?.unbindAll()
    }

    private suspend fun <T> awaitFuture(future: ListenableFuture<T>): T =
        suspendCancellableCoroutine { cont ->
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }

    private companion object {
        const val TAG = "CameraXSession"
    }
}

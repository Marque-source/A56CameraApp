package com.a56camera.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class SamsungCameraManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "SamsungCameraManager"

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var activeCamera: Camera? = null
    private var activeImageCapture: ImageCapture? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    var onFrame: ((ImageProxy) -> Unit)? = null

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        mode: SamsungCameraMode = SamsungCameraMode.AUTO
    ): Boolean = suspendCancellableCoroutine { continuation ->

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = try { providerFuture.get() } catch (e: Throwable) { null }
            if (provider == null) {
                if (continuation.isActive) continuation.resume(false)
                return@addListener
            }
            cameraProvider = provider

            val extensionsFuture = ExtensionsManager.getInstanceAsync(context, provider)
            extensionsFuture.addListener({
                try {
                    val extManager = extensionsFuture.get()
                    extensionsManager = extManager

                    val baseSelector = Device20Selector.create()
                    val finalSelector = tryResolveExtension(extManager, baseSelector, mode)

                    provider.unbindAll()

                    val preview = Preview.Builder()
                        .setTargetResolution(mode.previewResolution)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    val imageCapture = ImageCapture.Builder()
                        .setTargetResolution(mode.captureResolution)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setJpegQuality(95)
                        .build()
                        .also { activeImageCapture = it }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetResolution(mode.analysisResolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                onFrame?.invoke(imageProxy)
                                imageProxy.close()
                            }
                        }

                    val useCaseGroup = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageCapture)
                        .addUseCase(imageAnalysis)
                        .build()

                    activeCamera = provider.bindToLifecycle(
                        lifecycleOwner,
                        finalSelector,
                        useCaseGroup
                    )

                    val boundId = Device20Selector.getCameraId(activeCamera?.cameraInfo)
                    Log.i(tag, "Bound camera id: $boundId")

                    // AE/AWB kilidi: gece fusion için stabil pozlama
                    trySetAeAwbLock()

                    if (continuation.isActive) continuation.resume(boundId == "20" || boundId != null)

                } catch (e: Throwable) {
                    Log.e(tag, "Extension/ binding failed", e)
                    try {
                        bindStandardDevice20(lifecycleOwner, previewView, mode)
                        if (continuation.isActive) continuation.resume(true)
                    } catch (e2: Throwable) {
                        Log.e(tag, "Fallback bind failed", e2)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }, ContextCompat.getMainExecutor(context))

        }, ContextCompat.getMainExecutor(context))
    }

    private fun tryResolveExtension(
        extManager: ExtensionsManager,
        selector: CameraSelector,
        mode: SamsungCameraMode
    ): CameraSelector {
        val extensionMode = when (mode) {
            SamsungCameraMode.NIGHT -> ExtensionMode.NIGHT
            SamsungCameraMode.HDR -> ExtensionMode.HDR
            SamsungCameraMode.FACE_RETOUCH -> ExtensionMode.FACE_RETOUCH
            SamsungCameraMode.BOKEH -> ExtensionMode.BOKEH
            else -> ExtensionMode.AUTO
        }

        if (extensionMode == ExtensionMode.AUTO) return selector

        return try {
            val available = extManager.isExtensionAvailable(selector, extensionMode)
            if (available) {
                Log.i(tag, "Samsung extension available: $extensionMode")
                extManager.getExtensionEnabledCameraSelector(selector, extensionMode)
            } else {
                Log.w(tag, "Extension unavailable: $extensionMode")
                selector
            }
        } catch (e: Throwable) {
            Log.e(tag, "Extension check failed", e)
            selector
        }
    }

    private fun bindStandardDevice20(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        mode: SamsungCameraMode
    ) {
        val provider = cameraProvider ?: throw IllegalStateException("Provider null")
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetResolution(mode.previewResolution)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val imageCapture = ImageCapture.Builder()
            .setTargetResolution(mode.captureResolution)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .build()
            .also { activeImageCapture = it }

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(mode.analysisResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    onFrame?.invoke(imageProxy)
                    imageProxy.close()
                }
            }

        activeCamera = provider.bindToLifecycle(
            lifecycleOwner,
            Device20Selector.create(),
            preview,
            imageCapture,
            imageAnalysis
        )

        Log.i(tag, "Standard device 20 bound")
        trySetAeAwbLock()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun trySetAeAwbLock() {
        val camera = activeCamera ?: return
        try {
            val cameraControl = Camera2CameraControl.from(camera.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_LOCK,
                    true
                )
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_LOCK,
                    true
                )
                .build()
            cameraControl.addCaptureRequestOptions(options)
            Log.i(tag, "AE/AWB lock requested")
        } catch (e: Throwable) {
            Log.w(tag, "Could not set AE/AWB lock", e)
        }
    }

    fun takePhoto(
        outputOptions: ImageCapture.OutputFileOptions,
        executor: java.util.concurrent.Executor,
        onResult: (ImageCapture.OutputFileResults) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        activeImageCapture?.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    onResult(output)
                }
            }
        ) ?: onError(IllegalStateException("ImageCapture not bound"))
    }

    fun stop() {
        onFrame = null
        activeImageCapture = null
        activeCamera = null
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
}

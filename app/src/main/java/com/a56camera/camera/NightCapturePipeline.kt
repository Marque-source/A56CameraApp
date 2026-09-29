package com.a56camera.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.math.roundToInt

/**
 * 7 Kare YUV_420_888 Gece & HDR Çekim ve İşleme Pipeline'ı.
 */
class NightCapturePipeline(
    private val width: Int,
    private val height: Int,
    private val frameCount: Int = 7
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    val imageSurface: Surface
    private val imageReader: ImageReader

    private val frames = mutableListOf<CapturedFrame>()
    private val lock = Any()

    private var pendingFrames = 0
    private var listener: ((File) -> Unit)? = null
    private var outputFile: File? = null
    private var jpegOrientation = 0
    private var mode = FrameFusionProcessor.Mode.NIGHT

    private val bgHandler = Handler(Looper.getMainLooper())

    init {
        require(OpenCVLoader.initDebug()) { "OpenCV init failed" }
        imageReader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.YUV_420_888, frameCount)
        imageSurface = imageReader.surface
        imageReader.setOnImageAvailableListener({ reader ->
            val img: Image? = reader.acquireNextImage()
            img?.let { onImage(it) }
        }, bgHandler)
    }

    fun captureNight(
        device: CameraDevice,
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics,
        outFile: File,
        orientation: Int,
        onDone: (File) -> Unit
    ) {
        mode = FrameFusionProcessor.Mode.NIGHT
        startCapture(device, session, characteristics, outFile, orientation, emptyList(), onDone)
    }

    fun captureHdr(
        device: CameraDevice,
        session: CameraCaptureSession,
        characteristics: CameraCharacteristics,
        evShifts: List<Float>,
        outFile: File,
        orientation: Int,
        onDone: (File) -> Unit
    ) {
        mode = FrameFusionProcessor.Mode.HDR
        startCapture(device, session, characteristics, outFile, orientation, evShifts, onDone)
    }

    private fun startCapture(
        device: CameraDevice,
        session: CameraCaptureSession,
        ch: CameraCharacteristics,
        outFile: File,
        orientation: Int,
        evShifts: List<Float>,
        onDone: (File) -> Unit
    ) {
        synchronized(lock) {
            frames.clear()
            pendingFrames = frameCount
            listener = onDone
            outputFile = outFile
            jpegOrientation = orientation
        }

        val requests = buildRequests(device, ch, evShifts)
        session.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure
            ) {
                Log.e("NightPipeline", "Burst frame failed: ${failure.reason}")
            }
        }, bgHandler)
    }

    private fun buildRequests(
        device: CameraDevice,
        ch: CameraCharacteristics,
        evShifts: List<Float>
    ): List<CaptureRequest> {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.addTarget(imageSurface)

        // A56 desteklenen ayarlar
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        return if (mode == FrameFusionProcessor.Mode.NIGHT) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            List(frameCount) { builder.build() }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 10)
            evShifts.map { ev ->
                val units = (ev / step.toFloat()).roundToInt()
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, units)
                builder.setTag(ev)
                builder.build()
            }
        }
    }

    private fun onImage(img: Image) {
        val nv21 = YuvUtils.imageToNv21(img)
        val ts = img.timestamp
        val ev = 0f
        img.close()

        synchronized(lock) {
            frames += CapturedFrame(nv21, width, height, ev, ts)
            if (frames.size >= pendingFrames) {
                processNow(frames.toList())
            }
        }
    }

    private fun processNow(list: List<CapturedFrame>) {
        scope.launch {
            val out = outputFile ?: return@launch
            FrameFusionProcessor().process(list, mode, out, jpegOrientation)
            withContext(Dispatchers.Main) { listener?.invoke(out) }
        }
    }

    fun release() {
        job.cancel()
        imageReader.close()
    }
}

package com.a56camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class YuvBurstReader(
    private val size: Size = Size(4080, 3060),
    private val maxImages: Int = 15
) {
    private val thread = HandlerThread("YuvBurstReader").apply { start() }
    private val handler = Handler(thread.looper)
    private var imageReader: ImageReader? = null

    private val isCapturing = AtomicBoolean(false)
    private val syncQueue = FrameSyncQueue()
    private val frames = mutableListOf<Pair<YuvFrame, TotalCaptureResult>>()
    private var targetCount = 0
    private var onComplete: ((List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    init {
        syncQueue.setListener { image, result ->
            if (!isCapturing.get()) {
                try { image.close() } catch (_: Throwable) {}
                return@setListener
            }
            try {
                val frame = YuvFrame.fromImage(image, result)
                if (frame != null) {
                    frames.add(frame to result)
                }
                if (frames.size >= targetCount && isCapturing.getAndSet(false)) {
                    val resultList = frames.toList()
                    frames.clear()
                    onComplete?.invoke(resultList)
                }
            } catch (e: Exception) {
                onError?.invoke("Frame okuma hatası: ${e.message}")
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
        }
    }

    fun prepare(): Surface? {
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, maxImages).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                if (!isCapturing.get()) {
                    try { image.close() } catch (_: Throwable) {}
                    return@setOnImageAvailableListener
                }
                syncQueue.pushImage(image)
            }, handler)
        }
        return imageReader?.surface
    }

    fun startCapture(
        count: Int,
        onComplete: (List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit,
        onError: (String) -> Unit
    ) {
        this.targetCount = count
        this.onComplete = onComplete
        this.onError = onError
        frames.clear()
        syncQueue.clear()
        isCapturing.set(true)
    }

    fun pushCaptureResult(result: TotalCaptureResult) {
        if (isCapturing.get()) {
            syncQueue.pushResult(result)
        }
    }

    fun getSurface(): Surface? = imageReader?.surface

    fun stop() {
        isCapturing.set(false)
        syncQueue.clear()
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { thread.quitSafely() } catch (_: Throwable) {}
    }
}

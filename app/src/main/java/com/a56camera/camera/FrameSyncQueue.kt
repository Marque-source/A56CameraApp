package com.a56camera.camera

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs

class FrameSyncQueue {
    private val images = ConcurrentLinkedQueue<Pair<Image, Long>>()
    private val results = ConcurrentLinkedQueue<Pair<TotalCaptureResult, Long>>()
    private var listener: ((Image, TotalCaptureResult) -> Unit)? = null

    fun pushImage(image: Image) {
        images.offer(image to image.timestamp)
        tryMatch()
    }

    fun pushResult(result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        results.offer(result to ts)
        tryMatch()
    }

    fun setListener(listener: (Image, TotalCaptureResult) -> Unit) {
        this.listener = listener
    }

    private fun tryMatch() {
        val img = images.peek() ?: return
        val res = results.peek() ?: return
        if (abs(img.second - res.second) < 5_000_000L) { // 5ms tolerans
            images.poll()
            results.poll()
            listener?.invoke(img.first, res.first)
        } else if (img.second < res.second) {
            try { img.first.close() } catch (_: Throwable) {}
            images.poll()
        } else {
            results.poll()
        }
    }

    fun clear() {
        while (!images.isEmpty()) {
            try { images.poll()?.first?.close() } catch (_: Throwable) {}
        }
        results.clear()
    }
}

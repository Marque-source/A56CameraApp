package com.a56camera.pipeline

import android.media.Image
import com.a56camera.camera.CaptureMode

data class SceneDecision(
    val suggestedMode: CaptureMode,
    val reason: String
)

class PreviewSceneAnalyzer {

    fun analyze(previewFrame: Image): SceneDecision {
        val luma = computeAverageLuma(previewFrame)
        return when {
            luma < 15 -> SceneDecision(CaptureMode.NIGHT, "Düşük ışık algılandı (Gece Modu Öneriliyor)")
            luma > 85 -> SceneDecision(CaptureMode.PHOTO, "Yüksek parlaklık (HDR Etkin)")
            else -> SceneDecision(CaptureMode.PHOTO, "Normal ışık")
        }
    }

    private fun computeAverageLuma(frame: Image): Double {
        val plane = frame.planes.getOrNull(0) ?: return 50.0
        val buffer = plane.buffer
        var sum = 0L
        var count = 0
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = frame.width
        val height = frame.height
        val yArray = ByteArray(buffer.remaining())
        buffer.get(yArray)
        for (y in 0 until height step 8) {
            val rowStart = y * rowStride
            for (x in 0 until width step 8) {
                val idx = rowStart + x * pixelStride
                if (idx < yArray.size) {
                    sum += yArray[idx].toInt() and 0xFF
                    count++
                }
            }
        }
        return if (count > 0) sum.toDouble() / count else 50.0
    }
}

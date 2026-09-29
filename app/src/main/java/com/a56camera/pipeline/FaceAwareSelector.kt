package com.a56camera.pipeline

import android.graphics.Bitmap
import android.graphics.Rect

data class FaceInfo(
    val faceId: Int,
    val bounds: Rect,
    val eyesOpenScore: Float,
    val sharpness: Float
)

interface FaceAnalyzer {
    fun analyze(bitmap: Bitmap): List<FaceInfo>
}

class FaceAwareSelector(private val analyzer: FaceAnalyzer) {
    fun selectBestComposite(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("Kare bulunamadı")
        // Her karede yüzleri analiz et
        // Her yüz için en yüksek skorlu kareyi seç ve birleştir
        return frames.first()
    }
}

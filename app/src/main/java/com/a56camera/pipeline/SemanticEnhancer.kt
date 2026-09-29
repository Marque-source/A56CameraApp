package com.a56camera.pipeline

import android.graphics.Bitmap

interface SegmentationModel {
    fun run(bitmap: Bitmap): SegmentationMask
}

data class SegmentationMask(
    val width: Int,
    val height: Int,
    val labels: IntArray // her piksel için sınıf id
)

class SemanticEnhancer(private val model: SegmentationModel) {
    fun enhance(bitmap: Bitmap): Bitmap {
        val mask = model.run(bitmap)
        // Gökyüzü: saturation+contrast
        // Cilt: soft denoise
        // Saç/kenar: sharpen
        return applyPerRegion(bitmap, mask)
    }

    private fun applyPerRegion(bitmap: Bitmap, mask: SegmentationMask): Bitmap {
        // TODO: bölge bazlı renk/kontrast haritası uygulama
        return bitmap
    }
}

package com.a56camera.pipeline

import org.opencv.core.Mat
import org.opencv.photo.Photo

object DenoisePipeline {

    fun process(rgb: Mat): Mat {
        val denoised = Mat()
        return try {
            Photo.fastNlMeansDenoisingColored(rgb, denoised, 3f, 3f, 7, 21)
            val detail = Mat()
            Photo.detailEnhance(denoised, detail, 10f, 0.15f)
            denoised.release()
            detail
        } catch (e: Exception) {
            denoised.release()
            rgb.clone()
        }
    }
}

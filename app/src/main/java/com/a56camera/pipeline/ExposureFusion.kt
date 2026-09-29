package com.a56camera.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.photo.Photo

object ExposureFusion {

    fun merge(frames: List<Mat>): Mat {
        if (frames.isEmpty()) throw IllegalArgumentException("Kare bulunamadı")
        if (frames.size == 1) return frames[0].clone()

        val aligned = frames.map { it.clone() }
        val dst = Mat()
        try {
            val mergeMertens = Photo.createMergeMertens()
            mergeMertens.process(aligned, dst)
            Core.normalize(dst, dst, 0.0, 255.0, Core.NORM_MINMAX)
            dst.convertTo(dst, CvType.CV_32FC3)
        } catch (e: Exception) {
            aligned.forEach { it.release() }
            return frames[0].clone()
        }

        aligned.forEach { it.release() }
        return dst
    }
}

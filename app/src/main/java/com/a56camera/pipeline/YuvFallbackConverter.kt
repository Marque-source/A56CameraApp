package com.a56camera.pipeline

import com.a56camera.camera.YuvFrame
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Hızlı ve doğru YUV_420_888 (NV21) → RGB OpenCV Mat dönüştürücü.
 */
object YuvFallbackConverter {

    fun toMat(frame: YuvFrame): Mat {
        val width = frame.width
        val height = frame.height
        val nv21 = ByteArray(width * height + width * height / 2)

        // Y Düzlemi
        for (row in 0 until height) {
            val srcPos = row * frame.yRowStride
            val destPos = row * width
            if (srcPos + width <= frame.y.size && destPos + width <= nv21.size) {
                System.arraycopy(frame.y, srcPos, nv21, destPos, width)
            }
        }

        // UV Düzlemi -> NV21 (V, U interleaved)
        val uvH = height / 2
        val uvW = width / 2
        val uvOffset = width * height
        for (row in 0 until uvH) {
            for (col in 0 until uvW) {
                val vIdx = row * frame.vRowStride + col * frame.vPixelStride
                val uIdx = row * frame.uRowStride + col * frame.uPixelStride
                val outIdx = uvOffset + row * width + col * 2
                if (vIdx < frame.v.size && uIdx < frame.u.size && outIdx + 1 < nv21.size) {
                    nv21[outIdx] = frame.v[vIdx]
                    nv21[outIdx + 1] = frame.u[uIdx]
                }
            }
        }

        val nv21Mat = Mat(height + height / 2, width, CvType.CV_8UC1)
        nv21Mat.put(0, 0, nv21)

        val rgb = Mat()
        Imgproc.cvtColor(nv21Mat, rgb, Imgproc.COLOR_YUV2RGB_NV21, 3)
        nv21Mat.release()
        return rgb
    }
}

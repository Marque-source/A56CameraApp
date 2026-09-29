package com.a56camera.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

object OpenCvS24ColorGrading {

    fun apply(rgb: Mat): Mat {
        val result = rgb.clone()
        // 1. Lift shadows
        val shadowLift = Mat(result.size(), result.type(), Scalar(8.0, 6.0, 10.0))
        Core.add(result, shadowLift, result)
        shadowLift.release()

        // 2. Cilt tonları sıcak, diğerleri doygun (R*1.08, G*1.02, B*0.96)
        val channels = ArrayList<Mat>()
        Core.split(result, channels)
        if (channels.size >= 3) {
            Core.multiply(channels[0], Scalar(1.08), channels[0]) // R
            Core.multiply(channels[1], Scalar(1.02), channels[1]) // G
            Core.multiply(channels[2], Scalar(0.96), channels[2]) // B
            Core.merge(channels, result)
        }
        channels.forEach { it.release() }

        // 3. Soft S-curve tonemap
        val lut = createSCurve()
        Core.LUT(result, lut, result)
        lut.release()

        return result
    }

    private fun createSCurve(): Mat {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        for (i in 0 until 256) {
            val x = i / 255.0
            val y = (x * x * (3 - 2 * x)) * 255.0 // smoothstep
            lut.put(0, i, y.coerceIn(0.0, 255.0))
        }
        return lut
    }
}

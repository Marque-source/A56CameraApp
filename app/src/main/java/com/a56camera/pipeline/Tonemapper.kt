package com.a56camera.pipeline

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

/**
 * Linear RGB → sRGB görselleştirme.
 */
class Tonemapper {

    fun process(rgbLinear: Mat): Bitmap {
        // Gürültüyü azaltmak için hafif bilateral
        val filtered = Mat()
        org.opencv.imgproc.Imgproc.bilateralFilter(rgbLinear, filtered, 5, 0.1, 0.1)

        // sRGB gamma
        val gamma = Mat()
        Core.pow(filtered, 1.0 / 2.2, gamma)

        // 0..1 aralığında tut
        Core.min(gamma, Scalar.all(1.0), gamma)
        Core.max(gamma, Scalar.all(0.0), gamma)

        // 0..255
        val eightBit = Mat()
        Core.multiply(gamma, Scalar.all(255.0), eightBit)
        eightBit.convertTo(eightBit, CvType.CV_8UC3)

        val bitmap = Bitmap.createBitmap(eightBit.cols(), eightBit.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(eightBit, bitmap)

        filtered.release()
        gamma.release()
        eightBit.release()

        return bitmap
    }
}

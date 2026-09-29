package com.a56camera.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Düzeltilmiş ve Yumuşatılmış S24 Color Grading Motoru (OpenCV Mat).
 */
object S24ColorGrading {

    fun apply(input: Mat): Mat {
        val floatInput = Mat()
        input.convertTo(floatInput, CvType.CV_32FC3, 1.0 / 255.0)

        // 1. S-curve kontrast (daha yumuşak)
        val contrasted = applySCurve(floatInput)
        floatInput.release()

        // 2. Gölgeleri aydınlat, highlight'ları sıkıştır
        val lifted = liftShadows(contrasted)
        contrasted.release()

        // 3. Saturation + vibrance
        val saturated = boostSaturation(lifted, saturationScale = 1.12f)
        lifted.release()

        // 4. Split toning (soğuk gölgeler, sıcak highlightlar)
        val toned = splitTone(saturated)
        saturated.release()

        // 5. Clamp ve 8-bit
        Core.min(toned, Scalar(1.0, 1.0, 1.0), toned)
        Core.max(toned, Scalar(0.0, 0.0, 0.0), toned)

        val output = Mat()
        toned.convertTo(output, CvType.CV_8UC3, 255.0)
        toned.release()

        return output
    }

    private fun applySCurve(src: Mat): Mat {
        val x2 = Mat()
        Core.multiply(src, src, x2)
        val x3 = Mat()
        Core.multiply(x2, src, x3)

        val sCurve = Mat()
        val tempNeg = Mat()
        Core.multiply(x3, Scalar(-2.0, -2.0, -2.0), tempNeg)
        Core.addWeighted(x2, 3.0, tempNeg, 1.0, 0.0, sCurve)

        val out = Mat()
        Core.addWeighted(src, 0.6, sCurve, 0.4, 0.0, out)

        x2.release(); x3.release(); tempNeg.release(); sCurve.release()
        return out
    }

    private fun liftShadows(src: Mat): Mat {
        val out = Mat()
        Core.pow(src, 0.82, out)
        Core.addWeighted(src, 0.7, out, 0.3, 0.0, out)
        return out
    }

    private fun boostSaturation(src: Mat, saturationScale: Float): Mat {
        val hsv = Mat()
        Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGB2HSV)

        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)

        if (channels.size >= 2) {
            Core.multiply(channels[1], Scalar(saturationScale.toDouble()), channels[1])
            Core.min(channels[1], Scalar(1.0), channels[1])
            Core.merge(channels, hsv)
        }

        channels.forEach { it.release() }

        val rgb = Mat()
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        return rgb
    }

    private fun splitTone(src: Mat): Mat {
        val channels = ArrayList<Mat>()
        Core.split(src, channels)

        if (channels.size < 3) {
            channels.forEach { it.release() }
            return src
        }

        // Gölgelerde (koyu bölgeler) mavi-yeşil lift
        val shadowMask = Mat()
        Core.compare(channels[2], Scalar(0.35), shadowMask, Core.CMP_LT) // B < 0.35

        val shadowBoost = Mat()
        Core.add(channels[0], Scalar(0.03), shadowBoost)
        shadowBoost.copyTo(channels[0], shadowMask)

        // Highlight'larda sıcaklık
        val highlightMask = Mat()
        Core.compare(channels[0], Scalar(0.65), highlightMask, Core.CMP_GT) // R > 0.65

        val highlightBoost = Mat()
        Core.add(channels[2], Scalar(0.02), highlightBoost)
        highlightBoost.copyTo(channels[2], highlightMask)

        Core.merge(channels, src)

        channels.forEach { it.release() }
        shadowMask.release(); shadowBoost.release()
        highlightMask.release(); highlightBoost.release()

        return src
    }
}

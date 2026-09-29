package com.a56camera.pipeline

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Bayer RAW16 → RGB float32 Mat
 * Adımlar: black level subtract, demosaic, white balance, color correction, normalize.
 */
class RawToRgbConverter {

    fun convert(
        rawFrame: RawFrame,
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        bayerPattern: BayerPattern = BayerPattern.RGGB
    ): Mat {
        // 1. Black level subtract
        val blackLevel = getBlackLevel(result, characteristics)
        val normalized = subtractBlackLevel(rawFrame.mat, blackLevel)

        // 2. Demosaic
        val rgb = demosaic(normalized, bayerPattern)

        // 3. White balance
        val wbGains = getWhiteBalanceGains(result)
        applyWhiteBalance(rgb, wbGains)

        // 4. Color correction matrix (Android default veya özel)
        val ccm = getColorCorrectionMatrix(result, characteristics)
        applyCCM(rgb, ccm)

        // 5. Normalize 0..65535 → 0..1
        Core.convertScaleAbs(rgb, rgb, 1.0 / 65535.0, 0.0)

        // 6. Gamma (sRGB)
        applyGamma(rgb, gamma = 2.2)

        normalized.release()
        return rgb
    }

    private fun subtractBlackLevel(raw: Mat, blackLevel: Double): Mat {
        val out = Mat()
        val temp = Mat()
        raw.convertTo(temp, CvType.CV_32FC1)
        Core.subtract(temp, scalarAll(blackLevel), out)
        Core.max(out, scalarAll(0.0), out)
        temp.release()
        return out
    }

    private fun demosaic(raw: Mat, pattern: BayerPattern): Mat {
        val rgb = Mat()
        val code = when (pattern) {
            BayerPattern.RGGB -> Imgproc.COLOR_BayerRG2RGB
            BayerPattern.BGGR -> Imgproc.COLOR_BayerBG2RGB
            BayerPattern.GRBG -> Imgproc.COLOR_BayerGR2RGB
            BayerPattern.GBRG -> Imgproc.COLOR_BayerGB2RGB
        }
        Imgproc.cvtColor(raw, rgb, code)
        return rgb
    }

    private fun getWhiteBalanceGains(result: CaptureResult): FloatArray {
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) ?: return floatArrayOf(1f, 1f, 1f)
        return floatArrayOf(
            neutral[0].toFloat(),
            neutral[1].toFloat(),
            neutral[2].toFloat()
        )
    }

    private fun applyWhiteBalance(rgb: Mat, gains: FloatArray) {
        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        if (channels.size >= 3) {
            val rGain = gains.getOrElse(0) { 1f }.toDouble()
            val gGain = gains.getOrElse(1) { 1f }.toDouble()
            val bGain = gains.getOrElse(2) { 1f }.toDouble()
            Core.multiply(channels[0], scalarAll(rGain), channels[0])
            Core.multiply(channels[1], scalarAll(gGain), channels[1])
            Core.multiply(channels[2], scalarAll(bGain), channels[2])
            Core.merge(channels, rgb)
        }
        channels.forEach { it.release() }
    }

    private fun applyCCM(rgb: Mat, ccm: FloatArray?) {
        if (ccm == null || ccm.size < 9) return
        val channels = mutableListOf<Mat>()
        Core.split(rgb, channels)
        if (channels.size < 3) {
            channels.forEach { it.release() }
            return
        }
        val r = channels[0]
        val g = channels[1]
        val b = channels[2]

        val newR = Mat()
        val newG = Mat()
        val newB = Mat()
        val t1 = Mat(); val t2 = Mat()

        Core.multiply(r, scalarAll(ccm[0].toDouble()), newR)
        Core.multiply(g, scalarAll(ccm[1].toDouble()), t1)
        Core.add(newR, t1, newR)
        Core.multiply(b, scalarAll(ccm[2].toDouble()), t2)
        Core.add(newR, t2, newR)

        Core.multiply(r, scalarAll(ccm[3].toDouble()), newG)
        Core.multiply(g, scalarAll(ccm[4].toDouble()), t1)
        Core.add(newG, t1, newG)
        Core.multiply(b, scalarAll(ccm[5].toDouble()), t2)
        Core.add(newG, t2, newG)

        Core.multiply(r, scalarAll(ccm[6].toDouble()), newB)
        Core.multiply(g, scalarAll(ccm[7].toDouble()), t1)
        Core.add(newB, t1, newB)
        Core.multiply(b, scalarAll(ccm[8].toDouble()), t2)
        Core.add(newB, t2, newB)

        t1.release(); t2.release()

        channels[0] = newR
        channels[1] = newG
        channels[2] = newB
        Core.merge(channels, rgb)
        channels.forEach { it.release() }
    }

    private fun applyGamma(rgb: Mat, gamma: Double) {
        Core.pow(rgb, 1.0 / gamma, rgb)
        Core.min(rgb, scalarAll(1.0), rgb)
    }

    private fun getBlackLevel(result: CaptureResult, ch: CameraCharacteristics): Double {
        return result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.get(0)?.toDouble()
            ?: ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.getOffsetForIndex(0, 0)?.toDouble()
            ?: 256.0
    }

    private fun getColorCorrectionMatrix(result: CaptureResult, ch: CameraCharacteristics): FloatArray? {
        return result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { rggbMatrix ->
            FloatArray(9) { i -> rggbMatrix.getElement(i / 3, i % 3).toFloat() }
        }
    }

    private fun scalarAll(v: Double): Scalar = Scalar.all(v)

    enum class BayerPattern { RGGB, BGGR, GRBG, GBRG }
}

package com.a56camera.camera

import android.hardware.camera2.CaptureResult
import android.media.Image

/**
 * YUV_420_888 plane veri modeli — AWB, Color Gains & Transform destekli.
 */
data class YuvFrame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val yRowStride: Int,
    val uRowStride: Int,
    val vRowStride: Int,
    val uPixelStride: Int,
    val vPixelStride: Int,
    val iso: Int,
    val exposureNs: Long,
    val evShift: Float = 0f,
    val colorCorrectionGains: FloatArray? = null,
    val colorCorrectionTransform: FloatArray? = null,
    val awbMode: Int? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as YuvFrame
        return timestampNs == other.timestampNs
    }

    override fun hashCode(): Int = timestampNs.hashCode()

    fun withSwappedUv(): YuvFrame = copy(
        u = v,
        v = u,
        uRowStride = vRowStride,
        vRowStride = uRowStride,
        uPixelStride = vPixelStride,
        vPixelStride = uPixelStride
    )

    fun computeMeanY(): Double {
        if (y.isEmpty()) return 0.0
        var sum = 0L
        val step = 16
        var count = 0
        var i = 0
        while (i < y.size) {
            sum += (y[i].toInt() and 0xFF)
            count++
            i += step
        }
        return if (count > 0) sum.toDouble() / count else 0.0
    }

    fun logStats(tag: String = "YuvFrame") {
        val yAvg = if (y.isNotEmpty()) y.map { it.toInt() and 0xFF }.average() else 0.0
        val uAvg = if (u.isNotEmpty()) u.map { it.toInt() and 0xFF }.average() else 0.0
        val vAvg = if (v.isNotEmpty()) v.map { it.toInt() and 0xFF }.average() else 0.0
        android.util.Log.d(
            tag,
            "YUV plane stats | Y avg=$yAvg U avg=$uAvg V avg=$vAvg | " +
            "yRowStride=$yRowStride uRowStride=$uRowStride vRowStride=$vRowStride " +
            "uPixelStride=$uPixelStride vPixelStride=$vPixelStride | Gains=${colorCorrectionGains?.joinToString()}"
        )
    }

    companion object {
        fun fromImage(image: Image, result: CaptureResult?, evShift: Float = 0f): YuvFrame? {
            return try {
                val planes = image.planes ?: return null
                if (planes.size < 3) return null

                val yBuf = planes[0].buffer?.apply { rewind() } ?: return null
                val uBuf = planes[1].buffer?.apply { rewind() } ?: return null
                val vBuf = planes[2].buffer?.apply { rewind() } ?: return null

                val yRem = yBuf.remaining()
                val uRem = uBuf.remaining()
                val vRem = vBuf.remaining()
                if (yRem <= 0 || uRem <= 0 || vRem <= 0) return null

                val y = ByteArray(yRem).also { yBuf.get(it) }
                val u = ByteArray(uRem).also { uBuf.get(it) }
                val v = ByteArray(vRem).also { vBuf.get(it) }

                val gains = result?.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                    floatArrayOf(it.red, (it.greenEven + it.greenOdd) / 2f, it.blue)
                }
                val transform = result?.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { cst ->
                    FloatArray(9).also { arr ->
                        for (r in 0..2) {
                            for (c in 0..2) {
                                val rat = cst.getElement(c, r)
                                arr[r * 3 + c] = rat.numerator.toFloat() / rat.denominator.toFloat()
                            }
                        }
                    }
                }

                YuvFrame(
                    width = image.width,
                    height = image.height,
                    timestampNs = image.timestamp,
                    y = y, u = u, v = v,
                    yRowStride = planes[0].rowStride,
                    uRowStride = planes[1].rowStride,
                    vRowStride = planes[2].rowStride,
                    uPixelStride = planes[1].pixelStride,
                    vPixelStride = planes[2].pixelStride,
                    iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100,
                    exposureNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L,
                    evShift = evShift,
                    colorCorrectionGains = gains,
                    colorCorrectionTransform = transform,
                    awbMode = result?.get(CaptureResult.CONTROL_AWB_MODE)
                )
            } catch (e: Throwable) {
                null
            }
        }
    }
}

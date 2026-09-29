package com.a56camera.camera

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * YUV_420_888 -> NV21 byte[] ve OpenCV RGB Mat dönüştürücü.
 */
object YuvUtils {

    /** YUV_420_888 -> NV21 byte[] (planar veya semi-planar her ikisini de tolere eder) */
    fun imageToNv21(image: Image): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h + w * h / 2)

        // Y
        val y = image.planes[0]
        val yBuf = y.buffer.apply { rewind() }
        val yRowStride = y.rowStride
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            val len = kotlin.math.min(w, yBuf.remaining())
            if (len > 0 && pos + len <= out.size) {
                yBuf.get(out, pos, len)
            }
            pos += w
        }

        // UV
        val u = image.planes[1]
        val v = image.planes[2]
        val uvH = h / 2
        if (u.pixelStride == 2) {
            // semi-planar (NV21 varsayımı, genelde vPlane'dan okunur)
            val vRowStride = v.rowStride
            v.buffer.apply { rewind() }
            for (row in 0 until uvH) {
                val srcPos = row * vRowStride
                if (srcPos < v.buffer.capacity()) {
                    v.buffer.position(srcPos)
                    val len = kotlin.math.min(w, v.buffer.remaining())
                    if (len > 0 && pos + len <= out.size) {
                        v.buffer.get(out, pos, len)
                    }
                }
                pos += w
            }
        } else {
            // planar I420 -> NV21: V sonra U
            v.buffer.apply { rewind() }
            u.buffer.apply { rewind() }
            for (row in 0 until uvH) {
                val vPos = row * v.rowStride
                if (vPos < v.buffer.capacity()) {
                    v.buffer.position(vPos)
                    val len = kotlin.math.min(w / 2, v.buffer.remaining())
                    if (len > 0 && pos + len <= out.size) {
                        v.buffer.get(out, pos, len)
                    }
                }
                pos += w / 2

                val uPos = row * u.rowStride
                if (uPos < u.buffer.capacity()) {
                    u.buffer.position(uPos)
                    val len = kotlin.math.min(w / 2, u.buffer.remaining())
                    if (len > 0 && pos + len <= out.size) {
                        u.buffer.get(out, pos, len)
                    }
                }
                pos += w / 2
            }
        }
        return out
    }

    /** NV21 -> RGB Mat */
    fun nv21ToRgb(nv21: ByteArray, w: Int, h: Int): Mat {
        val yuv = Mat(h + h / 2, w, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val rgb = Mat()
        Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_NV21)
        yuv.release()
        return rgb
    }
}

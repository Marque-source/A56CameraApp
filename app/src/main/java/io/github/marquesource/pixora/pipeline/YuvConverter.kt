package io.github.marquesource.pixora.pipeline

import android.graphics.Bitmap
import io.github.marquesource.pixora.camera.YuvFrame
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

object YuvConverter {

    fun toRgbMat(frame: YuvFrame): Mat {
        val yuv = Mat(frame.height + frame.height / 2, frame.width, CvType.CV_8UC1)
        val data = ByteArray((yuv.total() * yuv.elemSize()).toInt())

        // Y Düzlemi
        for (row in 0 until frame.height) {
            val srcOffset = row * frame.yRowStride
            val destOffset = row * frame.width
            if (srcOffset + frame.width <= frame.y.size && destOffset + frame.width <= data.size) {
                System.arraycopy(frame.y, srcOffset, data, destOffset, frame.width)
            }
        }

        // UV Düzlemi
        val uvH = frame.height / 2
        for (row in 0 until uvH) {
            for (col in 0 until frame.width / 2) {
                val vIdx = row * frame.vRowStride + col * frame.vPixelStride
                val uIdx = row * frame.uRowStride + col * frame.uPixelStride
                if (vIdx < frame.v.size && uIdx < frame.u.size) {
                    val pos = frame.height * frame.width + row * frame.width + col * 2
                    if (pos + 1 < data.size) {
                        data[pos] = frame.v[vIdx]
                        data[pos + 1] = frame.u[uIdx]
                    }
                }
            }
        }

        yuv.put(0, 0, data)

        val rgb = Mat()
        Imgproc.cvtColor(yuv, rgb, Imgproc.COLOR_YUV2RGB_NV21)
        yuv.release()
        return rgb
    }

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat(bitmap.height, bitmap.width, CvType.CV_8UC4)
        org.opencv.android.Utils.bitmapToMat(bitmap, mat)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)
        mat.release()
        return bgr
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val rgb = Mat()
        if (mat.channels() == 3) {
            Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_BGR2RGBA)
        } else {
            mat.copyTo(rgb)
        }
        val bmp = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        org.opencv.android.Utils.matToBitmap(rgb, bmp)
        rgb.release()
        return bmp
    }
}

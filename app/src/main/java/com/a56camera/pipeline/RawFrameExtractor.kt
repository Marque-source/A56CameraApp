package com.a56camera.pipeline

import android.media.Image
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Android RAW16 buffer'ını Bayer uint16 Mat'e çevirir.
 * RAW16: 16-bit, little-endian, Bayer düzeninde.
 */
object RawFrameExtractor {

    private const val TAG = "RawFrameExtractor"

    fun extract(image: Image): RawFrame? {
        val plane = image.planes.getOrNull(0) ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer

        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val shortArray = ShortArray(width * height)
        var idx = 0
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val pos = rowStart + x * 2
                if (pos + 1 < bytes.size) {
                    val low = bytes[pos].toInt() and 0xFF
                    val high = bytes[pos + 1].toInt() and 0xFF
                    shortArray[idx++] = ((high shl 8) or low).toShort()
                } else {
                    shortArray[idx++] = 0
                }
            }
        }

        val mat = Mat(height, width, CvType.CV_16UC1)
        mat.put(0, 0, shortArray)
        return RawFrame(mat, width, height)
    }

    fun close(frame: RawFrame) {
        frame.mat.release()
    }
}

data class RawFrame(
    val mat: Mat,
    val width: Int,
    val height: Int
)

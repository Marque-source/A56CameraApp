package io.github.marquesource.pixora.utils

import android.media.Image
import android.graphics.ImageFormat

/**
 * YUV_420_888 karelerinden RGB + Luma histogramı hesaplar.
 *
 * Performans için örnekleme adımı (step) kullanılır — her pikseli değil, seyrek
 * örnekleri işler. Sonuç 256 kutuluk dört dizidir (R, G, B, Luma).
 */
object HistogramCalculator {

    const val BINS = 256

    data class Histogram(
        val red: IntArray = IntArray(BINS),
        val green: IntArray = IntArray(BINS),
        val blue: IntArray = IntArray(BINS),
        val luma: IntArray = IntArray(BINS)
    )

    /**
     * Önizleme karesinin küçük bir Bitmap kopyasından (TextureView.getBitmap) histogram hesaplar.
     * Ek bir kamera akışı gerektirmez.
     */
    fun fromBitmap(bitmap: android.graphics.Bitmap, step: Int = 2): Histogram {
        val hist = Histogram()
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var y = 0
        while (y < h) {
            var x = 0
            val row = y * w
            while (x < w) {
                val c = pixels[row + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                hist.red[r]++
                hist.green[g]++
                hist.blue[b]++
                // BT.601 luma
                hist.luma[((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)]++
                x += step
            }
            y += step
        }
        return hist
    }

    /**
     * YUV_420_888 Image'dan histogram hesaplar.
     * @param step örnekleme adımı (piksel atlama); büyük değer = daha hızlı, daha kaba
     */
    fun fromYuv(image: Image, step: Int = 8): Histogram {
        require(image.format == ImageFormat.YUV_420_888) { "YUV_420_888 bekleniyor" }
        val hist = Histogram()

        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var row = 0
        while (row < height) {
            var col = 0
            while (col < width) {
                val yIndex = row * yRowStride + col * yPixelStride
                if (yIndex >= yBuffer.limit()) { col += step; continue }
                val y = yBuffer.get(yIndex).toInt() and 0xFF

                val uvRow = row / 2
                val uvCol = col / 2
                val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride
                val u: Int
                val v: Int
                if (uvIndex < uBuffer.limit() && uvIndex < vBuffer.limit()) {
                    u = (uBuffer.get(uvIndex).toInt() and 0xFF) - 128
                    v = (vBuffer.get(uvIndex).toInt() and 0xFF) - 128
                } else { u = 0; v = 0 }

                // YUV → RGB (BT.601)
                val r = (y + 1.402 * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772 * u).toInt().coerceIn(0, 255)

                hist.red[r]++
                hist.green[g]++
                hist.blue[b]++
                hist.luma[y]++

                col += step
            }
            row += step
        }
        return hist
    }
}

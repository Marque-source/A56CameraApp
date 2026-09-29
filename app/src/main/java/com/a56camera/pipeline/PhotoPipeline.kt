package com.a56camera.pipeline

import com.a56camera.camera.YuvFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs

/**
 * Tam Çözünürlüklü 16-Bit YUV Mertens Exposure Fusion İşleme Hattı (4080x3060 Netlik).
 */
class PhotoPipeline {

    suspend fun processBurst(
        frames: List<YuvFrame>,
        outputPath: String
    ): Boolean = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) return@withContext false
        try {
            // 1. YUV → RGB (Tam çözünürlük 4080x3060 netlik korunur)
            val rgbFrames = frames.map { YuvConverter.toRgbMat(it) }

            // 2. Mertens Exposure Fusion
            val merged = ExposureFusion.merge(rgbFrames)
            rgbFrames.forEach(Mat::release)

            // 3. 16-bit Hassas Hassasiyet İşleme
            Core.normalize(merged, merged, 0.0, 65535.0, Core.NORM_MINMAX)
            merged.convertTo(merged, CvType.CV_16UC3)

            // 4. Samsung S24 Color Grading
            val graded = OpenCvS24ColorGrading.apply(merged)
            merged.release()

            // 5. JPEG için 8-bit Hassasiyete Dönüştürme
            val output8 = Mat()
            graded.convertTo(output8, CvType.CV_8UC3, 1.0 / 256.0)
            graded.release()

            // 6. %97 Yüksek Kaliteli JPEG Kaydı
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 97)
            Imgcodecs.imwrite(outputPath, output8, params)
            output8.release()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

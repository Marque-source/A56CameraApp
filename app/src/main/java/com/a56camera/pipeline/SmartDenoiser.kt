package com.a56camera.pipeline

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Gürültü seviyesine uyarlanan gürültü azaltma.
 *
 * Önceki sürüm parlaklık (luma) kanalına hiç dokunmuyor, ISO 800 altında hiç çalışmıyordu.
 * Telefonun ISP gürültü azaltması da MINIMAL/OFF seçildiği için parlaklık gürültüsü hattın
 * hiçbir yerinde temizlenmiyor ve ton eşleme + keskinleştirme onu büyütüyordu.
 *
 * Şimdi:
 *  - Gürültü miktarı görüntünün kendisinden ölçülür (ISO'ya güvenilmez: manuel braket ISO'yu düşürür).
 *  - Parlaklık kanalına gürültüyle orantılı, hafif Non-Local Means uygulanır (dokuyu korur).
 *  - Renk kanallarındaki lekeler (kroma gürültüsü) her zaman hafifçe yumuşatılır.
 */
class SmartDenoiser {

    companion object {
        private const val TAG = "SmartDenoiser"

        /** NLM gücü = ölçülen gürültü × bu katsayı. Büyüdükçe daha pürüzsüz ama daha yumuşak. */
        const val LUMA_STRENGTH = 1.3

        /** Bu seviyenin altındaki gürültü görünmez; NLM atlanır (hız + doku). */
        const val MIN_SIGMA = 1.2
    }

    fun denoise(src: Mat, @Suppress("UNUSED_PARAMETER") isoValue: Int): Mat {
        val startTime = System.currentTimeMillis()

        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)

        // 1. PARLAKLIK: gürültüyü ölç, yeterince varsa NLM uygula
        val sigma = estimateNoiseSigma(channels[0])
        if (sigma >= MIN_SIGMA) {
            val h = (sigma * LUMA_STRENGTH).coerceAtMost(12.0).toFloat()
            val cleanL = Mat()
            Photo.fastNlMeansDenoising(channels[0], cleanL, h, 5, 9)
            channels[0].release()
            channels[0] = cleanL
        }

        // 2. RENK: kroma lekelerini hafif Gaussian ile yumuşat
        for (i in 1..2) {
            val f = Mat()
            Imgproc.GaussianBlur(channels[i], f, Size(3.0, 3.0), 0.8)
            channels[i].release()
            channels[i] = f
        }

        val merged = Mat()
        Core.merge(channels, merged)
        channels.forEach { it.release() }
        lab.release()

        val result = Mat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_Lab2BGR)
        merged.release()

        Log.i(TAG, "Gürültü azaltma: sigma=%.2f, süre=%dms".format(sigma, System.currentTimeMillis() - startTime))
        return result
    }

    /**
     * Tek kanallı 8-bit görüntüde gürültü standart sapması tahmini.
     * 3x3 Laplace benzeri çekirdek (Immerkær) yanıtının medyanı kullanılır; medyan kenarlardan
     * etkilenmediği için dokulu sahnelerde de gürültüyü abartmaz. Hız için 1/4 örnekleme yapılır.
     */
    fun estimateNoiseSigma(gray: Mat): Double {
        val kernel = Mat(3, 3, CvType.CV_32F)
        kernel.put(0, 0, 1.0, -2.0, 1.0, -2.0, 4.0, -2.0, 1.0, -2.0, 1.0)

        // Tam çözünürlükte kırp (piksel ölçeğindeki gürültü korunmalı, küçültme gürültüyü siler)
        val maxSide = 1600
        val roi = if (gray.cols() > maxSide || gray.rows() > maxSide) {
            val w = minOf(gray.cols(), maxSide); val h = minOf(gray.rows(), maxSide)
            gray.submat((gray.rows() - h) / 2, (gray.rows() - h) / 2 + h, (gray.cols() - w) / 2, (gray.cols() - w) / 2 + w)
        } else gray

        val resp = Mat()
        Imgproc.filter2D(roi, resp, CvType.CV_32F, kernel)
        val absResp = Mat()
        Core.absdiff(resp, org.opencv.core.Scalar(0.0), absResp)
        val abs8 = Mat()
        absResp.convertTo(abs8, CvType.CV_8U) // 255 üstü doyar; medyan için sorun değil

        val hist = Mat()
        Imgproc.calcHist(listOf(abs8), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val total = abs8.total().toDouble()
        var cum = 0.0
        var median = 0
        for (i in 0 until 256) {
            cum += hist.get(i, 0)[0]
            if (cum >= total / 2) { median = i; break }
        }
        listOf(kernel, resp, absResp, abs8, hist).forEach { it.release() }
        if (roi !== gray) roi.release()

        // Çekirdeğin gürültüye yanıtı σ·√(1+4+1+4+16+4+1+4+1) = 6σ; medyan/0.6745 → std
        return median / (0.6745 * 6.0)
    }
}

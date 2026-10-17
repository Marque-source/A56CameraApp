package com.a56camera.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Aynı pozlamayla çekilmiş, hizalanmış karelerin hareket korumalı ağırlıklı ortalaması.
 *
 * Her kare referansla piksel piksel karşılaştırılır: fark [motionThreshold]'dan büyükse
 * (sahnede hareket var) o piksel o kareden alınmaz, değilse eşit ağırlıkla ortalamaya girer.
 * N karenin eşit ortalaması gürültüyü √N kat azaltır (5 kare ≈ %55).
 *
 * Eski NEON yolu kareleri ikişer ikişer ortalıyordu: son kare %50, ilk kareler %6 ağırlık
 * alıyor ve gürültü azaltımı %42'de kalıyordu.
 */
object TemporalMerge {

    /**
     * @param frames 8-bit BGR kareler; ilk eleman referans kabul edilir (ağırlığı her yerde 1)
     * @param motionThreshold 0..1 ölçeğinde hareket eşiği (0.20 ≈ 51/255)
     * @return 0..1 aralığında CV_32FC3 sonuç (çağıran 8-bit veya 16-bit'e çevirir)
     */
    fun weightedAverage(frames: List<Mat>, motionThreshold: Double = 0.20): Mat {
        require(frames.isNotEmpty()) { "En az 1 kare gerekli" }
        val rows = frames[0].rows()
        val cols = frames[0].cols()

        val refFloat = Mat()
        frames[0].convertTo(refFloat, CvType.CV_32FC3, 1.0 / 255.0)

        val accumulator = refFloat.clone()
        val weightSum = Mat(rows, cols, CvType.CV_32FC1, Scalar(1.0))

        val currFloat = Mat()
        val diff = Mat()
        val maxDiff = Mat()
        val weight = Mat()
        val weight3ch = Mat()
        val weighted = Mat()
        try {
            for (i in 1 until frames.size) {
                frames[i].convertTo(currFloat, CvType.CV_32FC3, 1.0 / 255.0)

                // Kanallardaki en büyük mutlak fark = hareket ölçüsü
                Core.absdiff(currFloat, refFloat, diff)
                val ch = mutableListOf<Mat>()
                Core.split(diff, ch)
                Core.max(ch[0], ch[1], maxDiff)
                Core.max(maxDiff, ch[2], maxDiff)
                ch.forEach { it.release() }

                // Fark <= eşik -> ağırlık 1, aksi halde 0 (hayalet önleme)
                Imgproc.threshold(maxDiff, weight, motionThreshold, 1.0, Imgproc.THRESH_BINARY_INV)
                Imgproc.cvtColor(weight, weight3ch, Imgproc.COLOR_GRAY2BGR)

                Core.multiply(currFloat, weight3ch, weighted)
                Core.add(accumulator, weighted, accumulator)
                Core.add(weightSum, weight, weightSum)
            }

            val weightSum3ch = Mat()
            Imgproc.cvtColor(weightSum, weightSum3ch, Imgproc.COLOR_GRAY2BGR)
            val result = Mat()
            Core.divide(accumulator, weightSum3ch, result)
            weightSum3ch.release()
            return result
        } finally {
            listOf(refFloat, accumulator, weightSum, currFloat, diff, maxDiff, weight, weight3ch, weighted)
                .forEach { it.release() }
        }
    }
}

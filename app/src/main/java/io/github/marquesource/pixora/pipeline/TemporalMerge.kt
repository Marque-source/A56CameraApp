package io.github.marquesource.pixora.pipeline

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
 *
 * Bellek: 12MP'de tam kare float ara görüntüler ~150 MB'lık parçalar demek (toplam 1 GB+).
 * Bu yüzden görüntü yatay şeritler halinde işlenir; ek bellek birkaç MB'ta kalır.
 */
object TemporalMerge {

    private const val STRIP_ROWS = 128

    /**
     * @param frames 8-bit BGR kareler; ilk eleman referans kabul edilir (ağırlığı her yerde 1)
     * @param motionThreshold 0..1 ölçeğinde hareket eşiği (0.20 ≈ 51/255)
     * @param produce16u true ise 16-bit doğrusal çıktı da üretilir (Expert RAW için)
     * @return 8-bit sonuç ve istenirse 16-bit sonuç
     */
    fun weightedAverage(
        frames: List<Mat>,
        motionThreshold: Double = 0.20,
        produce16u: Boolean = false
    ): Pair<Mat, Mat?> {
        require(frames.isNotEmpty()) { "En az 1 kare gerekli" }
        val rows = frames[0].rows()
        val cols = frames[0].cols()
        val out8 = Mat(rows, cols, CvType.CV_8UC3)
        val out16 = if (produce16u) Mat(rows, cols, CvType.CV_16UC3) else null

        val refF = Mat(); val acc = Mat(); val wSum = Mat()
        val curF = Mat(); val diff = Mat(); val maxDiff = Mat()
        val w = Mat(); val w3 = Mat(); val weighted = Mat(); val wSum3 = Mat(); val avg = Mat()
        val ch = mutableListOf<Mat>()
        try {
            var r0 = 0
            while (r0 < rows) {
                val r1 = minOf(rows, r0 + STRIP_ROWS)
                val refStrip = frames[0].rowRange(r0, r1)
                refStrip.convertTo(refF, CvType.CV_32FC3, 1.0 / 255.0)
                refF.copyTo(acc)
                wSum.create(r1 - r0, cols, CvType.CV_32FC1)
                wSum.setTo(Scalar(1.0))

                for (i in 1 until frames.size) {
                    frames[i].rowRange(r0, r1).convertTo(curF, CvType.CV_32FC3, 1.0 / 255.0)

                    // Kanallardaki en büyük mutlak fark = hareket ölçüsü
                    Core.absdiff(curF, refF, diff)
                    ch.forEach { it.release() }; ch.clear()
                    Core.split(diff, ch)
                    Core.max(ch[0], ch[1], maxDiff)
                    Core.max(maxDiff, ch[2], maxDiff)

                    // Fark <= eşik -> ağırlık 1, aksi halde 0 (hayalet önleme)
                    Imgproc.threshold(maxDiff, w, motionThreshold, 1.0, Imgproc.THRESH_BINARY_INV)
                    Imgproc.cvtColor(w, w3, Imgproc.COLOR_GRAY2BGR)
                    Core.multiply(curF, w3, weighted)
                    Core.add(acc, weighted, acc)
                    Core.add(wSum, w, wSum)
                }

                Imgproc.cvtColor(wSum, wSum3, Imgproc.COLOR_GRAY2BGR)
                Core.divide(acc, wSum3, avg)
                avg.convertTo(out8.rowRange(r0, r1), CvType.CV_8UC3, 255.0)
                out16?.let { avg.convertTo(it.rowRange(r0, r1), CvType.CV_16UC3, 65535.0) }
                r0 = r1
            }
            return out8 to out16
        } finally {
            ch.forEach { it.release() }
            listOf(refF, acc, wSum, curF, diff, maxDiff, w, w3, weighted, wSum3, avg).forEach { it.release() }
        }
    }
}

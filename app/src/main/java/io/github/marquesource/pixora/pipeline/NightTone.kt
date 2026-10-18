package io.github.marquesource.pixora.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.ln

/**
 * Gece ton eşleme: birleştirilmiş karayı doğal görünümle aydınlatır.
 *
 * Eski yol 8 bitlik parlaklık kanalına CLAHE + sert bir gama eğrisi + "siyah kilidi" uyguluyordu.
 * Telefonda bu üç sorun yaptı: koyu tonlar kademelendi (bantlanma), gölgeler simsiyah ezildi ve
 * görüntü "filtreli" göründü. Burada:
 *  - Hesap doğrusal ışıkta ve kayan noktada yapılır (sRGB → doğrusal → ton → sRGB);
 *    16 bitlik birleştirme çıktısı varsa kesir hassasiyeti korunur.
 *  - Sahne parlaklığına göre sınırlı bir pozlama kazancı seçilir (gece, gündüz gibi görünmesin).
 *  - Parlak yerler Reinhard omzu ile yumuşakça sıkıştırılır, gölgeler ezilmez.
 *  - Renk, parlaklık oranıyla taşınır; kroma gürültüsü büyümesin diye doygunluk hafifçe kısılır.
 *  - 8 bite inerken titreşim (dither) eklenir; düz karanlık alanlarda bant oluşmaz.
 */
object NightTone {

    /** Hedef orta ton (doğrusal). 0.18 gündüz gibi; gece hissi için biraz altı. */
    const val TARGET_KEY = 0.18
    const val MAX_GAIN = 8.0
    const val SATURATION = 0.8
    const val GRAY_WORLD = 0.5

    /**
     * @param src 8UC3 veya 16UC3 BGR (sRGB kodlu)
     * @return 8UC3 BGR
     */
    fun render(
        src: Mat,
        targetKey: Double = TARGET_KEY,
        maxGain: Double = MAX_GAIN,
        saturation: Double = SATURATION,
        grayWorld: Double = GRAY_WORLD
    ): Mat {
        val scale = if (src.depth() == CvType.CV_16U) 1.0 / 65535.0 else 1.0 / 255.0
        val f = Mat()
        src.convertTo(f, CvType.CV_32FC3, scale)

        // sRGB → doğrusal (2.2 gama yaklaşıklığı; ISP çıktısı için yeterli)
        Core.pow(f, 2.2, f)

        // Karanlıkta telefonun beyaz dengesi çoğu zaman tutmuyor (burst kareleri mor/yeşil kalıyor).
        // Gri dünya varsayımına doğru KISMİ düzeltme: ortam ışığının rengi korunur, aşırı sapma alınır.
        if (grayWorld > 0.0) {
            val m = Core.mean(f).`val`
            val g = (m[0] + m[1] + m[2]) / 3.0
            val gains = DoubleArray(3) { c ->
                val full = if (m[c] > 1e-6) g / m[c] else 1.0
                Math.pow(full, grayWorld).coerceIn(0.7, 1.6)
            }
            Core.multiply(f, Scalar(gains[0], gains[1], gains[2]), f)
        }

        val y = luminance(f)
        val stats = sceneStats(y)
        val gain = (targetKey / stats.geoMean).coerceIn(1.0, maxGain)

        // Siyah noktası: en koyu %0,5'in yarısı kadar çek (ezmeden derinlik verir)
        val black = stats.p005 * 0.5
        val white = ((stats.p995 - black) * gain).coerceAtLeast(1.0)   // Reinhard beyaz noktası

        // Yeni parlaklık: Yt = g·(Y−b) · (1 + g·(Y−b)/W²) / (1 + g·(Y−b))
        val ys = Mat()
        Core.subtract(y, Scalar(black), ys)
        Imgproc.threshold(ys, ys, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        Core.multiply(ys, Scalar(gain), ys)
        val num = Mat(); Core.multiply(ys, Scalar(1.0 / (white * white)), num)
        Core.add(num, Scalar(1.0), num)
        Core.multiply(num, ys, num)
        val den = Mat(); Core.add(ys, Scalar(1.0), den)
        val yt = Mat(); Core.divide(num, den, yt)

        // Oran = Yt / Y ; renk = Yt + sat·(RGB·oran − Yt)
        val yEps = Mat(); Core.max(y, Scalar(1e-5), yEps)
        val ratio = Mat(); Core.divide(yt, yEps, ratio)
        val ratio3 = Mat(); Core.merge(listOf(ratio, ratio, ratio), ratio3)
        val yt3 = Mat(); Core.merge(listOf(yt, yt, yt), yt3)
        Core.multiply(f, ratio3, f)
        Core.subtract(f, yt3, f)
        Core.multiply(f, Scalar(saturation, saturation, saturation), f)
        Core.add(f, yt3, f)
        Core.min(f, Scalar(1.0, 1.0, 1.0), f)
        Core.max(f, Scalar(0.0, 0.0, 0.0), f)

        // doğrusal → sRGB
        Core.pow(f, 1.0 / 2.2, f)

        // Üçgen dağılımlı titreşim (±1 LSB) ile 8 bite indir
        Core.multiply(f, Scalar(255.0, 255.0, 255.0), f)
        val n1 = Mat(f.size(), f.type()); Core.randu(n1, -0.5, 0.5)
        val n2 = Mat(f.size(), f.type()); Core.randu(n2, -0.5, 0.5)
        Core.add(f, n1, f); Core.add(f, n2, f)
        val out = Mat()
        f.convertTo(out, CvType.CV_8UC3)

        listOf(f, y, ys, num, den, yt, yEps, ratio, ratio3, yt3, n1, n2).forEach { it.release() }
        return out
    }

    /** Doğrusal BGR'den parlaklık (Rec.709). */
    private fun luminance(f: Mat): Mat {
        val y = Mat()
        Core.transform(f, y, Mat(1, 3, CvType.CV_32F).apply { put(0, 0, 0.0722, 0.7152, 0.2126) })
        return y
    }

    data class Stats(val geoMean: Double, val p005: Double, val p995: Double)

    /** Küçültülmüş görüntüden geometrik ortalama ve yüzdelikler. */
    fun sceneStats(y: Mat): Stats {
        val small = Mat()
        val k = (512.0 / maxOf(y.cols(), y.rows())).coerceAtMost(1.0)
        Imgproc.resize(y, small, Size(), k, k, Imgproc.INTER_AREA)
        val n = small.total().toInt()
        val arr = FloatArray(n); small.get(0, 0, arr); small.release()
        arr.sort()
        val p005 = arr[(n * 0.005).toInt().coerceIn(0, n - 1)].toDouble()
        val p995 = arr[(n * 0.995).toInt().coerceIn(0, n - 1)].toDouble()
        // En parlak %1 (lambalar) ortalamayı şişirmesin
        val hi = (n * 0.99).toInt().coerceAtLeast(1)
        var s = 0.0
        for (i in 0 until hi) s += ln(arr[i].toDouble() + 1e-4)
        val geo = exp(s / hi)
        return Stats(geo.coerceAtLeast(1e-4), p005, p995.coerceAtLeast(1e-3))
    }
}

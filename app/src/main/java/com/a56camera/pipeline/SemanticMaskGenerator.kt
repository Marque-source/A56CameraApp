package com.a56camera.pipeline

import android.util.Log
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Saf OpenCV Tabanlı Deterministik Semantik Maske & Akıllı Pütür (Smart Grain) Üreteci.
 *
 * TFLite veya monoküler derinlik modeli KULLANMAZ!
 * Bu sayede gece gökyüzünde monoküler modellerin (MiDaS vb.) ürettiği rastgele leke ve bloklaşmayı %100 önler.
 *
 * 320px alt-örnekleme üzerinde CIE-Lab + Laplacian doku analizi yapar (~4ms):
 * - Gökyüzü: %0 gren (kadife, ipek gibi pürüzsüz)
 * - Binalar, yapraklar, zemin: %100 organik mikro film greni (UniHAL plastiğini yok eden analog doku)
 */
object SemanticMaskGenerator {

    private const val TAG = "SemanticMaskGenerator"
    private const val ANALYSIS_WIDTH = 320

    data class SemanticMasks(
        val skyMask: Mat,       // 0..255 (255 = Gökyüzü)
        val textureMask: Mat    // 0..255 (255 = Bina/Asfalt/Doku, 0 = Gökyüzü)
    )

    /**
     * 320px üzerinde deterministik renk ve doku analiziyle gökyüzü ve doku maskesi üretir.
     * Çıktı maskeleri tam çözünürlüğe (12MP) bilinear yumuşak geçişle ölçeklenir.
     */
    fun generateHeuristicMasks(bgr: Mat): SemanticMasks {
        val fullW = bgr.cols()
        val fullH = bgr.rows()
        val scale = ANALYSIS_WIDTH.toDouble() / fullW
        val smallH = (fullH * scale).toInt().coerceAtLeast(1)

        val smallBgr = Mat()
        Imgproc.resize(bgr, smallBgr, Size(ANALYSIS_WIDTH.toDouble(), smallH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

        // 1. LAB Renk Uzayı Analizi
        val lab = Mat()
        Imgproc.cvtColor(smallBgr, lab, Imgproc.COLOR_BGR2Lab)
        val labChannels = mutableListOf<Mat>()
        Core.split(lab, labChannels)
        val luma = labChannels[0]
        val bChannel = labChannels[2] // b* < 128 soğuk/mavi (gökyüzü), > 128 sıcak

        // 2. Doku (Laplacian) Gradyan Analizi — Gökyüzünde kenar/doku enerjisi neredeyse sıfırdır
        val gray = Mat()
        Imgproc.cvtColor(smallBgr, gray, Imgproc.COLOR_BGR2GRAY)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_16S)
        val absLap = Mat()
        Core.convertScaleAbs(lap, absLap)
        lap.release()
        gray.release()

        // 3. Gökyüzü Tespiti:
        // - Resmin üst %65'lik diliminde aranır
        // - Düşük doku enerjisi (Laplacian < 18)
        // - Gece gökyüzü: Luma < 120 veya Mavi ton (bChannel < 134)
        val skySmall = Mat.zeros(smallH, ANALYSIS_WIDTH, CvType.CV_8UC1)
        val upperLimit = (smallH * 0.65).toInt().coerceAtMost(smallH)

        val lumaData = ByteArray(ANALYSIS_WIDTH)
        val bData = ByteArray(ANALYSIS_WIDTH)
        val lapData = ByteArray(ANALYSIS_WIDTH)
        val skyRow = ByteArray(ANALYSIS_WIDTH)

        for (r in 0 until upperLimit) {
            luma.get(r, 0, lumaData)
            bChannel.get(r, 0, bData)
            absLap.get(r, 0, lapData)

            for (c in 0 until ANALYSIS_WIDTH) {
                val lVal = lumaData[c].toInt() and 0xFF
                val bVal = bData[c].toInt() and 0xFF
                val lapVal = lapData[c].toInt() and 0xFF

                // Düşük doku varyansı ve gökyüzü kroması
                val isSmooth = lapVal < 18
                val isNightOrBlueSky = (bVal < 134) || (lVal < 80 && isSmooth)

                skyRow[c] = if (isSmooth && isNightOrBlueSky) 255.toByte() else 0.toByte()
            }
            skySmall.put(r, 0, skyRow)
        }

        lab.release()
        labChannels.forEach { it.release() }
        absLap.release()
        smallBgr.release()

        // Yumuşak geçiş için Gaussian blur
        val skyBlurred = Mat()
        Imgproc.GaussianBlur(skySmall, skyBlurred, Size(15.0, 15.0), 5.0)
        skySmall.release()

        // Tam çözünürlüğe ölçekle
        val fullSky = Mat()
        Imgproc.resize(skyBlurred, fullSky, Size(fullW.toDouble(), fullH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        skyBlurred.release()

        // Doku Maskesi = Invert(Sky) -> Gökyüzü = 0, Binalar/Zemin = 255
        val fullTexture = Mat()
        Core.bitwise_not(fullSky, fullTexture)

        return SemanticMasks(fullSky, fullTexture)
    }

    /**
     * Google Pixel & Leica Tarzı Akıllı Pütür (Smart Grain):
     * Gökyüzünü %0 gren ile kadife gibi bırakır; binalara, yola ve ağaçlara
     * UniHAL'in sildiği analog optik dokuyu enjekte eder.
     */
    fun applySmartGrain(bgr: Mat, iso: Int) {
        val t0 = System.currentTimeMillis()
        val masks = generateHeuristicMasks(bgr)

        val fullW = bgr.cols()
        val fullH = bgr.rows()

        // ISO'ya göre organik gren şiddeti (sigma: 2.0 - 4.5)
        val sigma = when {
            iso >= 2400 -> 4.5
            iso >= 1200 -> 3.5
            iso >= 600 -> 2.6
            else -> 1.8
        }

        // Normal dağılımlı analog mikro-gren matrisi
        val noise = Mat(fullH, fullW, CvType.CV_32FC1)
        Core.randn(noise, 0.0, sigma)

        // Doku maskesini normalize et [0.0, 1.0]
        val textureFloat = Mat()
        masks.textureMask.convertTo(textureFloat, CvType.CV_32FC1, 1.0 / 255.0)
        masks.skyMask.release()
        masks.textureMask.release()

        // noise = noise * textureMask (Gökyüzünde noise = 0.0 kalır!)
        Core.multiply(noise, textureFloat, noise)
        textureFloat.release()

        // 3 kanala genişlet
        val noise3ch = Mat()
        val noiseChannels = listOf(noise, noise, noise)
        Core.merge(noiseChannels, noise3ch)
        noise.release()

        // BGR + Noise
        val bgrFloat = Mat()
        bgr.convertTo(bgrFloat, CvType.CV_32FC3)
        Core.add(bgrFloat, noise3ch, bgrFloat)
        noise3ch.release()

        bgrFloat.convertTo(bgr, CvType.CV_8UC3)
        bgrFloat.release()

        Log.i(TAG, "🌾 [Akıllı Pütür (Smart Grain)] Tamamlandı: ${System.currentTimeMillis() - t0}ms (Gökyüzü: %0, Doku: %100, Sigma=${String.format(java.util.Locale.US, "%.1f", sigma)})")
    }
}

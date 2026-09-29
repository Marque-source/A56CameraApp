package com.a56camera.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * TFLite Çıktı (Output Tensor) Son-İşleme ve Bitmap Dönüştürme Motoru (Post-Processor).
 *
 * Exynos 1580 NPU ve AMD Xclipse 540 GPU'dan çıkan ham Float32 dizilerini
 * OpenCV C++ hızlandırmasıyla renkli, kayıpsız tam çözünürlüklü Bitmap'lere dönüştürür:
 *
 * 1. Zero-DCE: Guided Illumination Gain Map ile tam 12MP/50MP çözünürlüğü korur,
 *    sokak lambalarını patlatmadan sadece derin gölgeleri aydınlatır.
 * 2. MiDaS v2.1: 3D derinlik matrisini gökyüzü (Siyah) ve doku/yaprak/bina (Beyaz)
 *    semantik maskesine çevirir.
 * 3. Real-ESRGAN: 512x512 4x AI süper çözünürlük görselini kristal netlikte oluşturur.
 */
object TFLitePostProcessor {

    private const val TAG = "TFLitePostProcessor"

    /**
     * 1. Zero-DCE Gece Çıktısını Tam Çözünürlüklü Fotoğrafa Uygular (Guided Gain Map).
     *
     * @param original Tam çözünürlüklü ham fotoğraf (örn: 12MP / 50MP)
     * @param outputBuffer TFLite modelinden çıkan [1, size, size, 3] Float32 buffer
     * @param size Model boyutu (örn: 512 veya 256)
     * @param intensity Aydınlatma gücü (0.0f - 1.0f)
     */
    fun postProcessZeroDce(
        original: Bitmap,
        outputBuffer: ByteBuffer,
        size: Int = 512,
        intensity: Float = 0.65f
    ): Bitmap {
        val t0 = System.currentTimeMillis()
        val width = original.width
        val height = original.height

        outputBuffer.rewind()

        // 1. Model çıktısını küçük boyutlu Bitmap'e dönüştür
        val enhancedSmall = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (i in 0 until (size * size)) {
            val r = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            val g = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            val b = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        enhancedSmall.setPixels(pixels, 0, size, 0, 0, size, size)

        // 2. Giriş görselinin küçük kopyasını al
        val inputSmall = Bitmap.createScaledBitmap(original, size, size, true)

        val inMat = YuvConverter.bitmapToMat(inputSmall)
        val outMat = YuvConverter.bitmapToMat(enhancedSmall)
        inputSmall.recycle()
        enhancedSmall.recycle()

        val inFloat = Mat()
        val outFloat = Mat()
        inMat.convertTo(inFloat, CvType.CV_32FC3, 1.0 / 255.0)
        outMat.convertTo(outFloat, CvType.CV_32FC3, 1.0 / 255.0)
        inMat.release()
        outMat.release()

        // 3. Kazanç Haritası: Gain = (Enhanced + eps) / (Input + eps)
        val eps = Mat(size, size, CvType.CV_32FC3, Scalar(0.01, 0.01, 0.01))
        val inSafe = Mat()
        val outSafe = Mat()
        Core.add(inFloat, eps, inSafe)
        Core.add(outFloat, eps, outSafe)
        val gainMap = Mat()
        Core.divide(outSafe, inSafe, gainMap)
        inSafe.release()
        outSafe.release()
        eps.release()

        // 4. Sokak Lambası / Speküler Işık Patlaması Koruması:
        // Parlaklığı > 0.75 olan piksellerde kazancı 1.0x (artışsız) kilitle
        val gray = Mat()
        Imgproc.cvtColor(inFloat, gray, Imgproc.COLOR_BGR2GRAY)
        inFloat.release()
        outFloat.release()

        val shadowWeight = Mat()
        val targetScalar = Mat(gray.size(), gray.type(), Scalar(0.75))
        Core.subtract(targetScalar, gray, shadowWeight)
        targetScalar.release()
        gray.release()

        Core.divide(shadowWeight, Scalar(0.55), shadowWeight)
        Core.min(shadowWeight, Scalar(1.0), shadowWeight)
        Core.max(shadowWeight, Scalar(0.0), shadowWeight)

        val weight3 = Mat()
        Core.merge(listOf(shadowWeight, shadowWeight, shadowWeight), weight3)
        shadowWeight.release()

        // protectedGain = 1.0 + (gainMap - 1.0) * shadowWeight
        val gainMinusOne = Mat()
        Core.subtract(gainMap, Scalar(1.0, 1.0, 1.0), gainMinusOne)
        gainMap.release()

        val weightedDelta = Mat()
        Core.multiply(gainMinusOne, weight3, weightedDelta)
        gainMinusOne.release()
        weight3.release()

        val protectedGain = Mat()
        Core.add(weightedDelta, Scalar(1.0, 1.0, 1.0), protectedGain)
        weightedDelta.release()

        // 5. Kazanç Haritasını Tam Çözünürlüğe Ölçekle (Bilinear interpolation: sıfır halo, sıfır pikselleşme)
        val fullGain = Mat()
        Imgproc.resize(protectedGain, fullGain, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        protectedGain.release()

        // 6. Tam Çözünürlüklü Orijinal Fotoğraf ile Çarp (Zero-Noise Illumination)
        val origMat = YuvConverter.bitmapToMat(original)
        val origFloat = Mat()
        origMat.convertTo(origFloat, CvType.CV_32FC3, 1.0 / 255.0)
        origMat.release()

        val enhancedFullFloat = Mat()
        Core.multiply(origFloat, fullGain, enhancedFullFloat)
        fullGain.release()

        // 7. Şiddet (Intensity) Harmanlama
        val finalFloat = Mat()
        if (intensity < 1.0f) {
            Core.addWeighted(
                enhancedFullFloat, intensity.toDouble(),
                origFloat, (1.0f - intensity).toDouble(),
                0.0, finalFloat
            )
            enhancedFullFloat.release()
            origFloat.release()
        } else {
            enhancedFullFloat.copyTo(finalFloat)
            enhancedFullFloat.release()
            origFloat.release()
        }

        // Float32 -> 8-bit BGR Mat -> Bitmap
        val final8u = Mat()
        finalFloat.convertTo(final8u, CvType.CV_8UC3, 255.0)
        finalFloat.release()

        val resultBitmap = YuvConverter.matToBitmap(final8u)
        final8u.release()

        Log.d(TAG, "⚡ Zero-DCE Post-Processing tamamlandı: ${System.currentTimeMillis() - t0}ms (${width}x${height})")
        return resultBitmap
    }

    /**
     * 2. MiDaS 3D Derinlik Matrisinden Semantik Doku Maskesi Üretir.
     *
     * Gökyüzü ve uzaktaki arka plan ──► SİYAH (0)
     * Yakındaki balkon demirleri, yapraklar, binalar ──► BEYAZ (255)
     */
    fun postProcessMidasDepth(
        depthMap: Array<FloatArray>,
        size: Int = 256
    ): Bitmap {
        val maskBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        for (r in 0 until size) {
            for (c in 0 until size) {
                val v = depthMap[r][c]
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
        }

        val range = (maxVal - minVal).coerceAtLeast(0.0001f)
        val pixels = IntArray(size * size)
        var idx = 0

        for (r in 0 until size) {
            for (c in 0 until size) {
                val norm = ((depthMap[r][c] - minVal) / range).coerceIn(0f, 1f)
                val gray = (norm * 255f).toInt()
                pixels[idx++] = Color.rgb(gray, gray, gray)
            }
        }

        maskBmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return maskBmp
    }

    /**
     * 3. Real-ESRGAN x4 Süper Çözünürlük Çıktısını Bitmap'e Dönüştürür (512x512).
     */
    fun postProcessRealEsrgan(
        outputBuffer: ByteBuffer,
        outputSize: Int = 512
    ): Bitmap {
        outputBuffer.rewind()
        val srBmp = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(outputSize * outputSize)

        for (i in 0 until (outputSize * outputSize)) {
            val r = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            val g = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            val b = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        srBmp.setPixels(pixels, 0, outputSize, 0, 0, outputSize, outputSize)
        return srBmp
    }

    /**
     * 4. BiSeNetv2 Çıktısından Semantik Maskeleri Üretir.
     *
     * Gökyüzü (Sky), İnsan Teni/Vücut (Person/Skin), Yeşillik/Ağaç (Foliage) ve
     * Akıllı Gren Maskesi (TextureGrainMask) ayrıştırılır:
     * - Gökyüzü: 0 gren (%0 pütür, ipek gibi pürüzsüz)
     * - İnsan Teni/Kıyafet/Bina/Yaprak: 255 gren (%100 organik pütür & akütans)
     */
    data class SemanticMaskCollection(
        val skyMask: Bitmap,
        val skinPersonMask: Bitmap,
        val foliageMask: Bitmap,
        val textureGrainMask: Bitmap
    )

    fun postProcessBiSeNet(
        classIndices: IntArray,
        width: Int,
        height: Int,
        skyClassId: Int = 10,
        personClassId: Int = 11,
        foliageClassId: Int = 8
    ): SemanticMaskCollection {
        val skyBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val skinBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val foliageBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val grainBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val totalPixels = width * height
        val skyPixels = IntArray(totalPixels)
        val skinPixels = IntArray(totalPixels)
        val foliagePixels = IntArray(totalPixels)
        val grainPixels = IntArray(totalPixels)

        for (i in 0 until totalPixels) {
            val cls = classIndices.getOrElse(i) { -1 }

            val isSky = (cls == skyClassId)
            val isPerson = (cls == personClassId || cls == 12) // Person / Rider
            val isFoliage = (cls == foliageClassId || cls == 9) // Vegetation / Terrain

            skyPixels[i] = if (isSky) Color.WHITE else Color.BLACK
            skinPixels[i] = if (isPerson) Color.WHITE else Color.BLACK
            foliagePixels[i] = if (isFoliage) Color.WHITE else Color.BLACK

            // Gökyüzü = 0 gren, İnsan/Kıyafet/Ağaç/Bina = 255 gren
            grainPixels[i] = if (isSky) Color.BLACK else Color.WHITE
        }

        skyBmp.setPixels(skyPixels, 0, width, 0, 0, width, height)
        skinBmp.setPixels(skinPixels, 0, width, 0, 0, width, height)
        foliageBmp.setPixels(foliagePixels, 0, width, 0, 0, width, height)
        grainBmp.setPixels(grainPixels, 0, width, 0, 0, width, height)

        return SemanticMaskCollection(skyBmp, skinBmp, foliageBmp, grainBmp)
    }

    /**
     * 5. NAFNet AI Deblur Çıktısını Tam Çözünürlüklü Orijinale Uygular.
     * Küçük boyuttaki keskinleştirilmiş/hareket bulanıklığı giderilmiş AI çıktısını,
     * orijinal 12MP/50MP çözünürlüğe kenar korumalı (halo-free) yüksek frekans farkıyla enjekte eder.
     */
    fun postProcessNafNet(
        original: Bitmap,
        outputBuffer: ByteBuffer,
        modelSize: Int = 256,
        blendStrength: Float = 0.18f
    ): Bitmap {
        val t0 = System.currentTimeMillis()
        outputBuffer.rewind()

        val deblurredSmall = Bitmap.createBitmap(modelSize, modelSize, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(modelSize * modelSize)
        for (i in 0 until (modelSize * modelSize)) {
            val r = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            val g = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            val b = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        deblurredSmall.setPixels(pixels, 0, modelSize, 0, 0, modelSize, modelSize)

        // 1. Giriş görselinin aynı boyuttaki kopyası
        val originalSmall = Bitmap.createScaledBitmap(original, modelSize, modelSize, true)

        val inMat = YuvConverter.bitmapToMat(originalSmall)
        val debMat = YuvConverter.bitmapToMat(deblurredSmall)
        originalSmall.recycle()
        deblurredSmall.recycle()

        // 2. Yüksek Frekans Detay Artığı: Residual = Deblurred - Input
        val residual = Mat()
        Core.subtract(debMat, inMat, residual)
        inMat.release()
        debMat.release()

        // 3. Detay artığını tam çözünürlüğe iki-kübik (Bicubic) enterpolasyon ile ölçekle
        val fullResidual = Mat()
        Imgproc.resize(residual, fullResidual, Size(original.width.toDouble(), original.height.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        residual.release()

        // 4. Orijinal tam çözünürlüklü fotoğrafa residual ekle
        val origMat = YuvConverter.bitmapToMat(original)
        val origFloat = Mat()
        origMat.convertTo(origFloat, CvType.CV_32FC3)
        origMat.release()

        val resFloat = Mat()
        fullResidual.convertTo(resFloat, CvType.CV_32FC3)
        fullResidual.release()

        // result = orig + residual * blendStrength
        val scaledResidual = Mat()
        Core.multiply(resFloat, Scalar(blendStrength.toDouble(), blendStrength.toDouble(), blendStrength.toDouble()), scaledResidual)
        resFloat.release()

        val finalFloat = Mat()
        Core.add(origFloat, scaledResidual, finalFloat)
        origFloat.release()
        scaledResidual.release()

        val final8u = Mat()
        finalFloat.convertTo(final8u, CvType.CV_8UC3)
        finalFloat.release()

        val resultBitmap = YuvConverter.matToBitmap(final8u)
        final8u.release()

        Log.d(TAG, "⚡ NAFNet Deblur Post-Processing tamamlandı: ${System.currentTimeMillis() - t0}ms (${original.width}x${original.height})")
        return resultBitmap
    }
}

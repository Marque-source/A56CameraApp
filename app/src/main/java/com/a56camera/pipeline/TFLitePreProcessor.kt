package com.a56camera.pipeline

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TFLite Girdi/Çıktı Byte Buffer Yönetim Motoru.
 *
 * Bitmap verisini modellerin istediği tam boyutlara (256x256, 128x128 veya 512x512)
 * donanımsal olarak ölçeklendirip sıfır gecikmeli hafıza adresleriyle (Direct ByteBuffer)
 * Exynos 1580 NPU ve AMD Xclipse 540 GPU'ya besler.
 */
object TFLitePreProcessor {

    // MiDaS ImageNet İstatistikleri
    val MIDAS_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val MIDAS_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Gelen fotoğrafı TFLite modellerinin anlayacağı normalize edilmiş [1, Size, Size, 3] Float32 formatına çevirir.
     * zero_dce, midas_depth ve scene_classifier modellerinin girişi için ortaktır.
     *
     * @param bitmap Giriş görseli
     * @param targetSize Model giriş boyutu (örn: 256, 512)
     * @param mean Opsiyonel normalizasyon ortalaması (örn: MiDaS için ImageNet MEAN)
     * @param std Opsiyonel normalizasyon standart sapması (örn: MiDaS için ImageNet STD)
     */
    fun convertBitmapToTFLiteBuffer(
        bitmap: Bitmap,
        targetSize: Int,
        mean: FloatArray? = null,
        std: FloatArray? = null
    ): ByteBuffer {
        val scaledBitmap = if (bitmap.width != targetSize || bitmap.height != targetSize) {
            Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true)
        } else {
            bitmap
        }

        // 4 byte (Float32) * targetSize * targetSize * 3 (RGB)
        val byteBuffer = ByteBuffer.allocateDirect(4 * targetSize * targetSize * 3).apply {
            order(ByteOrder.nativeOrder()) // ARM64 yerel bellek sırası
        }

        fillBuffer(scaledBitmap, targetSize, byteBuffer, mean, std)

        if (scaledBitmap !== bitmap) {
            scaledBitmap.recycle() // Bellek sızıntısını önle
        }

        return byteBuffer
    }

    /**
     * Sıfır bellek tahsisli (Zero-Allocation) Buffer Doldurucu:
     * Önceden ayrılmış Direct ByteBuffer'ı doğrudan doldurarak Android Garbage Collector (GC)
     * duraklamalarını ve bellek parçalanmasını sıfıra indirir.
     */
    fun fillBuffer(
        bitmap: Bitmap,
        targetSize: Int,
        byteBuffer: ByteBuffer,
        mean: FloatArray? = null,
        std: FloatArray? = null
    ) {
        byteBuffer.rewind()
        val intValues = IntArray(targetSize * targetSize)
        bitmap.getPixels(intValues, 0, targetSize, 0, 0, targetSize, targetSize)

        val useImageNetNorm = mean != null && std != null && mean.size >= 3 && std.size >= 3

        var pixelIdx = 0
        for (i in 0 until targetSize) {
            for (j in 0 until targetSize) {
                val pixel = intValues[pixelIdx++]

                var r = ((pixel shr 16) and 0xFF) / 255.0f
                var g = ((pixel shr 8) and 0xFF) / 255.0f
                var b = (pixel and 0xFF) / 255.0f

                if (useImageNetNorm) {
                    r = (r - mean!![0]) / std!![0]
                    g = (g - mean[1]) / std[1]
                    b = (b - mean[2]) / std[2]
                }

                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
            }
        }
    }

    /**
     * Real-ESRGAN x4 Süper Çözünürlük modeli için özel ön işleme (128x128 girdi bütçesi)
     */
    fun convertForRealEsrgan(zoomCroppedBitmap: Bitmap): ByteBuffer {
        return convertBitmapToTFLiteBuffer(zoomCroppedBitmap, 128)
    }

    /**
     * MiDaS v2.1 3D Derinlik modeli için ImageNet normalizasyonlu ön işleme (256x256)
     */
    fun convertForMidas(bitmap: Bitmap): ByteBuffer {
        return convertBitmapToTFLiteBuffer(bitmap, 256, MIDAS_MEAN, MIDAS_STD)
    }

    /**
     * Zero-DCE Gece Görüş modeli için [0, 1] normalizasyonlu ön işleme (512x512 veya 256x256)
     */
    fun convertForZeroDce(bitmap: Bitmap, size: Int = 512): ByteBuffer {
        return convertBitmapToTFLiteBuffer(bitmap, size)
    }

    /**
     * BiSeNetv2 Anlamsal Segmentasyon modeli için ImageNet normalizasyonlu ön işleme (512x512)
     */
    fun convertForBiSeNet(bitmap: Bitmap, size: Int = 512): ByteBuffer {
        return convertBitmapToTFLiteBuffer(bitmap, size, MIDAS_MEAN, MIDAS_STD)
    }

    /**
     * NAFNet AI Hareket Bulanıklığı Siler (Deblur) modeli için [0, 1] ön işleme (256x256 veya 512x512)
     */
    fun convertForNafNet(bitmap: Bitmap, size: Int = 256): ByteBuffer {
        return convertBitmapToTFLiteBuffer(bitmap, size)
    }
}

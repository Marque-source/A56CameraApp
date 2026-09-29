package com.a56camera.pipeline

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer

/**
 * ARM64 NEON SIMD Donanım Tabanlı Görüntü Birleştirme ve Optimizasyon Motoru.
 * Samsung Galaxy A56'nın ARM Cortex-A720 NEON vektör birimlerinde tek saat çevriminde
 * hayalet engelleme (MAD) ve zamansal birleştirme çalıştırır.
 */
object NeonPipelineEngine {

    private const val TAG = "NeonPipelineEngine"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("pixel_camera_engine")
            isNativeLoaded = true
            Log.i(TAG, "⚡ [ARM64 NEON] Donanım hızlandırmalı SIMD motoru başarıyla yüklendi!")
        } catch (e: Throwable) {
            Log.w(TAG, "NEON C++ kütüphanesi hazır değil, OpenCV donanım hattı devrede: ${e.message}")
            isNativeLoaded = false
        }
    }

    fun isHardwareAccelerated(): Boolean = isNativeLoaded

    /**
     * İki kareyi ARM64 NEON SIMD ile doğrudan Bitmap bellek adreslerinden donanımsal olarak birleştirir.
     * @param refBitmap Referans (en keskin) kare
     * @param currBitmap Birleştirilecek mevcut kare
     * @param outBitmap Çıktı birleştirilmiş hedef Bitmap (ARGB_8888)
     * @param madThreshold Hareket eşiği (0-255, varsayılan 35)
     */
    fun robustMerge(
        refBitmap: Bitmap,
        currBitmap: Bitmap,
        outBitmap: Bitmap,
        madThreshold: Int = 35
    ): Boolean {
        if (!isNativeLoaded) return false
        val t0 = System.currentTimeMillis()
        val res = try {
            nativeRobustMerge(
                refBitmap,
                currBitmap,
                outBitmap,
                refBitmap.width,
                refBitmap.height,
                madThreshold
            )
        } catch (e: Throwable) {
            Log.e(TAG, "nativeRobustMerge hatası", e)
            false
        }
        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "⚡ [NEON JNI Kernel] nativeRobustMerge (${refBitmap.width}x${refBitmap.height}) bitti: ${elapsed}ms")
        return res
    }

    /**
     * DirectByteBuffer adresleri üzerinden sıfır gecikmeli (zero-copy) NEON birleştirme.
     */
    fun mergeBuffers(
        refBuf: ByteBuffer,
        currBuf: ByteBuffer,
        outBuf: ByteBuffer,
        pixelCount: Int,
        madThreshold: Int = 35
    ): Boolean {
        if (!isNativeLoaded) return false
        return try {
            nativeMergeBuffers(refBuf, currBuf, outBuf, pixelCount, madThreshold)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeMergeBuffers hatası", e)
            false
        }
    }

    /**
     * ARM64 NEON SIMD ile iki görseli semantik maske üzerinden sıfır kopyayla donanımsal harmanlar.
     */
    fun semanticBlend(
        bgBitmap: Bitmap,
        fgBitmap: Bitmap,
        maskBitmap: Bitmap,
        outBitmap: Bitmap
    ): Boolean {
        if (!isNativeLoaded) return false
        return try {
            nativeSemanticBlend(bgBitmap, fgBitmap, maskBitmap, outBitmap, bgBitmap.width, bgBitmap.height)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeSemanticBlend hatası", e)
            false
        }
    }

    // --- Native JNI Prototypleri ---

    private external fun nativeRobustMerge(
        refBitmap: Bitmap,
        currBitmap: Bitmap,
        outBitmap: Bitmap,
        width: Int,
        height: Int,
        madThreshold: Int
    ): Boolean

    private external fun nativeMergeBuffers(
        refBuf: ByteBuffer,
        currBuf: ByteBuffer,
        outBuf: ByteBuffer,
        pixelCount: Int,
        madThreshold: Int
    ): Boolean

    private external fun nativeSemanticBlend(
        bgBitmap: Bitmap,
        fgBitmap: Bitmap,
        maskBitmap: Bitmap,
        outBitmap: Bitmap,
        width: Int,
        height: Int
    ): Boolean
}

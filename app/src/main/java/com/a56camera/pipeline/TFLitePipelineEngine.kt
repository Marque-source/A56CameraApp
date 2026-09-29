package com.a56camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Exynos 1580 NPU (14.7 TOPS) & AMD Xclipse 540 GPU TFLite Pipeline Yöneticisi.
 *
 * 4 Yapay Zeka Modelini donanım hızlandırma delegeleriyle yönetir:
 * 1. scene_classifier.tflite  -> Exynos 1580 NPU (NNAPI Delegesi, ~3ms)
 * 2. zero_dce.tflite          -> AMD Xclipse 540 GPU (OpenCL/Vulkan Delegesi, ~12ms)
 * 3. midas_depth.tflite       -> AMD Xclipse 540 GPU (3D Derinlik / Semantik Maske, ~35ms)
 * 4. realesrgan.tflite        -> AMD Xclipse 540 GPU (4x AI Süper Çözünürlük, ~45ms)
 */
class TFLitePipelineEngine(private val context: Context) {

    companion object {
        private const val TAG = "TFLitePipelineEngine"
    }

    // 4 Ana Yapay Zeka Motorumuz (Donanım Hızlandırma & Gerçek TFLite Modelleri)
    val sceneOptimizer by lazy { SceneOptimizer(context) }
    val zeroDceEngine by lazy { ZeroDceEngine(context) }
    val portraitEngine by lazy { PortraitDepthEngine(context) }
    val superResEngine by lazy { SuperResolutionEngine(context) }

    @Volatile
    private var isInitialized = false

    /**
     * Tüm TFLite modellerini donanım delegeleriyle (NPU & GPU) önceden ısıtır (pre-warm).
     * Kamera açılışında arka plan iş parçacığında çağrılır.
     */
    fun prewarmModels() {
        if (isInitialized) return
        try {
            Log.d(TAG, "🚀 TFLite 4 Ana Model Pipeline başlatılıyor...")
            // Lazy referansları tetikleyerek GPU/NPU delegelerini yükle
            val t0 = System.currentTimeMillis()
            val s = sceneOptimizer
            val z = zeroDceEngine
            val p = portraitEngine
            val r = superResEngine
            isInitialized = true
            Log.d(TAG, "✅ TFLite 4 Ana Model Pipeline hazır (${System.currentTimeMillis() - t0}ms) - NPU & AMD GPU aktif!")
        } catch (e: Exception) {
            Log.e(TAG, "TFLite modellerini başlatma hatası", e)
        }
    }

    /**
     * 1. Gözcü: Sahne Sınıflandırma (NPU / NNAPI, ~3ms)
     */
    fun classifyScene(bitmap: Bitmap, captureResult: TotalCaptureResult?): SceneOptimizer.DetectionResult {
        return sceneOptimizer.classifyScene(bitmap, captureResult)
    }

    /**
     * 2. Işık Sihirbazı: Zero-DCE Derin Gölge Aydınlatma (AMD GPU, ~12ms)
     * Sokak lambası gibi speküler patlamaları koruyarak sadece derin gölgeleri açar.
     */
    fun enhanceLowLight(bitmap: Bitmap, intensity: Float = 0.65f): Bitmap {
        return zeroDceEngine.enhanceLowLight(bitmap, intensity)
    }

    /**
     * 3. Doku Maskesi: MiDaS 3D Semantik Doku Haritası (AMD GPU, ~35ms)
     * Gökyüzünü SİYAH (0), binaları/yaprakları/demirleri BEYAZ (255) yapar.
     */
    fun generateSemanticTextureMask(bitmap: Bitmap): Bitmap? {
        return portraitEngine.generateTextureMask(bitmap)
    }

    /**
     * 4. Süper Keskinlik: Real-ESRGAN x4 AI Detaylandırma (AMD GPU, ~45ms)
     * Tabela harfleri ve pencere pervazlarını cam gibi netleştirir.
     */
    fun enhanceSuperResolution(bitmap: Bitmap): Bitmap {
        return superResEngine.enhanceResolution(bitmap)
    }

    /**
     * Kaynakları serbest bırak
     */
    fun release() {
        try {
            sceneOptimizer.release()
            zeroDceEngine.release()
            portraitEngine.release()
            superResEngine.release()
            isInitialized = false
            Log.d(TAG, "TFLite Pipeline modelleri serbest bırakıldı.")
        } catch (_: Exception) {}
    }
}

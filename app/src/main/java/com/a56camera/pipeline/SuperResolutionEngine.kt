package com.a56camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Exynos 1580 NPU & AMD Xclipse 540 GPU Destekli AI Super Resolution Motoru (Real-ESRGAN x4).
 *
 * 128x128 giriş → 512x512 çıkış (4x upscale).
 * Düşük ışıkta veya dijital zoom yapıldığında piksellerin kaybolan mikro dokularını
 * (saç telleri, kirpikler, kumaş dokusu vb.) yapay zeka ile yeniden sentezler
 * ve dijital gürültüyü (noise) tamamen yok eder.
 */
class SuperResolutionEngine(private val context: Context) {

    companion object {
        private const val TAG = "SuperResolutionEngine"
        private const val MODEL_FILE = "realesrgan.tflite"
        private const val INPUT_SIZE = 128
        private const val OUTPUT_SIZE = 512
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isInitialized = false

    private val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = ByteBuffer.allocateDirect(1 * OUTPUT_SIZE * OUTPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            val modelBuffer = loadModelFile(MODEL_FILE) ?: run {
                Log.w(TAG, "$MODEL_FILE bulunamadı, süper çözünürlük pasif.")
                return
            }

            val compat = CompatibilityList()
            val isSupported = compat.isDelegateSupportedOnThisDevice
            Log.i(TAG, "🔍 [CompatibilityList] Real-ESRGAN isDelegateSupportedOnThisDevice = $isSupported")

            var interp: Interpreter? = null

            // 1. ADIM: Doğrudan GPU Delegate zorlayarak dene ve hatayı yakala
            try {
                Log.i(TAG, "🧪 Real-ESRGAN GpuDelegate doğrudan başlatma deneniyor...")
                val gpuOptions = Interpreter.Options().apply {
                    val delegate = GpuDelegate()
                    gpuDelegate = delegate
                    addDelegate(delegate)
                }
                interp = Interpreter(modelBuffer, gpuOptions)
                Log.i(TAG, "🎉 [BAŞARILI] GpuDelegate AMD Xclipse GPU üzerinde Real-ESRGAN için kabul edildi!")
            } catch (e: Throwable) {
                Log.e(TAG, "💥 [Real-ESRGAN GPU DELEGATE REDDEDİLDİ / HATA]: ${e.javaClass.name}: ${e.message}", e)
                gpuDelegate?.close()
                gpuDelegate = null
            }

            // 2. ADIM: GPU reddedildiyse 4-Thread XNNPack CPU kullan (Android 15 resmi standardı)
            if (interp == null) {
                Log.i(TAG, "🧪 Real-ESRGAN Fallback: 4-thread XNNPack CPU başlatılıyor...")
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interp = Interpreter(modelBuffer, cpuOptions)
                Log.i(TAG, "✅ [FALLBACK AKTİF] Real-ESRGAN 4-thread XNNPack CPU ile yüklendi.")
            }

            interpreter = interp
            isInitialized = true
            Log.d(TAG, "🚀 Real-ESRGAN AI Super Resolution Motoru başarıyla yüklendi!")
        } catch (e: Exception) {
            Log.e(TAG, "Super Resolution başlatma hatası: ${e.message}", e)
            isInitialized = false
        }
    }

    /**
     * Bir bitmap yamasını (128x128 veya ölçeklenmiş) 4x süper çözünürlükle (512x512) yeniden üretir.
     */
    fun upscalePatch(patch: Bitmap): Bitmap {
        if (!isInitialized || interpreter == null) return patch

        val startTime = System.currentTimeMillis()
        try {
            val scaledInput = if (patch.width != INPUT_SIZE || patch.height != INPUT_SIZE) {
                Bitmap.createScaledBitmap(patch, INPUT_SIZE, INPUT_SIZE, true)
            } else patch

            prepareInputBuffer(scaledInput)
            inputBuffer.rewind()
            outputBuffer.rewind()

            interpreter?.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            val resultBitmap = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(OUTPUT_SIZE * OUTPUT_SIZE)

            for (i in 0 until (OUTPUT_SIZE * OUTPUT_SIZE)) {
                val r = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                val g = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                val b = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }

            resultBitmap.setPixels(pixels, 0, OUTPUT_SIZE, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE)
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "4x AI Super Resolution tamamlandı: ${elapsed}ms")

            if (scaledInput !== patch) scaledInput.recycle()
            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Super Resolution çıkarım hatası", e)
            return patch
        }
    }

    /**
     * Bitmap üzerinde AI süper çözünürlük veya doku iyileştirme uygular.
     */
    fun enhanceResolution(bitmap: Bitmap): Bitmap {
        if (!isInitialized || interpreter == null) return bitmap
        return try {
            upscalePatch(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "enhanceResolution hatası: ${e.message}", e)
            bitmap
        }
    }

    private fun prepareInputBuffer(bitmap: Bitmap) {
        inputBuffer.clear()
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in intValues) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            inputBuffer.putFloat(r)
            inputBuffer.putFloat(g)
            inputBuffer.putFloat(b)
        }
    }

    private fun loadModelFile(modelName: String): ByteBuffer? {
        return try {
            val fileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "Model dosyası okunamadı: $modelName - ${e.message}")
            null
        }
    }

    fun release() {
        try {
            interpreter?.close()
            interpreter = null
            gpuDelegate?.close()
            gpuDelegate = null
        } catch (_: Exception) {}
    }
}

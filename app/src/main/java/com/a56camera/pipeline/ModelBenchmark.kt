package com.a56camera.pipeline

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Samsung Galaxy A56 5G (Exynos 1580 / AMD Xclipse 540 GPU)
 * Gerçek Donanım Yapay Zeka Hızlandırma Başarım Testi (Benchmark).
 *
 * Her 4 model için:
 * - AMD Xclipse 540 GPU (OpenCL GpuDelegate)
 * - 4-İş Parçacıklı XNNPack CPU (CPU Fallback)
 * sürelerini ölçer ve somut hızlanma oranını raporlar.
 */
object ModelBenchmark {
    private const val TAG = "A56_BENCHMARK"

    data class BenchmarkResult(
        val modelName: String,
        val gpuAvgMs: Double,
        val gpuMinMs: Double,
        val gpuMaxMs: Double,
        val cpuAvgMs: Double,
        val cpuMinMs: Double,
        val cpuMaxMs: Double,
        val speedup: Double
    )

    fun runAll(context: Context): List<BenchmarkResult> {
        Log.i(TAG, "=========================================================================")
        Log.i(TAG, "🏁 [A56 HARDWARE BENCHMARK] AMD Xclipse 540 GPU vs 4-Thread XNNPack CPU")
        Log.i(TAG, "   Cihaz: Samsung Galaxy A56 5G (SM-A566B) | Yonga: Exynos 1580 (s5e8855)")
        Log.i(TAG, "   GPU: AMD Xclipse 540 (RDNA architecture, OpenCL 3.0)")
        Log.i(TAG, "=========================================================================")

        val results = mutableListOf<BenchmarkResult>()

        // 1. Model: Scene Optimizer (MobileNetV2, 224x224x3 FP32)
        benchmarkModel(
            context = context,
            name = "1. Scene Optimizer (MobileNetV2)",
            assetPath = "scene_classifier.tflite",
            inputBytes = 1 * 224 * 224 * 3 * 4,
            outputSupplier = { Array(1) { FloatArray(1001) } }
        )?.let { results.add(it) }

        // 2. Model: Zero-DCE (512x512x3 FP32)
        benchmarkModel(
            context = context,
            name = "2. Zero-DCE Gece Görüşü",
            assetPath = "zero_dce.tflite",
            inputBytes = 1 * 512 * 512 * 3 * 4,
            outputSupplier = {
                ByteBuffer.allocateDirect(1 * 512 * 512 * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
        )?.let { results.add(it) }

        // 3. Model: MiDaS 3D Depth (256x256x3 FP32)
        benchmarkModel(
            context = context,
            name = "3. MiDaS 3D Derinlik Motoru",
            assetPath = "midas_depth.tflite",
            inputBytes = 1 * 256 * 256 * 3 * 4,
            outputSupplier = { Array(1) { Array(256) { FloatArray(256) } } }
        )?.let { results.add(it) }

        // 4. Model: Real-ESRGAN x4 (128x128x3 FP32 -> 512x512x3 FP32)
        benchmarkModel(
            context = context,
            name = "4. Real-ESRGAN x4 AI Super-Res",
            assetPath = "realesrgan.tflite",
            inputBytes = 1 * 128 * 128 * 3 * 4,
            outputSupplier = {
                ByteBuffer.allocateDirect(1 * 512 * 512 * 3 * 4).apply {
                    order(ByteOrder.nativeOrder())
                }
            }
        )?.let { results.add(it) }

        Log.i(TAG, "=========================================================================")
        Log.i(TAG, "📋 [A56 BENCHMARK SONUÇ TABLOSU - ÖZET]")
        Log.i(TAG, "%-38s | %-12s | %-12s | %s".format("Model", "AMD GPU", "CPU 4-Th", "Hızlanma"))
        Log.i(TAG, "-------------------------------------------------------------------------")
        for (r in results) {
            Log.i(TAG, "%-38s | %8.2f ms | %8.2f ms | %6.2fx".format(r.modelName, r.gpuAvgMs, r.cpuAvgMs, r.speedup))
        }
        Log.i(TAG, "=========================================================================")

        return results
    }

    private fun benchmarkModel(
        context: Context,
        name: String,
        assetPath: String,
        inputBytes: Int,
        outputSupplier: () -> Any
    ): BenchmarkResult? {
        Log.i(TAG, "-------------------------------------------------------------------------")
        Log.i(TAG, "📊 Model Ölçümü Başlatılıyor: $name ($assetPath)")

        val modelBuffer = try {
            val afd = context.assets.openFd(assetPath)
            FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        } catch (e: Exception) {
            Log.e(TAG, "Model yüklenemedi: $assetPath (${e.message})")
            return null
        }

        val inBuf = ByteBuffer.allocateDirect(inputBytes).apply {
            order(ByteOrder.nativeOrder())
            for (i in 0 until (inputBytes / 4)) putFloat(0.5f)
            rewind()
        }

        // 1. AMD Xclipse 540 GPU Testi
        var gpuAvg = 0.0
        var gpuMin = Double.MAX_VALUE
        var gpuMax = 0.0
        var gpuSuccess = false
        var gpuDelegate: GpuDelegate? = null
        var gpuInterp: Interpreter? = null

        try {
            gpuDelegate = GpuDelegate()
            val gpuOptions = Interpreter.Options().apply {
                addDelegate(gpuDelegate)
            }
            gpuInterp = Interpreter(modelBuffer, gpuOptions)

            val outObj = outputSupplier()

            // Isınma (Warmup: 3 döngü)
            for (w in 1..3) {
                inBuf.rewind()
                if (outObj is ByteBuffer) outObj.rewind()
                gpuInterp.run(inBuf, outObj)
            }

            // 10 yineleme ölçümü
            val times = mutableListOf<Double>()
            for (i in 1..10) {
                inBuf.rewind()
                if (outObj is ByteBuffer) outObj.rewind()
                val t0 = System.nanoTime()
                gpuInterp.run(inBuf, outObj)
                val dt = (System.nanoTime() - t0) / 1_000_000.0
                times.add(dt)
                if (dt < gpuMin) gpuMin = dt
                if (dt > gpuMax) gpuMax = dt
            }
            gpuAvg = times.average()
            gpuSuccess = true
            Log.i(TAG, "   ⚡ [AMD GPU OpenCL]    Ort: %6.2f ms | Min: %6.2f ms | Max: %6.2f ms".format(gpuAvg, gpuMin, gpuMax))
        } catch (e: Throwable) {
            Log.e(TAG, "   💥 GPU Test Başarısız: ${e.message}", e)
        } finally {
            gpuInterp?.close()
            gpuDelegate?.close()
        }

        // 2. 4-Thread XNNPack CPU Testi
        var cpuAvg = 0.0
        var cpuMin = Double.MAX_VALUE
        var cpuMax = 0.0
        var cpuSuccess = false
        var cpuInterp: Interpreter? = null

        try {
            val cpuOptions = Interpreter.Options().apply {
                setNumThreads(4)
            }
            cpuInterp = Interpreter(modelBuffer, cpuOptions)

            val outObj = outputSupplier()

            // Isınma (Warmup: 3 döngü)
            for (w in 1..3) {
                inBuf.rewind()
                if (outObj is ByteBuffer) outObj.rewind()
                cpuInterp.run(inBuf, outObj)
            }

            // 10 yineleme ölçümü
            val times = mutableListOf<Double>()
            for (i in 1..10) {
                inBuf.rewind()
                if (outObj is ByteBuffer) outObj.rewind()
                val t0 = System.nanoTime()
                cpuInterp.run(inBuf, outObj)
                val dt = (System.nanoTime() - t0) / 1_000_000.0
                times.add(dt)
                if (dt < cpuMin) cpuMin = dt
                if (dt > cpuMax) cpuMax = dt
            }
            cpuAvg = times.average()
            cpuSuccess = true
            Log.i(TAG, "   🐢 [CPU 4-Thread]     Ort: %6.2f ms | Min: %6.2f ms | Max: %6.2f ms".format(cpuAvg, cpuMin, cpuMax))
        } catch (e: Throwable) {
            Log.e(TAG, "   💥 CPU Test Başarısız: ${e.message}", e)
        } finally {
            cpuInterp?.close()
        }

        return if (gpuSuccess && cpuSuccess) {
            val speedup = if (gpuAvg > 0) cpuAvg / gpuAvg else 0.0
            Log.i(TAG, "   🚀 [BAŞARIM ARTIŞI]   GPU, CPU'dan %.2fx kat daha hızlı!".format(speedup))
            BenchmarkResult(name, gpuAvg, gpuMin, gpuMax, cpuAvg, cpuMin, cpuMax, speedup)
        } else {
            null
        }
    }
}

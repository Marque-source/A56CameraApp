package io.github.marquesource.pixora.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * AMD Xclipse 540 GPU & XNNPack CPU Destekli Zero-DCE Gece Görüş Motoru
 * (Zero-Reference Deep Curve Estimation).
 */
class ZeroDceEngine(private val context: Context) {

    companion object {
        private const val TAG = "ZeroDceEngine"
        private const val MODEL_FILE = "zero_dce.tflite"
        private const val INPUT_SIZE = 512
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var isInitialized = false

    private val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            val modelBuffer = loadModelFile(MODEL_FILE) ?: run {
                Log.w(TAG, "$MODEL_FILE bulunamadı, Zero-DCE pasif.")
                return
            }

            val compat = CompatibilityList()
            val isSupported = compat.isDelegateSupportedOnThisDevice
            Log.i(TAG, "🔍 [CompatibilityList] Zero-DCE isDelegateSupportedOnThisDevice = $isSupported")

            var interp: Interpreter? = null

            // 1. ADIM: Doğrudan GPU Delegate zorlayarak dene ve hatayı yakala
            try {
                Log.i(TAG, "🧪 Zero-DCE GpuDelegate doğrudan başlatma deneniyor...")
                val gpuOptions = Interpreter.Options().apply {
                    val delegate = GpuDelegate()
                    gpuDelegate = delegate
                    addDelegate(delegate)
                }
                interp = Interpreter(modelBuffer, gpuOptions)
                Log.i(TAG, "🎉 [BAŞARILI] GpuDelegate AMD Xclipse GPU üzerinde Zero-DCE için kabul edildi!")
            } catch (e: Throwable) {
                Log.e(TAG, "💥 [Zero-DCE GPU DELEGATE REDDEDİLDİ / HATA]: ${e.javaClass.name}: ${e.message}", e)
                gpuDelegate?.close()
                gpuDelegate = null
            }

            // 2. ADIM: GPU reddedildiyse 4-Thread XNNPack CPU kullan (Android 15 resmi standardı)
            if (interp == null) {
                Log.i(TAG, "🧪 Zero-DCE Fallback: 4-thread XNNPack CPU başlatılıyor...")
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interp = Interpreter(modelBuffer, cpuOptions)
                Log.i(TAG, "✅ [FALLBACK AKTİF] Zero-DCE 4-thread XNNPack CPU ile yüklendi.")
            }

            interpreter = interp
            isInitialized = true
            Log.d(TAG, "🚀 Zero-DCE Gece Görüş Motoru başarıyla yüklendi! (PINTO Model Zoo)")
        } catch (e: Exception) {
            Log.e(TAG, "Zero-DCE başlatma hatası: ${e.message}", e)
            isInitialized = false
        }
    }

    /**
     * Düşük ışıklı veya zifiri karanlık bir fotoğrafı yapay zekâ ile aydınlatır.
     *
     * @param original Girdi fotoğrafı (tam çözünürlük)
     * @param intensity Aydınlatma şiddeti (0.0f - 1.0f)
     * @return Aydınlatılmış yüksek dinamik aralıklı fotoğraf
     */
    fun enhanceLowLight(original: Bitmap, intensity: Float = 0.65f): Bitmap {
        if (!isInitialized || interpreter == null) return original

        val startTime = System.currentTimeMillis()
        try {
            val width = original.width
            val height = original.height

            // 1. 512x512 önizleme yamasını hazırla
            val input512 = Bitmap.createScaledBitmap(original, INPUT_SIZE, INPUT_SIZE, true)
            prepareInputBuffer(input512)
            inputBuffer.rewind()
            outputBuffer.rewind()

            // 2. Zero-DCE çıkarımı yap (GPU/NPU üzerinde ~1ms)
            interpreter?.run(inputBuffer, outputBuffer)
            outputBuffer.rewind()

            // 3. 512x512 aydınlatılmış görseli Bitmap'e dönüştür
            val enhanced512 = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)

            for (i in 0 until (INPUT_SIZE * INPUT_SIZE)) {
                val r = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                val g = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                val b = (outputBuffer.float.coerceIn(0f, 1f) * 255f).toInt()
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            enhanced512.setPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            // 4. Guided Illumination Gain Map:
            // Orijinal 512 ile aydınlatılmış 512 arasındaki kazancı hesapla
            val inMat = YuvConverter.bitmapToMat(input512)
            val outMat = YuvConverter.bitmapToMat(enhanced512)
            input512.recycle()
            enhanced512.recycle()

            val inFloat = Mat()
            val outFloat = Mat()
            inMat.convertTo(inFloat, CvType.CV_32FC3, 1.0 / 255.0)
            outMat.convertTo(outFloat, CvType.CV_32FC3, 1.0 / 255.0)
            inMat.release()
            outMat.release()

            // gainMap = (enhanced + eps) / (input + eps)
            val eps = Mat(INPUT_SIZE, INPUT_SIZE, CvType.CV_32FC3, org.opencv.core.Scalar(0.01, 0.01, 0.01))
            val inSafe = Mat()
            val outSafe = Mat()
            Core.add(inFloat, eps, inSafe)
            Core.add(outFloat, eps, outSafe)
            val gainMap512 = Mat()
            Core.divide(outSafe, inSafe, gainMap512)
            inSafe.release()
            outSafe.release()
            eps.release()

            // 4b. Işık Patlaması Önleyici Maske (Highlight Protection Mask)
            // Sokak lambası, tabela ve parlak ışık kaynaklarında kazancı 1.0x (artışsız) tut
            val gray512 = Mat()
            Imgproc.cvtColor(inFloat, gray512, Imgproc.COLOR_BGR2GRAY)
            inFloat.release()
            outFloat.release()

            val shadowWeight = Mat()
            val targetScalarMat = Mat(gray512.size(), gray512.type(), org.opencv.core.Scalar(0.75))
            Core.subtract(targetScalarMat, gray512, shadowWeight)
            targetScalarMat.release()
            Core.divide(shadowWeight, org.opencv.core.Scalar(0.55), shadowWeight)
            Core.min(shadowWeight, org.opencv.core.Scalar(1.0), shadowWeight)
            Core.max(shadowWeight, org.opencv.core.Scalar(0.0), shadowWeight)
            gray512.release()

            val weightChannels = listOf(shadowWeight, shadowWeight, shadowWeight)
            val weight3 = Mat()
            Core.merge(weightChannels, weight3)
            shadowWeight.release()

            // protectedGain = 1.0 + (gainMap - 1.0) * shadowWeight
            val gainMinusOne = Mat()
            Core.subtract(gainMap512, org.opencv.core.Scalar(1.0, 1.0, 1.0), gainMinusOne)
            val weightedDelta = Mat()
            Core.multiply(gainMinusOne, weight3, weightedDelta)
            gainMinusOne.release()
            weight3.release()
            gainMap512.release()

            val protectedGainMap = Mat()
            Core.add(weightedDelta, org.opencv.core.Scalar(1.0, 1.0, 1.0), protectedGainMap)
            weightedDelta.release()

            // 5. Kazanç haritasını tam çözünürlüğe ölçekle (INTER_LINEAR kenar halosu ve undershoot yaratmaz)
            val fullGainMap = Mat()
            Imgproc.resize(protectedGainMap, fullGainMap, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            protectedGainMap.release()

            // 6. Tam çözünürlüklü orijinal fotoğrafa kazanç haritasını uygula
            val srcFull = YuvConverter.bitmapToMat(original)
            val srcFullFloat = Mat()
            srcFull.convertTo(srcFullFloat, CvType.CV_32FC3, 1.0 / 255.0)
            srcFull.release()

            val enhancedFullFloat = Mat()
            Core.multiply(srcFullFloat, fullGainMap, enhancedFullFloat)
            srcFullFloat.release()
            fullGainMap.release()

            // Şiddet ayarı (Alpha blend ile orijinal ve aydınlatılmışı karıştır)
            val finalFloat = Mat()
            if (intensity < 1.0f) {
                val origFloat = Mat()
                val origMat = YuvConverter.bitmapToMat(original)
                origMat.convertTo(origFloat, CvType.CV_32FC3, 1.0 / 255.0)
                origMat.release()

                Core.addWeighted(
                    enhancedFullFloat, intensity.toDouble(),
                    origFloat, (1.0f - intensity).toDouble(),
                    0.0, finalFloat
                )
                origFloat.release()
                enhancedFullFloat.release()
            } else {
                enhancedFullFloat.copyTo(finalFloat)
                enhancedFullFloat.release()
            }

            // [0, 1] -> [0, 255]
            val final8u = Mat()
            finalFloat.convertTo(final8u, CvType.CV_8UC3, 255.0)
            finalFloat.release()

            val resultBitmap = YuvConverter.matToBitmap(final8u)
            final8u.release()

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Zero-DCE Gece Aydınlatması tamamlandı: ${elapsed}ms")
            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Zero-DCE aydınlatma hatası", e)
            return original
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

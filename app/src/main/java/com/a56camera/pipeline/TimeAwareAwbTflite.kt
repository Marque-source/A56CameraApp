package com.a56camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Time-Aware AWB TFLite NPU Inference Modülü.
 */
class TimeAwareAwbTflite(context: Context, modelPath: String = "time_aware_awb.tflite") {

    private var interpreter: Interpreter? = null

    init {
        try {
            val buffer = loadModelBuffer(context, modelPath)
            if (buffer != null) {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(buffer, options)
                Log.i(TAG, "Time-Aware AWB modeli 4-thread XNNPack CPU üzerinde başarıyla yüklendi")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Time-Aware AWB modeli yüklenemedi ($modelPath): ${e.message}")
        }
    }

    /** Model yüklendi ve NPU üzerinde aktif mi? */
    fun isLoaded(): Boolean = interpreter != null

    /**
     * NPU üzerinde AWB kazanç tahmini yapar.
     * @return FloatArray [rGain, gGain, bGain] veya hata durumunda null.
     */
    fun predictGains(bitmap: Bitmap, captureResult: CaptureResult?): FloatArray? {
        val tInterpreter = interpreter ?: return null
        return try {
            val histBuffer = computeHistogramBuffer(bitmap)
            val featBuffer = extractCaptureFeatures(captureResult)

            val outputs = HashMap<Int, Any>()
            val outputArray = Array(1) { FloatArray(3) }
            outputs[0] = outputArray

            val inputs = arrayOf(histBuffer, featBuffer)
            tInterpreter.runForMultipleInputsOutputs(inputs, outputs)

            val rgb = outputArray[0]
            floatArrayOf(rgb[0], 1.0f, rgb[2])
        } catch (e: Exception) {
            Log.e(TAG, "Time-Aware AWB NPU çıkarım hatası", e)
            null
        }
    }

    private fun loadModelBuffer(context: Context, modelPath: String): ByteBuffer? {
        return try {
            val fileDescriptor = context.assets.openFd(modelPath)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
        } catch (e: Exception) {
            null
        }
    }

    private fun computeHistogramBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * 4 * 48 * 48).order(ByteOrder.nativeOrder())
        // [1, 4, 48, 48] float32 histogram tamponu
        val width = bitmap.width
        val height = bitmap.height
        val step = maxOf(1, width / 48)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val hist = Array(4) { Array(48) { FloatArray(48) } }
        var count = 0

        for (i in pixels.indices step step) {
            val px = pixels[i]
            val r = (px shr 16 and 0xFF) / 255f
            val g = (px shr 8 and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            val gVal = g.coerceAtLeast(0.001f)

            val rg = (r / gVal).coerceIn(0.2f, 2.5f)
            val bg = (b / gVal).coerceIn(0.2f, 2.5f)

            val xIdx = ((rg - 0.2f) / (2.3f / 48f)).toInt().coerceIn(0, 47)
            val yIdx = ((bg - 0.2f) / (2.3f / 48f)).toInt().coerceIn(0, 47)

            hist[0][yIdx][xIdx] += 1f
            count++
        }

        val normFactor = if (count > 0) count.toFloat() else 1f
        for (c in 0 until 4) {
            for (y in 0 until 48) {
                for (x in 0 until 48) {
                    buffer.putFloat(hist[c][y][x] / normFactor)
                }
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun extractCaptureFeatures(result: CaptureResult?): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * 16).order(ByteOrder.nativeOrder())
        val iso = (result?.get(CaptureResult.SENSOR_SENSITIVITY) as? Int)?.toFloat() ?: 400f
        val exp = (result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) as? Long)?.toFloat() ?: 33000000f

        buffer.putFloat(iso)
        buffer.putFloat(exp / 1_000_000f) // ms
        buffer.putFloat(1f / (exp / 1_000_000_000f).coerceAtLeast(0.001f)) // shutter speed
        buffer.putFloat(0f) // flash

        // Time / probabilities
        for (i in 4 until 16) {
            buffer.putFloat(0f)
        }

        buffer.rewind()
        return buffer
    }

    fun close() {
        try {
            interpreter?.close()
            interpreter = null
        } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "TimeAwareAwbTflite"
    }
}

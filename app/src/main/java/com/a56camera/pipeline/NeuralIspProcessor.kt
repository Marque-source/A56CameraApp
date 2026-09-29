package com.a56camera.pipeline

import android.graphics.Bitmap
import android.util.Log
import com.a56camera.camera.YuvFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NeuralIspProcessor @Inject constructor(
    private val interpreter: Interpreter?,
    private val gpuPreprocessor: GpuYuvToRgbPreprocessor
) {
    private val tag = "NeuralIspProcessor"

    companion object {
        const val INPUT_W = 512
        const val INPUT_H = 512
        const val OUTPUT_W = 512
        const val OUTPUT_H = 512
    }

    suspend fun refine(
        yuvFrame: YuvFrame,
        orientation: Int = 0
    ): Bitmap? = withContext(Dispatchers.Default) {
        val interp = interpreter ?: run {
            Log.w(tag, "Interpreter null, inference atlanıyor")
            return@withContext null
        }

        try {
            // 1) YUV → RGB TensorImage
            val tensorImage = gpuPreprocessor.convert(yuvFrame, orientation, INPUT_W, INPUT_H)
                ?: return@withContext null

            // 2) Buffer
            val inputBuffer = tensorImage.buffer

            // 3) Output buffer
            val outputBuffer = TensorBuffer.createFixedSize(
                intArrayOf(1, OUTPUT_H, OUTPUT_W, 3),
                DataType.FLOAT32
            )

            // 4) Inference
            interp.run(inputBuffer, outputBuffer.buffer)

            // 5) Bitmap'e çevir
            outputToBitmap(outputBuffer)
        } catch (e: Throwable) {
            Log.e(tag, "Neural ISP inference hatası", e)
            null
        }
    }

    private fun outputToBitmap(output: TensorBuffer): Bitmap {
        val w = OUTPUT_W
        val h = OUTPUT_H
        val arr = output.floatArray

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var idx = 0
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = if (idx < arr.size) (arr[idx++] * 255f).toInt().coerceIn(0, 255) else 0
                val g = if (idx < arr.size) (arr[idx++] * 255f).toInt().coerceIn(0, 255) else 0
                val b = if (idx < arr.size) (arr[idx++] * 255f).toInt().coerceIn(0, 255) else 0
                pixels[y * w + x] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }
}

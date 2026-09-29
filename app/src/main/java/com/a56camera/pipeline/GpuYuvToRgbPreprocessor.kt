package com.a56camera.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.a56camera.camera.YuvFrame
import com.a56camera.camera.toNv21
import org.tensorflow.lite.support.image.TensorImage
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GpuYuvToRgbPreprocessor @Inject constructor(
    private val context: Context
) {
    private val tag = "GpuYuvToRgbPreprocessor"

    suspend fun convert(
        frame: YuvFrame,
        orientation: Int,
        outW: Int,
        outH: Int
    ): TensorImage? {
        return try {
            val tensorImage = TensorImage.fromBitmap(
                yuvToBitmap(frame, outW, outH)
            )
            tensorImage
        } catch (e: Throwable) {
            Log.e(tag, "GPU/CPU Preprocessing dönüştürme hatası", e)
            null
        }
    }

    private fun yuvToBitmap(frame: YuvFrame, outW: Int, outH: Int): Bitmap {
        val yuvImage = YuvImage(
            frame.toNv21(),
            ImageFormat.NV21,
            frame.width,
            frame.height,
            null
        )
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), 100, stream)
        val jpeg = stream.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        return Bitmap.createScaledBitmap(bitmap, outW, outH, true)
    }
}

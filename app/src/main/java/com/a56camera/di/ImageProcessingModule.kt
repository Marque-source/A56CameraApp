package com.a56camera.di

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.MappedByteBuffer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageProcessingModule {

    private const val TAG = "ImageProcessingModule"
    private const val MODEL_NAME = "s24_ultra_isp_refinement_int8.tflite"

    @Provides
    @Singleton
    fun provideModelBuffer(@ApplicationContext context: Context): MappedByteBuffer? {
        return try {
            FileUtil.loadMappedFile(context, MODEL_NAME)
        } catch (e: Throwable) {
            Log.w(TAG, "NPU TFLite model dosyası ($MODEL_NAME) yüklenemedi: ${e.message}")
            null
        }
    }

    @Provides
    @Singleton
    fun provideInterpreter(modelBuffer: MappedByteBuffer?): Interpreter? {
        if (modelBuffer == null) return null

        val options = Interpreter.Options().apply {
            numThreads = 4
            setUseXNNPACK(true)
        }

        return try {
            Log.i(TAG, "TFLite Interpreter NPU/XNNPACK modunda hazırlandı")
            Interpreter(modelBuffer, options)
        } catch (e: Throwable) {
            Log.e(TAG, "TFLite Interpreter oluşturulamadı", e)
            null
        }
    }
}

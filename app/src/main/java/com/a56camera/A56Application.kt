package com.a56camera

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class A56Application : Application() {

    var samsungSdkAvailable: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        initOpenCv()
        // Samsung SCamera SDK A56'da yok, kullanma
        samsungSdkAvailable = false

        try {
            val filter = android.content.IntentFilter("com.a56camera.RUN_BENCHMARK")
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                        Thread {
                            com.a56camera.pipeline.ModelBenchmark.runAll(ctx)
                        }.start()
                    }
                },
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Benchmark broadcast receiver kaydedilemedi: ${e.message}")
        }
    }

    private fun initOpenCv() {
        try {
            if (OpenCVLoader.initDebug()) {
                Log.i(TAG, "OpenCV yüklendi")
            } else {
                Log.w(TAG, "OpenCV yüklenemedi")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV hata: ${e.message}")
        }
    }

    companion object { private const val TAG = "A56Application" }
}

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
        samsungSdkAvailable = false
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

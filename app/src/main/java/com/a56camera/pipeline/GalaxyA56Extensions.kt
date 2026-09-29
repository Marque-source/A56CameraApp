package com.a56camera.pipeline

import android.hardware.camera2.CameraExtensionCharacteristics
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Galaxy A56 Hibrit Çekim Stratejisi Yönlendiricisi.
 *
 * Cihazın durumuna (ışık, hareket, yüz, RAW isteği) göre en uygun
 * Samsung Extension modunu veya Özel RAW Burst hattını seçer.
 */
object GalaxyA56Extensions {

    const val MODE_CUSTOM_RAW_BURST = -1

    @RequiresApi(Build.VERSION_CODES.S)
    const val AUTO = CameraExtensionCharacteristics.EXTENSION_AUTOMATIC
    @RequiresApi(Build.VERSION_CODES.S)
    const val NIGHT = CameraExtensionCharacteristics.EXTENSION_NIGHT
    @RequiresApi(Build.VERSION_CODES.S)
    const val HDR = CameraExtensionCharacteristics.EXTENSION_HDR
    @RequiresApi(Build.VERSION_CODES.S)
    const val BOKEH = CameraExtensionCharacteristics.EXTENSION_BOKEH

    /**
     * Sahne analizine ve kullanıcı tercihine göre en doğru çekim motorunu seçer.
     */
    fun pickExtension(
        luma: Double,
        hasFaces: Boolean,
        motion: Double,
        wantsRaw: Boolean,
        supportedExtensions: List<Int>
    ): Int {
        // 1. Kullanıcı RAW çekmek istiyorsa özel RAW Burst hattımıza yönlendir
        if (wantsRaw) return MODE_CUSTOM_RAW_BURST

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return MODE_CUSTOM_RAW_BURST

        // 2. Sahne koşullarına göre Samsung Extension seçimi
        return when {
            luma < 20 && motion < 15 && supportedExtensions.contains(NIGHT) -> NIGHT
            luma > 85 && supportedExtensions.contains(HDR) -> HDR
            hasFaces && supportedExtensions.contains(BOKEH) -> BOKEH
            supportedExtensions.contains(AUTO) -> AUTO
            else -> MODE_CUSTOM_RAW_BURST
        }
    }
}

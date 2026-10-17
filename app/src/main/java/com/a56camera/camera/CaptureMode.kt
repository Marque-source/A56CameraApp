package com.a56camera.camera

import com.a56camera.R

/**
 * Uygulamadaki tüm çekim modları.
 * Pixel Camera tarzı yatay mod seçicide gösterilir.
 */
enum class CaptureMode(val titleRes: Int, val isVideo: Boolean = false) {
    PHOTO(R.string.mode_photo),
    VIDEO(R.string.mode_video, isVideo = true),
    PRO(R.string.mode_pro),
    NIGHT(R.string.mode_night),
    PORTRAIT(R.string.mode_portrait),
    PANORAMA(R.string.mode_panorama),
    TIME_LAPSE(R.string.mode_timelapse, isVideo = true);

    companion object {
        /** Mod seçicide gösterilecek sıralama. */
        val ordered = listOf(NIGHT, PORTRAIT, PHOTO, VIDEO, PRO, PANORAMA, TIME_LAPSE)
    }
}

/** Flaş durumları. */
enum class FlashMode { AUTO, ON, OFF, TORCH }

/** Zamanlayıcı seçenekleri (saniye). */
enum class TimerMode(val seconds: Int) { OFF(0), THREE(3), TEN(10) }


/** Izgara tipleri. */
enum class GridType { OFF, GRID_3X3, SQUARE, GOLDEN }

/**
 * Galaxy A56 kamera modülleri ve karşılık gelen zoom oranları.
 * 0.6x = ultra geniş, 1x = ana (50MP), 2x = ana kameradan dijital kırpma.
 */
enum class CameraModule(val zoomRatio: Float, val label: String) {
    ULTRA_WIDE(0.6f, "0.6x"),
    WIDE(1.0f, "1x"),
    TELE_2X(2.0f, "2x")
}

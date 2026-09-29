package com.a56camera.ui.settings

data class CameraSettingsState(
    val preview1080p: Boolean = true,
    val smoothPreview: Boolean = true, // Akıcı Önizleme eklendi
    val nightMode: Boolean = false,
    val hdr: Boolean = true,
    val motionPhoto: Boolean = false,
    val timerSeconds: Int = 0,
    val gridEnabled: Boolean = true,
    val shutterSound: Boolean = true,
    val saveLocation: Boolean = false,
    val jpegQuality: Float = 95f
)

package com.a56camera.camera

import android.location.Location

data class CaptureMetadata(
    val location: Location? = null,
    val rotationDegrees: Int = 0
)

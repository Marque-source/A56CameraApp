package com.a56camera.camera

import android.util.Size

/**
 * Samsung A56 stok çözünürlük ve mod tanımları.
 */
enum class SamsungCameraMode(
    val previewResolution: Size,
    val analysisResolution: Size,
    val captureResolution: Size
) {
    AUTO(
        previewResolution = Size(1440, 1080),
        analysisResolution = Size(1280, 720),
        captureResolution = Size(4000, 3000)
    ),
    NIGHT(
        previewResolution = Size(1440, 1080),
        analysisResolution = Size(1280, 720),
        captureResolution = Size(4000, 3000)
    ),
    HDR(
        previewResolution = Size(1440, 1080),
        analysisResolution = Size(1280, 720),
        captureResolution = Size(4000, 3000)
    ),
    FACE_RETOUCH(
        previewResolution = Size(1440, 1080),
        analysisResolution = Size(512, 384),
        captureResolution = Size(4000, 3000)
    ),
    BOKEH(
        previewResolution = Size(1440, 1080),
        analysisResolution = Size(1280, 720),
        captureResolution = Size(4000, 3000)
    )
}

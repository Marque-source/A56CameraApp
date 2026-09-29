package com.a56camera.camera

/**
 * YuvFrame extension metodu — NV21 byte dizisine dönüştürür.
 */
fun YuvFrame.toNv21(): ByteArray {
    val nv21 = ByteArray(width * height * 3 / 2)

    // Y Düzlemi
    for (row in 0 until height) {
        val srcPos = row * yRowStride
        val len = kotlin.math.min(width, kotlin.math.max(0, y.size - srcPos))
        if (len > 0 && row * width + len <= nv21.size) {
            System.arraycopy(y, srcPos, nv21, row * width, len)
        }
    }

    // UV Düzlemi
    val uvH = height / 2
    val uvW = width / 2
    for (row in 0 until uvH) {
        for (col in 0 until uvW) {
            val srcIdx = row * vRowStride + col * vPixelStride
            val dst = width * height + row * width + col * 2
            if (dst + 1 < nv21.size) {
                nv21[dst] = v.getOrElse(srcIdx) { 0 }
                nv21[dst + 1] = u.getOrElse(srcIdx) { 0 }
            }
        }
    }
    return nv21
}

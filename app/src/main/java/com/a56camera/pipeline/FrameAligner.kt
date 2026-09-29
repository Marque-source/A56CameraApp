package com.a56camera.pipeline

import org.opencv.core.Mat

object FrameAligner {

    fun alignFrames(frames: List<Mat>, baseIndex: Int = frames.size / 2): List<Mat> {
        if (frames.isEmpty()) return emptyList()
        // Görüntü bozulmasını (distortion/halo) önlemek için doğrudan kareleri klonla
        return frames.map { it.clone() }
    }
}

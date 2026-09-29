package com.a56camera.pipeline

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video

/**
 * Kareleri referans kareye OpenCV ECC ile hizalar.
 * Düşük ışık/OIS kombinasyonu için kısa mesafeli hareketlerde güzel çalışır.
 */
class MotionAligner {

    fun align(
        frames: List<Mat>,
        referenceIndex: Int = frames.size / 2
    ): List<Mat> {
        require(frames.isNotEmpty())
        val reference = frames[referenceIndex]
        val grayRef = Mat()
        Imgproc.cvtColor(reference, grayRef, Imgproc.COLOR_RGB2GRAY)

        val aligned = mutableListOf<Mat>()
        for ((idx, frame) in frames.withIndex()) {
            if (idx == referenceIndex) {
                aligned.add(frame.clone())
                continue
            }
            val grayFrame = Mat()
            Imgproc.cvtColor(frame, grayFrame, Imgproc.COLOR_RGB2GRAY)

            val warpMatrix = Mat.eye(2, 3, CvType.CV_32F)
            try {
                Video.findTransformECC(
                    grayRef,
                    grayFrame,
                    warpMatrix,
                    Video.MOTION_EUCLIDEAN,
                    TermCriteria(
                        TermCriteria.COUNT + TermCriteria.EPS,
                        50,
                        0.001
                    )
                )
            } catch (e: Exception) {
                // Hizalama başarısız olursa identity kullan
                warpMatrix.setTo(Mat.eye(2, 3, CvType.CV_32F))
            }

            val alignedFrame = Mat()
            Imgproc.warpAffine(
                frame,
                alignedFrame,
                warpMatrix,
                frame.size(),
                Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP
            )

            aligned.add(alignedFrame)
            grayFrame.release()
            warpMatrix.release()
        }
        grayRef.release()
        return aligned
    }
}

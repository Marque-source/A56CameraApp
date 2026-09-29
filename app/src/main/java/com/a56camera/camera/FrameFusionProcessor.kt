package com.a56camera.camera

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import org.opencv.video.Video
import java.io.File
import kotlin.math.pow

data class CapturedFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val evCompensation: Float,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CapturedFrame
        return timestamp == other.timestamp
    }

    override fun hashCode(): Int = timestamp.hashCode()
}

class FrameFusionProcessor {

    enum class Mode { NIGHT, HDR }

    fun process(
        frames: List<CapturedFrame>,
        mode: Mode,
        output: File,
        orientation: Int
    ) {
        require(frames.size >= 2) { "At least 2 frames required for fusion" }

        val rgbs = frames.map { YuvUtils.nv21ToRgb(it.nv21, it.width, it.height) }
        val reference = rgbs.first()

        // 1) ECC Hizalama (düşük çözünürlükte hizalama)
        val aligned = mutableListOf<Mat>()
        aligned.add(reference.clone())
        for (i in 1 until rgbs.size) {
            aligned.add(alignEcc(reference, rgbs[i]))
            rgbs[i].release()
        }

        // 2) Fusion
        val fused = when (mode) {
            Mode.NIGHT -> averageAndEnhance(aligned)
            Mode.HDR -> mertensFusion(aligned)
        }

        // 3) JPEG encode
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95)
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", fused, buf, params)
        output.writeBytes(buf.toArray())

        // 4) EXIF orientation
        try {
            val exif = ExifInterface(output.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation(orientation).toString())
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w("FrameFusion", "Exif yazılamadı: ${e.message}")
        }

        fused.release()
        reference.release()
        aligned.forEach { it.release() }
        params.release()
        buf.release()
        Log.d("FrameFusion", "Fusion kaydedildi: $output")
    }

    private fun alignEcc(ref: Mat, cur: Mat): Mat {
        val grayRef = Mat()
        val grayCur = Mat()
        Imgproc.cvtColor(ref, grayRef, Imgproc.COLOR_RGB2GRAY)
        Imgproc.cvtColor(cur, grayCur, Imgproc.COLOR_RGB2GRAY)

        val scale = 0.25
        val smallRef = Mat()
        val smallCur = Mat()
        Imgproc.resize(grayRef, smallRef, Size(grayRef.width() * scale, grayRef.height() * scale))
        Imgproc.resize(grayCur, smallCur, Size(grayCur.width() * scale, grayCur.height() * scale))

        val warp = Mat.eye(2, 3, CvType.CV_32F)
        val criteria = TermCriteria(TermCriteria.MAX_ITER + TermCriteria.EPS, 60, 1e-5)

        val aligned = try {
            val score = Video.findTransformECC(
                smallRef, smallCur, warp,
                Video.MOTION_TRANSLATION, criteria
            )
            if (score.isNaN()) throw IllegalStateException("ECC failed")
            val out = Mat()
            Imgproc.warpAffine(cur, out, warp, cur.size())
            out
        } catch (e: Exception) {
            Log.w("FrameFusion", "Alignment failed, using unaligned frame: ${e.message}")
            cur.clone()
        }

        grayRef.release(); grayCur.release()
        smallRef.release(); smallCur.release(); warp.release()
        return aligned
    }

    private fun averageAndEnhance(mats: List<Mat>): Mat {
        val sum = Mat(mats.first().size(), CvType.CV_32FC3, Scalar(0.0))
        val temp = Mat()
        for (m in mats) {
            m.convertTo(temp, CvType.CV_32FC3)
            Core.add(sum, temp, sum)
        }
        Core.divide(sum, Scalar.all(mats.size.toDouble()), sum)
        val avg = Mat()
        sum.convertTo(avg, CvType.CV_8UC3)
        sum.release(); temp.release()

        return enhanceNight(avg)
    }

    private fun enhanceNight(rgb: Mat): Mat {
        // Bilateral gürültü kaldırma
        val denoised = Mat()
        Imgproc.bilateralFilter(rgb, denoised, 5, 50.0, 50.0)

        // LAB CLAHE (yerel kontrast)
        val lab = Mat()
        Imgproc.cvtColor(denoised, lab, Imgproc.COLOR_RGB2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        Core.merge(channels, lab)
        val enhanced = Mat()
        Imgproc.cvtColor(lab, enhanced, Imgproc.COLOR_Lab2RGB)

        // Gama / shadow boost
        val gamma = 0.85
        val lut = Mat(1, 256, CvType.CV_8UC1)
        for (i in 0..255) {
            val v = ((i / 255.0).pow(gamma) * 255).toInt().coerceIn(0, 255)
            lut.put(0, i, v.toDouble())
        }
        Core.LUT(enhanced, lut, enhanced)

        rgb.release(); denoised.release(); lab.release(); lut.release()
        channels.forEach { it.release() }
        return enhanced
    }

    private fun mertensFusion(mats: List<Mat>): Mat {
        return try {
            val merge = Photo.createMergeMertens()
            val result = Mat()
            merge.process(mats, result)
            val eight = Mat()
            result.convertTo(eight, CvType.CV_8UC3, 255.0)
            result.release()
            eight
        } catch (e: Exception) {
            Log.w("FrameFusion", "Mertens unavailable, falling back to average: ${e.message}")
            averageAndEnhance(mats)
        }
    }

    private fun exifOrientation(deg: Int): Int = when (deg) {
        0 -> ExifInterface.ORIENTATION_NORMAL
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}

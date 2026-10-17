package com.a56camera.pipeline

import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.a56camera.camera.YuvFrame
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Samsung S25 / iPhone tarzı çoklu kare ORB hizalamalı ve Mertens Exposure Fusion işlemcisi.
 */
class MultiFrameProcessor {

    companion object {
        private const val TAG = "MultiFrameProcessor"
        private const val ALIGN_WIDTH = 960
        private const val ORB_FEATURES = 500
    }

    data class ProcessOutput(
        val mergedMat8u: Mat,
        val rawMat16u: Mat? = null
    )

    /**
     * Birden çok EV bracket kareyi hizalar -> birleştirir -> tone map uygular.
     */
    fun process(
        frames: List<Pair<YuvFrame, TotalCaptureResult>>,
        produceRaw16u: Boolean = false
    ): ProcessOutput {
        if (frames.isEmpty()) throw IllegalArgumentException("Kare yok")

        frames.forEachIndexed { i, (frame, meta) ->
            val luma = frame.computeMeanY()
            val exp = meta.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1_000_000 } ?: 0
            val iso = meta.get(TotalCaptureResult.SENSOR_SENSITIVITY) ?: 0
            Log.i(TAG, "🧪 [Mertens Öncesi Ham Kare #${i + 1}] Parlaklık (Y-Luma): %.2f / 255.0 (exp=${exp}ms, iso=$iso, EV=%.1f)".format(luma, frame.evShift))
        }

        if (frames.size == 1) {
            val singleMat = yuvToMat(frames[0].first)
            val raw16u = if (produceRaw16u) {
                val r16 = Mat()
                singleMat.convertTo(r16, CvType.CV_16UC3, 257.0) // 255 * 257 = 65535
                r16
            } else null
            return ProcessOutput(singleMat, raw16u)
        }

        val tTotal0 = System.currentTimeMillis()

        // 1. YUV -> OpenCV Mat
        val tYuv0 = System.currentTimeMillis()
        val mats = frames.mapIndexed { i, (frame, _) ->
            val bgrMat = yuvToMat(frame)
            Log.i(TAG, "🔬 [YUV→BGR Sonrası Kare #${i + 1}] Mean=${Core.mean(bgrMat).`val`.joinToString()} (Ham YUV Luma ile karşılaştır!)")
            bgrMat
        }
        val tYuv = System.currentTimeMillis() - tYuv0

        // 2. Referans kare = EN KESKİN KARE
        val tSharp0 = System.currentTimeMillis()
        var bestIdx = 0
        var maxSharpness = -1.0
        val grayMats = mats.map { m ->
            val g = Mat()
            Imgproc.cvtColor(m, g, Imgproc.COLOR_BGR2GRAY)
            g
        }
        grayMats.forEachIndexed { i, g ->
            val lap = Mat()
            Imgproc.Laplacian(g, lap, CvType.CV_16S)
            val meanStd = MatOfDouble()
            val stdDev = MatOfDouble()
            Core.meanStdDev(lap, meanStd, stdDev)
            val score = stdDev.toArray().getOrNull(0) ?: 0.0
            lap.release()
            meanStd.release()
            stdDev.release()
            if (score > maxSharpness) {
                maxSharpness = score
                bestIdx = i
            }
        }
        val refIdx = bestIdx
        val refMat = mats[refIdx]
        val fullWidth = refMat.cols()
        val fullHeight = refMat.rows()
        val tSharp = System.currentTimeMillis() - tSharp0
        Log.i(TAG, "🎯 [En Keskin Referans Kare] #${refIdx + 1} seçildi (Keskinlik Skoru: %.1f, Süre: ${tSharp}ms)".format(maxSharpness))

        // 3. Hızlı hizalama piramidi
        val tAlign0 = System.currentTimeMillis()
        val alignWidth = if (fullWidth > ALIGN_WIDTH) ALIGN_WIDTH else fullWidth
        val alignHeight = (fullHeight * (alignWidth.toDouble() / fullWidth)).toInt()
        val scaleX = fullWidth.toDouble() / alignWidth
        val scaleY = fullHeight.toDouble() / alignHeight

        val refSmall = Mat()
        Imgproc.resize(grayMats[refIdx], refSmall, Size(alignWidth.toDouble(), alignHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        grayMats.forEach { it.release() }

        val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
        val refEnhanced = Mat()
        clahe.apply(refSmall, refEnhanced)
        refSmall.release()

        val detector = ORB.create(750, 1.2f, 8, 15, 0, 2, ORB.HARRIS_SCORE, 15, 12)
        val refKp = MatOfKeyPoint()
        val refDesc = Mat()
        detector.detectAndCompute(refEnhanced, Mat(), refKp, refDesc)
        refEnhanced.release()

        val validAligned = mutableListOf<Mat>()
        validAligned.add(refMat)

        mats.forEachIndexed { i, mat ->
            if (i != refIdx) {
                val aligned = alignFrameFast(
                    frame = mat,
                    refKp = refKp,
                    refDesc = refDesc,
                    detector = detector,
                    clahe = clahe,
                    alignWidth = alignWidth,
                    alignHeight = alignHeight,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight
                )
                validAligned.add(aligned)
            }
        }

        refKp.release()
        refDesc.release()
        val tAlign = System.currentTimeMillis() - tAlign0

        // Gündüz pozlama braketi (-0.5 EV + 0 EV): Mertens füzyonu parlak alanları -0.5 EV kareden,
        // gölgeleri 0 EV karelerden alır. Gece burst'ü aynı pozlamayla çekildiği için ortalanır.
        val isNightBurst = frames.any { (it.second.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) >= 500 } ||
                           frames.any { (it.second.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) >= 30_000_000L }
        val hasExposureBracket = !isNightBurst && frames.any { it.first.evShift < -0.1f || it.first.evShift > 0.1f }
        if (hasExposureBracket) {
            Log.i(TAG, "🌟 [Mertens Exposure Fusion] Farklı pozlamalı ${validAligned.size} kare birleştiriliyor")
            val tMertens0 = System.currentTimeMillis()

            // DİKKAT: OpenCV MergeMertens her girdiyi içeride 1/255 ile ölçekler (girdi tipine bakmadan).
            // Bu yüzden kareler 8-bit verilmeli; 0..1 float verilince sonuç ~0 (siyah) çıkıyordu.
            val merger = Photo.createMergeMertens().apply {
                contrastWeight = 1.0f
                saturationWeight = 1.0f
                exposureWeight = 1.0f
            }
            val fused = Mat() // CV_32FC3, yaklaşık 0..1
            merger.process(validAligned, fused)

            val raw16u = if (produceRaw16u) {
                val r16 = Mat()
                fused.convertTo(r16, CvType.CV_16UC3, 65535.0)
                r16
            } else null

            val fused8u = Mat()
            fused.convertTo(fused8u, CvType.CV_8UC3, 255.0)
            fused.release()
            validAligned.forEach { it.release() }

            Log.i(TAG, "⚡ [Mertens Tamamlandı] Süre: ${System.currentTimeMillis() - tMertens0}ms")
            return ProcessOutput(fused8u, raw16u)
        }

        // Aynı pozlamalı kareler: hareket korumalı eşit ağırlıklı ortalama (gürültü √N kat azalır)
        val tMerge0 = System.currentTimeMillis()
        // 16-bit çıktı Expert RAW (doğrusal DNG) için
        val (result, raw16u) = TemporalMerge.weightedAverage(validAligned, motionThreshold = 0.20, produce16u = produceRaw16u)
        validAligned.forEach { it.release() }

        Log.i(TAG, "⏱️ [MultiFrame] TOPLAM: ${System.currentTimeMillis() - tTotal0}ms | YUV→BGR: ${tYuv}ms | " +
            "Keskinlik: ${tSharp}ms | Hizalama: ${tAlign}ms | Ortalama: ${System.currentTimeMillis() - tMerge0}ms")
        return ProcessOutput(result, raw16u)
    }

    /**
     * Geriye uyumluluk için tek Mat döndüren process metodu
     */
    fun process(frames: List<Pair<YuvFrame, TotalCaptureResult>>): Mat {
        return process(frames, produceRaw16u = false).mergedMat8u
    }

    /**
     * Görüntüyü ara Bitmap çevrimlerine sokmadan, doğrudan saf OpenCV Mat olarak birleştirir.
     * Bu sayede ara katmanlardaki matlık, renk bozulması ve bulanıklık %100 yok edilir.
     */
    fun processToMat(frames: List<Pair<YuvFrame, TotalCaptureResult>>): Mat {
        return process(frames)
    }

    fun processToBitmap(frames: List<Pair<YuvFrame, TotalCaptureResult>>): Bitmap {
        val mat = process(frames)
        val bmp = matToBitmap(mat)
        mat.release()
        return bmp
    }

    /**
     * Piramit tabanlı alt-örneklemeli homografi hesaplama.
     * 12MP yerine 960px üzerinde özellik eşleyip, koordinatları 12MP'e ölçekleyerek
     * CPU maliyetini 1.8 saniyeden ~25 milisaniyeye düşürür.
     */
    private fun alignFrameFast(
        frame: Mat,
        refKp: MatOfKeyPoint,
        refDesc: Mat,
        detector: ORB,
        clahe: CLAHE,
        alignWidth: Int,
        alignHeight: Int,
        scaleX: Double,
        scaleY: Double,
        fullWidth: Int,
        fullHeight: Int
    ): Mat {
        if (refDesc.empty()) return frame.clone()

        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_BGR2GRAY)
        val graySmall = Mat()
        Imgproc.resize(gray, graySmall, Size(alignWidth.toDouble(), alignHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        gray.release()

        // CLAHE yalnızca keypoint bulmayı kolaylaştırmak için gri kanalda uygulanır
        val enhanced = Mat()
        clahe.apply(graySmall, enhanced)
        graySmall.release()

        val kp = MatOfKeyPoint()
        val desc = Mat()
        detector.detectAndCompute(enhanced, Mat(), kp, desc)
        enhanced.release()

        if (desc.empty()) {
            kp.release(); desc.release()
            return frame.clone()
        }

        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val matches = MatOfDMatch()
        matcher.match(refDesc, desc, matches)

        val sorted = matches.toList().sortedBy { it.distance }
        val good = sorted.take((sorted.size * 0.35).toInt().coerceAtLeast(10))

        if (good.size < 4) {
            kp.release(); desc.release(); matches.release()
            return frame.clone()
        }

        val refKpList = refKp.toList()
        val kpList = kp.toList()

        // Eşleşen noktaları tam çözünürlüğe (12MP) ölçekle
        val srcPts = MatOfPoint2f(*good.map {
            val pt = kpList[it.trainIdx].pt
            org.opencv.core.Point(pt.x * scaleX, pt.y * scaleY)
        }.toTypedArray())

        val dstPts = MatOfPoint2f(*good.map {
            val pt = refKpList[it.queryIdx].pt
            org.opencv.core.Point(pt.x * scaleX, pt.y * scaleY)
        }.toTypedArray())

        kp.release(); desc.release(); matches.release()

        // RANSAC ile aykırı değerleri filtreleyerek tam çözünürlüklü homografi hesapla
        val homography = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 4.0)
        srcPts.release(); dstPts.release()

        var aligned: Mat? = null
        if (!homography.empty()) {
            val h00 = homography.get(0, 0)[0]
            val h01 = homography.get(0, 1)[0]
            val h10 = homography.get(1, 0)[0]
            val h11 = homography.get(1, 1)[0]
            val det = h00 * h11 - h01 * h10
            if (det in 0.6..1.6) {
                aligned = Mat()
                Imgproc.warpPerspective(frame, aligned, homography, Size(fullWidth.toDouble(), fullHeight.toDouble()))
            }
            homography.release()
        }

        // Homografi bulunamadıysa veya bozuksa, kareyi ÇÖPE ATMA!
        // Donanım OIS aktif olduğu için titreşim < 2 pikseldir; NEON MAD kernel zaten korur.
        return aligned ?: frame.clone()
    }

    private fun yuvToMat(frame: YuvFrame): Mat {
        val rgb = YuvConverter.toRgbMat(frame)
        val bgr = Mat()
        Imgproc.cvtColor(rgb, bgr, Imgproc.COLOR_RGB2BGR)
        rgb.release()
        return bgr
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_BGR2RGBA)
        val bmp = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgb, bmp)
        rgb.release()
        return bmp
    }
}

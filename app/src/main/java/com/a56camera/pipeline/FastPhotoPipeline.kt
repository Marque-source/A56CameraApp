package com.a56camera.pipeline

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.a56camera.camera.YuvFrame
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 7 Kare YUV Burst + AlignMTB + Pixel 9a Style Bright & Vibrant HDR Tonemap.
 */
class FastPhotoPipeline(private val context: Context? = null) {

    private val tag = "FastPhotoPipeline"

    enum class YuvMode { AUTO, NV21, NV12, YV12, I420 }

    fun processBurst(
        frames: List<YuvFrame>,
        outputPath: String,
        orientation: Int = 0,
        onProgress: ((String) -> Unit)? = null
    ): Boolean {
        if (frames.isEmpty()) {
            Log.e(tag, "Kare listesi boş")
            return false
        }

        val ref = frames.minByOrNull { abs(it.evShift) } ?: frames.first()

        return try {
            if (!OpenCVLoader.initDebug()) {
                onProgress?.invoke("OpenCV yok, fallback JPEG")
                return saveSingleFrame(ref, outputPath)
            }

            val sameSize = frames.all { it.width == ref.width && it.height == ref.height }
            if (!sameSize || frames.size < 3) {
                onProgress?.invoke("Yeterli uyumlu kare yok, tek kare")
                val rgb = convertBestYuvMode(ref)
                correctWhiteBalance(rgb, ref)
                val out = processSingle(rgb, ref, orientation)
                rgb.release()
                return saveMatJpeg(out, outputPath).also { out.release() }
            }

            onProgress?.invoke("YUV modu seçiliyor")
            val yuvMode = selectBestYuvMode(ref)
            Log.d(tag, "Seçilen YUV modu: $yuvMode")

            onProgress?.invoke("Fusion başlıyor")
            val t0 = System.currentTimeMillis()

            val fullW = ref.width
            val fullH = ref.height
            val scale = 0.5
            val procW = (fullW * scale).toInt()
            val procH = (fullH * scale).toInt()

            // 1) YUV -> RGB + Donanım AWB Gains & CCM Uygula
            val rgbFrames = frames.map { frame ->
                val mat = convertYuvToMat(frame, yuvMode)
                correctWhiteBalance(mat, frame)
                mat
            }

            // 2) Downscale for fast fusion
            val scaled = rgbFrames.map {
                val s = Mat()
                Imgproc.resize(it, s, Size(procW.toDouble(), procH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                it.release()
                s
            }

            // 3) AlignMTB ile el titremesi hizalama + Mertens fusion
            val aligned = ArrayList<Mat>()
            try {
                val align = Photo.createAlignMTB()
                align.process(scaled, aligned)
            } catch (e: Throwable) {
                Log.w(tag, "AlignMTB hatası, ham karelerle devam ediliyor", e)
                aligned.clear()
                scaled.forEach { aligned.add(it.clone()) }
            }
            scaled.forEach { it.release() }

            val fusion = Mat()
            Photo.createMergeMertens().process(aligned, fusion)
            aligned.forEach { it.release() }

            // 4) Convert to 16-bit working space
            val fused16 = Mat()
            Core.normalize(fusion, fusion, 0.0, 65535.0, Core.NORM_MINMAX)
            fusion.convertTo(fused16, CvType.CV_16UC3)
            fusion.release()

            // 5) Percentile Gray-World AWB
            onProgress?.invoke("AWB dengeleniyor")
            val awb = applyAwb(fused16)
            fused16.release()

            // 6) LAB-space Bright Tonemap (L kanalı bilateral + Shadow Lift + Gamma, renkler canlı korunur)
            onProgress?.invoke("LAB Tonemap")
            val tonemapped = labTonemap(awb)
            awb.release()

            // 7) Upscale back to full resolution
            onProgress?.invoke("Büyütülüyor")
            val full16 = Mat()
            Imgproc.resize(tonemapped, full16, Size(fullW.toDouble(), fullH.toDouble()), 0.0, 0.0, Imgproc.INTER_LANCZOS4)
            tonemapped.release()

            // 8) Richer S-Curve Kontrast
            onProgress?.invoke("S-Curve Kontrast")
            val contrasted = applySCurve(full16)
            full16.release()

            // 9) YCrCb Skin Tone Desaturation + Vibrant Saturation Boost
            onProgress?.invoke("Cilt Tonu & Canlılık Düzeltme")
            val graded = desaturateSkinTone(contrasted)
            contrasted.release()

            // 10) Frequency Separation Sharpening
            onProgress?.invoke("Netleştirme")
            val sharpened = frequencySeparationSharpen(graded)
            graded.release()

            // 11) Final 8-bit + orientation
            val rotated = rotateMatIfNeeded(sharpened, orientation)
            sharpened.release()

            val output8 = Mat()
            rotated.convertTo(output8, CvType.CV_8UC3, 1.0 / 256.0)
            rotated.release()

            val t1 = System.currentTimeMillis()
            Log.d(tag, "Burst kare sayısı: ${frames.size}")
            Log.d(tag, "TOTAL PIPELINE SÜRESİ: ${t1 - t0} ms")

            onProgress?.invoke("Kaydediliyor")
            val ok = saveMatJpeg(output8, outputPath, quality = 100).also { output8.release() }
            onProgress?.invoke("Tamam")
            ok
        } catch (e: Throwable) {
            Log.e(tag, "Pipeline çöktü, emergency fallback", e)
            return runCatching { saveSingleFrame(ref, outputPath) }.getOrDefault(false)
        }
    }

    // ---------------------- YUV -> RGB ----------------------

    private fun selectBestYuvMode(frame: YuvFrame): YuvMode {
        val candidateModes = listOf(YuvMode.NV21, YuvMode.NV12, YuvMode.YV12, YuvMode.I420)
        val scores = candidateModes.associateWith { mode ->
            val rgb = convertYuvToMat(frame, mode)
            val score = colorBalanceScore(rgb)
            rgb.release()
            score
        }
        val best = scores.minByOrNull { it.value }?.key ?: YuvMode.NV21
        Log.d(tag, "YUV mod skorları: $scores")
        return best
    }

    private fun convertBestYuvMode(frame: YuvFrame): Mat = convertYuvToMat(frame, selectBestYuvMode(frame))

    private fun colorBalanceScore(rgb: Mat): Double {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = mutableListOf<Mat>()
        Core.split(lab, ch)
        val meanA = Core.mean(ch[1]).`val`[0] - 128.0
        val meanB = Core.mean(ch[2]).`val`[0] - 128.0
        val stdA = org.opencv.core.MatOfDouble()
        val stdB = org.opencv.core.MatOfDouble()
        Core.meanStdDev(ch[1], org.opencv.core.MatOfDouble(), stdA)
        Core.meanStdDev(ch[2], org.opencv.core.MatOfDouble(), stdB)
        val stdAVal = if (!stdA.empty()) stdA.get(0, 0)[0] else 0.0
        val stdBVal = if (!stdB.empty()) stdB.get(0, 0)[0] else 0.0
        ch.forEach { it.release() }
        lab.release()
        stdA.release()
        stdB.release()
        return abs(meanA) + abs(meanB) + stdAVal * 0.3 + stdBVal * 0.3
    }

    private fun convertYuvToMat(frame: YuvFrame, mode: YuvMode = YuvMode.AUTO): Mat {
        val w = frame.width
        val h = frame.height

        val isInterleaved = frame.uPixelStride == 2 && frame.vPixelStride == 2
        val effectiveMode = when {
            mode != YuvMode.AUTO -> mode
            isInterleaved -> YuvMode.NV21
            else -> YuvMode.YV12
        }

        val nv21 = ByteArray(w * h * 3 / 2)

        // Y plane
        for (row in 0 until h) {
            val srcPos = row * frame.yRowStride
            val len = min(w, max(0, frame.y.size - srcPos))
            if (len > 0 && row * w + len <= nv21.size) {
                System.arraycopy(frame.y, srcPos, nv21, row * w, len)
            }
        }

        val uvH = h / 2
        val uvW = w / 2

        when (effectiveMode) {
            YuvMode.NV21 -> {
                for (row in 0 until uvH) {
                    val srcRowU = row * frame.uRowStride
                    val srcRowV = row * frame.vRowStride
                    val dstRow = w * h + row * w
                    for (col in 0 until uvW) {
                        val uIdx = srcRowU + col * frame.uPixelStride
                        val vIdx = srcRowV + col * frame.vPixelStride
                        if (dstRow + col * 2 + 1 < nv21.size) {
                            nv21[dstRow + col * 2] = frame.v.getOrElse(vIdx) { 0 }
                            nv21[dstRow + col * 2 + 1] = frame.u.getOrElse(uIdx) { 0 }
                        }
                    }
                }
            }
            YuvMode.NV12 -> {
                for (row in 0 until uvH) {
                    val srcRowU = row * frame.uRowStride
                    val srcRowV = row * frame.vRowStride
                    val dstRow = w * h + row * w
                    for (col in 0 until uvW) {
                        val uIdx = srcRowU + col * frame.uPixelStride
                        val vIdx = srcRowV + col * frame.vPixelStride
                        if (dstRow + col * 2 + 1 < nv21.size) {
                            nv21[dstRow + col * 2] = frame.u.getOrElse(uIdx) { 0 }
                            nv21[dstRow + col * 2 + 1] = frame.v.getOrElse(vIdx) { 0 }
                        }
                    }
                }
            }
            YuvMode.YV12 -> {
                val uvSize = (w * h) / 4
                val vStart = w * h
                val uStart = w * h + uvSize
                for (row in 0 until uvH) {
                    val srcV = row * frame.vRowStride
                    val srcU = row * frame.uRowStride
                    val dstV = vStart + row * (w / 2)
                    val dstU = uStart + row * (w / 2)
                    for (col in 0 until uvW) {
                        val vIdx = srcV + col * frame.vPixelStride
                        val uIdx = srcU + col * frame.uPixelStride
                        if (dstV + col < nv21.size) nv21[dstV + col] = frame.v.getOrElse(vIdx) { 0 }
                        if (dstU + col < nv21.size) nv21[dstU + col] = frame.u.getOrElse(uIdx) { 0 }
                    }
                }
            }
            YuvMode.I420 -> {
                val uvSize = (w * h) / 4
                val uStart = w * h
                val vStart = w * h + uvSize
                for (row in 0 until uvH) {
                    val srcU = row * frame.uRowStride
                    val srcV = row * frame.vRowStride
                    val dstU = uStart + row * (w / 2)
                    val dstV = vStart + row * (w / 2)
                    for (col in 0 until uvW) {
                        val uIdx = srcU + col * frame.uPixelStride
                        val vIdx = srcV + col * frame.vPixelStride
                        if (dstU + col < nv21.size) nv21[dstU + col] = frame.u.getOrElse(uIdx) { 0 }
                        if (dstV + col < nv21.size) nv21[dstV + col] = frame.v.getOrElse(vIdx) { 0 }
                    }
                }
            }
            else -> {}
        }

        val yuvMat = Mat(h + h / 2, w, CvType.CV_8UC1)
        yuvMat.put(0, 0, nv21)

        val rgb = Mat()
        val code = when (effectiveMode) {
            YuvMode.NV21 -> Imgproc.COLOR_YUV2RGB_NV21
            YuvMode.NV12 -> Imgproc.COLOR_YUV2RGB_NV12
            YuvMode.YV12 -> Imgproc.COLOR_YUV2RGB_YV12
            YuvMode.I420 -> Imgproc.COLOR_YUV2RGB_I420
            else -> Imgproc.COLOR_YUV2RGB_NV21
        }
        Imgproc.cvtColor(yuvMat, rgb, code)
        yuvMat.release()
        return rgb
    }

    private fun correctWhiteBalance(rgb: Mat, frame: YuvFrame) {
        frame.colorCorrectionGains?.let { g ->
            if (g.size >= 3) {
                val channels = ArrayList<Mat>()
                Core.split(rgb, channels)
                Core.multiply(channels[0], Scalar.all(g[0].toDouble()), channels[0]) // R
                Core.multiply(channels[1], Scalar.all(g[1].toDouble()), channels[1]) // G
                Core.multiply(channels[2], Scalar.all(g[2].toDouble()), channels[2]) // B
                Core.merge(channels, rgb)
                channels.forEach { it.release() }
            }
        }

        frame.colorCorrectionTransform?.let { m ->
            if (m.size >= 9) {
                val cm = Mat(3, 3, CvType.CV_32F).apply {
                    for (i in 0..2) {
                        for (j in 0..2) {
                            put(i, j, m[i * 3 + j].toDouble())
                        }
                    }
                }
                val floatRgb = Mat()
                rgb.convertTo(floatRgb, CvType.CV_32F, 1.0 / 255.0)
                Core.transform(floatRgb, floatRgb, cm)
                Core.multiply(floatRgb, Scalar.all(255.0), floatRgb)
                floatRgb.convertTo(rgb, CvType.CV_8U)
                cm.release()
                floatRgb.release()
            }
        }
    }

    // ---------------------- Image Processing ----------------------

    private fun applyAwb(src: Mat): Mat {
        val f = Mat()
        src.convertTo(f, CvType.CV_32FC3, 1.0 / 65535.0)

        val rgbCh = mutableListOf<Mat>()
        Core.split(f, rgbCh)

        val gains = rgbCh.map { ch ->
            val sorted = Mat()
            Core.sort(ch.reshape(1, ch.rows() * ch.cols()), sorted, Core.SORT_ASCENDING + Core.SORT_EVERY_COLUMN)
            val flat = sorted.reshape(1, sorted.total().toInt())
            val total = flat.total().toInt()
            val lo = (total * 0.05).toInt()
            val hi = (total * 0.95).toInt()
            val values = FloatArray(total)
            flat.get(0, 0, values)
            sorted.release()
            flat.release()
            var sum = 0.0
            var count = 0
            for (i in lo..hi) {
                if (i in values.indices) {
                    sum += values[i]
                    count++
                }
            }
            if (count > 0) sum / count else 0.5
        }

        val avg = gains.average()
        val gR = avg / max(gains[0], 1e-6)
        val gB = avg / max(gains[2], 1e-6)

        Core.multiply(rgbCh[0], Scalar(gR), rgbCh[0])
        Core.multiply(rgbCh[2], Scalar(gB), rgbCh[2])

        val awbFloat = Mat()
        Core.merge(rgbCh, awbFloat)
        rgbCh.forEach { it.release() }

        val out = Mat()
        Core.normalize(awbFloat, awbFloat, 0.0, 1.0, Core.NORM_MINMAX)
        awbFloat.convertTo(out, CvType.CV_16UC3, 65535.0)
        awbFloat.release()
        f.release()
        return out
    }

    private fun labTonemap(src: Mat): Mat {
        val src8 = Mat()
        src.convertTo(src8, CvType.CV_8UC3, 1.0 / 256.0)

        val lab = Mat()
        Imgproc.cvtColor(src8, lab, Imgproc.COLOR_RGB2Lab)
        src8.release()

        val ch = mutableListOf<Mat>()
        Core.split(lab, ch)
        val l = ch[0]
        val a = ch[1]
        val b = ch[2]

        val lf = Mat()
        l.convertTo(lf, CvType.CV_32F, 1.0 / 255.0)

        val logL = Mat()
        Core.add(lf, Scalar(0.001), logL)
        Core.log(logL, logL)

        val blurL = Mat()
        Imgproc.bilateralFilter(logL, blurL, 9, 0.08, 75.0)

        val detail = Mat()
        Core.subtract(logL, blurL, detail)
        val outLog = Mat()
        Core.addWeighted(blurL, 0.85, detail, 0.9, 0.0, outLog)

        val outL = Mat()
        Core.exp(outLog, outL)
        Core.subtract(outL, Scalar(0.001), outL)

        // Normalize + Shadow Lift (+0.08) + Exposure Gain (1.20x)
        Core.normalize(outL, outL, 0.0, 1.0, Core.NORM_MINMAX)
        Core.add(outL, Scalar(0.08), outL)      // Gölgeleri daha çok aç
        Core.multiply(outL, Scalar(1.20), outL) // Genel pozlama artışı
        Core.min(outL, Scalar(1.0), outL)

        // Gamma < 1 aydınlatır (0.82)
        val gamma = Mat()
        Core.pow(outL, 0.82, gamma)

        val l16 = Mat()
        gamma.convertTo(l16, CvType.CV_16UC1, 65535.0)

        val l8 = Mat()
        l16.convertTo(l8, CvType.CV_8UC1, 1.0 / 256.0)
        l16.release()

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(l8, lEnhanced)
        l8.release()
        clahe.clear()

        val mergedLab = Mat()
        Core.merge(listOf(lEnhanced, a, b), mergedLab)
        lEnhanced.release()
        a.release()
        b.release()

        val rgb8 = Mat()
        Imgproc.cvtColor(mergedLab, rgb8, Imgproc.COLOR_Lab2RGB)
        mergedLab.release()

        val out = Mat()
        rgb8.convertTo(out, CvType.CV_16UC3, 256.0)
        rgb8.release()

        lf.release()
        logL.release()
        blurL.release()
        detail.release()
        outLog.release()
        outL.release()
        gamma.release()
        return out
    }

    private fun desaturateSkinTone(src: Mat): Mat {
        val src8 = Mat()
        src.convertTo(src8, CvType.CV_8UC3, 1.0 / 256.0)

        val ycrcb = Mat()
        Imgproc.cvtColor(src8, ycrcb, Imgproc.COLOR_RGB2YCrCb)
        src8.release()

        val ch = mutableListOf<Mat>()
        Core.split(ycrcb, ch)
        val y = ch[0]
        val cr = ch[1]
        val cb = ch[2]

        val skinMask = Mat()
        val m1 = Mat()
        val m2 = Mat()
        val m3 = Mat()
        val m4 = Mat()
        Imgproc.threshold(cr, m1, 140.0, 1.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(cr, m2, 180.0, 1.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.threshold(cb, m3, 80.0, 1.0, Imgproc.THRESH_BINARY)
        Imgproc.threshold(cb, m4, 135.0, 1.0, Imgproc.THRESH_BINARY_INV)

        Core.multiply(m1, m2, skinMask)
        Core.multiply(skinMask, m3, skinMask)
        Core.multiply(skinMask, m4, skinMask)

        val skinCr = Mat()
        Core.multiply(cr, skinMask, skinCr)
        Core.multiply(skinCr, Scalar(0.92), skinCr)
        Core.multiply(cr, Scalar(1.0), cr)
        Core.add(cr, skinCr, cr)

        val outYCrCb = Mat()
        Core.merge(listOf(y, cr, cb), outYCrCb)
        val outRgb8 = Mat()
        Imgproc.cvtColor(outYCrCb, outRgb8, Imgproc.COLOR_YCrCb2RGB)

        // HSV Saturation Boost (+18% Canlılık)
        val hsv = Mat()
        Imgproc.cvtColor(outRgb8, hsv, Imgproc.COLOR_RGB2HSV)
        outRgb8.release()

        val hsvCh = mutableListOf<Mat>()
        Core.split(hsv, hsvCh)
        Core.multiply(hsvCh[1], Scalar(1.18), hsvCh[1]) // +18% Saturation Boost
        Core.merge(hsvCh, hsv)

        val saturatedRgb8 = Mat()
        Imgproc.cvtColor(hsv, saturatedRgb8, Imgproc.COLOR_HSV2RGB)

        val out = Mat()
        saturatedRgb8.convertTo(out, CvType.CV_16UC3, 256.0)

        ycrcb.release()
        skinMask.release()
        skinCr.release()
        m1.release()
        m2.release()
        m3.release()
        m4.release()
        ch.forEach { it.release() }
        outYCrCb.release()
        hsv.release()
        hsvCh.forEach { it.release() }
        saturatedRgb8.release()

        return out
    }

    private fun applySCurve(src: Mat): Mat {
        val f = Mat()
        src.convertTo(f, CvType.CV_32FC3, 1.0 / 65535.0)

        val x = Mat()
        Core.normalize(f, x, -1.0, 1.0, Core.NORM_MINMAX)

        val x3 = Mat()
        Core.pow(x, 3.0, x3)

        val y = Mat()
        Core.multiply(x, Scalar(2.0), y)       // Daha güçlü S-curve (2.0x)
        val term = Mat()
        Core.multiply(x3, Scalar(0.75), term)   // (0.75x³)
        Core.subtract(y, term, y)

        Core.normalize(y, y, 0.0, 1.0, Core.NORM_MINMAX)

        val out = Mat()
        y.convertTo(out, CvType.CV_16UC3, 65535.0)
        f.release()
        x.release()
        x3.release()
        term.release()
        y.release()
        return out
    }

    private fun frequencySeparationSharpen(src: Mat): Mat {
        val base = Mat()
        Imgproc.bilateralFilter(src, base, 9, 75.0, 75.0)

        val detail = Mat()
        Core.subtract(src, base, detail)

        val sharpenedDetail = Mat()
        Core.multiply(detail, Scalar(1.35), sharpenedDetail)

        val out = Mat()
        Core.add(base, sharpenedDetail, out)
        base.release()
        detail.release()
        sharpenedDetail.release()
        return out
    }

    private fun processSingle(rgb: Mat, frame: YuvFrame, orientation: Int): Mat {
        val rotated = rotateMatIfNeeded(rgb, orientation)
        rgb.release()
        val awb = applyAwb(rotated)
        rotated.release()
        val tonemapped = labTonemap(awb)
        awb.release()
        val contrasted = applySCurve(tonemapped)
        tonemapped.release()
        val graded = desaturateSkinTone(contrasted)
        contrasted.release()
        val sharpened = frequencySeparationSharpen(graded)
        graded.release()
        val out = Mat()
        sharpened.convertTo(out, CvType.CV_8UC3, 1.0 / 256.0)
        sharpened.release()
        return out
    }

    private fun rotateMatIfNeeded(rgb: Mat, orientation: Int): Mat {
        if (orientation == 0) return rgb
        val rotated = Mat()
        when (orientation) {
            90 -> Core.rotate(rgb, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(rgb, rotated, Core.ROTATE_180)
            270 -> Core.rotate(rgb, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> rgb.copyTo(rotated)
        }
        return rotated
    }

    // ---------------------- Saving ----------------------

    private fun saveSingleFrame(frame: YuvFrame, outputPath: String): Boolean {
        return try {
            val nv21 = yuvFrameToNv21ByteArray(frame)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, frame.width, frame.height, null)
            FileOutputStream(outputPath).use { out ->
                yuvImage.compressToJpeg(Rect(0, 0, frame.width, frame.height), 95, out)
            }
            true
        } catch (e: Throwable) {
            Log.e(tag, "Fallback hatası", e)
            false
        }
    }

    private fun saveMatJpeg(mat: Mat, outputPath: String, quality: Int = 100): Boolean {
        return try {
            val bgr = Mat()
            Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGB2BGR)
            val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)
            Imgcodecs.imwrite(outputPath, bgr, params)
            bgr.release()
            true
        } catch (e: Throwable) {
            Log.e(tag, "JPEG kayıt hatası", e)
            false
        }
    }

    private fun yuvFrameToNv21ByteArray(frame: YuvFrame): ByteArray {
        val w = frame.width
        val h = frame.height
        val nv21 = ByteArray(w * h * 3 / 2)
        for (row in 0 until h) {
            val srcPos = row * frame.yRowStride
            val len = min(w, max(0, frame.y.size - srcPos))
            if (len > 0) System.arraycopy(frame.y, srcPos, nv21, row * w, len)
        }
        val uvH = h / 2
        val uvW = w / 2
        for (row in 0 until uvH) {
            for (col in 0 until uvW) {
                val srcIdx = row * frame.vRowStride + col * frame.vPixelStride
                val dst = w * h + row * w + col * 2
                if (dst + 1 < nv21.size) {
                    nv21[dst] = frame.v.getOrElse(srcIdx) { 0 }
                    nv21[dst + 1] = frame.u.getOrElse(srcIdx) { 0 }
                }
            }
        }
        return nv21
    }
}

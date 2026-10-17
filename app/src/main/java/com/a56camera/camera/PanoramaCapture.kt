package com.a56camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import com.a56camera.utils.MediaStoreHelper
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Panorama — jiroskop rehberli çekim ve OpenCV ile birleştirme.
 *
 * Kullanıcı telefonu yatay çevirirken her ~[captureStepDegrees] derecede önizlemeden bir kare
 * alınır (galeriye KAYDEDİLMEZ, bellekte tutulur). Durdurunca kareler silindirik izdüşüme
 * çevrilir, ardışık kareler arasındaki kayma faz korelasyonuyla bulunur ve örtüşen bölgeler
 * yumuşak geçişle harmanlanır.
 *
 * Eskiden her açı adımında galeriye ayrı bir fotoğraf kaydediliyor, birleştirme listesine hiç
 * kare eklenmediği için sonunda "hata" veriliyordu.
 */
class PanoramaCapture(private val context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val frames = mutableListOf<Mat>()
    private val frameAngles = mutableListOf<Float>()
    private var accumulatedAngle = 0f
    private var lastTimestamp = 0L
    @Volatile private var capturing = false

    /** Yeni bir kare alınması gerektiğinde (ana iş parçacığında değil, sensör iş parçacığında) çağrılır. */
    var onCaptureFrameNeeded: (() -> Unit)? = null
    var onAngleUpdate: ((Float) -> Unit)? = null

    /** Dikey tutulan telefonda önizlemenin yatay görüş açısı (radyan). */
    var horizontalFovRad: Double = Math.toRadians(66.0)

    private val captureStepDegrees = 15f
    private val maxFrames = 26
    private var nextCaptureAngle = 0f

    /** Jiroskop yoksa false döner. */
    fun start(): Boolean {
        if (gyroscope == null) { Log.w(TAG, "Jiroskop yok"); return false }
        clearFrames()
        accumulatedAngle = 0f
        nextCaptureAngle = captureStepDegrees
        lastTimestamp = 0L
        capturing = true
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        onCaptureFrameNeeded?.invoke()
        return true
    }

    fun stop() {
        capturing = false
        sensorManager.unregisterListener(this)
    }

    fun isCapturing(): Boolean = capturing

    /**
     * Önizlemeden alınmış dik (portre) kareyi ekler. Bellek için yüksekliği en fazla [WORK_HEIGHT] olacak şekilde küçültülür.
     */
    @Synchronized
    fun addFrame(bitmap: Bitmap) {
        if (frames.size >= maxFrames) return
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val scale = WORK_HEIGHT.toDouble() / bgr.rows()
        val small = if (scale < 1.0) {
            Mat().also { Imgproc.resize(bgr, it, Size(), scale, scale, Imgproc.INTER_AREA); bgr.release() }
        } else bgr
        frames.add(small)
        frameAngles.add(accumulatedAngle)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!capturing || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (lastTimestamp == 0L) { lastTimestamp = event.timestamp; return }
        val dt = (event.timestamp - lastTimestamp) * 1e-9f
        lastTimestamp = event.timestamp
        // Dikey tutulan telefonda yatay çevirme = cihazın Y ekseni etrafında dönüş
        val deltaDeg = Math.toDegrees((event.values[1] * dt).toDouble()).toFloat()
        accumulatedAngle += abs(deltaDeg)
        onAngleUpdate?.invoke(accumulatedAngle)
        if (accumulatedAngle >= nextCaptureAngle && frameCount() < maxFrames) {
            onCaptureFrameNeeded?.invoke()
            nextCaptureAngle += captureStepDegrees
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @Synchronized
    fun frameCount(): Int = frames.size

    /**
     * Kareleri birleştirip galeriye kaydeder. Arka plan iş parçacığında çağrılmalı.
     * @return kaydedilen panoramanın Uri'si; en az 2 kare yoksa veya hata olursa null
     */
    fun stitchAndSave(): Uri? {
        val list = synchronized(this) { frames.toList() }
        if (list.size < 2) { clearFrames(); return null }
        return try {
            val pano = stitch(list, horizontalFovRad)
            val uri = save(pano)
            pano.release()
            uri
        } catch (e: Throwable) {
            Log.e(TAG, "Panorama birleştirme hatası", e)
            null
        } finally {
            clearFrames()
        }
    }

    @Synchronized
    private fun clearFrames() {
        frames.forEach { it.release() }
        frames.clear()
        frameAngles.clear()
    }

    private fun save(bgr: Mat): Uri? {
        val name = MediaStoreHelper.timestampName("PANO")
        val uri = MediaStoreHelper.createImageUri(context, "$name.jpg") ?: return null
        return try {
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", bgr, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95))
            MediaStoreHelper.openOutput(context, uri)?.use { it.write(buf.toArray()) }
            buf.release()
            try {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    com.a56camera.utils.ExifHelper.writeCaptureMetadata(pfd.fileDescriptor, null, null, 0)
                }
            } catch (_: Exception) {}
            MediaStoreHelper.finalizePending(context, uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Panorama kaydetme hatası", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    companion object {
        private const val TAG = "PanoramaCapture"
        private const val WORK_HEIGHT = 1200

        /**
         * Saf OpenCV birleştirme (test edilebilir olması için Android'den bağımsız).
         * @param frames aynı boyutta, dik BGR kareler, çekim sırasıyla
         * @param hfovRad karelerin yatay görüş açısı
         */
        fun stitch(frames: List<Mat>, hfovRad: Double): Mat {
            val w = frames[0].cols()
            val h = frames[0].rows()
            val f = (w / 2.0) / tan(hfovRad / 2.0)

            // 1) Silindirik izdüşüm haritası (tüm kareler için aynı)
            val mapX = Mat(h, w, CvType.CV_32FC1)
            val mapY = Mat(h, w, CvType.CV_32FC1)
            val cx = w / 2.0; val cy = h / 2.0
            val rowX = FloatArray(w); val rowY = FloatArray(w)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val theta = (x - cx) / f
                    val hh = (y - cy) / f
                    val X = sin(theta); val Y = hh; val Z = cos(theta)
                    rowX[x] = (f * X / Z + cx).toFloat()
                    rowY[x] = (f * Y / Z + cy).toFloat()
                }
                mapX.put(y, 0, rowX); mapY.put(y, 0, rowY)
            }
            val ones = Mat(h, w, CvType.CV_8UC1, Scalar(255.0))
            val validMask = Mat()
            Imgproc.remap(ones, validMask, mapX, mapY, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
            ones.release()

            val warped = frames.map { fr ->
                Mat().also { Imgproc.remap(fr, it, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0)) }
            }
            mapX.release(); mapY.release()

            // 2) Ardışık kareler arası kayma (faz korelasyonu)
            val gray = warped.map { m ->
                val g = Mat(); Imgproc.cvtColor(m, g, Imgproc.COLOR_BGR2GRAY)
                val gf = Mat(); g.convertTo(gf, CvType.CV_32F); g.release(); gf
            }
            val window = Mat()
            Imgproc.createHanningWindow(window, Size(w.toDouble(), h.toDouble()), CvType.CV_32F)
            val offsets = mutableListOf(Point(0.0, 0.0))
            var typicalDx = 0.0
            for (i in 1 until gray.size) {
                val shift = Imgproc.phaseCorrelate(gray[i - 1], gray[i], window)
                // phaseCorrelate: i. kare (i-1)'e göre (shift.x, shift.y) kaymış → konumu bunun tersi yönde
                var dx = -shift.x
                var dy = -shift.y
                val plausible = abs(dx) > w * 0.05 && abs(dx) < w * 0.9 && abs(dy) < h * 0.15
                if (!plausible) {
                    // Güvenilmez eşleşme: önceki adımın kaymasını (ya da varsayılan %25'i) kullan
                    dx = if (typicalDx != 0.0) typicalDx else w * 0.25
                    dy = 0.0
                } else {
                    typicalDx = dx
                }
                val prev = offsets.last()
                offsets.add(Point(prev.x + dx, prev.y + dy))
            }
            gray.forEach { it.release() }
            window.release()

            // 3) Tuval ve yumuşak geçişli harmanlama
            val minX = offsets.minOf { it.x }; val maxX = offsets.maxOf { it.x }
            val minY = offsets.minOf { it.y }; val maxY = offsets.maxOf { it.y }
            val canvasW = (maxX - minX).toInt() + w
            val canvasH = (maxY - minY).toInt() + h
            val acc = Mat.zeros(canvasH, canvasW, CvType.CV_32FC3)
            val wsum = Mat.zeros(canvasH, canvasW, CvType.CV_32FC1)

            // Kenarlara doğru azalan ağırlık (örtüşmede dikiş izi kalmasın)
            val ramp = Mat(h, w, CvType.CV_32FC1)
            val rampRow = FloatArray(w) { x -> (minOf(x + 1, w - x).toFloat() / (w / 2f)).coerceIn(0.02f, 1f) }
            for (y in 0 until h) ramp.put(y, 0, rampRow)
            val maskF = Mat(); validMask.convertTo(maskF, CvType.CV_32FC1, 1.0 / 255.0)
            Core.multiply(ramp, maskF, ramp)
            maskF.release()
            val ramp3 = Mat(); Imgproc.cvtColor(ramp, ramp3, Imgproc.COLOR_GRAY2BGR)

            val coverage = Mat.zeros(canvasH, canvasW, CvType.CV_8UC1)
            for ((i, m) in warped.withIndex()) {
                val ox = (offsets[i].x - minX).toInt()
                val oy = (offsets[i].y - minY).toInt()
                val roi = org.opencv.core.Rect(ox, oy, w, h)
                val mf = Mat(); m.convertTo(mf, CvType.CV_32FC3)
                Core.multiply(mf, ramp3, mf)
                val accRoi = acc.submat(roi); Core.add(accRoi, mf, accRoi)
                val wRoi = wsum.submat(roi); Core.add(wRoi, ramp, wRoi)
                val cRoi = coverage.submat(roi); Core.bitwise_or(cRoi, validMask, cRoi)
                mf.release()
            }
            warped.forEach { it.release() }
            ramp.release(); ramp3.release(); validMask.release()

            Core.max(wsum, Scalar(1e-6), wsum)
            val w3 = Mat(); Imgproc.cvtColor(wsum, w3, Imgproc.COLOR_GRAY2BGR)
            Core.divide(acc, w3, acc)
            w3.release(); wsum.release()
            val out8 = Mat(); acc.convertTo(out8, CvType.CV_8UC3)
            acc.release()

            // 4) Siyah kenarları kırp. Satırlar, panoramanın iç kısmında (uç karelerin bombeli
            //    kenarları hariç) tamamen dolu olanlardan; sütunlar da bu satırlarda dolu olanlardan seçilir.
            var result: Mat = out8
            val cl = minOf(w / 3, canvasW / 4)
            val cr = maxOf(canvasW - w / 3, cl + 1)
            val rowCov = Mat()
            Core.reduce(coverage.colRange(cl, cr), rowCov, 1, Core.REDUCE_MIN)
            var top = 0; var bottom = canvasH - 1
            while (top < canvasH && rowCov.get(top, 0)[0] == 0.0) top++
            while (bottom > top && rowCov.get(bottom, 0)[0] == 0.0) bottom--
            rowCov.release()
            if (bottom - top > h / 3) {
                val colCov = Mat()
                Core.reduce(coverage.rowRange(top, bottom + 1), colCov, 0, Core.REDUCE_MIN)
                var left = 0; var right = canvasW - 1
                while (left < canvasW && colCov.get(0, left)[0] == 0.0) left++
                while (right > left && colCov.get(0, right)[0] == 0.0) right--
                colCov.release()
                if (right - left > w / 2) {
                    result = out8.submat(top, bottom + 1, left, right + 1).clone()
                    out8.release()
                }
            }
            coverage.release()
            return result
        }

        /** Dikey tutulan telefonda yatay görüş açısı: sensörün kısa kenarı ve odak uzaklığından. */
        fun horizontalFovPortrait(focalMm: Float, sensorShortSideMm: Float, zoomRatio: Float): Double {
            if (focalMm <= 0f || sensorShortSideMm <= 0f) return Math.toRadians(66.0)
            val half = atan(sensorShortSideMm / (2.0 * focalMm))
            return 2.0 * atan(tan(half) / zoomRatio.coerceAtLeast(0.5f))
        }
    }
}

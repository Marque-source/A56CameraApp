package com.a56camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import com.a56camera.utils.MediaStoreHelper
import kotlin.math.abs

/**
 * Panorama modu — jiroskop rehberli ardışık çekim ve yatay birleştirme.
 *
 * Kullanıcı cihazı yatay çevirirken belirli açı aralıklarında (ör. her ~15°)
 * otomatik kare yakalanır. Kareler yatayda kesişimli olarak birleştirilerek
 * (basit yatay ekleme + yumuşak geçiş) tek bir geniş görüntü üretilir.
 */
class PanoramaCapture(private val context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val frames = mutableListOf<Bitmap>()
    private var accumulatedAngle = 0f
    private var lastTimestamp = 0L
    private var capturing = false

    /** Her ~[captureStepDegrees] derecede bir kare yakalanması gerektiğini bildirir. */
    var onCaptureFrameNeeded: (() -> Unit)? = null
    var onAngleUpdate: ((Float) -> Unit)? = null

    private val captureStepDegrees = 15f
    private var nextCaptureAngle = 0f

    fun start() {
        if (gyroscope == null) { Log.w(TAG, "Jiroskop yok"); return }
        frames.clear()
        accumulatedAngle = 0f
        nextCaptureAngle = 0f
        lastTimestamp = 0L
        capturing = true
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        // İlk kareyi hemen iste
        onCaptureFrameNeeded?.invoke()
        nextCaptureAngle += captureStepDegrees
    }

    fun stop() {
        capturing = false
        sensorManager.unregisterListener(this)
    }

    /** Yakalanan JPEG byte dizisini kare listesine ekler. */
    fun addFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        frames.add(bmp)
    }

    fun addFrame(bitmap: Bitmap) { frames.add(bitmap) }

    override fun onSensorChanged(event: SensorEvent) {
        if (!capturing || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (lastTimestamp == 0L) { lastTimestamp = event.timestamp; return }
        val dt = (event.timestamp - lastTimestamp) * 1e-9f
        lastTimestamp = event.timestamp
        // Y ekseni etrafındaki dönüş (yatay pan) — rad/s → derece
        val deltaDeg = Math.toDegrees((event.values[1] * dt).toDouble()).toFloat()
        accumulatedAngle += abs(deltaDeg)
        onAngleUpdate?.invoke(accumulatedAngle)
        if (accumulatedAngle >= nextCaptureAngle) {
            onCaptureFrameNeeded?.invoke()
            nextCaptureAngle += captureStepDegrees
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Toplanan kareleri yatayda birleştirir. Kareler arasında sabit bir kesişim
     * (overlap) payı bırakılarak basit doğrusal harmanlama uygulanır.
     * @return kaydedilen panorama görüntüsünün Uri'si veya null
     */
    fun stitchAndSave(overlapFraction: Float = 0.3f): Uri? {
        if (frames.isEmpty()) return null
        if (frames.size == 1) return save(frames.first())

        val h = frames.minOf { it.height }
        val scaled = frames.map {
            if (it.height == h) it
            else Bitmap.createScaledBitmap(it, (it.width * h / it.height), h, true)
        }
        val overlapPx = (scaled.first().width * overlapFraction).toInt()
        var totalWidth = scaled.first().width
        for (i in 1 until scaled.size) totalWidth += scaled[i].width - overlapPx

        val result = Bitmap.createBitmap(totalWidth, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var x = 0
        for ((index, bmp) in scaled.withIndex()) {
            if (index == 0) {
                canvas.drawBitmap(bmp, 0f, 0f, paint)
                x = bmp.width - overlapPx
            } else {
                canvas.drawBitmap(bmp, x.toFloat(), 0f, paint)
                x += bmp.width - overlapPx
            }
        }
        return save(result)
    }

    private fun save(bitmap: Bitmap): Uri? {
        val name = MediaStoreHelper.timestampName("PANO")
        val uri = MediaStoreHelper.createImageUri(context, "$name.jpg") ?: return null
        return try {
            MediaStoreHelper.openOutput(context, uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
            }
            MediaStoreHelper.finalizePending(context, uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Panorama kaydetme hatası", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    fun frameCount(): Int = frames.size

    companion object { private const val TAG = "PanoramaCapture" }
}

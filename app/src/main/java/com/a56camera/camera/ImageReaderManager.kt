package com.a56camera.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Size
import android.view.Surface
import com.a56camera.utils.AspectRatio
import com.a56camera.utils.Camera2Utils

/**
 * Tüm ImageReader/format akışlarını yönetir.
 *
 * - JPEG: son fotoğraf
 * - RAW_SENSOR: DNG çekimi (eş zamanlı RAW+JPEG ve burst için)
 * - YUV_420_888: histogram / işleme / reprocessing kaynağı
 */
class ImageReaderManager(private val bgHandler: Handler) {

    var jpegReader: ImageReader? = null
        private set
    var rawReader: ImageReader? = null
        private set
    var yuvReader: ImageReader? = null
        private set

    private var rawListener: ((Image) -> Unit)? = null

    /** Belirli bir boyut için JPEG reader oluşturur (Extension oturumları için). */
    fun setupJpegWithSize(size: Size, onImage: (Image) -> Unit) {
        jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireNextImage()?.let { onImage(it) }
            }, bgHandler)
        }
    }

    /** JPEG reader'ı verilen en-boy oranındaki ve çözünürlük modundaki en büyük boyutta oluşturur. */
    fun setupJpeg(
        ch: CameraCharacteristics,
        aspect: AspectRatio = AspectRatio.RATIO_4_3,
        highResMode: Boolean = false,
        onImage: (Image) -> Unit
    ) {
        val size = Camera2Utils.largestOutputSizeWithAspect(ch, ImageFormat.JPEG, aspect, highResMode) ?: Size(4000, 3000)
        jpegReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireNextImage()?.let { onImage(it) }
            }, bgHandler)
        }
    }

    /**
     * RAW_SENSOR reader'ı kurar. Tek kare RAW+JPEG için birkaç tampon yeterli; kareler DNG yazılınca kapatılır.
     */
    fun setupRaw(ch: CameraCharacteristics, onImage: (Image) -> Unit): Boolean {
        closeRaw()
        return try {
            // DngCreator yalnızca RAW_SENSOR kabul eder; RAW10/RAW12 DNG'ye yazılamaz
            val rawFormat = ImageFormat.RAW_SENSOR
            val size = Camera2Utils.largestOutputSize(ch, rawFormat)
                ?: throw IllegalStateException("RAW_SENSOR çıktı boyutu bulunamadı")

            rawListener = onImage
            rawReader = ImageReader.newInstance(size.width, size.height, rawFormat, 4).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireNextImage()?.let { img ->
                        rawListener?.invoke(img) ?: img.close()
                    }
                }, bgHandler)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("ImageReaderManager", "RAW reader kurulamadı", e)
            closeRaw()
            false
        }
    }

    /** RAW ImageAvailable dinleyicisini dinamik günceller. */
    fun setRawImageAvailableListener(listener: (Image) -> Unit) {
        rawListener = listener
        rawReader?.setOnImageAvailableListener({ reader ->
            reader.acquireNextImage()?.let { img ->
                rawListener?.invoke(img) ?: img.close()
            }
        }, bgHandler)
    }

    /** YUV reader'ı — histogram ve işleme için düşük çözünürlük yeterli. */
    fun setupYuv(ch: CameraCharacteristics, targetSize: Size, onImage: (Image) -> Unit) {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val size = map?.getOutputSizes(ImageFormat.YUV_420_888)
            ?.minByOrNull { kotlin.math.abs(it.width - targetSize.width) + kotlin.math.abs(it.height - targetSize.height) }
            ?: targetSize
        yuvReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.let { onImage(it) }
            }, bgHandler)
        }
    }

    /** Aktif reader yüzeyleri (session yapılandırması için). */
    fun surfaces(includeYuv: Boolean = true): List<Surface> = buildList {
        jpegReader?.let { add(it.surface) }
        rawReader?.let { add(it.surface) }
        if (includeYuv) yuvReader?.let { add(it.surface) }
    }

    fun jpegSurface(): Surface? = jpegReader?.surface
    fun rawSurface(): Surface? = rawReader?.surface
    fun yuvSurface(): Surface? = yuvReader?.surface

    fun closeRaw() {
        rawListener = null
        try { rawReader?.close() } catch (_: Throwable) {}
        rawReader = null
    }

    fun closeAll() {
        closeRaw()
        try { jpegReader?.close() } catch (_: Throwable) {}; jpegReader = null
        try { yuvReader?.close() } catch (_: Throwable) {}; yuvReader = null
    }
}

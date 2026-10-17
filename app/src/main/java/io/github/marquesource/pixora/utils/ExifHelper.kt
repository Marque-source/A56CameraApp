package io.github.marquesource.pixora.utils

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import androidx.exifinterface.media.ExifInterface
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CaptureResult üst verisini ve GPS konumunu JPEG dosyasının EXIF alanlarına yazar.
 */
object ExifHelper {

    /**
     * Çekim sonucundan ISO, poz süresi, odak uzaklığı ve diyafram bilgilerini EXIF'e yazar.
     */
    fun writeCaptureMetadata(
        fd: FileDescriptor,
        result: TotalCaptureResult?,
        location: Location?,
        orientationDegrees: Int = 0
    ) {
        try {
            val exif = ExifInterface(fd)

            val exifOrientation = when (orientationDegrees) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())

            result?.let { r ->
                r.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                    exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
                }
                r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { ns ->
                    // Rasyonel olarak yaz (ondalık dize hızlı enstantanelerde hassasiyet kaybediyordu)
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureRational(ns))
                }
                r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000")
                }
                r.get(CaptureResult.LENS_APERTURE)?.let {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
                }
            }

            // Çekim tarihi ve cihaz bilgisi: uygulama fotoğrafı yeniden kodladığında ISP'nin yazdığı
            // EXIF kayboluyor, galeriler "çekim tarihi" ve "kamera modeli"ni gösteremiyordu.
            val now = Date()
            val dt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(now)
            val offset = SimpleDateFormat("XXX", Locale.US).format(now)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dt)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dt)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dt)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
            exif.setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
            exif.setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Pixora")

            // sRGB Renk Uzayı Mührü (1 = sRGB):
            // Samsung AMOLED panelin renkleri kontrolsüzce Display P3'e gererek
            // turuncuları nükleer renge çevirmesini ve gölgeleri ezmesini engeller.
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")

            location?.let { exif.setGpsInfo(it) }
            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Pozlama süresini EXIF rasyoneline çevirir: 1 sn altı "1/N", üstü "N/1000". */
    fun exposureRational(ns: Long): String {
        val seconds = ns / 1_000_000_000.0
        return if (seconds < 1.0) "1/${Math.round(1.0 / seconds)}" else "${Math.round(seconds * 1000)}/1000"
    }

    /**
     * JPEG baytlarındaki EXIF yönünü derece olarak okur. Yön etiketi yoksa null.
     * Kamera donanımı JPEG_ORIENTATION'ı ya pikselleri döndürerek (etiket = normal) ya da yalnızca
     * etiket yazarak uygular; ikisini ayırt etmek için gerekir (yoksa fotoğraf iki kez döndürülebilir).
     */
    fun readOrientationDegrees(jpeg: ByteArray): Int? = try {
        when (ExifInterface(java.io.ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
            ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> null
        }
    } catch (_: Exception) { null }

    fun writeLocation(fd: FileDescriptor, location: Location) {
        try {
            val exif = ExifInterface(fd)
            exif.setGpsInfo(location)
            exif.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun readSummary(fd: FileDescriptor): String = try {
        val exif = ExifInterface(fd)
        buildString {
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { append("ISO: $it\n") }
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let { append("Poz: ${it}s\n") }
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { append("Diyafram: f/$it\n") }
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { append("Odak: ${it}mm\n") }
        }
    } catch (e: Exception) { "" }
}

package com.a56camera.utils

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import androidx.exifinterface.media.ExifInterface
import java.io.FileDescriptor

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
                    val seconds = ns / 1_000_000_000.0
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, seconds.toString())
                }
                r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000")
                }
                r.get(CaptureResult.LENS_APERTURE)?.let {
                    exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
                }
            }

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

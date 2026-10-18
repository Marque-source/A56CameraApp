package io.github.marquesource.pixora.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.DngCreator
import android.location.Location
import android.media.Image
import android.net.Uri
import android.util.Log

/**
 * RAW görüntülerini DngCreator ile .dng dosyasına dönüştürüp MediaStore'a kaydeder.
 *
 * DngCreator, RAW_SENSOR Image + CameraCharacteristics + TotalCaptureResult üst verisi
 * kullanarak Adobe DNG uyumlu çıktı üretir.
 */
object DngHelper {

    private const val TAG = "DngHelper"

    /**
     * @param ch RAW kameranın karakteristikleri
     * @param captureResult çekim sırasındaki TotalCaptureResult
     * @param image RAW_SENSOR formatındaki Image (çağıran kapatmalı)
     * @param location isteğe bağlı GPS konumu
     * @return kaydedilen DNG'nin Uri'si veya null
     */
    fun saveDng(
        context: Context,
        ch: CameraCharacteristics,
        captureResult: android.hardware.camera2.TotalCaptureResult,
        image: Image,
        location: Location?,
        orientation: Int = 0
    ): Uri? {
        val name = MediaStoreHelper.timestampName("RAW")
        val uri = MediaStoreHelper.createDngUri(context, name) ?: return null
        return try {
            DngCreator(ch, captureResult).use { dng ->
                if (location != null) dng.setLocation(location)
                dng.setOrientation(exifOrientation(orientation))
                MediaStoreHelper.openOutput(context, uri)?.use { out ->
                    dng.writeImage(out, image)
                }
            }
            MediaStoreHelper.finalizePending(context, uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "DNG kaydetme hatası", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Birleştirilmiş (çok kareli) Bayer verisini DNG olarak kaydeder.
     * @param bayer RAW16 veri (satır satır, yerel bayt sırası), genişlik × yükseklik short
     */
    fun saveDngBuffer(
        context: Context,
        ch: CameraCharacteristics,
        captureResult: android.hardware.camera2.TotalCaptureResult,
        bayer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        location: Location?,
        orientation: Int = 0,
        prefix: String = "RAW_GECE"
    ): Uri? {
        val name = MediaStoreHelper.timestampName(prefix)
        val uri = MediaStoreHelper.createDngUri(context, name) ?: return null
        return try {
            DngCreator(ch, captureResult).use { dng ->
                if (location != null) dng.setLocation(location)
                dng.setOrientation(exifOrientation(orientation))
                MediaStoreHelper.openOutput(context, uri)?.use { out ->
                    bayer.rewind()
                    dng.writeByteBuffer(out, android.util.Size(width, height), bayer, 0)
                }
            }
            MediaStoreHelper.finalizePending(context, uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Birleşik DNG kaydetme hatası", e)
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    /** Derece cinsinden dönüşü ExifInterface yön sabitine çevirir. */
    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> android.media.ExifInterface.ORIENTATION_NORMAL
    }
}

package io.github.marquesource.pixora.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Fotoğraf/video/DNG dosyalarını MediaStore üzerinden DCIM/Camera klasörüne kaydeder.
 * Android 10+ (Scoped Storage) ve öncesi için uyumludur.
 */
object MediaStoreHelper {

    private const val RELATIVE_PATH = "DCIM/Camera/"
    private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"

    fun timestampName(prefix: String = "IMG"): String {
        val ts = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
        return "${prefix}_$ts"
    }

    // ---------- JPEG ----------
    fun createImageUri(context: Context, displayName: String, mime: String = "image/jpeg"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    // ---------- DNG (RAW) ----------
    fun createDngUri(context: Context, displayName: String): Uri? =
        createImageUri(context, "$displayName.dng", "image/x-adobe-dng")

    // ---------- Video ----------
    fun createVideoUri(context: Context, displayName: String, mime: String = "video/mp4"): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    fun openOutput(context: Context, uri: Uri): OutputStream? =
        context.contentResolver.openOutputStream(uri)

    /** IS_PENDING = 0 yaparak medya öğesini görünür kılar. */
    fun finalizePending(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(uri, values, null, null)
        }
    }

    /** Android 9 ve altı için dosya tabanlı kayıt dizini. */
    @Suppress("DEPRECATION")
    fun legacyDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            .apply { if (!exists()) mkdirs() }

    /** Eski API'de galeriye bildirim gönderir. */
    fun scan(context: Context, path: String, mime: String) {
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime), null)
    }

    /** En son eklenen görselin Uri'si (galeri thumbnail). */
    fun latestImageUri(context: Context): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        try {
            // İzin istenmediği için (Android 10+) sorgu yalnızca bu uygulamanın kaydettiği görselleri döndürür
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sort
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                }
            }
        } catch (_: Exception) {}
        return null
    }
}

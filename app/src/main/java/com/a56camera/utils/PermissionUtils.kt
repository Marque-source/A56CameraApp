package com.a56camera.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * İzin yönetimi yardımcı sınıfı.
 * Android sürümüne göre gerekli izin listesini oluşturur.
 */
object PermissionUtils {

    /**
     * Uygulamanın açılması için zorunlu izinler: yalnızca kamera (ve Android 9'da dosya yazma).
     * Mikrofon video başlatılırken, konum ayarlardan açılınca ayrıca istenir. Galeri okuma izni hiç
     * istenmez: uygulama kendi kaydettiği dosyaları izinsiz okuyabilir (Android 10+).
     */
    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.toTypedArray()
    }

    /** Konum izni (GPS EXIF için ayrı istenir). */
    val locationPermissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /** Tüm zorunlu izinler verilmiş mi? */
    fun hasAllPermissions(context: Context): Boolean =
        requiredPermissions().all { isGranted(context, it) }

    /** Cihazda en az bir kamera donanımı olup olmadığını kontrol eder. */
    fun hasCameraHardware(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun hasLocationPermission(context: Context): Boolean =
        locationPermissions.any { isGranted(context, it) }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

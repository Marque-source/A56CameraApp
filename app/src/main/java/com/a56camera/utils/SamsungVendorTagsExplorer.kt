package com.a56camera.utils

import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samsung Galaxy A56 (Exynos 1580 / UniHAL) Kamera Donanım ve Vendor Tag Keşif Modülü.
 *
 * Cihazın CameraCharacteristics, CaptureRequest, CaptureResult ve Session anahtarlarını
 * tarayarak "samsung." prefixli veya RAW/Night ile ilgili tüm gizli donanım yeteneklerini döker.
 */
object SamsungVendorTagsExplorer {

    private const val TAG = "SamsungVendorDiscovery"

    fun dumpCameraVendorTags(characteristics: CameraCharacteristics, cameraId: String) {
        Log.i(TAG, "================================================================================")
        Log.i(TAG, "🔍 [SAMSUNG VENDOR TAG DISCOVERY] - Kamera ID: $cameraId Başlıyor...")
        Log.i(TAG, "================================================================================")

        // 1. CameraCharacteristics Keys & Values
        try {
            val allCharKeys = characteristics.keys
            val samsungCharKeys = allCharKeys.filter { isRelevantKey(it.name) }.sortedBy { it.name }
            Log.i(TAG, "📋 [1/4] CameraCharacteristics Anahtarları (Toplam: ${allCharKeys.size}, İlgili: ${samsungCharKeys.size})")

            samsungCharKeys.forEach { key ->
                try {
                    val value = characteristics.get(key)
                    val formattedVal = formatValue(value)
                    Log.i(TAG, "  🔹 [CHAR] ${key.name} = $formattedVal")
                } catch (e: Throwable) {
                    Log.w(TAG, "  ⚠️ [CHAR] ${key.name} (Okuma hatası: ${e.message})")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "CameraCharacteristics listeleme hatası", e)
        }

        // 2. CaptureRequest Keys
        try {
            val allReqKeys = characteristics.availableCaptureRequestKeys
            val samsungReqKeys = allReqKeys.filter { isRelevantKey(it.name) }.sortedBy { it.name }
            Log.i(TAG, "--------------------------------------------------------------------------------")
            Log.i(TAG, "📤 [2/4] CaptureRequest Kontrol Anahtarları (Toplam: ${allReqKeys.size}, İlgili: ${samsungReqKeys.size})")

            samsungReqKeys.forEach { key ->
                Log.i(TAG, "  🔸 [REQ] ${key.name}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "CaptureRequest anahtarları listeleme hatası", e)
        }

        // 3. CaptureResult Keys
        try {
            val allResKeys = characteristics.availableCaptureResultKeys
            val samsungResKeys = allResKeys.filter { isRelevantKey(it.name) }.sortedBy { it.name }
            Log.i(TAG, "--------------------------------------------------------------------------------")
            Log.i(TAG, "📥 [3/4] CaptureResult Dönüş Anahtarları (Toplam: ${allResKeys.size}, İlgili: ${samsungResKeys.size})")

            samsungResKeys.forEach { key ->
                Log.i(TAG, "  ▫️ [RES] ${key.name}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "CaptureResult anahtarları listeleme hatası", e)
        }

        // 4. Session Keys (Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val allSessionKeys = characteristics.availableSessionKeys ?: emptyList()
                val samsungSessionKeys = allSessionKeys.filter { isRelevantKey(it.name) }.sortedBy { it.name }
                Log.i(TAG, "--------------------------------------------------------------------------------")
                Log.i(TAG, "🔒 [4/4] Session Yapılandırma Anahtarları (Toplam: ${allSessionKeys.size}, İlgili: ${samsungSessionKeys.size})")

                samsungSessionKeys.forEach { key ->
                    Log.i(TAG, "  🔑 [SESSION] ${key.name}")
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Session anahtarları listeleme hatası", e)
            }
        }

        // 5. Targeted Direct Probes (Doğrudan Anahtar Sorgulama & Reflection)
        Log.i(TAG, "--------------------------------------------------------------------------------")
        Log.i(TAG, "🎯 [5/5] Doğrudan Samsung Vendor Tag Sorguları:")

        probeCharKey(characteristics, "samsung.android.scaler.availableSuperNightRawStreamConfigurations", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.scaler.rawSensorInfo", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.sensor.info.sensorName", ByteArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.aeCompensationRange", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.liveHdrAvailableModes", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.availableFeatures", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.sdkAvailableFeatures", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.llHdrEvCompensationList", FloatArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.mfHdrEvCompensationList", FloatArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.availablePhotoNightZoomRatioRange", FloatArray::class.java)
        probeCharKey(characteristics, "samsung.android.scaler.availableSuperResolutionRawStreamConfigurations", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.scaler.availableHighresRawStreamConfigurations", IntArray::class.java)
        probeCharKey(characteristics, "samsung.android.control.captureHint", Int::class.javaObjectType)
        probeCharKey(characteristics, "samsung.android.control.requestHint", Int::class.javaObjectType)

        Log.i(TAG, "================================================================================")
        Log.i(TAG, "🏁 [SAMSUNG VENDOR TAG DISCOVERY] - Kamera ID: $cameraId Tamamlandı.")
        Log.i(TAG, "================================================================================")
    }

    private fun <T> probeCharKey(characteristics: CameraCharacteristics, keyName: String, clazz: Class<T>) {
        // CameraCharacteristics.Key(String, Class) yapıcısı Android 10 (API 29) ile geldi
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        try {
            val key = CameraCharacteristics.Key(keyName, clazz)
            val value = characteristics.get(key)
            if (value != null) {
                Log.i(TAG, "  ✅ [PROBE SUCCESS] $keyName = ${formatValue(value)}")
            } else {
                Log.i(TAG, "  ⚪ [PROBE NULL] $keyName (Tag kabul edildi ancak null döndü)")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "  ❌ [PROBE REJECTED] $keyName: ${e.message}")
        }
    }

    private fun isRelevantKey(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("samsung.") ||
                lower.contains("supernight") ||
                lower.contains("night") ||
                lower.contains("raw") ||
                lower.contains("tetra") ||
                lower.contains("unihal")
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is IntArray -> value.contentToString()
            is LongArray -> value.contentToString()
            is FloatArray -> value.contentToString()
            is DoubleArray -> value.contentToString()
            is ByteArray -> {
                // Eğer ASCII yazıysa string göster
                val isAscii = value.all { it in 0..127 }
                if (isAscii && value.isNotEmpty()) {
                    val str = String(value).trimEnd { it == '\u0000' }
                    "${value.contentToString()} -> \"$str\""
                } else {
                    value.contentToString()
                }
            }
            is ShortArray -> value.contentToString()
            is BooleanArray -> value.contentToString()
            is Array<*> -> value.contentDeepToString()
            else -> value.toString()
        }
    }

    fun testRaw12Stream(device: CameraDevice, bgHandler: Handler, onComplete: () -> Unit) {
        val completed = AtomicBoolean(false)
        fun finishOnce() {
            if (completed.compareAndSet(false, true)) {
                bgHandler.post { onComplete() }
            }
        }

        // 6 saniye genel test zaman aşımı
        bgHandler.postDelayed({
            if (!completed.get()) {
                Log.w(TAG, "⏱️ [RAW ALTERNATİF TESTLERİ ZAMAN AŞIMI] Normal önizlemeye geçiliyor...")
                finishOnce()
            }
        }, 6000)

        Log.i(TAG, "🧪 ================================================================================")
        Log.i(TAG, "🧪 [RAW BAĞLANTI İÇİN TÜM ALTERNATİF YOLLARIN TESTİ BAŞLIYOR]")
        Log.i(TAG, "🧪 ================================================================================")

        val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

        // -------------------------------------------------------------
        // Yol 1: Physical Camera ID "56" (Sony IMX906 Sensörü) + SessionConfiguration
        // Vendor tag'i hatırlayalım: [56, 4080, 3060, 0, 38]
        // -------------------------------------------------------------
        val width = 4080
        val height = 3060
        val format = ImageFormat.RAW12 // 38

        Log.i(TAG, "🧪 [Yol 1: Physical Camera '56' + OutputConfiguration + SessionConfiguration]")
        val reader1: ImageReader? = try {
            ImageReader.newInstance(width, height, format, 2)
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [Yol 1] ImageReader(RAW12) oluşturulamadı: ${e.message}")
            null
        }

        if (reader1 != null) {
            reader1.setOnImageAvailableListener({ r ->
                Log.i(TAG, "🎉🎉🎉 [YOL 1 BAŞARILI: RAW12 KARE GELDİ!] 🎉🎉🎉")
                val img = try { r.acquireNextImage() } catch (_: Throwable) { null }
                img?.use {
                    Log.i(TAG, "🔥 RAW12 Boyut: ${it.width}x${it.height}, Format: ${it.format}")
                }
                finishOnce()
            }, bgHandler)

            try {
                val outputConfig = android.hardware.camera2.params.OutputConfiguration(reader1.surface).apply {
                    setPhysicalCameraId("56")
                }

                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    bgExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.i(TAG, "🎉🎉🎉 [YOL 1 BAŞARILI! HAL Physical '56' RAW12 Kabul Etti!] 🎉🎉🎉")
                            try {
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader1.surface)
                                }
                                session.capture(req.build(), null, bgHandler)
                            } catch (e: Throwable) {
                                Log.e(TAG, "❌ [Yol 1 Capture Hatası]: ${e.message}")
                                finishOnce()
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.w(TAG, "🛑 [Yol 1 REDDEDİLDİ] HAL Physical '56' RAW12 yüzeyini reddetti.")
                            testPath2(device, bgHandler, bgExecutor, ::finishOnce)
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            try { reader1.close() } catch (_: Throwable) {}
                        }
                    }
                )

                // Vendor session parameter injection (superNightShotMode)
                try {
                    val initReq = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    sessionConfig.sessionParameters = initReq.build()
                } catch (_: Throwable) {}

                Log.i(TAG, "🧪 [Yol 1] SessionConfiguration ile createCaptureSession çağrılıyor...")
                device.createCaptureSession(sessionConfig)

            } catch (e: Throwable) {
                Log.e(TAG, "❌ [Yol 1 İstisna]: ${e.message}")
                testPath2(device, bgHandler, bgExecutor, ::finishOnce)
            }
        } else {
            testPath2(device, bgHandler, bgExecutor, ::finishOnce)
        }
    }

    private fun testPath2(
        device: CameraDevice,
        bgHandler: Handler,
        executor: java.util.concurrent.Executor,
        onDone: () -> Unit
    ) {
        // -------------------------------------------------------------
        // Yol 2: ImageFormat.RAW10 (37) Testi
        // -------------------------------------------------------------
        Log.i(TAG, "🧪 [Yol 2: ImageFormat.RAW10 (37) Test Ediliyor...]")
        val reader2: ImageReader? = try {
            ImageReader.newInstance(4080, 3060, ImageFormat.RAW10, 2)
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [Yol 2] ImageReader(RAW10) oluşturulamadı: ${e.message}")
            null
        }

        if (reader2 != null) {
            try {
                val outputConfig = android.hardware.camera2.params.OutputConfiguration(reader2.surface).apply {
                    setPhysicalCameraId("56")
                }
                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.i(TAG, "🎉🎉🎉 [YOL 2 BAŞARILI! HAL RAW10 Kabul Etti!] 🎉🎉🎉")
                            try {
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader2.surface)
                                }
                                session.capture(req.build(), null, bgHandler)
                            } catch (_: Throwable) {}
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.w(TAG, "🛑 [Yol 2 REDDEDİLDİ] HAL RAW10 yüzeyini reddetti.")
                            testPath3(device, bgHandler, executor, onDone)
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            try { reader2.close() } catch (_: Throwable) {}
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
            } catch (e: Throwable) {
                Log.e(TAG, "❌ [Yol 2 İstisna]: ${e.message}")
                testPath3(device, bgHandler, executor, onDone)
            }
        } else {
            testPath3(device, bgHandler, executor, onDone)
        }
    }

    private fun testPath3(
        device: CameraDevice,
        bgHandler: Handler,
        executor: java.util.concurrent.Executor,
        onDone: () -> Unit
    ) {
        // -------------------------------------------------------------
        // Yol 3: ImageFormat.RAW_SENSOR (32) Testi
        // -------------------------------------------------------------
        Log.i(TAG, "🧪 [Yol 3: ImageFormat.RAW_SENSOR (32) Test Ediliyor...]")
        val reader3: ImageReader? = try {
            ImageReader.newInstance(4080, 3060, ImageFormat.RAW_SENSOR, 2)
        } catch (e: Throwable) {
            Log.e(TAG, "❌ [Yol 3] ImageReader(RAW_SENSOR) oluşturulamadı: ${e.message}")
            null
        }

        if (reader3 != null) {
            try {
                val outputConfig = android.hardware.camera2.params.OutputConfiguration(reader3.surface).apply {
                    setPhysicalCameraId("56")
                }
                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            Log.i(TAG, "🎉🎉🎉 [YOL 3 BAŞARILI! HAL RAW_SENSOR Kabul Etti!] 🎉🎉🎉")
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.w(TAG, "🛑 [Yol 3 REDDEDİLDİ] HAL RAW_SENSOR yüzeyini reddetti.")
                            Log.i(TAG, "🏁 [TÜM ALTERNATİFLER TAMAMLANDI] Kesin donanım teşhisi hazır.")
                            onDone()
                        }

                        override fun onClosed(session: CameraCaptureSession) {
                            try { reader3.close() } catch (_: Throwable) {}
                        }
                    }
                )
                device.createCaptureSession(sessionConfig)
            } catch (e: Throwable) {
                Log.e(TAG, "❌ [Yol 3 İstisna]: ${e.message}")
                onDone()
            }
        } else {
            onDone()
        }
    }
}

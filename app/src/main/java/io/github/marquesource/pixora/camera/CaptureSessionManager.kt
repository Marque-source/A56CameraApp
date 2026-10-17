package io.github.marquesource.pixora.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * CaptureSession yaşam döngüsünü yönetir.
 *
 * - Normal önizleme/çekim oturumu (createCaptureSession)
 * - Yüksek hızlı (slow-motion) oturumu (createConstrainedHighSpeedCaptureSession)
 * - Tekrarlayan (repeating) önizleme isteği
 * - Tek kare ve burst çekim
 */
class CaptureSessionManager(private val bgHandler: Handler) {

    private var session: CameraCaptureSession? = null
    var isHighSpeed: Boolean = false
        private set

    interface Callback {
        fun onConfigured(session: CameraCaptureSession)
        fun onFailed(reason: String)
    }

    /** Standart oturum oluşturur (deprecated API tüm min SDK'larda çalışır). */
    @Suppress("DEPRECATION")
    fun createSession(device: CameraDevice, surfaces: List<Surface>, cb: Callback) {
        isHighSpeed = false
        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    cb.onConfigured(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    cb.onFailed("Oturum yapılandırılamadı")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createSession hatası", e)
            cb.onFailed(e.message ?: "bilinmeyen hata")
        }
    }

    /** Yüksek hızlı (constrained high-speed) oturum — slow motion için. */
    @Suppress("DEPRECATION")
    fun createHighSpeedSession(device: CameraDevice, surfaces: List<Surface>, cb: Callback) {
        isHighSpeed = true
        try {
            device.createConstrainedHighSpeedCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    cb.onConfigured(s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    cb.onFailed("Yüksek hızlı oturum yapılandırılamadı")
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createHighSpeedSession hatası", e)
            cb.onFailed(e.message ?: "bilinmeyen hata")
        }
    }

    /** Tekrarlayan (önizleme) isteği başlatır. */
    fun startRepeating(request: CaptureRequest, listener: CameraCaptureSession.CaptureCallback? = null) {
        val s = session ?: return
        try {
            if (isHighSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                val list = s.createHighSpeedRequestList(request)
                s.setRepeatingBurst(list, listener, bgHandler)
            } else {
                s.setRepeatingRequest(request, listener, bgHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRepeating hatası", e)
        }
    }

    fun stopRepeating() {
        try { session?.stopRepeating() } catch (e: Exception) { /* yoksay */ }
    }

    /** Tek kare çekim (fotoğraf). */
    fun capture(request: CaptureRequest, listener: CameraCaptureSession.CaptureCallback?) {
        if (isHighSpeed) {
            Log.w(TAG, "High speed oturumunda tek kare capture() desteklenmez, atlandı")
            return
        }
        try {
            session?.capture(request, listener, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "capture hatası", e)
        }
    }

    /** Burst çekim (ör. gece modu çoklu kare). */
    fun captureBurst(requests: List<CaptureRequest>, listener: CameraCaptureSession.CaptureCallback?) {
        try {
            session?.captureBurst(requests, listener, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "captureBurst hatası", e)
        }
    }

    /** Yüksek hızlı burst isteği listesi oluşturur (slow motion kayıt). */
    fun highSpeedRequestList(request: CaptureRequest): List<CaptureRequest> {
        val s = session
        return if (isHighSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
            s.createHighSpeedRequestList(request)
        } else listOf(request)
    }

    fun setRepeatingBurst(requests: List<CaptureRequest>, listener: CameraCaptureSession.CaptureCallback?) {
        try {
            val s = session
            val finalRequests = if (isHighSpeed && s is CameraConstrainedHighSpeedCaptureSession && requests.size == 1) {
                s.createHighSpeedRequestList(requests[0])
            } else {
                requests
            }
            s?.setRepeatingBurst(finalRequests, listener, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingBurst hatası", e)
        }
    }

    fun close() {
        try { session?.close() } catch (e: Exception) { /* yoksay */ }
        session = null
    }

    fun current(): CameraCaptureSession? = session

    companion object { private const val TAG = "CaptureSessionManager" }
}

package com.a56camera.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import com.a56camera.utils.MediaStoreHelper

/**
 * Camera2 + MediaRecorder ile video kaydı.
 *
 * Kalite profilleri: 4K@30, 1080p@60/30, 720p@120. H.264 veya H.265 (HEVC) kodlayıcı,
 * MIC ses kaynağı ve isteğe bağlı video stabilizasyonu desteklenir.
 */
class VideoCapture(private val context: Context) {

    enum class Quality(val size: Size, val fps: Int, val bitrate: Int) {
        UHD_60(Size(3840, 2160), 60, 55_000_000),
        UHD_30(Size(3840, 2160), 30, 42_000_000),
        FHD_60(Size(1920, 1080), 60, 20_000_000),
        FHD_30(Size(1920, 1080), 30, 14_000_000),
        HD_120(Size(1280, 720), 120, 18_000_000)
    }

    enum class Codec { H264, H265 }

    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var recorderSurface: Surface? = null
    var isRecording = false
        private set

    /** MediaRecorder yüzeyi (CaptureSession'a hedef olarak eklenir). */
    fun surface(): Surface? = recorderSurface

    /**
     * MediaRecorder'ı hazırlar ve kayıt yüzeyini döndürür.
     * Oturum bu yüzeyle yeniden yapılandırılmalıdır.
     */
    fun prepare(
        quality: Quality,
        codec: Codec,
        withAudio: Boolean,
        orientationHint: Int
    ): Surface? {
        val name = MediaStoreHelper.timestampName("VID")
        val uri = MediaStoreHelper.createVideoUri(context, "$name.mp4") ?: return null
        outputUri = uri
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null

        recorder = createRecorder().apply {
            if (withAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncodingBitRate(quality.bitrate)
            setVideoFrameRate(quality.fps)
            setVideoSize(quality.size.width, quality.size.height)
            setVideoEncoder(
                if (codec == Codec.H265) MediaRecorder.VideoEncoder.HEVC
                else MediaRecorder.VideoEncoder.H264
            )
            if (withAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setOrientationHint(orientationHint)
            try {
                prepare()
            } catch (e: Exception) {
                Log.e(TAG, "MediaRecorder prepare hatası", e)
                return null
            }
        }
        recorderSurface = recorder?.surface
        return recorderSurface
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else MediaRecorder()

    /** RECORD şablonlu tekrarlayan isteği başlatıp kaydı fiilen başlatır. */
    fun start() {
        try {
            recorder?.start()
            isRecording = true
        } catch (e: Exception) {
            Log.e(TAG, "start hatası", e)
        }
    }

    /** Kaydı durdurur, dosyayı görünür kılar ve Uri döndürür. */
    fun stop(): Uri? {
        return try {
            recorder?.stop()
            recorder?.reset()
            recorder?.release()
            isRecording = false
            recorderSurface?.release(); recorderSurface = null
            recorder = null
            outputUri?.also { MediaStoreHelper.finalizePending(context, it) }
        } catch (e: Exception) {
            Log.e(TAG, "stop hatası", e)
            // Bozuk dosyayı sil
            outputUri?.let { context.contentResolver.delete(it, null, null) }
            null
        }
    }

    fun cancel() {
        try {
            recorder?.reset(); recorder?.release()
        } catch (e: Exception) { /* yoksay */ }
        recorder = null
        recorderSurface?.release(); recorderSurface = null
        isRecording = false
        outputUri?.let { context.contentResolver.delete(it, null, null) }
        outputUri = null
    }

    /** Yüksek hızlı (HD_120 vb.) kalitenin cihazda desteklenip desteklenmediğini kontrol eder. */
    fun supportsHighSpeed(ch: CameraCharacteristics, size: Size, fps: Int): Boolean {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return try {
            map.highSpeedVideoSizes.any { it == size } &&
                map.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= fps }
        } catch (e: Exception) { false }
    }

    companion object { private const val TAG = "VideoCapture" }
}

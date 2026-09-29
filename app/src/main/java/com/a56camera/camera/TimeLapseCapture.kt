package com.a56camera.camera

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import com.a56camera.utils.MediaStoreHelper

/**
 * Time-lapse (hızlandırılmış) video kaydı.
 *
 * MediaRecorder.setCaptureRate() ile düşük yakalama hızı (ör. 1 fps) ayarlanır;
 * çıktı 30 fps oynatıldığında zaman hızlanmış görünür. Aralık seçenekleri:
 * 0.5s / 1s / 2s / 5s / 10s / 30s.
 */
class TimeLapseCapture(private val context: Context) {

    enum class Interval(val seconds: Double, val label: String) {
        HALF(0.5, "0.5s"),
        ONE(1.0, "1s"),
        TWO(2.0, "2s"),
        FIVE(5.0, "5s"),
        TEN(10.0, "10s"),
        THIRTY(30.0, "30s")
    }

    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var outputUri: Uri? = null
    var isRecording = false
        private set

    fun surface(): Surface? = recorderSurface

    /**
     * @param interval kareler arası gerçek zaman aralığı
     * @param playbackFps oynatma kare hızı (genellikle 30)
     */
    fun prepare(
        interval: Interval,
        size: Size = Size(1920, 1080),
        playbackFps: Int = 30,
        orientationHint: Int
    ): Surface? {
        val name = MediaStoreHelper.timestampName("TL")
        val uri = MediaStoreHelper.createVideoUri(context, "$name.mp4") ?: return null
        outputUri = uri
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null

        recorder = createRecorder().apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(20_000_000)
            setVideoSize(size.width, size.height)
            setVideoFrameRate(playbackFps)
            // Yakalama hızı = 1 / aralık (saniyede kare)
            setCaptureRate(1.0 / interval.seconds)
            setOrientationHint(orientationHint)
            try { prepare() } catch (e: Exception) {
                Log.e(TAG, "TimeLapse prepare hatası", e); return null
            }
        }
        recorderSurface = recorder?.surface
        return recorderSurface
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else MediaRecorder()

    fun start() {
        try { recorder?.start(); isRecording = true }
        catch (e: Exception) { Log.e(TAG, "start hatası", e) }
    }

    fun stop(): Uri? {
        return try {
            recorder?.stop(); recorder?.reset(); recorder?.release()
            isRecording = false
            recorderSurface?.release(); recorderSurface = null
            recorder = null
            outputUri?.also { MediaStoreHelper.finalizePending(context, it) }
        } catch (e: Exception) {
            Log.e(TAG, "stop hatası", e)
            outputUri?.let { context.contentResolver.delete(it, null, null) }
            null
        }
    }

    fun cancel() {
        try { recorder?.reset(); recorder?.release() } catch (e: Exception) { /* yoksay */ }
        recorder = null
        recorderSurface?.release(); recorderSurface = null
        isRecording = false
        outputUri?.let { context.contentResolver.delete(it, null, null) }
        outputUri = null
    }

    companion object { private const val TAG = "TimeLapseCapture" }
}

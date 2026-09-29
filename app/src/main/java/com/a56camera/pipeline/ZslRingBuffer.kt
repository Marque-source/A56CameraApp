package com.a56camera.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.util.Log
import com.a56camera.camera.YuvFrame
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Zero Shutter Lag (ZSL) Dairesel Bellek Tamponu (Ring Buffer).
 *
 * Kamera önizlemesi açıkken gelen son N adet YUV karesini ve metadata'sını RAM'de tutar.
 * Deklanşöre basıldığı an 0ms gecikmeyle en net, en az titremiş kareleri seçip pipeline'a verir.
 */
class ZslRingBuffer(
    val capacity: Int = 4
) {
    companion object {
        private const val TAG = "ZslRingBuffer"
    }

    /** Tamponda tutulan tek bir ZSL karesi */
    data class ZslEntry(
        val frame: YuvFrame,
        val metadata: TotalCaptureResult,
        val timestampNs: Long,
        val sharpnessScore: Float // Netlik / Bulanıklık puanı
    )

    private val lock = ReentrantLock()
    private val buffer = ArrayList<ZslEntry>(capacity)
    private var isLockedForCapture = false

    /**
     * Canlı kamera akışından gelen yeni kareyi tampona ekler.
     * Kapasite dolduğunda en eski kareyi otomatik kapatıp (recycle) yenisine yer açar.
     */
    fun push(image: Image, metadata: TotalCaptureResult) {
        lock.withLock {
            if (isLockedForCapture) {
                // Çekim esnasında tampon dondurulur, yeni gelenler düşürülür
                try { image.close() } catch (_: Throwable) {}
                return
            }

            try {
                val timestampNs = metadata.get(CaptureResult.SENSOR_TIMESTAMP) ?: image.timestamp
                val sharpness = calculateSharpnessScore(metadata)

                // Image'i bellek dostu YuvFrame'e kopyala
                val yuvFrame = YuvFrame.fromImage(image, metadata, 0f)

                if (yuvFrame == null) {
                    try { image.close() } catch (_: Throwable) {}
                    return
                }

                // Tampon dolduysa en eskiyi serbest bırak
                if (buffer.size >= capacity) {
                    buffer.removeAt(0)
                }

                buffer.add(ZslEntry(yuvFrame, metadata, timestampNs, sharpness))
            } catch (e: Exception) {
                Log.e(TAG, "ZSL push hatası", e)
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Deklanşöre basıldığı an çağrılır (0ms gecikme).
     * Tampondaki en kaliteli, netliği en yüksek N kareyi seçip döndürür.
     *
     * @param count İstenen kare sayısı (örn. 5 veya 6)
     */
    fun snapshotBestFrames(count: Int = 5): List<Pair<YuvFrame, TotalCaptureResult>> {
        lock.withLock {
            if (buffer.isEmpty()) return emptyList()

            val candidates = buffer.toList()
            val selected = if (candidates.size <= count) {
                candidates
            } else {
                candidates.sortedByDescending { it.sharpnessScore }.take(count)
            }

            Log.d(TAG, "ZSL Snapshot alındı: ${selected.size} / ${buffer.size} kare seçildi (0ms gecikme)")

            return selected.map { it.frame to it.metadata }
        }
    }

    /**
     * Çekim başladığında tamponu kilitler (yeni kareler eskiyi ezmesin).
     */
    fun lockForCapture() {
        lock.withLock { isLockedForCapture = true }
    }

    /**
     * Çekim tamamlandığında tamponu tekrar akışa açar.
     */
    fun unlockAfterCapture() {
        lock.withLock { isLockedForCapture = false }
    }

    /**
     * Tampondaki tüm kaynakları serbest bırakır.
     */
    fun clear() {
        lock.withLock {
            buffer.clear()
            isLockedForCapture = false
        }
    }

    /**
     * Metadata'dan netlik puanı kestirimi (Lens focus durumu, ISO, pozlama süresi).
     * Daha kısa pozlama süresi ve AF kilitli durumu = daha keskin görüntü.
     */
    private fun calculateSharpnessScore(metadata: TotalCaptureResult): Float {
        val afState = metadata.get(CaptureResult.CONTROL_AF_STATE) ?: CaptureResult.CONTROL_AF_STATE_INACTIVE
        val exposureTime = metadata.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val iso = metadata.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100

        var score = 100f

        if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
            score += 50f
        }

        val exposureMs = exposureTime / 1_000_000f
        score += (33f - exposureMs.coerceAtMost(33f)) * 2f

        score -= (iso / 100f)

        return score.coerceAtLeast(0f)
    }
}

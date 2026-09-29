package com.a56camera.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.Face
import androidx.core.graphics.applyCanvas

/**
 * Portre modu — yüz tespiti + arka plan bulanıklaştırma (bokeh benzeri).
 *
 * Camera2 STATISTICS_FACE_DETECT_MODE ile yüzler tespit edilir. Yakalanan JPEG
 * bitmap'inde yüz(ler)in dışındaki alan yazılım tarafında bulanıklaştırılır.
 * Donanım depth (bokeh) akışı olmayan cihazlarda yaklaşık bir sonuç üretir.
 */
class PortraitMode {

    /** Önizleme isteğine yüz tespitini etkinleştirir. */
    fun enableFaceDetection(builder: CaptureRequest.Builder, ch: CameraCharacteristics) {
        val modes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        if (modes != null && modes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL)) {
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL
            )
        } else if (modes != null && modes.contains(CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)) {
            builder.set(
                CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
            )
        }
    }

    /** Son sonuçtan tespit edilen yüzleri döndürür. */
    fun facesOf(result: TotalCaptureResult?): Array<Face> =
        result?.get(CaptureResult.STATISTICS_FACES) ?: emptyArray()

    /**
     * JPEG bitmap'ine yaklaşık bokeh uygular: yüz bölgelerini keskin bırakır,
     * arka planı bulanıklaştırıp üstüne yüzleri tekrar yerleştirir.
     *
     * @param faceRectsNormalized ekran/görüntü oranına göre 0..1 yüz dikdörtgenleri
     */
    fun applyBokeh(src: Bitmap, faceRectsNormalized: List<RectF>, blurRadius: Int = 24): Bitmap {
        val blurred = stackBlur(src.copy(Bitmap.Config.ARGB_8888, true), blurRadius)
        if (faceRectsNormalized.isEmpty()) return blurred
        return blurred.applyCanvas {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (nr in faceRectsNormalized) {
                val r = Rect(
                    (nr.left * src.width).toInt(),
                    (nr.top * src.height).toInt(),
                    (nr.right * src.width).toInt(),
                    (nr.bottom * src.height).toInt()
                )
                // Yüz alanını biraz genişlet (saç/omuz payı)
                val pad = (r.width() * 0.35f).toInt()
                r.inset(-pad, -pad)
                r.intersect(0, 0, src.width, src.height)
                drawBitmap(src, r, r, paint)
            }
        }
    }

    /**
     * Basit kutu (box) bulanıklaştırma — harici bağımlılık olmadan yazılım bokeh.
     * Büyük görüntüler için önce küçültülür.
     */
    private fun stackBlur(bitmap: Bitmap, radius: Int): Bitmap {
        val scale = 0.25f
        val small = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true
        )
        val w = small.width; val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val r = (radius * scale).toInt().coerceAtLeast(1)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var count = 0
                for (dy in -r..r) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    for (dx in -r..r) {
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val p = pixels[ny * w + nx]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        count++
                    }
                }
                out[y * w + x] = (0xFF shl 24) or
                    ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count)
            }
        }
        small.setPixels(out, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
    }
}

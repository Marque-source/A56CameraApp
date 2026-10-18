package io.github.marquesource.pixora.pipeline

import android.content.Context
import android.util.Log
import io.github.marquesource.pixora.BuildConfig
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Yalnızca geliştirici (debug) sürümlerinde: gece/portre işleme hattının ara aşamalarını uygulamanın
 * özel klasörüne kaydeder. Test betiği bunları `adb exec-out run-as ...` ile bilgisayara çeker; böylece
 * görüntü işleme gerçek telefon kareleriyle masaüstünde ayarlanabilir. Yayın sürümünde hiçbir şey yazmaz.
 */
class DebugDump(context: Context, tag: String) {
    private val dir: File? = if (BuildConfig.DEBUG) {
        File(context.filesDir, "debug/${System.currentTimeMillis()}_$tag").apply { mkdirs() }
    } else null

    val enabled get() = dir != null

    fun mat(stage: String, m: Mat) {
        val d = dir ?: return
        try {
            Imgcodecs.imwrite(File(d, "$stage.png").absolutePath, m)
        } catch (e: Throwable) {
            Log.w("DebugDump", "$stage yazılamadı: ${e.message}")
        }
    }

    fun text(name: String, content: String) {
        val d = dir ?: return
        try { File(d, name).writeText(content) } catch (_: Throwable) {}
    }

    companion object {
        /** En fazla bu kadar çekimin dökümü tutulur (eskiler silinir). */
        fun prune(context: Context, keep: Int = 6) {
            if (!BuildConfig.DEBUG) return
            val root = File(context.filesDir, "debug")
            root.listFiles()?.sortedByDescending { it.name }?.drop(keep)?.forEach { it.deleteRecursively() }
        }
    }
}

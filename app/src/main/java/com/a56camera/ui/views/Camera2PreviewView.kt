package com.a56camera.ui.views

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Camera2 önizlemesi için TextureView tabanlı görünüm.
 *
 * CameraX PreviewView'in yerini alır. SurfaceTexture hazır olduğunda geri çağırır,
 * sensör çıktı boyutuna göre en-boy oranını korur ("fitCenter" benzeri) ve önizleme
 * için kullanılabilir bir [Surface] üretir.
 */
class Camera2PreviewView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    /** SurfaceTexture hazır olduğunda (ilk kez) tetiklenir. */
    var onSurfaceReady: ((SurfaceTexture, Int, Int) -> Unit)? = null
    /** Boyut değiştiğinde tetiklenir. */
    var onSurfaceSizeChanged: ((Int, Int) -> Unit)? = null

    private var bufferSize: Size = Size(1920, 1080)
    private var sensorOrientation: Int = 90
    private var lastScaleX = 1f
    private var lastScaleY = 1f

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                onSurfaceReady?.invoke(st, width, height)
                applyTransform(width, height)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                onSurfaceSizeChanged?.invoke(width, height)
                applyTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    /** Kameradan seçilen önizleme buffer boyutunu ayarlar. */
    fun setBufferSize(size: Size, orientation: Int) {
        bufferSize = size
        sensorOrientation = orientation
        if (isAvailable) applyTransform(width, height)
    }

    /** Önizleme için bir Surface oluşturur (buffer boyutu ayarlanmış olarak). */
    fun createSurface(): Surface? {
        val st = surfaceTexture ?: return null
        st.setDefaultBufferSize(bufferSize.width, bufferSize.height)
        return Surface(st)
    }

    /**
     * TextureView içeriğini en-boy oranını koruyarak ortalar (letterbox).
     * Sensör 90/270° döndüğü için buffer genişlik/yüksekliği yer değiştirir.
     */
    private fun applyTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        // Sensör çıktısı yatay; portre görünümde döndürülmüş oran kullanılır
        val rotated = sensorOrientation == 90 || sensorOrientation == 270
        val contentW = if (rotated) bufferSize.height else bufferSize.width
        val contentH = if (rotated) bufferSize.width else bufferSize.height
        if (contentW == 0 || contentH == 0) return

        val viewRatio = viewWidth.toFloat() / viewHeight
        val contentRatio = contentW.toFloat() / contentH

        val scaleX: Float
        val scaleY: Float
        if (contentRatio > viewRatio) {
            // içerik daha geniş → yüksekliğe göre sığdır
            scaleX = 1f
            scaleY = (viewWidth / contentRatio) / viewHeight
        } else {
            scaleX = (viewHeight * contentRatio) / viewWidth
            scaleY = 1f
        }

        val matrix = Matrix()
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        matrix.setScale(scaleX, scaleY, cx, cy)
        setTransform(matrix)
        lastScaleX = scaleX
        lastScaleY = scaleY
    }

    /** Önizleme görüntüsünün görünüm içinde gerçekten kapladığı alan (siyah kenarlar hariç). */
    fun contentRect(): RectF {
        val w = width * lastScaleX
        val h = height * lastScaleY
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        return RectF(left, top, left + w, top + h)
    }
}

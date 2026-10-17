package io.github.marquesource.pixora.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dokunarak odaklama halkası ve dikey pozlama (ışık kısma/açma) kontrolü.
 * iPhone & Pixel tarzı: Odaklama halkasının yanında dikey kaydırma rayı ve parlayan altın güneş ikonu ☀️.
 * Parmağı yukarı kaydırınca ışık açılır (+EV), aşağı kaydırınca ışık kısılır (-EV).
 */
class FocusRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = context.resources.displayMetrics.density

    // Odak halkası çizim ayarları
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFCC00")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    // Ray (slider track) çizim ayarları
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Nötr (0 EV) çentiği
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B3FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Güneş ikonu gövdesi
    private val sunBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD54F")
        style = Paint.Style.FILL
    }

    // Güneş ışınları
    private val sunRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    // Güneş dış parıltısı (halo)
    private val sunGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFD54F")
        style = Paint.Style.FILL
    }

    // EV Etiketi arka planı ve metni
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD54F")
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Durum değişkenleri
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var targetRadius = 36f * density
    private var initialRadius = 55f * density
    private var alphaValue = 0
    private var locked = false

    // Dikey Pozlama Rayı (Slider) Ayarları
    private val trackHalfHeight = 65f * density  // Toplam yükseklik ~130dp
    private val sliderOffset = 55f * density     // Odak merkezinden sağa uzaklık
    private var sliderX = 0f
    private var isFlippedToLeft = false

    // Pozlama ilerlemesi: -1.0f (en karanlık) .. 0.0f (nötr) .. +1.0f (en aydınlık)
    private var exposureProgress = 0f
    private var exposureLabel = "0"
    private var showSlider = true

    private var scaleAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private var fadeRunnable: Runnable? = null

    /**
     * Odak halkasını ve ışık ayar çubuğunu verilen (x, y) konumunda gösterir.
     */
    fun showAt(
        x: Float,
        y: Float,
        isLocked: Boolean = false,
        currentEvProgress: Float = 0f,
        evLabel: String = "0"
    ) {
        cancelFadeOut()
        cx = x
        cy = y
        locked = isLocked
        exposureProgress = currentEvProgress.coerceIn(-1f, 1f)
        exposureLabel = evLabel

        // Ekranın sağ kenarına çok yakınsa rayı sola al (akıllı kenar kontrolü)
        val viewWidth = if (width > 0) width.toFloat() else 1080f
        isFlippedToLeft = (cx + sliderOffset + 40f * density > viewWidth)
        sliderX = if (isFlippedToLeft) cx - sliderOffset else cx + sliderOffset

        ringPaint.color = if (isLocked) Color.parseColor("#FFFFCC00") else Color.WHITE
        alphaValue = 255
        updateAlpha(255)

        // Halka küçülme animasyonu (Pixel & iPhone tarzı odaklanma hissi)
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(initialRadius, targetRadius).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                radius = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (!isLocked) {
            scheduleFadeOut(3200L)
        }
    }

    /**
     * Kullanıcı parmağını kaydırdıkça anlık pozlama değerini ve etiketini günceller.
     * @param progress -1.0f .. +1.0f arası normalleştirilmiş EV ilerlemesi
     * @param label "+1.3", "-0.7", "0" gibi gösterge metni
     */
    fun updateExposure(progress: Float, label: String) {
        exposureProgress = progress.coerceIn(-1f, 1f)
        exposureLabel = label
        keepAlive()
        invalidate()
    }

    /**
     * Kullanıcı dokunmaya devam ettikçe kaybolmayı erteler.
     */
    fun keepAlive() {
        cancelFadeOut()
        alphaValue = 255
        updateAlpha(255)
        invalidate()
    }

    /**
     * Belirtilen süre sonra pürüzsüzce kaybolmasını zamanlar.
     */
    fun scheduleFadeOut(delayMs: Long = 2500L) {
        cancelFadeOut()
        fadeRunnable = Runnable {
            fadeAnimator?.cancel()
            fadeAnimator = ValueAnimator.ofInt(255, 0).apply {
                duration = 350
                addUpdateListener {
                    val a = it.animatedValue as Int
                    alphaValue = a
                    updateAlpha(a)
                    invalidate()
                }
                start()
            }
        }
        postDelayed(fadeRunnable, delayMs)
    }

    private fun cancelFadeOut() {
        fadeRunnable?.let { removeCallbacks(it) }
        fadeRunnable = null
        fadeAnimator?.cancel()
    }

    private fun updateAlpha(a: Int) {
        ringPaint.alpha = a
        railPaint.alpha = (a * 0.45f).toInt()
        notchPaint.alpha = (a * 0.70f).toInt()
        sunBodyPaint.alpha = a
        sunRayPaint.alpha = a
        sunGlowPaint.alpha = (a * 0.25f).toInt()
        badgeBgPaint.alpha = (a * 0.65f).toInt()
        textPaint.alpha = a
    }

    fun isRingActive(): Boolean = alphaValue > 0

    /**
     * Verilen (x,y) noktasının halka veya ışık çubuğuna yakın olup olmadığını kontrol eder.
     */
    fun isPointNear(x: Float, y: Float): Boolean {
        if (!isRingActive()) return false
        val dx = x - cx
        val dy = y - cy
        val distSq = dx * dx + dy * dy
        val touchRadius = 90f * density
        return distSq <= touchRadius * touchRadius ||
                (Math.abs(x - sliderX) <= 45f * density && Math.abs(y - cy) <= trackHalfHeight + 30f * density)
    }

    fun hide() {
        cancelFadeOut()
        scaleAnimator?.cancel()
        alphaValue = 0
        updateAlpha(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alphaValue == 0) return

        // 1. ODAK HALKASI
        canvas.drawCircle(cx, cy, radius, ringPaint)
        if (locked) {
            // AF/AE kilitli iken çift halka
            canvas.drawCircle(cx, cy, radius + 7f * density, ringPaint)
        }

        // 2. DİKEY IŞIK RAYI (Slider Track)
        val trackTop = cy - trackHalfHeight
        val trackBottom = cy + trackHalfHeight
        canvas.drawLine(sliderX, trackTop, sliderX, trackBottom, railPaint)

        // Nötr (0 EV) merkez çentiği
        canvas.drawLine(sliderX - 4f * density, cy, sliderX + 4f * density, cy, notchPaint)

        // 3. GÜNEŞ İKONU ☀️
        // exposureProgress: +1.0 ise yukarıda (trackTop), -1.0 ise aşağıda (trackBottom)
        val sunY = cy - (exposureProgress * trackHalfHeight)
        val sunCoreRadius = 6.5f * density
        val sunRayInner = 8.5f * density
        val sunRayOuter = 13.5f * density

        // Dış parıltı halesi
        canvas.drawCircle(sliderX, sunY, sunRayOuter + 4f * density, sunGlowPaint)

        // Güneş çekirdeği
        canvas.drawCircle(sliderX, sunY, sunCoreRadius, sunBodyPaint)

        // 8 Adet Güneş Işını (0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45.0))
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val startX = sliderX + sunRayInner * cosA
            val startY = sunY + sunRayInner * sinA
            val endX = sliderX + sunRayOuter * cosA
            val endY = sunY + sunRayOuter * sinA
            canvas.drawLine(startX, startY, endX, endY, sunRayPaint)
        }

        // 4. EV ETİKETİ (Rozet) — 0 değilse veya kullanıcı ayarlıyorsa göster
        if (exposureLabel.isNotEmpty() && exposureLabel != "0" && exposureLabel != "0.0") {
            val badgeX = if (isFlippedToLeft) sliderX - 32f * density else sliderX + 32f * density
            val badgeW = 34f * density
            val badgeH = 18f * density
            val rect = RectF(
                badgeX - badgeW / 2f,
                sunY - badgeH / 2f,
                badgeX + badgeW / 2f,
                sunY + badgeH / 2f
            )
            canvas.drawRoundRect(rect, 4f * density, 4f * density, badgeBgPaint)
            val textY = sunY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(exposureLabel, badgeX, textY, textPaint)
        }
    }
}

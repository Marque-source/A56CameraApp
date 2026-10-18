package io.github.marquesource.pixora.ui.views

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.marquesource.pixora.R
import io.github.marquesource.pixora.camera.CaptureMode

/**
 * Yatay kaydırmalı çekim modu seçici (Pixel tarzı).
 * Seçili mod sarı renkle vurgulanır; tıklama ile mod değiştirilir.
 */
class ModeSelector @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    var onModeSelected: ((CaptureMode) -> Unit)? = null
    private var selected: CaptureMode = CaptureMode.PHOTO
    private val chips = mutableMapOf<CaptureMode, TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        buildChips()
    }

    private fun buildChips() {
        removeAllViews()
        chips.clear()
        val inflater = LayoutInflater.from(context)
        CaptureMode.ordered.forEach { mode ->
            val chip = inflater.inflate(R.layout.item_mode, this, false) as TextView
            chip.text = context.getString(mode.titleRes)
            chip.setOnClickListener { select(mode) }
            // Yıldız modu kamera açılıp uzun pozlama desteği anlaşılana kadar gizli
            if (mode == CaptureMode.ASTRO) chip.visibility = GONE
            addView(chip)
            chips[mode] = chip
        }
        updateHighlight()
    }

    fun select(mode: CaptureMode) {
        if (selected == mode) return
        selected = mode
        updateHighlight(animate = true)
        onModeSelected?.invoke(mode)
    }

    fun current(): CaptureMode = selected

    /**
     * Telefonun desteklemediği modu gizler (ör. yıldız modu uzun pozlama ister).
     * Gizlenen mod seçiliyse fotoğraf moduna geçilir.
     */
    fun setModeAvailable(mode: CaptureMode, available: Boolean) {
        val chip = chips[mode] ?: return
        chip.visibility = if (available) VISIBLE else GONE
        if (!available && selected == mode) select(CaptureMode.PHOTO)
    }

    private fun updateHighlight(animate: Boolean = false) {
        val selectedBg = ContextCompat.getDrawable(context, R.drawable.bg_mode_pill)
        val selectedColor = Color.BLACK
        val unselectedColor = Color.WHITE

        chips.forEach { (mode, chip) ->
            val isSel = mode == selected
            
            if (isSel) {
                chip.background = selectedBg
                chip.setTextColor(selectedColor)
            } else {
                chip.background = null
                chip.setTextColor(unselectedColor)
            }

            val targetSize = if (isSel) SELECTED_SP else UNSELECTED_SP
            if (animate) {
                chip.animate().cancel()
                chip.scaleX = 1f; chip.scaleY = 1f
                chip.textSize = targetSize
                val from = if (isSel) 0.85f else 1.12f
                chip.scaleX = from; chip.scaleY = from
                chip.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            } else {
                chip.textSize = targetSize
            }
        }
    }

    companion object {
        private const val SELECTED_SP = 15f
        private const val UNSELECTED_SP = 13f
    }
}

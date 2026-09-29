package com.a56camera.camera.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Samsung S24 stok görünümüne yakın renk/ton gradı.
 * Hem gündüz hem gece presetleri içerir.
 */
class S24ColorGrading {

    data class GradeParams(
        val shadowLift: Float,          // gölge parlaklığı kaldırma
        val highlightCompression: Float,// ışık patlaması yumuşatma
        val contrast: Float,            // kontrast
        val saturation: Float,          // doygunluk
        val warmth: Float,              // >0 sarı, <0 mavi
        val tint: Float,                // >0 magenta, <0 yeşil
        val shadowWarmth: Float,        // gölgede renk tonu (soğuk mavi-gri)
        val vibrance: Float             // canlılık
    )

    companion object {
        val DAY = GradeParams(
            shadowLift = 0.04f,
            highlightCompression = 0.10f,
            contrast = 1.08f,
            saturation = 1.12f,
            warmth = 0.03f,
            tint = 0.0f,
            shadowWarmth = -0.02f,
            vibrance = 0.15f
        )

        val NIGHT = GradeParams(
            shadowLift = 0.18f,          // gölgeleri aç (shadow detail)
            highlightCompression = 0.28f,    // lamba/ışık patlamalarını sıkıştır
            contrast = 1.05f,            // dengeli kontrast
            saturation = 1.05f,          // gece renklerini dengede tut
            warmth = 0.06f,              // gece sıcak ışıkları koru
            tint = 0.0f,
            shadowWarmth = -0.10f,       // gölgelere soğuk (mavi-gri) ton
            vibrance = 0.08f
        )
    }

    fun apply(bitmap: Bitmap, params: GradeParams = NIGHT) {
        applyTonemapAndColor(bitmap, params)
    }

    private fun applyTonemapAndColor(bitmap: Bitmap, p: GradeParams) {
        val matrix = ColorMatrix()

        // 1. Doygunluk (Saturation)
        matrix.setSaturation(p.saturation)

        // 2. Renk Sıcaklığı & Gölge Tonlama (Warmth & Shadow Cold-Blue/Warmth)
        val rScale = 1.0f + p.warmth
        val bScale = 1.0f - p.warmth * 0.7f + p.shadowWarmth * 0.2f
        val gScale = 1.0f + p.warmth * 0.1f

        // 3. Kontrast ve Parabolik Gölge Kaldırma (Shadow Lift) - Donanım Hızlandırmalı
        val contrastMatrix = ColorMatrix(floatArrayOf(
            rScale * p.contrast, 0f, 0f, 0f, p.shadowLift * 30f,
            0f, gScale * p.contrast, 0f, 0f, p.shadowLift * 25f,
            0f, 0f, bScale * p.contrast, 0f, p.shadowLift * 15f,
            0f, 0f, 0f, 1f, 0f
        ))
        matrix.postConcat(contrastMatrix)

        // 4. Samsung S24 Mavi/Kırmızı hassas ton ayarı
        val fineTuneMatrix = ColorMatrix(floatArrayOf(
            1.02f, 0.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.00f, 0.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.05f, 0.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f, 0.0f
        ))
        matrix.postConcat(fineTuneMatrix)

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        }
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }

    private fun compressHighlight(v: Float, strength: Float): Float {
        return if (v > 0.7f) {
            v - strength * (v - 0.7f).pow(3f) * 10f
        } else {
            v
        }
    }

    private fun saturate(v: Float): Float {
        return v.coerceIn(0f, 1f)
    }

    private fun applyColorMatrix(bitmap: Bitmap, matrix: ColorMatrix) {
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
    }
}

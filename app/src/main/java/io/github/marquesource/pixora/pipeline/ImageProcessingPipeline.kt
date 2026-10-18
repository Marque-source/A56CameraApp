package io.github.marquesource.pixora.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.net.Uri
import android.util.Log
import io.github.marquesource.pixora.camera.YuvFrame
import io.github.marquesource.pixora.utils.ExifHelper
import io.github.marquesource.pixora.utils.MediaStoreHelper
import org.opencv.android.OpenCVLoader

/**
 * Ana görüntü işleme pipeline'ı.
 */
class ImageProcessingPipeline(private val context: Context) {

    companion object {
        private const val TAG = "ImagePipeline"
        private var opencvInitialized = false
    }

    private val multiFrameProcessor = MultiFrameProcessor()
    private val denoiser = SmartDenoiser()
    val tfliteEngine by lazy { TFLitePipelineEngine(context) }
    val portraitEngine get() = tfliteEngine.portraitEngine
    val zeroDceEngine get() = tfliteEngine.zeroDceEngine

    data class ProcessingResult(
        val bitmap: Bitmap,
        val uri: Uri?,
        val rawUri: Uri? = null,
        val processingTimeMs: Long,
        val frameCount: Int,
        val iso: Int,
        val profile: ToneMapHelper.ColorProfile
    )

    data class PipelineConfig(
        val colorProfile: ToneMapHelper.ColorProfile = ToneMapHelper.ColorProfile.PIXEL_NATURAL,
        val denoiseEnabled: Boolean = true,
        val sharpenEnabled: Boolean = true,
        val hdrEnabled: Boolean = true,
        val portraitBokehEnabled: Boolean = false,
        val lowLightEnhanceEnabled: Boolean = false,
        val expertRawEnabled: Boolean = false,
        val jpegQuality: Int = 98,
        val saveToGallery: Boolean = true
    )

    fun initOpenCV(): Boolean {
        if (opencvInitialized) return true
        opencvInitialized = OpenCVLoader.initDebug()
        if (!opencvInitialized) {
            Log.e(TAG, "OpenCV başlatılamadı!")
        } else {
            Log.d(TAG, "OpenCV başarıyla başlatıldı")
        }
        return opencvInitialized
    }

    fun process(
        frames: List<Pair<YuvFrame, TotalCaptureResult>>,
        config: PipelineConfig = PipelineConfig(),
        location: Location? = null,
        orientation: Int = 0,
        onProgress: ((String) -> Unit)? = null
    ): ProcessingResult {
        require(frames.isNotEmpty()) { "En az 1 kare gerekli" }

        val startTime = System.currentTimeMillis()
        val referenceResult = frames[frames.size / 2].second
        val iso = referenceResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100

        Log.d(TAG, "Pipeline başlıyor: ${frames.size} kare, ISO=$iso, profil=${config.colorProfile.name}")

        DebugDump.prune(context)
        val dump = DebugDump(context, config.colorProfile.name.lowercase())
        if (dump.enabled) {
            dump.text("bilgi.txt", buildString {
                appendLine("kare=${frames.size} iso=$iso profil=${config.colorProfile.name}")
                appendLine("pozlama_ns=${referenceResult.get(CaptureResult.SENSOR_EXPOSURE_TIME)}")
                appendLine("ae=${referenceResult.get(CaptureResult.CONTROL_AE_STATE)} awb=${referenceResult.get(CaptureResult.CONTROL_AWB_STATE)}")
                appendLine("nr=${referenceResult.get(CaptureResult.NOISE_REDUCTION_MODE)} edge=${referenceResult.get(CaptureResult.EDGE_MODE)}")
                appendLine("cihaz=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("bokeh=${config.portraitBokehEnabled} zeroDce=${config.lowLightEnhanceEnabled}")
            })
            frames.forEachIndexed { i, (f, _) ->
                if (i < 5) {
                    val rgb = YuvConverter.toRgbMat(f); val b = org.opencv.core.Mat()
                    org.opencv.imgproc.Imgproc.cvtColor(rgb, b, org.opencv.imgproc.Imgproc.COLOR_RGB2BGR)
                    dump.mat("0_kare$i", b); rgb.release(); b.release()
                }
            }
        }

        // ──── Adım 1: Multi-frame ORB Hizalama & Temporal Fusion (Native BGR Mat) ────
        onProgress?.invoke("HDR birleştirme ve hizalama...")
        val mergeOutput = if (config.hdrEnabled && frames.size > 1) {
            multiFrameProcessor.process(frames, produceRaw16u = config.expertRawEnabled)
        } else {
            val rgb = YuvConverter.toRgbMat(frames[0].first)
            val bgr = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(rgb, bgr, org.opencv.imgproc.Imgproc.COLOR_RGB2BGR)
            rgb.release()
            val raw16u = if (config.expertRawEnabled) {
                val r16 = org.opencv.core.Mat()
                bgr.convertTo(r16, org.opencv.core.CvType.CV_16UC3, 257.0)
                r16
            } else null
            MultiFrameProcessor.ProcessOutput(bgr, raw16u)
        }
        val mergedMat = mergeOutput.mergedMat8u
        val rawMat16u = mergeOutput.rawMat16u
        dump.mat("1_birlesik", mergedMat)
        if (dump.enabled && config.colorProfile == ToneMapHelper.ColorProfile.NIGHT) {
            // Karşılaştırma için yeni gece ton yolu (kaydedilen fotoğrafı etkilemez)
            val alt = NightTone.render(mergedMat)
            val altDen = denoiser.denoise(alt, iso)
            val altOut = ToneMapHelper.applyAdaptiveAcutance(altDen, strength = 0.15, haloThreshold = 24.0, noiseFloor = 4.0)
            dump.mat("9_yeni_gece_ton", altOut)
            alt.release(); if (altDen !== alt) altDen.release(); altOut.release()
        }

        // ──── Adım 2: Akıllı Denoise (Doğrudan Mat üzerinde - Sıfır Ara Bitmap Çevrimi!) ────
        onProgress?.invoke("Gürültü azaltma...")
        val tDen0 = System.currentTimeMillis()
        val denoisedMat = if (config.denoiseEnabled) {
            val res = denoiser.denoise(mergedMat, iso)
            if (res !== mergedMat) mergedMat.release()
            res
        } else {
            mergedMat
        }
        val tDenoise = System.currentTimeMillis() - tDen0
        dump.mat("2_gurultu_azaltma", denoisedMat)

        // ──── Adım 3: Renk Profili & Ton Haritalama (Doğrudan Mat üzerinde) ────
        onProgress?.invoke("Renk işleme...")
        val tTm0 = System.currentTimeMillis()
        val coloredMat = if (config.colorProfile != ToneMapHelper.ColorProfile.NEUTRAL) {
            val res = ToneMapHelper.applyColorProfile(denoisedMat, config.colorProfile, referenceResult)
            if (res !== denoisedMat) denoisedMat.release()
            res
        } else {
            denoisedMat
        }
        val tToneMap = System.currentTimeMillis() - tTm0
        dump.mat("3_renk_ton", coloredMat)

        // ──── Adım 4: Adaptive Edge-Aware Akütans (Halo-Free Doğal Optik Keskinlik) ────
        val tAcu0 = System.currentTimeMillis()
        val finalMat = if (config.sharpenEnabled) {
            val acutanceStrength = when {
                iso >= 2000 -> 0.26
                iso >= 1200 -> 0.30
                iso >= 600 -> 0.34
                else -> 0.38
            }
            val sharp = ToneMapHelper.applyAdaptiveAcutance(coloredMat, strength = acutanceStrength, haloThreshold = 24.0)
            coloredMat.release()
            sharp
        } else {
            coloredMat
        }
        val tAcutance = System.currentTimeMillis() - tAcu0
        dump.mat("4_keskinlik", finalMat)

        // Not: Eskiden burada "Smart Grain" ile bilerek rastgele gren ekleniyordu (gece modunda her zaman,
        // diğer modlarda ISO 800+). Kumlanma şikayetinin doğrudan nedeni olduğu için kaldırıldı.

        // Yalnızca TEK SEFERDE son Bitmap'e çevir! (Sıfır sis, sıfır ara kayıp, saf ham optik netlik)
        val tM2B0 = System.currentTimeMillis()
        var currentBitmap = YuvConverter.matToBitmap(finalMat)
        val tMat2Bmp = System.currentTimeMillis() - tM2B0
        finalMat.release()

        Log.i(TAG, "⏱️ [Orta Pipeline Dağılımı] Denoise: ${tDenoise}ms | Ton Haritalama: ${tToneMap}ms | Akütans: ${tAcutance}ms | Mat→Bitmap: ${tMat2Bmp}ms")

        // ──── Adım 4: 3D Portre Bokeh (MiDaS v2.1 GPU) ────
        if (config.portraitBokehEnabled) {
            onProgress?.invoke("3D Derinlik Bokeh (MiDaS)...")
            try {
                val bokehBmp = portraitEngine.applyPortraitBokeh(currentBitmap)
                if (bokehBmp !== currentBitmap) {
                    currentBitmap.recycle()
                    currentBitmap = bokehBmp
                }
            } catch (e: Exception) {
                Log.w(TAG, "Portre bokeh hatası, orijinal kullanılıyor", e)
            }
        }

        // ──── Adım 5: Zero-DCE AI Gece Görüşü (yalnızca gerçekten karanlık kalmışsa) ────
        if (config.lowLightEnhanceEnabled) {
            // Önce mevcut parlaklığı ölç (hızlı 64x64 thumbnail ile) — zaten yeterince aydınlıksa Zero-DCE atla
            val thumb = Bitmap.createScaledBitmap(currentBitmap, 64, 64, false)
            val probeZdc = org.opencv.core.Mat(64, 64, org.opencv.core.CvType.CV_8UC4)
            org.opencv.android.Utils.bitmapToMat(thumb, probeZdc)
            thumb.recycle()
            val probeMean = org.opencv.core.Core.mean(probeZdc).`val`.take(3).average()
            probeZdc.release()

            if (probeMean < 50.0) {
                onProgress?.invoke("Zero-DCE Gece Görüşü (AI)...")
                Log.i(TAG, "🤖 [Zero-DCE] Başlıyor (meanBrightness=%.1f < 50, aydınlatma gerekli)...".format(probeMean))
                try {
                    val tZero = System.currentTimeMillis()
                    val enhanced = zeroDceEngine.enhanceLowLight(currentBitmap, intensity = 0.40f)
                    if (enhanced !== currentBitmap) {
                        currentBitmap.recycle()
                        currentBitmap = enhanced
                    }
                    Log.i(TAG, "🤖 [Zero-DCE] Tamamlandı (${System.currentTimeMillis() - tZero}ms)")
                } catch (e: Exception) {
                    Log.w(TAG, "Zero-DCE aydınlatma hatası", e)
                }
            } else {
                Log.i(TAG, "🤖 [Zero-DCE] Atlandı (meanBrightness=%.1f ≥ 50, yeterince aydınlık)".format(probeMean))
            }
        } else {
            Log.i(TAG, "🤖 [Zero-DCE] Atlandı (lowLightEnhanceEnabled=false)")
        }

        // Not: Eskiden 2x ve üzeri zoomda burada "Real-ESRGAN x4 süper çözünürlük" çalışıyordu. Model 128x128
        // girdi aldığı için BÜTÜN fotoğrafı 128x128'e küçültüp 512x512 olarak kaydediyordu. Kaldırıldı.

        if (dump.enabled) {
            val fm = org.opencv.core.Mat(); org.opencv.android.Utils.bitmapToMat(currentBitmap, fm)
            org.opencv.imgproc.Imgproc.cvtColor(fm, fm, org.opencv.imgproc.Imgproc.COLOR_RGBA2BGR)
            dump.mat("5_son", fm); fm.release()
        }

        var savedRawUri: Uri? = null
        try {
            if (config.saveToGallery && config.expertRawEnabled && rawMat16u != null) {
                onProgress?.invoke("16-Bit Linear DNG kaydediliyor...")
                savedRawUri = saveDngToGallery(
                    raw16u = rawMat16u,
                    iso = iso,
                    exposureNs = referenceResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L
                )
            }
        } finally {
            rawMat16u?.release()
        }

        var savedUri: Uri? = null
        if (config.saveToGallery) {
            onProgress?.invoke("Kaydediliyor...")
            savedUri = saveToGallery(
                bitmap = currentBitmap,
                quality = config.jpegQuality,
                captureResult = referenceResult,
                location = location,
                orientation = orientation
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Pipeline tamamlandı: ${elapsed}ms")

        return ProcessingResult(
            bitmap = currentBitmap,
            uri = savedUri,
            rawUri = savedRawUri,
            processingTimeMs = elapsed,
            frameCount = frames.size,
            iso = iso,
            profile = config.colorProfile
        )
    }

    private fun saveDngToGallery(
        raw16u: org.opencv.core.Mat,
        iso: Int,
        exposureNs: Long
    ): Uri? {
        val name = MediaStoreHelper.timestampName("RAW")
        val uri = MediaStoreHelper.createDngUri(context, name) ?: return null
        try {
            MediaStoreHelper.openOutput(context, uri)?.use { os ->
                LinearDngWriter.writeDng(raw16u, iso, exposureNs, os)
            }
            MediaStoreHelper.finalizePending(context, uri)
            Log.i(TAG, "🌟 [16-Bit Linear DNG] Galeriye kaydedildi: $uri")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "DNG kaydetme hatası", e)
            return null
        }
    }

    fun autoConfig(iso: Int, exposureNs: Long): PipelineConfig {
        return when {
            iso > 1600 || exposureNs > 50_000_000L -> PipelineConfig(
                colorProfile = ToneMapHelper.ColorProfile.NIGHT,
                denoiseEnabled = true,
                hdrEnabled = true,
                lowLightEnhanceEnabled = false
            )
            iso > 400 -> PipelineConfig(
                colorProfile = ToneMapHelper.ColorProfile.PIXEL_NATURAL,
                denoiseEnabled = true,
                hdrEnabled = true
            )
            else -> PipelineConfig(
                colorProfile = ToneMapHelper.ColorProfile.PIXEL_NATURAL,
                denoiseEnabled = false,
                hdrEnabled = true
            )
        }
    }

    /** Dışarıdan (ör. RAW gece yolu) üretilmiş görüntüyü aynı kayıt akışıyla galeriye yazar. */
    fun saveJpeg(bitmap: Bitmap, quality: Int, captureResult: TotalCaptureResult?, location: Location?, orientation: Int): Uri? =
        saveToGallery(bitmap, quality, captureResult, location, orientation)

    private fun saveToGallery(
        bitmap: Bitmap,
        quality: Int,
        captureResult: TotalCaptureResult?,
        location: Location?,
        orientation: Int
    ): Uri? {
        val tSave0 = System.currentTimeMillis()
        val name = MediaStoreHelper.timestampName("HDR")
        val uri = MediaStoreHelper.createImageUri(context, "$name.jpg") ?: return null

        val tRot0 = System.currentTimeMillis()
        val rotatedBitmap = if (orientation != 0) {
            val matrix = android.graphics.Matrix().apply { postRotate(orientation.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
        val tRotate = System.currentTimeMillis() - tRot0

        try {
            val tComp0 = System.currentTimeMillis()
            MediaStoreHelper.openOutput(context, uri)?.use { os ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
            }
            val tCompress = System.currentTimeMillis() - tComp0

            if (rotatedBitmap !== bitmap) {
                rotatedBitmap.recycle()
            }

            val tFin0 = System.currentTimeMillis()
            MediaStoreHelper.finalizePending(context, uri)
            val tFinalize = System.currentTimeMillis() - tFin0

            val tExif0 = System.currentTimeMillis()
            try {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    ExifHelper.writeCaptureMetadata(pfd.fileDescriptor, captureResult, location, 0)
                }
            } catch (_: Exception) {}
            val tExif = System.currentTimeMillis() - tExif0

            val tTotalSave = System.currentTimeMillis() - tSave0
            Log.i(TAG, "⏱️ [Kaydetme Dağılımı] TOPLAM: ${tTotalSave}ms | Döndürme: ${tRotate}ms | JPEG Sıkıştırma (%${quality}): ${tCompress}ms | MediaStore Finalize: ${tFinalize}ms | EXIF Yazma: ${tExif}ms")
            return uri
        } catch (e: Exception) {
            Log.e(TAG, "Kaydetme hatası", e)
            return null
        }
    }
}

package com.a56camera.pipeline

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.pow

/**
 * Tonemap eğrisi ve renk bilimi (color science) yönetimi.
 *
 * Samsung S25 / iPhone 14 tarzı "canlı ama doğal" görünüm için
 * S-curve tonemap + renk doygunluk ayarı uygular.
 */
object ToneMapHelper {

    private const val TAG = "ToneMapHelper"

    /** Gece CLAHE kırpma sınırı (eskiden 3.0). */
    const val NIGHT_CLAHE_CLIP = 1.2
    /** Gece parlatma eğrisi üssü (eskiden 0.72); CLAHE azalınca kaybolan parlaklığı telafi eder. */
    const val NIGHT_GAMMA = 0.62

    /** Önceden tanımlanmış renk profilleri */
    enum class ColorProfile(
        val gamma: Float,
        val contrast: Float,
        val saturation: Float,
        val warmth: Float,        // >0 sıcak, <0 soğuk
        val shadowLift: Float,    // gölgeleri kaldırma miktarı
        val highlightRoll: Float, // highlight rolloff
        val displayNameTr: String = ""
    ) {
        /** Google Pixel Real Tone & Doğal — Gerçekçi ten renkleri, derin temiz gölgeler, doğal renk bilimi */
        PIXEL_NATURAL(
            gamma = 0.92f, contrast = 1.10f, saturation = 1.02f,
            warmth = 0.000f, shadowLift = 0.015f, highlightRoll = 0.96f,
            displayNameTr = "Pixora Doğal"
        ),
        /** Leica Monokrom (True B&W) — Kadifemsi ton geçişli, yüksek dinamik aralıklı analog siyah-beyaz */
        PRO_MONOCHROME(
            gamma = 0.92f, contrast = 1.28f, saturation = 0.00f,
            warmth = 0.000f, shadowLift = 0.005f, highlightRoll = 0.90f,
            displayNameTr = "Monokrom B&W"
        ),
        /** Pro Flat / Sinematik Log — Düzenleme (Lightroom) için maksimum dinamik aralık ve düz ton eğrisi */
        PRO_CINEMA_LOG(
            gamma = 1.05f, contrast = 0.88f, saturation = 0.90f,
            warmth = 0.000f, shadowLift = 0.060f, highlightRoll = 0.88f,
            displayNameTr = "Flat Log"
        ),
        /** Gece modu — ferah gölgeler, dengeli parlaklık ve doğal gece sıcaklığı */
        NIGHT(
            gamma = 0.74f, contrast = 1.05f, saturation = 1.02f,
            warmth = 0.010f, shadowLift = 0.015f, highlightRoll = 0.95f,
            displayNameTr = "Gece Modu"
        ),
        /** Doğal — minimum müdahale */
        NEUTRAL(
            gamma = 1.0f, contrast = 1.0f, saturation = 1.0f,
            warmth = 0f, shadowLift = 0f, highlightRoll = 1.0f,
            displayNameTr = "Doğal"
        )
    }

    fun createCurve(profile: ColorProfile, points: Int = 64): TonemapCurve {
        val curve = generateSCurve(
            points = points,
            gamma = profile.gamma,
            contrast = profile.contrast,
            shadowLift = profile.shadowLift,
            highlightRoll = profile.highlightRoll
        )
        return TonemapCurve(curve, curve, curve)
    }

    fun applyToRequest(
        builder: CaptureRequest.Builder,
        profile: ColorProfile = ColorProfile.PIXEL_NATURAL
    ) {
        builder.set(
            CaptureRequest.TONEMAP_MODE,
            CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE
        )
        builder.set(CaptureRequest.TONEMAP_CURVE, createCurve(profile))
    }

    /**
     * Google Pixel & Apple Photonic Sahne-Adaptif Renk Sıcaklığı (AWB / CCT):
     * Sabit warmth eklemek yerine referans karenin gerçek sensör kazançlarına (COLOR_CORRECTION_GAINS) bakar.
     * Ortam zaten akkor / sarı sokak lambasıyla aydınlanıyorsa (CCT < 3200K, bGain / rGain > 1.35),
     * gereksiz yapay sarartmayı (warmth) 0'a indirir ve sahnenin neon sarı / turuncu çamura dönmesini engeller.
     */
    fun computeAdaptiveWarmth(profileWarmth: Float, captureResult: TotalCaptureResult?): Float {
        if (profileWarmth == 0f || captureResult == null) return profileWarmth
        val gains = captureResult.get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return profileWarmth
        val rGain = gains.red
        val bGain = gains.blue
        if (rGain <= 0.001f) return profileWarmth

        val ratio = bGain / rGain
        return when {
            ratio > 1.45f -> 0.0f                 // Aşırı sıcak sokak lambası (CCT < 3000K) -> sıcaklık ekleme!
            ratio > 1.20f -> profileWarmth * 0.25f // Sıcak iç mekan (CCT ~3500K) -> sıcaklığı %75 kıs
            ratio < 0.85f -> profileWarmth * 1.3f  // Soğuk/Mavi akşamüstü (CCT > 6500K) -> hafif ısıt
            else -> profileWarmth
        }
    }

    /**
     * BGR Mat üzerine Apple / Leica / Hasselblad renk bilimi uygular.
     */
    fun applyColorProfile(src: Mat, profile: ColorProfile, captureResult: TotalCaptureResult? = null): Mat {
        Log.i(TAG, "🎨 [applyColorProfile] ÇAĞRILDI -> İstenen Profil: ${profile.name}")
        val result = src.clone()

        when (profile) {
            ColorProfile.NIGHT -> {
                applyNightColorScience(result, profile)
            }
            ColorProfile.PRO_MONOCHROME -> {
                applyMonochromeColorScience(result, profile)
            }
            ColorProfile.PRO_CINEMA_LOG -> {
                applyLogColorScience(result, profile)
            }
            ColorProfile.NEUTRAL -> {
                // Saf donanım ISP çıktısı - dokunulmaz
            }
            else -> {
                // Varsayılan: Google Pixel Real Tone & Doğal Renk Bilimi
                applyPixelRealToneColorScience(result, profile, captureResult)
            }
        }

        return result
    }

    /**
     * Google Pixel Real Tone & Doğal Renk Bilimi:
     * 1. Samsung ISP'nin yapay sarı/turuncu ten rengi basmasını nötrler (Real Tone).
     * 2. LAB uzayında L kanalına hafif mikro-kontrast ve doğal dinamik aralık eğrisi uygular.
     * 3. Gökyüzü mavisini ve doğa yeşilini yapay neon tonlardan arındırıp gerçekçi Pixel tonlarına oturtur.
     */
    fun applyPixelRealToneColorScience(
        bgr: Mat,
        profile: ColorProfile = ColorProfile.PIXEL_NATURAL,
        captureResult: TotalCaptureResult? = null
    ) {
        if (bgr.empty()) return

        // 1. LAB renk uzayında hassas ton haritalama (renkleri bozmadan sadece Luma)
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val planes = mutableListOf<Mat>()
        Core.split(lab, planes)

        val lChannel = planes[0]

        // Pixel tarzı hafif kontrast ve gölge netliği (LUT)
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val lutData = ByteArray(256)
        for (i in 0..255) {
            val norm = i / 255.0
            val centered = (norm - 0.5) * 1.10
            val sig = 1.0 / (1.0 + Math.exp(-centered * 4.2))
            val corrected = Math.pow(sig, 0.94)
            val lifted = corrected * 0.988 + 0.012
            lutData[i] = (lifted.coerceIn(0.0, 1.0) * 255.0).toInt().toByte()
        }
        lut.put(0, 0, lutData)
        Core.LUT(lChannel, lut, lChannel)
        lut.release()

        Core.merge(planes, lab)
        Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
        lab.release()
        planes.forEach { it.release() }
        // Not: Burada ayrıca keskinleştirme yapılmıyor. Çağıran taraf (pipeline / tryFinishCapture)
        // zaten applyAdaptiveAcutance uyguluyor; iki kez keskinleştirmek gürültüyü de iki kez büyütüyordu.
    }

    /**
     * Google Night Sight & Apple Photonic Gece Bilimi:
     * LAB uzayında renkleri (a, b) hiç bozmadan Luminance (L) kanalında
     * adaptif gölge kaldırma (Shadow Lift) ve doku kurtarma uygular.
     * Noktasal LUT olduğu için sıfır hale (halo-free), a/b korunduğu için sıfır renk sapması!
     */
    private fun applyNightColorScience(bgr: Mat, profile: ColorProfile) {
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "🔍 [Tip Kontrolü] mat.type()=${bgr.type()} (CV_8UC3 beklenir: ${CvType.CV_8UC3})")
        require(bgr.type() == CvType.CV_8UC3) { "Beklenmeyen Mat tipi: ${bgr.type()}, 8-bit BGR bekleniyordu!" }
        Log.i(TAG, "🌙 [applyNightColorScience] BAŞLADI - Giriş boyutu: ${bgr.cols()}x${bgr.rows()}")

        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val labPlanes = mutableListOf<Mat>()
        Core.split(lab, labPlanes)

        val lChannel = labPlanes[0] // 0..255 parlaklık kanalı

        // 1. Ortalama ve Min/Max parlaklığı ölç (Öncesi)
        val initialStats = Core.minMaxLoc(lChannel)
        val meanL = Core.mean(lChannel).`val`[0]
        Log.i(TAG, "📊 [L-Giriş] meanL=%.2f, minL=%.1f, maxL=%.1f".format(meanL, initialStats.minVal, initialStats.maxVal))

        // 2. Lokal Ton Haritalama (12x12 CLAHE):
        // Kırpma sınırı 3.0 iken düz karanlık alanlarda gürültüyü 3.5 kat büyütüyor, sinyal/gürültü
        // oranını yarıya indiriyordu. 1.2 hafif yerel kontrast verir; parlatmayı aşağıdaki eğri yapar.
        val clahe = Imgproc.createCLAHE(NIGHT_CLAHE_CLIP, Size(12.0, 12.0))
        clahe.apply(lChannel, lChannel)

        // 3. Shadow Lift Curve, Sky Black Anchor & Highlight Soft-Knee:
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val lutData = ByteArray(256)
        for (i in 0..255) {
            val norm = i / 255.0

            // A. Gölge Kaldırma (Google Night Sight & Apple Photonic Luma Eğrisi):
            // norm^0.72 sayesinde karanlıkta kalan ağaç, bina ve arabalar görünür hale gelir.
            var v = Math.pow(norm, NIGHT_GAMMA)

            // B. Kadife Gökyüzü Sabitleyici (Sadece gerçek gece gökyüzü < 0.035 için siyahı kilitler, ağaçları ezmez!)
            if (norm < 0.035) {
                val ratio = norm / 0.035
                v = v * (ratio * ratio)
            }

            // C. Highlight Soft-Knee Sıkıştırma (Işık kaynaklarını patlatmadan yumuşak tutar)
            if (v > 0.80) {
                val excess = v - 0.80
                v = 0.80 + 0.20 * (1.0 - exp(-excess / 0.20 * 2.0))
            }

            lutData[i] = (v * 255.0).toInt().coerceIn(0, 255).toByte()
        }
        lutData[0] = 0.toByte()
        lut.put(0, 0, lutData)
        Core.LUT(lChannel, lut, lChannel)
        lut.release()

        // LAB -> BGR geri birleştir (a ve b kanalları korunduğu için renkler tamamen doğaldır)
        Core.merge(labPlanes, lab)
        Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
        lab.release()
        labPlanes.forEach { it.release() }

        // 4. Highlight Desaturation (Sadece aşırı parlak ampul merkezlerini saf beyaza yumuşat, sahayı söndürme!)
        applyHighlightDesaturation(bgr, threshold = 225.0)

        // 5. Doğal hafif sıcaklık dengesi (Leica / Apple Amber Altın Ambiyans)
        if (profile.warmth != 0f) {
            applyWarmth(bgr, profile.warmth)
        }

        val elapsed = System.currentTimeMillis() - t0
        Log.i(TAG, "🏁 [applyNightColorScience] TAMAMLANDI (${elapsed}ms)")
    }

    /**
     * Apple Photonic & Google Pixel Highlight Desaturation:
     * Aşırı parlak ışık kaynaklarının (sokak lambası, araba farı, tabela ampulü) merkezindeki
     * neon sarı doygunluğunu kademeli olarak saf optik beyaza doğru yumuşatır.
     * Lamba etrafındaki ağaç yaprakları sarı bir çamur olmak yerine yeşil-altın yaprak
     * dokularını korur, lamba ampulü ise sinematik bir haleyle doğal görünür.
     */
    fun applyHighlightDesaturation(bgr: Mat, threshold: Double = 195.0) {
        val hsv = Mat()
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvPlanes = mutableListOf<Mat>()
        Core.split(hsv, hsvPlanes)

        val sChannel = hsvPlanes[1] // Saturation (0..255)
        val vChannel = hsvPlanes[2] // Value / Brightness (0..255)

        val vFloat = Mat()
        vChannel.convertTo(vFloat, CvType.CV_32F)
        val sFloat = Mat()
        sChannel.convertTo(sFloat, CvType.CV_32F)

        // excess = max(0, V - threshold)
        val vExcess = Mat()
        Core.subtract(vFloat, Scalar(threshold), vExcess)
        Core.max(vExcess, Scalar(0.0), vExcess)

        // normExcess = excess / (255.0 - threshold) -> [0.0, 1.0]
        val range = (255.0 - threshold).coerceAtLeast(1.0)
        val normExcess = Mat()
        Core.multiply(vExcess, Scalar(1.0 / range), normExcess)

        // multiplier = max(0, 1.0 - normExcess)
        val ones = Mat(normExcess.size(), normExcess.type(), Scalar(1.0))
        val satMultiplier = Mat()
        Core.subtract(ones, normExcess, satMultiplier)
        Core.max(satMultiplier, Scalar(0.0), satMultiplier)

        // newS = sFloat * satMultiplier
        val newSFloat = Mat()
        Core.multiply(sFloat, satMultiplier, newSFloat)
        newSFloat.convertTo(sChannel, CvType.CV_8U)

        // Temizlik
        vFloat.release()
        sFloat.release()
        vExcess.release()
        normExcess.release()
        ones.release()
        satMultiplier.release()
        newSFloat.release()

        Core.merge(hsvPlanes, hsv)
        Imgproc.cvtColor(hsv, bgr, Imgproc.COLOR_HSV2BGR)
        hsv.release()
        hsvPlanes.forEach { it.release() }
    }

    /**
     * Apple Photonic Engine Renk Bilimi:
     * 1. LAB renk uzayında lokal mikro-kontrast (3D derinlik & doku koruma)
     * 2. Filmik S-Curve (derin siyahlar + yumuşak highlight roll-off)
     * 3. Seçici şeftali-sıcak ten rengi tonlaması (soluk/yeşil teni engeller)
     * 4. Doğal zengin yeşillik ve gökyüzü mavisi
     */
    private fun applyPhotonicColorScience(bgr: Mat, profile: ColorProfile, captureResult: TotalCaptureResult? = null) {
        Log.i(TAG, "🍎 [applyPhotonicColorScience] ÇAĞRILDI -> Profil: ${profile.name}")
        // 1. LAB Uzayında Mikro-Kontrast (Texture Pop)
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val labPlanes = mutableListOf<Mat>()
        Core.split(lab, labPlanes)

        // L (Parlaklık) kanalına adaptif mikro-kontrast ver:
        // Gece çekimlerinde (NIGHT) 12x12 CLAHE bulutları ve gökyüzünü pastel boya gibi parçaladığı için,
        // çok yumuşak 1.05x ve 8x8 karo boyutu kullanılır (sıfır lekelenme, kadife gökyüzü!)
        val claheLimit = if (profile == ColorProfile.NIGHT) 1.05 else 1.08
        val gridSize = org.opencv.core.Size(8.0, 8.0)
        val clahe = Imgproc.createCLAHE(claheLimit, gridSize)
        clahe.apply(labPlanes[0], labPlanes[0])

        Core.merge(labPlanes, lab)
        Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
        lab.release()
        labPlanes.forEach { it.release() }

        // 2. Filmik S-Curve & Gamma LUT
        applyPhotonicCurve(bgr, profile)

        // 3. Sahne-Adaptif Beyaz Dengesi ve Cilt Tonu Koruma (CCT < 3000K ise sıcaklık ekleme!)
        val effectiveWarmth = computeAdaptiveWarmth(profile.warmth, captureResult)
        if (effectiveWarmth != 0f) {
            applySelectiveColors(bgr, effectiveWarmth)
        }
    }

    /**
     * Leica Monokrom Renk Bilimi:
     * Renkli görüntüyü CIE fotometrik parlaklık ağırlıklarıyla (0.299R + 0.587G + 0.114B)
     * analog siyah-beyaza çevirir, ardından Leica M Monochrom tarzı kadifemsi derin
     * kontrast eğrisi uygular. Sıfır renk gürültüsü, jilet gibi optik ton zenginliği!
     */
    private fun applyMonochromeColorScience(bgr: Mat, profile: ColorProfile) {
        Log.i(TAG, "🖤 [applyMonochromeColorScience] ÇAĞRILDI -> Leica Monokrom Tonlama")
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)

        // LAB mikro-kontrast dokusu
        val clahe = Imgproc.createCLAHE(1.15, org.opencv.core.Size(8.0, 8.0))
        clahe.apply(gray, gray)

        // Leica S-Curve kontrast eğrisi
        applyPhotonicCurve(gray, profile)

        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
        gray.release()
    }

    /**
     * Pro Flat / Cinema Log Renk Bilimi:
     * Gölgeleri kaldırarak ve parlak alanları yumuşatarak fotoğraf düzenleme
     * yazılımları (Lightroom, Snapseed) için maksimum dinamik aralığı korur.
     */
    private fun applyLogColorScience(bgr: Mat, profile: ColorProfile) {
        Log.i(TAG, "🎬 [applyLogColorScience] ÇAĞRILDI -> Flat Log Dinamik Aralık")
        applyPhotonicCurve(bgr, profile)
    }

    private fun generateSCurve(
        points: Int,
        gamma: Float,
        contrast: Float,
        shadowLift: Float,
        highlightRoll: Float
    ): FloatArray {
        val curve = FloatArray(points * 2)
        for (i in 0 until points) {
            val x = i.toFloat() / (points - 1)

            val centered = (x - 0.5f) * contrast
            val sigmoid = (1.0f / (1.0f + exp((-centered * 5.0f).toDouble()))).toFloat()

            var y = sigmoid.pow(gamma)

            y = y * (1f - shadowLift) + shadowLift

            if (y > highlightRoll) {
                val excess = y - highlightRoll
                y = highlightRoll + excess * 0.5f
            }

            curve[i * 2] = x
            curve[i * 2 + 1] = y.coerceIn(0f, 1f)
        }
        curve[0] = 0f; curve[1] = shadowLift.coerceIn(0f, 0.05f)
        curve[(points - 1) * 2] = 1f; curve[(points - 1) * 2 + 1] = 1f
        return curve
    }

    private fun applyGamma(mat: Mat, gamma: Float) {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val lutData = ByteArray(256)
        for (i in 0..255) {
            val normalized = i / 255.0
            val corrected = normalized.pow(gamma.toDouble()) * 255.0
            lutData[i] = corrected.toInt().coerceIn(0, 255).toByte()
        }
        lut.put(0, 0, lutData)
        Core.LUT(mat, lut, mat)
        lut.release()
    }

    private fun applySaturation(mat: Mat, factor: Float) {
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_BGR2HSV)

        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels)

        channels[1].convertTo(channels[1], CvType.CV_32F)
        Core.multiply(channels[1], Scalar(factor.toDouble()), channels[1])
        channels[1].convertTo(channels[1], CvType.CV_8U)

        Core.merge(channels, hsv)
        Imgproc.cvtColor(hsv, mat, Imgproc.COLOR_HSV2BGR)

        hsv.release()
        channels.forEach { it.release() }
    }

    private fun applyWarmth(mat: Mat, warmth: Float) {
        if (warmth == 0f) return
        val channels = mutableListOf<Mat>()
        Core.split(mat, channels)

        // Multiplicative Kelvin Beyaz Dengesi:
        // Toplamsal kaydırma (Add) gölgeleri çamurlaştırıp pastel boya yapar.
        // Çarpımsal kazanç (Multiply) ise gerçek optik filtre gibi çalışır:
        // Siyahlar saf siyah kalır (0 * gain = 0), renkler doğallığını kaybetmez!
        val rGain = (1.0 + warmth.toDouble()).coerceIn(0.8, 1.2)
        val bGain = (1.0 - warmth.toDouble()).coerceIn(0.8, 1.2)

        channels[2].convertTo(channels[2], CvType.CV_32F)
        channels[0].convertTo(channels[0], CvType.CV_32F)

        Core.multiply(channels[2], Scalar(rGain), channels[2])
        Core.multiply(channels[0], Scalar(bGain), channels[0])

        channels[2].convertTo(channels[2], CvType.CV_8U)
        channels[0].convertTo(channels[0], CvType.CV_8U)

        Core.merge(channels, mat)
        channels.forEach { it.release() }
    }

    private fun applyPhotonicCurve(mat: Mat, profile: ColorProfile) {
        val lut = Mat(1, 256, CvType.CV_8UC1)
        val lutData = ByteArray(256)

        // Sigmoid uç noktalarını hesapla (0.0 tam siyah, 1.0 tam beyaz olsun)
        val s0 = 1.0 / (1.0 + exp(0.5 * profile.contrast * 5.2))
        val s1 = 1.0 / (1.0 + exp(-0.5 * profile.contrast * 5.2))
        val sRange = (s1 - s0).coerceAtLeast(0.001)

        for (i in 0..255) {
            val x = i / 255.0

            // 1. Sigmoid S-Curve Midtone Contrast (Normalize: [0, 1])
            val centered = (x - 0.5) * profile.contrast
            val rawSigmoid = 1.0 / (1.0 + exp(-centered * 5.2))
            val sigmoid = ((rawSigmoid - s0) / sRange).coerceIn(0.0, 1.0)

            // 2. Gamma
            var y = sigmoid.pow(profile.gamma.toDouble())

            // 3. Deep Black Anchor (asla gri gölge bırakmaz)
            y = y * (1.0 - profile.shadowLift) + profile.shadowLift

            // 4. Filmic Highlight Compression (Apple Shoulder Rolloff)
            if (y > profile.highlightRoll) {
                val excess = y - profile.highlightRoll
                val rollRange = 1.0 - profile.highlightRoll
                y = profile.highlightRoll + rollRange * (1.0 - exp(-excess / rollRange * 1.5))
            }

            lutData[i] = (y * 255.0).toInt().coerceIn(0, 255).toByte()
        }

        lut.put(0, 0, lutData)
        Core.LUT(mat, lut, mat)
        lut.release()
    }

    private fun applySelectiveColors(bgr: Mat, warmth: Float) {
        Log.i(TAG, "🎨 [applySelectiveColors] ÇAĞRILDI -> warmth: $warmth")
        val warmShift = (warmth * 255).toInt()
        val bgrChannels = mutableListOf<Mat>()
        Core.split(bgr, bgrChannels)

        // Apple Sıcak Şeftali Cilt Tonu Koruma
        // Kırmızı kanala doğal sıcaklık ekle
        Core.add(bgrChannels[2], Scalar(warmShift * 1.2), bgrChannels[2])
        // Maviyi hafif düşürerek sarılık değil sıcaklık kat
        Core.subtract(bgrChannels[0], Scalar(warmShift * 0.8), bgrChannels[0])

        Core.merge(bgrChannels, bgr)
        bgrChannels.forEach { it.release() }
    }

    /**
     * Adaptive Edge-Aware Unsharp Mask (Akütans / Halo-Free Keskinleştirme):
     * Sabit 1.38x yerine; yüksek kontrastlı kenarlardaki (ağaç dalları, parlak kenarlar)
     * aşırı sapmaları (haloları) sınırlar, mikro dokularda (kumaş, saç teli, gözenek)
     * ise tam optik keskinliği (%100) serbest bırakır.
     */
    fun applyAdaptiveAcutance(
        src: Mat,
        strength: Double = 0.38,
        haloThreshold: Double = 24.0,
        noiseFloor: Double = 3.0
    ): Mat {
        // 4080x3060 (12.5 MP) piksel sıklığında gerçek optik doku sınırlarını yakalamak için 1.15 sigma
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 1.15)

        val srcFloat = Mat()
        val blurFloat = Mat()
        src.convertTo(srcFloat, CvType.CV_32FC3)
        blurred.convertTo(blurFloat, CvType.CV_32FC3)
        blurred.release()

        // delta = src - blurred
        val delta = Mat()
        Core.subtract(srcFloat, blurFloat, delta)
        blurFloat.release()

        // Gürültü eşiği (coring): |delta| < noiseFloor olan küçük farklar gürültüdür, keskinleştirilmez;
        // daha büyük farklardan noiseFloor kadarı düşülür. Eşik yokken her gürültü tanesi de büyütülüyordu.
        if (noiseFloor > 0.0) {
            val absDelta = Mat()
            Core.absdiff(delta, Scalar(0.0, 0.0, 0.0), absDelta)
            val shrunk = Mat()
            Core.subtract(absDelta, Scalar(noiseFloor, noiseFloor, noiseFloor), shrunk)
            Core.max(shrunk, Scalar(0.0, 0.0, 0.0), shrunk)
            // delta = sign(delta) * max(|delta| - floor, 0)  ->  delta * shrunk / |delta|
            val safeAbs = Mat()
            Core.max(absDelta, Scalar(1e-6, 1e-6, 1e-6), safeAbs)
            Core.divide(delta, safeAbs, delta)
            Core.multiply(delta, shrunk, delta)
            listOf(absDelta, shrunk, safeAbs).forEach { it.release() }
        }

        // Halo bastırma: Yüksek kontrastlı sınırlarda delta'yı [-threshold, +threshold] ile sınırla (clamping)
        val clampedDelta = Mat()
        val upper = Scalar(haloThreshold, haloThreshold, haloThreshold)
        val lower = Scalar(-haloThreshold, -haloThreshold, -haloThreshold)
        Core.min(delta, upper, clampedDelta)
        Core.max(clampedDelta, lower, clampedDelta)
        delta.release()

        // sharp = src + strength * clampedDelta
        val scaledDelta = Mat()
        Core.multiply(clampedDelta, Scalar(strength, strength, strength), scaledDelta)
        clampedDelta.release()

        val sharpFloat = Mat()
        Core.add(srcFloat, scaledDelta, sharpFloat)
        srcFloat.release()
        scaledDelta.release()

        val sharp = Mat()
        sharpFloat.convertTo(sharp, CvType.CV_8UC3)
        sharpFloat.release()

        return sharp
    }

    /**
     * OpenCV C++ Katmanı Eşdeğeri Unsharp Mask:
     * addWeighted(input, 1.40, blurred, -0.40, 0) ile UniHAL'in sildiği tuğla,
     * yaprak ve ince hatları sıfır halo ile geri kazandırır.
     */
    fun applyUnsharpMask(mat: Mat, strength: Double = 0.35, sigma: Double = 1.0): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(0.0, 0.0), sigma)
        val sharpened = Mat()
        Core.addWeighted(mat, 1.0 + strength, blurred, -strength, 0.0, sharpened)
        blurred.release()
        return sharpened
    }
}

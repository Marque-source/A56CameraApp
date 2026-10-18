package io.github.marquesource.pixora.pipeline

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RAW gece modu: sensörün ham (Bayer) karelerini hizalayıp birleştirir ve görüntüye dönüştürür.
 *
 * Telefonun işlediği (YUV) karelerde gürültü azaltma, keskinleştirme ve ton eğrisi zaten uygulanmış
 * olduğundan üstüne ikinci kez işlem yapmak "yağlı boya" görünümüne yol açıyordu. Burada her şey
 * ham, doğrusal veride ve tek seferde yapılır:
 *
 *  1. Her kare 4 yarım çözünürlüklü renk düzlemine ayrılır (R, G1, G2, B) — Bayer düzeni bozulmaz.
 *  2. Kareler yeşil düzlem üzerinden faz korelasyonuyla hizalanır (el titremesi).
 *  3. Hareket eden bölgeler gürültü modeline göre ağırlıklandırılarak ortalanır (hayalet yok).
 *  4. Siyah seviyesi, lens gölgelenmesi, beyaz dengesi ve renk matrisi uygulanır.
 *  5. Yarım çözünürlükte RGB oluşturulur (2x2 birleştirme gürültüyü ayrıca yarıya indirir),
 *     gece ton eşleme + gürültü azaltma yapılır, sonra tam çözünürlüğe büyütülür.
 *
 * Android'e bağımlı değildir; masaüstünde gerçek/sentetik karelerle test edilebilir.
 */
object RawNightProcessor {

    /** Bayer düzlem konumundaki renk: 0=R, 1=G (çift satır), 2=G (tek satır), 3=B */
    private const val R = 0; private const val G1 = 1; private const val G2 = 2; private const val B = 3

    /**
     * @param cfa CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR)
     * @param blackLevel [p00, p01, p10, p11] konumlarının siyah seviyesi
     * @param wbGains R, G, B kazançları (COLOR_CORRECTION_GAINS)
     * @param ccm sensör RGB → doğrusal sRGB 3x3 matris, satır öncelikli (COLOR_CORRECTION_TRANSFORM)
     * @param shading lens gölgelenme kazançları, CV_32FC4 (R, G_çift, G_tek, B) küçük ızgara; yoksa null
     * @param noiseScale / noiseOffset normalize sinyal için varyans = scale·x + offset (SENSOR_NOISE_PROFILE, yeşil); yoksa ölçülür
     */
    data class Params(
        val cfa: Int,
        val blackLevel: DoubleArray,
        val whiteLevel: Double,
        val wbGains: DoubleArray,
        val ccm: DoubleArray,
        val shading: Mat? = null,
        val noiseScale: Double? = null,
        val noiseOffset: Double? = null
    )

    data class Result(
        /** 8UC3 BGR, tam çözünürlük */
        val image: Mat,
        /** Birleştirilmiş Bayer verisi, CV_16UC1, orijinal sensör ölçeğinde (DNG için); istenmediyse null */
        val mergedBayer: Mat?,
        val usedFrames: Int,
        val shifts: List<Point>,
        val meanWeight: Double
    )

    /** Düzlem (p00, p01, p10, p11) → renk kanalı eşlemesi. */
    fun planeChannels(cfa: Int): IntArray = when (cfa) {
        0 -> intArrayOf(R, G1, G2, B)   // RGGB
        1 -> intArrayOf(G1, R, B, G2)   // GRBG
        2 -> intArrayOf(G1, B, R, G2)   // GBRG
        3 -> intArrayOf(B, G1, G2, R)   // BGGR
        else -> intArrayOf(R, G1, G2, B)
    }

    /**
     * @param frames CV_16UC1 ham kareler (aynı boyut); ilki referans olarak kullanılır. Bu fonksiyon
     *               kareleri serbest bırakır (bellek tasarrufu).
     */
    fun process(
        frames: MutableList<Mat>,
        p: Params,
        keepMergedBayer: Boolean = false,
        /** null = gürültüye göre otomatik; true/false = zorla (test için) */
        detailTransfer: Boolean? = null,
        /** Yıldız (uzun pozlama) modu: sıcak piksel temizliği, karanlık gökyüzü tonu, tam ayrıntı yok */
        astro: Boolean = false,
        dump: ((String, Mat) -> Unit)? = null
    ): Result {
        require(frames.isNotEmpty())
        val chan = planeChannels(p.cfa)

        // Uzun pozlamada sensörün "sıcak pikselleri" her karede aynı yerde parlak nokta bırakır.
        // Yıldızlar gökyüzü döndükçe kareler arasında kayar, sıcak pikseller kaymaz: tüm karelerin
        // en düşük değeri bile komşularından çok parlaksa o piksel sıcaktır.
        val hotMasks = if (astro && frames.size >= 3) findHotPixels(frames, p) else null
        dump?.let { d -> hotMasks?.let { m -> d("0_sicak_piksel", m[0]) } }

        // 1) Referans düzlemleri (siyah seviyesi çıkarılmış, 0..1)
        val refPlanes = splitPlanes(frames[0], p)
        frames[0].release()
        hotMasks?.let { fixHotPixels(refPlanes, it) }
        val refGreen = green(refPlanes, chan)

        // Gürültü modeli (yeşil düzlem için σ(x))
        val (nS, nO) = noiseModel(refGreen, p)

        val acc = refPlanes.map { it.clone() }.toMutableList()
        val wSum = Mat(refGreen.size(), CvType.CV_32F, Scalar(1.0))
        val shifts = mutableListOf(Point(0.0, 0.0))
        var weightTotal = 0.0

        // Referans yeşili bulanık (hareket karşılaştırması için)
        val refBlur = Mat(); Imgproc.blur(refGreen, refBlur, Size(5.0, 5.0))
        // Beklenen fark gürültüsü: σ_d ≈ √2·σ(x) / 5 (5x5 kutu ortalama)
        val sigmaD = Mat()
        Core.multiply(refBlur, Scalar(nS), sigmaD); Core.add(sigmaD, Scalar(nO), sigmaD)
        Core.max(sigmaD, Scalar(1e-12), sigmaD); Core.sqrt(sigmaD, sigmaD)
        Core.multiply(sigmaD, Scalar(sqrt(2.0) / 5.0), sigmaD)

        for (i in 1 until frames.size) {
            val planes = splitPlanes(frames[i], p)
            frames[i].release()
            hotMasks?.let { fixHotPixels(planes, it) }
            val g = green(planes, chan)
            val shift = estimateShift(refGreen, g)
            shifts.add(shift)

            val warped = planes.map { translate(it, shift) }
            planes.forEach { it.release() }
            val gW = translate(g, shift); g.release()

            // Ağırlık: fark ≤ 4σ → 1, ≥ 8σ → 0, arası doğrusal
            val gBlur = Mat(); Imgproc.blur(gW, gBlur, Size(5.0, 5.0))
            val diff = Mat(); Core.absdiff(gBlur, refBlur, diff)
            val ratio = Mat(); Core.divide(diff, sigmaD, ratio)
            val w = Mat(); Core.subtract(ratio, Scalar(4.0), w)
            Core.multiply(w, Scalar(-0.25), w); Core.add(w, Scalar(1.0), w)
            Core.min(w, Scalar(1.0), w); Core.max(w, Scalar(0.0), w)
            // Hizalamadan sonra kenarda kalan boş bant ağırlık almasın
            zeroBorder(w, shift)
            weightTotal += Core.mean(w).`val`[0]

            for (k in 0 until 4) {
                val t = Mat(); Core.multiply(warped[k], w, t)
                Core.add(acc[k], t, acc[k]); t.release()
            }
            Core.add(wSum, w, wSum)
            listOf(gBlur, diff, ratio, w, gW).forEach { it.release() }
            warped.forEach { it.release() }
        }
        frames.clear()
        refPlanes.forEach { it.release() }
        refGreen.release(); refBlur.release(); sigmaD.release()

        for (k in 0 until 4) Core.divide(acc[k], wSum, acc[k])
        wSum.release()

        val mergedBayer = if (keepMergedBayer) interleave(acc, p) else null

        // 2) Lens gölgelenmesi + beyaz dengesi (kanal başına)
        val gainFor = doubleArrayOf(p.wbGains[0], p.wbGains[1], p.wbGains[1], p.wbGains[2])
        val shadingUp = p.shading?.let { upsampleShading(it, acc[0].size()) }
        for (k in 0 until 4) {
            val c = chan[k]
            shadingUp?.let { Core.multiply(acc[k], it[c], acc[k]) }
            Core.multiply(acc[k], Scalar(gainFor[c]), acc[k])
        }
        shadingUp?.forEach { it.release() }

        // Işık yeterliyse (birleşik karede gürültü düşük) tam çözünürlüklü ayrıntı sonradan geri eklenir;
        // karanlıkta yarım çözünürlük (2x2 birleştirme) gürültüyü bastırdığı için olduğu gibi kalır.
        val mergedSigma = sqrt((nS * 0.18 + nO).coerceAtLeast(0.0)) / sqrt(shifts.size.toDouble())
        hotMasks?.forEach { it.release() }
        val useDetail = detailTransfer ?: (!astro && mergedSigma < DETAIL_SIGMA_MAX)
        val detail = if (useDetail) detailMap(acc, p.cfa) else null

        // 3) Yarım çözünürlüklü doğrusal RGB (iki yeşilin ortalaması)
        val byChan = arrayOfNulls<Mat>(4)
        for (k in 0 until 4) byChan[chan[k]] = acc[k]
        val gAvg = Mat(); Core.addWeighted(byChan[G1]!!, 0.5, byChan[G2]!!, 0.5, 0.0, gAvg)
        val bgr = Mat(); Core.merge(listOf(byChan[B]!!, gAvg, byChan[R]!!), bgr)
        acc.forEach { it.release() }; gAvg.release()

        // 4) Renk matrisi (RGB sıralı matrisi BGR'ye çevirerek)
        val m = p.ccm
        val ccmBgr = Mat(3, 3, CvType.CV_32F).apply {
            put(0, 0,
                m[8], m[7], m[6],   // B' = B·m22 + G·m21 + R·m20
                m[5], m[4], m[3],   // G'
                m[2], m[1], m[0])   // R'
        }
        Core.transform(bgr, bgr, ccmBgr)
        ccmBgr.release()
        Core.max(bgr, Scalar(0.0, 0.0, 0.0), bgr)
        if (dump != null) { val pv = toPreview8(bgr); dump("1_dogrusal", pv); pv.release() }

        // 5) Ton eşleme (RAW'da beyaz dengesi kameradan geldiği için gri dünya düzeltmesi hafif)
        // Yıldız modunda gökyüzü gece gibi koyu kalsın (gri bir "gündüz" gökyüzü olmasın)
        val toned = if (astro) NightTone.renderLinear(bgr, targetKey = 0.07, maxGain = 16.0, saturation = 1.0, grayWorld = 0.15)
            else NightTone.renderLinear(bgr, saturation = 0.95, grayWorld = 0.25)
        dump?.invoke("2_ton", toned)

        // 6) Gürültü azaltma (yarım çözünürlükte) ve tam çözünürlüğe büyütme
        val den = SmartDenoiser().denoise(toned, 3200)
        if (den !== toned) toned.release()
        chromaDenoise(den, sigma = 2.5)
        dump?.invoke("3_gurultu", den)
        val full = Mat()
        Imgproc.resize(den, full, Size(den.cols() * 2.0, den.rows() * 2.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
        den.release()
        if (detail != null) {
            // Ayrıntı oranını sRGB'ye taşı (yaklaşık 1/2.2 üs) ve her kanala uygula
            val f = Mat(); full.convertTo(f, CvType.CV_32FC3)
            Core.pow(detail, 1.0 / 2.2, detail)
            val ch = ArrayList<Mat>(3); Core.split(f, ch)
            for (c in ch) Core.multiply(c, detail, c)
            Core.merge(ch, f); ch.forEach { it.release() }; detail.release()
            f.convertTo(full, CvType.CV_8UC3); f.release()
            dump?.invoke("3b_ayrinti", full)
        }
        val sharp = ToneMapHelper.applyAdaptiveAcutance(full, strength = if (detail != null) 0.12 else 0.22,
            haloThreshold = 24.0, noiseFloor = 3.0)
        full.release()

        return Result(sharp, mergedBayer, shifts.size, shifts,
            if (shifts.size > 1) weightTotal / (shifts.size - 1) else 1.0)
    }

    /** Düzlem başına sıcak piksel maskeleri (CV_8U, 255 = sıcak). */
    fun findHotPixels(frames: List<Mat>, p: Params): List<Mat> {
        val tmin = frames[0].clone()
        for (i in 1 until frames.size) Core.min(tmin, frames[i], tmin)
        val planes = splitPlanes(tmin, p); tmin.release()
        return planes.map { pl ->
            val med = Mat(); Imgproc.medianBlur(pl, med, 3)
            val diff = Mat(); Core.subtract(pl, med, diff)
            // Komşu medyanının üstünde: mutlak 0,02 (≈ beyazın %2'si) ve göreli %50
            val thr = Mat(); Core.multiply(med, Scalar(0.5), thr); Core.max(thr, Scalar(0.02), thr)
            val mask = Mat(); Core.compare(diff, thr, mask, Core.CMP_GT)
            // Komşu pikselleri de kapsasın (sıcak pikseller bazen 2 piksellik)
            Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            pl.release(); med.release(); diff.release(); thr.release()
            mask
        }
    }

    /** Maskelenen pikselleri aynı renk düzlemindeki 5x5 medyanla değiştirir. */
    private fun fixHotPixels(planes: List<Mat>, masks: List<Mat>) {
        for (k in planes.indices) {
            val med = Mat(); Imgproc.medianBlur(planes[k], med, 5)
            med.copyTo(planes[k], masks[k]); med.release()
        }
    }

    /** Birleşik karedeki gürültü (normalize, orta ton) bunun altındaysa tam çözünürlük ayrıntısı eklenir. */
    const val DETAIL_SIGMA_MAX = 0.012

    // ---------------------------------------------------------------- yardımcılar

    /**
     * Tam çözünürlüklü ayrıntı haritası: D = Y_tam / büyütülmüş(Y_yarım).
     * Y = (R + 2G + B) / 4 hem tam (Bayer demozaik) hem yarım çözünürlükte aynı ağırlıklarla hesaplanır;
     * R ve B'nin ağırlığı eşit olduğundan demozaik kodunda yalnızca yeşilin konumu önemlidir.
     */
    private fun detailMap(planes: List<Mat>, cfa: Int): Mat {
        val ph = planes[0].rows(); val pw = planes[0].cols()
        val scale = 16383.0   // 0..4 aralığı 16 bite sığsın (beyaz dengesi 1'i aşabilir)
        val u16 = planes.map { pl -> Mat().also { pl.convertTo(it, CvType.CV_16U, scale) } }
        val evenTwo = Mat(); Core.merge(listOf(u16[0], u16[1]), evenTwo)
        val oddTwo = Mat(); Core.merge(listOf(u16[2], u16[3]), oddTwo)
        val pairs = Mat(); Core.hconcat(listOf(evenTwo.reshape(1, ph), oddTwo.reshape(1, ph)), pairs)
        val bayer = pairs.reshape(1, ph * 2).clone()
        u16.forEach { it.release() }; evenTwo.release(); oddTwo.release(); pairs.release()

        val code = if (cfa == 1 || cfa == 2) Imgproc.COLOR_BayerGB2BGR else Imgproc.COLOR_BayerBG2BGR
        val rgb = Mat(); Imgproc.cvtColor(bayer, rgb, code); bayer.release()
        val rgbF = Mat(); rgb.convertTo(rgbF, CvType.CV_32FC3, 1.0 / scale); rgb.release()
        val yFullF = Mat()
        Core.transform(rgbF, yFullF, Mat(1, 3, CvType.CV_32F).apply { put(0, 0, 0.25, 0.5, 0.25) })
        rgbF.release()

        val yHalf = Mat(ph, pw, CvType.CV_32F, Scalar(0.0))
        for (pl in planes) Core.addWeighted(yHalf, 1.0, pl, 0.25, 0.0, yHalf)
        val yUp = Mat(); Imgproc.resize(yHalf, yUp, yFullF.size(), 0.0, 0.0, Imgproc.INTER_LINEAR); yHalf.release()

        Core.add(yFullF, Scalar(0.002), yFullF); Core.add(yUp, Scalar(0.002), yUp)
        val d = Mat(); Core.divide(yFullF, yUp, d)
        Core.min(d, Scalar(1.6), d); Core.max(d, Scalar(0.6), d)
        yFullF.release(); yUp.release()
        return d
    }

    /** Ham kareyi 4 yarım çözünürlüklü 32F düzleme ayırır; siyah seviyesi çıkarılır, 0..1 ölçeğine getirilir. */
    fun splitPlanes(raw: Mat, p: Params): List<Mat> {
        val h = raw.rows(); val w = raw.cols()
        // H×W → (H/2)×(2W): her satır = çift satır + tek satır
        val pairs = raw.reshape(1, h / 2)
        val even = pairs.colRange(0, w).clone()
        val odd = pairs.colRange(w, 2 * w).clone()
        val out = ArrayList<Mat>(4)
        for ((rowIdx, rows) in listOf(even, odd).withIndex()) {
            val two = rows.reshape(2, rows.rows())         // çift/tek sütun → 2 kanal
            val ch = ArrayList<Mat>(2); Core.split(two, ch)
            for ((colIdx, c) in ch.withIndex()) {
                val f = Mat()
                val pos = rowIdx * 2 + colIdx
                val range = (p.whiteLevel - p.blackLevel[pos]).coerceAtLeast(1.0)
                c.convertTo(f, CvType.CV_32F, 1.0 / range, -p.blackLevel[pos] / range)
                c.release()
                out.add(f)
            }
            rows.release()
        }
        return out   // p00, p01, p10, p11
    }

    /** 4 düzlemi (0..1) yeniden Bayer düzenine ve sensör ölçeğine çevirir (CV_16UC1). */
    fun interleave(planes: List<Mat>, p: Params): Mat {
        val ph = planes[0].rows()
        val u16 = planes.mapIndexed { pos, pl ->
            val range = (p.whiteLevel - p.blackLevel[pos]).coerceAtLeast(1.0)
            Mat().also { pl.convertTo(it, CvType.CV_16U, range, p.blackLevel[pos]) }
        }
        val evenTwo = Mat(); Core.merge(listOf(u16[0], u16[1]), evenTwo)
        val oddTwo = Mat(); Core.merge(listOf(u16[2], u16[3]), oddTwo)
        val evenRows = evenTwo.reshape(1, ph)   // ph × 2pw
        val oddRows = oddTwo.reshape(1, ph)
        val pairs = Mat(); Core.hconcat(listOf(evenRows, oddRows), pairs)  // ph × 4pw
        val out = pairs.reshape(1, ph * 2).clone()  // 2ph × 2pw
        u16.forEach { it.release() }; evenTwo.release(); oddTwo.release(); pairs.release()
        return out
    }

    private fun green(planes: List<Mat>, chan: IntArray): Mat {
        val g1 = planes[chan.indexOf(G1)]; val g2 = planes[chan.indexOf(G2)]
        return Mat().also { Core.addWeighted(g1, 0.5, g2, 0.5, 0.0, it) }
    }

    /** Varyans modeli: SENSOR_NOISE_PROFILE varsa onu, yoksa düz alanlardan tahmini kullanır. */
    private fun noiseModel(green: Mat, p: Params): Pair<Double, Double> {
        if (p.noiseScale != null && p.noiseOffset != null && p.noiseScale > 0) {
            // İki yeşil ortalandığı için varyans yarıya iner
            return p.noiseScale / 2.0 to p.noiseOffset / 2.0
        }
        // Immerkær: Laplacian benzeri çekirdekle σ tahmini (sinyalden bağımsız varsayım)
        val small = Mat(); Imgproc.resize(green, small, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
        val k = Mat(3, 3, CvType.CV_32F).apply { put(0, 0, 1.0, -2.0, 1.0, -2.0, 4.0, -2.0, 1.0, -2.0, 1.0) }
        val r = Mat(); Imgproc.filter2D(small, r, CvType.CV_32F, k)
        Core.absdiff(r, Scalar(0.0), r)
        val sigma = Core.mean(r).`val`[0] * sqrt(Math.PI / 2.0) / 6.0 * 2.0  // yarım ölçek düzeltmesi
        small.release(); r.release()
        val v = (sigma * sigma).coerceAtLeast(1e-8)
        return 0.0 to v
    }

    /** Yeşil düzlemler arasındaki kaymayı (referansa göre) iki aşamada bulur: kaba (1/4) + ince (merkez). */
    fun estimateShift(ref: Mat, img: Mat): Point {
        val s = 0.25
        val a = Mat(); val b = Mat()
        Imgproc.resize(ref, a, Size(), s, s, Imgproc.INTER_AREA)
        Imgproc.resize(img, b, Size(), s, s, Imgproc.INTER_AREA)
        val win = Mat(); Imgproc.createHanningWindow(win, a.size(), CvType.CV_32F)
        val coarse = Imgproc.phaseCorrelate(a, b, win)
        a.release(); b.release(); win.release()
        var dx = coarse.x / s; var dy = coarse.y / s

        // İnce ayar: merkezde, kaba kaymayla hizalanmış pencere
        val size = min(1024, min(ref.cols(), ref.rows()) - 64)
        if (size >= 128) {
            val cx = ref.cols() / 2 - size / 2; val cy = ref.rows() / 2 - size / 2
            val ix = (cx + dx).toInt().coerceIn(0, img.cols() - size)
            val iy = (cy + dy).toInt().coerceIn(0, img.rows() - size)
            val ra = ref.submat(Rect(cx, cy, size, size))
            val rb = img.submat(Rect(ix, iy, size, size))
            val w2 = Mat(); Imgproc.createHanningWindow(w2, Size(size.toDouble(), size.toDouble()), CvType.CV_32F)
            val fine = Imgproc.phaseCorrelate(ra, rb, w2)
            w2.release()
            if (abs(fine.x) < 8 && abs(fine.y) < 8) {
                dx = (ix - cx) + fine.x; dy = (iy - cy) + fine.y
            }
        }
        return Point(dx, dy)
    }

    /** Görüntüyü (dx,dy) kadar geri kaydırarak referansa hizalar. */
    private fun translate(src: Mat, shift: Point): Mat {
        if (abs(shift.x) < 1e-3 && abs(shift.y) < 1e-3) return src.clone()
        val m = Mat(2, 3, CvType.CV_64F).apply { put(0, 0, 1.0, 0.0, -shift.x, 0.0, 1.0, -shift.y) }
        val out = Mat()
        Imgproc.warpAffine(src, out, m, src.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        m.release()
        return out
    }

    private fun zeroBorder(w: Mat, shift: Point) {
        val bx = kotlin.math.ceil(abs(shift.x)).toInt().coerceAtMost(w.cols() / 4)
        val by = kotlin.math.ceil(abs(shift.y)).toInt().coerceAtMost(w.rows() / 4)
        if (bx > 0) {
            val x0 = if (shift.x > 0) w.cols() - bx else 0
            w.submat(Rect(x0, 0, bx, w.rows())).setTo(Scalar(0.0))
        }
        if (by > 0) {
            val y0 = if (shift.y > 0) w.rows() - by else 0
            w.submat(Rect(0, y0, w.cols(), by)).setTo(Scalar(0.0))
        }
    }

    /** Lens gölgelenme ızgarasını (CV_32FC4, R/Geven/Godd/B) düzlem boyutuna büyütür. */
    private fun upsampleShading(map: Mat, size: Size): List<Mat> {
        val up = Mat(); Imgproc.resize(map, up, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
        val ch = ArrayList<Mat>(4); Core.split(up, ch); up.release()
        return ch   // R, G1, G2, B sırası
    }

    /** Renk gürültüsü: Lab'de a/b kanallarını yumuşat (karanlıkta renkli benekleri alır, ayrıntı L'de kalır). */
    private fun chromaDenoise(bgr8: Mat, sigma: Double) {
        val lab = Mat(); Imgproc.cvtColor(bgr8, lab, Imgproc.COLOR_BGR2Lab)
        val ch = ArrayList<Mat>(3); Core.split(lab, ch)
        for (i in 1..2) Imgproc.GaussianBlur(ch[i], ch[i], Size(0.0, 0.0), sigma)
        Core.merge(ch, lab); Imgproc.cvtColor(lab, bgr8, Imgproc.COLOR_Lab2BGR)
        lab.release(); ch.forEach { it.release() }
    }

    private fun toPreview8(lin: Mat): Mat {
        val t = Mat(); Core.multiply(lin, Scalar(4.0, 4.0, 4.0), t)
        Core.min(t, Scalar(1.0, 1.0, 1.0), t); Core.pow(t, 1.0 / 2.2, t)
        val o = Mat(); t.convertTo(o, CvType.CV_8UC3, 255.0); t.release()
        return o
    }

}

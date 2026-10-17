package com.a56camera

import android.Manifest
import android.graphics.Rect
import android.graphics.RectF
import com.a56camera.camera.ExposureController
import com.a56camera.camera.WhiteBalanceController
import com.a56camera.utils.Camera2Utils
import com.a56camera.utils.ExifHelper
import com.a56camera.utils.PermissionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Kamera mantığı için birim testleri (telefon gerektirmez). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraLogicTest {

    private val array = Rect(0, 0, 4080, 3060)              // 4:3 yatay sensör
    private val screen = RectF(0f, 0f, 1080f, 1440f)          // dikey önizleme alanı

    // ---------- Dokunarak odak eşlemesi ----------

    @Test fun arkaKamera_ekranUstOrtasi_sensorSolOrtasi() {
        val p = Camera2Utils.mapViewToSensor(540f, 0f, screen, 90, false, array, 1f, 4f / 3f)
        assertEquals(0, p.x)
        assertEquals(1530.0, p.y.toDouble(), 2.0)
    }

    @Test fun arkaKamera_ekranSagOrtasi_sensorUstOrtasi() {
        // Sensör görüntüsü saat yönünde 90° döndürülerek gösterilir: sensörün üst kenarı ekranın sağına gelir
        val p = Camera2Utils.mapViewToSensor(1080f, 720f, screen, 90, false, array, 1f, 4f / 3f)
        assertEquals(2040.0, p.x.toDouble(), 2.0)
        assertEquals(0, p.y)
    }

    @Test fun onKamera_aynaliVe270Derece() {
        // Ön kamera (270°, aynalı): ekranın üst ortası sensörün sağ ortasına düşer
        val p = Camera2Utils.mapViewToSensor(540f, 0f, screen, 270, true, array, 1f, 4f / 3f)
        assertEquals(4079, p.x)
        assertEquals(1530.0, p.y.toDouble(), 2.0)
    }

    @Test fun zoom2x_gorunenBolgeyeEslenir() {
        val p = Camera2Utils.mapViewToSensor(540f, 0f, screen, 90, false, array, 2f, 4f / 3f)
        assertEquals(1020.0, p.x.toDouble(), 2.0)    // 2x'te görünen bölge sensörün ortadaki yarısı
        assertEquals(1530.0, p.y.toDouble(), 2.0)
    }

    @Test fun akis16_9_ustAltKirpilmisSensoreEslenir() {
        // 16:9 akış 4:3 sensörün ortadaki bandını gösterir (yükseklik 4080/1.777 ≈ 2295)
        val p = Camera2Utils.mapViewToSensor(0f, 720f, screen, 90, false, array, 1f, 16f / 9f)
        assertEquals(2678.0, p.y.toDouble(), 3.0)
    }

    @Test fun letterbox_siyahKenarlarHesabaKatilir() {
        val content = RectF(0f, 180f, 1080f, 1260f)   // içerik ekranın ortasında
        val p = Camera2Utils.mapViewToSensor(540f, 180f, content, 90, false, array, 1f, 4f / 3f)
        assertEquals(0, p.x)
    }

    // ---------- Pro mod pozlama ----------

    @Test fun yalnizcaIso_manuelPozlamayiAcarVePerdeyiHesaplar() {
        val e = ExposureController()
        e.setIsoFromProgress(0.5f)
        assertTrue(e.isManual())
        val (iso, exp) = e.effectiveManualValues()
        // Ölçüm yokken varsayılan ışık miktarı: ISO 100 × 1/60 sn
        assertEquals(100.0 * 16_666_666 / iso, exp.toDouble(), exp * 0.02)
    }

    @Test fun yalnizcaPerde_isoHesaplanir() {
        val e = ExposureController()
        e.manualExposureNanos = 8_333_333L   // 1/120 sn
        assertTrue(e.isManual())
        assertEquals(200, e.effectiveManualValues().first)
    }

    // ---------- Beyaz dengesi ----------

    @Test fun kelvin_enYakinHazirAyaraEslenir() {
        val wb = WhiteBalanceController()
        assertEquals(WhiteBalanceController.Preset.INCANDESCENT, wb.presetForKelvin(2600))
        assertEquals(WhiteBalanceController.Preset.DAYLIGHT, wb.presetForKelvin(5500))
        assertEquals(WhiteBalanceController.Preset.SHADE, wb.presetForKelvin(7500))
    }

    // ---------- İzinler ----------

    @Test fun yalnizcaKameraIzniZorunlu() {
        assertEquals(listOf(Manifest.permission.CAMERA), PermissionUtils.requiredPermissions().toList())
    }

    // ---------- EXIF ----------

    @Test fun pozlamaSuresiRasyonelYazilir() {
        assertEquals("1/250", ExifHelper.exposureRational(4_000_000L))
        assertEquals("1/4000", ExifHelper.exposureRational(250_000L))
        assertEquals("2000/1000", ExifHelper.exposureRational(2_000_000_000L))
    }
}

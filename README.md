# A56 Kamera 📷

Samsung Galaxy A56 için **Google Pixel Camera tarzı**, tam özellikli **Native Android** kamera uygulaması.
Kotlin + **Camera2 API** + Material Design 3 + Hilt ile geliştirilmiştir. Önizleme, çekim, video ve
gelişmiş modlar doğrudan Camera2 (`android.hardware.camera2`) üzerine kuruludur. Projede CameraX
bağımlılıkları ve deneysel `CameraXSession` / `CameraXExtensionsProbe` sınıfları bulunur ama uygulamanın
çalışan akışı bunları kullanmaz. Manuel sensör kontrolü, RAW ve yüksek hızlı çekim cihazın Camera2
yeteneklerine bağlıdır.

---

## ✨ Özellikler

### Kamera Modülleri
- **50MP Ana** (1x), **12MP Ultra Geniş** (0.6x), **2x** dijital, **12MP Ön** kamera
- Modüller arası geçiş + zoom butonları (0.6x / 1x / 2x)
- Pinch-to-zoom + dokunarak odaklama

### Çekim Modları
| Mod | Açıklama |
|-----|----------|
| **Fotoğraf** | JPEG çekim, HDR, flaş, zamanlayıcı |
| **Video** | 4K@30, 1080p@60/30, 720p@120 |
| **Pro/Manuel** | ISO, perde, odak, WB, EV, histogram |
| **Gece** | Camera2 çok kareli uzun pozlama (manuel ISO/perde + HIGH_QUALITY NR) |
| **Portre** | Camera2 yüz tespiti (FACE_DETECT) + yazılım bokeh |
| **Panorama** | Jiroskop rehberli ardışık çekim + yatay birleştirme |
| **Ağır Çekim** | Constrained high-speed oturum (720p@240 / 1080p@120, donanıma göre) |
| **Hızlandırılmış** | Time-lapse (0.5s–30s aralık, MediaRecorder capture-rate) |
| **RAW (DNG)** | Fotoğraf/Pro modunda Camera2 RAW_SENSOR + DngCreator ile JPEG + sensör DNG; Gece/Portre/HDR'da işlenmiş 16-bit doğrusal DNG |

### Pro Mod Kontrolleri
- **ISO**: Auto, 50–3200 (logaritmik)
- **Perde Hızı**: Auto, 1/4000s – 30s
- **Odak**: AF / Manuel (sonsuz → makro)
- **Beyaz Dengesi**: Auto, 2300K – 7500K
- **EV**: Cihaz aralığına göre pozlama telafisi
- **Canlı Histogram** (RGB/Luma)

### Ek Özellikler
- Grid çizgileri (3x3 / kare / altın oran)
- AF/AE kilidi (uzun basma)
- GPS EXIF konum etiketleme (Ayarlar → Konum Etiketi)
- Ses tuşu ile çekim
- MediaStore'a kayıt (DCIM/Camera)
- Galeri thumbnail önizleme
- Tam siyah AMOLED dostu arayüz

---

## 🛠️ Teknoloji

- **Dil:** Kotlin
- **Min SDK:** 28 (Android 9) · **Target/Compile SDK:** 34
- **Kamera:** **Camera2 API** (`android.hardware.camera2`)
- **Görüntü işleme:** OpenCV, TensorFlow Lite (sahne, Zero-DCE, MiDaS, Real-ESRGAN), ARM64 NEON (C++)
- **DI:** Hilt (Dagger)
- **UI:** Material Design 3, ViewBinding, önizleme için `TextureView`
- **Görsel yükleme:** Glide

---

## 📦 Proje Yapısı

```
app/src/main/
├── java/com/a56camera/
│   ├── A56Application.kt              # Hilt uygulama sınıfı
│   ├── CameraActivity.kt             # Ana kamera ekranı (launcher)
│   ├── SettingsActivity.kt           # Ayarlar
│   ├── camera/                       # Saf Camera2 katmanı
│   │   ├── CaptureMode.kt            # Mod/flaş/grid enum'ları
│   │   ├── Camera2Manager.kt         # Merkezi orkestratör (aç/kapa, önizleme, çekim)
│   │   ├── CaptureSessionManager.kt  # Oturum yaşam döngüsü (normal + high-speed)
│   │   ├── CaptureRequestBuilder.kt  # Tüm CaptureRequest parametreleri + ProcessingSettings
│   │   ├── ImageReaderManager.kt     # JPEG / RAW_SENSOR / YUV akışları
│   │   ├── ExposureController.kt     # AE / manuel ISO + perde
│   │   ├── FocusController.kt        # AF / dokunarak odak / manuel odak
│   │   ├── WhiteBalanceController.kt # AWB / preset / manuel Kelvin
│   │   ├── ZoomController.kt         # CONTROL_ZOOM_RATIO / CROP_REGION
│   │   ├── NightModeCapture.kt       # Çok kareli uzun pozlama
│   │   ├── TimeLapseCapture.kt       # Hızlandırılmış kayıt
│   │   ├── VideoCapture.kt           # MediaRecorder video (H.264/H.265)
│   │   ├── PortraitMode.kt           # Yüz tespiti + yazılım bokeh
│   │   └── PanoramaCapture.kt        # Jiroskop rehberli birleştirme
│   ├── ui/views/
│   │   ├── Camera2PreviewView.kt     # TextureView önizleme (CameraX PreviewView yerine)
│   │   ├── ModeSelector.kt           # Yatay mod seçici
│   │   ├── ZoomSeekBar.kt            # Kaydırmalı zoom
│   │   ├── HistogramView.kt          # Canlı histogram
│   │   ├── FocusRingView.kt          # Odak halkası animasyonu
│   │   └── GridOverlayView.kt        # Izgara katmanı
│   └── utils/
│       ├── PermissionUtils.kt
│       ├── Camera2Utils.kt           # Karakteristik okuma, ID haritalama, dönüşümler
│       ├── MediaStoreHelper.kt       # DCIM/Camera kaydı (JPEG/DNG/video)
│       ├── DngHelper.kt              # DngCreator ile RAW → DNG
│       ├── ExifHelper.kt             # CaptureResult → EXIF + GPS
│       └── HistogramCalculator.kt    # Önizleme karesi → RGB/Luma histogram
└── res/                              # Layout, drawable, values (Türkçe)
```

---

## 🚀 Derleme (Build)

### Android Studio ile (önerilen)
1. **Android Studio** (Hedgehog veya üzeri) açın.
2. `File → Open` ile `A56CameraApp` klasörünü seçin.
3. Gradle senkronizasyonunun bitmesini bekleyin (bağımlılıklar indirilir).
4. Bir Galaxy A56 / Android cihazı bağlayın (USB hata ayıklama açık) veya emülatör başlatın.
5. **Run ▶** butonuna basın.

### Komut satırı ile
```bash
cd A56CameraApp

# Debug APK derle (Gradle 8.13 wrapper ile otomatik indirilir)
./gradlew assembleDebug

# APK çıktısı:
# app/build/outputs/apk/debug/app-debug.apk

# Cihaza kur (adb bağlıyken)
./gradlew installDebug
```

> **Gereksinimler:** JDK 17 veya üzeri (Android Studio'nun kendi JDK'sı yeterli), Android SDK 34,
> NDK ve CMake 3.22.1 (C++ NEON çekirdeği için; Android Studio ilk derlemede kurmayı önerir).
> SDK yolu `local.properties` içindeki `sdk.dir` ile verilir; bu dosya kişiye özel olduğu için depoya eklenmez.

---

## 🔐 İzinler

Uygulama ilk açılışta şu izinleri ister:
`CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES/VIDEO` (zorunlu) ve
`ACCESS_FINE_LOCATION` (GPS EXIF için; yalnızca Ayarlar → Konum Etiketi açıldığında istenir).

Başka uygulamalar "fotoğraf çek" (`ACTION_IMAGE_CAPTURE`) isteğiyle bu uygulamayı açabilir; çekilen
fotoğraf istenen `EXTRA_OUTPUT` konumuna yazılıp çağıran uygulamaya döndürülür.

---

## ⚠️ Cihaz Notları

- **RAW (DNG)** ve **Gece/Portre** modları donanım desteğine bağlıdır. RAW akışı işleme akışlarıyla
  birlikte açılamazsa uygulama RAW'ı kapatıp uyarı verir ve normal çekime devam eder.
- **Gece/Portre braketi**, cihaz `MANUAL_SENSOR` destekliyorsa ISO ve perde süresi doğrudan
  ayarlanarak çekilir; desteklemiyorsa AE telafisine düşer.
- **Ultra geniş (0.6x)** modülü, cihazın fiziksel ultra geniş lensinden seçilir; yoksa ana kamera
  kullanılır.
- **Test ve Doğrulama Durumu:** Proje, fiziksel **Samsung Galaxy A56 5G (SM-A566B, Android 15 / One UI 7, Exynos 1580)** üzerinde derlenmiş, kurulmuş ve tüm modları (Pixel Real Tone, 4K/1080p 60fps video, gece sahnesi, hızlı ayar paneli) test edilip doğrulanmıştır.

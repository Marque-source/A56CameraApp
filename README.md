# A56 Kamera 📷

Samsung Galaxy A56 için **Google Pixel Camera tarzı**, tam özellikli **Native Android** kamera uygulaması.
Kotlin + **saf Camera2 API** + Material Design 3 + Hilt ile geliştirilmiştir. CameraX **kullanılmaz** —
tüm önizleme, çekim, video ve gelişmiş modlar doğrudan Camera2 (`android.hardware.camera2`) üzerine kuruludur.
Galaxy A56 tam Camera2 donanım seviyesini (LEVEL_3) sunar; bu sayede manuel sensör kontrolü, RAW ve yüksek
hızlı çekim tam olarak kullanılabilir.

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
| **RAW (DNG)** | Camera2 RAW_SENSOR + DngCreator ile RAW + JPEG eş zamanlı |

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
- GPS EXIF konum etiketleme
- Ses tuşu ile çekim
- MediaStore'a kayıt (DCIM/Camera)
- Galeri thumbnail önizleme
- Tam siyah AMOLED dostu arayüz

---

## 🛠️ Teknoloji

- **Dil:** Kotlin
- **Min SDK:** 26 (Android 8.0) · **Target SDK:** 35
- **Kamera:** Saf **Camera2 API** (`android.hardware.camera2`) — CameraX yok
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
│   │   ├── SlowMotionCapture.kt      # Constrained high-speed kayıt
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
│       └── HistogramCalculator.kt    # YUV → RGB/Luma histogram
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

# Gradle wrapper JAR'ı eksikse bir kez oluşturun:
gradle wrapper --gradle-version 8.9

# Debug APK derle
./gradlew assembleDebug

# APK çıktısı:
# app/build/outputs/apk/debug/app-debug.apk

# Cihaza kur (adb bağlıyken)
./gradlew installDebug
```

> **Not:** `gradle-wrapper.jar` dosyası depoya dahil edilmemiştir. Android Studio ilk açılışta
> otomatik oluşturur; komut satırında ise `gradle wrapper` komutunu bir kez çalıştırın
> (sistemde Gradle 8.x kurulu olmalı).

---

## 🔐 İzinler

Uygulama ilk açılışta şu izinleri ister:
`CAMERA`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES/VIDEO` (zorunlu) ve
`ACCESS_FINE_LOCATION` (GPS EXIF için, ayarlardan etkinleştirilir).

---

## ⚠️ Cihaz Notları

- **RAW (DNG)** ve **Gece/Portre** modları donanım desteğine bağlıdır; desteklenmeyen cihazlarda
  otomatik olarak standart çekime düşülür veya "desteklenmiyor" uyarısı verilir.
- **Ultra geniş (0.6x)** modülü, cihazın fiziksel ultra geniş lensinden seçilir; yoksa ana kamera
  kullanılır.
- **Test ve Doğrulama Durumu:** Proje, fiziksel **Samsung Galaxy A56 5G (SM-A566B, Android 15 / One UI 7, Exynos 1580)** üzerinde derlenmiş, kurulmuş ve tüm modları (Pixel Real Tone, 4K/1080p 60fps video, gece sahnesi, hızlı ayar paneli) test edilip doğrulanmıştır.

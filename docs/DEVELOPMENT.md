# Pixora — Geliştirici Belgeleri

## 🛠️ Teknoloji

- **Dil:** Kotlin
- **Min SDK:** 28 (Android 9) · **Target/Compile SDK:** 35
- **Kamera:** **Camera2 API** (`android.hardware.camera2`)
- **Görüntü işleme:** OpenCV 4.13, LiteRT/TensorFlow Lite (sahne sınıflandırma, Zero-DCE, MiDaS)
- Yerel kütüphaneler Android 15'in 16 KB bellek sayfalarına hizalıdır
- **DI:** Hilt (Dagger)
- **UI:** Material Design 3, ViewBinding, önizleme için `TextureView`
- **Görsel yükleme:** Glide

---

## 📦 Proje Yapısı

```
app/src/main/
├── java/io/github/marquesource/pixora/
│   ├── PixoraApplication.kt           # Uygulama sınıfı
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
│   │   ├── ExposureBracketingStrategy.kt # Gece/portre burst pozlaması
│   │   ├── TimeLapseCapture.kt       # Hızlandırılmış kayıt
│   │   ├── VideoCapture.kt           # MediaRecorder video (H.264/H.265)
│   │   └── PanoramaCapture.kt        # Jiroskop rehberli çekim + silindirik birleştirme
│   ├── pipeline/                     # Görüntü işleme
│   │   ├── ImageProcessingPipeline.kt # Gece/portre: birleştirme → gürültü azaltma → ton → keskinlik → kayıt
│   │   ├── MultiFrameProcessor.kt    # Hizalama (ORB) + Mertens / eşit ortalama
│   │   ├── TemporalMerge.kt          # Hareket korumalı kare ortalaması
│   │   ├── SmartDenoiser.kt          # Gürültüye uyarlanan NLM
│   │   ├── ToneMapHelper.kt          # Renk profilleri, gece tonu, keskinleştirme
│   │   └── *Engine.kt / SceneOptimizer.kt # LiteRT modelleri
│   ├── ui/views/
│   │   ├── Camera2PreviewView.kt     # TextureView önizleme
│   │   ├── ModeSelector.kt           # Yatay mod seçici
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
2. `File → Open` ile proje klasörünü seçin.
3. Gradle senkronizasyonunun bitmesini bekleyin (bağımlılıklar indirilir).
4. Bir Android telefon bağlayın (USB hata ayıklama açık) veya emülatör başlatın.
5. **Run ▶** butonuna basın.

### Komut satırı ile
```bash

# Debug APK derle (Gradle 8.13 wrapper ile otomatik indirilir)
./gradlew assembleDebug

# APK çıktısı:
# app/build/outputs/apk/debug/app-debug.apk

# Cihaza kur (adb bağlıyken)
./gradlew installDebug
```

> **Gereksinimler:** JDK 17 veya üzeri (Android Studio'nun kendi JDK'sı yeterli) ve Android SDK 35.
> NDK/CMake gerekmez. SDK yolu `local.properties` içindeki `sdk.dir` ile verilir; bu dosya kişiye özel
> olduğu için depoya eklenmez.

### Testler
```bash
./gradlew testDebugUnitTest   # Robolectric birim testleri (odak eşleme, pozlama, beyaz dengesi, izinler, EXIF)
```

### Yayın (imzalı) sürümü
Proje köküne `keystore.properties` koyun (git'e eklenmez):
```
storeFile=/tam/yol/anahtar.jks
storePassword=...
keyAlias=...
keyPassword=...
```
Sonra `./gradlew assembleRelease`. Dosya yoksa yayın APK'sı imzasız üretilir ve telefona kurulamaz.

---

## 🔐 İzinler

- `CAMERA` — tek zorunlu izin (Android 9'da ayrıca dosya yazma).
- `RECORD_AUDIO` — ilk video kaydında istenir; reddedilirse video sessiz kaydedilir.
- `ACCESS_FINE_LOCATION` — yalnızca Ayarlar → Konum Etiketi açılınca istenir.
- Galeri okuma izni istenmez: uygulama yalnızca kendi kaydettiği dosyaları görür.

Başka uygulamalar "fotoğraf çek" (`ACTION_IMAGE_CAPTURE`) isteğiyle bu uygulamayı açabilir; çekilen
fotoğraf istenen `EXTRA_OUTPUT` konumuna yazılıp çağıran uygulamaya döndürülür ve galeride bırakılmaz.

---

## ⚠️ Cihaz Notları

- **RAW (DNG)** ve **Gece/Portre** modları donanım desteğine bağlıdır. RAW akışı işleme akışlarıyla
  birlikte açılamazsa uygulama RAW'ı kapatıp uyarı verir ve normal çekime devam eder.
- **Gece/Portre braketi**, cihaz `MANUAL_SENSOR` destekliyorsa ISO ve perde süresi doğrudan
  ayarlanarak çekilir; desteklemiyorsa AE telafisine düşer.
- **Ultra geniş (0.6x)** modülü, cihazın fiziksel ultra geniş lensinden ya da 1x altı zoom oranından seçilir;
  telefon ikisini de uygulamalara açmıyorsa uyarı gösterilir.
- **50MP**, **60 FPS**, **video sabitleme (EIS)**, **RAW** ve **elle ISO/enstantane/odak** seçenekleri, açık kameranın
  Camera2 yeteneklerine göre otomatik etkinleştirilir veya gizlenir.
- **Test durumu:** Birim testleri (12) ve görüntü işleme hattının masaüstü ölçümleri yanında, uygulama Samsung Galaxy A56
  (Android 16) üzerinde adb ile uçtan uca denendi: açılış, fotoğraf, gece, portre, pro, video ve arka plana alma.

### Galaxy A56'da ölçülen kamera yetenekleri
Donanım seviyesi LIMITED. Uygulamalara açık: ana (12MP), ultra geniş, ön kamera; EV ±2; AWB hazır ayarları;
720p 120/240 FPS yüksek hızlı video; 4K30. Kapalı: 50MP, makro, elle ISO/enstantane/odak, RAW, EIS, 60 FPS.

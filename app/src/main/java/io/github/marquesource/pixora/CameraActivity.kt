package io.github.marquesource.pixora

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.github.marquesource.pixora.camera.Camera2Manager
import io.github.marquesource.pixora.camera.CameraModule
import io.github.marquesource.pixora.camera.CaptureMode
import io.github.marquesource.pixora.camera.FlashMode
import io.github.marquesource.pixora.camera.GridType
import io.github.marquesource.pixora.camera.PanoramaCapture
import io.github.marquesource.pixora.camera.TimeLapseCapture
import io.github.marquesource.pixora.camera.TimerMode
import io.github.marquesource.pixora.camera.VideoCapture
import io.github.marquesource.pixora.databinding.ActivityCameraBinding
import io.github.marquesource.pixora.utils.AspectRatio
import io.github.marquesource.pixora.utils.HistogramCalculator
import io.github.marquesource.pixora.utils.MediaStoreHelper
import io.github.marquesource.pixora.utils.PermissionUtils
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var camera: Camera2Manager

    private lateinit var videoCapture: VideoCapture
    private lateinit var timeLapse: TimeLapseCapture
    private lateinit var panorama: PanoramaCapture

    private var currentMode = CaptureMode.PHOTO
    private var flashMode = FlashMode.AUTO
    private var timerMode = TimerMode.OFF
    private var gridType = GridType.OFF
    private var histogramEnabled = false
    private var isRecording = false

    private lateinit var scaleDetector: ScaleGestureDetector

    private enum class ProParam { NONE, ISO, SHUTTER, FOCUS, WB, EV, PROFILE }
    private var activeProParam = ProParam.NONE
    private var proLimitHintShown = false

    private val proProfiles = listOf(
        io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL,
        io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PRO_MONOCHROME,
        io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PRO_CINEMA_LOG,
        io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.NEUTRAL
    )
    private var proProfileIndex = 0
    private val currentProProfile get() = proProfiles[proProfileIndex]

    private var isExposureDragging = false
    private var lastDragTouchY = 0f

    // ─── Konum etiketi (EXIF GPS) ───
    private var lastKnownLocation: Location? = null
    private var locationPermissionAsked = false
    private var fusedLocation: com.google.android.gms.location.FusedLocationProviderClient? = null
    private val locationCallback = object : com.google.android.gms.location.LocationCallback() {
        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
            result.lastLocation?.let { lastKnownLocation = it }
        }
    }
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) startLocationUpdates()
        else Toast.makeText(this, "Konum izni verilmedi, fotoğraflara konum eklenmeyecek", Toast.LENGTH_SHORT).show()
    }

    // ─── Başka uygulamadan gelen "fotoğraf çek" isteği (ACTION_IMAGE_CAPTURE) ───
    private var captureIntentOutput: Uri? = null
    private var isCaptureIntent = false

    // ─── Canlı histogram: önizleme karesinden ~7 fps ───
    private val histogramHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val histogramTick = object : Runnable {
        override fun run() {
            if (binding.histogram.isVisible && binding.previewView.isAvailable) {
                try {
                    binding.previewView.getBitmap(160, 120)?.let { bmp ->
                        val hist = HistogramCalculator.fromBitmap(bmp)
                        bmp.recycle()
                        binding.histogram.updateHistogram(hist.red, hist.green, hist.blue, hist.luma)
                    }
                } catch (_: Exception) {}
            }
            histogramHandler.postDelayed(this, 150L)
        }
    }

    // ─── İzinler ───
    // Yalnızca kamera izni zorunlu. Eskiden mikrofon ve galeri izinleri de zorunluydu: biri reddedilirse
    // (ya da Android 14+ "Seçili fotoğraflar" seçilirse) kamera hiç açılmıyordu.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionUtils.hasAllPermissions(this)) initCamera() else showCameraPermissionDenied()
    }

    /** Mikrofon izni video kaydı başlatılırken istenir; reddedilirse video sessiz kaydedilir. */
    private var audioPermissionAsked = false
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Mikrofon izni yok: video sessiz kaydedilecek", Toast.LENGTH_SHORT).show()
        startVideoRecording()
    }

    private var cameraInitialized = false
    private var cameraStarted = false
    private var timerRunning = false
    private var timerRunnable: Runnable? = null
    private val shutterSound by lazy { android.media.MediaActionSound() }
    private val panoExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun showCameraPermissionDenied() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kamera izni gerekli")
            .setMessage("Fotoğraf çekebilmek için kamera iznine ihtiyaç var.")
            .setPositiveButton("Ayarları aç") { _, _ ->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)))
            }
            .setNegativeButton("Tekrar dene") { _, _ ->
                permissionLauncher.launch(PermissionUtils.requiredPermissions())
            }
            .setCancelable(false)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        actionBar?.hide()
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableFullscreen()
        applyCutoutInsets()
        // Not: Eskiden setShowWhenLocked/setTurnScreenOn ile uygulama kilit ekranının üstünde açık
        // kalabiliyordu; kilitli telefonda son fotoğrafın küçük resmi görünüyordu. Kaldırıldı.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!PermissionUtils.hasCameraHardware(this)) {
            Toast.makeText(this, "Cihazda kamera donanımı bulunamadı!", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        videoCapture = VideoCapture(this)
        timeLapse = TimeLapseCapture(this)
        panorama = PanoramaCapture(this)
        camera = Camera2Manager(this, binding.previewView)

        setupModeSelector()
        setupTopBar()
        setupZoomBar()
        setupShutter()
        setupGestures()
        setupProPanel()
        setupOrientationListener()

        selectedVideoRes = prefs().getString("pref_video_res", "fhd") ?: "fhd"
        selectedVideoFps = prefs().getInt("pref_video_fps", 30)
        selectedVideoEis = prefs().getBoolean("pref_video_eis", true)
        updateQuickSettingsPill()

        if (PermissionUtils.hasAllPermissions(this)) initCamera()
        else permissionLauncher.launch(PermissionUtils.requiredPermissions())
    }

    /** Üst çubuğu ön kamera deliğinin (display cutout) altına it; alt paneli hareket çubuğundan uzak tut. */
    private fun applyCutoutInsets() {
        val baseTop = (24 * resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            (binding.topBar.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { lp ->
                val top = maxOf(baseTop, cutout.top + baseTop / 3)
                if (lp.topMargin != top) { lp.topMargin = top; binding.topBar.layoutParams = lp }
            }
            val baseBottom = (32 * resources.displayMetrics.density).toInt()
            binding.bottomPanel.setPadding(binding.bottomPanel.paddingLeft, binding.bottomPanel.paddingTop,
                binding.bottomPanel.paddingRight, maxOf(baseBottom, nav.bottom + baseBottom / 4))
            insets
        }
    }

    private fun enableFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private var lastProLabelTime = 0L

    private fun initCamera() {
        histogramEnabled = prefs().getBoolean("pref_histogram", false)
        camera.onError = { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
        camera.onCameraOpened = { runOnUiThread { if (!isFinishing && !isDestroyed) applyCapabilityUi() } }
        camera.onPreviewResult = {
            if (currentMode == CaptureMode.PRO) {
                val now = System.currentTimeMillis()
                if (now - lastProLabelTime >= 100L) {
                    lastProLabelTime = now
                    runOnUiThread { refreshProLabels() }
                }
            }
        }
        camera.onSceneDetected = { detection ->
            runOnUiThread {
                if (currentMode == CaptureMode.PHOTO &&
                    detection.scene != io.github.marquesource.pixora.pipeline.SceneOptimizer.SceneType.AUTO &&
                    detection.confidence >= 0.50f
                ) {
                    binding.tvSceneBadge.text = "${detection.scene.icon} ${detection.scene.labelTr}"
                    binding.tvSceneBadge.isVisible = true
                } else {
                    binding.tvSceneBadge.isVisible = false
                }
            }
        }

        camera.flashMode = flashMode
        applyGrid()
        binding.histogram.isVisible = histogramEnabled
        updateGalleryThumb()
        cameraInitialized = true
        startCameraIfPossible()
        handleIntent(intent)
    }

    /**
     * Kamerayı yalnızca bir kez başlatır. Eskiden onCreate ve onResume ikisi de start() çağırıyor,
     * kamera iki kez açılıyor ve her dönüşte yapay zeka modelleri yeniden yükleniyordu.
     */
    private fun startCameraIfPossible() {
        if (!cameraInitialized || cameraStarted || !PermissionUtils.hasAllPermissions(this)) return
        if (binding.previewView.isAvailable) {
            cameraStarted = true
            camera.start()
        } else {
            binding.previewView.onSurfaceReady = { _, _, _ -> startCameraIfPossible() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == android.provider.MediaStore.ACTION_IMAGE_CAPTURE) {
            isCaptureIntent = true
            captureIntentOutput = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT)
            }
            binding.modeContainer.select(CaptureMode.PHOTO)
        }
        val modeStr = intent?.getStringExtra("mode")
        if (modeStr != null) {
            try {
                val targetMode = CaptureMode.valueOf(modeStr.uppercase())
                binding.modeContainer.select(targetMode)
            } catch (_: Exception) {}
        }
        val zoomVal = if (BuildConfig.DEBUG) intent?.getFloatExtra("zoom", -1f) ?: -1f else -1f
        if (zoomVal >= 1.8f) {
            selectModule(CameraModule.TELE_2X)
        } else if (zoomVal in 0.8f..1.2f) {
            selectModule(CameraModule.WIDE)
        } else if (zoomVal in 0.3f..0.7f) {
            selectModule(CameraModule.ULTRA_WIDE)
        }
        // Otomatik deklanşör yalnızca geliştirme (debug) sürümünde: dışarıdaki herhangi bir uygulamanın
        // bu kamerayı açıp kullanıcının haberi olmadan fotoğraf çektirmesini önler.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("capture", false) == true) {
            binding.root.postDelayed({ onShutterPressed() }, 800)
        }
    }

    private fun setupModeSelector() {
        binding.modeContainer.onModeSelected = { mode -> switchMode(mode) }
        binding.modeContainer.select(CaptureMode.PHOTO)
    }

    private var currentAspectRatio = AspectRatio.RATIO_4_3

    // ─── Google Pixel Tarzı Video & Hızlı Ayar Durumları ───
    private var selectedVideoRes: String = "fhd" // "fhd" (1080p) veya "uhd" (4K)
    private var selectedVideoFps: Int = 30       // 30 veya 60 fps
    private var selectedVideoEis: Boolean = true
    private var isPixelSettingsOpen: Boolean = false

    private fun switchMode(mode: CaptureMode) {
        if (isRecording || timerRunning) return
        val wasVideo = currentMode.isVideo
        currentMode = mode
        // Video/time-lapse 16:9 kaydedilir; önizleme de 16:9 olsun ki görülen = kaydedilen
        if (::camera.isInitialized && mode.isVideo != wasVideo) {
            camera.aspectRatio = if (mode.isVideo) AspectRatio.RATIO_16_9 else currentAspectRatio
        }
        val isPro = (mode == CaptureMode.PRO)
        binding.proPanel.root.isVisible = isPro
        binding.histogram.isVisible = isPro || histogramEnabled

        if (isPro) {
            updateProBadge()
            refreshProLabels()
            if (!proLimitHintShown && ::camera.isInitialized && !camera.exposure.manualSupported) {
                proLimitHintShown = true
                Toast.makeText(
                    this,
                    "Bu telefon elle ISO, enstantane ve odak ayarını başka kamera uygulamalarına açmıyor. " +
                        "Burada pozlama (EV), beyaz dengesi ve profil kullanılabilir.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            if (mode != CaptureMode.PHOTO) {
                binding.tvSceneBadge.isVisible = false
            }
            camera.exposure.reset()
            camera.focus.reset()
            camera.whiteBalance.reset()
        }

        binding.shutterInner.setBackgroundResource(
            if (mode.isVideo) R.drawable.bg_shutter_video else R.drawable.bg_shutter_pixel
        )
        camera.clearFocusLock()

        // Pixel tarzı üst pill metnini güncelle
        updateQuickSettingsPill()

        // Video moduna geçildiğinde Google Pixel gibi ayarlar panelini otomatik aç!
        if (mode == CaptureMode.VIDEO) {
            openQuickSettings(forceVideo = true)
        } else {
            if (isPixelSettingsOpen) {
                openQuickSettings(forceVideo = false)
            }
        }
    }

    private fun updateQuickSettingsPill() {
        if (currentMode == CaptureMode.VIDEO) {
            val resName = if (selectedVideoRes == "uhd") "4K" else "1080P"
            binding.pillQuickSettings.text = "📹 $resName • ${selectedVideoFps} FPS ▾"
        } else {
            val mp = if (::camera.isInitialized) camera.photoMegapixels(is50MpEnabled) else 0
            val resText = if (mp > 0) "${mp}MP" else if (is50MpEnabled) "50MP" else "12MP"
            val rawText = if (isExpertRawEnabled) " • RAW" else ""
            binding.pillQuickSettings.text = "📸 $resText • ${currentAspectRatio.label}$rawText ▾"
        }
    }

    private fun openQuickSettings(forceVideo: Boolean = false) {
        val isVideo = (currentMode == CaptureMode.VIDEO || forceVideo)
        binding.panelPixelSettings.tvPixelSettingsTitle.text = if (isVideo) "Video Kalitesi & FPS" else "Kamera Hızlı Ayarları"
        binding.panelPixelSettings.layoutVideoSettings.isVisible = isVideo
        binding.panelPixelSettings.layoutPhotoSettings.isVisible = !isVideo

        if (isVideo) {
            refreshVideoSettingsUi()
        } else {
            refreshPhotoSettingsUi()
        }

        binding.viewSettingsScrim.isVisible = true
        binding.panelPixelSettings.root.isVisible = true
        binding.panelPixelSettings.root.alpha = 0f
        binding.panelPixelSettings.root.translationY = -40f
        binding.panelPixelSettings.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
        isPixelSettingsOpen = true
    }

    private fun closeQuickSettings() {
        if (!isPixelSettingsOpen) return
        binding.panelPixelSettings.root.animate()
            .alpha(0f)
            .translationY(-30f)
            .setDuration(180)
            .withEndAction {
                binding.panelPixelSettings.root.isVisible = false
                binding.viewSettingsScrim.isVisible = false
                isPixelSettingsOpen = false
            }
            .start()
    }

    private fun toggleQuickSettings() {
        if (isPixelSettingsOpen) closeQuickSettings() else openQuickSettings()
    }

    private fun refreshVideoSettingsUi() {
        val isFhd = (selectedVideoRes == "fhd")
        binding.panelPixelSettings.btnVideoResFhd.setBackgroundResource(
            if (isFhd) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnVideoResFhd.setTextColor(
            if (isFhd) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        binding.panelPixelSettings.btnVideoRes4k.setBackgroundResource(
            if (!isFhd) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnVideoRes4k.setTextColor(
            if (!isFhd) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        val is30 = (selectedVideoFps == 30)
        binding.panelPixelSettings.btnVideoFps30.setBackgroundResource(
            if (is30) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnVideoFps30.setTextColor(
            if (is30) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        binding.panelPixelSettings.btnVideoFps60.setBackgroundResource(
            if (!is30) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnVideoFps60.setTextColor(
            if (!is30) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        binding.panelPixelSettings.btnVideoEisAuto.setBackgroundResource(
            if (selectedVideoEis) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnVideoEisAuto.setTextColor(
            if (selectedVideoEis) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        binding.panelPixelSettings.btnVideoEisOff.setBackgroundResource(
            if (!selectedVideoEis) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnVideoEisOff.setTextColor(
            if (!selectedVideoEis) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        val resLabel = if (selectedVideoRes == "uhd") "4K UHD (3840x2160)" else "1080p FHD (1920x1080)"
        binding.panelPixelSettings.tvVideoSummary.text = "Seçilen: $resLabel @ ${selectedVideoFps} FPS"
        updateQuickSettingsPill()
    }

    private fun selectVideoResolution(res: String) {
        selectedVideoRes = res
        prefs().edit().putString("pref_video_res", res).apply()
        refreshVideoSettingsUi()
    }

    private fun selectVideoFps(fps: Int) {
        selectedVideoFps = fps
        prefs().edit().putInt("pref_video_fps", fps).apply()
        refreshVideoSettingsUi()
    }

    private fun selectVideoEis(enabled: Boolean) {
        if (enabled && ::camera.isInitialized && !camera.isVideoStabilizationSupported()) {
            Toast.makeText(this, "Bu kamera video sabitlemeyi (EIS) uygulamalara açmıyor", Toast.LENGTH_SHORT).show()
        }
        selectedVideoEis = enabled
        prefs().edit().putBoolean("pref_video_eis", enabled).apply()
        refreshVideoSettingsUi()
    }

    private fun refreshPhotoSettingsUi() {
        binding.panelPixelSettings.btnPhoto12Mp.setBackgroundResource(
            if (!is50MpEnabled) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnPhoto12Mp.setTextColor(
            if (!is50MpEnabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        binding.panelPixelSettings.btnPhoto50Mp.setBackgroundResource(
            if (is50MpEnabled) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnPhoto50Mp.setTextColor(
            if (is50MpEnabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )

        val ratios = listOf(
            binding.panelPixelSettings.btnPhotoRatio43 to AspectRatio.RATIO_4_3,
            binding.panelPixelSettings.btnPhotoRatio169 to AspectRatio.RATIO_16_9,
            binding.panelPixelSettings.btnPhotoRatio11 to AspectRatio.RATIO_1_1,
            binding.panelPixelSettings.btnPhotoRatioFull to AspectRatio.RATIO_FULL
        )
        ratios.forEach { (btn, ratio) ->
            val sel = (currentAspectRatio == ratio)
            btn.setBackgroundResource(if (sel) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal)
            btn.setTextColor(if (sel) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }

        val timers = listOf(
            binding.panelPixelSettings.btnPhotoTimerOff to TimerMode.OFF,
            binding.panelPixelSettings.btnPhotoTimer3s to TimerMode.THREE,
            binding.panelPixelSettings.btnPhotoTimer10s to TimerMode.TEN
        )
        timers.forEach { (btn, t) ->
            val sel = (timerMode == t)
            btn.setBackgroundResource(if (sel) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal)
            btn.setTextColor(if (sel) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }

        val flashes = listOf(
            binding.panelPixelSettings.btnPhotoFlashOff to FlashMode.OFF,
            binding.panelPixelSettings.btnPhotoFlashAuto to FlashMode.AUTO,
            binding.panelPixelSettings.btnPhotoFlashOn to FlashMode.ON
        )
        flashes.forEach { (btn, f) ->
            val sel = (flashMode == f)
            btn.setBackgroundResource(if (sel) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal)
            btn.setTextColor(if (sel) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }

        binding.panelPixelSettings.btnPhotoRawOff.setBackgroundResource(
            if (!isExpertRawEnabled) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnPhotoRawOff.setTextColor(
            if (!isExpertRawEnabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )
        binding.panelPixelSettings.btnPhotoRawOn.setBackgroundResource(
            if (isExpertRawEnabled) R.drawable.bg_pixel_pill_selected else R.drawable.bg_pixel_pill_normal
        )
        binding.panelPixelSettings.btnPhotoRawOn.setTextColor(
            if (isExpertRawEnabled) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        )
        updateQuickSettingsPill()
    }

    private fun selectPhotoFlash(mode: FlashMode) {
        flashMode = mode
        if (::camera.isInitialized) camera.setFlash(flashMode)
        refreshPhotoSettingsUi()
    }

    private fun selectPhotoRes(is50Mp: Boolean) {
        if (is50MpEnabled != is50Mp) toggleResolution()
        refreshPhotoSettingsUi()
    }

    private fun selectPhotoRatio(ratio: AspectRatio) {
        currentAspectRatio = ratio
        if (::camera.isInitialized && !currentMode.isVideo) camera.aspectRatio = currentAspectRatio
        refreshPhotoSettingsUi()
    }

    private fun selectPhotoTimer(timer: TimerMode) {
        timerMode = timer
        refreshPhotoSettingsUi()
    }

    private fun selectPhotoRaw(raw: Boolean) {
        if (isExpertRawEnabled != raw) toggleExpertRaw()
        refreshPhotoSettingsUi()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTopBar() {
        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnTimer.setOnClickListener { cycleTimer() }
        binding.btnSettings.setOnClickListener {
            // Kısa dokunuş: Pixel hızlı ayarlar paneli, uzun dokunuş: sistem ayarları
            toggleQuickSettings()
        }
        binding.btnSettings.setOnLongClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        binding.pillQuickSettings.setOnClickListener { toggleQuickSettings() }
        binding.viewSettingsScrim.setOnClickListener { closeQuickSettings() }
        binding.panelPixelSettings.btnPixelSettingsClose.setOnClickListener { closeQuickSettings() }

        // Video ayar tıklayıcıları
        binding.panelPixelSettings.btnVideoResFhd.setOnClickListener { selectVideoResolution("fhd") }
        binding.panelPixelSettings.btnVideoRes4k.setOnClickListener { selectVideoResolution("uhd") }
        binding.panelPixelSettings.btnVideoFps30.setOnClickListener { selectVideoFps(30) }
        binding.panelPixelSettings.btnVideoFps60.setOnClickListener { selectVideoFps(60) }
        binding.panelPixelSettings.btnVideoEisAuto.setOnClickListener { selectVideoEis(true) }
        binding.panelPixelSettings.btnVideoEisOff.setOnClickListener { selectVideoEis(false) }

        // Fotoğraf ayar tıklayıcıları
        binding.panelPixelSettings.btnPhoto12Mp.setOnClickListener { selectPhotoRes(false) }
        binding.panelPixelSettings.btnPhoto50Mp.setOnClickListener { selectPhotoRes(true) }
        binding.panelPixelSettings.btnPhotoRatio43.setOnClickListener { selectPhotoRatio(AspectRatio.RATIO_4_3) }
        binding.panelPixelSettings.btnPhotoRatio169.setOnClickListener { selectPhotoRatio(AspectRatio.RATIO_16_9) }
        binding.panelPixelSettings.btnPhotoRatio11.setOnClickListener { selectPhotoRatio(AspectRatio.RATIO_1_1) }
        binding.panelPixelSettings.btnPhotoRatioFull.setOnClickListener { selectPhotoRatio(AspectRatio.RATIO_FULL) }
        binding.panelPixelSettings.btnPhotoTimerOff.setOnClickListener { selectPhotoTimer(TimerMode.OFF) }
        binding.panelPixelSettings.btnPhotoTimer3s.setOnClickListener { selectPhotoTimer(TimerMode.THREE) }
        binding.panelPixelSettings.btnPhotoTimer10s.setOnClickListener { selectPhotoTimer(TimerMode.TEN) }
        binding.panelPixelSettings.btnPhotoRawOff.setOnClickListener { selectPhotoRaw(false) }
        binding.panelPixelSettings.btnPhotoRawOn.setOnClickListener { selectPhotoRaw(true) }
        binding.panelPixelSettings.btnPhotoFlashOff.setOnClickListener { selectPhotoFlash(FlashMode.OFF) }
        binding.panelPixelSettings.btnPhotoFlashAuto.setOnClickListener { selectPhotoFlash(FlashMode.AUTO) }
        binding.panelPixelSettings.btnPhotoFlashOn.setOnClickListener { selectPhotoFlash(FlashMode.ON) }

        binding.btnResolution.setOnClickListener { toggleResolution() }
        binding.btnAspectRatio.setOnClickListener { cycleAspectRatio() }
        binding.btnRaw.setOnClickListener { toggleExpertRaw() }

        val glassButtons = listOf(
            binding.btnSettings, binding.pillQuickSettings, binding.btnFlash
        )
        glassButtons.forEach { btn ->
            btn.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }
                false
            }
        }
    }

    private var isExpertRawEnabled = false

    private fun toggleExpertRaw() {
        isExpertRawEnabled = !isExpertRawEnabled
        if (isExpertRawEnabled) {
            binding.btnRaw.setBackgroundResource(R.drawable.bg_glass_button_active)
            binding.btnRaw.setTextColor(android.graphics.Color.BLACK)
        } else {
            binding.btnRaw.setBackgroundResource(R.drawable.bg_glass_button)
            binding.btnRaw.setTextColor(android.graphics.Color.WHITE)
        }
        if (::camera.isInitialized) camera.rawEnabled = isExpertRawEnabled
        val msg = when {
            !isExpertRawEnabled -> "RAW kapalı (yalnızca JPEG)"
            ::camera.isInitialized && !camera.isRawSupported() -> "Bu kamera RAW desteklemiyor (yalnızca işlenmiş DNG)"
            else -> "RAW açık (JPEG + sensör DNG)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private var is50MpEnabled = false

    /**
     * Açık kameranın gerçekten desteklediği seçenekleri etkin bırakır, desteklemediklerini soluklaştırır.
     * (Galaxy A56: 50MP, 60 FPS, video sabitleme ve RAW üçüncü taraf uygulamalara kapalı.)
     */
    private fun applyCapabilityUi() {
        if (!::camera.isInitialized) return
        val p = binding.panelPixelSettings
        fun setAvailable(v: android.view.View, ok: Boolean) { v.isEnabled = ok; v.alpha = if (ok) 1f else 0.35f }

        val highRes = camera.supportsHighResPhoto()
        camera.photoMegapixels(false).takeIf { it > 0 }?.let { p.btnPhoto12Mp.text = "$it MP" }
        camera.photoMegapixels(true).takeIf { highRes && it > 0 }?.let { p.btnPhoto50Mp.text = "$it MP" }
        if (!highRes && is50MpEnabled) toggleResolution()
        binding.btnResolution.isVisible = highRes
        setAvailable(p.btnPhoto50Mp, highRes)

        val fps60 = camera.supportsVideo(1920, 1080, 60)
        if (!fps60 && selectedVideoFps == 60) { selectedVideoFps = 30; prefs().edit().putInt("pref_video_fps", 30).apply() }
        setAvailable(p.btnVideoFps60, fps60)

        val uhd = camera.supportsVideo(3840, 2160, 30)
        if (!uhd && selectedVideoRes == "uhd") { selectedVideoRes = "fhd"; prefs().edit().putString("pref_video_res", "fhd").apply() }
        setAvailable(p.btnVideoRes4k, uhd)

        val eis = camera.isVideoStabilizationSupported()
        setAvailable(p.btnVideoEisAuto, eis)
        if (!eis) selectedVideoEis = false

        setAvailable(p.btnPhotoRawOn, camera.isRawSupported())

        refreshVideoSettingsUi()
        refreshPhotoSettingsUi()
        updateQuickSettingsPill()
        if (currentMode == CaptureMode.PRO) refreshProLabels()
    }

    private fun toggleResolution() {
        is50MpEnabled = !is50MpEnabled
        val mp = if (::camera.isInitialized) camera.photoMegapixels(is50MpEnabled) else 0
        binding.btnResolution.text = if (mp > 0) "${mp}MP" else if (is50MpEnabled) "50MP" else "12MP"
        if (is50MpEnabled) {
            binding.btnResolution.setBackgroundResource(R.drawable.bg_glass_button_active)
            binding.btnResolution.setTextColor(android.graphics.Color.BLACK)
        } else {
            binding.btnResolution.setBackgroundResource(R.drawable.bg_glass_button)
            binding.btnResolution.setTextColor(android.graphics.Color.WHITE)
        }
        if (::camera.isInitialized) camera.isHighRes50Mp = is50MpEnabled
        Toast.makeText(this, if (is50MpEnabled) "${binding.btnResolution.text} yüksek çözünürlük" else "${binding.btnResolution.text} hızlı çekim", Toast.LENGTH_SHORT).show()
    }

    private fun cycleAspectRatio() {
        currentAspectRatio = when (currentAspectRatio) {
            AspectRatio.RATIO_4_3 -> AspectRatio.RATIO_16_9
            AspectRatio.RATIO_16_9 -> AspectRatio.RATIO_1_1
            AspectRatio.RATIO_1_1 -> AspectRatio.RATIO_FULL
            AspectRatio.RATIO_FULL -> AspectRatio.RATIO_4_3
        }
        binding.btnAspectRatio.text = currentAspectRatio.label
        if (::camera.isInitialized && !currentMode.isVideo) camera.aspectRatio = currentAspectRatio
        Toast.makeText(this, "Fotoğraf Oranı: ${currentAspectRatio.label}", Toast.LENGTH_SHORT).show()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            FlashMode.AUTO -> FlashMode.ON
            FlashMode.ON -> FlashMode.OFF
            FlashMode.OFF -> FlashMode.TORCH
            FlashMode.TORCH -> FlashMode.AUTO
        }
        binding.btnFlash.setImageResource(
            when (flashMode) {
                FlashMode.AUTO -> R.drawable.ic_flash_auto
                FlashMode.ON, FlashMode.TORCH -> R.drawable.ic_flash_on
                FlashMode.OFF -> R.drawable.ic_flash_off
            }
        )
        camera.setFlash(flashMode)
    }

    private fun cycleTimer() {
        timerMode = when (timerMode) {
            TimerMode.OFF -> TimerMode.THREE
            TimerMode.THREE -> TimerMode.TEN
            TimerMode.TEN -> TimerMode.OFF
        }
        if (timerMode != TimerMode.OFF) {
            binding.btnTimer.setBackgroundResource(R.drawable.bg_glass_button_active)
        } else {
            binding.btnTimer.setBackgroundResource(R.drawable.bg_glass_button)
        }
        Toast.makeText(this, when (timerMode) {
            TimerMode.OFF -> R.string.timer_off
            TimerMode.THREE -> R.string.timer_3s
            TimerMode.TEN -> R.string.timer_10s
        }, Toast.LENGTH_SHORT).show()
    }

    /** Izgara Ayarlar → "Izgara" seçeneğinden gelir (eskiden ekranda açacak bir düğme yoktu). */
    private fun applyGrid() {
        gridType = when (prefs().getString("pref_grid", "off")) {
            "3x3" -> GridType.GRID_3X3
            "square" -> GridType.SQUARE
            "golden" -> GridType.GOLDEN
            else -> GridType.OFF
        }
        binding.gridOverlay.gridType = gridType
    }

    private fun setupZoomBar() {
        binding.btnZoomUltra.setOnClickListener { selectModule(CameraModule.ULTRA_WIDE) }
        binding.btnZoomWide.setOnClickListener { selectModule(CameraModule.WIDE) }
        binding.btnZoomTele.setOnClickListener { selectModule(CameraModule.TELE_2X) }
    }

    private fun selectModule(module: CameraModule) {
        if (isRecording) return
        if (!camera.selectModule(module)) return

        val selectedBg = R.drawable.bg_zoom_button_selected
        val unselectedBg = R.drawable.bg_circle_control_translucent
        
        binding.btnZoomUltra.apply {
            background = androidx.core.content.ContextCompat.getDrawable(this@CameraActivity, if (module == CameraModule.ULTRA_WIDE) selectedBg else unselectedBg)
            setTextColor(if (module == CameraModule.ULTRA_WIDE) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        binding.btnZoomWide.apply {
            background = androidx.core.content.ContextCompat.getDrawable(this@CameraActivity, if (module == CameraModule.WIDE) selectedBg else unselectedBg)
            setTextColor(if (module == CameraModule.WIDE) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        binding.btnZoomTele.apply {
            background = androidx.core.content.ContextCompat.getDrawable(this@CameraActivity, if (module == CameraModule.TELE_2X) selectedBg else unselectedBg)
            setTextColor(if (module == CameraModule.TELE_2X) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isRecording) {
                    binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(300).start()
                    camera.toggleLens()
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                // Uzun basma: AF/AE kilidi (eskiden dokunma dinleyicisi uzun basmayı yuttuğu için hiç çalışmıyordu)
                if (scaleDetector.isInProgress || camera.focus.isManual()) return
                camera.focusAt(e.x, e.y, lock = true)
                binding.focusRing.showAt(e.x, e.y, isLocked = true, currentEvProgress = 0f, evLabel = "KİLİTLİ")
                Toast.makeText(this@CameraActivity, "AF/AE kilitlendi (açmak için başka yere dokunun)", Toast.LENGTH_SHORT).show()
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                camera.focusAt(e.x, e.y, lock = false)
                val evRange = camera.exposure.evRange
                val currentComp = camera.exposure.exposureCompensation
                val progress = if (currentComp >= 0) {
                    if (evRange.upper != 0) currentComp.toFloat() / evRange.upper else 0f
                } else {
                    if (evRange.lower != 0) -currentComp.toFloat() / evRange.lower else 0f
                }
                binding.focusRing.showAt(
                    x = e.x,
                    y = e.y,
                    isLocked = false,
                    currentEvProgress = progress,
                    evLabel = camera.exposure.evLabel()
                )
                binding.previewView.performClick()
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.scaleZoom(detector.scaleFactor)
                showZoomRatioLabel()
                return true
            }
        })

        binding.previewView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastDragTouchY = event.y
                    isExposureDragging = binding.focusRing.isRingActive() && binding.focusRing.isPointNear(event.x, event.y)
                    if (isExposureDragging) {
                        binding.focusRing.keepAlive()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && binding.focusRing.isRingActive()) {
                        val dy = lastDragTouchY - event.y // yukarı = pozitif (+EV), aşağı = negatif (-EV)

                        if (isExposureDragging || (Math.abs(dy) > 8 && binding.focusRing.isPointNear(event.x, event.y))) {
                            isExposureDragging = true
                            binding.focusRing.keepAlive()

                            val evRange = camera.exposure.evRange
                            val totalSpan = (evRange.upper - evRange.lower).coerceAtLeast(1)
                            val density = resources.displayMetrics.density
                            val dragDistanceForFullRange = 160f * density

                            val deltaStep = (dy / dragDistanceForFullRange) * totalSpan
                            if (Math.abs(deltaStep) >= 0.15f) {
                                val oldComp = camera.exposure.exposureCompensation
                                val newComp = (oldComp + Math.round(deltaStep)).coerceIn(evRange.lower, evRange.upper)
                                if (newComp != oldComp) {
                                    camera.exposure.exposureCompensation = newComp
                                    camera.refreshPreview()
                                    if (currentMode == CaptureMode.PRO) {
                                        refreshProLabels()
                                    }
                                }
                                lastDragTouchY = event.y

                                val progress = if (newComp >= 0) {
                                    if (evRange.upper != 0) newComp.toFloat() / evRange.upper else 0f
                                } else {
                                    if (evRange.lower != 0) -newComp.toFloat() / evRange.lower else 0f
                                }
                                binding.focusRing.updateExposure(progress, camera.exposure.evLabel())
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isExposureDragging) {
                        isExposureDragging = false
                        binding.focusRing.scheduleFadeOut(2500L)
                    }
                }
            }
            true
        }

    }

    private val hideZoomLabel = Runnable {
        binding.zoomRatioLabel.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { binding.zoomRatioLabel.isVisible = false }
            .start()
    }

    private fun showZoomRatioLabel() {
        val label = binding.zoomRatioLabel
        label.text = camera.zoom.label()
        val handler = binding.root.handler ?: return
        handler.removeCallbacks(hideZoomLabel)
        if (!label.isVisible) {
            label.alpha = 0f
            label.isVisible = true
            label.animate().alpha(1f).setDuration(120).start()
        } else {
            label.alpha = 1f
        }
        handler.postDelayed(hideZoomLabel, 1200)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupShutter() {
        binding.btnShutter.setOnClickListener { onShutterPressed() }
        binding.btnShutter.setOnLongClickListener {
            if (!currentMode.isVideo) {
                onShutterPressed()
                true
            } else false
        }
        binding.btnShutter.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            false
        }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.btnSwitchCamera.setOnClickListener {
            if (!isRecording) {
                it.animate().rotationBy(180f).setDuration(300).start()
                camera.toggleLens()
            }
        }
    }

    private fun onShutterPressed() {
        if (!cameraStarted) return
        if (timerRunning) { cancelTimer(); return }   // geri sayım sırasında tekrar basmak iptal eder
        if (timerMode != TimerMode.OFF && !currentMode.isVideo && currentMode != CaptureMode.PANORAMA && !isRecording) startTimerThenCapture()
        else performCapture()
    }

    private fun startTimerThenCapture() {
        var remaining = timerMode.seconds
        timerRunning = true
        binding.statusChip.isVisible = true
        val tick = object : Runnable {
            override fun run() {
                if (!timerRunning) return
                if (remaining <= 0) {
                    timerRunning = false
                    binding.statusChip.isVisible = false
                    performCapture()
                } else {
                    binding.statusChip.text = remaining.toString()
                    remaining--
                    binding.root.postDelayed(this, 1000)
                }
            }
        }
        timerRunnable = tick
        binding.root.post(tick)
    }

    private fun cancelTimer() {
        timerRunning = false
        timerRunnable?.let { binding.root.removeCallbacks(it) }
        timerRunnable = null
        binding.statusChip.isVisible = false
    }

    private fun performCapture() {
        // Çok kareli çekim sürerken ikinci bir çekim kareleri karıştırıyordu
        if ((currentMode == CaptureMode.NIGHT || currentMode == CaptureMode.PORTRAIT) && camera.isBurstInProgress()) {
            Toast.makeText(this, "Önceki çekim bitmeden yeni çekim yapılamaz", Toast.LENGTH_SHORT).show()
            return
        }
        when (currentMode) {
            CaptureMode.VIDEO -> toggleVideo()
            CaptureMode.TIME_LAPSE -> toggleTimeLapse()
            CaptureMode.NIGHT -> captureNight()
            CaptureMode.PORTRAIT -> capturePortrait()
            CaptureMode.PANORAMA -> togglePanorama()
            CaptureMode.PRO -> capturePro()
            else -> capturePhoto()
        }
    }

    private fun capturePro() {
        animateShutter()
        binding.statusChip.isVisible = true
        binding.statusChip.text = "Pro Çekim (${currentProProfile.displayNameTr})..."

        camera.capturePro(
            location = currentLocation(),
            deviceRotation = deviceRotationDegrees(),
            profile = currentProProfile,
            onProgress = { step ->
                runOnUiThread {
                    binding.statusChip.isVisible = true
                    binding.statusChip.text = step
                }
            },
            onSaved = { uri, info ->
                runOnUiThread {
                    binding.statusChip.isVisible = false
                    if (deliverCaptureResult(uri)) return@runOnUiThread
                    val msg = if (info.isNotEmpty()) "Pro Fotoğraf Kaydedildi\n$info" else "Pro Fotoğraf Kaydedildi"
                    Toast.makeText(this@CameraActivity, msg, Toast.LENGTH_SHORT).show()
                    updateGalleryThumb(uri)
                }
            },
            onError = {
                runOnUiThread {
                    binding.statusChip.isVisible = false
                    Toast.makeText(this@CameraActivity, "Pro çekim hatası", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun capturePortrait() {
        animateShutter()
        binding.statusChip.isVisible = true
        binding.statusChip.text = "Portre Modu (3D Derinlik NPU...)"

        camera.capturePortrait(
            location = currentLocation(),
            deviceRotation = deviceRotationDegrees(),
            onProgress = { step ->
                runOnUiThread {
                    binding.statusChip.isVisible = true
                    binding.statusChip.text = step
                }
            },
            onSaved = { uri ->
                runOnUiThread {
                    binding.statusChip.isVisible = false
                    if (deliverCaptureResult(uri)) return@runOnUiThread
                    Toast.makeText(this@CameraActivity, "Portre Fotoğrafı Kaydedildi", Toast.LENGTH_SHORT).show()
                    updateGalleryThumb(uri)
                }
            },
            onError = {
                runOnUiThread {
                    binding.statusChip.isVisible = false
                    Toast.makeText(this@CameraActivity, "Portre çekim hatası", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private var moonPulseAnimator: android.animation.ObjectAnimator? = null

    private fun startNightCaptureUi() {
        binding.nightCaptureOverlay.alpha = 0f
        binding.nightCaptureOverlay.isVisible = true
        binding.nightCaptureOverlay.animate().alpha(1f).setDuration(250).start()
        binding.nightCaptureLabel.text = "✨ Sabit Tutun..."

        // Parlayan altın ay ikonu nabız animasyonu
        moonPulseAnimator?.cancel()
        val scaleX = android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 0.85f, 1.20f, 0.85f)
        val scaleY = android.animation.PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 0.85f, 1.20f, 0.85f)
        moonPulseAnimator = android.animation.ObjectAnimator.ofPropertyValuesHolder(binding.nightMoonIcon, scaleX, scaleY).apply {
            duration = 1000
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopNightCaptureUi() {
        moonPulseAnimator?.cancel()
        binding.nightCaptureOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            binding.nightCaptureOverlay.isVisible = false
            binding.nightCaptureOverlay.alpha = 1f
        }.start()
    }

    private fun captureNight() {
        animateShutter()
        startNightCaptureUi()

        camera.captureNight(
            location = currentLocation(),
            deviceRotation = deviceRotationDegrees(),
            onProgress = { step ->
                runOnUiThread {
                    if (step == "DONE_CAPTURE") {
                        stopNightCaptureUi()
                        Toast.makeText(this@CameraActivity, "✨ Çekildi! Galeride işleniyor...", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.nightCaptureLabel.text = "✨ $step"
                    }
                }
            },
            onSaved = { uri ->
                runOnUiThread {
                    stopNightCaptureUi()
                    if (deliverCaptureResult(uri)) return@runOnUiThread
                    updateGalleryThumb(uri)
                    Toast.makeText(this@CameraActivity, "📸 Gece Fotoğrafı Hazır!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = {
                runOnUiThread {
                    stopNightCaptureUi()
                    Toast.makeText(this@CameraActivity, "Gece çekim hatası", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private var physicalOrientationDegrees = 0
    private var orientationEventListener: android.view.OrientationEventListener? = null

    private fun setupOrientationListener() {
        orientationEventListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                physicalOrientationDegrees = when ((orientation + 45) / 90 * 90 % 360) {
                    0 -> 0
                    90 -> 90
                    180 -> 180
                    270 -> 270
                    else -> 0
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    private fun deviceRotationDegrees(): Int = physicalOrientationDegrees

    private fun capturePhoto() {
        animateShutter()
        binding.statusChip.isVisible = false
        camera.capturePhoto(
            location = currentLocation(),
            deviceRotation = deviceRotationDegrees(),
            onSaved = { uri ->
                runOnUiThread {
                    binding.statusChip.isVisible = false
                    if (deliverCaptureResult(uri)) return@runOnUiThread
                    Toast.makeText(this@CameraActivity, R.string.photo_saved, Toast.LENGTH_SHORT).show()
                    updateGalleryThumb(uri)
                }
            },
            onErr = {
                runOnUiThread {
                    binding.statusChip.isVisible = false
                    Toast.makeText(this@CameraActivity, R.string.error_capture, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun toggleVideo() {
        if (isRecording) {
            stopVideo(returnToPreview = true)
        } else {
            val hasAudio = PermissionUtils.isGranted(this, Manifest.permission.RECORD_AUDIO)
            if (!hasAudio && !audioPermissionAsked) {
                audioPermissionAsked = true
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)  // sonuçta startVideoRecording()
                return
            }
            startVideoRecording()
        }
    }

    /** Seçilen kaliteyi, kamera desteklemiyorsa desteklenen en yakın kaliteye düşürür. */
    private fun supportedVideoQuality(): VideoCapture.Quality {
        var q = videoQuality()
        if (!camera.supportsVideo(q.size.width, q.size.height, q.fps)) {
            val fallback = when (q) {
                VideoCapture.Quality.UHD_60 -> if (camera.supportsVideo(3840, 2160, 30)) VideoCapture.Quality.UHD_30 else VideoCapture.Quality.FHD_60
                VideoCapture.Quality.UHD_30 -> VideoCapture.Quality.FHD_30
                VideoCapture.Quality.FHD_60 -> VideoCapture.Quality.FHD_30
                else -> q
            }
            if (fallback != q) {
                Toast.makeText(this, "${q.size.height}p@${q.fps} bu kamerada yok, ${fallback.size.height}p@${fallback.fps} kullanılıyor", Toast.LENGTH_SHORT).show()
                q = if (!camera.supportsVideo(fallback.size.width, fallback.size.height, fallback.fps)) VideoCapture.Quality.FHD_30 else fallback
            }
        }
        return q
    }

    private fun startVideoRecording() {
        if (isRecording || currentMode != CaptureMode.VIDEO) return
        val withAudio = PermissionUtils.isGranted(this, Manifest.permission.RECORD_AUDIO)
        val quality = supportedVideoQuality()
        val codec = if (prefs().getString("pref_video_codec", "h264") == "h265")
            VideoCapture.Codec.H265 else VideoCapture.Codec.H264
        val surface = videoCapture.prepare(quality, codec, withAudio, camera.currentDeviceRotationOrientation(deviceRotationDegrees()))
        if (surface == null) {
            Toast.makeText(this, R.string.error_capture, Toast.LENGTH_SHORT).show(); return
        }
        closeQuickSettings()
        isRecording = true
        val eis = selectedVideoEis && camera.isVideoStabilizationSupported()
        camera.startVideoSession(surface, fps = quality.fps, stabilization = eis) {
            videoCapture.start()
            playSound(android.media.MediaActionSound.START_VIDEO_RECORDING)
            binding.statusChip.isVisible = true
            binding.statusChip.text = getString(R.string.recording)
        }
    }

    /**
     * Kaydı durdurur ve KAYDEDER. Eskiden uygulamadan çıkınca/ekran kilitlenince kayıt iptal edilip
     * dosya siliniyordu.
     */
    private fun stopVideo(returnToPreview: Boolean) {
        camera.prepareStopRecording()
        val uri = videoCapture.stop()
        isRecording = false
        binding.statusChip.isVisible = false
        playSound(android.media.MediaActionSound.STOP_VIDEO_RECORDING)
        if (returnToPreview) camera.stopRecordingReturnPreview()
        if (uri != null) {
            Toast.makeText(this, R.string.video_saved, Toast.LENGTH_SHORT).show()
            updateGalleryThumb(uri)
        } else Toast.makeText(this, R.string.error_capture, Toast.LENGTH_SHORT).show()
    }

    private fun toggleTimeLapse() {
        if (isRecording) {
            stopTimeLapse(returnToPreview = true)
        } else {
            val interval = when (prefs().getString("pref_timelapse_interval", "1")) {
                "0.5" -> TimeLapseCapture.Interval.HALF
                "2" -> TimeLapseCapture.Interval.TWO
                "5" -> TimeLapseCapture.Interval.FIVE
                "10" -> TimeLapseCapture.Interval.TEN
                "30" -> TimeLapseCapture.Interval.THIRTY
                else -> TimeLapseCapture.Interval.ONE
            }
            val surface = timeLapse.prepare(interval, orientationHint = camera.currentDeviceRotationOrientation(deviceRotationDegrees())) ?: run {
                Toast.makeText(this, R.string.error_capture, Toast.LENGTH_SHORT).show(); return
            }
            isRecording = true
            camera.startVideoSession(surface) {
                timeLapse.start()
                binding.statusChip.isVisible = true
                binding.statusChip.text = getString(R.string.recording)
            }
        }
    }

    private fun stopTimeLapse(returnToPreview: Boolean) {
        camera.prepareStopRecording()
        val uri = timeLapse.stop()
        isRecording = false
        binding.statusChip.isVisible = false
        if (returnToPreview) camera.stopRecordingReturnPreview()
        if (uri != null) { Toast.makeText(this, R.string.video_saved, Toast.LENGTH_SHORT).show(); updateGalleryThumb(uri) }
        else Toast.makeText(this, "Kayıt çok kısa: henüz hiç kare alınmadı", Toast.LENGTH_SHORT).show()
    }

    /**
     * Panorama: kareler önizlemeden alınır (galeriye kaydedilmez), durdurunca birleştirilir.
     * Eskiden her açı adımında galeriye ayrı fotoğraf kaydediliyor ve birleştirme hiç çalışmıyordu.
     */
    private fun togglePanorama() {
        if (isRecording) {
            stopPanorama()
        } else {
            panorama.horizontalFovRad = camera.portraitHorizontalFovRad()
            panorama.onAngleUpdate = { angle ->
                runOnUiThread { binding.statusChip.text = "${angle.toInt()}° · ${panorama.frameCount()} kare — bitirmek için tekrar basın" }
            }
            panorama.onCaptureFrameNeeded = { runOnUiThread { grabPanoramaFrame() } }
            isRecording = true
            binding.statusChip.isVisible = true
            binding.statusChip.text = "Telefonu dik tutup yavaşça yana çevirin"
            if (!panorama.start()) {
                isRecording = false
                binding.statusChip.isVisible = false
                Toast.makeText(this, "Bu cihazda jiroskop yok, panorama çekilemez", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun grabPanoramaFrame() {
        if (!binding.previewView.isAvailable) return
        val full = binding.previewView.bitmap ?: return
        val r = binding.previewView.contentRect()
        val crop = try {
            android.graphics.Bitmap.createBitmap(full, r.left.toInt().coerceAtLeast(0), r.top.toInt().coerceAtLeast(0),
                r.width().toInt().coerceAtMost(full.width), r.height().toInt().coerceAtMost(full.height))
        } catch (_: Exception) { full }
        if (crop !== full) full.recycle()
        playSound(android.media.MediaActionSound.FOCUS_COMPLETE)
        panoExecutor.execute { panorama.addFrame(crop); crop.recycle() }
    }

    private fun stopPanorama() {
        panorama.stop()
        isRecording = false
        binding.statusChip.text = "Panorama birleştiriliyor..."
        panoExecutor.execute {
            val uri = panorama.stitchAndSave()
            runOnUiThread {
                binding.statusChip.isVisible = false
                if (uri != null) { Toast.makeText(this, "Panorama kaydedildi", Toast.LENGTH_SHORT).show(); updateGalleryThumb(uri) }
                else Toast.makeText(this, "Panorama için en az 2 kare gerekli: telefonu biraz daha çevirin", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun playSound(sound: Int) {
        if (prefs().getBoolean("pref_capture_sound", true)) {
            try { shutterSound.play(sound) } catch (_: Throwable) {}
        }
    }

    private fun animateShutter() {
        playSound(android.media.MediaActionSound.SHUTTER_CLICK)
        binding.previewView.animate().alpha(0.3f).setDuration(60).withEndAction {
            binding.previewView.animate().alpha(1f).setDuration(60).start()
        }.start()
    }

    private fun setupProPanel() {
        val panel = binding.proPanel
        panel.paramIso.paramLabel.text = getString(R.string.pro_iso)
        panel.paramShutter.paramLabel.text = getString(R.string.pro_shutter)
        panel.paramFocus.paramLabel.text = getString(R.string.pro_focus)
        panel.paramWb.paramLabel.text = getString(R.string.pro_wb)
        panel.paramEv.paramLabel.text = getString(R.string.pro_ev)
        panel.paramProfile.paramLabel.text = getString(R.string.pro_profile)
        panel.paramProfile.paramValue.text = currentProProfile.displayNameTr
        refreshProLabels()

        panel.paramIso.root.setOnClickListener { openProSlider(ProParam.ISO) }
        panel.paramShutter.root.setOnClickListener { openProSlider(ProParam.SHUTTER) }
        panel.paramFocus.root.setOnClickListener { openProSlider(ProParam.FOCUS) }
        panel.paramWb.root.setOnClickListener { openProSlider(ProParam.WB) }
        panel.paramEv.root.setOnClickListener { openProSlider(ProParam.EV) }
        panel.paramProfile.root.setOnClickListener { cycleProProfile() }

        panel.btnProReset.setOnClickListener {
            camera.exposure.reset()
            camera.focus.reset()
            camera.whiteBalance.reset()
            panel.proSliderRow.isVisible = false
            activeProParam = ProParam.NONE
            refreshProLabels()
            camera.refreshPreview()
            Toast.makeText(this, "Pro Ayarları Sıfırlandı (Otomatik)", Toast.LENGTH_SHORT).show()
        }

        panel.proSlider.addOnChangeListener { _, value, _ -> onProSliderChanged(value) }
    }

    private fun openProSlider(param: ProParam) {
        activeProParam = param
        val panel = binding.proPanel
        panel.proSliderRow.isVisible = true
        panel.proSlider.valueFrom = 0f
        panel.proSlider.valueTo = 100f
        panel.proSlider.value = when (param) {
            ProParam.ISO -> camera.exposure.isoProgress() * 100f
            ProParam.SHUTTER -> camera.exposure.shutterProgress() * 100f
            ProParam.FOCUS -> camera.focus.focusProgress() * 100f
            ProParam.WB -> camera.whiteBalance.kelvinProgress() * 100f
            ProParam.EV -> {
                val r = camera.exposure.evRange
                if (r.upper == r.lower) 50f
                else ((camera.exposure.exposureCompensation - r.lower).toFloat() / (r.upper - r.lower)) * 100f
            }
            ProParam.PROFILE, ProParam.NONE -> 0f
        }
    }

    private fun onProSliderChanged(value: Float) {
        val p = value / 100f
        when (activeProParam) {
            ProParam.ISO -> camera.exposure.setIsoFromProgress(p)
            ProParam.SHUTTER -> camera.exposure.setShutterFromProgress(p)
            ProParam.FOCUS -> camera.focus.setFocusFromProgress(p)
            ProParam.WB -> camera.whiteBalance.setKelvinFromProgress(p)
            ProParam.EV -> {
                val r = camera.exposure.evRange
                camera.exposure.exposureCompensation = (r.lower + p * (r.upper - r.lower)).toInt()
            }
            ProParam.PROFILE, ProParam.NONE -> {}
        }
        binding.proPanel.proSliderValue.text = when (activeProParam) {
            ProParam.ISO -> camera.exposure.isoLabel()
            ProParam.SHUTTER -> camera.exposure.shutterLabel()
            ProParam.FOCUS -> camera.focus.focusLabel()
            ProParam.WB -> camera.whiteBalance.label()
            ProParam.EV -> camera.exposure.evLabel()
            ProParam.PROFILE -> currentProProfile.displayNameTr
            ProParam.NONE -> ""
        }
        refreshProLabels()
        camera.refreshPreview()
    }

    private fun refreshProLabels() {
        if (::camera.isInitialized) {
            // Telefonun izin vermediği elle ayarları gizle (A56: ISO/enstantane/odak kapalı).
            val panel = binding.proPanel
            val isoOk = camera.exposure.manualSupported
            val focusOk = camera.focus.manualFocusSupported
            panel.paramIso.root.isVisible = isoOk
            panel.paramShutter.root.isVisible = isoOk
            panel.paramFocus.root.isVisible = focusOk
            val hiddenActive = (!isoOk && (activeProParam == ProParam.ISO || activeProParam == ProParam.SHUTTER)) ||
                (!focusOk && activeProParam == ProParam.FOCUS)
            if (hiddenActive) { activeProParam = ProParam.NONE; panel.proSliderRow.isVisible = false }
        }
        binding.proPanel.paramIso.paramValue.text = camera.exposure.isoLabel()
        binding.proPanel.paramShutter.paramValue.text = camera.exposure.shutterLabel()
        binding.proPanel.paramFocus.paramValue.text = camera.focus.focusLabel()
        binding.proPanel.paramWb.paramValue.text = camera.whiteBalance.label()
        binding.proPanel.paramEv.paramValue.text = camera.exposure.evLabel()
        binding.proPanel.paramProfile.paramValue.text = currentProProfile.displayNameTr
    }

    private fun cycleProProfile() {
        proProfileIndex = (proProfileIndex + 1) % proProfiles.size
        camera.proProfile = currentProProfile
        binding.proPanel.paramProfile.paramValue.text = currentProProfile.displayNameTr
        updateProBadge()
        Toast.makeText(this, "Pro Profil: ${currentProProfile.displayNameTr}", Toast.LENGTH_SHORT).show()
    }

    private fun updateProBadge() {
        if (currentMode == CaptureMode.PRO) {
            binding.tvSceneBadge.text = "📸 PRO · ${currentProProfile.displayNameTr}"
            binding.tvSceneBadge.isVisible = true
            binding.tvSceneBadge.setOnClickListener {
                cycleProProfile()
            }
        }
    }

    /** Bu uygulamanın son kaydettiği fotoğraf/video (galeri düğmesi bunu gösterir). */
    private var lastCapturedUri: Uri? = null

    private fun updateGalleryThumb(uri: Uri? = null) {
        if (uri != null) lastCapturedUri = uri
        // Galeri izni istenmediği için MediaStore yalnızca bu uygulamanın kendi dosyalarını döndürür
        val target = uri ?: lastCapturedUri ?: MediaStoreHelper.latestImageUri(this) ?: return
        Glide.with(this).load(target).centerCrop().circleCrop().into(binding.btnGallery)
    }

    private fun openGallery() {
        val uri = lastCapturedUri ?: MediaStoreHelper.latestImageUri(this)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (uri != null) setDataAndType(uri, contentResolver.getType(uri) ?: "image/*") else type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (e: Exception) {
            Toast.makeText(this, "Galeri açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            && prefs().getBoolean("pref_volume_capture", true)) {
            onShutterPressed(); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(this)

    private fun locationTaggingEnabled(): Boolean =
        prefs().getBoolean("pref_location_tagging", false)

    /** Ayarlarda "Konum Etiketi" açıksa ve izin varsa en son bilinen konum; aksi halde null. */
    private fun currentLocation(): Location? =
        if (locationTaggingEnabled() && PermissionUtils.hasLocationPermission(this)) lastKnownLocation else null

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!locationTaggingEnabled()) return
        if (!PermissionUtils.hasLocationPermission(this)) {
            // İzin penceresi kapanınca onResume tekrar çalışır; reddedilirse sürekli sormamak için bir kez iste
            if (!locationPermissionAsked) {
                locationPermissionAsked = true
                locationPermissionLauncher.launch(PermissionUtils.locationPermissions)
            }
            return
        }
        val client = fusedLocation
            ?: com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                .also { fusedLocation = it }
        client.lastLocation.addOnSuccessListener { loc -> if (loc != null) lastKnownLocation = loc }
        val request = com.google.android.gms.location.LocationRequest.Builder(
            com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L
        ).setMinUpdateIntervalMillis(5_000L).build()
        try {
            client.requestLocationUpdates(request, locationCallback, android.os.Looper.getMainLooper())
        } catch (e: SecurityException) {
            android.util.Log.w("CameraActivity", "Konum güncellemesi başlatılamadı", e)
        }
    }

    private fun stopLocationUpdates() {
        fusedLocation?.removeLocationUpdates(locationCallback)
    }

    /**
     * ACTION_IMAGE_CAPTURE ile açıldıysak çekilen fotoğrafı çağıran uygulamaya teslim et.
     * EXTRA_OUTPUT verilmişse dosyayı oraya kopyalar, verilmemişse küçük bir önizleme döndürür.
     * @return istek teslim edildiyse true (aktivite kapanır)
     */
    private fun deliverCaptureResult(savedUri: Uri): Boolean {
        if (!isCaptureIntent) return false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val output = captureIntentOutput
                    if (output != null) {
                        contentResolver.openInputStream(savedUri)?.use { input ->
                            contentResolver.openOutputStream(output, "w")?.use { out -> input.copyTo(out) }
                        } != null
                    } else true
                } catch (e: Exception) {
                    android.util.Log.e("CameraActivity", "Çekim sonucu çağıran uygulamaya yazılamadı", e)
                    false
                }
            }
            if (!ok) {
                setResult(RESULT_CANCELED)
            } else if (captureIntentOutput != null) {
                setResult(RESULT_OK)
            } else {
                val thumb = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(savedUri)?.use { input ->
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                            android.graphics.BitmapFactory.decodeStream(input, null, opts)
                        }
                    } catch (_: Exception) { null }
                }
                setResult(RESULT_OK, Intent("inline-data").putExtra("data", thumb))
            }
            // Başka uygulama için çekilen fotoğraf kullanıcının galerisinde kalmasın
            // (ör. bir banka uygulamasına çekilen kimlik fotoğrafı)
            withContext(Dispatchers.IO) {
                try { contentResolver.delete(savedUri, null, null) } catch (_: Exception) {}
            }
            finish()
        }
        return true
    }

    private fun videoQuality(): VideoCapture.Quality = when {
        selectedVideoRes == "uhd" && selectedVideoFps == 60 -> VideoCapture.Quality.UHD_60
        selectedVideoRes == "uhd" -> VideoCapture.Quality.UHD_30
        selectedVideoFps == 60 -> VideoCapture.Quality.FHD_60
        else -> VideoCapture.Quality.FHD_30
    }

    override fun onResume() {
        super.onResume()
        enableFullscreen()
        histogramEnabled = prefs().getBoolean("pref_histogram", false)
        binding.histogram.isVisible = currentMode == CaptureMode.PRO || histogramEnabled
        histogramHandler.removeCallbacks(histogramTick)
        histogramHandler.post(histogramTick)
        startLocationUpdates()
        applyGrid()
        if (::camera.isInitialized) {
            // Kullanıcı kamera iznini Ayarlar'dan verip geri döndüyse
            if (!cameraInitialized && PermissionUtils.hasAllPermissions(this)) initCamera()
            else startCameraIfPossible()
        }
    }

    override fun onPause() {
        super.onPause()
        histogramHandler.removeCallbacks(histogramTick)
        stopLocationUpdates()
        cancelTimer()
        if (isRecording) {
            // Kayıt iptal edilip silinmez, kaydedilir
            when (currentMode) {
                CaptureMode.VIDEO -> stopVideo(returnToPreview = false)
                CaptureMode.TIME_LAPSE -> stopTimeLapse(returnToPreview = false)
                CaptureMode.PANORAMA -> stopPanorama()
                else -> {}
            }
            isRecording = false
        }
        if (::camera.isInitialized) camera.release()
        cameraStarted = false
    }

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener?.disable()
        if (::camera.isInitialized) camera.destroy()
        panoExecutor.shutdown()
        try { shutterSound.release() } catch (_: Throwable) {}
    }
}

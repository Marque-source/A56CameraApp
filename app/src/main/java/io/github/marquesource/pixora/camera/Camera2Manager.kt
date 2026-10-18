package io.github.marquesource.pixora.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.location.Location
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.marquesource.pixora.ui.views.Camera2PreviewView
import io.github.marquesource.pixora.utils.AspectRatio
import io.github.marquesource.pixora.utils.Camera2Utils
import io.github.marquesource.pixora.utils.ExifHelper
import io.github.marquesource.pixora.utils.MediaStoreHelper
import io.github.marquesource.pixora.utils.SamsungVendorTagsExplorer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe çekim isteği veri modeli.
 * JPEG verisi ve CaptureResult farklı callback'lerden geldiği için
 * ikisi de burada toplanır — hangisi son gelirse kaydetmeyi tetikler.
 */
class PendingCaptureRequest(
    val location: Location?,
    val orientation: Int,
    val onSaved: (Uri) -> Unit,
    val onError: () -> Unit,
    val colorProfile: io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile? = null,
    val isProMode: Boolean = false,
    val onProSaved: ((Uri, String) -> Unit)? = null,
    /** Bu çekimde RAW_SENSOR karesi de istendi mi (gerçek DNG için)? */
    val expectsRaw: Boolean = false,
    /** DngCreator için çekimin yapıldığı kameranın karakteristikleri. */
    val characteristics: CameraCharacteristics? = null,
    val createTime: Long = System.currentTimeMillis()
) {
    @Volatile var captureResult: TotalCaptureResult? = null
    @Volatile var jpegBytes: ByteArray? = null
    /** RAW_SENSOR karesi; DNG yazıldıktan sonra kapatılır. */
    @Volatile var rawImage: Image? = null
    private val completed = AtomicBoolean(false)

    /** Gerekli tüm parçalar (metadata + JPEG + varsa RAW) hazırsa true döner (sadece bir kez). */
    fun tryComplete(): Boolean {
        val rawReady = !expectsRaw || rawImage != null
        return captureResult != null && jpegBytes != null && rawReady && !completed.getAndSet(true)
    }

    fun isCompleted(): Boolean = completed.get()
}

class Camera2Manager(
    private val context: Context,
    private val previewView: Camera2PreviewView
) {
    private val TAG = "Camera2Manager"
    private val CONVERGENCE_TIMEOUT_MS = 1200L
    private val RAW_NIGHT_FRAMES = 6
    /** Elde tutarken kare başına en uzun süre (Note 10S'de OIS yok). */
    private val RAW_NIGHT_MAX_EXP_NS = 80_000_000L
    private val systemManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    val exposure = ExposureController()
    val focus = FocusController()
    val whiteBalance = WhiteBalanceController()
    val zoom = ZoomController()
    val processing = ProcessingSettings()
    private val requestBuilder = CaptureRequestBuilder(exposure, focus, whiteBalance, zoom, processing)

    private var imageReaders: ImageReaderManager? = null
    private var sessionManager: CaptureSessionManager? = null

    /**
     * Ağır görüntü işleme (birleştirme, gürültü azaltma, yapay zeka modelleri, JPEG kodlama) için
     * uygulama ömrü boyunca yaşayan ayrı iş parçacığı. Eskiden bunlar kamera iş parçacığında
     * çalışıyor ve işlem sürerken kamera geri çağrılarını bekletiyordu. TFLite GPU delegesi
     * oluşturulduğu iş parçacığında kullanılmak zorunda olduğu için modeller hep burada yüklenir.
     */
    private val procThread = HandlerThread("PixoraProcess").also { it.start() }
    private val procHandler = Handler(procThread.looper)
    private var modelsWarmedUp = false
    private val sceneAnalysisBusy = AtomicBoolean(false)
    private val burstInProgress = AtomicBoolean(false)

    /** Video/time-lapse kaydı sürerken kayıt yüzeyi; tekrarlayan istekler bunu da hedeflemeli. */
    private var recordingSurface: Surface? = null
    private var recordingFps: Int = 30
    private var recordingStabilization: Boolean = false
    private val focusResetToken = Any()

    private var cameras: List<Camera2Utils.CameraInfo> = emptyList()
    var currentFacing: Camera2Utils.Facing = Camera2Utils.Facing.BACK
        private set
    private var currentInfo: Camera2Utils.CameraInfo? = null
    private var cameraDevice: CameraDevice? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSize: Size = Size(1920, 1080)
    private var previewSurface: Surface? = null
    private var lastResult: TotalCaptureResult? = null
    private var stillCaptureResult: TotalCaptureResult? = null

    private var yuvBurstReader: ImageReader? = null
    private var yuvBurstSurface: Surface? = null
    private var yuvBurstFrameListener: ImageReader.OnImageAvailableListener? = null
    private val burstLock = Any()
    private val isCameraClosing = AtomicBoolean(false)

    private val captureQueue = ConcurrentLinkedQueue<PendingCaptureRequest>()

    var flashMode: FlashMode = FlashMode.AUTO

    var isHighRes50Mp: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                restartSessionForAspectOrRes()
            }
        }

    var aspectRatio: AspectRatio = AspectRatio.RATIO_4_3
        set(value) {
            if (field != value) {
                field = value
                restartSessionForAspectOrRes()
            }
        }

    fun restartSessionForAspectOrRes() {
        if (cameraDevice == null || isCameraClosing.get()) return
        bgHandler?.post {
            val ch = characteristics ?: return@post
            mainPost {
                chooseSizes(ch)
                createStandardPreviewSession()
            }
        }
    }


    /**
     * Expert RAW: açıkken oturuma gerçek RAW_SENSOR akışı eklenir ve fotoğraf çekimlerinde
     * JPEG'in yanında DngCreator ile sensör verisinden DNG yazılır.
     * Değişince oturum yeniden kurulur (RAW yüzeyi oturum yapılandırmasının parçası).
     */
    var rawEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                restartSessionForAspectOrRes()
            }
        }

    /**
     * Gece modunda ham (RAW) çok kareli birleştirme kullanılsın mı? Etkinlik gece moduna geçince
     * true yapar. Yalnızca RAW + elle sensör kontrolü olan telefonlarda oturuma RAW akışı eklenir;
     * diğerlerinde (ör. Galaxy A56) hiçbir şey değişmez ve YUV gece yolu kullanılır.
     */
    var nightRawMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (supportsRawNight() && !rawEnabled) restartSessionForAspectOrRes()
            }
        }

    /** Bu kamerada RAW gece modu kullanılabilir mi? */
    fun supportsRawNight(): Boolean {
        val ch = characteristics ?: return false
        if (currentInfo?.supportsRaw != true || rawStreamUnsupported) return false
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        return caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) &&
            !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty()
    }

    /** RAW akışı denenip oturum kurulamadıysa true — aynı kamerada tekrar denenmez. */
    private var rawStreamUnsupported = false

    var onPreviewResult: ((TotalCaptureResult) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Kamera her açıldığında (lens/ön-arka değişiminde de) çağrılır; arayüz yeteneklere göre güncellenir. */
    var onCameraOpened: (() -> Unit)? = null
    var onSceneDetected: ((io.github.marquesource.pixora.pipeline.SceneOptimizer.DetectionResult) -> Unit)? = null

    /** Tek bir işleme hattı (ve tek set TFLite modeli) uygulama ömrü boyunca kullanılır. */
    private val pipeline by lazy { io.github.marquesource.pixora.pipeline.ImageProcessingPipeline(context) }
    private var lastSceneAnalysisTimestamp = 0L

    /** Kamerayı açar. Birden çok kez çağrılırsa (onResume vb.) modeller yeniden yüklenmez. */
    fun start() {
        startBackgroundThread()
        if (!pipeline.initOpenCV()) {
            onError?.invoke("OpenCV başlatılamadı")
            return
        }
        if (!modelsWarmedUp) {
            modelsWarmedUp = true
            procHandler.post { pipeline.tfliteEngine.prewarmModels() }
        }
        cameras = Camera2Utils.enumerateCameras(systemManager)
        openForFacing(currentFacing)
    }

    /** Aktivite yok edilirken: kamerayı kapat, modelleri serbest bırak, işleme thread'ini bitir. */
    fun destroy() {
        release()
        procHandler.post {
            try { pipeline.tfliteEngine.release() } catch (_: Throwable) {}
        }
        procThread.quitSafely()
    }

    private fun startBackgroundThread() {
        if (bgThread == null || bgThread?.isAlive == false) {
            bgThread = HandlerThread("Camera2Bg").also { it.start() }
            bgHandler = Handler(bgThread!!.looper)
            imageReaders = ImageReaderManager(bgHandler!!)
            sessionManager = CaptureSessionManager(bgHandler!!)
        }
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        try { bgThread?.join(); bgThread = null; bgHandler = null } catch (_: InterruptedException) {}
    }

    private fun openForFacing(facing: Camera2Utils.Facing, initialZoom: Float = 1f) {
        val info = Camera2Utils.mainCamera(cameras, facing) ?: run {
            onError?.invoke("Kamera bulunamadı")
            return
        }
        openCamera(info, initialZoom)
    }

    private fun openCamera(info: Camera2Utils.CameraInfo, initialZoom: Float = 1f) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke("Kamera izni yok")
            return
        }
        closeCamera()
        isCameraClosing.set(false)
        currentInfo = info
        currentFacing = info.facing
        rawStreamUnsupported = false
        val ch = systemManager.getCameraCharacteristics(info.id)
        characteristics = ch
        if (io.github.marquesource.pixora.BuildConfig.DEBUG) {
            if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                io.github.marquesource.pixora.utils.SamsungVendorTagsExplorer.dumpCameraVendorTags(ch, info.id)
            }
        }
        exposure.readRanges(ch)
        whiteBalance.readCharacteristics(ch)
        focus.readCharacteristics(ch)
        zoom.readCharacteristics(ch)
        zoom.setZoom(initialZoom)
        chooseSizes(ch)

        if (io.github.marquesource.pixora.BuildConfig.DEBUG && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Camera2Utils.checkCamera2Extensions(systemManager, info.id)
        }

        try {
            systemManager.openCamera(info.id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (isCameraClosing.get()) {
                        try { device.close() } catch (_: Throwable) {}
                        return
                    }
                    cameraDevice = device
                    createPreviewSession()
                    onCameraOpened?.invoke()
                }

                override fun onDisconnected(device: CameraDevice) {
                    try { device.close() } catch (_: Throwable) {}
                    if (cameraDevice == device) cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    try { device.close() } catch (_: Throwable) {}
                    if (cameraDevice == device) cameraDevice = null
                    if (!isCameraClosing.get()) {
                        onError?.invoke("Kamera açma hatası ($error)")
                    }
                }
            }, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera hatası", e)
            onError?.invoke(e.message ?: "Kamera açılamadı")
        }
    }

    private fun chooseSizes(ch: CameraCharacteristics) {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val targetSize = Camera2Utils.largestOutputSizeWithAspect(ch, ImageFormat.JPEG, aspectRatio) ?: Size(1920, 1080)
        previewSize = if (map != null) {
            Camera2Utils.chooseOptimalSize(
                map, SurfaceTexture::class.java,
                targetSize.width,
                targetSize.height,
                maxWidth = 1920, maxHeight = 1920
            )
        } else Size(1920, 1080)
        val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        previewView.setBufferSize(previewSize, orientation)
    }


    fun createPreviewSession() {
        createStandardPreviewSession()
    }

    private fun createStandardPreviewSession() {
        val wantRaw = (rawEnabled || (nightRawMode && supportsRawNight())) &&
            currentInfo?.supportsRaw == true && !rawStreamUnsupported
        createStandardPreviewSession(withRaw = wantRaw)
    }

    private fun createStandardPreviewSession(withRaw: Boolean) {
        val device = cameraDevice ?: return
        val ch = characteristics ?: return
        val readers = imageReaders ?: return
        val session = sessionManager ?: return

        previewSurface = previewView.createSurface() ?: return
        readers.closeAll()
        readers.setupJpeg(ch, aspectRatio, isHighRes50Mp) { image -> onJpegAvailable(image) }
        if (withRaw) {
            readers.setupRaw(ch) { image -> onRawAvailable(image) }
        }

        setupYuvBurstReader(ch)

        val surfaces = buildList {
            add(previewSurface!!)
            readers.jpegSurface()?.let { add(it) }
            readers.rawSurface()?.let { add(it) }
            yuvBurstSurface?.let { add(it) }
        }

        session.createSession(device, surfaces, object : CaptureSessionManager.Callback {
            override fun onConfigured(s: CameraCaptureSession) {
                if (isCameraClosing.get() || cameraDevice == null) return
                startPreviewRepeating()
            }
            override fun onFailed(reason: String) {
                if (isCameraClosing.get()) return
                if (withRaw) {
                    // Bu akış kombinasyonu (önizleme + JPEG + RAW + YUV) cihazda desteklenmiyor:
                    // RAW'sız tekrar dene ki kamera hiç açılmamış gibi kalmasın.
                    Log.w(TAG, "RAW akışıyla oturum kurulamadı ($reason), RAW'sız yeniden deneniyor")
                    rawStreamUnsupported = true
                    mainPost { onError?.invoke("Bu kamerada RAW + işleme akışı birlikte açılamadı, RAW kapatıldı") }
                    mainPost { createStandardPreviewSession(withRaw = false) }
                    return
                }
                onError?.invoke(reason)
            }
        })
    }

    private fun setupYuvBurstReader(ch: CameraCharacteristics) {
        try { yuvBurstReader?.close() } catch (_: Throwable) {}
        val size = Camera2Utils.largestOutputSizeWithAspect(ch, ImageFormat.YUV_420_888, aspectRatio)
            ?: Size(4080, 3060)

        // 16 karelik geniş tampon boyutu (burst ve ZSL taşmalarını önler)
        yuvBurstReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 16).apply {
            setOnImageAvailableListener({ reader ->
                val burstListener = yuvBurstFrameListener
                if (burstListener != null) {
                    // Burst çekimi aktif: kareyi doğrudan burst dinleyicisine devret
                    burstListener.onImageAvailable(reader)
                } else {
                    // Boşta kalan kareleri derhal serbest bırak
                    try { reader.acquireLatestImage()?.close() } catch (_: Throwable) {}
                }
            }, bgHandler)
        }
        yuvBurstSurface = yuvBurstReader!!.surface
    }

    /**
     * O anki duruma uygun tekrarlayan isteği kurar: normalde önizleme, kayıt sürerken
     * önizleme + kayıt yüzeyi (RECORD şablonu). Eskiden zoom/odak/pozlama/flaş değişikliği
     * her zaman yalnızca önizleme isteği gönderdiği için kayıt sırasında video donuyordu.
     */
    private fun buildRepeatingRequest(): CaptureRequest.Builder? {
        val device = cameraDevice ?: return null
        val ch = characteristics ?: return null
        val rec = recordingSurface
        return if (rec != null) {
            val surfaces = listOfNotNull(previewSurface, rec)
            requestBuilder.build(device, ch, CameraDevice.TEMPLATE_RECORD, surfaces, flashMode, forVideo = true)
                ?.also { applyVideoSettings(it, ch) }
        } else {
            val readers = imageReaders ?: return null
            val targets = buildList {
                previewSurface?.let { add(it) }
                readers.yuvSurface()?.let { add(it) }
            }
            requestBuilder.build(device, ch, CameraDevice.TEMPLATE_PREVIEW, targets, flashMode, forPreview = true)
        }
    }

    private fun startPreviewRepeating() {
        if (isCameraClosing.get()) return
        val builder = buildRepeatingRequest() ?: return
        sessionManager?.startRepeating(builder.build(), previewCallback)
    }

    /** Kayıt isteğine FPS aralığı ve (destekleniyorsa ve istenmişse) video sabitleme uygular. */
    private fun applyVideoSettings(builder: CaptureRequest.Builder, ch: CameraCharacteristics) {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val target = ranges?.find { it.upper == recordingFps && it.lower == recordingFps }
            ?: ranges?.find { it.upper == recordingFps }
        target?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        val stabModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val wanted = if (recordingStabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                     else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        if (stabModes?.contains(wanted) == true) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, wanted)
        }
    }

    /**
     * 12MP'den büyük (ör. 50MP) fotoğraf bu kamerada uygulamalara açık mı?
     * Galaxy A56'da 50MP yalnızca Samsung'un kendi kamerasına açık; uygulamalar en fazla 4080x3060 alır.
     */
    fun supportsHighResPhoto(): Boolean {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val sizes = (map.getHighResolutionOutputSizes(ImageFormat.JPEG) ?: emptyArray()) +
            (map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray())
        return sizes.any { it.width.toLong() * it.height > 20_000_000L }
    }

    /** Fotoğrafın gerçek megapiksel değeri (seçili en-boy oranı ve çözünürlük moduna göre). */
    fun photoMegapixels(highRes: Boolean = isHighRes50Mp): Int {
        val ch = characteristics ?: return 0
        val size = Camera2Utils.largestOutputSizeWithAspect(ch, ImageFormat.JPEG, aspectRatio, highRes) ?: return 0
        return Math.round(size.width.toDouble() * size.height / 1_000_000.0).toInt()
    }

    /** Video sabitleme (EIS) bu kamerada var mı? */
    fun isVideoStabilizationSupported(): Boolean =
        characteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

    /** Kamera bu çözünürlük ve kare hızında video kaydedebiliyor mu? */
    fun supportsVideo(width: Int, height: Int, fps: Int): Boolean {
        val ch = characteristics ?: return false
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val size = Size(width, height)
        val sizes = map.getOutputSizes(android.media.MediaRecorder::class.java) ?: return false
        if (sizes.none { it == size }) return false
        val minFrameNs = try { map.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, size) } catch (_: Exception) { 0L }
        if (minFrameNs > 0 && minFrameNs > 1_000_000_000L / fps + 100_000L) return false
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return false
        return ranges.any { it.upper >= fps }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastResult = result
            exposure.updateMetering(result)
            onPreviewResult?.invoke(result)

            // Sahne analizi (yaklaşık 2 kez/sn). Kayıt sırasında ve işleme thread'i meşgulken atlanır.
            val now = System.currentTimeMillis()
            if (recordingSurface == null && now - lastSceneAnalysisTimestamp > 450L &&
                !sceneAnalysisBusy.get()
            ) {
                lastSceneAnalysisTimestamp = now
                sceneAnalysisBusy.set(true)
                mainPost {
                    val thumbnail = try {
                        if (previewView.isAvailable) previewView.getBitmap(400, 400) else null
                    } catch (_: Throwable) { null }
                    if (thumbnail == null) { sceneAnalysisBusy.set(false); return@mainPost }
                    procHandler.post {
                        try {
                            if (!thumbnail.isRecycled) {
                                val detection = pipeline.tfliteEngine.sceneOptimizer.classifyScene(thumbnail, result)
                                thumbnail.recycle()
                                // Güvenli değilse varsayılana dön (eskiden son algılanan profil yapışık kalıyordu)
                                colorProfile = if (detection.confidence >= 0.50f) detection.scene.targetProfile
                                               else io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL
                                mainPost { onSceneDetected?.invoke(detection) }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Sahne analizi hatası", e)
                        } finally {
                            sceneAnalysisBusy.set(false)
                        }
                    }
                }
            }
        }
    }

    fun capturePhoto(
        location: Location?,
        deviceRotation: Int,
        onSaved: (Uri) -> Unit,
        onErr: () -> Unit,
        onRawSaved: ((Uri) -> Unit)? = null
    ) {
        val device = cameraDevice ?: return onErr()
        val ch = characteristics ?: return onErr()
        val readers = imageReaders ?: return onErr()
        val jpegSurface = readers.jpegSurface() ?: return onErr()

        val orientation = jpegOrientation(ch, deviceRotation)
        val rawSurface = if (rawEnabled) readers.rawSurface() else null
        // Gece profili (güçlü gölge açma) çok kareli gece birleştirmesi içindir. Tek kare ISP JPEG'i zaten
        // doğru pozlanmıştır; loş iç mekanda bu profil uygulanınca fotoğraf aşırı parlak ve kumlu çıkıyordu.
        val photoProfile = if (colorProfile == io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.NIGHT)
            io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL else colorProfile
        val pendingReq = PendingCaptureRequest(
            location, orientation, onSaved, onErr, photoProfile,
            expectsRaw = rawSurface != null,
            characteristics = ch
        )
        captureQueue.add(pendingReq)

        val builder = requestBuilder.build(
            device, ch, CameraDevice.TEMPLATE_STILL_CAPTURE,
            listOfNotNull(jpegSurface, rawSurface), flashMode,
            jpegOrientation = orientation, location = location
        ) ?: run {
            captureQueue.remove(pendingReq)
            mainPost { onErr() }
            return
        }

        sessionManager?.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult
            ) {
                pendingReq.captureResult = result
                stillCaptureResult = result
                lastResult = result
                tryFinishCapture(pendingReq)
            }
            override fun onCaptureFailed(
                s: CameraCaptureSession, r: CaptureRequest,
                failure: CaptureFailure
            ) {
                discardRequest(pendingReq)
                mainPost { onErr() }
            }
        })
    }

    var colorProfile: io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile =
        io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL

    var proProfile: io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile =
        io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL

    /**
     * Pro Modu Özel Çekimi (End-to-End Professional Imaging):
     * 1. Manuel ISO, Perde Hızı, Odak ve Kelvin değerlerini %100 doğrulukla sensöre uygular.
     * 2. Samsung ISP'nin çamurlaştırıcı gürültü filtresini (NOISE_REDUCTION_MODE_OFF) ve yapay beyaz
     *    kenar çizgilerini (EDGE_MODE_OFF) tamamen kapatır — saf optik dokuyu korur!
     * 3. Seçilen Pro Renk Profilini (Leica, Hasselblad, Monokrom, Flat Log) 32-bit renk uzayında işler.
     * 4. %100 kayıpsız JPEG kalitesiyle kaydeder.
     */
    fun capturePro(
        location: Location?,
        deviceRotation: Int,
        profile: io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile = proProfile,
        onProgress: ((String) -> Unit)? = null,
        onSaved: (Uri, String) -> Unit,
        onError: () -> Unit
    ) {
        val device = cameraDevice ?: return onError()
        val ch = characteristics ?: return onError()
        val readers = imageReaders ?: return onError()
        val jpegSurface = readers.jpegSurface() ?: return onError()

        val orientation = jpegOrientation(ch, deviceRotation)
        val rawSurface = if (rawEnabled) readers.rawSurface() else null
        val pendingReq = PendingCaptureRequest(
            location = location,
            orientation = orientation,
            onSaved = { uri -> onSaved(uri, "") },
            onError = onError,
            colorProfile = profile,
            isProMode = true,
            onProSaved = onSaved,
            expectsRaw = rawSurface != null,
            characteristics = ch
        )
        captureQueue.add(pendingReq)

        val builder = try {
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Pro CaptureRequest oluşturulamadı", e)
            captureQueue.remove(pendingReq)
            mainPost { onError() }
            return
        }

        builder.addTarget(jpegSurface)
        rawSurface?.let { builder.addTarget(it) }

        // Manuel Pro Pozlama, Odak ve Beyaz Dengesi Korumalı Uygulama
        exposure.applyTo(builder)
        focus.applyTo(builder)
        whiteBalance.applyTo(builder)
        zoom.applyTo(builder)

        // Samsung ISP Denoise/Edge Müdahalelerini Tamamen İptal Et (Optik Doku Koruma)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_FAST)
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE)
        builder.set(CaptureRequest.JPEG_QUALITY, 100.toByte())
        builder.set(CaptureRequest.JPEG_ORIENTATION, orientation)
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        location?.let { builder.set(CaptureRequest.JPEG_GPS_LOCATION, it) }

        onProgress?.invoke("Pro Çekim (${profile.displayNameTr})...")

        sessionManager?.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                pendingReq.captureResult = result
                stillCaptureResult = result
                lastResult = result
                tryFinishCapture(pendingReq)
            }
            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                discardRequest(pendingReq)
                mainPost { onError() }
            }
        })
    }

    /**
     * Google Pixel Night Sight + Apple Photonic V2 Gece Modu:
     * 1. 5 kare AYNI pozlamayla YUV burst (gerekirse süre uzatılıp ISO düşürülür).
     * 2. Kareler yakalanınca arayüz serbest kalır (DONE_CAPTURE); işleme ayrı iş parçacığında sürer.
     * 3. Hizalama + hareket korumalı ortalama → gürültüye uyarlanan gürültü azaltma → gece tonu → keskinleştirme.
     */
    fun captureNight(
        location: Location?,
        deviceRotation: Int,
        onProgress: ((String) -> Unit)? = null,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        val ch = characteristics ?: return onError()
        val orientation = jpegOrientation(ch, deviceRotation)

        if (nightRawMode && supportsRawNight() && imageReaders?.rawSurface() != null) {
            captureNightRaw(ch, orientation, location, onProgress, onSaved, onError = {
                // RAW yolu başarısız olursa kullanıcı eli boş kalmasın: YUV gece yoluna düş
                Log.w(TAG, "RAW gece başarısız, YUV gece yoluna geçiliyor")
                captureNightYuv(ch, orientation, location, onProgress, onSaved, onError)
            })
            return
        }
        captureNightYuv(ch, orientation, location, onProgress, onSaved, onError)
    }

    private fun captureNightYuv(
        ch: CameraCharacteristics,
        orientation: Int,
        location: Location?,
        onProgress: ((String) -> Unit)?,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        val iso = lastResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 800
        val exposureNs = lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 40_000_000L
        val frameCount = 5
        Log.i(TAG, "🌙 Gece çekimi: $frameCount kare (ISO=$iso, exp=${exposureNs / 1_000_000}ms, OIS=ON, AE_LOCK=ON)")
        onProgress?.invoke("Gece Çekimi ($frameCount kare)...")

        captureBracketedBurst(
            frameCount = frameCount,
            isNight = true,
            onProgress = onProgress,
            onComplete = { frames ->
                // Sensör foton toplamayı BİTİRDİ! Arayüz anında serbest kalır (ZSL hissi)
                mainPost { onProgress?.invoke("DONE_CAPTURE") }

                procHandler.post {
                    try {
                        val config = io.github.marquesource.pixora.pipeline.ImageProcessingPipeline.PipelineConfig(
                            colorProfile = io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.NIGHT,
                            denoiseEnabled = true,
                            sharpenEnabled = true,
                            hdrEnabled = true,
                            lowLightEnhanceEnabled = true,
                            expertRawEnabled = rawEnabled,
                            jpegQuality = 98,
                            saveToGallery = true
                        )

                        val result = pipeline.process(
                            frames = frames,
                            config = config,
                            location = location,
                            orientation = orientation,
                            onProgress = { step -> mainPost { onProgress?.invoke(step) } }
                        )

                        mainPost {
                            if (result.uri != null) {
                                Log.d(TAG, "Gece V2 çekimi tamamlandı: ${result.processingTimeMs}ms, " +
                                    "ISO=${result.iso}, ${result.frameCount} kare")
                                onSaved(result.uri)
                            } else {
                                onError()
                            }
                            try { result.bitmap.recycle() } catch (_: Throwable) {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Gece V2 pipeline hatası", e)
                        mainPost { onError() }
                    }
                }
            },
            onError = { msg ->
                Log.e(TAG, "Gece burst hatası: $msg")
                mainPost { onError() }
            }
        )
    }

    // ======================================================================== RAW gece

    /**
     * RAW gece çekimi: [RAW_NIGHT_FRAMES] ham kare elle pozlamayla çekilir, [RawNightProcessor] ile
     * hizalanıp birleştirilir. Telefonun işlediği karelerden çok daha temiz sonuç verir; yalnızca
     * RAW + elle sensör kontrolü olan telefonlarda çalışır (bkz. [supportsRawNight]).
     */
    private fun captureNightRaw(
        ch: CameraCharacteristics,
        orientation: Int,
        location: Location?,
        onProgress: ((String) -> Unit)?,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        val device = cameraDevice ?: return onError()
        val readers = imageReaders ?: return onError()
        val rawSurface = readers.rawSurface() ?: return onError()
        val rawReader = readers.rawReader ?: return onError()
        if (!burstInProgress.compareAndSet(false, true)) return onError()

        // ---- Pozlama planı: önizlemenin ölçtüğü ışık miktarını koru, süreyi uzatıp ISO'yu düşür
        val isoRange = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: android.util.Range(100, 3200)
        val expRange = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: android.util.Range(100_000L, 1_000_000_000L)
        val mIso = lastResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 800
        val mExp = lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 33_000_000L
        val target = mIso.toDouble() * mExp
        val cap = minOf(expRange.upper, RAW_NIGHT_MAX_EXP_NS).toDouble()
        var exp = (target / isoRange.lower).coerceIn(expRange.lower.toDouble(), cap)
        if (target / exp > isoRange.upper) exp = minOf(target / isoRange.upper, cap)
        val expNs = exp.toLong()
        val iso = (target / exp).toInt().coerceIn(isoRange.lower, isoRange.upper)
        val n = RAW_NIGHT_FRAMES
        Log.i(TAG, "🌙 RAW gece: $n kare, ISO=$iso, süre=${expNs / 1_000_000}ms (ölçüm ISO=$mIso, ${mExp / 1_000_000}ms)")
        mainPost { onProgress?.invoke("RAW gece çekimi ($n kare)...") }

        val w = rawReader.width; val h = rawReader.height
        val frames = java.util.TreeMap<Long, org.opencv.core.Mat>()
        val results = java.util.TreeMap<Long, TotalCaptureResult>()
        val done = AtomicBoolean(false)
        val rowBuf = ShortArray(w * h)

        fun restoreListener() {
            imageReaders?.setRawImageAvailableListener { image -> onRawAvailable(image) }
        }

        fun fail(msg: String) {
            if (done.getAndSet(true)) return
            Log.e(TAG, "RAW gece hatası: $msg")
            synchronized(frames) { frames.values.forEach { it.release() }; frames.clear() }
            restoreListener()
            burstInProgress.set(false)
            mainPost { onError() }
        }

        fun finish() {
            if (done.getAndSet(true)) return
            restoreListener()
            burstInProgress.set(false)
            val pairs = synchronized(frames) {
                frames.entries.mapNotNull { (ts, m) -> results[ts]?.let { m to it } }.also {
                    frames.values.filter { f -> it.none { p -> p.first === f } }.forEach { f -> f.release() }
                    frames.clear()
                }
            }
            if (pairs.size < 2) {
                pairs.forEach { it.first.release() }
                Log.w(TAG, "RAW gece: yeterli kare yok (${pairs.size})")
                mainPost { onError() }
                return
            }
            mainPost { onProgress?.invoke("DONE_CAPTURE") }
            procHandler.post { processRawNight(ch, pairs, orientation, location, onProgress, onSaved, onError) }
        }

        fun checkComplete() {
            val ready = synchronized(frames) { frames.keys.count { results.containsKey(it) } }
            if (ready >= n) finish()
        }

        readers.setRawImageAvailableListener { image ->
            try {
                if (done.get()) return@setRawImageAvailableListener
                val plane = image.planes[0]
                val buf = plane.buffer.order(java.nio.ByteOrder.nativeOrder())
                val mat = org.opencv.core.Mat(h, w, org.opencv.core.CvType.CV_16UC1)
                if (plane.rowStride == w * 2) {
                    buf.asShortBuffer().get(rowBuf, 0, w * h)
                } else {
                    val sb = buf.asShortBuffer()
                    for (r in 0 until h) { sb.position(r * plane.rowStride / 2); sb.get(rowBuf, r * w, w) }
                }
                mat.put(0, 0, rowBuf)
                synchronized(frames) { frames[image.timestamp] = mat }
            } catch (e: Throwable) {
                Log.e(TAG, "RAW kare kopyalanamadı", e)
            } finally {
                try { image.close() } catch (_: Throwable) {}
            }
            checkComplete()
        }

        val requests = (0 until n).map {
            device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                set(CaptureRequest.SENSOR_FRAME_DURATION, expNs)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
                if (focus.hasAf) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
                zoom.applyTo(this)
            }.build()
        }

        bgHandler?.postDelayed({
            if (done.get()) return@postDelayed
            val got = synchronized(frames) { frames.keys.count { results.containsKey(it) } }
            if (got >= 2) finish() else fail("zaman aşımı ($got kare)")
        }, 3000L + n * expNs / 1_000_000L)

        try {
            sessionManager?.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    synchronized(frames) { results[ts] = result }
                    checkComplete()
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                    Log.w(TAG, "RAW gece karesi başarısız: ${f.reason}")
                }
            }) ?: fail("oturum yok")
        } catch (e: Exception) {
            fail("burst başlatılamadı: ${e.message}")
        }
    }

    private fun processRawNight(
        ch: CameraCharacteristics,
        pairs: List<Pair<org.opencv.core.Mat, TotalCaptureResult>>,
        orientation: Int,
        location: Location?,
        onProgress: ((String) -> Unit)?,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        try {
            pipeline.initOpenCV()
            val ref = pairs.first().second
            val params = rawParams(ch, ref)
            val dump = io.github.marquesource.pixora.pipeline.DebugDump(context, "raw_gece")
            dump.text("bilgi.txt", "kare=${pairs.size} iso=${ref.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                "süre=${ref.get(CaptureResult.SENSOR_EXPOSURE_TIME)} cfa=${params.cfa} siyah=${params.blackLevel.joinToString()} " +
                "beyaz=${params.whiteLevel} wb=${params.wbGains.joinToString()} ccm=${params.ccm.joinToString()} " +
                "gölge=${params.shading?.size()} gürültü=${params.noiseScale},${params.noiseOffset} cihaz=${Build.MODEL}")
            mainPost { onProgress?.invoke("Ham kareler birleştiriliyor...") }
            val t0 = System.currentTimeMillis()
            val result = io.github.marquesource.pixora.pipeline.RawNightProcessor.process(
                pairs.map { it.first }.toMutableList(), params,
                keepMergedBayer = rawEnabled,
                dump = if (dump.enabled) { name, m -> dump.mat(name, m) } else null
            )
            params.shading?.release()
            Log.i(TAG, "RAW gece işlendi: ${System.currentTimeMillis() - t0}ms, ${result.usedFrames} kare, " +
                "ağırlık=%.2f kaymalar=${result.shifts}".format(result.meanWeight))
            dump.mat("4_son", result.image)

            val bmp = io.github.marquesource.pixora.pipeline.YuvConverter.matToBitmap(result.image)
            result.image.release()
            val uri = pipeline.saveJpeg(bmp, 95, ref, location, orientation)
            bmp.recycle()

            result.mergedBayer?.let { bayer ->
                val shorts = ShortArray((bayer.total()).toInt()); bayer.get(0, 0, shorts)
                val bb = java.nio.ByteBuffer.allocateDirect(shorts.size * 2).order(java.nio.ByteOrder.nativeOrder())
                bb.asShortBuffer().put(shorts)
                io.github.marquesource.pixora.utils.DngHelper.saveDngBuffer(
                    context, ch, ref, bb, bayer.cols(), bayer.rows(), location, orientation)
                bayer.release()
            }
            mainPost { if (uri != null) onSaved(uri) else onError() }
        } catch (e: Throwable) {
            Log.e(TAG, "RAW gece işleme hatası", e)
            pairs.forEach { try { it.first.release() } catch (_: Throwable) {} }
            mainPost { onError() }
        }
    }

    /** Karakteristikler + çekim sonucundan RAW işleme parametreleri. */
    private fun rawParams(ch: CameraCharacteristics, r: TotalCaptureResult): io.github.marquesource.pixora.pipeline.RawNightProcessor.Params {
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val dynBlack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) else null
        val pattern = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = DoubleArray(4) { pos ->
            dynBlack?.getOrNull(pos)?.toDouble()
                ?: pattern?.getOffsetForIndex(pos % 2, pos / 2)?.toDouble() ?: 64.0
        }
        val dynWhite = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) else null
        val white = (dynWhite ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
        val g = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = if (g != null) doubleArrayOf(g.red.toDouble(), (g.greenEven + g.greenOdd) / 2.0, g.blue.toDouble())
            else doubleArrayOf(2.0, 1.0, 1.6)
        val t = r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val ccm = DoubleArray(9) { i ->
            t?.getElement(i % 3, i / 3)?.toDouble() ?: if (i % 4 == 0) 1.0 else 0.0
        }
        val lsm = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shading = lsm?.let { m ->
            val mat = org.opencv.core.Mat(m.rowCount, m.columnCount, org.opencv.core.CvType.CV_32FC4)
            val data = FloatArray(m.rowCount * m.columnCount * 4)
            var i = 0
            for (row in 0 until m.rowCount) for (col in 0 until m.columnCount) for (c in 0 until 4) {
                data[i++] = m.getGainFactor(c, col, row)
            }
            mat.put(0, 0, data); mat
        }
        val np = r.get(CaptureResult.SENSOR_NOISE_PROFILE)
        val nS = np?.map { it.first }?.average()
        val nO = np?.map { it.second }?.average()
        return io.github.marquesource.pixora.pipeline.RawNightProcessor.Params(cfa, black, white, wb, ccm, shading, nS, nO)
    }

    /**
     * Exynos 1580 NPU & MiDaS 3D Destekli Portre Çekimi.
     * YUV burst yakalar, Apple Photonic renk eğrisi ve NPU 3D derinlik haritasıyla
     * arka planı f/1.4 lens gibi kademeli bulanıklaştırır.
     */
    fun capturePortrait(
        location: Location?,
        deviceRotation: Int,
        onProgress: ((String) -> Unit)? = null,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        val ch = characteristics ?: return onError()
        val orientation = jpegOrientation(ch, deviceRotation)

        Log.d(TAG, "Portre çekimi başlıyor: YUV Burst + MiDaS 3D NPU Bokeh")
        // Çok karanlıkta derinlik haritası konuyu arka plandan ayıramıyor ve bütün kareyi bulanıklaştırıyor
        // (A56 ve Note 10S testleri). O zaman bulanıklık uygulanmaz, temiz bir fotoğraf kaydedilir.
        val darkScene = lastResult?.let {
            (it.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0) >= 1600 ||
                (it.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) >= 50_000_000L
        } ?: false
        if (darkScene) mainPost { onProgress?.invoke("Ortam çok karanlık: arka plan bulanıklığı kapalı") }
        onProgress?.invoke("Portre çekiliyor...")

        captureBracketedBurst(
            isNight = false,
            onProgress = { step -> mainPost { onProgress?.invoke(step) } },
            onComplete = { frames ->
                procHandler.post {
                    try {
                        onProgress?.invoke("3D Derinlik Haritası (MiDaS NPU)...")
                        val config = io.github.marquesource.pixora.pipeline.ImageProcessingPipeline.PipelineConfig(
                            colorProfile = io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL,
                            denoiseEnabled = true,
                            sharpenEnabled = true,
                            hdrEnabled = true,
                            portraitBokehEnabled = !darkScene,
                            expertRawEnabled = rawEnabled,
                            saveToGallery = true
                        )

                        val result = pipeline.process(
                            frames = frames,
                            config = config,
                            location = location,
                            orientation = orientation,
                            onProgress = { step -> mainPost { onProgress?.invoke(step) } }
                        )

                        mainPost {
                            if (result.uri != null) {
                                Log.d(TAG, "Portre çekimi tamamlandı: ${result.processingTimeMs}ms, ${result.frameCount} kare")
                                onSaved(result.uri)
                            } else onError()
                            try { result.bitmap.recycle() } catch (_: Throwable) {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Portre modu pipeline hatası", e)
                        mainPost { onError() }
                    }
                }
            },
            onError = { msg ->
                Log.e(TAG, "Portre burst hatası: $msg")
                mainPost { onError() }
            }
        )
    }



    /**
     * YUV_420_888 tabanlı Exposure Bracketed Burst çekimi yapar.
     *
     * Çekimden önce otomatik pozlama ve beyaz dengesinin oturmasını (en fazla ~1,2 sn) bekler.
     * A56 testinde mod değiştirdikten hemen sonra çekilen gece fotoğrafı, beyaz dengesi henüz
     * oturmadığı için mor/pembe çıkıyordu; birkaç saniye sonraki çekim doğal renkteydi.
     */
    fun captureBracketedBurst(
        frameCount: Int = 3,
        isNight: Boolean = false,
        onProgress: ((String) -> Unit)? = null,
        onComplete: (List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit,
        onError: (String) -> Unit
    ) {
        val bg = bgHandler ?: return onError("Kamera hazır değil")
        val start = android.os.SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                val waited = android.os.SystemClock.uptimeMillis() - start
                if (isConverged(lastResult) || waited >= CONVERGENCE_TIMEOUT_MS) {
                    if (waited >= CONVERGENCE_TIMEOUT_MS) Log.w(TAG, "AE/AWB oturmadan çekiliyor (${waited}ms)")
                    captureBracketedBurstNow(frameCount, isNight, onProgress, onComplete, onError)
                } else {
                    bg.postDelayed(this, 40L)
                }
            }
        }
        bg.post(check)
    }

    /** Pozlama ve beyaz dengesi oturdu mu (ya da kilitli mi)? */
    private fun isConverged(r: TotalCaptureResult?): Boolean {
        r ?: return false
        val ae = r.get(CaptureResult.CONTROL_AE_STATE)
        val awb = r.get(CaptureResult.CONTROL_AWB_STATE)
        val aeOk = ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        val awbOk = awb == null || awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
        return aeOk && awbOk
    }

    private fun captureBracketedBurstNow(
        frameCount: Int,
        isNight: Boolean,
        onProgress: ((String) -> Unit)?,
        onComplete: (List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCameraClosing.get()) return onError("Kamera kapanıyor")
        val device = cameraDevice ?: return onError("Kamera cihazı hazır değil")
        val ch = characteristics ?: return onError("Karakteristikler bulunamadı")
        val yuvSurface = yuvBurstSurface ?: return onError("YUV burst yüzeyi hazır değil")
        // Aynı anda ikinci bir burst başlatılırsa kareler iki çekim arasında karışıyordu
        if (!burstInProgress.compareAndSet(false, true)) return onError("Önceki çekim sürüyor")
        val userOnComplete = onComplete
        val userOnError = onError
        @Suppress("NAME_SHADOWING")
        val onComplete: (List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit = { frames ->
            burstInProgress.set(false); userOnComplete(frames)
        }
        @Suppress("NAME_SHADOWING")
        val onError: (String) -> Unit = { msg -> burstInProgress.set(false); userOnError(msg) }

        val bracket = lastResult?.let {
            ExposureBracketingStrategy.computeForScene(it, isNight)
        } ?: ExposureBracketingStrategy.BracketSettings(
            baseIso = if (isNight) 1200 else 100,
            baseExposureNs = if (isNight) 50_000_000L else 10_000_000L,
            evShifts = ExposureBracketingStrategy.getEvShifts(isNight)
        )

        val expectedEvCount = bracket.evShifts.size
        val evShifts = bracket.evShifts.take(frameCount.coerceAtMost(expectedEvCount))
        val actualFrameCount = evShifts.size

        val collectedFrames = mutableListOf<Pair<YuvFrame, Float>>()
        val collectedMetadata = mutableListOf<TotalCaptureResult>()
        val imageCounter = AtomicInteger(0)
        val metadataCounter = AtomicInteger(0)
        val completed = AtomicBoolean(false)
        val collectedResults = mutableListOf<Pair<YuvFrame, TotalCaptureResult>>()

        // 3.0 saniyelik zaman aşımı koruması (5 kare gece çekimi için ideal!)
        bgHandler?.postDelayed({
            if (completed.get()) return@postDelayed
            if (collectedFrames.isNotEmpty()) {
                Log.w(TAG, "Burst koruma zaman aşımı tetiklendi, ${collectedFrames.size} kare ile devam ediliyor")
                checkBurstComplete(0, completed, collectedFrames, collectedMetadata, collectedResults, onComplete)
            } else if (!completed.getAndSet(true)) {
                // Hiç kare gelmedi: eskiden burada sonsuza kadar "işleniyor"da kalınıyordu
                mainPost {
                    yuvBurstFrameListener = null
                    onError("Kamera kare göndermedi (zaman aşımı)")
                }
            }
        }, 4000)

        yuvBurstFrameListener = ImageReader.OnImageAvailableListener { reader ->
            val img = try {
                reader.acquireNextImage()
            } catch (e: Exception) {
                Log.w(TAG, "YUV burst acquireNextImage uyarısı", e)
                null
            } ?: return@OnImageAvailableListener

            val idx = imageCounter.getAndIncrement()

            if (idx < actualFrameCount) {
                val ev = evShifts.getOrElse(idx) { 0f }
                val frame = try {
                    YuvFrame.fromImage(img, lastResult, ev)
                } catch (e: Throwable) {
                    Log.e(TAG, "YuvFrame dönüşüm hatası", e)
                    null
                }
                // Buffer açlığı ve maxImages taşmasını önlemek için Image DERHAL kapatılır!
                try { img.close() } catch (_: Throwable) {}

                if (frame != null) {
                    val luma = frame.computeMeanY()
                    Log.i(TAG, "📥 [Ham Sensör YUV #${idx + 1}] Parlaklık (Y-Luma): %.2f / 255.0 (exp=${frame.exposureNs / 1_000_000}ms, iso=${frame.iso}, EV=%.1f)".format(luma, ev))
                    synchronized(burstLock) {
                        collectedFrames.add(frame to ev)
                    }
                }
            } else {
                try { img.close() } catch (_: Throwable) {}
            }

            checkBurstComplete(actualFrameCount, completed, collectedFrames, collectedMetadata, collectedResults, onComplete)
        }

        // Önizlemenin ölçtüğü pozlama; manuel braketin temeli
        val meteredIso = lastResult?.get(CaptureResult.SENSOR_SENSITIVITY)
        val meteredExp = lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val useManualBracket = meteredIso != null && meteredExp != null &&
            flashMode != FlashMode.ON && flashMode != FlashMode.TORCH &&
            ExposureBracketingStrategy.supportsManualSensor(ch)
        Log.i(TAG, "Braket yöntemi: ${if (useManualBracket) "manuel ISO/perde" else "AE telafisi"}")

        val requests = evShifts.mapNotNull { ev ->
            val b = requestBuilder.build(
                device = device,
                ch = ch,
                template = CameraDevice.TEMPLATE_STILL_CAPTURE,
                surfaces = listOf(yuvSurface),
                flashMode = flashMode,
                forYuvBurst = true
            ) ?: return@mapNotNull null

            // Doğal ve temiz Camera2 pozlaması: Samsung 3A motorunu kör etmek yerine
            // sahneyi doğru ölçmesine izin veriyoruz, OIS ve FAST NR ile stabil burst alıyoruz.
            if (useManualBracket) {
                ExposureBracketingStrategy.applyManualEvShift(b, ch, meteredIso!!, meteredExp!!, ev, isNight || bracket.isNight)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                ExposureBracketingStrategy.applyEvShift(b, ch, ev)
            }
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
            if (isNight) {
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                Log.i(TAG, "📷 [Doğal Gece Burst] EV=$ev, AWB_LOCK=ON, OIS=ON, NR=FAST")
            } else {
                Log.i(TAG, "📷 [Doğal Burst] EV=$ev, OIS=ON, NR=FAST")
            }
            b.build()
        }

        try {
            onProgress?.invoke("Burst çekiliyor (${actualFrameCount} kare)")
            sessionManager?.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val idx = metadataCounter.getAndIncrement()
                    val reqExp = request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)
                    val reqIso = request.get(CaptureRequest.SENSOR_SENSITIVITY)
                    val resExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    val resIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
                    val ctrlMode = result.get(CaptureResult.CONTROL_MODE)

                    Log.i(TAG, "📸 [Burst Dönüş #${idx + 1}] İSTENEN: exp=${reqExp?.let { it / 1_000_000 }}ms, iso=$reqIso | " +
                        "GERÇEK (Donanım): exp=${resExp?.let { it / 1_000_000 }}ms, iso=$resIso | " +
                        "AE_MODE=$aeMode, CTRL_MODE=$ctrlMode")

                    if (idx < actualFrameCount) {
                        synchronized(burstLock) {
                            collectedMetadata.add(result)
                        }
                    }
                    checkBurstComplete(actualFrameCount, completed, collectedFrames, collectedMetadata, collectedResults, onComplete)
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure
                ) {
                    if (!completed.getAndSet(true)) {
                        synchronized(burstLock) {
                            collectedFrames.clear()
                            collectedMetadata.clear()
                        }
                        mainPost {
                            yuvBurstFrameListener = null
                            onError("Bracketed burst hatası: ${failure.reason}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            if (!completed.getAndSet(true)) {
                synchronized(burstLock) {
                    collectedFrames.clear()
                    collectedMetadata.clear()
                }
                mainPost {
                    yuvBurstFrameListener = null
                    onError("Bracketed burst istisna: ${e.message}")
                }
            }
        }
    }

    private fun checkBurstComplete(
        actualFrameCount: Int,
        completed: AtomicBoolean,
        collectedFrames: MutableList<Pair<YuvFrame, Float>>,
        collectedMetadata: MutableList<TotalCaptureResult>,
        collectedResults: MutableList<Pair<YuvFrame, TotalCaptureResult>>,
        onComplete: (List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit
    ) {
        if (completed.get()) return

        synchronized(burstLock) {
            if (actualFrameCount > 0 && (collectedFrames.size < actualFrameCount || collectedMetadata.size < actualFrameCount)) return

            if (completed.getAndSet(true)) return

            val count = if (actualFrameCount > 0) minOf(collectedFrames.size, collectedMetadata.size, actualFrameCount) else collectedFrames.size

            for (i in 0 until count) {
                val (frame, _) = collectedFrames[i]
                val meta = collectedMetadata.find { it.get(CaptureResult.SENSOR_TIMESTAMP) == frame.timestampNs }
                    ?: collectedMetadata.getOrNull(i)
                    ?: lastResult

                if (meta != null) {
                    collectedResults.add(frame to meta)
                }
            }

            collectedFrames.clear()
            collectedMetadata.clear()

            mainPost {
                yuvBurstFrameListener = null
                onComplete(collectedResults.toList())
            }
        }
    }

    private fun onJpegAvailable(image: Image) {
        // Kareler istek sırasıyla gelir: JPEG'ini henüz almamış ilk isteğe ait
        val req = captureQueue.firstOrNull { it.jpegBytes == null }
        if (req == null) {
            image.close()
            return
        }

        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            req.jpegBytes = bytes
            tryFinishCapture(req)
        } catch (e: Exception) {
            Log.e(TAG, "JPEG okuma hatası", e)
            discardRequest(req)
            mainPost { req.onError() }
        } finally {
            image.close()
        }
    }

    /** RAW_SENSOR karesi: DNG yazılana kadar açık tutulur, sonra kapatılır. */
    private fun onRawAvailable(image: Image) {
        val req = captureQueue.firstOrNull { it.expectsRaw && it.rawImage == null }
        if (req == null) {
            image.close()
            return
        }
        req.rawImage = image
        tryFinishCapture(req)
    }

    /** İsteği kuyruktan çıkarır ve tuttuğu RAW karesini serbest bırakır. */
    private fun discardRequest(req: PendingCaptureRequest) {
        captureQueue.remove(req)
        try { req.rawImage?.close() } catch (_: Throwable) {}
        req.rawImage = null
    }

    private fun tryFinishCapture(req: PendingCaptureRequest) {
        if (!req.tryComplete()) return
        captureQueue.remove(req)

        val bytes = req.jpegBytes ?: return
        val result = req.captureResult ?: stillCaptureResult ?: lastResult

        procHandler.post {
            val prefix = if (req.isProMode) "PRO" else "IMG"
            val name = MediaStoreHelper.timestampName(prefix)
            val uri = MediaStoreHelper.createImageUri(context, "$name.jpg")
            if (uri == null) {
                discardRequest(req)
                mainPost { req.onError() }
                return@post
            }
            try {
                // Donanım ISP çıktısına Apple/Leica/Hasselblad renk bilimi ve optik keskinlik uygula
                val outputBytes = if (req.colorProfile != null && req.colorProfile != io.github.marquesource.pixora.pipeline.ToneMapHelper.ColorProfile.NEUTRAL) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val mat = io.github.marquesource.pixora.pipeline.YuvConverter.bitmapToMat(bmp)
                        bmp.recycle()

                        val colored = io.github.marquesource.pixora.pipeline.ToneMapHelper.applyColorProfile(mat, req.colorProfile)
                        if (colored !== mat) mat.release()

                        // Pro modunda akütans ve gren optimizasyonu (halo-free)
                        val acutanceStrength = if (req.isProMode) 0.30 else 0.38
                        val sharp = io.github.marquesource.pixora.pipeline.ToneMapHelper.applyAdaptiveAcutance(colored, acutanceStrength, 22.0)
                        colored.release()

                        val finalBmp = io.github.marquesource.pixora.pipeline.YuvConverter.matToBitmap(sharp)
                        sharp.release()

                        val stream = java.io.ByteArrayOutputStream()
                        val quality = if (req.isProMode) 100 else 98
                        finalBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
                        finalBmp.recycle()
                        stream.toByteArray()
                    } catch (e: Exception) {
                        Log.w(TAG, "Renk bilimi uygulanamadı, ham ISP kaydediliyor", e)
                        bytes
                    }
                } else {
                    bytes
                }

                MediaStoreHelper.openOutput(context, uri)?.use { it.write(outputBytes) }
                try {
                    // Donanım pikselleri zaten döndürdüyse (etiket "normal") tekrar döndürme etiketi yazma
                    val exifRotation = ExifHelper.readOrientationDegrees(bytes) ?: req.orientation
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        ExifHelper.writeCaptureMetadata(pfd.fileDescriptor, result, req.location, exifRotation)
                    }
                } catch (_: Exception) {}
                MediaStoreHelper.finalizePending(context, uri)

                // Gerçek RAW: sensörden gelen RAW_SENSOR karesi DngCreator ile DNG'ye yazılır
                saveRawIfPresent(req, result)

                val elapsed = System.currentTimeMillis() - req.createTime
                Log.d("ImagePipeline", "Pipeline tamamlandı: ${elapsed}ms")

                if (req.isProMode && req.onProSaved != null) {
                    val expNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                    val isoVal = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                    val shutterStr = Camera2Utils.formatExposureTime(expNs)
                    val profName = req.colorProfile?.displayNameTr ?: "Pro"
                    val info = "$shutterStr · ISO $isoVal · $profName"
                    mainPost { req.onProSaved.invoke(uri, info) }
                } else {
                    mainPost { req.onSaved(uri) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "JPEG kaydetme hatası", e)
                discardRequest(req)
                mainPost { req.onError() }
            }
        }
    }

    private fun saveRawIfPresent(req: PendingCaptureRequest, result: TotalCaptureResult?) {
        val raw = req.rawImage ?: return
        try {
            val ch = req.characteristics
            if (ch != null && result != null) {
                val dngUri = io.github.marquesource.pixora.utils.DngHelper.saveDng(
                    context, ch, result, raw, req.location, req.orientation
                )
                if (dngUri != null) Log.i(TAG, "RAW DNG kaydedildi: $dngUri")
                else Log.w(TAG, "RAW DNG kaydedilemedi")
            }
        } catch (e: Exception) {
            Log.w(TAG, "RAW DNG yazma hatası", e)
        } finally {
            try { raw.close() } catch (_: Throwable) {}
            req.rawImage = null
        }
    }

    fun setFlash(mode: FlashMode) {
        flashMode = mode
        startPreviewRepeating()
    }

    /**
     * Dokunarak odak/pozlama. [lock] = uzun basma: AF/AE kilitlenir ve bir sonraki dokunuşa kadar kalır.
     * Kilitsiz dokunuşta odak 5 sn dokunulan noktada tutulur, sonra sürekli odağa dönülür.
     */
    fun focusAt(x: Float, y: Float, lock: Boolean) {
        if (isCameraClosing.get()) return
        cameraDevice ?: return
        if (focus.isManual()) return
        val streamAspect = previewSize.width.toFloat() / previewSize.height
        if (!focus.setTapPoint(x, y, previewView.contentRect(), zoom.currentRatio, streamAspect, lock)) return
        exposure.aeLocked = false

        val triggerBuilder = buildRepeatingRequest() ?: return
        focus.triggerAf(triggerBuilder)
        val bg = bgHandler ?: return
        bg.removeCallbacksAndMessages(focusResetToken)
        sessionManager?.capture(triggerBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                if (lock) exposure.aeLocked = true
                val repeat = buildRepeatingRequest() ?: return
                focus.clearAfTrigger(repeat)
                sessionManager?.startRepeating(repeat.build(), previewCallback)
            }
        })
        if (!lock) {
            bg.postAtTime({
                if (!focus.afLocked) {
                    focus.clearTap()
                    startPreviewRepeating()
                }
            }, focusResetToken, android.os.SystemClock.uptimeMillis() + 5000L)
        }
    }

    /** Dokunarak odak/kilidi bırakıp sürekli odağa döner. */
    fun clearFocusLock() {
        bgHandler?.removeCallbacksAndMessages(focusResetToken)
        focus.clearTap()
        exposure.aeLocked = false
        startPreviewRepeating()
    }

    fun setZoomRatio(ratio: Float) {
        zoom.setZoom(ratio)
        startPreviewRepeating()
    }

    fun scaleZoom(factor: Float) {
        zoom.scale(factor)
        startPreviewRepeating()
    }

    /**
     * Lens/zoom düğmeleri. @return seçim uygulandıysa true (ör. ultra geniş erişilemiyorsa false).
     */
    fun selectModule(module: CameraModule): Boolean {
        if (currentFacing != Camera2Utils.Facing.BACK) return false
        focus.clearTap()
        when (module) {
            CameraModule.ULTRA_WIDE -> {
                val uw = Camera2Utils.ultraWideCamera(cameras)
                when {
                    zoom.minZoom() < 0.99f -> setZoomRatio(zoom.minZoom())
                    uw != null && uw.id != currentInfo?.id -> openCamera(uw)
                    else -> {
                        // Samsung bazı modellerde ultra geniş lensi üçüncü taraf uygulamalara açmıyor
                        onError?.invoke("Bu telefonda ultra geniş lens uygulamalara açık değil")
                        return false
                    }
                }
            }
            CameraModule.WIDE -> {
                val main = Camera2Utils.mainCamera(cameras, Camera2Utils.Facing.BACK)
                if (main != null && main.id != currentInfo?.id) openCamera(main)
                else setZoomRatio(1f)
            }
            CameraModule.TELE_2X -> {
                val main = Camera2Utils.mainCamera(cameras, Camera2Utils.Facing.BACK)
                if (main != null && main.id != currentInfo?.id) {
                    openCamera(main, initialZoom = 2f)
                } else setZoomRatio(2f)
            }
        }
        return true
    }

    fun toggleLens() {
        currentFacing = if (currentFacing == Camera2Utils.Facing.BACK)
            Camera2Utils.Facing.FRONT else Camera2Utils.Facing.BACK
        exposure.reset(); focus.reset(); whiteBalance.reset()
        openForFacing(currentFacing)
    }

    fun currentCharacteristics(): CameraCharacteristics? = characteristics

    fun currentDeviceRotationOrientation(deviceRotation: Int): Int {
        val ch = characteristics ?: return 0
        return jpegOrientation(ch, deviceRotation)
    }

    fun isRawSupported(): Boolean = currentInfo?.supportsRaw == true

    /** Gece/portre burst çekimi sürüyor mu? */
    fun isBurstInProgress(): Boolean = burstInProgress.get()

    /** Dikey tutulan telefonda önizlemenin yatay görüş açısı (panorama birleştirme için). */
    fun portraitHorizontalFovRad(): Double {
        val info = currentInfo ?: return Math.toRadians(66.0)
        val size = info.physicalSize ?: return Math.toRadians(66.0)
        val longSide = maxOf(size.width, size.height)
        var shortSide = minOf(size.width, size.height)
        // Önizleme akışı sensörden daha geniş (ör. 16:9) ise sensörün kısa kenarı kırpılır
        val streamAspect = maxOf(previewSize.width, previewSize.height).toFloat() / minOf(previewSize.width, previewSize.height)
        if (streamAspect > longSide / shortSide) shortSide = longSide / streamAspect
        return PanoramaCapture.horizontalFovPortrait(info.focalLength, shortSide, zoom.currentRatio)
    }

    fun refreshPreview() {
        startPreviewRepeating()
    }

    /**
     * Video/time-lapse oturumu: önizleme + kayıt yüzeyi. Kayıt yüzeyi saklanır; kayıt sürerken
     * zoom/odak/pozlama/flaş değişiklikleri de bu yüzeyi hedefler (eskiden video donuyordu).
     */
    fun startVideoSession(
        recorderSurface: Surface,
        fps: Int = 30,
        stabilization: Boolean = false,
        onReady: () -> Unit
    ) {
        val device = cameraDevice ?: return
        val session = sessionManager ?: return
        bgHandler?.removeCallbacksAndMessages(focusResetToken)
        focus.clearTap()

        val preview = previewSurface ?: previewView.createSurface()?.also { previewSurface = it } ?: return
        session.createSession(device, listOf(preview, recorderSurface), object : CaptureSessionManager.Callback {
            override fun onConfigured(s: CameraCaptureSession) {
                if (isCameraClosing.get()) return
                recordingSurface = recorderSurface
                recordingFps = fps
                recordingStabilization = stabilization
                val builder = buildRepeatingRequest() ?: return
                session.startRepeating(builder.build(), previewCallback)
                mainPost(onReady)
            }
            override fun onFailed(reason: String) {
                recordingSurface = null
                onError?.invoke(reason)
            }
        })
    }

    /** Kaydı durdurmadan önce: kameranın kayıt yüzeyine kare göndermesini kes. */
    fun prepareStopRecording() {
        try { sessionManager?.stopRepeating() } catch (_: Throwable) {}
    }

    fun stopRecordingReturnPreview() {
        recordingSurface = null
        focus.clearTap()
        characteristics?.let { chooseSizes(it) }
        createPreviewSession()
    }

    /**
     * JPEG_ORIENTATION hesabı (Android CaptureRequest.JPEG_ORIENTATION dokümanındaki formül).
     * [deviceRotation], OrientationEventListener'dan gelen saat yönündeki fiziksel açıdır (0/90/180/270).
     * Arka kamera: (sensör + açı), ön kamera: (sensör − açı) — ön kamera ayna olduğu için yön terslenir.
     */
    private fun jpegOrientation(ch: CameraCharacteristics, deviceRotation: Int): Int {
        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val rotation = ((deviceRotation % 360) + 360) % 360
        return if (currentFacing == Camera2Utils.Facing.FRONT) {
            (sensorOrientation - rotation + 360) % 360
        } else {
            (sensorOrientation + rotation) % 360
        }
    }

    private val mainHandler = Handler(context.mainLooper)
    private fun mainPost(block: () -> Unit) = mainHandler.post(block)

    private fun closeCamera() {
        isCameraClosing.set(true)
        yuvBurstFrameListener = null
        captureQueue.toList().forEach { discardRequest(it) }
        sessionManager?.close()
        try { cameraDevice?.close() } catch (_: Throwable) {}
        cameraDevice = null
        try { yuvBurstReader?.close(); yuvBurstReader = null; yuvBurstSurface = null } catch (_: Throwable) {}
        imageReaders?.closeAll()
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
        captureQueue.clear()
    }

    fun release() {
        recordingSurface = null
        closeCamera()
        stopBackgroundThread()
    }
}

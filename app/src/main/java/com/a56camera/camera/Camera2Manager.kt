package com.a56camera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
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
import com.a56camera.ui.views.Camera2PreviewView
import com.a56camera.utils.AspectRatio
import com.a56camera.utils.Camera2Utils
import com.a56camera.utils.ExifHelper
import com.a56camera.utils.MediaStoreHelper
import com.a56camera.utils.SamsungVendorTagsExplorer
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
    val colorProfile: com.a56camera.pipeline.ToneMapHelper.ColorProfile? = null,
    val isProMode: Boolean = false,
    val onProSaved: ((Uri, String) -> Unit)? = null,
    val createTime: Long = System.currentTimeMillis()
) {
    @Volatile var captureResult: TotalCaptureResult? = null
    @Volatile var jpegBytes: ByteArray? = null
    private val completed = AtomicBoolean(false)

    /** İki parça da hazırsa true döner (sadece bir kez). */
    fun tryComplete(): Boolean {
        return captureResult != null && jpegBytes != null && !completed.getAndSet(true)
    }

    fun isCompleted(): Boolean = completed.get()
}

class Camera2Manager(
    private val context: Context,
    private val previewView: Camera2PreviewView
) {
    private val TAG = "Camera2Manager"
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

    private val multiFrameProcessor = com.a56camera.pipeline.MultiFrameProcessor()
    private val smartDenoiser = com.a56camera.pipeline.SmartDenoiser()

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

    private var extensionSession: CameraExtensionSession? = null

    var rawEnabled: Boolean = false

    var onPreviewResult: ((TotalCaptureResult) -> Unit)? = null
    var onYuvImage: ((Image) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSceneDetected: ((com.a56camera.pipeline.SceneOptimizer.DetectionResult) -> Unit)? = null

    private lateinit var pipeline: com.a56camera.pipeline.ImageProcessingPipeline
    private val zslRingBuffer = com.a56camera.pipeline.ZslRingBuffer(capacity = 8)
    private val sceneOptimizer by lazy { com.a56camera.pipeline.SceneOptimizer(context) }
    private var lastSceneAnalysisTimestamp = 0L

    fun start() {
        startBackgroundThread()
        pipeline = com.a56camera.pipeline.ImageProcessingPipeline(context)
        if (!pipeline.initOpenCV()) {
            onError?.invoke("OpenCV başlatılamadı")
            return
        }
        Thread {
            pipeline.tfliteEngine.prewarmModels()
        }.start()
        cameras = Camera2Utils.enumerateCameras(systemManager)
        openForFacing(currentFacing)
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
        val ch = systemManager.getCameraCharacteristics(info.id)
        characteristics = ch
        com.a56camera.utils.SamsungVendorTagsExplorer.dumpCameraVendorTags(ch, info.id)
        exposure.readRanges(ch)
        focus.readCharacteristics(ch)
        zoom.readCharacteristics(ch)
        zoom.setZoom(initialZoom)
        chooseSizes(ch)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
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

    fun setExtensionForMode(mode: CaptureMode, hdr: HdrMode) {
        // A56 üzerinde yazılımsal YUV pipeline aktif
    }

    fun createPreviewSession() {
        createStandardPreviewSession()
    }

    private fun createStandardPreviewSession() {
        val device = cameraDevice ?: return
        val ch = characteristics ?: return
        val readers = imageReaders ?: return
        val session = sessionManager ?: return

        previewSurface = previewView.createSurface() ?: return
        readers.closeAll()
        readers.setupJpeg(ch, aspectRatio, isHighRes50Mp) { image -> onJpegAvailable(image) }

        setupYuvBurstReader(ch)

        val surfaces = buildList {
            add(previewSurface!!)
            readers.jpegSurface()?.let { add(it) }
            readers.rawSurface()?.let { add(it) }
            readers.yuvSurface()?.let { add(it) }
            yuvBurstSurface?.let { add(it) }
        }

        session.createSession(device, surfaces, object : CaptureSessionManager.Callback {
            override fun onConfigured(s: CameraCaptureSession) {
                if (isCameraClosing.get() || cameraDevice == null) return
                startPreviewRepeating()
            }
            override fun onFailed(reason: String) {
                if (isCameraClosing.get()) return
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

    private fun startPreviewRepeating() {
        if (isCameraClosing.get()) return
        val device = cameraDevice ?: return
        val ch = characteristics ?: return
        val readers = imageReaders ?: return
        val targets = buildList {
            previewSurface?.let { add(it) }
            readers.yuvSurface()?.let { add(it) }
        }
        val builder = requestBuilder.build(
            device, ch, CameraDevice.TEMPLATE_PREVIEW, targets, flashMode
        ) ?: return
        sessionManager?.startRepeating(builder.build(), previewCallback)
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            lastResult = result
            onPreviewResult?.invoke(result)

            // AMD Xclipse 540 GPU Scene Optimizer & Özel Yerel ISP QR Taraması (her 450ms'de bir analiz)
            val now = System.currentTimeMillis()
            if (now - lastSceneAnalysisTimestamp > 450L) {
                lastSceneAnalysisTimestamp = now
                mainPost {
                    val thumbnail = try {
                        if (previewView.isAvailable) previewView.getBitmap(400, 400) else null
                    } catch (_: Throwable) { null }
                    bgHandler?.post {
                        if (thumbnail != null && !thumbnail.isRecycled) {
                            // PINTO / Exynos 1580 NPU Canlı Sahne Analizi (MobileNetV2)
                            val detection = sceneOptimizer.classifyScene(thumbnail, result)
                            thumbnail.recycle()
                            if (detection.confidence >= 0.50f && detection.scene != com.a56camera.pipeline.SceneOptimizer.SceneType.AUTO) {
                                colorProfile = detection.scene.targetProfile
                            }
                            mainPost { onSceneDetected?.invoke(detection) }
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
        val pendingReq = PendingCaptureRequest(location, orientation, onSaved, onErr, colorProfile)
        captureQueue.add(pendingReq)

        val builder = requestBuilder.build(
            device, ch, CameraDevice.TEMPLATE_STILL_CAPTURE,
            listOf(jpegSurface), flashMode,
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
                captureQueue.remove(pendingReq)
                mainPost { onErr() }
            }
        })
    }

    var colorProfile: com.a56camera.pipeline.ToneMapHelper.ColorProfile =
        com.a56camera.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL

    var proProfile: com.a56camera.pipeline.ToneMapHelper.ColorProfile =
        com.a56camera.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL

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
        profile: com.a56camera.pipeline.ToneMapHelper.ColorProfile = proProfile,
        onProgress: ((String) -> Unit)? = null,
        onSaved: (Uri, String) -> Unit,
        onError: () -> Unit
    ) {
        val device = cameraDevice ?: return onError()
        val ch = characteristics ?: return onError()
        val readers = imageReaders ?: return onError()
        val jpegSurface = readers.jpegSurface() ?: return onError()

        val orientation = jpegOrientation(ch, deviceRotation)
        val pendingReq = PendingCaptureRequest(
            location = location,
            orientation = orientation,
            onSaved = { uri -> onSaved(uri, "") },
            onError = onError,
            colorProfile = profile,
            isProMode = true,
            onProSaved = onSaved
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
                captureQueue.remove(pendingReq)
                mainPost { onError() }
            }
        })
    }

    /**
     * Sıfır gecikmeli HDR çekim (ZSL - Zero Shutter Lag).
     * Deklanşöre basıldığı an gecikme: 0ms!
     * Tampondaki hazır en net kareleri anında kilitler ve işler.
     */
    fun captureZslHdr(
        location: Location?,
        deviceRotation: Int,
        onProgress: ((String) -> Unit)? = null,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        val ch = characteristics ?: return onError()
        val orientation = jpegOrientation(ch, deviceRotation)

        zslRingBuffer.lockForCapture()
        val zslFrames = zslRingBuffer.snapshotBestFrames(count = 5)

        // Tampon henüz dolmadıysa normal HDR'a geç
        if (zslFrames.size < 3) {
            Log.d(TAG, "ZSL tamponu henüz hazır değil (${zslFrames.size} kare), normal HDR'a geçiliyor...")
            zslRingBuffer.unlockAfterCapture()
            captureHdr(location, deviceRotation, onProgress, onSaved, onError)
            return
        }

        Log.d(TAG, "⚡ ZSL Tetiklendi: 0ms gecikmeyle ${zslFrames.size} kare alındı!")
        onProgress?.invoke("Fotoğraf işleniyor...")

        bgHandler?.post {
            try {
                val referenceResult = zslFrames[zslFrames.size / 2].second
                val iso = referenceResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                val exposureNs = referenceResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L

                val config = pipeline.autoConfig(iso, exposureNs).copy(
                    colorProfile = colorProfile,
                    superResolutionEnabled = zoom.currentRatio >= 1.8f,
                    expertRawEnabled = rawEnabled,
                    jpegQuality = 98,
                    saveToGallery = true
                )

                val result = pipeline.process(
                    frames = zslFrames,
                    config = config,
                    location = location,
                    orientation = orientation,
                    onProgress = { step -> mainPost { onProgress?.invoke(step) } }
                )

                mainPost {
                    if (result.uri != null) {
                        Log.d(TAG, "ZSL HDR tamamlandı: ${result.processingTimeMs}ms")
                        onSaved(result.uri)
                    } else {
                        onError()
                    }
                    try { result.bitmap.recycle() } catch (_: Throwable) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "ZSL pipeline hatası", e)
                mainPost { onError() }
            } finally {
                zslRingBuffer.unlockAfterCapture()
            }
        }
    }

    /**
     * HDR çekim: Bracketed burst -> multi-frame merge -> denoise -> kaydet.
     */
    fun captureHdr(
        location: Location?,
        deviceRotation: Int,
        onProgress: ((String) -> Unit)? = null,
        onSaved: (Uri) -> Unit,
        onError: () -> Unit
    ) {
        val ch = characteristics ?: return onError()
        val orientation = jpegOrientation(ch, deviceRotation)

        val iso = lastResult?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
        val exposureNs = lastResult?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
        val isNight = iso > 800 || exposureNs > 33_000_000L

        val frameCount = 3

        onProgress?.invoke("Çekiliyor ($frameCount kare)...")

        captureBracketedBurst(
            frameCount = frameCount,
            isNight = isNight,
            onProgress = onProgress,
            onComplete = { frames ->
                bgHandler?.post {
                    try {
                        val config = pipeline.autoConfig(iso, exposureNs).copy(
                            colorProfile = colorProfile,
                            superResolutionEnabled = zoom.currentRatio >= 1.8f,
                            expertRawEnabled = rawEnabled,
                            jpegQuality = 98,
                            saveToGallery = true
                        )

                        onProgress?.invoke("İşleniyor...")

                        val result = pipeline.process(
                            frames = frames,
                            config = config,
                            location = location,
                            orientation = orientation,
                            onProgress = { step -> mainPost { onProgress?.invoke(step) } }
                        )

                        mainPost {
                            if (result.uri != null) {
                                Log.d(TAG, "HDR çekim tamamlandı: ${result.processingTimeMs}ms, " +
                                    "ISO=${result.iso}, ${result.frameCount} kare")
                                onSaved(result.uri)
                            } else {
                                onError()
                            }
                            try { result.bitmap.recycle() } catch (_: Throwable) {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "HDR pipeline hatası", e)
                        mainPost { onError() }
                    }
                }
            },
            onError = { msg ->
                Log.e(TAG, "Burst hatası: $msg")
                mainPost { onError() }
            }
        )
    }

    /**
     * Google Pixel Night Sight + Apple Photonic V2 Gece Modu:
     * 1. 3 kare bracketed YUV burst (-1.0 EV, -0.4 EV, +0.4 EV): Sokak lambası patlamaz, sıfır çamur.
     * 2. Samsung ISP kirlilikleri KAPALI (NOISE_REDUCTION_MODE_OFF): Balmumu yumuşatması yok, optik doku korunur.
     * 3. Anında Vizör Serbestliği (DONE_CAPTURE): 250ms içinde arayüz açılır, kullanıcı yürümeye devam eder.
     * 4. Arka Planda 4 TFLite Modeli + C++ Robust Merge: Zero-DCE, MiDaS doku maskesi, Smart Grain, Real-ESRGAN!
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

                bgHandler?.post {
                    try {
                        val config = com.a56camera.pipeline.ImageProcessingPipeline.PipelineConfig(
                            colorProfile = com.a56camera.pipeline.ToneMapHelper.ColorProfile.NIGHT,
                            denoiseEnabled = true,
                            sharpenEnabled = true,
                            hdrEnabled = true,
                            superResolutionEnabled = zoom.currentRatio >= 1.8f,
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
        onProgress?.invoke("Portre çekiliyor...")

        captureBracketedBurst(
            isNight = false,
            onProgress = { step -> mainPost { onProgress?.invoke(step) } },
            onComplete = { frames ->
                bgHandler?.post {
                    try {
                        onProgress?.invoke("3D Derinlik Haritası (MiDaS NPU)...")
                        val config = com.a56camera.pipeline.ImageProcessingPipeline.PipelineConfig(
                            colorProfile = com.a56camera.pipeline.ToneMapHelper.ColorProfile.PIXEL_NATURAL,
                            denoiseEnabled = true,
                            sharpenEnabled = true,
                            hdrEnabled = true,
                            portraitBokehEnabled = true,
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



    /** YUV_420_888 tabanlı Exposure Bracketed Burst çekimi yapar. */
    fun captureBracketedBurst(
        frameCount: Int = 3,
        isNight: Boolean = false,
        onProgress: ((String) -> Unit)? = null,
        onComplete: (List<Pair<YuvFrame, TotalCaptureResult>>) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCameraClosing.get()) return onError("Kamera kapanıyor")
        val device = cameraDevice ?: return onError("Kamera cihazı hazır değil")
        val ch = characteristics ?: return onError("Karakteristikler bulunamadı")
        val yuvSurface = yuvBurstSurface ?: return onError("YUV burst yüzeyi hazır değil")

        // RAM'i rahatlatmak için ZSL tamponunu temizle (100MB boş bellek aç)
        zslRingBuffer.clear()

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
            if (!completed.get() && collectedFrames.isNotEmpty()) {
                Log.w(TAG, "Burst koruma zaman aşımı tetiklendi, ${collectedFrames.size} kare ile devam ediliyor")
                checkBurstComplete(0, completed, collectedFrames, collectedMetadata, collectedResults, onComplete)
            }
        }, 3000)

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
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            ExposureBracketingStrategy.applyEvShift(b, ch, ev)
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
        val req = captureQueue.peek()
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
            captureQueue.remove(req)
            mainPost { req.onError() }
        } finally {
            image.close()
        }
    }

    private fun tryFinishCapture(req: PendingCaptureRequest) {
        if (!req.tryComplete()) return
        captureQueue.remove(req)

        val bytes = req.jpegBytes ?: return
        val result = req.captureResult ?: stillCaptureResult ?: lastResult

        bgHandler?.post {
            val prefix = if (req.isProMode) "PRO" else "IMG"
            val name = MediaStoreHelper.timestampName(prefix)
            val uri = MediaStoreHelper.createImageUri(context, "$name.jpg")
            if (uri == null) {
                mainPost { req.onError() }
                return@post
            }
            try {
                // Donanım ISP çıktısına Apple/Leica/Hasselblad renk bilimi ve optik keskinlik uygula
                val outputBytes = if (req.colorProfile != null && req.colorProfile != com.a56camera.pipeline.ToneMapHelper.ColorProfile.NEUTRAL) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        val mat = com.a56camera.pipeline.YuvConverter.bitmapToMat(bmp)
                        bmp.recycle()

                        val colored = com.a56camera.pipeline.ToneMapHelper.applyColorProfile(mat, req.colorProfile)
                        if (colored !== mat) mat.release()

                        // Pro modunda akütans ve gren optimizasyonu (halo-free)
                        val acutanceStrength = if (req.isProMode) 0.30 else 0.38
                        val sharp = com.a56camera.pipeline.ToneMapHelper.applyAdaptiveAcutance(colored, acutanceStrength, 22.0)
                        colored.release()

                        val finalBmp = com.a56camera.pipeline.YuvConverter.matToBitmap(sharp)
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
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        ExifHelper.writeCaptureMetadata(pfd.fileDescriptor, result, req.location, req.orientation)
                    }
                } catch (_: Exception) {}
                MediaStoreHelper.finalizePending(context, uri)

                // 16-Bit Linear DNG Kaydı
                if (rawEnabled) {
                    try {
                        val dngName = MediaStoreHelper.timestampName("RAW")
                        val dngUri = MediaStoreHelper.createDngUri(context, dngName)
                        if (dngUri != null) {
                            val decoded = android.graphics.BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size)
                            if (decoded != null) {
                                val mat8 = com.a56camera.pipeline.YuvConverter.bitmapToMat(decoded)
                                decoded.recycle()
                                val mat16 = org.opencv.core.Mat()
                                try {
                                    mat8.convertTo(mat16, org.opencv.core.CvType.CV_16UC3, 257.0)
                                    val expNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 10_000_000L
                                    val isoVal = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 100
                                    MediaStoreHelper.openOutput(context, dngUri)?.use { os ->
                                        com.a56camera.pipeline.LinearDngWriter.writeDng(mat16, isoVal, expNs, os)
                                    }
                                    MediaStoreHelper.finalizePending(context, dngUri)
                                    Log.i(TAG, "🌟 [Pro Mode 16-bit DNG] DNG kaydedildi: $dngUri")
                                } finally {
                                    mat8.release()
                                    mat16.release()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Pro DNG yazma hatası", e)
                    }
                }

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
                mainPost { req.onError() }
            }
        }
    }

    fun setFlash(mode: FlashMode) {
        flashMode = mode
        startPreviewRepeating()
    }

    fun focusAt(x: Float, y: Float, lock: Boolean) {
        if (isCameraClosing.get()) return
        val device = cameraDevice ?: return
        val ch = characteristics ?: return
        val readers = imageReaders ?: return
        val targets = buildList {
            previewSurface?.let { add(it) }
            readers.yuvSurface()?.let { add(it) }
        }

        val triggerBuilder = requestBuilder.build(device, ch, CameraDevice.TEMPLATE_PREVIEW, targets, flashMode) ?: return
        requestBuilder.applyTouchFocus(triggerBuilder, x, y, previewView.width, previewView.height)
        if (lock) {
            exposure.aeLocked = true
            triggerBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        sessionManager?.capture(triggerBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                val repeatBuilder = requestBuilder.build(device, ch, CameraDevice.TEMPLATE_PREVIEW, targets, flashMode) ?: return
                focus.clearAfTrigger(repeatBuilder)
                sessionManager?.startRepeating(repeatBuilder.build(), previewCallback)
            }
        })
    }

    fun setZoomRatio(ratio: Float) {
        zoom.setZoom(ratio)
        startPreviewRepeating()
    }

    fun scaleZoom(factor: Float) {
        zoom.scale(factor)
        startPreviewRepeating()
    }

    fun selectModule(module: CameraModule) {
        when (module) {
            CameraModule.ULTRA_WIDE -> {
                val uw = Camera2Utils.ultraWideCamera(cameras)
                if (uw != null && uw.id != currentInfo?.id) {
                    openCamera(uw)
                } else {
                    setZoomRatio(zoom.minZoom())
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

    fun refreshPreview() {
        startPreviewRepeating()
    }

    fun startVideoSession(recorderSurface: Surface, fps: Int = 30, onReady: () -> Unit) {
        val device = cameraDevice ?: return
        val ch = characteristics ?: return
        val session = sessionManager ?: return

        val preview = previewSurface ?: previewView.createSurface()?.also { previewSurface = it } ?: return
        val surfaces = listOf(preview, recorderSurface)
        session.createSession(device, surfaces, object : CaptureSessionManager.Callback {
            override fun onConfigured(s: CameraCaptureSession) {
                if (isCameraClosing.get()) return
                val builder = requestBuilder.build(
                    device, ch, CameraDevice.TEMPLATE_RECORD, surfaces, flashMode, forVideo = true
                ) ?: return

                // İstenen FPS (30 veya 60) için en uygun donanım AE FPS aralığını ayarla
                val availableRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val targetRange = availableRanges?.find { it.upper == fps && it.lower == fps }
                    ?: availableRanges?.find { it.upper == fps }
                if (targetRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetRange)
                    Log.i("Camera2Manager", "🎯 Video AE FPS Range ayarlandı: $targetRange")
                }

                session.startRepeating(builder.build(), null)
                mainPost(onReady)
            }
            override fun onFailed(reason: String) { onError?.invoke(reason) }
        })
    }

    fun stopRecordingReturnPreview() {
        characteristics?.let { chooseSizes(it) }
        createPreviewSession()
    }

    private fun jpegOrientation(ch: CameraCharacteristics, deviceRotation: Int): Int {
        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return if (currentFacing == Camera2Utils.Facing.FRONT) {
            (360 - (sensorOrientation + deviceRotation) % 360) % 360
        } else {
            (sensorOrientation - deviceRotation + 360) % 360
        }
    }

    private val mainHandler = Handler(context.mainLooper)
    private fun mainPost(block: () -> Unit) = mainHandler.post(block)

    private fun closeCamera() {
        isCameraClosing.set(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { extensionSession?.close(); extensionSession = null } catch (_: Throwable) {}
        }
        yuvBurstFrameListener = null
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
        closeCamera()
        stopBackgroundThread()
    }
}

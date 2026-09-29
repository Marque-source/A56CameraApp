package com.a56camera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Samsung Camera SDK importları. SDK, libs/ altındaki camera-v1.3.2.aar +
// sdk-v1.0.0.jar dosyalarından derleme zamanında gelir ve APK'ya paketlenir.
// Çalışma zamanında SDK'yı desteklemeyen cihazlarda SCamera.initialize() istisna
// atar; bu sınıftaki TÜM SDK erişimi try-catch ile korunur, böylece Samsung dışı
// cihazlarda uygulama standart Camera2 ile çalışmaya devam eder.
import com.samsung.android.sdk.camera.SCamera
import com.samsung.android.sdk.camera.processor.SCameraHdrProcessor
import com.samsung.android.sdk.camera.processor.SCameraLowLightProcessor
import com.samsung.android.sdk.camera.processor.SCameraProcessorManager

/**
 * Samsung NPU tabanlı görüntü işlemcilerine (HDR, düşük ışık, bokeh) sarmalayıcı.
 *
 * Samsung Camera SDK'sının `SCameraProcessorManager` API'si üzerinden donanım
 * hızlandırmalı işlemcileri kullanır:
 *  - **HDR**: Farklı pozlamalarda çekilen 3 kareden tek yüksek dinamik aralıklı görüntü.
 *  - **Düşük ışık (Low Light)**: Aynı pozlamada çekilen çok sayıda karenin birleştirilmesi (multi-frame stacking).
 *  - **Alan derinliği (DoF/bokeh)**: Portre efekti.
 *
 * SDK bulunmayan/desteklemeyen cihazlarda tüm metotlar güvenle `false`/`null` döner
 * (graceful degradation) — çağıran taraf standart Camera2 çekimine düşmelidir.
 *
 * NOT: [processHdr] ve [processLowLight] senkron çalışır (sonucu bekler). Bunları
 * arka plan iş parçacığından çağırın; ana iş parçacığından çağırmayın.
 */
class SamsungNpuProcessor(context: Context) {

    private var processorManager: SCameraProcessorManager? = null
    private var available = false

    // İşlemci geri çağrıları bu iş parçacığına teslim edilir.
    private val bgThread = HandlerThread("SamsungNpu").apply { start() }
    private val handler = Handler(bgThread.looper)

    init {
        try {
            val sCamera = SCamera()
            sCamera.initialize(context)
            if (sCamera.isFeatureEnabled(SCamera.SCAMERA_PROCESSOR)) {
                processorManager = sCamera.sCameraProcessorManager
                available = true
                Log.i(TAG, "Samsung NPU işlemcileri hazır")
            } else {
                Log.i(TAG, "Cihaz SCAMERA_PROCESSOR özelliğini desteklemiyor")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Samsung Camera SDK kullanılamıyor: ${e.message}")
            available = false
        }
    }

    /** SDK ve işlemci yöneticisi bu cihazda kullanılabilir mi? */
    fun isAvailable(): Boolean = available

    /** NPU HDR işlemcisi destekleniyor mu? */
    fun isHdrSupported(): Boolean = try {
        available && processorManager?.isProcessorAvailable(
            SCameraProcessorManager.PROCESSOR_TYPE_HDR
        ) == true
    } catch (e: Throwable) {
        Log.w(TAG, "isHdrSupported hatası: ${e.message}"); false
    }

    /** NPU düşük ışık işlemcisi destekleniyor mu? */
    fun isLowLightSupported(): Boolean = try {
        available && processorManager?.isProcessorAvailable(
            SCameraProcessorManager.PROCESSOR_TYPE_LOW_LIGHT
        ) == true
    } catch (e: Throwable) {
        Log.w(TAG, "isLowLightSupported hatası: ${e.message}"); false
    }

    /**
     * 3 farklı pozlamalı JPEG görüntüden tek bir HDR görüntü üretir.
     * @return HDR sonucu Bitmap; desteklenmiyorsa/başarısızsa null.
     */
    fun processHdr(images: List<Image>): Bitmap? {
        if (!isHdrSupported() || images.isEmpty()) return null
        val pm = processorManager ?: return null
        var processor: SCameraHdrProcessor? = null
        return try {
            processor = pm.createProcessor(SCameraProcessorManager.PROCESSOR_TYPE_HDR)
            val first = images.first()
            val param = processor.parameters
            param.set(SCameraHdrProcessor.STILL_INPUT_FORMAT, ImageFormat.JPEG)
            param.set(SCameraHdrProcessor.STILL_OUTPUT_FORMAT, ImageFormat.JPEG)
            param.set(SCameraHdrProcessor.STILL_SIZE, Size(first.width, first.height))
            processor.setParameters(param)

            var output: Bitmap? = null
            val latch = CountDownLatch(1)
            processor.setEventCallback(object : SCameraHdrProcessor.EventCallback() {
                override fun onProcessCompleted(result: Image?) {
                    output = result?.let { imageToBitmap(it) }
                    result?.close()
                    latch.countDown()
                }

                override fun onError(code: Int) {
                    Log.w(TAG, "HDR işleme hatası: $code")
                    latch.countDown()
                }
            }, handler)

            processor.initialize()
            processor.requestMultiProcess(images)
            latch.await(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            output
        } catch (e: Throwable) {
            Log.w(TAG, "processHdr hatası: ${e.message}"); null
        } finally {
            try { processor?.deinitialize() } catch (_: Throwable) {}
        }
    }

    /**
     * Aynı pozlamada çekilmiş çok sayıda kareyi birleştirerek gürültüsü azaltılmış
     * tek bir düşük ışık görüntüsü üretir (multi-frame stacking).
     * @return İyileştirilmiş Bitmap; desteklenmiyorsa/başarısızsa null.
     */
    fun processLowLight(images: List<Image>): Bitmap? {
        if (!isLowLightSupported() || images.isEmpty()) return null
        val pm = processorManager ?: return null
        var processor: SCameraLowLightProcessor? = null
        return try {
            processor = pm.createProcessor(SCameraProcessorManager.PROCESSOR_TYPE_LOW_LIGHT)
            val first = images.first()
            val param = processor.parameters
            param.set(SCameraLowLightProcessor.STILL_INPUT_FORMAT, ImageFormat.JPEG)
            param.set(SCameraLowLightProcessor.STILL_OUTPUT_FORMAT, ImageFormat.JPEG)
            param.set(SCameraLowLightProcessor.STILL_SIZE, Size(first.width, first.height))
            processor.setParameters(param)

            var output: Bitmap? = null
            val latch = CountDownLatch(1)
            processor.setEventCallback(object : SCameraLowLightProcessor.EventCallback() {
                override fun onProcessCompleted(result: Image?) {
                    output = result?.let { imageToBitmap(it) }
                    result?.close()
                    latch.countDown()
                }

                override fun onError(code: Int) {
                    Log.w(TAG, "Düşük ışık işleme hatası: $code")
                    latch.countDown()
                }
            }, handler)

            processor.initialize()
            processor.requestMultiProcess(images)
            latch.await(PROCESS_TIMEOUT_SEC, TimeUnit.SECONDS)
            output
        } catch (e: Throwable) {
            Log.w(TAG, "processLowLight hatası: ${e.message}"); null
        } finally {
            try { processor?.deinitialize() } catch (_: Throwable) {}
        }
    }

    /** JPEG formatındaki Image'ı Bitmap'e dönüştürür. */
    private fun imageToBitmap(image: Image): Bitmap? = try {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Throwable) {
        Log.w(TAG, "imageToBitmap hatası: ${e.message}"); null
    }

    /** Kaynakları serbest bırakır. */
    fun release() {
        try { bgThread.quitSafely() } catch (_: Throwable) {}
    }

    companion object {
        private const val TAG = "SamsungNpuProcessor"
        private const val PROCESS_TIMEOUT_SEC = 10L
    }
}

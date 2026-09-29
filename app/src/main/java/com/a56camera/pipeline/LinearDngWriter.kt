package com.a56camera.pipeline

import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-Bit Computational Linear DNG (Adobe Digital Negative) Yazıcı.
 *
 * Çoklu kare birleştirmeden (Mertens Exposure Fusion) elde edilen 32-bit kayıpsız
 * HDR piksel verisini, 16-bit lineer RAW (0..65535) olarak Adobe DNG 1.4 formatında yazar.
 *
 * Üretilen DNG dosyaları:
 * - Adobe Lightroom Mobile & Desktop
 * - Adobe Photoshop / Camera Raw
 * - Snapseed
 * - Darktable & RawTherapee
 * tarafından resmi Sony IMX906 renk matrisleri ve 16-bit dinamik aralıkla tanınır.
 */
object LinearDngWriter {

    private const val TAG = "LinearDngWriter"

    // Sony IMX906 Donanım Kalibrasyon Matrisleri (Samsung Exynos 1580 Dumpsys'den birebir tescillidir)
    // android.sensor.colorTransform1:
    private val COLOR_TRANSFORM_1 = longArrayOf(
        593, 1024,   78, 1024,  -118, 1024,
        -491, 1024, 1412, 1024,    72, 1024,
        -137, 1024,  417, 1024,   428, 1024
    )

    // android.sensor.forwardMatrix1:
    private val FORWARD_MATRIX_1 = longArrayOf(
        893, 1024, -200, 1024,   294, 1024,
        317, 1024,  697, 1024,    10, 1024,
        -16, 1024, -557, 1024,  1418, 1024
    )

    /**
     * 16-bit BGR Mat'i Adobe Linear DNG (RGB) olarak OutputStream'e yazar.
     *
     * @param bgr16u CV_16UC3 formatında 16-bit birleştirilmiş Mat
     * @param iso Sensör ISO değeri
     * @param exposureNs Pozlama süresi (nanosaniye)
     * @param out Hedef OutputStream
     */
    fun writeDng(
        bgr16u: Mat,
        iso: Int,
        exposureNs: Long,
        out: OutputStream
    ) {
        require(bgr16u.type() == CvType.CV_16UC3) {
            "DNG yazıcı yalnızca CV_16UC3 Mat kabul eder, gelen: ${bgr16u.type()}"
        }

        val startTime = System.currentTimeMillis()
        val width = bgr16u.cols()
        val height = bgr16u.rows()

        // 1. BGR -> RGB Dönüşümü (DNG standardı RGB'dir)
        val rgb16u = Mat()
        Imgproc.cvtColor(bgr16u, rgb16u, Imgproc.COLOR_BGR2RGB)

        val bufferedOut = BufferedOutputStream(out, 128 * 1024)

        try {
            // TIFF Header ve IFD Verilerini Hazırla
            val dataStream = ByteArrayOutputStream()

            fun writeAscii(str: String): Int {
                val offset = dataStream.size()
                val bytes = (str + "\u0000").toByteArray(Charsets.US_ASCII)
                dataStream.write(bytes)
                return offset
            }

            fun writeShortArray(arr: ShortArray): Int {
                val offset = dataStream.size()
                val buf = ByteBuffer.allocate(arr.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                arr.forEach { buf.putShort(it) }
                dataStream.write(buf.array())
                return offset
            }

            fun writeRational(num: Long, den: Long): Int {
                val offset = dataStream.size()
                val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(num.toInt())
                buf.putInt(den.toInt())
                dataStream.write(buf.array())
                return offset
            }

            fun writeSRationalArray(arr: LongArray): Int {
                val offset = dataStream.size()
                val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in arr.indices step 2) {
                    buf.putInt(arr[i].toInt())
                    buf.putInt(arr[i + 1].toInt())
                }
                dataStream.write(buf.array())
                return offset
            }

            // Yardımcı veri bloklarını doldur (Data Stream içinde bağıl adresler)
            val bitsPerSampleRel = writeShortArray(shortArrayOf(16, 16, 16))
            val makeRel = writeAscii("Samsung")
            val modelRel = writeAscii("Galaxy A56 5G")
            val softwareRel = writeAscii("A56 Computational Expert RAW 1.0")
            val cameraModelRel = writeAscii("Samsung Galaxy A56 5G (Sony IMX906)")

            val xResRel = writeRational(72, 1)
            val yResRel = writeRational(72, 1)

            val expSeconds = exposureNs.toDouble() / 1_000_000_000.0
            val expDen = if (expSeconds > 0) (1.0 / expSeconds).toLong().coerceAtLeast(1) else 100
            val expNum = 1L
            val expTimeRel = writeRational(expNum, expDen)
            val fNumberRel = writeRational(18, 10) // f/1.8
            val focalLengthRel = writeRational(554, 100) // 5.54mm

            val forwardMatRel = writeSRationalArray(FORWARD_MATRIX_1)
            val colorMatRel = writeSRationalArray(COLOR_TRANSFORM_1)
            val asShotNeutralRel = writeSRationalArray(longArrayOf(1, 1, 1, 1, 1, 1))

            // IFD0 Girişleri (Toplam 29 Etiket — TIFF Standartlarına göre Etiket ID'sine göre artan sırada!)
            val tagCount = 29
            val ifdSize = 2 + tagCount * 12 + 4 // 354 bayt
            val baseDataOffset = 8 + ifdSize // Header(8) + IFD0(354) = 362

            val ifdBuffer = ByteBuffer.allocate(ifdSize).order(ByteOrder.LITTLE_ENDIAN)
            ifdBuffer.putShort(tagCount.toShort())

            fun putTag(tag: Int, type: Int, count: Int, valueOrRelOffset: Int, isOffset: Boolean) {
                ifdBuffer.putShort(tag.toShort())
                ifdBuffer.putShort(type.toShort())
                ifdBuffer.putInt(count)
                val finalVal = if (isOffset) baseDataOffset + valueOrRelOffset else valueOrRelOffset
                ifdBuffer.putInt(finalVal)
            }

            // 16-bit RGB veri boyutu
            val pixelDataSize = width * height * 3 * 2
            val extraDataBytes = dataStream.size()
            val pixelDataOffset = ((baseDataOffset + extraDataBytes + 15) / 16) * 16 // 16 bayt hizalama

            // Etiketler (Kesin Artan Sırada):
            putTag(254, 4, 1, 0, false)                                      // NewSubfileType (0 = Full res)
            putTag(256, 4, 1, width, false)                                  // ImageWidth
            putTag(257, 4, 1, height, false)                                 // ImageLength
            putTag(258, 3, 3, bitsPerSampleRel, true)                        // BitsPerSample [16, 16, 16]
            putTag(259, 3, 1, 1, false)                                      // Compression (1 = Uncompressed)
            putTag(262, 3, 1, 34892, false)                                  // PhotometricInterpretation (34892 = LinearRaw)
            putTag(271, 2, 8, makeRel, true)                                 // Make ("Samsung")
            putTag(272, 2, 14, modelRel, true)                               // Model ("Galaxy A56 5G")
            putTag(273, 4, 1, pixelDataOffset, false)                        // StripOffsets
            putTag(274, 3, 1, 1, false)                                      // Orientation (1 = Top-Left)
            putTag(277, 3, 1, 3, false)                                      // SamplesPerPixel (3)
            putTag(278, 4, 1, height, false)                                 // RowsPerStrip
            putTag(279, 4, 1, pixelDataSize, false)                          // StripByteCounts
            putTag(282, 5, 1, xResRel, true)                                 // XResolution
            putTag(283, 5, 1, yResRel, true)                                 // YResolution
            putTag(284, 3, 1, 1, false)                                      // PlanarConfiguration (1 = Chunky)
            putTag(296, 3, 1, 2, false)                                      // ResolutionUnit (2 = Inches)
            putTag(305, 2, 33, softwareRel, true)                            // Software
            putTag(33434, 5, 1, expTimeRel, true)                            // ExposureTime
            putTag(33437, 5, 1, fNumberRel, true)                            // FNumber
            putTag(34855, 3, 1, iso.coerceIn(50, 65535), false)              // ISOSpeedRatings
            putTag(37386, 5, 1, focalLengthRel, true)                        // FocalLength

            // DNG Özel Etiketleri
            // DNGVersion (1.4.0.0) -> bayt dizilimi 1, 4, 0, 0 (Little Endian int: 0x00000401)
            val dngVersionPacked = (1 and 0xFF) or ((4 and 0xFF) shl 8)
            putTag(50706, 1, 4, dngVersionPacked, false)                     // DNGVersion
            val dngBackVersionPacked = (1 and 0xFF) or ((1 and 0xFF) shl 8)
            putTag(50707, 1, 4, dngBackVersionPacked, false)                 // DNGBackwardVersion
            putTag(50708, 2, 35, cameraModelRel, true)                       // UniqueCameraModel
            putTag(50710, 10, 9, forwardMatRel, true)                        // ForwardMatrix1
            putTag(50721, 10, 9, colorMatRel, true)                          // ColorMatrix1
            putTag(50728, 5, 3, asShotNeutralRel, true)                      // AsShotNeutral
            putTag(50778, 3, 1, 21, false)                                   // CalibrationIlluminant1 (21 = D65 Daylight)

            ifdBuffer.putInt(0) // Next IFD = 0

            // 1. TIFF Başlığı (8 Bayt)
            val header = byteArrayOf(
                0x49, 0x49, // 'II' Little Endian
                0x2A, 0x00, // Magic 42
                0x08, 0x00, 0x00, 0x00 // IFD0 Offset = 8
            )
            bufferedOut.write(header)

            // 2. IFD0 Bloğu
            bufferedOut.write(ifdBuffer.array())

            // 3. Veri Bloğu (Metinler, Rasyonel Değerler, Matrisler)
            val extraData = dataStream.toByteArray()
            bufferedOut.write(extraData)

            // 4. Hizalama Dolgusu (Pixel Data Offset'e kadar)
            val currentOffset = baseDataOffset + extraData.size
            if (pixelDataOffset > currentOffset) {
                bufferedOut.write(ByteArray(pixelDataOffset - currentOffset))
            }

            // 5. 16-Bit RGB Piksel Akışı (Kayıpsız, Akış Halinde)
            val rowShorts = ShortArray(width * 3)
            val rowByteBuffer = ByteBuffer.allocate(width * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)

            for (row in 0 until height) {
                rgb16u.get(row, 0, rowShorts)
                rowByteBuffer.clear()
                for (s in rowShorts) {
                    rowByteBuffer.putShort(s)
                }
                bufferedOut.write(rowByteBuffer.array())
            }

            bufferedOut.flush()
            val elapsed = System.currentTimeMillis() - startTime
            val sizeMb = (pixelDataOffset + pixelDataSize) / (1024.0 * 1024.0)
            Log.i(TAG, "🌟 [Expert RAW DNG] Başarıyla yazıldı: ${width}x${height} (%.1f MB, %dms)".format(sizeMb, elapsed))

        } finally {
            rgb16u.release()
        }
    }
}

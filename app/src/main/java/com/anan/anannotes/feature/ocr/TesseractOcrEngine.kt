package com.anan.anannotes.feature.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.pow

enum class OcrProgressStage(val percent: Int, val label: String) {
  PREPARING_MODELS(10, "准备离线模型"),
  PREPARING_IMAGE(30, "准备图片"),
  DESKEWING_IMAGE(40, "正在校正倾斜"),
  RECOGNIZING(45, "正在识别文字"),
  ENHANCING_IMAGE(60, "正在优化图像"),
  RESULT_READY(90, "整理识别结果"),
  COMPLETE(100, "识别完成"),
}

fun interface OcrProgressListener {
  suspend fun onStage(stage: OcrProgressStage)
}

class TesseractOcrEngine(private val context: Context) {
  suspend fun recognize(imageFile: File, progress: OcrProgressListener = OcrProgressListener {}): String =
    withContext(Dispatchers.IO) {
      require(imageFile.isFile && imageFile.length() in 1..MAX_INPUT_BYTES) { "图片不存在或超过 OCR 处理上限" }
      progress.onStage(OcrProgressStage.PREPARING_MODELS)
      val dataDirectory = TesseractModelInstaller(context).install()
      progress.onStage(OcrProgressStage.PREPARING_IMAGE)
      val bitmap = decodeBounded(imageFile)
      recognizeBitmap(dataDirectory, bitmap, progress)
    }

  /** Decodes directly from caller-owned memory; private OCR never needs a plaintext cache file. */
  suspend fun recognize(imageBytes: ByteArray, progress: OcrProgressListener = OcrProgressListener {}): String =
    withContext(Dispatchers.IO) {
      require(imageBytes.isNotEmpty() && imageBytes.size.toLong() <= MAX_INPUT_BYTES) { "图片不存在或超过 OCR 处理上限" }
      progress.onStage(OcrProgressStage.PREPARING_MODELS)
      val dataDirectory = TesseractModelInstaller(context).install()
      progress.onStage(OcrProgressStage.PREPARING_IMAGE)
      val bitmap = decodeBounded(imageBytes)
      recognizeBitmap(dataDirectory, bitmap, progress)
    }

  private suspend fun recognizeBitmap(dataDirectory: File, bitmap: Bitmap, progress: OcrProgressListener): String {
    var deskewedBitmap: Bitmap? = null
    try {
      val correctionDegrees = estimateDeskewAngle(bitmap)
      if (correctionDegrees != 0f) {
        progress.onStage(OcrProgressStage.DESKEWING_IMAGE)
        deskewedBitmap = rotateForOcr(bitmap, correctionDegrees)
      }
      progress.onStage(OcrProgressStage.RECOGNIZING)
      nativeEngineMutex.lock()
      val recognized = try {
        recognizeWithCandidates(dataDirectory, bitmap, deskewedBitmap, progress)
      } finally {
        nativeEngineMutex.unlock()
      }
      progress.onStage(OcrProgressStage.RESULT_READY)
      return recognized
    } finally {
      deskewedBitmap?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
      if (!bitmap.isRecycled) bitmap.recycle()
    }
  }

  private fun estimateDeskewAngle(bitmap: Bitmap): Float {
    if (bitmap.width < MIN_DESKEW_IMAGE_EDGE || bitmap.height < MIN_DESKEW_IMAGE_EDGE) return 0f
    val scale = minOf(1f, MAX_DESKEW_SAMPLE_EDGE / maxOf(bitmap.width, bitmap.height).toFloat())
    val sampleWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val sampleHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    val sample = if (sampleWidth == bitmap.width && sampleHeight == bitmap.height) {
      bitmap
    } else {
      Bitmap.createScaledBitmap(bitmap, sampleWidth, sampleHeight, true)
    }
    try {
      val pixels = IntArray(sampleWidth * sampleHeight)
      sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
      for (index in pixels.indices) pixels[index] = luminance(pixels[index])
      return OcrDeskewEstimator.estimate(sampleWidth, sampleHeight, pixels)
    } finally {
      if (sample !== bitmap && !sample.isRecycled) sample.recycle()
    }
  }

  private fun rotateForOcr(bitmap: Bitmap, correctionDegrees: Float): Bitmap = Bitmap.createBitmap(
    bitmap,
    0,
    0,
    bitmap.width,
    bitmap.height,
    Matrix().apply { postRotate(correctionDegrees) },
    true,
  )

  private fun decodeBounded(imageBytes: ByteArray): Bitmap {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "当前 Android 版本无法安全解码 OCR 图片" }
    val source = ImageDecoder.createSource(ByteBuffer.wrap(imageBytes))
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      decoder.isMutableRequired = false
      val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
      val sample = ((longest + MAX_BITMAP_EDGE - 1) / MAX_BITMAP_EDGE).coerceAtLeast(1)
      decoder.setTargetSampleSize(sample)
    }
  }

  private fun decodeBounded(file: File): Bitmap {
    check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { "当前 Android 版本无法安全解码 OCR 图片" }
    val source = ImageDecoder.createSource(file)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      decoder.isMutableRequired = false
      val longest = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
      val sample = ((longest + MAX_BITMAP_EDGE - 1) / MAX_BITMAP_EDGE).coerceAtLeast(1)
      decoder.setTargetSampleSize(sample)
    }
  }

  private suspend fun recognizeWithCandidates(
    dataDirectory: File,
    bitmap: Bitmap,
    deskewedBitmap: Bitmap?,
    progress: OcrProgressListener,
  ): String {
    val original = recognizeCandidate(dataDirectory, bitmap)
    var selectedBitmap = bitmap
    var selectedCandidate = original
    if (deskewedBitmap != null) {
      val corrected = recognizeCandidate(dataDirectory, deskewedBitmap)
      selectedCandidate = OcrCandidateSelector.choose(
        original = original,
        originalImageHeight = bitmap.height,
        enhanced = corrected,
        enhancedImageHeight = deskewedBitmap.height,
      )
      if (selectedCandidate === corrected) selectedBitmap = deskewedBitmap
    }

    val result = if (
      selectedCandidate.meanConfidence < ENHANCED_PASS_CONFIDENCE_THRESHOLD ||
      hasLowContrast(selectedBitmap)
    ) {
      progress.onStage(OcrProgressStage.ENHANCING_IMAGE)
      val enhancedBitmap = createEnhancedCandidate(selectedBitmap)
      try {
        val enhanced = recognizeCandidate(dataDirectory, enhancedBitmap)
        OcrCandidateSelector.choose(selectedCandidate, enhanced, selectedBitmap.height)
      } finally {
        enhancedBitmap.recycle()
      }
    } else {
      selectedCandidate
    }
    return OcrResultFilter.clean(result.lines, selectedBitmap.height)
  }

  /** Samples luminance sparsely so faint gray print is detected without scanning every pixel twice. */
  private fun hasLowContrast(bitmap: Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    val sampleStride = maxOf(1, maxOf(width, height) / CONTRAST_SAMPLE_EDGE)
    val histogram = IntArray(LUMINANCE_BINS)
    val row = IntArray(width)
    var sampleCount = 0
    for (y in 0 until height step sampleStride) {
      bitmap.getPixels(row, 0, width, 0, y, width, 1)
      for (x in 0 until width step sampleStride) {
        histogram[luminance(row[x])]++
        sampleCount++
      }
    }
    if (sampleCount == 0) return false
    val lower = percentileLuminance(histogram, (sampleCount * CONTRAST_LOWER_PERCENTILE).toInt())
    val upper = percentileLuminance(histogram, (sampleCount * CONTRAST_UPPER_PERCENTILE).toInt())
    return upper - lower <= LOW_CONTRAST_RANGE
  }

  private fun percentileLuminance(histogram: IntArray, targetCount: Int): Int {
    var seen = 0
    for (luminance in histogram.indices) {
      seen += histogram[luminance]
      if (seen > targetCount) return luminance
    }
    return histogram.lastIndex
  }

  private fun recognizeCandidate(dataDirectory: File, bitmap: Bitmap): OcrTextCandidate {
    val api = TessBaseAPI()
    try {
      check(api.init(dataDirectory.absolutePath, LANGUAGES)) { "离线 OCR 模型初始化失败" }
      api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
      api.setImage(bitmap)
      val meanConfidence = api.meanConfidence().toFloat()
      val lines = readTextLines(api, bitmap.height, meanConfidence)
      return OcrTextCandidate(lines, meanConfidence)
    } finally {
      api.recycle()
    }
  }

  private fun readTextLines(api: TessBaseAPI, imageHeight: Int, meanConfidence: Float): List<OcrLineEvidence> {
    val iterator = api.resultIterator ?: return fallbackTextLines(api, imageHeight, meanConfidence)
    val lines = mutableListOf<OcrLineEvidence>()
    try {
      val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
      iterator.begin()
      do {
        val text = iterator.getUTF8Text(level).orEmpty()
        val bounds = iterator.getBoundingRect(level)
        if (text.isNotBlank()) {
          lines += OcrLineEvidence(
            text = text,
            confidence = iterator.confidence(level),
            top = bounds?.top ?: 0,
            bottom = bounds?.bottom ?: imageHeight,
          )
        }
      } while (iterator.next(level))
    } finally {
      iterator.delete()
    }
    return lines.ifEmpty { fallbackTextLines(api, imageHeight, meanConfidence) }
  }

  private fun fallbackTextLines(api: TessBaseAPI, imageHeight: Int, confidence: Float): List<OcrLineEvidence> =
    api.getUTF8Text().orEmpty().lineSequence()
      .filter(String::isNotBlank)
      .map { line -> OcrLineEvidence(line, confidence, imageHeight / 2, imageHeight / 2 + 1) }
      .toList()

  /** Creates one bounded grayscale/gamma candidate for faint print; callers own and recycle it. */
  private fun createEnhancedCandidate(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val row = IntArray(width)
    try {
      for (y in 0 until height) {
        source.getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
          val color = row[x]
          val normalized = luminance(color) / 255.0
          val adjusted = (255.0 * normalized.pow(ENHANCEMENT_GAMMA)).roundToInt().coerceIn(0, 255)
          row[x] = Color.rgb(adjusted, adjusted, adjusted)
        }
        enhanced.setPixels(row, 0, width, 0, y, width, 1)
      }
      return enhanced
    } catch (failure: Throwable) {
      enhanced.recycle()
      throw failure
    }
  }

  private fun luminance(color: Int): Int {
    val alpha = Color.alpha(color)
    val value = (77 * Color.red(color) + 150 * Color.green(color) + 29 * Color.blue(color)) shr 8
    return (value * alpha + 255 * (255 - alpha)) / 255
  }

  private companion object {
    const val LANGUAGES = "chi_sim+eng"
    const val MAX_BITMAP_EDGE = 2400
    const val MAX_INPUT_BYTES = 250L * 1024L * 1024L
    const val ENHANCED_PASS_CONFIDENCE_THRESHOLD = 72f
    const val ENHANCEMENT_GAMMA = 1.35
    const val CONTRAST_SAMPLE_EDGE = 160
    const val CONTRAST_LOWER_PERCENTILE = 0.01
    const val CONTRAST_UPPER_PERCENTILE = 0.99
    const val LOW_CONTRAST_RANGE = 96
    const val MIN_DESKEW_IMAGE_EDGE = 64
    const val MAX_DESKEW_SAMPLE_EDGE = 640
    const val LUMINANCE_BINS = 256
    val nativeEngineMutex = Mutex()
  }
}

/** Copies only verified bundled models into app-private storage; OCR never downloads weights. */
class TesseractModelInstaller(
  private val context: Context,
  private val assetDirectory: String = "ocr/tessdata",
  private val languageChecksums: Map<String, String> = MODEL_SHA256,
) {
  suspend fun install(): File = withContext(Dispatchers.IO) {
    val tessdata = File(context.filesDir, "ocr/tessdata")
    if (!tessdata.isDirectory && !tessdata.mkdirs()) throw IOException("无法创建离线 OCR 模型目录")
    languageChecksums.forEach { (language, checksum) ->
      require(language.matches(Regex("[a-z_]{2,16}"))) { "OCR 语言标识无效" }
      val target = File(tessdata, "$language.traineddata")
      if (target.isFile && target.length() in 1..MAX_MODEL_BYTES && sha256(target) == checksum) return@forEach
      val temporary = File(tessdata, ".$language.${System.nanoTime()}.tmp")
      try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open("$assetDirectory/$language.traineddata").use { input ->
          FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              output.write(buffer, 0, count)
              digest.update(buffer, 0, count)
              if (temporary.length() > MAX_MODEL_BYTES) throw IOException("OCR 模型超出安全上限")
            }
            output.fd.sync()
          }
        }
        val actual = digest.digest().toHex()
        if (!actual.equals(checksum, ignoreCase = true)) throw IOException("离线 OCR 模型校验失败")
        try {
          java.nio.file.Files.move(
            temporary.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
          java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
      } finally {
        temporary.delete()
      }
    }
    tessdata.parentFile ?: throw IOException("OCR 模型目录无效")
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().toHex()
  }

  private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }

  companion object {
    private const val MAX_MODEL_BYTES = 64L * 1024L * 1024L
    val MODEL_SHA256 = mapOf(
      "chi_sim" to "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730",
      "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2",
    )
  }
}

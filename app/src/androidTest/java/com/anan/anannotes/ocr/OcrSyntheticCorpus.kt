package com.anan.anannotes.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal data class OcrSyntheticSample(
  val id: String,
  val bitmap: Bitmap,
  val expectedText: String,
  val expectedLineCount: Int,
  val minimumLineCount: Int = (expectedLineCount - 1).coerceAtLeast(1),
  val forbiddenTokens: List<String> = emptyList(),
  val maxCharacterErrorRate: Double,
)

/** Deterministic OCR fixtures; these are regression examples, not real-photo data.
 *
 * Sample metadata lives here in one place, but pixels load from calibration PNGs
 * bundled in the test APK (assets/ocr/corpus). The assets were rendered once on the
 * calibration device (AnAn_API35_Pixel6) via [OcrCorpusAssetExportTest]: runtime
 * Canvas text rasterization differs per Android release, which used to make the CER
 * budgets API-dependent. Identical asset pixels restore one budget for every API.
 * Regenerate the assets whenever the layouts in [CalibrationRenderer] change. */
internal object OcrSyntheticCorpus {
  private const val ASSET_DIRECTORY = "ocr/corpus"

  internal data class SampleSpec(
    val id: String,
    val assetFileName: String,
    val expectedText: String,
    val expectedLineCount: Int,
    val minimumLineCount: Int,
    val forbiddenTokens: List<String>,
    val maxCharacterErrorRate: Double,
  )

  private val mixedLines = listOf(
    "安安简记 离线识别测试",
    "保留原始换行，文字可编辑。",
    "Meeting Notes 2026 OCR 600 DPI",
    "Order ID: A7B9-2046",
  )
  private val sparseLines = listOf(
    "短编号 A7",
    "Meeting Notes 2026",
    "订单 AB-2046",
    "待办：周五复盘",
  )
  private val twoColumnLeft = listOf("学习资料", "课程编号：A7", "英语复习 2026", "整理错题与笔记")
  private val twoColumnRight = listOf("工作记录", "项目编号：B9", "Meeting 09:30", "周五提交报告")

  fun create(): List<OcrSyntheticSample> = sampleSpecs().map { spec ->
    OcrSyntheticSample(
      id = spec.id,
      bitmap = loadBitmap(spec.assetFileName),
      expectedText = spec.expectedText,
      expectedLineCount = spec.expectedLineCount,
      minimumLineCount = spec.minimumLineCount,
      forbiddenTokens = spec.forbiddenTokens,
      maxCharacterErrorRate = spec.maxCharacterErrorRate,
    )
  }

  fun sampleSpecs(): List<SampleSpec> = listOf(
    SampleSpec(
      id = "mixed-body",
      assetFileName = "mixed-body.png",
      expectedText = mixedLines.joinToString("\n"),
      expectedLineCount = mixedLines.size,
      minimumLineCount = mixedLines.size - 1,
      forbiddenTokens = emptyList(),
      maxCharacterErrorRate = 0.08,
    ),
    SampleSpec(
      id = "small-low-contrast",
      assetFileName = "small-low-contrast.png",
      expectedText = mixedLines.joinToString("\n"),
      expectedLineCount = mixedLines.size,
      minimumLineCount = mixedLines.size - 1,
      forbiddenTokens = emptyList(),
      maxCharacterErrorRate = 0.12,
    ),
    SampleSpec(
      id = "sparse-status-noise",
      assetFileName = "sparse-status-noise.png",
      expectedText = sparseLines.joinToString("\n"),
      expectedLineCount = sparseLines.size,
      minimumLineCount = sparseLines.size - 1,
      forbiddenTokens = listOf("10:08", "5G", "88%"),
      maxCharacterErrorRate = 0.12,
    ),
    SampleSpec(
      id = "two-columns",
      assetFileName = "two-columns.png",
      expectedText = (listOf("工作与学习") + twoColumnLeft + twoColumnRight).joinToString("\n"),
      expectedLineCount = 9,
      minimumLineCount = 7,
      forbiddenTokens = emptyList(),
      maxCharacterErrorRate = 0.20,
    ),
  )

  private fun loadBitmap(fileName: String): Bitmap {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    return assets.open("$ASSET_DIRECTORY/$fileName").use { input ->
      BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
      })
    } ?: error("校准语料资产缺失: $fileName（用 OcrCorpusAssetExportTest 重新生成）")
  }

  fun rotate(source: Bitmap, degrees: Float): Bitmap {
    val radians = Math.toRadians(degrees.toDouble())
    val width = ceil(kotlin.math.abs(source.width * cos(radians)) + kotlin.math.abs(source.height * sin(radians)))
      .toInt()
    val height = ceil(kotlin.math.abs(source.height * cos(radians)) + kotlin.math.abs(source.width * sin(radians)))
      .toInt()
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { rotated ->
      val canvas = Canvas(rotated)
      canvas.drawColor(source.getPixel(0, 0))
      canvas.translate(width / 2f, height / 2f)
      canvas.rotate(degrees)
      canvas.drawBitmap(source, -source.width / 2f, -source.height / 2f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }
  }

  /** Renders the calibration pixels on the CURRENT device. Only used by
   * [OcrCorpusAssetExportTest]; the corpus itself consumes the bundled assets. */
  internal object CalibrationRenderer {
    fun render(spec: SampleSpec): Bitmap = when (spec.id) {
      "mixed-body" -> renderMixedBody()
      "small-low-contrast" -> renderSmallLowContrastBody()
      "sparse-status-noise" -> renderSparseScreenshot()
      "two-columns" -> renderTwoColumns()
      else -> error("未知语料样本 ${spec.id}")
    }

    /** Matches TesseractOcrEngineIntegrationTest's tiny 19px corpus, JPEG q24. */
    fun tinyCompressedJpeg(): ByteArray {
      val bitmap = renderTinyLowQualityBody()
      return ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 24, output))
        bitmap.recycle()
        output.toByteArray()
      }
    }

    private fun renderMixedBody(): Bitmap {
      val bitmap = Bitmap.createBitmap(1700, 760, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
      mixedLines.forEachIndexed { index, line ->
        canvas.drawText(line, 70f, 130f + index * 160f, bodyPaint(68f))
      }
      return bitmap
    }

    private fun renderSparseScreenshot(): Bitmap {
      val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
      canvas.drawText("10:08   5G   88%", 18f, 32f, bodyPaint(28f, Color.DKGRAY))
      val positions = listOf(70f to 210f, 160f to 390f, 80f to 580f, 370f to 760f)
      sparseLines.zip(positions).forEach { (line, position) ->
        canvas.drawText(line, position.first, position.second, bodyPaint(54f))
      }
      return bitmap
    }

    private fun renderSmallLowContrastBody(): Bitmap {
      val bitmap = Bitmap.createBitmap(720, 322, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap).apply { drawColor(Color.rgb(246, 245, 241)) }
      mixedLines.forEachIndexed { index, line ->
        canvas.drawText(line, 30f, 55f + index * 66f, bodyPaint(29f, Color.rgb(186, 184, 180)))
      }
      return bitmap
    }

    private fun renderTwoColumns(): Bitmap {
      val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
      canvas.drawText("工作与学习", 70f, 100f, bodyPaint(58f))
      twoColumnLeft.forEachIndexed { index, line -> canvas.drawText(line, 70f, 250f + index * 145f, bodyPaint(48f)) }
      twoColumnRight.forEachIndexed { index, line -> canvas.drawText(line, 850f, 250f + index * 145f, bodyPaint(48f)) }
      return bitmap
    }

    private fun renderTinyLowQualityBody(): Bitmap {
      val bitmap = Bitmap.createBitmap(480, 215, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.drawColor(Color.rgb(246, 245, 241))
      val scale = 480 / 1700f
      val paint = bodyPaint(19f, Color.rgb(180, 178, 174))
      mixedLines.forEachIndexed { index, line ->
        canvas.drawText(line, 70f * scale, (130f + index * 160f) * scale, paint)
      }
      return bitmap
    }

    private fun bodyPaint(textSize: Float, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      this.color = color
      this.textSize = textSize
      typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
  }
}

internal fun characterErrorRate(expected: String, actual: String): Double {
  val left = expected.filterNot(Char::isWhitespace)
  val right = actual.filterNot(Char::isWhitespace)
  if (left.isEmpty()) return if (right.isEmpty()) 0.0 else 1.0
  var previous = IntArray(right.length + 1) { it }
  for (leftIndex in left.indices) {
    val current = IntArray(right.length + 1)
    current[0] = leftIndex + 1
    for (rightIndex in right.indices) {
      val substitution = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
      current[rightIndex + 1] = minOf(previous[rightIndex + 1] + 1, current[rightIndex] + 1, substitution)
    }
    previous = current
  }
  return previous[right.length].toDouble() / left.length
}

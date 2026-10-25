package com.anan.anannotes.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/** Deterministic, locally rendered OCR fixtures; these are regression examples, not real-photo data. */
internal object OcrSyntheticCorpus {
  fun create(): List<OcrSyntheticSample> = listOf(
    mixedBody(),
    smallLowContrastBody(),
    sparseScreenshot(),
    twoColumns(),
  )

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

  private fun mixedBody(): OcrSyntheticSample {
    val bitmap = Bitmap.createBitmap(1700, 760, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
    val lines = listOf(
      "安安简记 离线识别测试",
      "保留原始换行，文字可编辑。",
      "Meeting Notes 2026 OCR 600 DPI",
      "Order ID: A7B9-2046",
    )
    lines.forEachIndexed { index, line ->
      canvas.drawText(line, 70f, 130f + index * 160f, bodyPaint(68f))
    }
    return OcrSyntheticSample(
      id = "mixed-body",
      bitmap = bitmap,
      expectedText = lines.joinToString("\n"),
      expectedLineCount = lines.size,
      maxCharacterErrorRate = 0.08,
    )
  }

  private fun sparseScreenshot(): OcrSyntheticSample {
    val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
    canvas.drawText("10:08   5G   88%", 18f, 32f, bodyPaint(28f, Color.DKGRAY))
    val lines = listOf(
      "短编号 A7",
      "Meeting Notes 2026",
      "订单 AB-2046",
      "待办：周五复盘",
    )
    val positions = listOf(70f to 210f, 160f to 390f, 80f to 580f, 370f to 760f)
    lines.zip(positions).forEach { (line, position) ->
      canvas.drawText(line, position.first, position.second, bodyPaint(54f))
    }
    return OcrSyntheticSample(
      id = "sparse-status-noise",
      bitmap = bitmap,
      expectedText = lines.joinToString("\n"),
      expectedLineCount = lines.size,
      forbiddenTokens = listOf("10:08", "5G", "88%"),
      maxCharacterErrorRate = 0.12,
    )
  }

  private fun smallLowContrastBody(): OcrSyntheticSample {
    val bitmap = Bitmap.createBitmap(720, 322, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { drawColor(Color.rgb(246, 245, 241)) }
    val lines = listOf(
      "安安简记 离线识别测试",
      "保留原始换行，文字可编辑。",
      "Meeting Notes 2026 OCR 600 DPI",
      "Order ID: A7B9-2046",
    )
    lines.forEachIndexed { index, line ->
      canvas.drawText(
        line,
        30f,
        55f + index * 66f,
        bodyPaint(29f, Color.rgb(186, 184, 180)),
      )
    }
    return OcrSyntheticSample(
      id = "small-low-contrast",
      bitmap = bitmap,
      expectedText = lines.joinToString("\n"),
      expectedLineCount = lines.size,
      maxCharacterErrorRate = 0.12,
    )
  }

  private fun twoColumns(): OcrSyntheticSample {
    val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
    canvas.drawText("工作与学习", 70f, 100f, bodyPaint(58f))
    val left = listOf("学习资料", "课程编号：A7", "英语复习 2026", "整理错题与笔记")
    val right = listOf("工作记录", "项目编号：B9", "Meeting 09:30", "周五提交报告")
    left.forEachIndexed { index, line -> canvas.drawText(line, 70f, 250f + index * 145f, bodyPaint(48f)) }
    right.forEachIndexed { index, line -> canvas.drawText(line, 850f, 250f + index * 145f, bodyPaint(48f)) }
    val lines = listOf("工作与学习") + left + right
    return OcrSyntheticSample(
      id = "two-columns",
      bitmap = bitmap,
      expectedText = lines.joinToString("\n"),
      expectedLineCount = lines.size,
      minimumLineCount = 7,
      maxCharacterErrorRate = 0.20,
    )
  }

  private fun bodyPaint(textSize: Float, color: Int = Color.BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.textSize = textSize
    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
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

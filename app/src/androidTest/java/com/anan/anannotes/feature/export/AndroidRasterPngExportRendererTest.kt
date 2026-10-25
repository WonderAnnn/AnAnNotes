package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.TodoModel
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRasterPngExportRendererTest {
  @Test fun layoutKeepsInlineFormatHeadingQuoteAndEveryIndependentTodo() {
    val heading = BlockModel("heading", "note", 0, BlockType.TEXT, text = "分级标题",
      formatting = BlockFormattingState.Supported(BlockFormatting(presentation = TextBlockPresentation.HEADING)))
    val body = BlockModel("body", "note", 1, BlockType.TEXT, text = "加粗的内容",
      formatting = BlockFormattingState.Supported(BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD)))))
    val quote = BlockModel("quote", "note", 2, BlockType.QUOTE, text = "引用内容")
    val tasks = (1..3).map { BlockModel("task-$it", "note", it + 2, BlockType.TODO, text = "待办 $it") }
    val source = snapshot("moonlit-sky", "").let { it.copy(document = it.document.copy(
      note = it.document.note.copy(fontSizeSpOverride = 24f, lineSpacingMultiplierOverride = 1.8f),
      blocks = listOf(heading, body, quote) + tasks,
      todos = tasks.mapIndexed { index, block -> TodoModel(block.id, "note", block.id, checkNotNull(block.text), index == 1, 1L) })) }
    val plan = RasterPngLayout.measure(source, ExportFormat.PNG_LONG_IMAGE, { error("无图片") }, {})
    val commands = plan.pages.single().commands.filterIsInstance<RasterCommand.Text>()
    val bodyCommand = commands.single { it.layout.text.toString() == "加粗的内容" }
    assertEquals(72f, bodyCommand.layout.paint.textSize, 0.01f)
    assertTrue(commands.single { it.layout.text.toString() == "分级标题" }.layout.paint.textSize > 72f)
    val spans = (bodyCommand.layout.text as Spanned).getSpans(0, 2, StyleSpan::class.java)
    assertTrue(spans.any { it.style == Typeface.BOLD })
    assertTrue(commands.single { it.layout.text.toString() == "引用内容" }.quote)
    assertEquals(listOf(false, true, false), commands.filter { it.checkbox != null }.map { it.checkbox })
  }
  @Test fun a4PngHasTrueSixHundredDpiDimensionsDensityAndRenderedContent() = runBlocking {
    val result = render(snapshot("moonlit-sky", text = "中文高清正文\n下一行保持间距"), ExportFormat.PNG_PAGED_600_DPI)
    assertEquals(listOf(1), result.map { it.first })
    val chunks = chunks(result.single().second)
    DataInputStream(ByteArrayInputStream(chunks.getValue("IHDR"))).use {
      assertEquals(4961, it.readInt()); assertEquals(7016, it.readInt())
    }
    DataInputStream(ByteArrayInputStream(chunks.getValue("pHYs"))).use {
      assertEquals(23622, it.readInt()); assertEquals(23622, it.readInt()); assertEquals(1, it.readUnsignedByte())
    }
    val bitmap = decode(result.single().second, sample = 8)
    try {
      assertTrue(
        "A4 PNG 应使用月下星河高清底图，而不是纯色背景",
        bitmap.getPixel(0, 0) != NoteSkinCatalog.byId("moonlit-sky").background.toArgb(),
      )
      assertTrue("不是空白画布", hasNonBackground(bitmap))
    } finally { bitmap.recycle() }
  }

  @Test fun attributionIsAVisualOnlyFooterForA4AndLongPng() {
    val source = snapshot("minimal-white", "只导出视觉署名")
    listOf(ExportFormat.PNG_PAGED_600_DPI, ExportFormat.PNG_LONG_IMAGE).forEach { format ->
      val withoutAttribution = RasterPngLayout.measure(source, format, { error("无图片") }, {})
      val withAttribution = RasterPngLayout.measure(source.copy(includeAttribution = true), format, { error("无图片") }, {})
      assertTrue(withAttribution.pages.single().commands.filterIsInstance<RasterCommand.Text>()
        .any { it.layout.text.toString() == ExportLayout.ATTRIBUTION_TEXT })
      assertTrue(withoutAttribution.pages.single().commands.filterIsInstance<RasterCommand.Text>()
        .none { it.layout.text.toString() == ExportLayout.ATTRIBUTION_TEXT })
      assertEquals(
        ExportLayout.toPlainText(source.document),
        ExportLayout.toPlainText(source.copy(includeAttribution = true).document),
      )
    }
  }

  @Test fun a4AndLongPngRenderSakuraPatternWithoutWashingOutText() = runBlocking {
    listOf(ExportFormat.PNG_PAGED_600_DPI to 4, ExportFormat.PNG_LONG_IMAGE to 1).forEach { (format, sample) ->
      val result = render(snapshot("sakura-petal-paper", "樱花纸笺正文应清晰可读"), format)
      val bitmap = decode(result.single().second, sample)
      try {
        assertTrue("$format 应绘制低对比度樱花纹样", hasSoftSakuraPixels(bitmap))
        assertTrue("$format 正文应保持深色对比", hasDarkTextPixels(bitmap))
      } finally {
        bitmap.recycle()
      }
    }
  }

  @Test fun allSevenNewSkinsRenderOnA4AndTallLongPngWithoutLosingTextContrast() = runBlocking {
    val skinIds = listOf(
      "bamboo-shadow", "misty-tide", "osmanthus-note", "moonlit-sky", "wisteria-cloud",
      "camellia-letter", "southern-ginkgo",
    )
    skinIds.forEach { skinId ->
      val skin = NoteSkinCatalog.byId(skinId)
      listOf(ExportFormat.PNG_PAGED_600_DPI to 8, ExportFormat.PNG_LONG_IMAGE to 1).forEach { (format, sample) ->
        val text = (1..32).joinToString("\n") { "第 $it 行，${skin.name} 皮肤导出适配" }
        val result = render(snapshot(skinId, text), format)
        val bitmap = decode(result.first().second, sample)
        try {
          assertTrue("$skinId / $format 应在正文外侧保留可见纹样", hasPatternInOuterMargin(bitmap, skin.background.toArgb()))
          assertTrue("$skinId / $format 正文与纸面应保持清晰对比", hasHighContrastPixel(bitmap, skin.background.toArgb()))
          if (format == ExportFormat.PNG_LONG_IMAGE) assertTrue("长图应超出朋友圈竖图高度", bitmap.height > 1_350)
        } finally {
          bitmap.recycle()
        }
      }
    }
  }

  @Test fun a4PaginatedContentProducesEveryOrderedPage() = runBlocking {
    val result = render(snapshot("minimal-white", (1..78).joinToString("\n") { "第 $it 行，分页不丢失" }), ExportFormat.PNG_PAGED_600_DPI)
    assertTrue(result.size > 1)
    assertEquals((1..result.size).toList(), result.map { it.first })
    result.forEach { (_, bytes) ->
      val bitmap = decode(bytes, 8)
      try { assertTrue(hasNonBackground(bitmap)) } finally { bitmap.recycle() }
    }
  }

  @Test fun longImageKeepsOneFileBeyondSocialPageHeightInLightAndDarkSkins() = runBlocking {
    listOf("minimal-white", "moonlit-sky").forEach { skin ->
      val result = render(snapshot(skin, (1..35).joinToString("\n") { "长图第 $it 行" }), ExportFormat.PNG_LONG_IMAGE)
      assertEquals(1, result.size)
      val bitmap = decode(result.single().second, 1)
      try {
        assertEquals(1080, bitmap.width)
        assertTrue(bitmap.height > 1350)
        assertTrue(hasNonBackground(bitmap))
      } finally { bitmap.recycle() }
      assertTrue("长图不伪造物理 DPI", "pHYs" !in chunks(result.single().second))
    }
  }

  @Test fun overlongImageFailsBeforeAnyOutputIsCreated() = runBlocking {
    var outputs = 0
    val error = try {
      AndroidRasterPngExportRenderer(exportTestResources()).render(
        snapshot("minimal-white", "超过安全高度\n".repeat(1500)),
        ExportFormat.PNG_LONG_IMAGE,
        openImage = { error("无图片") },
      ) { _, _ -> outputs++; error("不能创建文件") }
      error("应拒绝超限")
    } catch (failure: ExportOperationException) { failure }
    assertEquals(ExportErrorCode.WRITE_FAILED, error.code)
    assertTrue(error.message.contains("分页"))
    assertEquals(0, outputs)
  }

  @Test fun bandDrawingPreservesImageRatioAndPixelsAcrossBandBoundaries() = runBlocking {
    val original = Bitmap.createBitmap(100, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
    val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    original.recycle()
    val image = BlockModel("image", "note", 0, BlockType.IMAGE, assetPath = "notes/assets/image.png",
      widthPercent = 50f, aspectRatio = 1f, alignment = BlockAlignment.CENTER)
    val document = snapshot("minimal-white", "").let { it.copy(document = it.document.copy(blocks = listOf(image))) }
    val result = render(document, ExportFormat.PNG_LONG_IMAGE, bytes)
    val bitmap = decode(result.single().second, 1)
    try {
      var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
      for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        if (bitmap.getPixel(x, y) == Color.MAGENTA) {
          left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
      }
      assertTrue(right > left && bottom > top)
      assertTrue(kotlin.math.abs((right - left + 1).toFloat() / (bottom - top + 1) - 2f) < 0.03f)
      assertTrue(kotlin.math.abs((left + right) / 2f - 540f) < 1f)
      for (y in top..bottom) assertEquals("画带接缝不能丢失原图", Color.MAGENTA, bitmap.getPixel(540, y))
    } finally { bitmap.recycle() }
  }

  @Test fun a4PngRendererDoesNotDiscardFineOriginalImageDetailBeforeRasterizing() {
    // This source fits the A4 render width, but the old 9 MP cap rounded its decode sample
    // from 1 to 2 and blurred all one-pixel vertical detail before drawing it at 1:1.
    val source = Bitmap.createBitmap(4_000, 3_000, Bitmap.Config.ARGB_8888)
    val stripeRow = IntArray(4_000) { x -> if (x % 2 == 0) Color.BLACK else Color.WHITE }
    repeat(source.height) { y -> source.setPixels(stripeRow, 0, source.width, 0, y, source.width, 1) }
    val originalBytes = ByteArrayOutputStream().also {
      assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it))
    }.toByteArray()
    source.recycle()

    val image = BlockModel("detail-image", "note", 0, BlockType.IMAGE,
      assetPath = "notes/assets/detail.png", widthPercent = 100f, aspectRatio = 4f / 3f)
    val page = RasterPage(
      listOf(RasterCommand.Image(image, RectF(0f, 0f, 4_000f, 3_000f))),
      height = 3_200,
    )
    val plan = RasterPngPlan(
      pages = listOf(page), width = 4_000, scale = 1f,
      skin = NoteSkinCatalog.byId("minimal-white"), dpi = null,
    )
    val png = ByteArrayOutputStream()
    AndroidRasterPngExportRenderer(exportTestResources()).renderPage(
      plan, page, { ByteArrayInputStream(originalBytes) }, png, checkActive = {},
    )
    val rendered = BitmapFactory.decodeByteArray(png.toByteArray(), 0, png.size())
      ?: error("渲染出的 PNG 应能解码")
    try {
      val row = IntArray(2_000)
      rendered.getPixels(row, 0, row.size, 1_000, 1_500, row.size, 1)
      val transitions = (1 until row.size).count { index -> row[index] != row[index - 1] }
      assertTrue("600 DPI 导出不应在写 PNG 前把原图细线降采样成一片灰", transitions > 1_900)
    } finally {
      rendered.recycle()
    }
  }

  private suspend fun render(snapshot: ExportSnapshot, format: ExportFormat, image: ByteArray = byteArrayOf()): List<Pair<Int, ByteArray>> {
    val pages = mutableListOf<Pair<Int, ByteArray>>()
    AndroidRasterPngExportRenderer(exportTestResources()).render(snapshot, format, { ByteArrayInputStream(image) }) { number, content ->
      val output = ByteArrayOutputStream()
      content(output)
      pages += number to output.toByteArray()
      ExportFileReceipt("page-$number.png", "content://test/$number")
    }
    return pages
  }

  private fun snapshot(skin: String, text: String) = ExportSnapshot(
    NoteDocument(NoteModel("note", "folder", "导出标题", skinId = skin, createdAtMillis = 1, updatedAtMillis = 2),
      listOf(BlockModel("text", "note", 0, BlockType.TEXT, text = text)), emptyList()), "测试",
  )

  private fun decode(bytes: ByteArray, sample: Int) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
    BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("PNG 不能解码")

  private fun hasNonBackground(bitmap: Bitmap): Boolean {
    val background = bitmap.getPixel(0, 0)
    for (y in bitmap.height / 30 until bitmap.height * 3 / 4) for (x in bitmap.width / 30 until bitmap.width * 9 / 10) {
      if (bitmap.getPixel(x, y) != background) return true
    }
    return false
  }

  private fun hasSoftSakuraPixels(bitmap: Bitmap): Boolean {
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      val pixel = bitmap.getPixel(x, y)
      val red = Color.red(pixel)
      val green = Color.green(pixel)
      val blue = Color.blue(pixel)
      if (red - green >= 12 && red - blue >= 7 && green < 246) return true
    }
    return false
  }

  private fun hasDarkTextPixels(bitmap: Bitmap): Boolean {
    for (y in bitmap.height / 25 until bitmap.height * 3 / 4) for (x in bitmap.width / 30 until bitmap.width * 9 / 10) {
      val pixel = bitmap.getPixel(x, y)
      if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) return true
    }
    return false
  }

  private fun hasPatternInOuterMargin(bitmap: Bitmap, background: Int): Boolean {
    val margin = (bitmap.width * 0.065f).toInt().coerceAtLeast(1)
    for (y in 0 until bitmap.height) for (x in 0 until margin) {
      if (maxChannelDifference(bitmap.getPixel(x, y), background) > 2) return true
    }
    return false
  }

  private fun hasHighContrastPixel(bitmap: Bitmap, background: Int): Boolean {
    val left = bitmap.width / 6
    val right = bitmap.width * 5 / 6
    val bottom = bitmap.height * 3 / 4
    for (y in 0 until bottom) for (x in left until right) {
      if (maxChannelDifference(bitmap.getPixel(x, y), background) > 60) return true
    }
    return false
  }

  private fun maxChannelDifference(first: Int, second: Int): Int = (0..2).maxOf { channel ->
    kotlin.math.abs(Color.red(first).let { if (channel == 0) it else if (channel == 1) Color.green(first) else Color.blue(first) } -
      Color.red(second).let { if (channel == 0) it else if (channel == 1) Color.green(second) else Color.blue(second) })
  }

  private fun chunks(bytes: ByteArray): Map<String, ByteArray> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    input.skipBytes(8)
    buildMap {
      while (input.available() > 0) {
        val length = input.readInt()
        val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        val content = ByteArray(length).also(input::readFully)
        input.readInt()
        if (type != "IDAT") put(type, content)
      }
    }
  }
}

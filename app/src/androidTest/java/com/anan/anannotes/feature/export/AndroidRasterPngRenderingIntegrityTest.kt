package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.TodoModel
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import com.anan.anannotes.ui.theme.NoteSkinBackgroundPainter
import com.anan.anannotes.ui.theme.NoteSkinDecoration
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidRasterPngRenderingIntegrityTest {
  private data class TextFixture(val id: String, val text: String, val type: BlockType, val pixelTop: Float, val completed: Boolean? = null)
  private val fixtures = listOf(
    TextFixture("bold", "中文加粗跨带", BlockType.TEXT, 116f),
    TextFixture("quote", "引用中文跨带", BlockType.QUOTE, 244f),
    TextFixture("done", "完成待办跨带", BlockType.TODO, 372f, true),
    TextFixture("open", "未完待办跨带", BlockType.TODO, 500f, false),
  )

  @Test
  fun bandedChineseBoldQuoteAndCompletedTodoMatchIndependentWholeCanvas() {
    for (skinId in listOf("minimal-white", "moonlit-sky")) {
      val source = formattedSnapshot(skinId)
      val measured = RasterPngLayout.measure(source, ExportFormat.PNG_LONG_IMAGE, { error("no image") }, {})
      val commands = fixtures.map { fixture ->
        val command = measured.pages.single().commands.filterIsInstance<RasterCommand.Text>()
          .single { it.layout.text.toString() == fixture.text }
        val top = fixture.pixelTop / 0.5f
        command.copy(top = top, bottom = top + command.bottom - command.top)
      }
      val page = RasterPage(commands, 576)
      val plan = measured.copy(
        pages = listOf(page),
        width = 540,
        scale = 0.5f,
        dpi = null,
        skin = measured.skin.copy(decoration = NoteSkinDecoration.NONE),
      )
      val actual = renderSmallPage(plan, page)
      val expected = independentFormattedReference(plan, bold = true, strike = true)
      val plainBold = independentFormattedReference(plan, bold = false, strike = true)
      val plainStrike = independentFormattedReference(plan, bold = true, strike = false)
      val checkboxEdges = independentFormattedReference(plan, bold = true, strike = true, drawText = false)
      try {
        assertTrue("reference must distinguish actual bold glyph pixels", differingPixels(expected, plainBold) > 0)
        assertTrue("reference must distinguish completed-todo strikethrough pixels", differingPixels(expected, plainStrike) > 0)
        commands.forEach { command ->
          assertSameRegion("exact text including strike: ${command.layout.text}, $skinId", expected, actual,
            command.x.toInt() / 2, (command.top / 2).toInt(), (command.width / 2).toInt(), ceil((command.bottom - command.top) / 2).toInt())
          if (command.quote) assertSameRegion("exact quote marker, $skinId", expected, actual,
            ((command.x - 11f) / 2).toInt(), (command.top / 2).toInt(), 3, ceil((command.bottom - command.top) / 2).toInt())
        }
        assertFormattedPixels(expected, actual, checkboxEdges, plan)
      } finally {
        actual.recycle(); expected.recycle(); plainBold.recycle(); plainStrike.recycle(); checkboxEdges.recycle()
      }
    }
  }

  @Test
  fun everySourceLineHasOneContinuousPageIntervalAndMatchingRenderedPixels() {
    val body = (0 until 180).joinToString("\n") { "来源行${it.toString().padStart(3, '0')}：跨页中文完整顺序" }
    val source = ExportSnapshot(
      NoteDocument(
        NoteModel("note", "folder", "分页逐行校验", createdAtMillis = 1L, updatedAtMillis = 2L),
        listOf(BlockModel("body", "note", 0, BlockType.TEXT, text = body)), emptyList(),
      ), "测试",
    )
    val measured = RasterPngLayout.measure(source, ExportFormat.PNG_PAGED_600_DPI, { error("no image") }, {})
    assertTrue("fixture must actually span pages", measured.pages.size > 1)
    var nextSourceLine = 0
    var nextSourceCharacter = 0
    val visited = mutableListOf<Int>()
    measured.pages.forEachIndexed { pageIndex, original ->
      // The same measured A4 commands, rendered at point scale to keep reference bitmaps small.
      val page = original.copy(height = ceil(original.height / measured.scale.toDouble()).toInt())
      val plan = measured.copy(pages = listOf(page), width = 600, scale = 1f, dpi = null)
      val actual = renderSmallPage(plan, page)
      val expected = wholeCommandReference(plan, page)
      try {
        assertSamePixels("A4 source page ${pageIndex + 1}", expected, actual)
        page.commands.filterIsInstance<RasterCommand.Text>().filter { it.layout.text.toString() == body }.forEach { command ->
          val layout = command.layout
          assertEquals("all literal source lines must exist", 180, layout.lineCount)
          val first = layout.getLineForVertical(command.sourceTop)
          val sourceBottom = command.sourceTop + (command.bottom - command.top).roundToInt()
          val until = layout.getLineForVertical(sourceBottom - 1) + 1
          assertEquals("no missing/duplicated source line at page ${pageIndex + 1}", nextSourceLine, first)
          assertEquals("source character intervals must be continuous", nextSourceCharacter, layout.getLineStart(first))
          assertEquals(layout.getLineTop(first), command.sourceTop)
          assertEquals(layout.getLineBottom(until - 1), sourceBottom)
          for (line in first until until) {
            visited += line
            val top = (command.top + layout.getLineTop(line) - command.sourceTop).roundToInt()
            val bottom = (command.top + layout.getLineBottom(line) - command.sourceTop).roundToInt()
            assertTrue("source line $line has a positive on-page interval", bottom > top && top >= 0 && bottom <= page.height)
            assertSameRegion("source line $line on page ${pageIndex + 1}", expected, actual, command.x.toInt(), top, command.width.toInt(), bottom - top)
          }
          nextSourceLine = until
          nextSourceCharacter = layout.getLineEnd(until - 1)
        }
      } finally { actual.recycle(); expected.recycle() }
    }
    assertEquals((0 until 180).toList(), visited)
    assertEquals(180, nextSourceLine)
    assertEquals(body.length, nextSourceCharacter)
  }

  @Test
  fun dividerCenteredJustBelowBandEdgeKeepsItsUpperStrokePixels() {
    val page = RasterPage(listOf(RasterCommand.Divider(8f, 56f, 128.5f / 8f)), 256)
    val plan = RasterPngPlan(listOf(page), 512, 8f, NoteSkinCatalog.byId("minimal-white"), null)
    val expected = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
    expected.eraseColor(plan.skin.background.toArgb())
    val expectedCanvas = Canvas(expected).apply {
      scale(8f, 8f)
    }
    NoteSkinBackgroundPainter.drawPattern(
      canvas = expectedCanvas,
      width = plan.width / plan.scale,
      height = page.height / plan.scale,
      skin = plan.skin,
      resources = exportTestResources(),
    )
    expectedCanvas.apply {
      drawLine(8f, 128.5f / 8f, 56f, 128.5f / 8f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plan.skin.divider.toArgb(); strokeWidth = 1f })
    }
    val actual = renderSmallPage(plan, page)
    try {
      assertTrue("independent stroke occupies pixels above the 128-row boundary", expected.getPixel(128, 126) != plan.skin.background.toArgb())
      assertEquals("upper half of actual divider stroke cannot be clipped", expected.getPixel(128, 126), actual.getPixel(128, 126))
      assertSamePixels("whole divider stroke across band boundary", expected, actual)
    } finally { expected.recycle(); actual.recycle() }
  }

  private fun formattedSnapshot(skinId: String): ExportSnapshot {
    val blocks = fixtures.mapIndexed { index, fixture ->
      BlockModel(fixture.id, "note", index, fixture.type, text = fixture.text,
        formatting = if (fixture.id == "bold") BlockFormattingState.Supported(
          BlockFormatting(inlineStyles = listOf(InlineStyleRange(2, 4, InlineStyle.BOLD))),
        ) else BlockFormattingState.Supported(BlockFormatting()))
    }
    return ExportSnapshot(
      NoteDocument(
        NoteModel("note", "folder", "参考标题", skinId = skinId, fontSizeSpOverride = 24f,
          lineSpacingMultiplierOverride = 1.3f, createdAtMillis = 1L, updatedAtMillis = 2L),
        blocks, fixtures.filter { it.completed != null }.map {
          TodoModel(it.id, "note", it.id, it.text, it.completed == true, 1L)
        },
      ), "测试",
    )
  }

  /** An independently styled, single Canvas draw: no renderer helpers or band selection. */
  private fun independentFormattedReference(plan: RasterPngPlan, bold: Boolean, strike: Boolean, drawText: Boolean = true): Bitmap {
    val bitmap = Bitmap.createBitmap(plan.width, plan.pages.single().height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(plan.skin.background.toArgb())
    val canvas = Canvas(bitmap).apply { scale(plan.scale, plan.scale) }
    NoteSkinBackgroundPainter.drawPattern(
      canvas = canvas,
      width = plan.width / plan.scale,
      height = plan.pages.single().height / plan.scale,
      skin = plan.skin,
      resources = exportTestResources(),
    )
    fixtures.forEach { fixture ->
      val indent = when (fixture.type) { BlockType.QUOTE -> 72f; BlockType.TODO -> 144f; else -> 0f }
      val text = SpannableString(fixture.text).apply {
        if (bold && fixture.id == "bold") setSpan(StyleSpan(Typeface.BOLD), 2, 4, 0)
        if (strike && fixture.completed == true) setSpan(StrikethroughSpan(), 0, length, 0)
      }
      val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (if (fixture.completed == true) plan.skin.secondaryText else plan.skin.foreground).toArgb()
        textSize = 72f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        isSubpixelText = true
      }
      val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (896f - indent).toInt())
        .setIncludePad(false).setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setLineSpacing(72f * (1.3f - 1f), 1f).build()
      val x = 92f + indent
      val top = fixture.pixelTop / plan.scale
      if (drawText) {
        val save = canvas.save()
        canvas.clipRect(x, top, x + 896f - indent, top + layout.height)
        canvas.translate(x, top)
        layout.draw(canvas)
        canvas.restoreToCount(save)
      }
      if (drawText && fixture.type == BlockType.QUOTE) {
        canvas.drawLine(x - 9f, top, x - 9f, top + layout.height,
          Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plan.skin.accent.toArgb(); strokeWidth = 2f })
      }
      fixture.completed?.let { referenceCheckbox(canvas, x, top, layout.getLineBottom(0).toFloat(), 72f, plan, it) }
    }
    return bitmap
  }

  /** Draws measured source intervals once onto a whole page, independently of band traversal. */
  private fun wholeCommandReference(plan: RasterPngPlan, page: RasterPage): Bitmap {
    val bitmap = Bitmap.createBitmap(plan.width, page.height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(plan.skin.background.toArgb())
    val canvas = Canvas(bitmap).apply { scale(plan.scale, plan.scale) }
    NoteSkinBackgroundPainter.drawPattern(
      canvas = canvas,
      width = plan.width / plan.scale,
      height = page.height / plan.scale,
      skin = plan.skin,
      resources = exportTestResources(),
    )
    page.commands.forEach { command ->
      check(command is RasterCommand.Text) { "pagination fixture contains text only" }
      val save = canvas.save()
      canvas.clipRect(command.x, command.top, command.x + command.width, command.bottom)
      canvas.translate(command.x, command.top - command.sourceTop)
      command.layout.draw(canvas)
      canvas.restoreToCount(save)
    }
    return bitmap
  }

  private fun referenceCheckbox(canvas: Canvas, x: Float, top: Float, lineHeight: Float, textSize: Float, plan: RasterPngPlan, completed: Boolean) {
    val size = max(8f, textSize * 0.9f)
    val boxTop = top + max(0f, (lineHeight - size) / 2f)
    val box = RectF(x - size * 1.7f, boxTop, x - size * 0.7f, boxTop + size)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = (if (completed) plan.skin.accent else plan.skin.secondaryText).toArgb()
      style = if (completed) Paint.Style.FILL else Paint.Style.STROKE
      strokeWidth = max(1f, size * 0.07f)
    }
    canvas.drawRoundRect(box, size * 0.18f, size * 0.18f, paint)
    if (completed) {
      paint.color = plan.skin.background.toArgb(); paint.style = Paint.Style.STROKE
      paint.strokeWidth = size * 0.1f; paint.strokeCap = Paint.Cap.ROUND
      canvas.drawLine(box.left + size * 0.2f, box.centerY(), box.left + size * 0.42f, box.bottom - size * 0.22f, paint)
      canvas.drawLine(box.left + size * 0.42f, box.bottom - size * 0.22f, box.right - size * 0.16f, box.top + size * 0.22f, paint)
    }
  }

  private fun assertFormattedPixels(expected: Bitmap, actual: Bitmap, checkboxEdges: Bitmap, plan: RasterPngPlan) {
    val a = IntArray(expected.width * expected.height)
    val b = IntArray(a.size)
    val edges = IntArray(a.size)
    expected.getPixels(a, 0, expected.width, 0, 0, expected.width, expected.height)
    actual.getPixels(b, 0, actual.width, 0, 0, actual.width, actual.height)
    checkboxEdges.getPixels(edges, 0, expected.width, 0, 0, expected.width, expected.height)
    val mismatches = a.indices.filter { a[it] != b[it] &&
      (0..2).maxOf { channel ->
        kotlin.math.abs(((a[it] ushr (channel * 8)) and 255) - ((b[it] ushr (channel * 8)) and 255))
      } > 1
    }
    // Ignore only one-level sampling noise from rasterizing the approved atlas; all
    // material differences must still be independently identified checkbox AA edges.
    val solid = setOf(plan.skin.background.toArgb(), plan.skin.accent.toArgb(), plan.skin.secondaryText.toArgb())
    val mismatchSummary = mismatches.take(40).joinToString { index ->
      val x = index % expected.width
      val y = index / expected.width
      "($x,$y:${a[index].toUInt().toString(16)}>${b[index].toUInt().toString(16)})"
    }
    assertTrue("at most six checkbox AA edge pixels may differ: ${mismatches.size}; $mismatchSummary", mismatches.size <= 6)
    mismatches.forEach { index ->
      val x = index % expected.width
      val y = index / expected.width
      assertTrue("non-checkbox-edge pixel differs at ($x,$y)", edges[index] !in solid && a[index] == edges[index])
      val delta = (0..2).maxOf { channel ->
        kotlin.math.abs(((a[index] ushr (channel * 8)) and 255) - ((b[index] ushr (channel * 8)) and 255))
      }
      // Independent whole-canvas and streamed-bitmap draws differ by at most 15 channel
      // levels on these antialiased checkbox edges on API 35; all solid/text pixels stay exact.
      assertTrue("checkbox AA channel delta on ${plan.skin.id} at ($x,$y): $delta", delta <= 15)
    }
  }

  private fun renderSmallPage(plan: RasterPngPlan, page: RasterPage): Bitmap {
    val output = ByteArrayOutputStream()
    AndroidRasterPngExportRenderer(exportTestResources()).renderPage(plan, page, { error("no image") }, output, {})
    val bytes = output.toByteArray()
    return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
  }

  private fun differingPixels(first: Bitmap, second: Bitmap): Int {
    val a = IntArray(first.width * first.height)
    val b = IntArray(second.width * second.height)
    first.getPixels(a, 0, first.width, 0, 0, first.width, first.height)
    second.getPixels(b, 0, second.width, 0, 0, second.width, second.height)
    return a.indices.count { a[it] != b[it] }
  }

  private fun assertSamePixels(label: String, expected: Bitmap, actual: Bitmap) =
    assertSameRegion(label, expected, actual, 0, 0, expected.width, expected.height)

  private fun assertSameRegion(label: String, expected: Bitmap, actual: Bitmap, x: Int, y: Int, width: Int, height: Int) {
    assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
    val a = IntArray(width * height)
    val b = IntArray(width * height)
    expected.getPixels(a, 0, width, x, y, width, height)
    actual.getPixels(b, 0, width, x, y, width, height)
    fun channelDelta(index: Int) = (0..2).maxOf { channel ->
      kotlin.math.abs(((a[index] ushr (channel * 8)) and 255) - ((b[index] ushr (channel * 8)) and 255))
    }
    val mismatches = a.indices.filter { a[it] != b[it] }
    val mismatch = mismatches.firstOrNull { channelDelta(it) > 1 }
    val maxChannelDelta = mismatches.maxOfOrNull(::channelDelta) ?: 0
    assertTrue("$label: pixel mismatch at (${x + (mismatch ?: 0) % width}, ${y + (mismatch ?: 0) / width})" +
      if (mismatch != null) ", expected=${a[mismatch]}, actual=${b[mismatch]}, significant=${mismatches.count { channelDelta(it) > 1 }}, " +
        "allDifferent=${mismatches.size}, maxChannelDelta=$maxChannelDelta" else " (minor atlas resampling delta ≤1)", mismatch == null)
  }
}

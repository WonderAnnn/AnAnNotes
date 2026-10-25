package com.anan.anannotes.feature.export

import android.graphics.BitmapFactory
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
import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.ui.theme.NoteSkin
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal sealed interface RasterCommand {
  val top: Float
  val bottom: Float
  data class Text(
    val layout: StaticLayout, val x: Float, val width: Float,
    override val top: Float, val sourceTop: Int, override val bottom: Float,
    val quote: Boolean = false, val checkbox: Boolean? = null, val firstLineHeight: Float = 0f,
  ) : RasterCommand
  data class Image(val block: BlockModel, val bounds: RectF) : RasterCommand {
    override val top get() = bounds.top
    override val bottom get() = bounds.bottom
  }
  data class Divider(val left: Float, val right: Float, val centerY: Float) : RasterCommand {
    val strokeWidth get() = 1f
    override val top get() = centerY - strokeWidth / 2f
    override val bottom get() = centerY + strokeWidth / 2f
  }
}

internal data class RasterPage(val commands: List<RasterCommand>, val height: Int)
internal data class RasterPngPlan(
  val pages: List<RasterPage>, val width: Int, val scale: Float, val skin: NoteSkin, val dpi: Int?,
)

/** Measures without allocating page bitmaps or opening any destination file. Coordinates are
 * PDF points for A4, native 1080px for long images; rasterization occurs only after preflight.
 */
internal object RasterPngLayout {
  const val MAX_LONG_HEIGHT = 32768
  private const val MAX_PAGES = 512
  private const val MAX_TEXT_LENGTH = 1_000_000L
  private const val MAX_BLOCKS = 10000

  fun measure(snapshot: ExportSnapshot, format: ExportFormat, openImage: (BlockModel) -> InputStream,
    checkActive: () -> Unit): RasterPngPlan {
    val a4 = format == ExportFormat.PNG_PAGED_600_DPI
    require(a4 || format == ExportFormat.PNG_LONG_IMAGE)
    val document = snapshot.document
    if (document.blocks.size > MAX_BLOCKS || document.note.title.length.toLong() +
      document.blocks.sumOf { it.text?.length?.toLong() ?: 0L } > MAX_TEXT_LENGTH) {
      fail("笔记超过高清渲染安全上限，请分成较小笔记后导出。")
    }
    val skin = NoteSkinCatalog.byId(document.note.skinId)
    val settings = DisplaySettingsRules.resolve(snapshot.displaySettings, document.note.fontSizeSpOverride,
      document.note.lineSpacingMultiplierOverride)
    val pixelWidth = if (a4) ExportLayout.a4PngSize(600).width else 1080
    val logicalWidth = if (a4) ExportLayout.a4PdfPageSize.widthPoints else 1080f
    val logicalHeight = if (a4) ExportLayout.a4PdfPageSize.heightPoints else Float.POSITIVE_INFINITY
    val margin = if (a4) 42f else 92f
    val bodySize = settings.fontSizeSp * if (a4) 0.75f else 3f
    val gap = if (a4) 8f else bodySize * 0.45f
    val contentWidth = logicalWidth - margin * 2
    val bottom = logicalHeight - margin - if (a4 && snapshot.includeAttribution) 22f else 0f
    val commands = mutableListOf<RasterCommand>()
    val pages = mutableListOf<RasterPage>()
    var y = margin

    fun checkHeight() {
      if (!a4 && y + margin > MAX_LONG_HEIGHT) fail("长图超过 32768 px 安全高度，请选择 A4 分页 PNG。")
      checkActive()
    }
    fun finishPage() {
      if (pages.size >= MAX_PAGES) fail("分页图片超过 512 页安全上限，请分开导出笔记。")
      if (a4) {
        if (snapshot.includeAttribution) {
          val attributionPaint = textPaint(skin.secondaryText.toArgb(), 8f)
          val attribution = makeLayout(ExportLayout.ATTRIBUTION_TEXT, attributionPaint, logicalWidth.toInt(), 1f)
          val x = (logicalWidth - attributionPaint.measureText(ExportLayout.ATTRIBUTION_TEXT)) / 2f
          commands += RasterCommand.Text(attribution, x, attributionPaint.measureText(ExportLayout.ATTRIBUTION_TEXT),
            logicalHeight - 40f, 0, logicalHeight - 40f + attribution.height)
        }
        val footer = makeLayout((pages.size + 1).toString(), textPaint(skin.secondaryText.toArgb(), 9f), 60, 1f)
        commands += RasterCommand.Text(footer, logicalWidth / 2f - 5f, 60f,
          logicalHeight - 25f, 0, logicalHeight - 25f + footer.height)
      }
      val longImageFooterSpace = if (!a4 && snapshot.includeAttribution) 24f else 0f
      val height = if (a4) ExportLayout.a4PngSize(600).height else max(1,
        ceil((y + margin + longImageFooterSpace).toDouble()).toInt())
      if (!a4 && snapshot.includeAttribution) {
        if (height > MAX_LONG_HEIGHT) fail("长图超过 32768 px 安全高度，请选择 A4 分页 PNG。")
        val attributionPaint = textPaint(skin.secondaryText.toArgb(), 18f)
        val attribution = makeLayout(ExportLayout.ATTRIBUTION_TEXT, attributionPaint, logicalWidth.toInt(), 1f)
        val x = (logicalWidth - attributionPaint.measureText(ExportLayout.ATTRIBUTION_TEXT)) / 2f
        val top = height - 36f
        commands += RasterCommand.Text(attribution, x, attributionPaint.measureText(ExportLayout.ATTRIBUTION_TEXT),
          top, 0, top + attribution.height)
      }
      pages += RasterPage(commands.toList(), height)
      commands.clear()
      y = margin
    }
    fun nextPage() {
      check(a4)
      finishPage()
    }
    fun addText(layout: StaticLayout, indent: Float = 0f, quote: Boolean = false, completed: Boolean? = null) {
      var first = 0
      while (first < layout.lineCount) {
        checkActive()
        val sourceTop = layout.getLineTop(first)
        var end = first
        while (end < layout.lineCount && layout.getLineBottom(end) - sourceTop <= bottom - y) end++
        if (end == first && y > margin + 1f) { nextPage(); continue }
        // A single line must fit even for unusually large fonts; clip to available content area.
        if (end == first) end++
        val height = min((layout.getLineBottom(end - 1) - sourceTop).toFloat(), bottom - y)
        commands += RasterCommand.Text(layout, margin + indent, contentWidth - indent, y, sourceTop, y + height,
          quote, completed.takeIf { first == 0 }, (layout.getLineBottom(first) - sourceTop).toFloat())
        y += height
        checkHeight()
        first = end
        if (first < layout.lineCount) nextPage()
      }
      y += gap
      checkHeight()
    }

    addText(makeLayout(document.note.title.ifBlank { "无标题笔记" },
      textPaint(skin.foreground.toArgb(), if (a4) 23f else bodySize * 1.45f, true), contentWidth.toInt(), 1.15f))
    y += gap
    val todos = document.todos.associateBy { it.blockId }
    document.blocks.sortedBy(BlockModel::position).forEach { block ->
      checkActive()
      when (block.type) {
        BlockType.TEXT, BlockType.OCR_TEXT, BlockType.QUOTE, BlockType.TODO -> {
          val text = block.text.orEmpty()
          if (text.isBlank()) return@forEach
          val completed = todos[block.id]?.isCompleted == true
          val heading = block.type == BlockType.TEXT && (block.formatting as? BlockFormattingState.Supported)
            ?.value?.presentation == TextBlockPresentation.HEADING
          val color = if (completed || block.type == BlockType.OCR_TEXT) skin.secondaryText else skin.foreground
          val indent = when (block.type) { BlockType.TODO -> bodySize * 2f; BlockType.QUOTE -> bodySize; else -> 0f }
          addText(makeLayout(styledText(block, text, completed),
            textPaint(color.toArgb(), bodySize * if (heading) 1.35f else 1f, heading),
            max(1, (contentWidth - indent).toInt()), settings.lineSpacingMultiplier), indent,
            quote = block.type == BlockType.QUOTE, completed = completed.takeIf { block.type == BlockType.TODO })
        }
        BlockType.IMAGE -> {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          openImage(block).use { BitmapFactory.decodeStream(it, null, bounds) }
          if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ExportAssetReadException("无法解码笔记中的原图")
          val percent = block.widthPercent.takeIf(Float::isFinite)?.coerceIn(10f, 100f) ?: 100f
          val ratio = bounds.outWidth.toFloat() / bounds.outHeight
          var width = contentWidth * percent / 100f
          var height = width / ratio
          if (a4 && height > bottom - margin) { height = bottom - margin; width = height * ratio }
          if (a4 && y + height > bottom && y > margin + 1f) nextPage()
          val left = when (block.alignment) {
            BlockAlignment.START -> margin
            BlockAlignment.CENTER -> (logicalWidth - width) / 2f
            BlockAlignment.END -> logicalWidth - margin - width
          }
          commands += RasterCommand.Image(block, RectF(left, y, left + width, y + height))
          y += height + gap
          checkHeight()
        }
        BlockType.DIVIDER -> {
          if (a4 && y + gap * 2 > bottom) nextPage()
          commands += RasterCommand.Divider(margin, logicalWidth - margin, y + gap)
          y += gap * 2
          checkHeight()
        }
      }
    }
    finishPage()
    return RasterPngPlan(pages, pixelWidth, pixelWidth / logicalWidth, skin, if (a4) 600 else null)
  }

  private fun makeLayout(text: CharSequence, paint: TextPaint, width: Int, spacing: Float): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setIncludePad(false)
      .setAlignment(Layout.Alignment.ALIGN_NORMAL).setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
      .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
      .setLineSpacing(max(0f, paint.textSize * (spacing - 1f)), 1f).build()

  private fun textPaint(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color; textSize = size
    typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    isSubpixelText = true
  }

  private fun styledText(block: BlockModel, text: String, completed: Boolean): CharSequence {
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
    return SpannableString(text).apply {
      if (completed) setSpan(StrikethroughSpan(), 0, text.length, 0)
      if (formatting != null && BlockFormattingRules.validate(text, block.type, formatting) == null) {
        formatting.inlineStyles.filter { it.startUtf16 < it.endUtf16 }.forEach { range ->
          val span = when (range.style) {
            InlineStyle.BOLD -> StyleSpan(Typeface.BOLD)
            InlineStyle.ITALIC -> StyleSpan(Typeface.ITALIC)
            InlineStyle.STRIKETHROUGH -> StrikethroughSpan()
          }
          setSpan(span, range.startUtf16, range.endUtf16, 0)
        }
      }
    }
  }

  private fun fail(message: String): Nothing = throw ExportOperationException(ExportErrorCode.WRITE_FAILED, message)
}

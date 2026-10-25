package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.content.res.Resources
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
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
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import com.anan.anannotes.ui.theme.NoteSkinBackgroundPainter
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders fixed-size social PNGs one page at a time; each bitmap is released after its file is written. */
class AndroidSocialPngExportRenderer(private val resources: Resources) : SocialPngExportRenderer {
  override suspend fun render(
    snapshot: ExportSnapshot,
    format: ExportFormat,
    openImage: (BlockModel) -> InputStream,
    writePage: suspend (pageNumber: Int, content: (OutputStream) -> Unit) -> ExportFileReceipt,
  ) {
    val size = ExportLayout.pngSize(format)
    val document = snapshot.document
    val skin = NoteSkinCatalog.byId(document.note.skinId)
    val display = DisplaySettingsRules.resolve(
      defaults = snapshot.displaySettings,
      fontSizeSpOverride = document.note.fontSizeSpOverride,
      lineSpacingMultiplierOverride = document.note.lineSpacingMultiplierOverride,
    )
    val margin = size.width * PAGE_MARGIN_FRACTION
    val contentWidth = size.width - margin * 2f
    val top = size.height * PAGE_TOP_FRACTION
    val bottom = size.height - margin * 1.35f - if (snapshot.includeAttribution) 22f else 0f
    val bodySize = display.fontSizeSp * SOCIAL_PX_PER_SP
    val todoByBlockId = document.todos.associateBy { it.blockId }
    var bitmap: Bitmap? = null
    var canvas: Canvas? = null
    var pageNumber = 0
    var y = top

    suspend fun finishPage() {
      val pageBitmap = bitmap ?: return
      val completedPageNumber = pageNumber
      try {
        val footerPaint = textPaint(skin.secondaryText.toArgb(), size = size.width * FOOTER_SIZE_FRACTION)
        drawAttributionIfEnabled(checkNotNull(canvas), skin, size, margin, snapshot.includeAttribution)
        val footer = completedPageNumber.toString()
        val footerWidth = footerPaint.measureText(footer)
        checkNotNull(canvas).drawText(
          footer,
          (size.width - footerWidth) / 2f,
          size.height - margin * 0.38f,
          footerPaint,
        )
        writePage(completedPageNumber) { output ->
          if (!pageBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw IOException("PNG 编码失败")
          }
        }
      } finally {
        pageBitmap.recycle()
        bitmap = null
        canvas = null
      }
    }

    suspend fun startPage() {
      finishPage()
      pageNumber += 1
      val nextBitmap = try {
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
      } catch (error: OutOfMemoryError) {
        throw ExportOperationException(
          ExportErrorCode.WRITE_FAILED,
          "设备可用内存不足，无法生成社交 PNG。请减少超大图片后重试。",
          error,
        )
      }
      bitmap = nextBitmap
      canvas = Canvas(nextBitmap).also {
        NoteSkinBackgroundPainter.draw(it, size.width.toFloat(), size.height.toFloat(), skin, resources)
      }
      y = top
      if (pageNumber > 1) {
        val continuationPaint = textPaint(
          skin.secondaryText.toArgb(),
          size = bodySize * CONTINUATION_TITLE_SCALE,
          bold = true,
        )
        val title = document.note.title.ifBlank { "无标题笔记" }
        val clippedTitle = TextUtils.ellipsize(
          title,
          continuationPaint,
          contentWidth,
          TextUtils.TruncateAt.END,
        )
        checkNotNull(canvas).drawText(clippedTitle, 0, clippedTitle.length, margin, y + continuationPaint.textSize, continuationPaint)
        y += continuationPaint.textSize * CONTINUATION_GAP_SCALE
      }
    }

    suspend fun nextPage() = startPage()

    suspend fun drawLayout(
      layout: StaticLayout,
      x: Float,
      width: Float,
      quote: Boolean = false,
      todo: BlockModel? = null,
      todoCompleted: Boolean = false,
    ) {
      if (layout.lineCount == 0) return
      var firstLine = 0
      while (firstLine < layout.lineCount) {
        if (bitmap == null) startPage()
        val availableHeight = bottom - y
        if (availableHeight <= 1f && y > top + 1f) {
          nextPage()
          continue
        }
        val blockTop = layout.getLineTop(firstLine)
        var endLine = firstLine
        while (endLine < layout.lineCount) {
          val fragmentHeight = layout.getLineBottom(endLine) - blockTop
          if (fragmentHeight > bottom - y && endLine > firstLine) break
          if (fragmentHeight > bottom - y && y > top + 1f) break
          endLine += 1
          if (fragmentHeight > bottom - y) break
        }
        if (endLine == firstLine) {
          val singleLineHeight = layout.getLineBottom(firstLine) - layout.getLineTop(firstLine)
          if (singleLineHeight > bottom - top || y <= top + 1f) {
            throw ExportOperationException(
              ExportErrorCode.WRITE_FAILED,
              "当前字号或行间距过大，无法在社交图片中完整排版。请调小字体或行间距后重试。",
            )
          }
          nextPage()
          continue
        }

        val fragmentBottom = layout.getLineBottom(endLine - 1)
        val fragmentHeight = (fragmentBottom - blockTop).toFloat()
        val activeCanvas = checkNotNull(canvas)
        activeCanvas.withClip(x, y, x + width, y + fragmentHeight) {
          activeCanvas.withTranslation(x, y - blockTop) { layout.draw(activeCanvas) }
        }
        if (quote) {
          val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.accent.toArgb()
            strokeWidth = max(3f, bodySize * 0.055f)
            strokeCap = Paint.Cap.ROUND
          }
          activeCanvas.drawLine(x - bodySize * 0.28f, y, x - bodySize * 0.28f, y + fragmentHeight, marker)
        }
        if (todo != null && firstLine == 0) {
          drawTodoCheckbox(activeCanvas, x - bodySize * 0.98f, y, fragmentHeight, todoCompleted, skin)
        }
        y += fragmentHeight
        firstLine = endLine
        if (firstLine < layout.lineCount) nextPage()
      }
      y += bodySize * PARAGRAPH_GAP_SCALE
    }

    try {
      startPage()
      val titlePaint = textPaint(skin.foreground.toArgb(), size = bodySize * TITLE_SCALE, bold = true)
      drawLayout(
        makeLayout(
          text = document.note.title.ifBlank { "无标题笔记" },
          paint = titlePaint,
          width = contentWidth.roundToInt(),
          lineSpacingMultiplier = TITLE_LINE_SPACING,
        ),
        x = margin,
        width = contentWidth,
      )

      if (y + bodySize * SKIN_LABEL_GAP_SCALE > bottom) nextPage()
      val skinPaint = textPaint(skin.secondaryText.toArgb(), size = bodySize * SKIN_LABEL_SCALE)
      checkNotNull(canvas).drawText(skin.name, margin, y + bodySize * SKIN_LABEL_BASELINE_SCALE, skinPaint)
      y += bodySize * SKIN_LABEL_GAP_SCALE

      document.blocks.sortedBy(BlockModel::position).forEach { block ->
        val text = block.text.orEmpty()
        when (block.type) {
          BlockType.TEXT,
          BlockType.OCR_TEXT,
          BlockType.QUOTE,
          BlockType.TODO,
          -> {
            if (text.isBlank()) return@forEach
            val completed = todoByBlockId[block.id]?.isCompleted == true
            val presentation = (block.formatting as? BlockFormattingState.Supported)
              ?.value?.presentation
            val isHeading = block.type == BlockType.TEXT && presentation == TextBlockPresentation.HEADING
            val textColor = when {
              completed || block.type == BlockType.OCR_TEXT -> skin.secondaryText.toArgb()
              else -> skin.foreground.toArgb()
            }
            val paint = textPaint(
              color = textColor,
              size = if (isHeading) bodySize * HEADING_SCALE else bodySize,
              bold = isHeading,
            )
            val styledText = styledText(block, text, completed)
            val indent = when (block.type) {
              BlockType.TODO -> bodySize * TODO_INDENT_SCALE
              BlockType.QUOTE -> bodySize * QUOTE_INDENT_SCALE
              else -> 0f
            }
            val layout = makeLayout(
              text = styledText,
              paint = paint,
              width = max(1, (contentWidth - indent).roundToInt()),
              lineSpacingMultiplier = display.lineSpacingMultiplier,
            )
            drawLayout(
              layout = layout,
              x = margin + indent,
              width = contentWidth - indent,
              quote = block.type == BlockType.QUOTE,
              todo = block.takeIf { it.type == BlockType.TODO },
              todoCompleted = completed,
            )
          }
          BlockType.IMAGE -> {
            val image = decodeImage(block, openImage, size.width, size.height)
            try {
              val percent = block.widthPercent.takeIf(Float::isFinite)?.coerceIn(10f, 100f) ?: 100f
              val sourceRatio = image.width.toFloat() / image.height.toFloat()
              var imageWidth = contentWidth * percent / 100f
              var imageHeight = imageWidth / sourceRatio
              val maxHeight = bottom - top
              if (imageHeight > maxHeight) {
                imageHeight = maxHeight
                imageWidth = imageHeight * sourceRatio
              }
              if (y + imageHeight > bottom && y > top + 1f) nextPage()
              val left = when (block.alignment) {
                BlockAlignment.START -> margin
                BlockAlignment.CENTER -> (size.width - imageWidth) / 2f
                BlockAlignment.END -> size.width - margin - imageWidth
              }
              val destination = RectF(left, y, left + imageWidth, y + imageHeight)
              val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
              checkNotNull(canvas).drawBitmap(image, null, destination, imagePaint)
              y += imageHeight + bodySize * PARAGRAPH_GAP_SCALE
            } finally {
              image.recycle()
            }
          }
          BlockType.DIVIDER -> {
            if (y + bodySize * DIVIDER_BLOCK_HEIGHT_SCALE > bottom && y > top + 1f) nextPage()
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = skin.divider.toArgb()
              strokeWidth = max(1f, bodySize * 0.025f)
            }
            val centerY = y + bodySize * DIVIDER_CENTER_SCALE
            checkNotNull(canvas).drawLine(margin, centerY, size.width - margin, centerY, dividerPaint)
            y += bodySize * DIVIDER_BLOCK_HEIGHT_SCALE
          }
        }
      }
      finishPage()
    } catch (error: OutOfMemoryError) {
      throw ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "设备可用内存不足，无法生成社交 PNG。请减少超大图片后重试。",
        error,
      )
    } finally {
      bitmap?.let { if (!it.isRecycled) it.recycle() }
    }
  }

  private fun decodeImage(
    block: BlockModel,
    openImage: (BlockModel) -> InputStream,
    targetWidth: Int,
    targetHeight: Int,
  ): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImage(block).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw ExportAssetReadException("无法解码笔记中的原图")
    }
    val options = BitmapFactory.Options().apply {
      inSampleSize = ExportLayout.imageSampleSize(bounds.outWidth, bounds.outHeight, max(targetWidth, targetHeight))
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return openImage(block).use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw ExportAssetReadException("无法解码笔记中的原图")
  }

  private fun makeLayout(
    text: CharSequence,
    paint: TextPaint,
    width: Int,
    lineSpacingMultiplier: Float,
  ): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
    .setIncludePad(false)
    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
    .setLineSpacing(max(0f, paint.textSize * (lineSpacingMultiplier - 1f)), 1f)
    .build()

  private fun styledText(block: BlockModel, text: String, completed: Boolean): CharSequence {
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
      ?: return if (completed) SpannableString(text).apply {
        if (text.isNotEmpty()) setSpan(StrikethroughSpan(), 0, text.length, 0)
      } else text
    if (BlockFormattingRules.validate(text, block.type, formatting) != null) return text
    val styled = SpannableString(text)
    if (completed && text.isNotEmpty()) styled.setSpan(StrikethroughSpan(), 0, text.length, 0)
    formatting.inlineStyles.forEach { range ->
      if (range.startUtf16 >= range.endUtf16) return@forEach
      when (range.style) {
        InlineStyle.BOLD -> styled.setSpan(StyleSpan(Typeface.BOLD), range.startUtf16, range.endUtf16, 0)
        InlineStyle.ITALIC -> styled.setSpan(StyleSpan(Typeface.ITALIC), range.startUtf16, range.endUtf16, 0)
        InlineStyle.STRIKETHROUGH -> styled.setSpan(StrikethroughSpan(), range.startUtf16, range.endUtf16, 0)
      }
    }
    return styled
  }

  private fun drawTodoCheckbox(
    canvas: Canvas,
    left: Float,
    lineTop: Float,
    fragmentHeight: Float,
    completed: Boolean,
    skin: com.anan.anannotes.ui.theme.NoteSkin,
  ) {
    val boxSize = max(25f, fragmentHeight * TODO_CHECKBOX_SCALE)
    val top = lineTop + (fragmentHeight - boxSize) / 2f
    val box = RectF(left, top, left + boxSize, top + boxSize)
    val checkboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (completed) skin.accent.toArgb() else skin.secondaryText.toArgb()
      style = if (completed) Paint.Style.FILL else Paint.Style.STROKE
      strokeWidth = max(2f, boxSize * 0.08f)
    }
    canvas.drawRoundRect(box, boxSize * 0.18f, boxSize * 0.18f, checkboxPaint)
    if (completed) {
      val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.background.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = max(2f, boxSize * 0.11f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
      }
      canvas.drawLine(box.left + boxSize * 0.22f, box.centerY(), box.left + boxSize * 0.42f, box.bottom - boxSize * 0.24f, checkPaint)
      canvas.drawLine(box.left + boxSize * 0.42f, box.bottom - boxSize * 0.24f, box.right - boxSize * 0.16f, box.top + boxSize * 0.2f, checkPaint)
    }
  }

  private fun textPaint(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    isSubpixelText = true
  }

  private fun drawAttributionIfEnabled(
    canvas: Canvas,
    skin: com.anan.anannotes.ui.theme.NoteSkin,
    size: ExportPixelSize,
    margin: Float,
    enabled: Boolean,
  ) {
    if (!enabled) return
    val paint = textPaint(skin.secondaryText.toArgb(), size = max(12f, size.width * 0.014f))
    val textWidth = paint.measureText(ExportLayout.ATTRIBUTION_TEXT)
    canvas.drawText(
      ExportLayout.ATTRIBUTION_TEXT,
      (size.width - textWidth) / 2f,
      size.height - margin * 0.82f,
      paint,
    )
  }

  private companion object {
    const val PAGE_MARGIN_FRACTION = 0.085f
    const val PAGE_TOP_FRACTION = 0.09f
    const val SOCIAL_PX_PER_SP = 3f
    const val TITLE_SCALE = 1.45f
    const val TITLE_LINE_SPACING = 1.15f
    const val CONTINUATION_TITLE_SCALE = 0.58f
    const val CONTINUATION_GAP_SCALE = 0.65f
    const val PARAGRAPH_GAP_SCALE = 0.45f
    const val SKIN_LABEL_SCALE = 0.52f
    const val SKIN_LABEL_GAP_SCALE = 1.6f
    const val SKIN_LABEL_BASELINE_SCALE = 1.1f
    const val HEADING_SCALE = 1.28f
    const val TODO_INDENT_SCALE = 1.65f
    const val QUOTE_INDENT_SCALE = 0.75f
    const val TODO_CHECKBOX_SCALE = 0.8f
    const val DIVIDER_BLOCK_HEIGHT_SCALE = 1.1f
    const val DIVIDER_CENTER_SCALE = 0.52f
    const val FOOTER_SIZE_FRACTION = 0.018f
  }
}

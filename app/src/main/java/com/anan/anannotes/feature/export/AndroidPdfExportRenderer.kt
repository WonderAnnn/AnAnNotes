package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.content.res.Resources
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
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
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import com.anan.anannotes.ui.theme.NoteSkinBackgroundPainter
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders text as PDF vector glyphs and decodes only page-sized image samples. */
class AndroidPdfExportRenderer(private val resources: Resources) : PdfExportRenderer {
  override fun render(
    snapshot: ExportSnapshot,
    openImage: (BlockModel) -> InputStream,
    output: OutputStream,
  ) {
    val document = snapshot.document
    val skin = NoteSkinCatalog.byId(document.note.skinId)
    val display = DisplaySettingsRules.resolve(
      defaults = snapshot.displaySettings,
      fontSizeSpOverride = document.note.fontSizeSpOverride,
      lineSpacingMultiplierOverride = document.note.lineSpacingMultiplierOverride,
    )
    val bodySize = display.fontSizeSp * SP_TO_PDF_POINTS
    val pageWidth = ExportLayout.a4PdfPageSize.widthPoints.roundToInt()
    val pageHeight = ExportLayout.a4PdfPageSize.heightPoints.roundToInt()
    val margin = 42f
    val contentWidth = pageWidth - margin * 2
    val bottom = pageHeight - margin - if (snapshot.includeAttribution) 22f else 0f
    val todoByBlockId = document.todos.associateBy { it.blockId }
    val pdf = PdfDocument()
    var page: PdfDocument.Page? = null
    var pageNumber = 0
    var y = margin

    fun finishPage() {
      val activePage = page ?: return
      val footerPaint = textPaint(skin.secondaryText.toArgb(), size = 9f)
      if (snapshot.includeAttribution) {
        val attributionPaint = textPaint(skin.secondaryText.toArgb(), size = 8f)
        val attributionWidth = attributionPaint.measureText(ExportLayout.ATTRIBUTION_TEXT)
        activePage.canvas.drawText(
          ExportLayout.ATTRIBUTION_TEXT,
          (pageWidth - attributionWidth) / 2f,
          pageHeight - 33f,
          attributionPaint,
        )
      }
      activePage.canvas.drawText(pageNumber.toString(), pageWidth / 2f, pageHeight - 18f, footerPaint)
      pdf.finishPage(activePage)
      page = null
    }

    fun startPage() {
      finishPage()
      pageNumber += 1
      val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
      val next = pdf.startPage(info)
      page = next
      NoteSkinBackgroundPainter.draw(next.canvas, pageWidth.toFloat(), pageHeight.toFloat(), skin, resources)
      y = margin
    }

    fun ensurePage() {
      if (page == null) startPage()
    }

    fun nextPage() = startPage()

    fun drawLayout(
      layout: StaticLayout,
      x: Float,
      width: Float,
      quote: Boolean = false,
      todo: BlockModel? = null,
      todoCompleted: Boolean = false,
    ) {
      ensurePage()
      if (layout.lineCount == 0) return
      var firstLine = 0
      while (firstLine < layout.lineCount) {
        val availableHeight = bottom - y
        if (availableHeight <= 1f && y > margin + 1f) {
          nextPage()
          continue
        }
        val blockTop = layout.getLineTop(firstLine)
        var endLine = firstLine
        while (endLine < layout.lineCount) {
          val fragmentHeight = layout.getLineBottom(endLine) - blockTop
          if (fragmentHeight > bottom - y && endLine > firstLine) break
          if (fragmentHeight > bottom - y && y > margin + 1f) break
          endLine += 1
          if (fragmentHeight > bottom - y) break
        }
        if (endLine == firstLine) {
          nextPage()
          continue
        }
        val fragmentBottom = layout.getLineBottom(endLine - 1)
        val fragmentHeight = (fragmentBottom - blockTop).toFloat()
        val canvas = checkNotNull(page).canvas
        canvas.withClip(x, y, x + width, y + fragmentHeight) {
          canvas.withTranslation(x, y - blockTop) { layout.draw(canvas) }
        }
        if (quote) {
          val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = skin.accent.toArgb()
            strokeWidth = 2.5f
          }
          canvas.drawLine(x - 9f, y, x - 9f, y + fragmentHeight, markerPaint)
        }
        if (todo != null && firstLine == 0) {
          val boxTop = y + max(1f, (fragmentHeight - 13f) / 2f)
          val box = RectF(x - 24f, boxTop, x - 8f, boxTop + 16f)
          val checkboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (todoCompleted) skin.accent.toArgb() else skin.secondaryText.toArgb()
            style = if (todoCompleted) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 1.5f
          }
          canvas.drawRoundRect(box, 3f, 3f, checkboxPaint)
          if (todoCompleted) {
            val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = skin.background.toArgb()
              style = Paint.Style.STROKE
              strokeWidth = 1.7f
              strokeCap = Paint.Cap.ROUND
              strokeJoin = Paint.Join.ROUND
            }
            canvas.drawLine(box.left + 3.5f, box.centerY(), box.left + 6.5f, box.bottom - 4f, checkPaint)
            canvas.drawLine(box.left + 6.5f, box.bottom - 4f, box.right - 3f, box.top + 4f, checkPaint)
          }
        }
        y += fragmentHeight
        firstLine = endLine
        if (firstLine < layout.lineCount) nextPage()
      }
      y += 8f
    }

    try {
      ensurePage()
      val titlePaint = textPaint(skin.foreground.toArgb(), size = 23f, bold = true)
      val titleLayout = makeLayout(
        text = document.note.title.ifBlank { "无标题笔记" },
        paint = titlePaint,
        width = contentWidth.toInt(),
        lineSpacingMultiplier = 1.15f,
      )
      drawLayout(titleLayout, x = margin, width = contentWidth)
      if (y + 22f > bottom) nextPage()
      val skinLabelPaint = textPaint(skin.secondaryText.toArgb(), size = 9f)
      checkNotNull(page).canvas.drawText(skin.name, margin, y + 12f, skinLabelPaint)
      y += 24f

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
              size = if (isHeading) bodySize * 1.35f else bodySize,
              bold = isHeading,
            )
            val styledText = styledText(block, text, completed)
            val lineSpacing = display.lineSpacingMultiplier
            val indent = when (block.type) {
              BlockType.TODO -> 28f
              BlockType.QUOTE -> 18f
              else -> 0f
            }
            val layout = makeLayout(
              text = styledText,
              paint = paint,
              width = max(1, (contentWidth - indent).toInt()),
              lineSpacingMultiplier = lineSpacing,
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
            val bitmap = decodePageSizedImage(block, openImage)
            try {
              val requestedWidth = contentWidth * block.widthPercent.coerceIn(10f, 100f) / 100f
              val ratio = block.aspectRatio?.takeIf { it.isFinite() && it > 0f }
                ?: bitmap.width.toFloat() / bitmap.height
              val maxImageHeight = min(420f, bottom - margin)
              val scale = min(1f, maxImageHeight / (requestedWidth / ratio))
              val imageWidth = requestedWidth * scale
              val imageHeight = imageWidth / ratio
              if (y + imageHeight > bottom && y > margin + 1f) nextPage()
              val left = when (block.alignment) {
                BlockAlignment.START -> margin
                BlockAlignment.CENTER -> (pageWidth - imageWidth) / 2f
                BlockAlignment.END -> pageWidth - margin - imageWidth
              }
              val destination = RectF(left, y, left + imageWidth, y + imageHeight)
              val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
              checkNotNull(page).canvas.drawBitmap(bitmap, null, destination, imagePaint)
              y += imageHeight + 12f
            } finally {
              bitmap.recycle()
            }
          }
          BlockType.DIVIDER -> {
            ensurePage()
            if (y + 16f > bottom) nextPage()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              color = skin.divider.toArgb()
              strokeWidth = 1f
            }
            checkNotNull(page).canvas.drawLine(margin, y + 7f, pageWidth - margin, y + 7f, paint)
            y += 22f
          }
        }
      }
      finishPage()
      pdf.writeTo(output)
      output.flush()
    } catch (error: OutOfMemoryError) {
      throw ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "设备可用内存不足，无法生成 PDF。请减少超大图片后重试。",
        error,
      )
    } finally {
      page?.let { runCatching { pdf.finishPage(it) } }
      pdf.close()
    }
  }

  private fun decodePageSizedImage(
    block: BlockModel,
    openImage: (BlockModel) -> InputStream,
  ): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImage(block).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
      throw ExportAssetReadException("无法解码笔记中的原图")
    }
    val targetWidth = MAX_IMAGE_DECODE_EDGE
    val targetHeight = MAX_IMAGE_DECODE_EDGE
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return openImage(block).use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw ExportAssetReadException("无法解码笔记中的原图")
  }

  private fun sampleSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    require(targetWidth == targetHeight) { "当前 PDF 图片解码器要求方形尺寸上限" }
    return ExportLayout.imageSampleSize(width, height, targetWidth)
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
      ?: return if (completed) SpannableString(text).apply { setSpan(StrikethroughSpan(), 0, text.length, 0) } else text
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

  private fun textPaint(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    isSubpixelText = true
  }

  private companion object {
    const val SP_TO_PDF_POINTS = 0.75f
    const val MAX_IMAGE_DECODE_EDGE = 1_536
  }
}

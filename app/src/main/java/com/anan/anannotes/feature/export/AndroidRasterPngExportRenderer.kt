package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.content.res.Resources
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.ui.theme.NoteSkinBackgroundPainter
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** True 600DPI A4 pages / one 1080px long PNG, with a 128-row canvas and one sampled original.
 * Layout and encoding run off the UI thread. No full-page or full-long-image bitmap is created.
 */
class AndroidRasterPngExportRenderer(private val resources: Resources) : SocialPngExportRenderer {
  override suspend fun render(snapshot: ExportSnapshot, format: ExportFormat,
    openImage: (BlockModel) -> InputStream,
    writePage: suspend (pageNumber: Int, content: (OutputStream) -> Unit) -> ExportFileReceipt) {
    // Do not invoke writePage/flow emissions inside withContext: Flow requires its collector context.
    val context = currentCoroutineContext()
    val plan = withContext(Dispatchers.Default) {
      try { RasterPngLayout.measure(snapshot, format, openImage) { context.ensureActive() } }
      catch (error: OutOfMemoryError) { memoryFailure(error) }
    }
    plan.pages.forEachIndexed { index, page ->
      context.ensureActive()
      writePage(index + 1) { output ->
        try { renderPage(plan, page, openImage, output) { context.ensureActive() } }
        catch (error: PngSizeLimitException) { throw ExportOperationException(ExportErrorCode.WRITE_FAILED, checkNotNull(error.message), error) }
        catch (error: OutOfMemoryError) { memoryFailure(error) }
      }
    }
  }

  internal fun renderPage(plan: RasterPngPlan, page: RasterPage, openImage: (BlockModel) -> InputStream,
    output: OutputStream, checkActive: () -> Unit) {
    val band = Bitmap.createBitmap(plan.width, BAND_HEIGHT + BAND_OVERDRAW * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(band)
    val row = IntArray(plan.width)
    var image: Bitmap? = null
    var imageId: String? = null
    fun decode(command: RasterCommand.Image): Bitmap {
      if (imageId != command.block.id) {
        image?.recycle(); image = null; imageId = null
        image = decodeImage(command, plan.scale, openImage)
        imageId = command.block.id
      }
      return checkNotNull(image)
    }
    try {
      StreamingPngWriter(output, plan.width, page.height, plan.dpi).use { writer ->
        var start = 0
        while (start < page.height) {
          checkActive()
          val count = min(BAND_HEIGHT, page.height - start)
          val bandTop = max(0, start - BAND_OVERDRAW)
          val bandBottom = min(page.height, start + count + BAND_OVERDRAW)
          val outputOffset = start - bandTop
          band.eraseColor(plan.skin.background.toArgb())
          val save = canvas.save()
          try {
            // Render beyond both output edges so antialiased marks that cross a band seam
            // are rasterized with their full geometry before the seam rows are discarded.
            canvas.translate(0f, -bandTop.toFloat())
            canvas.scale(plan.scale, plan.scale)
            val top = bandTop / plan.scale
            val bottom = bandBottom / plan.scale
            NoteSkinBackgroundPainter.drawPattern(
              canvas = canvas,
              width = plan.width / plan.scale,
              height = page.height / plan.scale,
              skin = plan.skin,
              resources = resources,
              visibleTop = top,
              visibleBottom = bottom,
            )
            page.commands.filter {
              // Stroke bounds include both halves; AA may also cover an adjacent device pixel.
              val fringe = if (it is RasterCommand.Divider) 1f / plan.scale else 0f
              it.bottom + fringe >= top && it.top - fringe <= bottom
            }.forEach { command ->
              checkActive()
              when (command) {
                is RasterCommand.Text -> {
                  canvas.withClip(command.x, command.top, command.x + command.width, command.bottom) {
                    withTranslation(command.x, command.top - command.sourceTop) { command.layout.draw(this) }
                  }
                  if (command.quote) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plan.skin.accent.toArgb(); strokeWidth = 2f }
                    canvas.drawLine(command.x - 9f, command.top, command.x - 9f, command.bottom, paint)
                  }
                  command.checkbox?.let { completed -> drawCheckbox(canvas, command, plan, completed) }
                }
                is RasterCommand.Image -> {
                  val bitmap = decode(command)
                  canvas.drawBitmap(bitmap, null, command.bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                is RasterCommand.Divider -> {
                  val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plan.skin.divider.toArgb(); strokeWidth = command.strokeWidth }
                  canvas.drawLine(command.left, command.centerY, command.right, command.centerY, paint)
                }
              }
            }
          } finally { canvas.restoreToCount(save) }
          repeat(count) { offset ->
            if (offset % 32 == 0) checkActive()
            band.getPixels(row, 0, plan.width, 0, outputOffset + offset, plan.width, 1)
            writer.writeRow(row)
          }
          start += count
        }
        writer.finish()
      }
    } finally { image?.recycle(); band.recycle() }
  }

  private fun decodeImage(command: RasterCommand.Image, scale: Float, openImage: (BlockModel) -> InputStream): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImage(command.block).use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ExportAssetReadException("无法解码笔记中的原图")
    val targetEdge = max(1, ceil(max(command.bounds.width(), command.bounds.height()) * scale).toInt())
      .coerceAtMost(MAX_IMAGE_EDGE)
    var sample = ExportLayout.imageSampleSize(bounds.outWidth, bounds.outHeight, targetEdge)
    // 9 MP forced many 12 MP phone photos from sample=1 to sample=2, then enlarged
    // that already-blurred half-size bitmap across a 600 DPI A4 image block. Keep enough
    // source pixels for the raster target; the renderer still catches OOM and reports a
    // recoverable export error instead of crashing the process.
    while (ceil(bounds.outWidth.toDouble() / sample) * ceil(bounds.outHeight.toDouble() / sample) > MAX_IMAGE_PIXELS) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return openImage(command.block).use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw ExportAssetReadException("无法解码笔记中的原图")
  }

  private fun drawCheckbox(canvas: Canvas, command: RasterCommand.Text, plan: RasterPngPlan, completed: Boolean) {
    val size = max(8f, command.layout.paint.textSize * 0.9f)
    val top = command.top + max(0f, (command.firstLineHeight - size) / 2f)
    val box = RectF(command.x - size * 1.7f, top, command.x - size * 0.7f, top + size)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (completed) plan.skin.accent.toArgb() else plan.skin.secondaryText.toArgb()
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

  private fun memoryFailure(error: OutOfMemoryError): Nothing = throw ExportOperationException(
    ExportErrorCode.WRITE_FAILED, "设备可用内存不足，无法生成高清 PNG。请减少超大图片后重试。", error)

  private companion object {
    const val BAND_HEIGHT = 128
    // Keep clipped bitmap edges at least 8 output pixels from emitted rows. This covers
    // the checkmark stroke, its antialias fringe, and the native Canvas clip halo.
    const val BAND_OVERDRAW = 8
    const val MAX_IMAGE_EDGE = 8192
    const val MAX_IMAGE_PIXELS = 16_000_000L
  }
}

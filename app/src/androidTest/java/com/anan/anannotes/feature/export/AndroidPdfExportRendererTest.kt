package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPdfExportRendererTest {
  @Test
  fun pdfUsesSakuraPaperAndKeepsTextReadableOverThePattern() {
    val output = ByteArrayOutputStream()
    AndroidPdfExportRenderer(exportTestResources()).render(
      snapshot(
        skinId = "sakura-petal-paper",
        blocks = listOf(block(BlockType.TEXT, "正文在浅樱粉纸笺上仍清晰")),
      ),
      { error("不应读取图片") },
      output,
    )

    withPdf(output.toByteArray()) { pdf ->
      pdf.openPage(0).use { page ->
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          assertTrue("PDF 使用独立樱花底图，而不是旧的纯色背景", bitmap.getPixel(4, 4) != Color.rgb(255, 247, 248))
          assertTrue("PDF 页面应绘制低对比度樱花花纹", hasSoftSakuraPixels(bitmap))
          assertTrue("PDF 正文应保持深色可读", hasDarkTextPixels(bitmap))
        } finally {
          bitmap.recycle()
        }
      }
    }
  }

  @Test
  fun everyNewPatternedSkinRendersOnPdfPagesWithoutReducingTextContrast() {
    val skinIds = listOf(
      "bamboo-shadow", "misty-tide", "osmanthus-note", "moonlit-sky", "wisteria-cloud",
      "camellia-letter", "southern-ginkgo",
    )
    skinIds.forEach { skinId ->
      val skin = NoteSkinCatalog.byId(skinId)
      val output = ByteArrayOutputStream()
      AndroidPdfExportRenderer(exportTestResources()).render(
        snapshot(skinId = skinId, blocks = listOf(block(BlockType.TEXT, "${skin.name} 的 PDF 正文清晰可读"))),
        { error("不应读取图片") },
        output,
      )

      withPdf(output.toByteArray()) { pdf ->
        pdf.openPage(0).use { page ->
          val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
          try {
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            assertTrue("$skinId 应在 PDF 正文外侧绘制纹样", hasPatternInOuterMargin(bitmap, skin.background.toArgb()))
            assertTrue("$skinId 的 PDF 正文应有足够纸面反差", hasHighContrastText(bitmap, skin.background.toArgb()))
          } finally {
            bitmap.recycle()
          }
        }
      }
    }
  }

  @Test
  fun rendersA4PdfWithSelectedSkinAndVectorText() {
    val output = ByteArrayOutputStream()
    val snapshot = snapshot(
      skinId = "moonlit-sky",
      blocks = listOf(block(BlockType.TEXT, "矢量文字可以放大查看")),
    )

    AndroidPdfExportRenderer(exportTestResources()).render(snapshot, { error("不应读取图片") }, output)

    withPdf(output.toByteArray()) { pdf ->
      assertEquals(1, pdf.pageCount)
      pdf.openPage(0).use { page ->
        assertEquals(595, page.width)
        assertEquals(842, page.height)
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        try {
          page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
          assertTrue(
            "PDF 页面应绘制月下星河高清底图",
            bitmap.getPixel(4, 4) != NoteSkinCatalog.byId("moonlit-sky").background.toArgb(),
          )
          assertTrue("页面上应绘制文本", hasNonBackgroundPixel(bitmap))
        } finally {
          bitmap.recycle()
        }
      }
    }
  }

  @Test
  fun optionalAttributionIsRenderedInPdfWithoutChangingPageSize() {
    val base = snapshot(blocks = listOf(block(BlockType.TEXT, "正文不添加署名文字")))
    val plain = renderPdfPage(base)
    val attributed = renderPdfPage(base.copy(includeAttribution = true))
    try {
      assertEquals(plain.width, attributed.width)
      assertEquals(plain.height, attributed.height)
      var differentPixels = 0
      for (y in 0 until plain.height) {
        for (x in 0 until plain.width) {
          if (plain.getPixel(x, y) != attributed.getPixel(x, y)) differentPixels++
        }
      }
      assertTrue("署名开关开启后 PDF 页面应出现底部署名", differentPixels > 0)
    } finally {
      plain.recycle()
      attributed.recycle()
    }
  }

  @Test
  fun longParagraphsPaginateWithoutCollapsingIntoOnePage() {
    val longText = (1..180).joinToString("\n") { "第 $it 行：这是用于验证分页的正文内容。" }
    val output = ByteArrayOutputStream()
    val snapshot = snapshot(blocks = listOf(block(BlockType.TEXT, longText)))

    AndroidPdfExportRenderer(exportTestResources()).render(snapshot, { error("不应读取图片") }, output)

    withPdf(output.toByteArray()) { pdf -> assertTrue(pdf.pageCount > 1) }
  }

  @Test
  fun longTitlePaginatesInsteadOfBeingClippedAtThePageBottom() {
    val output = ByteArrayOutputStream()
    val snapshot = snapshot(title = "标题内容".repeat(320), blocks = emptyList())

    AndroidPdfExportRenderer(exportTestResources()).render(snapshot, { error("不应读取图片") }, output)

    withPdf(output.toByteArray()) { pdf -> assertTrue("超长标题必须在后续页面继续", pdf.pageCount > 1) }
  }

  @Test
  fun embedsAnOriginalImageBlock() {
    val source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
    val image = ByteArrayOutputStream().use { output ->
      source.compress(Bitmap.CompressFormat.PNG, 100, output)
      output.toByteArray()
    }
    source.recycle()
    val output = ByteArrayOutputStream()
    val imageBlock = block(BlockType.IMAGE, assetPath = "original.png").copy(aspectRatio = 2f)

    AndroidPdfExportRenderer(exportTestResources()).render(snapshot(blocks = listOf(imageBlock)), { ByteArrayInputStream(image) }, output)

    withPdf(output.toByteArray()) { pdf -> assertEquals(1, pdf.pageCount) }
  }

  private fun snapshot(
    title: String = "PDF 导出验收",
    skinId: String = "minimal-white",
    blocks: List<BlockModel>,
  ) = ExportSnapshot(
    document = NoteDocument(
      note = NoteModel(
        id = "note-pdf",
        folderId = "folder",
        title = title,
        skinId = skinId,
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
      ),
      blocks = blocks,
      todos = emptyList(),
    ),
    folderName = "测试",
    displaySettings = DisplaySettings(),
  )

  private fun block(type: BlockType, text: String? = null, assetPath: String? = null) = BlockModel(
    id = "block-$type",
    noteId = "note-pdf",
    position = 0,
    type = type,
    text = text,
    assetPath = assetPath,
  )

  private fun hasNonBackgroundPixel(bitmap: Bitmap): Boolean {
    val background = bitmap.getPixel(4, 4)
    for (y in 80 until bitmap.height - 40 step 5) {
      for (x in 35 until bitmap.width - 35 step 5) {
        if (bitmap.getPixel(x, y) != background) return true
      }
    }
    return false
  }

  private fun hasSoftSakuraPixels(bitmap: Bitmap): Boolean {
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
      val pixel = bitmap.getPixel(x, y)
      val red = Color.red(pixel)
      val green = Color.green(pixel)
      val blue = Color.blue(pixel)
      if (red - green >= 13 && red - blue >= 8 && green < 245) return true
    }
    return false
  }

  private fun hasDarkTextPixels(bitmap: Bitmap): Boolean {
    for (y in 80 until bitmap.height - 40) for (x in 35 until bitmap.width - 35) {
      val pixel = bitmap.getPixel(x, y)
      if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) return true
    }
    return false
  }

  private fun hasPatternInOuterMargin(bitmap: Bitmap, background: Int): Boolean {
    val margin = (bitmap.width * 0.06f).toInt().coerceAtLeast(1)
    for (y in 0 until bitmap.height) for (x in 1 until margin) {
      if (maxChannelDifference(bitmap.getPixel(x, y), background) > 2) return true
    }
    return false
  }

  private fun hasHighContrastText(bitmap: Bitmap, background: Int): Boolean {
    for (y in 90 until bitmap.height * 3 / 4) for (x in 70 until bitmap.width - 70) {
      if (maxChannelDifference(bitmap.getPixel(x, y), background) > 60) return true
    }
    return false
  }

  private fun maxChannelDifference(first: Int, second: Int): Int = maxOf(
    kotlin.math.abs(Color.red(first) - Color.red(second)),
    kotlin.math.abs(Color.green(first) - Color.green(second)),
    kotlin.math.abs(Color.blue(first) - Color.blue(second)),
  )

  private fun withPdf(bytes: ByteArray, block: (PdfRenderer) -> Unit) {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File.createTempFile("anan-export-test", ".pdf", context.cacheDir)
    try {
      file.writeBytes(bytes)
      ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use(block)
      }
    } finally {
      file.delete()
    }
  }

  private fun renderPdfPage(snapshot: ExportSnapshot): Bitmap {
    val output = ByteArrayOutputStream()
    AndroidPdfExportRenderer(exportTestResources()).render(snapshot, { error("不应读取图片") }, output)
    var rendered: Bitmap? = null
    withPdf(output.toByteArray()) { pdf ->
      pdf.openPage(0).use { page ->
        rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        page.render(checkNotNull(rendered), null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      }
    }
    return checkNotNull(rendered)
  }
}

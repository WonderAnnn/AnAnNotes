package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSocialPngExportRendererTest {
  @Test
  fun rendersSquareImageAtExactSizeWithSelectedSkinAndText() = runBlocking {
    val pages = render(snapshot(blocks = listOf(block(BlockType.TEXT, "社交图片正文"))), ExportFormat.PNG_SOCIAL_SQUARE)

    assertEquals(listOf(1), pages.map { it.first })
    val bitmap = decode(pages.single().second)
    try {
      assertEquals(1080, bitmap.width)
      assertEquals(1080, bitmap.height)
      assertTrue(
        "社交 PNG 应使用月下星河高清底图，而不是纯色背景",
        bitmap.getPixel(4, 4) != NoteSkinCatalog.byId("moonlit-sky").background.toArgb(),
      )
      assertTrue("正文应实际绘制到 PNG", hasNonBackgroundPixel(bitmap))
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun optionalAttributionChangesOnlyRenderedImagePixelsNotDimensions() = runBlocking {
    val source = snapshot(listOf(block(BlockType.TEXT, "纯正文不被署名污染")))
    val plain = render(source, ExportFormat.PNG_SOCIAL_PORTRAIT).single().second
    val branded = render(source.copy(includeAttribution = true), ExportFormat.PNG_SOCIAL_PORTRAIT).single().second
    val plainBitmap = decode(plain)
    val brandedBitmap = decode(branded)
    try {
      assertEquals(plainBitmap.width, brandedBitmap.width)
      assertEquals(plainBitmap.height, brandedBitmap.height)
      var differentPixels = 0
      for (y in 0 until plainBitmap.height) {
        for (x in 0 until plainBitmap.width) {
          if (plainBitmap.getPixel(x, y) != brandedBitmap.getPixel(x, y)) differentPixels++
        }
      }
      assertTrue("开启署名后应在视觉导出中绘制署名", differentPixels > 0)
    } finally {
      plainBitmap.recycle()
      brandedBitmap.recycle()
    }
    assertFalse(ExportLayout.toPlainText(source.document).contains(ExportLayout.ATTRIBUTION_TEXT))
  }

  @Test
  fun socialPngUsesTheSameSakuraPatternWithReadableText() = runBlocking {
    val pages = render(
      snapshot(listOf(block(BlockType.TEXT, "浅樱色纸笺上的正文")), skinId = "sakura-petal-paper"),
      ExportFormat.PNG_SOCIAL_PORTRAIT,
    )
    val bitmap = decode(pages.single().second)
    try {
      assertTrue("社交 PNG 使用独立樱花底图，而不是旧的纯色背景", bitmap.getPixel(4, 4) != Color.rgb(255, 247, 248))
      assertTrue("社交 PNG 应含樱花纹样", hasSoftSakuraPixels(bitmap))
      assertTrue("社交 PNG 正文应仍然清晰", hasDarkTextPixels(bitmap))
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun longContentIsSplitIntoOrderedPortraitImagesWithoutChangingCanvasSize() = runBlocking {
    val longText = (1..240).joinToString("\n") { "第 $it 行：这段内容必须保留在连续的社交图片中。" }

    val pages = render(snapshot(blocks = listOf(block(BlockType.TEXT, longText))), ExportFormat.PNG_SOCIAL_PORTRAIT)

    assertTrue("长笔记不能被裁切成一张图", pages.size > 1)
    assertEquals((1..pages.size).toList(), pages.map { it.first })
    pages.forEach { (_, bytes) ->
      val bitmap = decode(bytes)
      try {
        assertEquals(1080, bitmap.width)
        assertEquals(1350, bitmap.height)
        assertTrue("每张续页都应有内容", hasNonBackgroundPixel(bitmap))
      } finally {
        bitmap.recycle()
      }
    }
  }

  @Test
  fun imageBlockKeepsOriginalAspectRatioWhenExported() = runBlocking {
    val source = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888)
    Canvas(source).drawColor(Color.MAGENTA)
    val sourceBytes = ByteArrayOutputStream().use { output ->
      source.compress(Bitmap.CompressFormat.PNG, 100, output)
      output.toByteArray()
    }
    source.recycle()
    val image = block(BlockType.IMAGE, assetPath = "image.png").copy(widthPercent = 50f, aspectRatio = 1f)

    val pages = render(snapshot(blocks = listOf(image)), ExportFormat.PNG_SOCIAL_PORTRAIT, sourceBytes)
    val bitmap = decode(pages.single().second)
    try {
      val bounds = colorBounds(bitmap, Color.MAGENTA)
      val renderedRatio = (bounds.right - bounds.left + 1).toFloat() / (bounds.bottom - bounds.top + 1)
      assertTrue("图片必须按原始 2:1 比例显示，实际为 $renderedRatio", kotlin.math.abs(renderedRatio - 2f) < 0.12f)
    } finally {
      bitmap.recycle()
    }
  }

  private suspend fun render(
    snapshot: ExportSnapshot,
    format: ExportFormat,
    imageBytes: ByteArray = byteArrayOf(),
  ): List<Pair<Int, ByteArray>> {
    val pages = mutableListOf<Pair<Int, ByteArray>>()
    AndroidSocialPngExportRenderer(exportTestResources()).render(
      snapshot = snapshot,
      format = format,
      openImage = { ByteArrayInputStream(imageBytes) },
    ) { pageNumber, writePng ->
      val output = ByteArrayOutputStream()
      writePng(output)
      pages += pageNumber to output.toByteArray()
      ExportFileReceipt("page-$pageNumber.png", "content://test/$pageNumber")
    }
    return pages
  }

  private fun snapshot(blocks: List<BlockModel>, skinId: String = "moonlit-sky") = ExportSnapshot(
    document = NoteDocument(
      note = NoteModel(
        id = "social-note",
        folderId = "folder",
        title = "社交图片标题",
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
    noteId = "social-note",
    position = 0,
    type = type,
    text = text,
    assetPath = assetPath,
  )

  private fun decode(bytes: ByteArray): Bitmap =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("PNG 无法解码")

  private fun hasNonBackgroundPixel(bitmap: Bitmap): Boolean {
    val background = bitmap.getPixel(4, 4)
    for (y in 60 until bitmap.height - 40 step 4) {
      for (x in 35 until bitmap.width - 35 step 4) {
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
    for (y in 60 until bitmap.height - 40) for (x in 35 until bitmap.width - 35) {
      val pixel = bitmap.getPixel(x, y)
      if (Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) return true
    }
    return false
  }

  private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

  private fun colorBounds(bitmap: Bitmap, color: Int): Bounds {
    var left = bitmap.width
    var top = bitmap.height
    var right = -1
    var bottom = -1
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        if (bitmap.getPixel(x, y) == color) {
          left = minOf(left, x)
          top = minOf(top, y)
          right = maxOf(right, x)
          bottom = maxOf(bottom, y)
        }
      }
    }
    check(right >= left && bottom >= top) { "图片块没有绘制到 PNG" }
    return Bounds(left, top, right, bottom)
  }
}

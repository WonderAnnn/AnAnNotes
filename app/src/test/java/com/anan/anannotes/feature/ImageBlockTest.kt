package com.anan.anannotes.feature

import com.anan.anannotes.core.ImageBlockRules
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.files.PreviewCacheStore
import java.io.ByteArrayInputStream
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBlockTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun imageWidthIsClampedToTenThroughOneHundredPercent() {
    assertEquals(10f, ImageBlockRules.normalizeWidthPercent(1f), 0f)
    assertEquals(100f, ImageBlockRules.normalizeWidthPercent(120f), 0f)
    assertEquals(100f, ImageBlockRules.normalizeWidthPercent(Float.NaN), 0f)
  }

  @Test
  fun imageHeightUsesAvailableWidthAndOriginalAspectRatio() {
    val size = ImageBlockRules.sizeForWidth(availableWidthPx = 400, widthPercent = 50f, aspectRatio = 2f)

    assertEquals(200, size.widthPx)
    assertEquals(100, size.heightPx)
  }

  @Test
  fun imageDefaultsToCenteredAndPreservesTextOrderAtCursor() {
    val originalText = NoteBlockDraft(id = "text-before", type = BlockType.TEXT, text = "甲乙丙丁")
    val existingAfter = NoteBlockDraft(id = "text-after", type = BlockType.TEXT, text = "后续段落")
    val image = NoteBlockDraft(id = "image-1", type = BlockType.IMAGE, assetPath = "notes/assets/original.img", aspectRatio = 1.5f)
    val generatedIds = listOf("text-tail")
    var generatedIdIndex = 0

    val result = ImageBlockRules.insertAtSelection(
      blocks = listOf(originalText, existingAfter),
      textBlockId = originalText.id,
      selectionStart = 2,
      selectionEnd = 2,
      image = image,
      newTextBlockId = { generatedIds[generatedIdIndex++] },
    )

    assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT, BlockType.TEXT), result.map { it.type })
    assertEquals("甲乙", result[0].text)
    assertEquals("丙丁", result[2].text)
    assertEquals("后续段落", result[3].text)
    assertEquals(BlockAlignment.CENTER, result[1].alignment)
    assertEquals(100f, result[1].widthPercent, 0f)
    assertEquals("甲乙丙丁后续段落", result.filter { it.type == BlockType.TEXT }.joinToString("") { it.text.orEmpty() })
    assertTrue(result.map { it.id }.toSet().size == result.size)
  }

  @Test
  fun insertingImageSplitsAndRebasesTextFormatting() {
    val text = NoteBlockDraft(
      id = "formatted-text",
      type = BlockType.TEXT,
      text = "甲乙丙丁",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 4, InlineStyle.BOLD))),
      ),
    )

    val result = ImageBlockRules.insertAtSelection(
      blocks = listOf(text),
      textBlockId = text.id,
      selectionStart = 2,
      selectionEnd = 2,
      image = NoteBlockDraft(id = "image", type = BlockType.IMAGE),
      newTextBlockId = { "right-text" },
    )

    assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT), result.map { it.type })
    assertEquals(
      listOf(InlineStyleRange(0, 2, InlineStyle.BOLD)),
      (result[0].formatting as BlockFormattingState.Supported).value.inlineStyles,
    )
    assertEquals(
      listOf(InlineStyleRange(0, 2, InlineStyle.BOLD)),
      (result[2].formatting as BlockFormattingState.Supported).value.inlineStyles,
    )
    assertEquals(emptyList<InlineStyleRange>(), (result[1].formatting as BlockFormattingState.Supported).value.inlineStyles)
  }

  @Test
  fun insertingImageAtTextStartDoesNotCreateAnEmptyLeadingBlock() {
    val text = NoteBlockDraft(
      id = "text",
      type = BlockType.TEXT,
      text = "正文",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.ITALIC))),
      ),
    )

    val atStart = ImageBlockRules.insertAtSelection(
      blocks = listOf(text),
      textBlockId = text.id,
      selectionStart = 0,
      selectionEnd = 0,
      image = NoteBlockDraft(id = "image-start", type = BlockType.IMAGE),
      newTextBlockId = { "unused-start" },
    )
    assertEquals(listOf(BlockType.IMAGE, BlockType.TEXT), atStart.map { it.type })
    assertEquals(listOf("正文"), atStart.filter { it.type == BlockType.TEXT }.map { it.text })
  }

  @Test
  fun insertingImageAtTextEndAddsAnEditableTextBlockBelowIt() {
    val text = NoteBlockDraft(id = "text", type = BlockType.TEXT, text = "正文")

    val atEnd = ImageBlockRules.insertAtSelection(
      blocks = listOf(text),
      textBlockId = text.id,
      selectionStart = 2,
      selectionEnd = 2,
      image = NoteBlockDraft(id = "image-end", type = BlockType.IMAGE),
      newTextBlockId = { "editable-tail" },
    )

    assertEquals(listOf(BlockType.TEXT, BlockType.IMAGE, BlockType.TEXT), atEnd.map { it.type })
    assertEquals("editable-tail", atEnd.last().id)
    assertEquals("", atEnd.last().text)
  }

  @Test
  fun cacheCleanupDeletesOnlyRegenerablePreviews() {
    val filesRoot = temporaryFolder.newFolder("files")
    val cacheRoot = temporaryFolder.newFolder("cache")
    val originalStore = OriginalImageStore(filesRoot)
    val original = originalStore.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "jpg")
    val previewStore = PreviewCacheStore(cacheRoot)
    val previewPath = previewStore.write("asset-1", byteArrayOf(4, 5))
    val ocrText = NoteBlockDraft(type = BlockType.OCR_TEXT, text = "可搜索的识别文字", ocrStatus = OcrStatus.SUCCEEDED)

    previewStore.clear()

    assertTrue(originalStore.resolve(original.relativePath).exists())
    assertEquals(byteArrayOf(1, 2, 3).toList(), originalStore.resolve(original.relativePath).readBytes().toList())
    assertEquals("可搜索的识别文字", ocrText.text)
    assertEquals(false, previewStore.resolve(previewPath).exists())
  }

  @Test
  fun originalImageStoreRejectsPathsOutsideItsAssetDirectory() {
    val store = OriginalImageStore(temporaryFolder.newFolder("files"))

    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      store.resolve("../private/secret.jpg")
    }
  }
}

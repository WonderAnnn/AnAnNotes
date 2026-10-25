package com.anan.anannotes.feature.export

import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.TodoModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportLayoutTest {
  @Test
  fun a4PngAt600DpiUsesExactPageDimensions() {
    assertEquals(ExportPixelSize(width = 4961, height = 7016), ExportLayout.a4PngSize(dpi = 600))
  }

  @Test
  fun a4PdfUsesPhysicalA4PageSizeInPoints() {
    val page = ExportLayout.a4PdfPageSize

    assertEquals(595.2756f, page.widthPoints, 0.01f)
    assertEquals(841.8898f, page.heightPoints, 0.01f)
  }

  @Test
  fun imageSamplingBoundsBothLandscapeAndPortraitDecodeDimensions() {
    assertEquals(4, ExportLayout.imageSampleSize(4_000, 500, 1_536))
    assertEquals(2, ExportLayout.imageSampleSize(1_024, 2_048, 1_536))
    assertEquals(1, ExportLayout.imageSampleSize(1_200, 900, 1_536))
  }

  @Test
  fun imageSamplingRejectsInvalidDimensions() {
    assertThrows(IllegalArgumentException::class.java) {
      ExportLayout.imageSampleSize(0, 100, 1_536)
    }
  }

  @Test
  fun socialPngPresetsHaveExactSupportedDimensions() {
    assertEquals(ExportPixelSize(1080, 1080), ExportLayout.pngSize(ExportFormat.PNG_SOCIAL_SQUARE))
    assertEquals(ExportPixelSize(1080, 1350), ExportLayout.pngSize(ExportFormat.PNG_SOCIAL_PORTRAIT))
  }

  @Test
  fun longImageIsSplitIntoBoundedSegmentsWithoutLosingPixels() {
    val segments = ExportLayout.splitLongImage(totalHeightPx = 20_000, maxSegmentHeightPx = 8_000)

    assertEquals(listOf(8_000, 8_000, 4_000), segments)
    assertEquals(20_000, segments.sum())
  }

  @Test
  fun longImageRejectsNonPositiveSizes() {
    assertThrows(IllegalArgumentException::class.java) {
      ExportLayout.splitLongImage(totalHeightPx = 0, maxSegmentHeightPx = 8_000)
    }
  }

  @Test
  fun longImageSplittingDoesNotOverflowAtMaximumSupportedHeight() {
    assertEquals(
      listOf(Int.MAX_VALUE),
      ExportLayout.splitLongImage(totalHeightPx = Int.MAX_VALUE, maxSegmentHeightPx = Int.MAX_VALUE),
    )
  }

  @Test
  fun longImageRejectsSegmentCountsThatWouldExhaustMemory() {
    assertThrows(IllegalArgumentException::class.java) {
      ExportLayout.splitLongImage(totalHeightPx = 4_097, maxSegmentHeightPx = 1)
    }
  }

  @Test
  fun defaultFileNameIncludesFolderTitleAndDateAndReplacesIllegalCharacters() {
    val name = ExportLayout.defaultBaseFileName(
      folderName = "学习/资料",
      title = "计划:*?",
      date = LocalDate.of(2026, 9, 28),
    )

    assertEquals("学习_资料_计划_2026-09-28", name)
    assertFalse(name.any { it in "<>:\"/\\|?*" || it.code < 32 })
  }

  @Test
  fun defaultFileNameTruncationDoesNotSplitSupplementaryUnicodeCharacters() {
    val title = "a".repeat(63) + "😀" + "b"

    val name = ExportLayout.defaultBaseFileName(
      folderName = "文件夹",
      title = title,
      date = LocalDate.of(2026, 9, 28),
    )

    assertEquals("文件夹_${"a".repeat(63)}😀_2026-09-28", name)
  }

  @Test
  fun defaultFileNameFitsConservativeUtf8ComponentBudget() {
    val name = ExportLayout.defaultBaseFileName(
      folderName = "资料".repeat(64),
      title = "记录".repeat(64),
      date = LocalDate.of(2026, 9, 28),
    )

    assertTrue(name.toByteArray(Charsets.UTF_8).size <= 180)
    assertTrue(name.startsWith("资料"))
    assertTrue(name.contains("_记录"))
  }

  @Test
  fun uniqueFileNameAddsSequenceInsteadOfReplacingAnExistingExport() {
    assertEquals(
      "学习_计划_2026-09-28 (3).txt",
      ExportLayout.uniqueFileName(
        requestedName = "学习_计划_2026-09-28.txt",
        existingNames = setOf("学习_计划_2026-09-28.txt", "学习_计划_2026-09-28 (2).txt"),
      ),
    )
  }

  @Test
  fun plainTextKeepsTitleTextQuotesTodosAndOcrButOmitsImages() {
    val document = sampleDocument(
      blocks = listOf(
        block(0, BlockType.TEXT, "正文第一行\n正文第二行"),
        block(1, BlockType.QUOTE, "引用内容"),
        block(2, BlockType.TODO, "待办一"),
        block(3, BlockType.IMAGE, assetPath = "notes/assets/private-name.jpg"),
        block(4, BlockType.OCR_TEXT, "识别文字"),
      ),
      todos = listOf(todo("todo-1", "block-2", "待办一", completed = true)),
    )

    val text = ExportLayout.toPlainText(document)

    assertTrue(text.startsWith("一条笔记\n\n"))
    assertTrue(text.contains("正文第一行\n正文第二行"))
    assertTrue(text.contains("> 引用内容"))
    assertTrue(text.contains("- [x] 待办一"))
    assertTrue(text.contains("识别文字"))
    assertFalse(text.contains("private-name.jpg"))
  }

  @Test
  fun plainTextClipboardSerializationUsesDraftOrderAndExcludesImagesAndDividers() {
    val text = ExportLayout.toPlainText(
      title = "快速复制",
      blocks = listOf(
        NoteBlockDraft(id = "first", type = BlockType.TEXT, text = "正文第一行\n正文第二行"),
        NoteBlockDraft(id = "quote", type = BlockType.QUOTE, text = "引用内容"),
        NoteBlockDraft(id = "done", type = BlockType.TODO, text = "已完成"),
        NoteBlockDraft(id = "image", type = BlockType.IMAGE, assetPath = "notes/assets/secret.jpg"),
        NoteBlockDraft(id = "open", type = BlockType.TODO, text = "尚未完成"),
        NoteBlockDraft(id = "ocr", type = BlockType.OCR_TEXT, text = "识别文字"),
        NoteBlockDraft(id = "divider", type = BlockType.DIVIDER),
      ),
      completedTodoBlockIds = setOf("done"),
    )

    assertEquals(
      "快速复制\n\n正文第一行\n正文第二行\n\n> 引用内容\n\n- [x] 已完成\n\n- [ ] 尚未完成\n\n识别文字",
      text,
    )
    assertFalse(text.contains("secret.jpg"))
  }

  @Test
  fun markdownPreservesHeadingsInlineStylesQuotesTodosAndImageReferences() {
    val document = sampleDocument(
      blocks = listOf(
        block(
          position = 0,
          type = BlockType.TEXT,
          text = "重点",
          formatting = BlockFormattingState.Supported(
            BlockFormatting(
              presentation = TextBlockPresentation.HEADING,
              inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD)),
            ),
          ),
        ),
        block(1, BlockType.QUOTE, "引用\n第二行"),
        block(2, BlockType.TODO, "完成任务"),
        block(3, BlockType.IMAGE, assetPath = "notes/assets/photo.JPEG"),
      ),
      todos = listOf(todo("todo-1", "block-2", "完成任务", completed = false)),
    )

    val markdown = ExportLayout.toMarkdown(document)

    assertTrue(markdown.contains("## **重点**"))
    assertTrue(markdown.contains("> 引用\n> 第二行"))
    assertTrue(markdown.contains("- [ ] 完成任务"))
    assertTrue(markdown.contains("![图片](images/image-001.jpeg)"))
  }

  @Test
  fun markdownUsesWellFormedInlineHtmlForCrossingStyleRanges() {
    val document = sampleDocument(
      blocks = listOf(
        block(
          position = 0,
          type = BlockType.TEXT,
          text = "abcdef",
          formatting = BlockFormattingState.Supported(
            BlockFormatting(
              inlineStyles = listOf(
                InlineStyleRange(0, 4, InlineStyle.BOLD),
                InlineStyleRange(2, 6, InlineStyle.ITALIC),
              ),
            ),
          ),
        ),
      ),
    )

    assertTrue(ExportLayout.toMarkdown(document).contains("<strong>ab<em>cd</em></strong><em>ef</em>"))
  }

  @Test
  fun markdownEscapesLiteralMarkdownAndHtmlSyntaxInUserText() {
    val markdown = ExportLayout.toMarkdown(
      sampleDocument(
        blocks = listOf(
          block(0, BlockType.TEXT, "*literal* [link](https://example.test) <tag> & text"),
          block(1, BlockType.QUOTE, "<script>\n> literal"),
          block(2, BlockType.TODO, "- not a second task"),
        ),
      ),
    )

    assertTrue(markdown.contains("\\*literal\\* \\[link\\]\\(https://example\\.test\\) &lt;tag&gt; &amp; text"))
    assertTrue(markdown.contains("> &lt;script&gt;\n> &gt; literal"))
    assertTrue(markdown.contains("- [ ] \\- not a second task"))
  }

  @Test
  fun crossingStyledMarkdownEscapesLiteralHtmlText() {
    val document = sampleDocument(
      blocks = listOf(
        block(
          position = 0,
          type = BlockType.TEXT,
          text = "<img>&",
          formatting = BlockFormattingState.Supported(
            BlockFormatting(
              inlineStyles = listOf(
                InlineStyleRange(0, 4, InlineStyle.BOLD),
                InlineStyleRange(2, 6, InlineStyle.ITALIC),
              ),
            ),
          ),
        ),
      ),
    )

    val markdown = ExportLayout.toMarkdown(document)
    assertTrue(markdown.contains("&lt;"))
    assertTrue(markdown.contains("&gt;"))
    assertTrue(markdown.contains("&amp;"))
    assertFalse(markdown.contains("<img>"))
  }

  @Test
  fun markdownZipContainsMarkdownAndOriginalImageBytes() {
    val imageBytes = byteArrayOf(1, 3, 5, 7)
    val document = sampleDocument(
      blocks = listOf(block(0, BlockType.IMAGE, assetPath = "notes/assets/photo.png")),
    )
    val output = ByteArrayOutputStream()

    ExportLayout.writeMarkdownZip(output, document) { block ->
      assertEquals("block-0", block.id)
      ByteArrayInputStream(imageBytes)
    }

    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes()
        zip.closeEntry()
      }
    }

    assertTrue(entries.keys.any { it.endsWith(".md") })
    assertTrue(entries.containsKey("images/"))
    assertArrayEquals(imageBytes, entries["images/image-001.png"])
    val markdown = entries.entries.first { it.key.endsWith(".md") }.value.toString(Charsets.UTF_8)
    assertTrue(markdown.contains("# 一条笔记"))
    assertTrue(markdown.contains("![图片](images/image-001.png)"))
  }

  @Test
  fun markdownZipClosesImageStreamWhenCreatingItsZipEntryFails() {
    val document = sampleDocument(
      blocks = listOf(block(0, BlockType.IMAGE, assetPath = "notes/assets/photo.png")),
    )
    var imageStreamOpened = false
    var imageStreamClosed = false
    val imageStream = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
      override fun close() {
        imageStreamClosed = true
        super.close()
      }
    }
    val failingOutput = object : OutputStream() {
      override fun write(value: Int) {
        if (imageStreamOpened) throw IOException("模拟 ZIP 条目写入失败")
      }

      override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (imageStreamOpened) throw IOException("模拟 ZIP 条目写入失败")
      }
    }

    assertThrows(IOException::class.java) {
      ExportLayout.writeMarkdownZip(failingOutput, document) {
        imageStreamOpened = true
        imageStream
      }
    }

    assertTrue(imageStreamClosed)
  }

  private fun sampleDocument(
    blocks: List<BlockModel>,
    todos: List<TodoModel> = emptyList(),
  ) = NoteDocument(
    note = NoteModel(
      id = "note-1",
      folderId = "folder-1",
      title = "一条笔记",
      createdAtMillis = 1L,
      updatedAtMillis = 2L,
    ),
    blocks = blocks,
    todos = todos,
  )

  private fun block(
    position: Int,
    type: BlockType,
    text: String? = null,
    assetPath: String? = null,
    formatting: BlockFormattingState = BlockFormattingState.Supported(BlockFormatting()),
  ) = BlockModel(
    id = "block-$position",
    noteId = "note-1",
    position = position,
    type = type,
    text = text,
    assetPath = assetPath,
    formatting = formatting,
  )

  private fun todo(id: String, blockId: String, text: String, completed: Boolean) = TodoModel(
    id = id,
    noteId = "note-1",
    blockId = blockId,
    text = text,
    isCompleted = completed,
    updatedAtMillis = 2L,
  )
}

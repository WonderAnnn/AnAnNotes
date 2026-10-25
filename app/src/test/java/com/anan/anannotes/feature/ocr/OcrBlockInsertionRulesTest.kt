package com.anan.anannotes.feature.ocr

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TextBlockPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrBlockInsertionRulesTest {
  @Test
  fun insertsIndependentPendingOcrBlockAtCaretWithoutDroppingTrailingText() {
    val text = NoteBlockDraft(id = "text", type = BlockType.TEXT, text = "前后正文")
    val pending = NoteBlockDraft(id = "ocr", type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)

    val result = OcrBlockInsertionRules.insertAtSelection(listOf(text), text.id, 1, 3, pending)

    assertEquals(listOf("前", null, "文"), result.blocks.map { it.text })
    assertEquals(listOf(BlockType.TEXT, BlockType.OCR_TEXT, BlockType.TEXT), result.blocks.map { it.type })
    assertEquals("ocr", result.blocks[1].id)
    assertEquals(OcrStatus.PENDING, result.blocks[1].ocrStatus)
    assertEquals(result.blocks.last().id, result.focusTextBlockId)
  }

  @Test
  fun splittingTextPreservesFormattingOnBothSides() {
    val text = NoteBlockDraft(
      id = "text",
      type = BlockType.TEXT,
      text = "abcd",
      formatting = com.anan.anannotes.core.model.BlockFormattingState.Supported(
        com.anan.anannotes.core.model.BlockFormatting(
          presentation = TextBlockPresentation.HEADING,
          inlineStyles = listOf(InlineStyleRange(0, 4, InlineStyle.BOLD)),
        ),
      ),
    )

    val result = OcrBlockInsertionRules.insertAtSelection(
      listOf(text), text.id, 2, 2,
      NoteBlockDraft(id = "ocr", type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING),
    )

    assertTrue((result.blocks.first().formatting as com.anan.anannotes.core.model.BlockFormattingState.Supported).value.inlineStyles
      .all { it.startUtf16 >= 0 && it.endUtf16 <= 2 })
    assertEquals("ab", result.blocks.first().text)
    assertEquals("cd", result.blocks[2].text)
    assertEquals(TextBlockPresentation.HEADING,
      (result.blocks[2].formatting as com.anan.anannotes.core.model.BlockFormattingState.Supported).value.presentation)
  }
}

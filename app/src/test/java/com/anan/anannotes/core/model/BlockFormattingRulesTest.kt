package com.anan.anannotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFormattingRulesTest {
  @Test
  fun appliesInlineStyleToSelectedUtf16Range() {
    val formatted = BlockFormattingRules.setInlineStyle(
      text = "甲乙丙",
      formatting = BlockFormatting(),
      selection = TextSelection(1, 2),
      style = InlineStyle.BOLD,
      enabled = true,
    )

    assertEquals(listOf(InlineStyleRange(1, 2, InlineStyle.BOLD)), formatted.inlineStyles)
  }

  @Test
  fun removesOnlySelectedPartOfAnInlineStyleRange() {
    val formatted = BlockFormattingRules.setInlineStyle(
      text = "甲乙丙丁",
      formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 4, InlineStyle.BOLD))),
      selection = TextSelection(1, 3),
      style = InlineStyle.BOLD,
      enabled = false,
    )

    assertEquals(
      listOf(
        InlineStyleRange(0, 1, InlineStyle.BOLD),
        InlineStyleRange(3, 4, InlineStyle.BOLD),
      ),
      formatted.inlineStyles,
    )
  }

  @Test
  fun normalizationMergesAdjacentRangesOfSameStyleButKeepsDifferentStylesOverlapping() {
    val normalized = BlockFormattingRules.normalize(
      listOf(
        InlineStyleRange(0, 2, InlineStyle.BOLD),
        InlineStyleRange(2, 4, InlineStyle.BOLD),
        InlineStyleRange(1, 3, InlineStyle.ITALIC),
      ),
    )

    assertEquals(
      listOf(
        InlineStyleRange(0, 4, InlineStyle.BOLD),
        InlineStyleRange(1, 3, InlineStyle.ITALIC),
      ),
      normalized,
    )
  }

  @Test
  fun mixedSelectionIsUnifiedByApplyingStyleToWholeSelection() {
    val formatted = BlockFormattingRules.setInlineStyle(
      text = "甲乙丙",
      formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 1, InlineStyle.BOLD))),
      selection = TextSelection(0, 3),
      style = InlineStyle.BOLD,
      enabled = true,
    )

    assertEquals(listOf(InlineStyleRange(0, 3, InlineStyle.BOLD)), formatted.inlineStyles)
  }

  @Test
  fun rejectsSelectionOutsideTextInsteadOfClampingIt() {
    assertThrows(IllegalArgumentException::class.java) {
      BlockFormattingRules.setInlineStyle(
        text = "甲乙丙",
        formatting = BlockFormatting(),
        selection = TextSelection(0, 4),
        style = InlineStyle.BOLD,
        enabled = true,
      )
    }
  }

  @Test
  fun insertedTextInheritsFormattingAndCarriesSelectionAndComposition() {
    val result = BlockFormattingRules.replaceText(
      oldText = "甲乙丙",
      newText = "甲新乙丙",
      oldSelection = TextSelection(1, 1),
      selection = TextSelection(2, 2),
      composing = TextSelection(1, 2),
      formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD))),
    )

    assertEquals("甲新乙丙", result.text)
    assertEquals(TextSelection(2, 2), result.selection)
    assertEquals(TextSelection(1, 2), result.composing)
    assertEquals(listOf(InlineStyleRange(0, 3, InlineStyle.BOLD)), result.formatting.inlineStyles)
  }

  @Test
  fun explicitTypingOverrideWinsOverInheritedStyle() {
    val result = BlockFormattingRules.replaceText(
      oldText = "甲乙丙",
      newText = "甲新乙丙",
      oldSelection = TextSelection(1, 1),
      selection = TextSelection(2, 2),
      composing = null,
      formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD))),
      typingOverrides = mapOf(InlineStyle.BOLD to false),
    )

    assertEquals(
      listOf(
        InlineStyleRange(0, 1, InlineStyle.BOLD),
        InlineStyleRange(2, 3, InlineStyle.BOLD),
      ),
      result.formatting.inlineStyles,
    )
  }

  @Test
  fun deletingTextShiftsTheFollowingFormattedRangeLeft() {
    val result = BlockFormattingRules.replaceText(
      oldText = "甲乙丙",
      newText = "甲丙",
      oldSelection = TextSelection(1, 2),
      selection = TextSelection(1, 1),
      composing = null,
      formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(1, 3, InlineStyle.ITALIC))),
    )

    assertEquals(listOf(InlineStyleRange(1, 2, InlineStyle.ITALIC)), result.formatting.inlineStyles)
  }

  @Test
  fun selectionOnlyChangeDoesNotRewriteFormattingWhenTextIsUnchanged() {
    val formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(1, 2, InlineStyle.BOLD)))

    val result = BlockFormattingRules.replaceText(
      oldText = "aaaa",
      newText = "aaaa",
      oldSelection = TextSelection(1, 2),
      selection = TextSelection(2, 2),
      composing = null,
      formatting = formatting,
    )

    assertEquals(formatting, result.formatting)
  }

  @Test
  fun acceptsWholeEmojiRangeAndNormalizesSelectionsAroundSurrogatePair() {
    val text = "A😀B"
    val completeEmoji = BlockFormatting(inlineStyles = listOf(InlineStyleRange(1, 3, InlineStyle.BOLD)))

    assertEquals(null, BlockFormattingRules.validate(text, BlockType.TEXT, completeEmoji))
    assertEquals(
      FormattingDecodeError.INVALID_RANGE,
      BlockFormattingRules.validate(
        text,
        BlockType.TEXT,
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(1, 2, InlineStyle.BOLD))),
      ),
    )
    val expanded = BlockFormattingRules.setInlineStyle(
      text = text,
      formatting = BlockFormatting(),
      selection = TextSelection(1, 2),
      style = InlineStyle.BOLD,
      enabled = true,
    )
    val collapsed = BlockFormattingRules.normalizeSelection(text, TextSelection(2, 2))

    assertEquals(listOf(InlineStyleRange(1, 3, InlineStyle.BOLD)), expanded.inlineStyles)
    assertEquals(TextSelection(1, 1), collapsed)
  }

  @Test
  fun caretStyleUsesTheAdjacentCharacterAndUsesRightSideAtTextStart() {
    val formatting = BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 1, InlineStyle.BOLD)))

    assertTrue(BlockFormattingRules.isStyleActiveAtCaret("AB", formatting, InlineStyle.BOLD, 0))
    assertTrue(BlockFormattingRules.isStyleActiveAtCaret("AB", formatting, InlineStyle.BOLD, 1))
    assertFalse(BlockFormattingRules.isStyleActiveAtCaret("AB", formatting, InlineStyle.BOLD, 2))
  }

  @Test
  fun combiningCharactersAndNewlinesRetainUtf16FormattingBoundaries() {
    val text = "A\u0301\n乙"
    val formatting = BlockFormatting(
      inlineStyles = listOf(
        InlineStyleRange(0, 2, InlineStyle.BOLD),
        InlineStyleRange(3, 4, InlineStyle.ITALIC),
      ),
    )

    assertEquals(null, BlockFormattingRules.validate(text, BlockType.TEXT, formatting))
    val inserted = BlockFormattingRules.replaceText(
      oldText = text,
      newText = "A\u0301!\n乙",
      oldSelection = TextSelection(2, 2),
      selection = TextSelection(3, 3),
      composing = null,
      formatting = formatting,
    )

    assertEquals("A\u0301!\n乙", inserted.text)
    assertEquals(
      listOf(
        InlineStyleRange(0, 3, InlineStyle.BOLD),
        InlineStyleRange(4, 5, InlineStyle.ITALIC),
      ),
      inserted.formatting.inlineStyles,
    )
  }

  @Test
  fun pasteUndoAndRedoKeepUnaffectedFormattingRangesAnchored() {
    val originalText = "甲乙丙丁"
    val originalFormatting = BlockFormatting(
      inlineStyles = listOf(
        InlineStyleRange(0, 1, InlineStyle.BOLD),
        InlineStyleRange(2, 4, InlineStyle.ITALIC),
      ),
    )
    val plainPasteOverrides = mapOf(InlineStyle.BOLD to false, InlineStyle.ITALIC to false)
    val pasted = BlockFormattingRules.replaceText(
      oldText = originalText,
      newText = "甲复制内容丙丁",
      oldSelection = TextSelection(1, 2),
      selection = TextSelection(5, 5),
      composing = null,
      formatting = originalFormatting,
      typingOverrides = plainPasteOverrides,
    )
    val expectedAfterPaste = listOf(
      InlineStyleRange(0, 1, InlineStyle.BOLD),
      InlineStyleRange(5, 7, InlineStyle.ITALIC),
    )
    assertEquals(expectedAfterPaste, pasted.formatting.inlineStyles)

    val undone = BlockFormattingRules.replaceText(
      oldText = pasted.text,
      newText = originalText,
      oldSelection = pasted.selection,
      selection = TextSelection(2, 2),
      composing = null,
      formatting = pasted.formatting,
      typingOverrides = plainPasteOverrides,
    )
    assertEquals(originalFormatting.inlineStyles, undone.formatting.inlineStyles)

    val redone = BlockFormattingRules.replaceText(
      oldText = undone.text,
      newText = pasted.text,
      oldSelection = undone.selection,
      selection = pasted.selection,
      composing = null,
      formatting = undone.formatting,
      typingOverrides = plainPasteOverrides,
    )
    assertEquals(expectedAfterPaste, redone.formatting.inlineStyles)
  }

  @Test
  fun headingIsRejectedForQuoteBlocks() {
    assertEquals(
      FormattingDecodeError.INVALID_BLOCK_STYLE,
      BlockFormattingRules.validate(
        "引用",
        BlockType.QUOTE,
        BlockFormatting(presentation = TextBlockPresentation.HEADING),
      ),
    )
  }
}

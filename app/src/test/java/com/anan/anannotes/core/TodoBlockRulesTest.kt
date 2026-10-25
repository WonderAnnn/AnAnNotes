package com.anan.anannotes.core

import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class TodoBlockRulesTest {
  @Test
  fun insertingOverSelectionKeepsBothTextSidesAndTheirFormatting() {
    val original = NoteBlockDraft(
      id = "source",
      type = BlockType.TEXT,
      text = "ABCDEFGHIJ",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 10, InlineStyle.BOLD))),
      ),
    )

    val result = TodoBlockRules.insertAtSelection(
      blocks = listOf(original),
      textBlockId = "source",
      selection = TextSelection(3, 7),
      newBlockId = listOf("todo", "todo-tail").iterator()::next,
    )

    assertEquals(
      listOf(
        original.copy(
          text = "ABC",
          formatting = BlockFormattingState.Supported(
            BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 3, InlineStyle.BOLD))),
          ),
        ),
        NoteBlockDraft(id = "todo", type = BlockType.TODO, text = ""),
        original.copy(
          id = "todo-tail",
          text = "HIJ",
          formatting = BlockFormattingState.Supported(
            BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 3, InlineStyle.BOLD))),
          ),
        ),
      ),
      result,
    )
  }

  @Test
  fun insertingAtEndAddsEditableTextSpaceAfterTodo() {
    val original = NoteBlockDraft(id = "source", type = BlockType.TEXT, text = "保留正文")

    val ids = listOf("todo", "todo-tail").iterator()
    val result = TodoBlockRules.insertAtSelection(
      blocks = listOf(original),
      textBlockId = "source",
      selection = TextSelection(4, 4),
      newBlockId = ids::next,
    )

    assertEquals(listOf("source", "todo", "todo-tail"), result.map(NoteBlockDraft::id))
    assertEquals(listOf(BlockType.TEXT, BlockType.TODO, BlockType.TEXT), result.map(NoteBlockDraft::type))
    assertEquals(listOf("保留正文", "", ""), result.map { it.text.orEmpty() })
  }

  @Test
  fun insertingAtStartRetainsOriginalBlockIdentityAndFormattingForFollowingText() {
    val original = NoteBlockDraft(
      id = "source",
      type = BlockType.QUOTE,
      text = "引用内容",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 4, InlineStyle.ITALIC))),
      ),
    )

    val result = TodoBlockRules.insertAtSelection(
      blocks = listOf(original),
      textBlockId = "source",
      selection = TextSelection(0, 0),
      newBlockId = { "todo" },
    )

    assertEquals("todo", result[0].id)
    assertEquals(BlockType.TODO, result[0].type)
    assertEquals(original, result[1])
  }

  @Test
  fun insertingFromInsideTodoPlacesNewTodoAfterFocusedBlockWithoutSplittingIt() {
    val original = NoteBlockDraft(id = "current-todo", type = BlockType.TODO, text = "正在编辑的任务")
    val beforeText = NoteBlockDraft(id = "before-text", type = BlockType.TEXT, text = "前文")
    val followingText = NoteBlockDraft(id = "following-text", type = BlockType.TEXT, text = "后续正文")

    val result = TodoBlockRules.insertAfterTodo(
      blocks = listOf(beforeText, original, followingText),
      todoBlockId = original.id,
      newTodoBlockId = "new-todo",
    )

    assertEquals(listOf("前文", "正在编辑的任务", "", "后续正文"), result.map { it.text.orEmpty() })
    assertEquals(
      listOf(BlockType.TEXT, BlockType.TODO, BlockType.TODO, BlockType.TEXT),
      result.map { it.type },
    )
    assertEquals(original, result[1])
    assertEquals("new-todo", result[2].id)
  }

  @Test
  fun completedTodoStateRemainsAttachedToItsBlockWhileTextIsTemporarilyBlank() {
    val blocks = listOf(
      NoteBlockDraft(id = "kept", type = BlockType.TODO, text = "保留完成状态"),
      NoteBlockDraft(id = "cleared", type = BlockType.TODO, text = ""),
      NoteBlockDraft(id = "ordinary", type = BlockType.TEXT, text = "正文"),
    )

    assertEquals(
      setOf("kept", "cleared"),
      TodoBlockRules.completedIdsForPersistedTodoBlocks(setOf("kept", "cleared", "removed"), blocks),
    )
  }
}

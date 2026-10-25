package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateEditorRulesTest {
  @Test
  fun privateEditorExportsOnlyTheSavedSnapshotWithoutUnsavedChanges() {
    val note = PrivateNoteDocument(
      id = PrivateNoteId("note-1"),
      title = "当前标题",
      blocks = listOf(NoteBlockDraft(id = "body-1", type = BlockType.TEXT, text = "已保存正文")),
      createdAtMillis = 1L,
      updatedAtMillis = 2L,
      contentRevision = 1L,
    )
    val unchangedBlocks = note.blocks.toList()
    val unchangedTodos = emptyList<PrivateTodoDraft>()

    assertTrue(PrivateEditorRules.canExportSavedNote(note, note.title, unchangedBlocks, unchangedTodos))
    assertEquals(false, PrivateEditorRules.canExportSavedNote(null, "新笔记", unchangedBlocks, unchangedTodos))
    assertEquals(false, PrivateEditorRules.canExportSavedNote(note, "未保存标题", unchangedBlocks, unchangedTodos))
    assertEquals(
      false,
      PrivateEditorRules.canExportSavedNote(
        note,
        note.title,
        listOf(unchangedBlocks.single().copy(text = "未保存正文")),
        unchangedTodos,
      ),
    )
    assertEquals(
      false,
      PrivateEditorRules.canExportSavedNote(
        note,
        note.title,
        unchangedBlocks,
        listOf(PrivateTodoDraft(blockId = "todo-1", text = "新待办")),
      ),
    )
  }

  @Test
  fun insertingAndReorderingTodosKeepsTheirStableBlockIds() {
    val image = NoteBlockDraft(id = "image-1", type = BlockType.IMAGE, assetPath = "private-asset:kept")
    val firstTodo = NoteBlockDraft(id = "todo-a", type = BlockType.TODO, text = "原任务 A")
    val secondTodo = NoteBlockDraft(id = "todo-b", type = BlockType.TODO, text = "原任务 B")
    val result = PrivateEditorRules.mergeBlocks(
      existing = listOf(image, firstTodo, NoteBlockDraft(id = "text-1", type = BlockType.TEXT, text = "旧正文"), secondTodo),
      body = "新正文",
      todos = listOf(
        PrivateTodoDraft(blockId = "todo-new", text = "新任务"),
        PrivateTodoDraft(blockId = "todo-b", text = "更新任务 B"),
        PrivateTodoDraft(blockId = "todo-a", text = "更新任务 A"),
      ),
    )

    assertEquals("新正文", result.first { it.id == "text-1" }.text)
    assertEquals(image, result.first { it.id == "image-1" })
    assertEquals(listOf("todo-new", "todo-b", "todo-a"), result.filter { it.type == BlockType.TODO }.map { it.id })
    assertEquals(listOf("新任务", "更新任务 B", "更新任务 A"), result.filter { it.type == BlockType.TODO }.map { it.text })
  }

  @Test
  fun temporarilyBlankExistingTodoKeepsItsBlockIdentityUntilExplicitlyRemoved() {
    val body = NoteBlockDraft(id = "body", type = BlockType.TEXT, text = "正文")
    val todo = NoteBlockDraft(id = "todo-done", type = BlockType.TODO, text = "已完成任务")
    val existing = listOf(body, todo)

    val blanked = PrivateEditorRules.mergeBlocks(
      existing = existing,
      blocks = listOf(body, todo.copy(text = "")),
      todos = listOf(PrivateTodoDraft(blockId = todo.id, text = "")),
    )
    val removed = PrivateEditorRules.mergeBlocks(
      existing = existing,
      blocks = listOf(body),
      todos = emptyList(),
    )

    assertEquals(listOf(body, todo.copy(text = "")), blanked)
    assertEquals(listOf(body), removed)
  }

  @Test
  fun blankTitleUsesFirstBodyLineWithoutRemovingThatLineFromContent() {
    val body = "标题行\n正文第二行"

    assertEquals("标题行", PrivateEditorRules.resolveTitle("", body))
    assertEquals(body, PrivateEditorRules.mergeBlocks(emptyList(), body, emptyList()).single().text)
  }

  @Test
  fun blankNewEditorDoesNotCreateContentBlocks() {
    assertTrue(PrivateEditorRules.mergeBlocks(emptyList(), "  ", emptyList()).isEmpty())
  }

  @Test
  fun orderedBlockMergeKeepsUneditedIdsImagesAndFormatting() {
    val body = NoteBlockDraft(
      id = "body-id",
      type = BlockType.TEXT,
      text = "私密正文更新",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 4, InlineStyle.BOLD))),
      ),
    )
    val quote = NoteBlockDraft(
      id = "quote-id",
      type = BlockType.QUOTE,
      text = "引用原文",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.ITALIC))),
      ),
    )
    val image = NoteBlockDraft(id = "image-id", type = BlockType.IMAGE, assetPath = "private-asset:stable-ref")
    val ocr = NoteBlockDraft(
      id = "ocr-id",
      type = BlockType.OCR_TEXT,
      text = "识别原文",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.STRIKETHROUGH))),
      ),
    )
    val todo = NoteBlockDraft(id = "todo-id", type = BlockType.TODO, text = "旧任务")

    val result = PrivateEditorRules.mergeBlocks(
      existing = listOf(body.copy(text = "私密正文"), quote, image, ocr, todo),
      blocks = listOf(body, quote, image, ocr, todo),
      todos = listOf(PrivateTodoDraft(blockId = "todo-id", text = "更新任务")),
    )

    assertEquals(listOf("body-id", "quote-id", "image-id", "ocr-id", "todo-id"), result.map { it.id })
    assertEquals("私密正文更新", result[0].text)
    assertEquals(quote, result[1])
    assertEquals(image, result[2])
    assertEquals(ocr, result[3])
    assertEquals("更新任务", result[4].text)
  }

  @Test
  fun recoveryDraftMustMatchBothPrivateNoteIdAndBaseRevision() {
    val matching = PrivateEditSnapshot(
      draftId = "matching",
      noteId = PrivateNoteId("note-a"),
      folderId = "private-uncategorized",
      title = "恢复",
      titleManuallySet = true,
      blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "草稿")),
      skinId = "minimal-white",
      baseContentRevision = 7L,
      localRevision = 3L,
      savedAtMillis = 100L,
    )
    val stale = matching.copy(draftId = "stale", baseContentRevision = 6L, savedAtMillis = 300L)
    val otherNote = matching.copy(draftId = "other", noteId = PrivateNoteId("note-b"), savedAtMillis = 400L)

    assertEquals(
      matching,
      PrivateEditorRules.findRecoverableDraft(listOf(stale, otherNote, matching), PrivateNoteId("note-a"), 7L),
    )
    assertEquals(null, PrivateEditorRules.findRecoverableDraft(listOf(stale, otherNote), PrivateNoteId("note-a"), 7L))
    assertEquals(null, PrivateEditorRules.findRecoverableDraft(listOf(matching), null, null))
  }
}

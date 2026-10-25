package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.TextSelection
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorExportRulesTest {
  @Test
  fun emptyNewNoteIsBlockedWithoutCreatingAPersistentNote() {
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.EMPTY),
      EditorExportRules.evaluate(emptyState()),
    )
  }

  @Test
  fun meaningfulUnsavedNoteMustBePersistedBeforeExport() {
    assertEquals(
      EditorExportDecision.PersistFirst,
      EditorExportRules.evaluate(
        emptyState().copy(
          body = "最新正文",
          blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "最新正文")),
          saveStatus = EditorSaveStatus.DIRTY,
        ),
      ),
    )
  }

  @Test
  fun savedCurrentNoteIsReadyForExport() {
    assertEquals(
      EditorExportDecision.Ready("note-1"),
      EditorExportRules.evaluate(
        emptyState().copy(
          noteId = "note-1",
          body = "已保存内容",
          blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "已保存内容")),
          saveStatus = EditorSaveStatus.SAVED,
        ),
      ),
    )
  }

  @Test
  fun emptyExistingNoteCannotBeExported() {
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.EMPTY),
      EditorExportRules.evaluate(emptyState().copy(noteId = "note-1", saveStatus = EditorSaveStatus.SAVED)),
    )
  }

  @Test
  fun activeImeCompositionBlocksExport() {
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.COMPOSING),
      EditorExportRules.evaluate(
        emptyState().copy(
          noteId = "note-1",
          saveStatus = EditorSaveStatus.DIRTY,
          composing = TextSelection(0, 1),
        ),
      ),
    )
  }

  @Test
  fun titleAndTodoImeCompositionsBlockExport() {
    val saved = emptyState().copy(
      noteId = "note-1",
      body = "正文",
      blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "正文")),
      saveStatus = EditorSaveStatus.SAVED,
    )
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.COMPOSING),
      EditorExportRules.evaluate(saved.copy(titleComposing = TextSelection(0, 1))),
    )
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.COMPOSING),
      EditorExportRules.evaluate(saved.copy(todoCompositions = mapOf("todo-1" to TextSelection(0, 1)))),
    )
  }

  @Test
  fun conflictAndFailedSaveBlockExport() {
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.CONFLICT),
      EditorExportRules.evaluate(emptyState().copy(noteId = "note-1", saveStatus = EditorSaveStatus.CONFLICT)),
    )
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.SAVE_FAILED),
      EditorExportRules.evaluate(emptyState().copy(noteId = "note-1", saveStatus = EditorSaveStatus.FAILED)),
    )
  }

  @Test
  fun unloadedOrRecoveringEditorCannotExport() {
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.NOT_LOADED),
      EditorExportRules.evaluate(emptyState().copy(isLoaded = false)),
    )
    val recovery = EditorDraftSnapshot(
      draftId = "draft-1",
      noteId = null,
      folderId = UNCATEGORIZED_FOLDER_ID,
      baseContentRevision = 0L,
      localRevision = 1L,
      savedAtMillis = 1L,
      draft = NoteDraft(folderId = UNCATEGORIZED_FOLDER_ID),
    )
    assertEquals(
      EditorExportDecision.Blocked(EditorExportBlockReason.RECOVERY),
      EditorExportRules.evaluate(emptyState().copy(pendingRecovery = recovery)),
    )
  }

  private fun emptyState() = EditorUiState(
    folderId = UNCATEGORIZED_FOLDER_ID,
    blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "")),
    isLoaded = true,
  )
}

package com.anan.anannotes.core

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.NoteSortOrder
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TRASH_RETENTION_MILLIS
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRulesTest {
  @Test
  fun blankDraftIsNotPersisted() {
    assertNull(createNoteIfMeaningful(NoteDraft(title = "  "), id = "n-1", nowMillis = 10L))
  }

  @Test
  fun contentCreatesAnIndependentNoteWithStableId() {
    val note =
      createNoteIfMeaningful(
        NoteDraft(folderId = "folder-a", blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "正文"))),
        id = "n-1",
        nowMillis = 10L,
      )

    assertEquals("n-1", note?.id)
    assertEquals("folder-a", note?.folderId)
  }

  @Test
  fun pendingTextOnlyOcrBlockIsMeaningfulContent() {
    val note = createNoteIfMeaningful(
      NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING))),
      id = "ocr-only",
      nowMillis = 10L,
    )

    assertEquals("ocr-only", note?.id)
  }

  @Test
  fun deletingFolderMovesItsNotesToUncategorized() {
    val note = NoteModel(id = "n-1", folderId = "folder-a", createdAtMillis = 1L, updatedAtMillis = 2L)

    val moved = moveNotesToUncategorized(listOf(note), "folder-a", UNCATEGORIZED_FOLDER_ID)

    assertEquals(UNCATEGORIZED_FOLDER_ID, moved.single().folderId)
  }

  @Test
  fun softDeleteCanBeRestoredAndExpiresAfterThirtyDays() {
    val note = NoteModel(id = "n-1", folderId = UNCATEGORIZED_FOLDER_ID, createdAtMillis = 1L, updatedAtMillis = 1L)
    val deleted = moveToTrash(note, nowMillis = 100L)

    assertEquals(100L, deleted.deletedAtMillis)
    assertFalse(isTrashExpired(deleted, nowMillis = 100L + TRASH_RETENTION_MILLIS - 1L))
    assertTrue(isTrashExpired(deleted, nowMillis = 100L + TRASH_RETENTION_MILLIS))
    assertNull(restoreFromTrash(deleted).deletedAtMillis)
  }

  @Test
  fun pinnedNotesStayAheadOfSelectedTimeSort() {
    val pinned = NoteModel(id = "pinned", folderId = "f", createdAtMillis = 1L, updatedAtMillis = 1L, isPinned = true)
    val recentlyEdited = NoteModel(id = "recent", folderId = "f", createdAtMillis = 2L, updatedAtMillis = 100L)

    assertEquals(listOf("pinned", "recent"), sortNotes(listOf(recentlyEdited, pinned), NoteSortOrder.RECENT_EDITED).map { it.id })
  }

  @Test
  fun createdSortUsesCreationTimeAfterPinnedGroup() {
    val older = NoteModel(id = "older", folderId = "f", createdAtMillis = 1L, updatedAtMillis = 100L)
    val newer = NoteModel(id = "newer", folderId = "f", createdAtMillis = 2L, updatedAtMillis = 2L)
    val pinned = NoteModel(id = "pinned", folderId = "f", createdAtMillis = 0L, updatedAtMillis = 0L, isPinned = true)

    assertEquals(listOf("pinned", "newer", "older"), sortNotes(listOf(older, pinned, newer), NoteSortOrder.CREATED).map { it.id })
  }
}

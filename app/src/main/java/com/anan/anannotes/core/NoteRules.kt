package com.anan.anannotes.core

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.NoteSortOrder
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TRASH_RETENTION_MILLIS

fun createNoteIfMeaningful(draft: NoteDraft, id: String, nowMillis: Long): NoteModel? {
  val hasTitle = draft.title.isNotBlank()
  val hasContent = draft.blocks.any { block ->
    when (block.type) {
      BlockType.IMAGE -> !block.assetPath.isNullOrBlank()
      BlockType.DIVIDER -> true
      BlockType.OCR_TEXT -> block.ocrStatus == OcrStatus.PENDING || !block.text.isNullOrBlank()
      else -> !block.text.isNullOrBlank()
    }
  }
  if (!hasTitle && !hasContent) return null

  return NoteModel(
    id = id,
    folderId = draft.folderId,
    title = draft.title,
    titleManuallySet = draft.titleManuallySet,
    contentRevision = 1L,
    createdAtMillis = nowMillis,
    updatedAtMillis = nowMillis,
    skinId = draft.skinId,
    fontSizeSpOverride = draft.fontSizeSpOverride,
    lineSpacingMultiplierOverride = draft.lineSpacingMultiplierOverride,
  )
}

fun moveNotesToUncategorized(
  notes: List<NoteModel>,
  deletedFolderId: String,
  uncategorizedFolderId: String,
): List<NoteModel> = notes.map { note ->
  if (note.folderId == deletedFolderId) note.copy(folderId = uncategorizedFolderId) else note
}

fun moveToTrash(note: NoteModel, nowMillis: Long): NoteModel =
  note.copy(deletedAtMillis = nowMillis, updatedAtMillis = nowMillis)

fun restoreFromTrash(note: NoteModel): NoteModel = note.copy(deletedAtMillis = null)

fun isTrashExpired(note: NoteModel, nowMillis: Long): Boolean {
  val deletedAt = note.deletedAtMillis ?: return false
  return nowMillis >= deletedAt && nowMillis - deletedAt >= TRASH_RETENTION_MILLIS
}

fun sortNotes(notes: List<NoteModel>, order: NoteSortOrder): List<NoteModel> =
  when (order) {
    NoteSortOrder.RECENT_EDITED ->
      notes.sortedWith(
        compareByDescending<NoteModel> { it.isPinned }
          .thenByDescending { it.updatedAtMillis }
          .thenByDescending { it.createdAtMillis }
          .thenBy { it.id },
      )
    NoteSortOrder.CREATED ->
      notes.sortedWith(
        compareByDescending<NoteModel> { it.isPinned }
          .thenByDescending { it.createdAtMillis }
          .thenByDescending { it.updatedAtMillis }
          .thenBy { it.id },
      )
  }

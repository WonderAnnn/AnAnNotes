package com.anan.anannotes.feature.home

import com.anan.anannotes.core.model.FolderModel
import com.anan.anannotes.core.model.NotePreviewModel
import com.anan.anannotes.core.model.NoteSortOrder
import com.anan.anannotes.feature.export.PendingExportFeedback

const val ALL_NOTES_FOLDER_ID = "system-all-notes"

data class HomeUiState(
  val folders: List<FolderModel> = emptyList(),
  val selectedFolderId: String = ALL_NOTES_FOLDER_ID,
  val sortOrder: NoteSortOrder = NoteSortOrder.RECENT_EDITED,
  val notes: List<NotePreviewModel> = emptyList(),
  val selectedNoteIds: Set<String> = emptySet(),
  val isExporting: Boolean = false,
  val pendingExportFeedback: PendingExportFeedback? = null,
  val message: String? = null,
) {
  val isSelecting: Boolean get() = selectedNoteIds.isNotEmpty()
  val selectedFolderName: String
    get() = if (selectedFolderId == ALL_NOTES_FOLDER_ID) "全部" else folders.firstOrNull { it.id == selectedFolderId }?.name ?: "全部"
}

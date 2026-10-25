package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.autoTitleFromBody
import com.anan.anannotes.core.editorTitle
import com.anan.anannotes.core.model.DEFAULT_NOTE_SKIN_ID
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.TextSelection
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.feature.export.PendingExportFeedback

enum class EditorSaveStatus {
  CLEAN,
  DIRTY,
  SAVING,
  SAVED,
  FAILED,
  CONFLICT,
}

data class EditorOcrProgress(
  val percent: Int,
  val stage: String,
)

data class EditorUiState(
  val noteId: String? = null,
  val draftId: String = "",
  val importRequestId: String? = null,
  val folderId: String = UNCATEGORIZED_FOLDER_ID,
  val baseContentRevision: Long = 0L,
  val body: String = "",
  val manualTitle: String? = null,
  val titleManuallySet: Boolean = false,
  val blocks: List<NoteBlockDraft> = emptyList(),
  val skinId: String = DEFAULT_NOTE_SKIN_ID,
  val fontSizeSpOverride: Float? = null,
  val lineSpacingMultiplierOverride: Float? = null,
  val saveStatus: EditorSaveStatus = EditorSaveStatus.CLEAN,
  val errorMessage: String? = null,
  val isLoaded: Boolean = true,
  val showMoreToolbar: Boolean = false,
  val revision: Long = 0L,
  val pendingRecovery: EditorDraftSnapshot? = null,
  val recoveryConflictRevision: Long? = null,
  val saveConflictRevision: Long? = null,
  val activeTextBlockId: String? = null,
  val selectionStart: Int = 0,
  val selectionEnd: Int = 0,
  val composing: TextSelection? = null,
  val titleSelectionStart: Int = 0,
  val titleSelectionEnd: Int = 0,
  val titleComposing: TextSelection? = null,
  val todoSelections: Map<String, TextSelection> = emptyMap(),
  val todoCompositions: Map<String, TextSelection> = emptyMap(),
  val typingOverrides: Map<InlineStyle, Boolean> = emptyMap(),
  val completedTodoBlockIds: Set<String> = emptySet(),
  val focusTodoBlockId: String? = null,
  val activeTodoBlockId: String? = null,
  val isExporting: Boolean = false,
  val exportMessage: String? = null,
  val pendingExportFeedback: List<PendingExportFeedback> = emptyList(),
  val ocrProgressByBlock: Map<String, EditorOcrProgress> = emptyMap(),
) {
  val hasActiveComposition: Boolean
    get() = composing != null || titleComposing != null || todoCompositions.isNotEmpty()

  val titleForSave: String
    get() = if (titleManuallySet) manualTitle.orEmpty() else autoTitleFromBody(body)

  val titleForDisplay: String
    get() = editorTitle(body, manualTitle, titleManuallySet)

  val titleFieldValue: String
    get() = if (titleManuallySet) manualTitle.orEmpty() else autoTitleFromBody(body)
}

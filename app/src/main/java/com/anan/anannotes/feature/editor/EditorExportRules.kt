package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.createNoteIfMeaningful
import com.anan.anannotes.core.model.NoteDraft

enum class EditorExportBlockReason {
  NOT_LOADED,
  EMPTY,
  COMPOSING,
  RECOVERY,
  SAVE_FAILED,
  CONFLICT,
}

sealed interface EditorExportDecision {
  data object PersistFirst : EditorExportDecision

  data class Ready(val noteId: String) : EditorExportDecision

  data class Blocked(val reason: EditorExportBlockReason) : EditorExportDecision
}

object EditorExportRules {
  fun evaluate(state: EditorUiState): EditorExportDecision {
    if (!state.isLoaded) return EditorExportDecision.Blocked(EditorExportBlockReason.NOT_LOADED)
    if (state.pendingRecovery != null) return EditorExportDecision.Blocked(EditorExportBlockReason.RECOVERY)
    if (state.saveStatus == EditorSaveStatus.CONFLICT || state.saveConflictRevision != null) {
      return EditorExportDecision.Blocked(EditorExportBlockReason.CONFLICT)
    }
    if (state.saveStatus == EditorSaveStatus.FAILED) {
      return EditorExportDecision.Blocked(EditorExportBlockReason.SAVE_FAILED)
    }
    if (state.hasActiveComposition) return EditorExportDecision.Blocked(EditorExportBlockReason.COMPOSING)

    val noteId = state.noteId
    if (!hasMeaningfulContent(state)) {
      return EditorExportDecision.Blocked(EditorExportBlockReason.EMPTY)
    }
    if (noteId != null && state.saveStatus in setOf(EditorSaveStatus.CLEAN, EditorSaveStatus.SAVED)) {
      return EditorExportDecision.Ready(noteId)
    }
    return EditorExportDecision.PersistFirst
  }

  private fun hasMeaningfulContent(state: EditorUiState): Boolean = createNoteIfMeaningful(
    NoteDraft(
      folderId = state.folderId,
      title = state.titleForSave,
      titleManuallySet = state.titleManuallySet,
      blocks = state.blocks,
      skinId = state.skinId,
      fontSizeSpOverride = state.fontSizeSpOverride,
      lineSpacingMultiplierOverride = state.lineSpacingMultiplierOverride,
    ),
    id = "editor-export-preview",
    nowMillis = 0L,
  ) != null
}

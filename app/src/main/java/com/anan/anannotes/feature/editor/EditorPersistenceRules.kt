package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.model.TextSelection

internal object EditorPersistenceRules {
  fun shouldFinalizeCompositionForExplicitSave(state: EditorUiState): Boolean = state.hasActiveComposition

  fun shouldPersistInput(
    previousComposing: TextSelection?,
    composing: TextSelection?,
    contentChanged: Boolean,
  ): Boolean = composing == null && (contentChanged || previousComposing != null)
}

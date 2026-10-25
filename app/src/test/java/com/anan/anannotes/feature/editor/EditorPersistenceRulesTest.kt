package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.model.TextSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPersistenceRulesTest {
  @Test
  fun explicitSaveFinalizesCompositionInEveryTextField() {
    val empty = EditorUiState()
    assertFalse(EditorPersistenceRules.shouldFinalizeCompositionForExplicitSave(empty))
    assertTrue(EditorPersistenceRules.shouldFinalizeCompositionForExplicitSave(empty.copy(composing = TextSelection(2, 4))))
    assertTrue(EditorPersistenceRules.shouldFinalizeCompositionForExplicitSave(empty.copy(titleComposing = TextSelection(2, 4))))
    assertTrue(
      EditorPersistenceRules.shouldFinalizeCompositionForExplicitSave(
        empty.copy(todoCompositions = mapOf("todo-1" to TextSelection(2, 4))),
      ),
    )
  }

  @Test
  fun doesNotPersistWhileImeCompositionIsActive() {
    assertFalse(
      EditorPersistenceRules.shouldPersistInput(
        previousComposing = null,
        composing = TextSelection(2, 4),
        contentChanged = true,
      ),
    )
  }

  @Test
  fun persistsWhenCompositionCommitsEvenIfCommittedTextIsUnchanged() {
    assertTrue(
      EditorPersistenceRules.shouldPersistInput(
        previousComposing = TextSelection(2, 4),
        composing = null,
        contentChanged = false,
      ),
    )
  }

  @Test
  fun persistsOrdinaryContentChangesButIgnoresSelectionOnlyUpdates() {
    assertTrue(EditorPersistenceRules.shouldPersistInput(previousComposing = null, composing = null, contentChanged = true))
    assertFalse(EditorPersistenceRules.shouldPersistInput(previousComposing = null, composing = null, contentChanged = false))
  }
}

package com.anan.anannotes.feature.privatemodule

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.feature.export.OutputTarget
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrivatePendingExportStateTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun safRequestRestoresFormatAttributionNoteAndTarget() {
    val restorationTester = StateRestorationTester(composeRule)
    val stateReference = AtomicReference<MutableState<PrivatePendingExportState>>()
    val expected = PrivatePendingExportState(
      format = ExportFormat.PDF,
      includeAttribution = true,
      noteId = PrivateNoteId("private-note-42"),
      target = OutputTarget.DocumentTree("content://documents/tree/primary%3ADocuments"),
    )

    restorationTester.setContent {
      val state = rememberPrivatePendingExportState()
      SideEffect { stateReference.set(state) }
    }
    composeRule.runOnIdle { stateReference.get().value = expected }

    restorationTester.emulateSavedInstanceStateRestore()

    composeRule.runOnIdle { assertEquals(expected, stateReference.get().value) }
  }
}

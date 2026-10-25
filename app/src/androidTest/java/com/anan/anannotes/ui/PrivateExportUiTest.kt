package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.feature.privatemodule.PrivateExportFormatSheet
import com.anan.anannotes.feature.privatemodule.PrivateExportHistoryRecord
import com.anan.anannotes.feature.privatemodule.PrivateExportHistoryScreen
import com.anan.anannotes.feature.privatemodule.PrivateExportPasswordDialog
import com.anan.anannotes.feature.privatemodule.PrivateExportPlaintextWarningDialog
import com.anan.anannotes.feature.privatemodule.PrivateNoteId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateExportUiTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun passwordDialogShowsCooldownAndDisablesVerificationUntilItExpires() {
    composeRule.setContent {
      MaterialTheme {
        PrivateExportPasswordDialog(
          errorMessage = null,
          retryAfterMillis = 4_000L,
          onSubmit = {},
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithTag("private-export-password-field").performTextInput("secret")
    composeRule.onNodeWithText("请等待 4 秒后再次验证").assertIsDisplayed()
    composeRule.onNodeWithTag("private-export-password-submit").assertIsNotEnabled()
  }

  @Test
  fun privateFormatPickerOffersSocialPngAndDeliversTheSelectedFormat() {
    val selected = mutableStateOf(mutableListOf<Pair<ExportFormat, Boolean>>())
    composeRule.setContent {
      MaterialTheme {
        PrivateExportFormatSheet(onSelect = { format, attribution -> selected.value.add(format to attribution) }, onDismiss = {})
      }
    }

    composeRule.onNodeWithTag("export-attribution-checkbox").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("private-export-format-png-portrait").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("private-export-format-txt").assertIsDisplayed().performClick()
    assertEquals(
      listOf(ExportFormat.PNG_SOCIAL_PORTRAIT to true, ExportFormat.TXT to false),
      selected.value,
    )
  }

  @Test
  fun privateExportRequiresPlaintextWarningConfirmation() {
    val confirmed = mutableStateOf(false)
    composeRule.setContent {
      MaterialTheme {
        PrivateExportPlaintextWarningDialog(
          errorMessage = null,
          isExporting = false,
          onConfirm = { confirmed.value = true },
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithText("导出文件将不再受安安简记私密模块保护").assertIsDisplayed()
    composeRule.onNodeWithTag("private-export-plaintext-confirm").performClick()
    assertTrue(confirmed.value)
  }

  @Test
  fun privateExportHistoryShowsOnlyThePrivateHistoryProvidedByUnlockedModule() {
    var wentBack = false
    composeRule.setContent {
      MaterialTheme {
        PrivateExportHistoryScreen(
          records = listOf(
            PrivateExportHistoryRecord(
              id = "history-1",
              noteId = PrivateNoteId("private-note-1"),
              noteTitle = "私密会议",
              format = ExportFormat.PDF,
              fileName = "私密会议_2026-10-01.pdf",
              destinationUri = "content://private/export/1",
              createdAtMillis = 1_790_812_800_000L,
              succeeded = true,
            ),
          ),
          onBack = { wentBack = true },
        )
      }
    }

    composeRule.onNodeWithTag("private-export-history-screen").assertIsDisplayed()
    composeRule.onNodeWithText("私密会议_2026-10-01.pdf").assertIsDisplayed()
    composeRule.onNodeWithText("笔记：私密会议 · PDF").assertIsDisplayed()
    composeRule.onNodeWithTag("private-export-history-back").performClick()
    assertTrue(wentBack)
  }
}

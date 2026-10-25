package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.repository.ExportStorageSummary
import com.anan.anannotes.data.repository.StorageOverview
import com.anan.anannotes.feature.settings.ExportHistoryScreen
import com.anan.anannotes.feature.settings.ExportHistoryUiState
import com.anan.anannotes.feature.settings.StorageOverviewScreen
import com.anan.anannotes.feature.settings.StorageOverviewUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportHistoryAndStorageScreenTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun historyShowsReceiptActionsFailureCodeAndClearConfirmation() {
    var clearCount = 0
    var refreshCount = 0
    var backCount = 0
    val opened = mutableListOf<String>()
    val shared = mutableListOf<String>()
    val records = listOf(record("success"), record("failed").copy(succeeded = false, errorCode = "AN-EXP-003", destinationUri = ""))
    composeRule.setContent {
      MaterialTheme {
        ExportHistoryScreen(
          state = ExportHistoryUiState(records = records, isLoading = false),
          onBack = { backCount++ },
          onClear = { clearCount++ },
          onRefresh = { refreshCount++ },
          onOpen = { opened += it.id },
          onShare = { shared += it.id },
        )
      }
    }

    composeRule.onNodeWithTag("export-history-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("export-history-page-header").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("返回").performClick()
    composeRule.onNodeWithTag("export-history-refresh").performClick()
    composeRule.onNodeWithTag("export-history-open-success").performScrollTo().performClick()
    composeRule.onNodeWithTag("export-history-share-success").performClick()
    composeRule.onNodeWithText("AN-EXP-003", substring = true).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("export-history-open-failed").assertDoesNotExist()
    composeRule.onNodeWithTag("export-history-clear").performClick()
    composeRule.runOnIdle { assertEquals(0, clearCount) }
    composeRule.onNodeWithText("不会删除", substring = true).assertIsDisplayed()
    composeRule.onNodeWithTag("export-history-confirm-clear").performClick()

    composeRule.runOnIdle {
      assertEquals(1, clearCount)
      assertEquals(1, refreshCount)
      assertEquals(1, backCount)
      assertEquals(listOf("success"), opened)
      assertEquals(listOf("success"), shared)
    }
    assertEquals(0, composeRule.onAllNodesWithText("私密", substring = true).fetchSemanticsNodes().size)
  }

  @Test
  fun emptyHistoryDisablesClearAndShowsFeedback() {
    composeRule.setContent {
      MaterialTheme {
        ExportHistoryScreen(
          state = ExportHistoryUiState(isLoading = false, message = "历史读取失败，请重试"),
          onBack = {},
          onClear = {},
          onRefresh = {},
          onOpen = {},
          onShare = {},
        )
      }
    }

    composeRule.onNodeWithTag("export-history-clear").assertIsNotEnabled()
    composeRule.onNodeWithText("暂无导出记录").assertIsDisplayed()
    composeRule.onNodeWithText("历史读取失败，请重试").assertIsDisplayed()
  }

  @Test
  fun storageShowsPartialExportCoverageAndRoutesOnlyPreviewCleanup() {
    var clearCount = 0
    var refreshCount = 0
    var backCount = 0
    composeRule.setContent {
      MaterialTheme {
        StorageOverviewScreen(
          state = StorageOverviewUiState(
            overview = StorageOverview(
              databaseBytes = 15L,
              assetBytes = 24L,
              previewBytes = 36L,
              exportedFiles = ExportStorageSummary(
                recordedFileCount = 3,
                knownBytes = 128L,
                knownSizeCount = 1,
                unknownSizeCount = 1,
                unavailableCount = 1,
              ),
            ),
            isLoading = false,
          ),
          onBack = { backCount++ },
          onRefresh = { refreshCount++ },
          onClearPreviewCache = { clearCount++ },
        )
      }
    }

    composeRule.onNodeWithTag("storage-overview-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("storage-overview-page-header").assertIsDisplayed()
    composeRule.onNodeWithTag("storage-overview-internal-total").assertTextContains("75 B", substring = true)
    composeRule.onNodeWithTag("storage-overview-refresh").performClick()
    composeRule.onNodeWithContentDescription("返回").performClick()
    composeRule.onNodeWithText("大小未知", substring = true).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("不可访问", substring = true).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("不计入", substring = true).performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("storage-overview-clear-previews").performScrollTo().performClick()

    composeRule.runOnIdle {
      assertEquals(1, clearCount)
      assertEquals(1, refreshCount)
      assertEquals(1, backCount)
    }
    assertEquals(0, composeRule.onAllNodesWithText("私密", substring = true).fetchSemanticsNodes().size)
  }

  private fun record(id: String) = ExportHistoryModel(
    id = id,
    noteId = "ordinary-note",
    format = ExportFormat.TXT,
    fileName = "$id.txt",
    destinationUri = "content://exports/$id",
    createdAtMillis = 1234L,
    succeeded = true,
  )
}

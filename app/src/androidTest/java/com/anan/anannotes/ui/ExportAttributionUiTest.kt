package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.feature.export.ExportFormatSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportAttributionUiTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun attributionStartsOffAndIsPassedOnlyForPdfAndPng() {
    val selections = mutableStateListOf<Pair<ExportFormat, Boolean>>()
    composeRule.setContent {
      MaterialTheme {
        ExportFormatSheet(
          title = "导出笔记",
          sheetTestTag = "test-export-sheet",
          listTestTag = "test-export-list",
          exportDirectoryLabel = "选择保存文件夹",
          onSelectDocumentFormat = { format, include -> selections += format to include },
          onSelectPictureFormat = { format, include -> selections += format to include },
          onChangeExportDirectory = {},
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithTag("export-attribution-checkbox").assertIsOff()
    composeRule.onNodeWithTag("export-format-txt").performScrollTo().performClick()
    composeRule.onNodeWithTag("export-attribution-checkbox").performClick()
    composeRule.onNodeWithTag("export-format-pdf").performScrollTo().performClick()
    composeRule.onNodeWithTag("test-export-list").performScrollToNode(hasTestTag("export-format-markdown-zip"))
    composeRule.onNodeWithTag("export-format-markdown-zip").performClick()

    assertEquals(
      listOf(
        ExportFormat.TXT to false,
        ExportFormat.PDF to true,
        ExportFormat.MARKDOWN_ZIP to false,
      ),
      selections.toList(),
    )
  }
}

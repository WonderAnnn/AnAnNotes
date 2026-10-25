package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.anan.anannotes.feature.home.ALL_NOTES_FOLDER_ID
import com.anan.anannotes.feature.home.FolderBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FolderChipFeedbackTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun folderChipPressFeedbackDoesNotPaintOutsideRoundedCorners() {
    composeRule.setContent {
      MaterialTheme {
        FolderBar(
          folders = emptyList(),
          selectedFolderId = ALL_NOTES_FOLDER_ID,
          onSelectFolder = {},
          onCreateFolder = {},
          onRenameFolder = { _, _ -> },
          onDeleteFolder = {},
          modifier = Modifier,
        )
      }
    }
    composeRule.waitForIdle()

    val chip = composeRule.onNodeWithTag("folder-uncategorized")
    val cornerBeforePress = chip.captureToImage().toPixelMap()[0, 0]

    composeRule.mainClock.autoAdvance = false
    chip.performTouchInput {
      down(Offset(0.5f, 0.5f))
    }
    repeat(18) { composeRule.mainClock.advanceTimeByFrame() }
    val cornerDuringPress = chip.captureToImage().toPixelMap()[0, 0]
    chip.performTouchInput { up() }
    composeRule.mainClock.autoAdvance = true

    assertEquals("按压反馈应裁切在圆角形状内", cornerBeforePress, cornerDuringPress)
  }
}

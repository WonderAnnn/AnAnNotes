package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.DEFAULT_NOTE_SKIN_ID
import com.anan.anannotes.feature.ocr.OcrImportMode
import com.anan.anannotes.feature.ocr.OcrImportModeSheet
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrImportModeSheetTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun offersThreeImportModesAndReturnsTheSelectedMode() {
    var selected: OcrImportMode? = null
    composeRule.setContent {
      MaterialTheme {
        OcrImportModeSheet(
          skin = NoteSkinCatalog.byId(DEFAULT_NOTE_SKIN_ID),
          onSelect = { selected = it },
          onDismiss = {},
        )
      }
    }

    composeRule.onNodeWithTag("ocr-import-image-only").assertIsDisplayed()
    composeRule.onNodeWithTag("ocr-import-image-and-text").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("ocr-import-text-only").assertIsDisplayed()
    assertEquals(OcrImportMode.IMAGE_AND_TEXT, selected)
  }
}

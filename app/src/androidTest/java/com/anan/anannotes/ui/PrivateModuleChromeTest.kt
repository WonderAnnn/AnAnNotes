package com.anan.anannotes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.feature.privatemodule.PrivateModuleHeader
import com.anan.anannotes.feature.privatemodule.PrivateModuleNavigation
import com.anan.anannotes.feature.privatemodule.PrivateSection
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateModuleChromeTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun privateNavigationShowsIconsAndSwitchesSections() {
    var selected by mutableStateOf(PrivateSection.NOTES)
    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        PrivateModuleNavigation(
          section = selected,
          onSelectSection = { selected = it },
        )
      }
    }

    composeRule.onNodeWithTag("private-nav-notes").assertIsSelected()
    composeRule.onNodeWithTag("private-nav-todos").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("私密笔记", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("私密待办", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("private-nav-todos").performClick()
    composeRule.onNodeWithTag("private-nav-todos").assertIsSelected()
  }

  @Test
  fun privateHeaderUsesAccessibleMinimalBackAndLockActions() {
    var wentBack = false
    var locked = false
    composeRule.setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        PrivateModuleHeader(
          onBack = { wentBack = true },
          onLock = { locked = true },
        )
      }
    }

    composeRule.onNodeWithTag("private-back-button").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("private-lock-button").assertIsDisplayed().performClick()
    composeRule.runOnIdle {
      assertTrue(wentBack)
      assertTrue(locked)
    }
  }
}

package com.anan.anannotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class SearchAndSettingsScreenTest {
  @get:Rule
  val composeRule = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()

  @Test
  fun searchUsesCompactSafeAreaHeaderAndRoundedTonalField() {
    composeRule.onNodeWithTag("search-entry").performClick()

    composeRule.onNodeWithTag("search-page-header").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
    composeRule.onNodeWithTag("search-field-surface").assertIsDisplayed()
    composeRule.onNodeWithTag("search-query").assertIsDisplayed()
    assertTrue(composeRule.onAllNodesWithText("返回").fetchSemanticsNodes().isEmpty())

    val fieldHeight = composeRule.onNodeWithTag("search-field-surface").fetchSemanticsNode().boundsInRoot.height
    val density = composeRule.activity.resources.displayMetrics.density
    assert(fieldHeight in (50f * density)..(58f * density)) { "搜索框应保持约 54dp 的紧凑高度，实际像素高度为 $fieldHeight" }
  }

  @Test
  fun settingsUsesSameCompactBackHeaderAndContinuousSliders() {
    composeRule.onNodeWithTag("home-settings-button").performClick()

    composeRule.onNodeWithTag("settings-page-header").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
    composeRule.onNodeWithTag("default-font-size-slider").assertIsDisplayed()
    composeRule.onNodeWithTag("default-line-spacing-slider").assertIsDisplayed()
    composeRule.onNodeWithTag("display-settings-preview").assertIsDisplayed()
    assertTrue(composeRule.onAllNodesWithText("返回").fetchSemanticsNodes().isEmpty())
  }
}

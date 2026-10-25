package com.anan.anannotes.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Before
  fun returnToHomeBetweenNavigationTests() {
    composeRule.waitForIdle()
    if (composeRule.onAllNodesWithTag("home-title-header").fetchSemanticsNodes().isEmpty()) {
      composeRule.activityRule.scenario.onActivity { activity ->
        activity.onBackPressedDispatcher.onBackPressed()
      }
      composeRule.waitForIdle()
    }
  }

  @Test
  fun quickCreateOpensEditor() {
    composeRule.onNodeWithTag("new-note-button").performClick()
    composeRule.onNodeWithTag("screen-editor").fetchSemanticsNode()
  }

  @Test
  fun settingsOpenHistoryAndStorageThenReturnWithoutLosingNavigation() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    composeRule.onNodeWithTag("settings-export-history").performScrollTo().performClick()
    composeRule.onNodeWithTag("export-history-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("page-back-arrow").performClick()
    composeRule.onNodeWithTag("settings-storage-overview").performScrollTo().performClick()
    composeRule.onNodeWithTag("storage-overview-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("page-back-arrow").performClick()
    composeRule.onNodeWithTag("settings-page-header").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun settingsExposeOperationalEncryptedBackupAndRestoreEntry() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    composeRule.onNodeWithTag("settings-backup-restore").performScrollTo().performClick()
    composeRule.onNodeWithTag("backup-restore-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("backup-choose-destination").assertIsDisplayed()
    composeRule.onNodeWithTag("backup-choose-source").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("backup-inspect-action").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun settingsExposePrivacyNoticeAndExplainStorageBoundaries() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    composeRule.onNodeWithTag("settings-privacy-notice").performScrollTo().performClick()

    composeRule.onNodeWithTag("privacy-notice-screen").assertIsDisplayed()
    composeRule.onNodeWithText("本应用不申请互联网权限", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("普通笔记未做应用级加密", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("文件提供商可能会同步到云端", substring = true).assertIsDisplayed()

    composeRule.onNodeWithTag("page-back-arrow").performClick()
    composeRule.onNodeWithTag("settings-page-header").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun homeOffersDocumentImportWithoutChangingQuickCreate() {
    composeRule.onNodeWithTag("home-import-button").assertIsDisplayed()
    composeRule.onNodeWithTag("new-note-button").assertIsDisplayed()
  }

  @Test
  fun independentHomeEntryOpensPrivateAccessPage() {
    composeRule.onNodeWithTag("private-entry-button").performClick()
    composeRule.onNodeWithTag("private-module-root").fetchSemanticsNode()
    composeRule.onNodeWithTag("private-access-form").fetchSemanticsNode()
  }
}

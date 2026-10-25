package com.anan.anannotes.ui

import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarAppearanceTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun changingDisplayThemeUpdatesStatusAndNavigationIconsAndRestoresPreference() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    val modes = listOf("system", "light", "dark")
    val originalMode = modes.single { mode ->
      composeRule.onNodeWithTag("theme-mode-$mode").fetchSemanticsNode()
        .config[SemanticsProperties.Selected]
    }

    try {
      selectTheme("light")
      composeRule.runOnIdle {
        val controller = WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
        assertTrue("浅色界面需要深色状态栏图标", controller.isAppearanceLightStatusBars)
        assertTrue("浅色界面需要深色导航栏图标", controller.isAppearanceLightNavigationBars)
      }

      selectTheme("dark")
      composeRule.runOnIdle {
        val controller = WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
        assertFalse("深色界面需要浅色状态栏图标", controller.isAppearanceLightStatusBars)
        assertFalse("深色界面需要浅色导航栏图标", controller.isAppearanceLightNavigationBars)
      }

      selectTheme("light")
      assertSystemBarIcons(darkIcons = true)
      composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
      assertSystemBarIcons(darkIcons = true)
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("home-title-header").fetchSemanticsNodes().isNotEmpty() ||
          composeRule.onAllNodesWithTag("home-selection-header").fetchSemanticsNodes().isNotEmpty()
      }
      val homeHeaderVisible = composeRule.onAllNodesWithTag("home-title-header").fetchSemanticsNodes().isNotEmpty()
      val selectionHeaderVisible = composeRule.onAllNodesWithTag("home-selection-header").fetchSemanticsNodes().isNotEmpty()
      assertTrue("返回后应显示首页；home=$homeHeaderVisible selection=$selectionHeaderVisible", homeHeaderVisible || selectionHeaderVisible)
      if (selectionHeaderVisible) composeRule.onNodeWithText("取消").performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithTag("private-entry-button").performClick()
      composeRule.onNodeWithTag("private-module-root").assertIsDisplayed()
      assertSystemBarIcons(darkIcons = false)
      composeRule.onNodeWithTag("private-back-button").performClick()
      composeRule.onNodeWithTag("home-title-header").assertIsDisplayed()
      assertSystemBarIcons(darkIcons = true)
    } finally {
      returnToHome()
      composeRule.onNodeWithTag("home-settings-button").performClick()
      selectTheme(originalMode)
      composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
    }
  }

  @Test
  fun editorSkinControlsStatusBarIconsAndRestoresGlobalThemeOnExit() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    val modes = listOf("system", "light", "dark")
    val originalMode = modes.single { mode ->
      composeRule.onNodeWithTag("theme-mode-$mode").fetchSemanticsNode()
        .config[SemanticsProperties.Selected]
    }

    try {
      selectTheme("light")
      composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
      assertSystemBarIcons(darkIcons = true)

      composeRule.onNodeWithTag("new-note-button").performClick()
      composeRule.onNodeWithTag("editor-skin-button").performClick()
      composeRule.onNodeWithTag("skin-picker-options")
        .performScrollToNode(hasTestTag("skin-option-moonlit-sky"))
      composeRule.onNodeWithTag("skin-option-moonlit-sky").performClick()
      composeRule.waitForIdle()
      assertStatusBarIcons(darkIcons = false)
      composeRule.runOnIdle {
        val controller = WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
        assertTrue("编辑页的导航栏图标继续遵循全局浅色主题", controller.isAppearanceLightNavigationBars)
      }

      composeRule.onNodeWithTag("skin-picker-options")
        .performScrollToNode(hasTestTag("skin-option-minimal-white"))
      composeRule.onNodeWithTag("skin-option-minimal-white").performClick()
      composeRule.waitForIdle()
      assertStatusBarIcons(darkIcons = true)

      composeRule.onNodeWithTag("editor-back").performClick()
      composeRule.onNodeWithTag("home-title-header").assertIsDisplayed()
      assertSystemBarIcons(darkIcons = true)

      composeRule.onNodeWithTag("private-entry-button").performClick()
      composeRule.onNodeWithTag("private-module-root").assertIsDisplayed()
      assertSystemBarIcons(darkIcons = false)
      composeRule.onNodeWithTag("private-back-button").performClick()
      composeRule.onNodeWithTag("home-title-header").assertIsDisplayed()
      assertSystemBarIcons(darkIcons = true)
    } finally {
      returnToHome()
      composeRule.onNodeWithTag("home-settings-button").performClick()
      selectTheme(originalMode)
      composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
    }
  }

  @Test
  fun editorActivityRecreationRestoresGlobalNavigationIconsAndKeepsNoteStatusIcons() {
    val systemIsDark =
      (composeRule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    composeRule.onNodeWithTag("home-settings-button").performClick()
    val modes = listOf("system", "light", "dark")
    val originalMode = modes.single { mode ->
      composeRule.onNodeWithTag("theme-mode-$mode").fetchSemanticsNode()
        .config[SemanticsProperties.Selected]
    }
    val appMode = if (systemIsDark) "light" else "dark"
    val globalDarkIcons = appMode == "light"
    val noteSkinId = if (globalDarkIcons) "moonlit-sky" else "minimal-white"
    val noteStatusDarkIcons = !globalDarkIcons

    try {
      selectTheme(appMode)
      composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
      assertSystemBarIcons(darkIcons = globalDarkIcons)

      composeRule.onNodeWithTag("new-note-button").performClick()
      composeRule.onNodeWithTag("editor-skin-button").performClick()
      composeRule.onNodeWithTag("skin-picker-options")
        .performScrollToNode(hasTestTag("skin-option-$noteSkinId"))
      composeRule.onNodeWithTag("skin-option-$noteSkinId").performClick()
      composeRule.onNodeWithTag("skin-picker-close").performClick()
      assertStatusBarIcons(darkIcons = noteStatusDarkIcons)
      assertNavigationBarIcons(darkIcons = globalDarkIcons)

      composeRule.activityRule.scenario.recreate()
      composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithTag("screen-editor").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.waitForIdle()
      assertStatusBarIcons(darkIcons = noteStatusDarkIcons)
      assertNavigationBarIcons(darkIcons = globalDarkIcons)

      composeRule.onNodeWithTag("editor-back").performClick()
      composeRule.onNodeWithTag("home-title-header").assertIsDisplayed()
      assertSystemBarIcons(darkIcons = globalDarkIcons)
    } finally {
      returnToHome()
      composeRule.onNodeWithTag("home-settings-button").performClick()
      selectTheme(originalMode)
      composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
    }
  }

  private fun selectTheme(mode: String) {
    composeRule.onNodeWithTag("theme-mode-$mode").performScrollTo().performClick()
    composeRule.waitForIdle()
  }

  private fun assertSystemBarIcons(darkIcons: Boolean) {
    composeRule.runOnIdle {
      val controller = WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
      assertEquals("状态栏图标外观应匹配当前页面", darkIcons, controller.isAppearanceLightStatusBars)
      assertEquals("导航栏图标外观应匹配当前页面", darkIcons, controller.isAppearanceLightNavigationBars)
    }
  }

  private fun assertStatusBarIcons(darkIcons: Boolean) {
    composeRule.runOnIdle {
      val controller = WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
      assertEquals("状态栏图标外观应匹配当前笔记皮肤", darkIcons, controller.isAppearanceLightStatusBars)
    }
  }

  private fun assertNavigationBarIcons(darkIcons: Boolean) {
    composeRule.runOnIdle {
      val controller = WindowCompat.getInsetsController(composeRule.activity.window, composeRule.activity.window.decorView)
      assertEquals("导航栏图标继续匹配全局主题", darkIcons, controller.isAppearanceLightNavigationBars)
    }
  }

  private fun returnToHome() {
    repeat(4) {
      if (composeRule.onAllNodesWithTag("home-title-header").fetchSemanticsNodes().isNotEmpty()) return
      composeRule.activityRule.scenario.onActivity { activity ->
        activity.onBackPressedDispatcher.onBackPressed()
      }
      composeRule.waitForIdle()
    }
  }
}

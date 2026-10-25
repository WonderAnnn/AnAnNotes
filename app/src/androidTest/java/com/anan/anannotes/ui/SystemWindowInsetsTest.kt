package com.anan.anannotes.ui

import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.MainActivity
import com.anan.anannotes.feature.privatemodule.applyPrivateScreenshotPolicy
import com.anan.anannotes.feature.privatemodule.restoreWindowFlag
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemWindowInsetsTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun settingsAndSearchStayInsideRealSystemBarAndCutoutInsets() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    composeRule.onNodeWithTag("settings-screen").assertIsDisplayed()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("settings-page-header").performScrollTo().assertIsDisplayed()

    val insets = readSafeSystemInsets()
    val settingsRoot = composeRule.onNodeWithTag("settings-screen").fetchSemanticsNode().boundsInRoot
    val settingsHeader = composeRule.onNodeWithTag("settings-page-header").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "设置标题需避开真实顶部状态栏/切口 inset=$insets root=$settingsRoot header=$settingsHeader",
      settingsHeader.top >= settingsRoot.top + insets.top,
    )
    assertTrue(
      "设置标题需避开真实左侧切口 inset=$insets root=$settingsRoot header=$settingsHeader",
      settingsHeader.left >= settingsRoot.left + insets.left,
    )
    Log.i("AnAnE1Bounds", "settingsRoot=$settingsRoot settingsHeader=$settingsHeader safe=$insets")
    captureSettingsScreenshotWhenRequested()
    composeRule.onNodeWithTag("clear-preview-cache").performScrollTo().assertIsDisplayed()
    val cacheAction = composeRule.onNodeWithTag("clear-preview-cache").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "设置页末尾按钮需避开真实底部系统栏 inset=$insets root=$settingsRoot action=$cacheAction",
      cacheAction.bottom <= settingsRoot.bottom - insets.bottom,
    )

    composeRule.onNodeWithTag("page-back-arrow").performScrollTo().performClick()
    composeRule.onNodeWithTag("search-entry").performClick()
    composeRule.onNodeWithTag("screen-search").assertIsDisplayed()
    val searchRoot = composeRule.onNodeWithTag("screen-search").fetchSemanticsNode().boundsInRoot
    val searchHeader = composeRule.onNodeWithTag("search-page-header").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "搜索标题需避开真实顶部状态栏/切口 inset=$insets root=$searchRoot header=$searchHeader",
      searchHeader.top >= searchRoot.top + insets.top,
    )
    assertTrue(
      "搜索标题需避开真实左侧切口 inset=$insets root=$searchRoot header=$searchHeader",
      searchHeader.left >= searchRoot.left + insets.left,
    )
  }

  @Test
  fun homeAndTodoStayInsideRealInsetsAndKeepBottomNavigationGeometry() {
    composeRule.waitForIdle()
    val insets = readSafeSystemInsets()
    val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    val homeHeader = composeRule.onNodeWithTag("home-title-header").fetchSemanticsNode().boundsInRoot
    val homeTabBounds = composeRule.onNodeWithTag("nav-home").fetchSemanticsNode().boundsInRoot
    val todoTabBounds = composeRule.onNodeWithTag("nav-todo").fetchSemanticsNode().boundsInRoot

    assertTrue(
      "首页标题应位于顶部和左右切口安全区内 inset=$insets root=$root header=$homeHeader",
      homeHeader.top >= root.top + insets.top &&
        homeHeader.left >= root.left + insets.left &&
        homeHeader.right <= root.right - insets.right,
    )
    assertTrue(
      "首页底部导航项应位于系统导航栏上方 inset=$insets root=$root tab=$homeTabBounds",
      homeTabBounds.bottom <= root.bottom - insets.bottom,
    )

    composeRule.onNodeWithTag("nav-todo").performClick()
    composeRule.waitForIdle()

    val todoHeader = composeRule.onNodeWithTag("todo-title-header").fetchSemanticsNode().boundsInRoot
    val todoTabBoundsAfterSwitch = composeRule.onNodeWithTag("nav-todo").fetchSemanticsNode().boundsInRoot
    val homeTabBoundsAfterSwitch = composeRule.onNodeWithTag("nav-home").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "待办标题应位于顶部和左右切口安全区内 inset=$insets root=$root header=$todoHeader",
      todoHeader.top >= root.top + insets.top &&
        todoHeader.left >= root.left + insets.left &&
        todoHeader.right <= root.right - insets.right,
    )
    assertTrue(
      "待办底部导航项应位于系统导航栏上方 inset=$insets root=$root tab=$todoTabBoundsAfterSwitch",
      todoTabBoundsAfterSwitch.bottom <= root.bottom - insets.bottom,
    )
    assertEquals("切到待办页后笔记导航项 bounds 不应移动", homeTabBounds, homeTabBoundsAfterSwitch)
    assertEquals("切到待办页后待办导航项 bounds 不应移动", todoTabBounds, todoTabBoundsAfterSwitch)
  }

  @Test
  fun privateAuthenticationCanvasIsMediumGraphiteRatherThanNearBlack() {
    composeRule.onNodeWithTag("private-entry-button").performClick()
    val image = composeRule.onNodeWithTag("private-module-root").captureToImage().toPixelMap()
    val pixel = image[1, image.height / 2]
    val luminance = relativeLuminance(pixel.red, pixel.green, pixel.blue)

    assertTrue(
      "私密认证页背景应提亮为中性深灰，当前采样像素=$pixel, luminance=$luminance",
      luminance >= 0.035,
    )
  }

  private fun relativeLuminance(red: Float, green: Float, blue: Float): Double {
    fun linear(channel: Float): Double =
      if (channel <= 0.04045f) channel / 12.92 else Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4)
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
  }

  @Test
  fun privateAccessFormStaysInsideRealSystemInsetsAndKeepsScreenshotProtection() {
    composeRule.onNodeWithTag("private-entry-button").performClick()
    composeRule.onNodeWithTag("private-module-root").assertIsDisplayed()
    composeRule.onNodeWithTag("private-access-form").assertIsDisplayed()
    composeRule.waitForIdle()

    val insets = readSafeSystemInsets()
    val root = composeRule.onNodeWithTag("private-module-root").fetchSemanticsNode().boundsInRoot
    val backButton = composeRule.onNodeWithTag("private-back-button").fetchSemanticsNode().boundsInRoot
    val submitButton = composeRule.onNodeWithTag("private-auth-submit").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "私密页返回控件必须避开顶部状态栏/切口 inset=$insets root=$root back=$backButton",
      backButton.top >= root.top + insets.top,
    )
    assertTrue(
      "私密页返回控件必须避开左侧切口 inset=$insets root=$root back=$backButton",
      backButton.left >= root.left + insets.left,
    )
    assertTrue(
      "私密页主操作必须避开底部导航/手势区 inset=$insets root=$root submit=$submitButton",
      submitButton.bottom <= root.bottom - insets.bottom,
    )
    composeRule.runOnIdle {
      val flags = composeRule.activity.window.attributes.flags
      assertTrue(
        "开发版私密页前台应允许截图",
        flags and WindowManager.LayoutParams.FLAG_SECURE == 0,
      )
    }
  }

  @Test
  fun debugWindowAllowsCaptureThroughoutPrivateModuleAndPreservesOtherFlags() {
    val window = composeRule.activity.window
    val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
    var originalFlags = 0
    try {
      composeRule.runOnUiThread {
        originalFlags = window.attributes.flags
        val otherFlags = originalFlags and secureFlag.inv()

        applyPrivateScreenshotPolicy(
          window = window,
          isDebuggable = true,
        )
        assertEquals("Debug 已解锁私密内容应允许系统截图", 0, window.attributes.flags and secureFlag)
        assertEquals("放开截图不能更改其他窗口标志", otherFlags, window.attributes.flags and secureFlag.inv())

        applyPrivateScreenshotPolicy(
          window = window,
          isDebuggable = true,
        )
        assertEquals("Debug 锁定状态仍应允许系统截图", 0, window.attributes.flags and secureFlag)
      }
    } finally {
      composeRule.runOnUiThread {
        window.setFlags(
          restoreWindowFlag(window.attributes.flags, originalFlags, secureFlag),
          secureFlag,
        )
      }
    }
  }

  private fun readSafeSystemInsets(): Insets {
    var safeInsets: Insets? = null
    var cutoutInsets: Insets? = null
    composeRule.runOnIdle {
      val rootInsets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
        ?: error("窗口尚未提供根 Insets")
      val safeTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
      safeInsets = rootInsets.getInsets(safeTypes)
      cutoutInsets = rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
    }
    val result = requireNotNull(safeInsets)
    Log.i("AnAnE1Insets", "api=${android.os.Build.VERSION.SDK_INT} safe=$result cutout=$cutoutInsets")
    return result
  }

  private fun captureSettingsScreenshotWhenRequested() {
    val captureRequested = InstrumentationRegistry.getArguments().getString("captureE1Screenshot")?.toBooleanStrictOrNull() == true
    if (!captureRequested) return
    composeRule.waitForIdle()
    val screenshotPath = "/sdcard/Download/anan-e1-settings-real-window.png"
    val commandOutput = InstrumentationRegistry.getInstrumentation().uiAutomation
      .executeShellCommand("screencap -p $screenshotPath")
    android.os.ParcelFileDescriptor.AutoCloseInputStream(commandOutput).use { input ->
      input.copyTo(ByteArrayOutputStream())
    }
    Log.i("AnAnE1Screenshot", screenshotPath)
  }
}

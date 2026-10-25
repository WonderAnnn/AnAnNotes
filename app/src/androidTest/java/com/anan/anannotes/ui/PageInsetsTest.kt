package com.anan.anannotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold
import com.anan.anannotes.ui.theme.AnAnNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageInsetsTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun secondaryContentRespectsTopSideAndBottomInsetsExactlyOnce() {
    val simulatedInsets = WindowInsets(left = 53, top = 71, right = 47, bottom = 83)
    var baseDensity = 1f
    composeRule.setContent {
      baseDensity = LocalDensity.current.density
      AnAnNotesTheme {
        PageScaffold(
          modifier = Modifier.testTag("secondary-page-root"),
          windowInsets = simulatedInsets,
        ) {
          Column(
            modifier = Modifier.fillMaxSize()
              .windowInsetsPadding(simulatedInsets)
              .testTag("secondary-page-safe-content"),
          ) {
            CompactPageHeader("存储概览", onBack = {}, testTag = "secondary-page-header")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {}, modifier = Modifier.testTag("secondary-page-last-action")) {
              Text("清理")
            }
          }
        }
      }
    }

    val page = composeRule.onNodeWithTag("secondary-page-root").fetchSemanticsNode().boundsInRoot
    val content = composeRule.onNodeWithTag("secondary-page-safe-content").fetchSemanticsNode().boundsInRoot
    val header = composeRule.onNodeWithTag("secondary-page-header").fetchSemanticsNode().boundsInRoot
    val lastAction = composeRule.onNodeWithTag("secondary-page-last-action").fetchSemanticsNode().boundsInRoot
    val backButton = composeRule.onNodeWithTag("page-back-arrow").fetchSemanticsNode().boundsInRoot

    assertEquals("左侧安全区应只应用一次", 53f, content.left - page.left, 1f)
    assertEquals("顶部安全区应只应用一次", 71f, content.top - page.top, 1f)
    assertEquals("右侧安全区应只应用一次", 47f, page.right - content.right, 1f)
    assertEquals("底部安全区应只应用一次", 83f, page.bottom - content.bottom, 1f)
    assertTrue("返回标题栏不能进入顶部/左侧切口区", header.left >= content.left && header.top >= content.top)
    assertTrue("最后一个操作不能被底部系统栏挡住", lastAction.bottom <= content.bottom)
    assertTrue("返回箭头触摸区域宽度至少 48dp；actual=${backButton.width / baseDensity}dp density=$baseDensity", backButton.width / baseDensity >= 48f)
    assertTrue("返回箭头触摸区域高度至少 48dp；actual=${backButton.height / baseDensity}dp density=$baseDensity", backButton.height / baseDensity >= 48f)
    composeRule.onNodeWithTag("page-back-arrow").performClick()
  }

  @Test
  fun zeroInsetsDoNotAddArtificialPageSpacing() {
    val zeroInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)
    composeRule.setContent {
      AnAnNotesTheme {
        PageScaffold(
          modifier = Modifier.testTag("zero-insets-page-root"),
          windowInsets = zeroInsets,
        ) {
          Box(Modifier.fillMaxSize().testTag("zero-insets-page-content"))
        }
      }
    }

    val page = composeRule.onNodeWithTag("zero-insets-page-root").fetchSemanticsNode().boundsInRoot
    val content = composeRule.onNodeWithTag("zero-insets-page-content").fetchSemanticsNode().boundsInRoot
    assertEquals("零 Insets 时内容顶边不应增加额外留白", 0f, content.top - page.top, 0f)
    assertEquals("零 Insets 时内容左边不应增加额外留白", 0f, content.left - page.left, 0f)
    assertEquals("零 Insets 时内容右边不应增加额外留白", 0f, page.right - content.right, 0f)
    assertEquals("零 Insets 时内容底边不应增加额外留白", 0f, page.bottom - content.bottom, 0f)
  }

  @Test
  fun headerAndActionsStayInsideSafeAreaAtTwoHundredPercentFontScale() {
    val simulatedInsets = WindowInsets(left = 61, top = 73, right = 59, bottom = 89)
    var baseDensity = 1f
    var appliedFontScale = 1f
    val title = "导出记录和存储概览与全面屏安全区适配设置"
    composeRule.setContent {
      val density = LocalDensity.current
      baseDensity = density.density
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
        appliedFontScale = LocalDensity.current.fontScale
        AnAnNotesTheme {
          Box(Modifier.width(320.dp).fillMaxHeight()) {
            PageScaffold(
              modifier = Modifier.testTag("large-text-page-root"),
              windowInsets = simulatedInsets,
            ) {
              Column(
                modifier = Modifier.fillMaxSize()
                  .windowInsetsPadding(simulatedInsets)
                  .padding(horizontal = 12.dp)
                  .testTag("large-text-page-content"),
              ) {
                CompactPageHeader(title, onBack = {}, testTag = "large-text-page-header")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {}, modifier = Modifier.testTag("large-text-page-last-action")) {
                  Text("清理预览缓存")
                }
              }
            }
          }
        }
      }
    }

    val page = composeRule.onNodeWithTag("large-text-page-root").fetchSemanticsNode().boundsInRoot
    val content = composeRule.onNodeWithTag("large-text-page-content").fetchSemanticsNode().boundsInRoot
    val header = composeRule.onNodeWithTag("large-text-page-header").fetchSemanticsNode().boundsInRoot
    val titleBounds = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
    val action = composeRule.onNodeWithTag("large-text-page-last-action").fetchSemanticsNode().boundsInRoot
    val backButton = composeRule.onNodeWithTag("page-back-arrow").fetchSemanticsNode().boundsInRoot
    assertEquals(61f + 12f * baseDensity, content.left - page.left, 2f)
    assertEquals(73f, content.top - page.top, 1f)
    assertEquals(59f, page.right - (content.right + 12f * baseDensity), 2f)
    assertEquals(89f, page.bottom - content.bottom, 1f)
    assertTrue(
      "大字体标题必须自然增高；fontScale=$appliedFontScale，pageWidth=${page.width / baseDensity}dp，contentWidth=${content.width / baseDensity}dp，headerWidth=${header.width / baseDensity}dp，headerHeight=${header.height / baseDensity}dp，titleWidth=${titleBounds.width / baseDensity}dp，titleHeight=${titleBounds.height / baseDensity}dp",
      header.height / baseDensity > 52f,
    )
    assertTrue("大字体操作按钮必须留在底部安全区内", action.bottom <= content.bottom)
    assertTrue("200% 字号下返回箭头触摸区宽度至少 48dp；actual=${backButton.width / baseDensity}dp density=$baseDensity", backButton.width / baseDensity >= 48f)
    assertTrue("200% 字号下返回箭头触摸区高度至少 48dp；actual=${backButton.height / baseDensity}dp density=$baseDensity", backButton.height / baseDensity >= 48f)
  }
}

package com.anan.anannotes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.theme.AnAnNotesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondaryPageLayoutTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun headerSupportsNormalLargeAndVeryLargeTextWithoutClippingOrShrinkingBackTarget() {
    var baseDensity = 1f
    var fontScale by mutableStateOf(1f)
    var backCalls = 0
    val title = "导出记录和存储概览"
    composeRule.setContent {
      val density = LocalDensity.current
      baseDensity = density.density
      CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        AnAnNotesTheme {
          Column(modifier = Modifier.width(220.dp)) {
            CompactPageHeader(
              title = title,
              onBack = { backCalls++ },
              testTag = "secondary-test-header",
            )
          }
        }
      }
    }

    listOf(1f, 1.5f, 2f).forEach { scale ->
      composeRule.runOnIdle { fontScale = scale }
      composeRule.waitForIdle()

      val header = composeRule.onNodeWithTag("secondary-test-header").fetchSemanticsNode().boundsInRoot
      val titleBounds = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
      val backButton = composeRule.onNodeWithTag("page-back-arrow").fetchSemanticsNode().boundsInRoot
      assertTrue("${scale * 100}% 字号下标题栏至少保持基础高度", header.height / baseDensity >= 52f)
      assertTrue("${scale * 100}% 字号下标题不能超出标题栏", titleBounds.bottom <= header.bottom + 1f)
      assertTrue("${scale * 100}% 字号下标题不能横向溢出", titleBounds.right <= header.right + 1f)
      assertTrue("${scale * 100}% 字号下返回触摸区至少 48dp；actual=${backButton.width / baseDensity}dp density=$baseDensity", backButton.width / baseDensity >= 48f)
      assertTrue("${scale * 100}% 字号下返回触摸区至少 48dp 高；actual=${backButton.height / baseDensity}dp density=$baseDensity", backButton.height / baseDensity >= 48f)
      composeRule.onNodeWithTag("page-back-arrow").performClick()
    }
    assertEquals("各字号下返回按钮均可触发", 3, backCalls)
  }
}

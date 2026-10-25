package com.anan.anannotes.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.feature.editor.ImageAdjustmentSheet
import com.anan.anannotes.feature.editor.TextSettingsSheet
import com.anan.anannotes.feature.settings.SettingsScreen
import com.anan.anannotes.ui.components.HapticSlider
import com.anan.anannotes.ui.theme.AnAnNotesTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontSizeHapticFeedbackTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun settingsFontSizeSliderTicksOnlyWhenRoundedSpValueChanges() {
    val haptics = RecordingHapticFeedback()
    val settingsState = mutableStateOf(DisplaySettings())

    composeRule.setContent {
      CompositionLocalProvider(LocalHapticFeedback provides haptics) {
        AnAnNotesTheme {
          SettingsScreen(
            settings = settingsState.value,
            onSettingsChange = { settingsState.value = it },
            onClearPreviewCache = {},
            cacheMessage = null,
            onBack = {},
          )
        }
      }
    }
    composeRule.waitForIdle()

    assertEquals(0, composeRule.onNodeWithTag("default-font-size-slider")
      .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
    assertEquals(0, composeRule.onNodeWithTag("default-line-spacing-slider")
      .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)

    setProgress("default-font-size-slider", 18.2f)
    assertTrue(haptics.events.isEmpty())
    setProgress("default-font-size-slider", 18.7f)
    setProgress("default-font-size-slider", 19.2f)
    setProgress("default-font-size-slider", 20.2f)
    setProgress("default-font-size-slider", 19.2f)
    assertEquals(List(3) { HapticFeedbackType.SegmentFrequentTick }, haptics.events.toList())
    setProgress("default-line-spacing-slider", 1.7f)
    assertEquals(List(4) { HapticFeedbackType.SegmentFrequentTick }, haptics.events.toList())
    composeRule.runOnIdle {
      settingsState.value = settingsState.value.copy(defaultFontSizeSp = 24.2f)
    }
    setProgress("default-font-size-slider", 24.3f)

    assertEquals(List(4) { HapticFeedbackType.SegmentFrequentTick }, haptics.events.toList())
  }

  @Test
  fun lineSpacingSliderTicksWhenAdjustmentCrossesAFiveHundredthStep() {
    val haptics = RecordingHapticFeedback()
    val lineSpacing = mutableStateOf(1.35f)

    composeRule.setContent {
      CompositionLocalProvider(LocalHapticFeedback provides haptics) {
        AnAnNotesTheme {
          HapticSlider(
            value = lineSpacing.value,
            onValueChange = { lineSpacing.value = it },
            valueRange = 1f..2f,
            steps = 0,
            hapticStepSize = 0.05f,
            modifier = Modifier.testTag("line-spacing-slider"),
          )
        }
      }
    }
    composeRule.waitForIdle()

    assertEquals(0, composeRule.onNodeWithTag("line-spacing-slider")
      .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
    setProgress("line-spacing-slider", 1.7f)

    assertEquals(listOf(HapticFeedbackType.SegmentFrequentTick), haptics.events.toList())
  }

  @Test
  fun perNoteTypographySlidersKeepContinuousAdjustment() {
    composeRule.setContent {
      AnAnNotesTheme {
        TextSettingsSheet(
          settings = DisplaySettings(),
          fontSizeSpOverride = 20f,
          lineSpacingMultiplierOverride = 1.1f,
          onGlobalSettingsChange = {},
          onNoteOverridesChange = { _, _ -> },
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()

    assertEquals(0, composeRule.onNodeWithTag("editor-font-size-slider")
      .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
    assertEquals(0, composeRule.onNodeWithTag("editor-line-spacing-slider")
      .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
  }

  @Test
  fun imageWidthSliderProvidesContinuousAdjustment() {
    val width = mutableStateOf(50f)

    composeRule.setContent {
      AnAnNotesTheme {
        ImageAdjustmentSheet(
          widthPercent = width.value,
          onWidthPercentChange = { width.value = it },
          onDeleteClick = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()

    val slider = composeRule.onNodeWithTag("image-width-slider")
    assertEquals(0, slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
    setProgress("image-width-slider", 53f)

    assertEquals(53f, width.value, 0.1f)
  }

  private fun setProgress(testTag: String, value: Float) {
    val slider = composeRule.onNodeWithTag(testTag)
    val semantics = slider.fetchSemanticsNode().config
    slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
      assertTrue("滑杆应接受值 $value；当前语义：$semantics", setProgress(value))
    }
    composeRule.waitForIdle()
  }

  private class RecordingHapticFeedback : HapticFeedback {
    val events = CopyOnWriteArrayList<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
      events += hapticFeedbackType
    }
  }
}

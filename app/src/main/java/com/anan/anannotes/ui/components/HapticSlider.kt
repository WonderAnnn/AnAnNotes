package com.anan.anannotes.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticSlider(
  value: Float,
  onValueChange: (Float) -> Unit,
  valueRange: ClosedFloatingPointRange<Float>,
  steps: Int,
  hapticStepSize: Float,
  modifier: Modifier = Modifier,
) {
  require(hapticStepSize.isFinite() && hapticStepSize > 0f)

  val hapticFeedback = LocalHapticFeedback.current
  val interactionSource = remember { MutableInteractionSource() }
  val currentHapticStep = ((value - valueRange.start) / hapticStepSize).roundToInt()
  var lastHapticStep by remember(valueRange, hapticStepSize, currentHapticStep) {
    mutableIntStateOf(currentHapticStep)
  }

  Slider(
    value = value,
    onValueChange = { newValue ->
      val newHapticStep = ((newValue - valueRange.start) / hapticStepSize).roundToInt()
      if (newHapticStep != lastHapticStep) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        lastHapticStep = newHapticStep
      }
      onValueChange(newValue)
    },
    valueRange = valueRange,
    steps = steps,
    modifier = modifier,
    interactionSource = interactionSource,
    thumb = {
      SliderDefaults.Thumb(
        interactionSource = interactionSource,
        thumbSize = DpSize(width = 4.dp, height = 18.dp),
      )
    },
  )
}

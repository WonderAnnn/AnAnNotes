package com.anan.anannotes.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.ImageBlockRules
import com.anan.anannotes.ui.components.HapticSlider
import com.anan.anannotes.ui.theme.AnAnInputFieldShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageAdjustmentSheet(
  widthPercent: Float,
  onWidthPercentChange: (Float) -> Unit,
  onDeleteClick: () -> Unit,
  onDismiss: () -> Unit,
) {
  var numericValue by remember(widthPercent) { mutableStateOf(widthPercent.toInt().toString()) }
  ModalBottomSheet(
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = LocalWindowInfo.current.containerDpSize.height * 0.82f)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp)
        .padding(bottom = 24.dp)
        .testTag("image-adjustment-sheet"),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("图片大小", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      }
      Text("宽度　${widthPercent.toInt()}%　·　保持原图比例", style = MaterialTheme.typography.titleMedium)
      HapticSlider(
        value = widthPercent,
        onValueChange = { value ->
          val normalized = ImageBlockRules.normalizeWidthPercent(value)
          numericValue = normalized.toInt().toString()
          onWidthPercentChange(normalized)
        },
        valueRange = ImageBlockRules.MIN_WIDTH_PERCENT..ImageBlockRules.MAX_WIDTH_PERCENT,
        steps = 0,
        hapticStepSize = 5f,
        modifier = Modifier.testTag("image-width-slider"),
      )
      OutlinedTextField(
        value = numericValue,
        onValueChange = { input ->
          numericValue = input.filter(Char::isDigit).take(3)
          numericValue.toIntOrNull()?.takeIf { it in ImageBlockRules.MIN_WIDTH_PERCENT.toInt()..ImageBlockRules.MAX_WIDTH_PERCENT.toInt() }
            ?.let { value -> onWidthPercentChange(value.toFloat()) }
        },
        modifier = Modifier.fillMaxWidth().testTag("image-width-percent-input"),
        label = { Text("宽度百分比") },
        suffix = { Text("%") },
        shape = AnAnInputFieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
      )
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDeleteClick, modifier = Modifier.testTag("delete-single-image")) {
          Text("删除图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("完成") }
      }
    }
  }
}

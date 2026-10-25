package com.anan.anannotes.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.ui.components.HapticSlider

private enum class TypographyScope { GLOBAL, NOTE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSettingsSheet(
  settings: DisplaySettings,
  fontSizeSpOverride: Float?,
  lineSpacingMultiplierOverride: Float?,
  onGlobalSettingsChange: (DisplaySettings) -> Unit,
  onNoteOverridesChange: (fontSizeSp: Float?, lineSpacingMultiplier: Float?) -> Unit,
  onDismiss: () -> Unit,
) {
  var scope by remember(fontSizeSpOverride, lineSpacingMultiplierOverride) {
    mutableStateOf(if (fontSizeSpOverride != null || lineSpacingMultiplierOverride != null) TypographyScope.NOTE else TypographyScope.GLOBAL)
  }
  val fontSize = if (scope == TypographyScope.GLOBAL) settings.defaultFontSizeSp else fontSizeSpOverride ?: settings.defaultFontSizeSp
  val lineSpacing = if (scope == TypographyScope.GLOBAL) settings.lineSpacingMultiplier else lineSpacingMultiplierOverride ?: settings.lineSpacingMultiplier

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
        .testTag("text-settings-sheet"),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("字体与显示", style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onDismiss) { Text("完成") }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(scope == TypographyScope.GLOBAL, { scope = TypographyScope.GLOBAL }, label = { Text("全局默认") })
        FilterChip(scope == TypographyScope.NOTE, { scope = TypographyScope.NOTE }, label = { Text("仅本条笔记") })
      }
      Text("字号　${fontSize.toInt()} sp", style = MaterialTheme.typography.titleMedium)
      HapticSlider(
        value = fontSize,
        onValueChange = { value ->
          if (scope == TypographyScope.GLOBAL) onGlobalSettingsChange(settings.copy(defaultFontSizeSp = value))
          else onNoteOverridesChange(value, lineSpacingMultiplierOverride)
        },
        valueRange = DisplaySettingsRules.MIN_FONT_SIZE_SP..DisplaySettingsRules.MAX_FONT_SIZE_SP,
        steps = 0,
        hapticStepSize = 1f,
        modifier = Modifier.testTag("editor-font-size-slider"),
      )
      Text("行间距　${"%.2f".format(lineSpacing)} 倍", style = MaterialTheme.typography.titleMedium)
      HapticSlider(
        value = lineSpacing,
        onValueChange = { value ->
          if (scope == TypographyScope.GLOBAL) onGlobalSettingsChange(settings.copy(lineSpacingMultiplier = value))
          else onNoteOverridesChange(fontSizeSpOverride, value)
        },
        valueRange = DisplaySettingsRules.MIN_LINE_SPACING_MULTIPLIER..DisplaySettingsRules.MAX_LINE_SPACING_MULTIPLIER,
        steps = 0,
        hapticStepSize = 0.05f,
        modifier = Modifier.testTag("editor-line-spacing-slider"),
      )
      Card(
        modifier = Modifier.fillMaxWidth().testTag("editor-typography-preview"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Text(
          "预览文字\n安安简记",
          modifier = Modifier.padding(14.dp),
          fontSize = fontSize.sp,
          lineHeight = (fontSize * lineSpacing).sp,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      if (scope == TypographyScope.NOTE && (fontSizeSpOverride != null || lineSpacingMultiplierOverride != null)) {
        TextButton(
          onClick = { onNoteOverridesChange(null, null) },
          modifier = Modifier.testTag("editor-reset-typography"),
        ) { Text("恢复全局默认") }
      }
      Spacer(Modifier.height(2.dp))
    }
  }
}

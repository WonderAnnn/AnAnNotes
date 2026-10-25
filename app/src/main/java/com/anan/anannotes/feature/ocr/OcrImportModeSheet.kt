package com.anan.anannotes.feature.ocr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anan.anannotes.ui.theme.NoteSkin

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OcrImportModeSheet(
  skin: NoteSkin,
  onSelect: (OcrImportMode) -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(onDismissRequest = onDismiss, containerColor = skin.surface) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp)) {
      Text("导入图片", style = MaterialTheme.typography.titleLarge, color = skin.foreground)
      Text(
        "选择图片如何加入这条笔记",
        style = MaterialTheme.typography.bodyMedium,
        color = skin.secondaryText,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
      )
      ImportChoice(
        title = "仅插入图片",
        description = "保留原图，不识别文字",
        tag = "ocr-import-image-only",
        skin = skin,
      ) { onSelect(OcrImportMode.IMAGE_ONLY) }
      ImportChoice(
        title = "插入图片并识别文字",
        description = "保留原图；识别结果可编辑和搜索",
        tag = "ocr-import-image-and-text",
        skin = skin,
      ) { onSelect(OcrImportMode.IMAGE_AND_TEXT) }
      ImportChoice(
        title = "只识别文字",
        description = "识别完成后不保留原图",
        tag = "ocr-import-text-only",
        skin = skin,
      ) { onSelect(OcrImportMode.TEXT_ONLY) }
    }
  }
}

@Composable
private fun ImportChoice(
  title: String,
  description: String,
  tag: String,
  skin: NoteSkin,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().testTag(tag),
  ) {
    Column(Modifier.fillMaxWidth()) {
      Text(title, color = skin.foreground, style = MaterialTheme.typography.titleMedium)
      Text(description, color = skin.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
  }
}

package com.anan.anannotes.feature.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.supportsAttribution

private data class ExportFormatOption(
  val format: ExportFormat,
  val label: String,
  val destination: ExportDestination,
)

private enum class ExportDestination { DOCUMENTS, PICTURES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportFormatSheet(
  title: String,
  sheetTestTag: String,
  listTestTag: String,
  exportDirectoryLabel: String,
  onSelectDocumentFormat: (ExportFormat, Boolean) -> Unit,
  onSelectPictureFormat: (ExportFormat, Boolean) -> Unit,
  onChangeExportDirectory: () -> Unit,
  onDismiss: () -> Unit,
) {
  var includeAttribution by remember { mutableStateOf(false) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val options = listOf(
    ExportFormatOption(ExportFormat.PDF, "PDF（A4）", ExportDestination.DOCUMENTS),
    ExportFormatOption(ExportFormat.PNG_PAGED_600_DPI, "PNG（A4 分页，600 DPI）", ExportDestination.PICTURES),
    ExportFormatOption(ExportFormat.PNG_LONG_IMAGE, "PNG（长图） 1080 px 宽", ExportDestination.PICTURES),
    ExportFormatOption(ExportFormat.PNG_SOCIAL_SQUARE, "PNG（朋友圈方图） 1080 × 1080", ExportDestination.PICTURES),
    ExportFormatOption(ExportFormat.PNG_SOCIAL_PORTRAIT, "PNG（朋友圈竖图） 1080 × 1350", ExportDestination.PICTURES),
    ExportFormatOption(ExportFormat.TXT, "TXT", ExportDestination.DOCUMENTS),
    ExportFormatOption(ExportFormat.MARKDOWN_ZIP, "Markdown ZIP（包含原图）", ExportDestination.DOCUMENTS),
  )

  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag(sheetTestTag),
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth()
        .testTag(listTestTag)
        .heightIn(max = LocalWindowInfo.current.containerDpSize.height * 0.68f)
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Text(
          title,
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      }
      item {
        Text(
          "PDF、TXT 和 Markdown ZIP 保存到所选文件夹；PNG 保存到系统相册",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      item {
        ExportAttributionToggle(
          checked = includeAttribution,
          onCheckedChange = { includeAttribution = it },
        )
      }
      items(options, key = { it.format.name }) { option ->
        TextButton(
          onClick = {
            when (option.destination) {
              ExportDestination.DOCUMENTS -> onSelectDocumentFormat(
                option.format,
                includeAttribution && option.format.supportsAttribution(),
              )
              ExportDestination.PICTURES -> onSelectPictureFormat(
                option.format,
                includeAttribution && option.format.supportsAttribution(),
              )
            }
          },
          modifier = Modifier.fillMaxWidth().testTag(formatTestTag(option.format)),
        ) {
          Text(option.label)
        }
      }
      item {
        TextButton(
          onClick = onChangeExportDirectory,
          modifier = Modifier.fillMaxWidth().testTag("change-export-directory"),
        ) {
          Text(exportDirectoryLabel)
        }
      }
    }
  }
}

@Composable
fun ExportAttributionToggle(
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().testTag("export-attribution-toggle"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag("export-attribution-checkbox"),
    )
    Column(modifier = Modifier.padding(start = 8.dp)) {
      Text("添加“来自安安简记”署名", style = MaterialTheme.typography.bodyLarge)
      Text(
        "仅用于 PDF 和 PNG；TXT、Markdown 内容不变",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

private fun formatTestTag(format: ExportFormat): String = when (format) {
  ExportFormat.PDF -> "export-format-pdf"
  ExportFormat.PNG_PAGED_600_DPI -> "export-format-png-a4"
  ExportFormat.PNG_LONG_IMAGE -> "export-format-png-long"
  ExportFormat.PNG_SOCIAL_SQUARE -> "export-format-png-social-square"
  ExportFormat.PNG_SOCIAL_PORTRAIT -> "export-format-png-social-portrait"
  ExportFormat.TXT -> "export-format-txt"
  ExportFormat.MARKDOWN_ZIP -> "export-format-markdown-zip"
}

package com.anan.anannotes.feature.privatemodule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.supportsAttribution
import com.anan.anannotes.feature.export.ExportAttributionToggle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateExportFormatSheet(
  onSelect: (ExportFormat, Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  var includeAttribution by remember { mutableStateOf(false) }
  val formats = listOf(
    ExportFormat.PDF to "PDF（A4）",
    ExportFormat.PNG_PAGED_600_DPI to "PNG（A4 分页，600 DPI）",
    ExportFormat.PNG_LONG_IMAGE to "PNG（长图）",
    ExportFormat.PNG_SOCIAL_SQUARE to "PNG（朋友圈方图，1080 × 1080）",
    ExportFormat.PNG_SOCIAL_PORTRAIT to "PNG（朋友圈竖图，1080 × 1350）",
    ExportFormat.TXT to "TXT",
    ExportFormat.MARKDOWN_ZIP to "Markdown ZIP（包含原图）",
  )
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("private-export-format-sheet"),
  ) {
    LazyColumn(
      modifier = Modifier.fillMaxWidth()
        .heightIn(max = LocalWindowInfo.current.containerDpSize.height * 0.68f)
        .padding(horizontal = 20.dp)
        .padding(bottom = 24.dp)
        .testTag("private-export-format-list"),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      item {
        Text("导出私密笔记", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        Text(
          "文件会以普通文件保存；导出前需要重新验证私密密码。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(bottom = 4.dp),
        )
      }
      item {
        ExportAttributionToggle(
          checked = includeAttribution,
          onCheckedChange = { includeAttribution = it },
        )
      }
      items(formats, key = { it.first.name }) { (format, label) ->
        TextButton(
          onClick = { onSelect(format, includeAttribution && format.supportsAttribution()) },
          modifier = Modifier.fillMaxWidth().testTag(privateExportFormatTag(format)),
        ) { Text(label) }
      }
    }
  }
}

@Composable
fun PrivateExportPasswordDialog(
  errorMessage: String?,
  retryAfterMillis: Long = 0L,
  onSubmit: (CharArray) -> Unit,
  onDismiss: () -> Unit,
) {
  var password by remember { mutableStateOf("") }
  AlertDialog(
    modifier = Modifier.testTag("private-export-password-dialog"),
    onDismissRequest = onDismiss,
    title = { Text("验证私密密码") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("恢复密钥不能用于导出验证。")
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          modifier = Modifier.fillMaxWidth().testTag("private-export-password-field"),
          label = { Text("私密密码") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        val visibleError = if (retryAfterMillis > 0L) {
          "请等待 ${(retryAfterMillis + 999L) / 1_000L} 秒后再次验证"
        } else errorMessage
        visibleError?.let {
          Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("private-export-password-error"))
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val chars = password.toCharArray()
          password = ""
          onSubmit(chars)
        },
        enabled = password.isNotEmpty() && retryAfterMillis <= 0L,
        modifier = Modifier.testTag("private-export-password-submit"),
      ) { Text("验证") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("private-export-password-cancel")) { Text("取消") }
    },
  )
}

@Composable
fun PrivateExportPlaintextWarningDialog(
  errorMessage: String?,
  isExporting: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    modifier = Modifier.testTag("private-export-plaintext-warning"),
    onDismissRequest = { if (!isExporting) onDismiss() },
    title = { Text("确认导出私密笔记") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("导出文件将不再受安安简记私密模块保护")
        errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (isExporting) Text("正在导出…")
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        enabled = !isExporting,
        modifier = Modifier.testTag("private-export-plaintext-confirm"),
      ) { Text(if (isExporting) "正在导出" else "导出普通文件") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !isExporting, modifier = Modifier.testTag("private-export-plaintext-cancel")) {
        Text("取消")
      }
    },
  )
}

@Composable
fun PrivateExportHistoryScreen(
  records: List<PrivateExportHistoryRecord>,
  onBack: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().testTag("private-export-history-screen")) {
    TextButton(onClick = onBack, modifier = Modifier.testTag("private-export-history-back")) { Text("返回") }
    Text("私密导出记录", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
    if (records.isEmpty()) {
      Text("暂无导出记录", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("private-export-history-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(records, key = PrivateExportHistoryRecord::id) { record ->
          Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Text(record.fileName, style = MaterialTheme.typography.titleSmall)
            Text("笔记：${record.noteTitle.ifBlank { "无标题笔记" }} · ${privateFormatLabel(record.format)}", style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(privateHistoryDate(record.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Text(
                if (record.succeeded) "已导出" else "失败 ${record.errorCode.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = if (record.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
              )
            }
          }
        }
      }
    }
  }
}

private fun privateExportFormatTag(format: ExportFormat): String = when (format) {
  ExportFormat.PDF -> "private-export-format-pdf"
  ExportFormat.PNG_PAGED_600_DPI -> "private-export-format-png-a4"
  ExportFormat.PNG_LONG_IMAGE -> "private-export-format-png-long"
  ExportFormat.PNG_SOCIAL_SQUARE -> "private-export-format-png-square"
  ExportFormat.PNG_SOCIAL_PORTRAIT -> "private-export-format-png-portrait"
  ExportFormat.TXT -> "private-export-format-txt"
  ExportFormat.MARKDOWN_ZIP -> "private-export-format-markdown"
}

private fun privateFormatLabel(format: ExportFormat): String = when (format) {
  ExportFormat.PDF -> "PDF"
  ExportFormat.PNG_PAGED_600_DPI -> "PNG A4 600 DPI"
  ExportFormat.PNG_LONG_IMAGE -> "PNG 长图"
  ExportFormat.PNG_SOCIAL_SQUARE -> "PNG 方图"
  ExportFormat.PNG_SOCIAL_PORTRAIT -> "PNG 竖图"
  ExportFormat.TXT -> "TXT"
  ExportFormat.MARKDOWN_ZIP -> "Markdown ZIP"
}

private fun privateHistoryDate(timestampMillis: Long): String =
  SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(timestampMillis))

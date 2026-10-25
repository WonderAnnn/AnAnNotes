package com.anan.anannotes.feature.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ExportHistoryScreen(
  viewModel: ExportHistoryViewModel = viewModel(),
  onBack: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  ExportHistoryScreen(
    state = state,
    onBack = onBack,
    onClear = viewModel::clear,
    onRefresh = viewModel::refresh,
    onOpen = viewModel::open,
    onShare = viewModel::share,
  )
}

@Composable
fun ExportHistoryScreen(
  state: ExportHistoryUiState,
  onBack: () -> Unit,
  onClear: () -> Unit,
  onRefresh: () -> Unit,
  onOpen: (ExportHistoryModel) -> Unit,
  onShare: (ExportHistoryModel) -> Unit,
) {
  var showClearConfirmation by remember { mutableStateOf(false) }
  val busy = state.isLoading || state.isClearing
  PageScaffold(modifier = Modifier.testTag("export-history-screen")) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    CompactPageHeader(title = "导出记录", onBack = onBack, testTag = "export-history-page-header")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.testTag("export-history-refresh")) {
        Text("刷新")
      }
      TextButton(
        onClick = { showClearConfirmation = true },
        enabled = !busy && state.records.isNotEmpty(),
        modifier = Modifier.testTag("export-history-clear"),
      ) { Text("清空记录") }
    }
    state.message?.let { message ->
      HistoryCard { Text(message, modifier = Modifier.testTag("export-history-message"), style = MaterialTheme.typography.bodyMedium) }
    }
    Column(
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(if (state.isClearing) "正在清空记录…" else "正在读取记录…", style = MaterialTheme.typography.bodySmall)
      }
      Text("保留最近 100 条普通导出记录", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
      if (!state.isLoading && state.records.isEmpty()) {
        HistoryCard { Text("暂无导出记录", style = MaterialTheme.typography.bodyLarge) }
      }
      state.records.forEach { record ->
        key(record.id) {
          HistoryCard {
            Text(record.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
              "${record.format.displayName()} · ${formatExportTime(record.createdAtMillis)}",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodySmall,
            )
            if (record.succeeded) {
              Text("导出成功", style = MaterialTheme.typography.bodyMedium)
              Text(
                "保存位置：${exportDestinationLabel(record.destinationUri)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpen(record) }, enabled = !busy, modifier = Modifier.testTag("export-history-open-${record.id}")) {
                  Text("打开")
                }
                TextButton(onClick = { onShare(record) }, enabled = !busy, modifier = Modifier.testTag("export-history-share-${record.id}")) {
                  Text("分享")
                }
              }
            } else {
              Text("导出失败 · 错误码：${record.errorCode ?: "未知"}", style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
  }

  if (showClearConfirmation) {
    AlertDialog(
      onDismissRequest = { showClearConfirmation = false },
      title = { Text("清空导出记录？") },
      text = { Text("仅清空记录，不会删除已经导出的文件。") },
      confirmButton = {
        TextButton(
          onClick = { showClearConfirmation = false; onClear() },
          enabled = !busy && state.records.isNotEmpty(),
          modifier = Modifier.testTag("export-history-confirm-clear"),
        ) { Text("清空记录") }
      },
      dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text("取消") } },
    )
  }
}

@Composable
private fun HistoryCard(content: @Composable () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
  }
}

private fun ExportFormat.displayName(): String = when (this) {
  ExportFormat.PDF -> "PDF"
  ExportFormat.PNG_PAGED_600_DPI -> "PNG · A4 分页 · 600 DPI"
  ExportFormat.PNG_LONG_IMAGE -> "PNG · 长图"
  ExportFormat.PNG_SOCIAL_SQUARE -> "PNG · 朋友圈方图"
  ExportFormat.PNG_SOCIAL_PORTRAIT -> "PNG · 朋友圈竖图"
  ExportFormat.TXT -> "TXT 文本"
  ExportFormat.MARKDOWN_ZIP -> "Markdown ZIP"
}

private fun formatExportTime(millis: Long): String =
  DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE)
    .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

private fun exportDestinationLabel(uriString: String): String {
  val uri = Uri.parse(uriString)
  if (uri.scheme != "content") return "位置不可用，请重新导出"
  if (uri.authority == "media") return "系统相册 / AnAn Notes / Exports"
  val documentId = uri.pathSegments.zipWithNext().firstOrNull { it.first == "document" }?.second
  if (documentId?.startsWith("primary:") == true) {
    val folder = documentId.substringAfter(':').substringBeforeLast('/', "")
    return if (folder.isBlank()) "内部共享存储" else "内部共享存储 / $folder"
  }
  return "系统文件提供方"
}

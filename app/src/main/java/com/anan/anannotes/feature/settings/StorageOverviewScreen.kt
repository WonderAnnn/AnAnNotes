package com.anan.anannotes.feature.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold
import java.util.Locale

@Composable
fun StorageOverviewScreen(
  viewModel: StorageOverviewViewModel = viewModel(),
  onBack: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  StorageOverviewScreen(
    state = state,
    onBack = onBack,
    onRefresh = viewModel::refresh,
    onClearPreviewCache = viewModel::clearPreviewCache,
  )
}

@Composable
fun StorageOverviewScreen(
  state: StorageOverviewUiState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onClearPreviewCache: () -> Unit,
) {
  val busy = state.isLoading || state.isClearing
  PageScaffold(modifier = Modifier.testTag("storage-overview-screen")) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    CompactPageHeader(title = "存储概览", onBack = onBack, testTag = "storage-overview-page-header")
    TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.testTag("storage-overview-refresh")) { Text("刷新") }
    state.message?.let { message ->
      StorageCard { Text(message, modifier = Modifier.testTag("storage-overview-message"), style = MaterialTheme.typography.bodyMedium) }
    }
    Column(
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (busy) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(if (state.isClearing) "正在清理并刷新统计…" else "正在读取存储统计…", style = MaterialTheme.typography.bodySmall)
      }
      val overview = state.overview
      if (overview == null && !state.isLoading) {
        StorageCard { Text("暂无存储统计，请刷新重试。", style = MaterialTheme.typography.bodyLarge) }
      }
      if (overview != null) {
        StorageCard {
          Text("普通笔记内部存储", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text("仅含数据库、原图和预览缓存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          StorageRow("笔记数据库（含临时日志）", overview.databaseBytes)
          StorageRow("笔记原图", overview.assetBytes)
          StorageRow("预览缓存", overview.previewBytes)
          Text("内部合计", style = MaterialTheme.typography.labelLarge)
          Text(
            formatStorageBytes(overview.internalBytes),
            modifier = Modifier.testTag("storage-overview-internal-total"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          if (overview.internalUnreadableEntries > 0) {
            Text("有 ${overview.internalUnreadableEntries} 项无法读取，以上仅为可读取部分。", style = MaterialTheme.typography.bodySmall)
          }
        }
        StorageCard {
          val exports = overview.exportedFiles
          Text("记录中可访问的导出文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          if (exports.recordedFileCount == 0) Text("暂无成功导出记录", style = MaterialTheme.typography.bodyMedium)
          Text("可访问文件：${exports.accessibleFileCount} 个", style = MaterialTheme.typography.bodyMedium)
          Text(
            if (exports.knownSizeCount > 0) "已知大小合计：${formatStorageBytes(exports.knownBytes)}（${exports.knownSizeCount} 个文件）"
            else "已知大小：暂无可用大小数据。",
            style = MaterialTheme.typography.bodyMedium,
          )
          Text("大小未知：${exports.unknownSizeCount} 个", style = MaterialTheme.typography.bodyMedium)
          Text("不可访问：${exports.unavailableCount} 个", style = MaterialTheme.typography.bodyMedium)
          Text(
            "仅统计当前成功记录中的文件。裁剪或清空记录后，相关导出无法再追溯；外部导出副本不计入内部合计。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
        }
        StorageCard {
          Text("预览缓存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
          Text("清理可重新生成的预览图，保留笔记、原图和导出文件。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
          TextButton(
            onClick = onClearPreviewCache,
            enabled = !busy,
            modifier = Modifier.testTag("storage-overview-clear-previews"),
          ) { Text("清理预览缓存") }
        }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}
}

@Composable
private fun StorageCard(content: @Composable () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
  }
}

@Composable
private fun StorageRow(label: String, bytes: Long) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    Text(formatStorageBytes(bytes), style = MaterialTheme.typography.bodyMedium)
  }
}

private fun formatStorageBytes(bytes: Long): String {
  if (bytes < 1024L) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB", "TB", "PB", "EB")
  var amount = bytes.toDouble()
  var unit = -1
  do { amount /= 1024.0; unit++ } while (amount >= 1024.0 && unit < units.lastIndex)
  return String.format(Locale.SIMPLIFIED_CHINESE, "%.1f %s", amount, units[unit])
}

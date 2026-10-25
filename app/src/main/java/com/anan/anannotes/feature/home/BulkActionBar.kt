package com.anan.anannotes.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun BulkActionBar(
  onMove: () -> Unit,
  onPin: () -> Unit,
  pinActionLabel: String,
  onExport: () -> Unit,
  exportEnabled: Boolean = true,
  isExporting: Boolean = false,
  onDelete: () -> Unit,
) {
  Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 0.dp) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .navigationBarsPadding()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      TextButton(onClick = onMove) { Text("移动") }
      TextButton(onClick = onPin) { Text(pinActionLabel) }
      TextButton(
        onClick = onExport,
        enabled = exportEnabled,
        modifier = Modifier.testTag("home-export-button"),
      ) { Text(if (isExporting) "导出中…" else "导出") }
      TextButton(onClick = onDelete) { Text("删除") }
    }
  }
}

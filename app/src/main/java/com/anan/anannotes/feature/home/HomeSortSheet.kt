package com.anan.anannotes.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.NoteSortOrder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeSortSheet(
  selectedOrder: NoteSortOrder,
  onSelectOrder: (NoteSortOrder) -> Unit,
  onDismiss: () -> Unit,
) {
  val compactHeight = LocalWindowInfo.current.containerDpSize.height <= 480.dp
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = compactHeight)
  ModalBottomSheet(
    sheetState = sheetState,
    onDismissRequest = onDismiss,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = minOf(LocalWindowInfo.current.containerDpSize.height * 0.72f, 360.dp))
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 32.dp)
        .testTag("home-sort-sheet"),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("排序和卡片布局", style = MaterialTheme.typography.titleLarge)
      SortRow(
        text = "最近编辑时间",
        selected = selectedOrder == NoteSortOrder.RECENT_EDITED,
        testTag = "sort-recent-edited",
        onClick = { onSelectOrder(NoteSortOrder.RECENT_EDITED) },
      )
      SortRow(
        text = "创建时间",
        selected = selectedOrder == NoteSortOrder.CREATED,
        testTag = "sort-created",
        onClick = { onSelectOrder(NoteSortOrder.CREATED) },
      )
      Text("卡片布局", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
      Text(
        "根据屏幕宽度和系统字体大小自动调整为双列或单列。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SortRow(text: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
  TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(testTag)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
      RadioButton(selected = selected, onClick = onClick)
    }
  }
}

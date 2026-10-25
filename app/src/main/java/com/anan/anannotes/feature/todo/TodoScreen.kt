package com.anan.anannotes.feature.todo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.core.model.TodoFeedItem
import com.anan.anannotes.feature.home.MainBottomBar

@Composable
fun TodoScreen(
  onHome: () -> Unit,
  onTodo: () -> Unit,
  onOpenNote: (noteId: String) -> Unit,
) {
  val viewModel: TodoViewModel = viewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { MainBottomBar(selectedIndex = 1, onHome = onHome, onTodo = onTodo) },
  ) { contentPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("todo-title-header"),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("待办", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.align(Alignment.Top))
        Spacer(Modifier.weight(1f))
      }
      Row(
        modifier = Modifier.fillMaxWidth().testTag("todo-completed-filter"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("显示已完成", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(
          checked = state.showCompleted,
          onCheckedChange = viewModel::setShowCompleted,
          modifier = Modifier.testTag("todo-show-completed"),
        )
      }
      if (state.items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
          Text(
            if (state.showCompleted) "还没有待办" else "没有未完成待办",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 20.dp),
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().testTag("todo-list"),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(state.items, key = TodoFeedItem::id) { item ->
            TodoCard(
              item = item,
              onOpenNote = { onOpenNote(item.noteId) },
              onCheckedChange = { checked -> viewModel.setCompleted(item.id, checked) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun TodoCard(item: TodoFeedItem, onOpenNote: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
  Card(
    onClick = onOpenNote,
    modifier = Modifier.fillMaxWidth().testTag("todo-item-${item.id}"),
    shape = RoundedCornerShape(18.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Checkbox(
        checked = item.isCompleted,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.testTag("todo-checkbox-${item.id}"),
      )
      Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
        Text(
          text = item.text,
          modifier = Modifier.fillMaxWidth().testTag("todo-text-${item.id}"),
          style = MaterialTheme.typography.bodyLarge,
          color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
          else MaterialTheme.colorScheme.onSurface,
          textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = "${item.noteTitle} · ${item.folderName}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.clickable(onClick = onOpenNote),
        )
      }
    }
  }
}

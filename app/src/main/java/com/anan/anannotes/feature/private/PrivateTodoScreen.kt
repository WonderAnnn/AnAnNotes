package com.anan.anannotes.feature.privatemodule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
fun PrivateTodoScreen(
  todos: List<PrivateTodoItem>,
  showCompleted: Boolean,
  onShowCompletedChange: (Boolean) -> Unit,
  onTodoCompletedChange: (PrivateTodoItem, Boolean) -> Unit,
  onOpenNote: (PrivateNoteId) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().testTag("private-todo-screen")) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("显示已完成", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
      Switch(
        checked = showCompleted,
        onCheckedChange = onShowCompletedChange,
        modifier = Modifier.testTag("private-show-completed"),
      )
    }

    if (todos.isEmpty()) {
      Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("没有待办事项", style = MaterialTheme.typography.titleLarge)
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag("private-todo-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(todos, key = { "${it.noteId.value}:${it.todoId}" }) { todo ->
          Card(
            modifier = Modifier.fillMaxWidth().testTag("private-todo-${todo.todoId}"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = todo.completed,
                onCheckedChange = { onTodoCompletedChange(todo, it) },
                modifier = Modifier.testTag("private-todo-check-${todo.todoId}"),
              )
              Column(
                modifier = Modifier.weight(1f).padding(start = 6.dp),
              ) {
                Text(
                  text = todo.text,
                  style = MaterialTheme.typography.bodyLarge,
                  color = if (todo.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                  textDecoration = if (todo.completed) TextDecoration.LineThrough else TextDecoration.None,
                )
                Text(
                  text = todo.noteTitle.ifBlank { "无标题笔记" },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(top = 4.dp),
                  maxLines = 1,
                )
              }
              androidx.compose.material3.TextButton(onClick = { onOpenNote(todo.noteId) }) {
                Text("打开")
              }
            }
          }
        }
      }
    }
  }
}

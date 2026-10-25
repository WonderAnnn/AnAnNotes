package com.anan.anannotes.feature.privatemodule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PrivateHomeScreen(
  notes: List<PrivateNotePreview>,
  onCreateNote: () -> Unit,
  onOpenNote: (PrivateNoteId) -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().testTag("private-home-screen")) {
    if (notes.isEmpty()) {
      Column(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("还没有私密笔记", style = MaterialTheme.typography.titleLarge)
      }
    } else {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth().testTag("private-note-list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
          start = 20.dp,
          top = 8.dp,
          end = 20.dp,
          bottom = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(notes, key = { it.id.value }) { note ->
          Card(
            onClick = { onOpenNote(note.id) },
            modifier = Modifier.fillMaxWidth().testTag("private-note-${note.id.value}"),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
          ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
              Text(
                text = note.title.ifBlank { "无标题笔记" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
              )
              Text(
                text = privateDate(note.updatedAtMillis),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
              )
            }
          }
        }
      }
    }
    FloatingActionButton(
      onClick = onCreateNote,
      modifier = Modifier.align(Alignment.End).padding(16.dp).testTag("private-new-note"),
      containerColor = MaterialTheme.colorScheme.primary,
      contentColor = MaterialTheme.colorScheme.onPrimary,
      elevation = FloatingActionButtonDefaults.elevation(
        defaultElevation = 0.dp,
        pressedElevation = 0.dp,
        focusedElevation = 0.dp,
        hoveredElevation = 0.dp,
      ),
    ) {
      Text("+", style = MaterialTheme.typography.headlineMedium)
    }
  }
}

private fun privateDate(timestampMillis: Long): String =
  SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()).format(Date(timestampMillis))

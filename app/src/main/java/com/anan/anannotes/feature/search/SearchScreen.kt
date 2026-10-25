package com.anan.anannotes.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.core.model.NoteSearchResult
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SearchScreen(
  onBack: () -> Unit,
  onOpenNote: (noteId: String) -> Unit,
) {
  val viewModel: SearchViewModel = viewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  PageScaffold(modifier = Modifier.testTag("screen-search")) {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
    CompactPageHeader(title = "搜索笔记", onBack = onBack, testTag = "search-page-header")
    Surface(
      modifier = Modifier.fillMaxWidth().height(54.dp).testTag("search-field-surface"),
      shape = RoundedCornerShape(18.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        SearchGlyph(MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(10.dp))
        BasicTextField(
          value = state.query,
          onValueChange = viewModel::onQueryChanged,
          modifier = Modifier.weight(1f).testTag("search-query"),
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = {}),
          decorationBox = { innerTextField ->
            Box {
              if (state.query.isEmpty()) {
                Text("搜索标题和正文", color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
              innerTextField()
            }
          },
        )
        if (state.query.isNotEmpty()) {
          IconButton(onClick = { viewModel.onQueryChanged("") }, modifier = Modifier.testTag("search-clear")) {
            Text("×", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.headlineSmall)
          }
        }
      }
    }

    when {
      state.isSearching && state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        CircularProgressIndicator(modifier = Modifier.padding(top = 28.dp), color = MaterialTheme.colorScheme.tertiary)
      }
      state.errorMessage != null -> Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
      state.query.isNotBlank() && state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text("没有找到相关笔记", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 28.dp))
      }
      else -> LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("search-results"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(state.results, key = NoteSearchResult::noteId) { result ->
          SearchResultCard(result, state.query, onClick = { onOpenNote(result.noteId) })
        }
      }
    }
    }
  }
}

@Composable
private fun SearchGlyph(color: Color) {
  Canvas(Modifier.size(20.dp)) {
    val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(color, radius = size.minDimension * 0.31f, center = Offset(size.width * 0.42f, size.height * 0.42f), style = stroke)
    drawLine(
      color = color,
      start = Offset(size.width * 0.64f, size.height * 0.64f),
      end = Offset(size.width * 0.9f, size.height * 0.9f),
      strokeWidth = 1.8.dp.toPx(),
      cap = StrokeCap.Round,
    )
  }
}

@Composable
private fun SearchResultCard(result: NoteSearchResult, query: String, onClick: () -> Unit) {
  val date = remember(result.updatedAtMillis) {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(result.updatedAtMillis))
  }
  Surface(
    modifier = Modifier.fillMaxWidth().testTag("search-result-${result.noteId}").clickable(onClick = onClick),
    shape = RoundedCornerShape(20.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
      HighlightedText(result.title.ifBlank { "无标题笔记" }, query, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(6.dp))
      HighlightedText(
        result.snippet,
        query,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("search-result-highlight-${result.noteId}"),
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = "${result.folderName} · $date",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun HighlightedText(
  text: String,
  query: String,
  style: androidx.compose.ui.text.TextStyle,
  modifier: Modifier = Modifier,
) {
  val emphasis = MaterialTheme.colorScheme.tertiary
  val annotatedText = remember(text, query, emphasis) { SearchHighlight.annotate(text, query, emphasis) }
  Text(annotatedText, modifier = modifier, style = style)
}

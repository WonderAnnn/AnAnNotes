package com.anan.anannotes.feature.privatemodule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PrivateModuleHeader(
  onBack: () -> Unit,
  onLock: () -> Unit,
  onShowExportHistory: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onBack, modifier = Modifier.testTag("private-back-button")) {
      PrivateModuleGlyph(
        kind = PrivateGlyphKind.BACK,
        contentDescription = "返回",
        tint = MaterialTheme.colorScheme.onSurface,
      )
    }
    Text(
      "私密笔记",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.weight(1f).padding(start = 8.dp),
    )
    androidx.compose.material3.TextButton(
      onClick = onShowExportHistory,
      modifier = Modifier.testTag("private-export-history-entry"),
    ) { Text("记录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    IconButton(onClick = onLock, modifier = Modifier.testTag("private-lock-button")) {
      PrivateModuleGlyph(
        kind = PrivateGlyphKind.LOCK,
        contentDescription = "锁定私密笔记",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun PrivateModuleNavigation(
  section: PrivateSection,
  onSelectSection: (PrivateSection) -> Unit,
  modifier: Modifier = Modifier,
) {
  NavigationBar(
    containerColor = MaterialTheme.colorScheme.surface,
    modifier = modifier,
  ) {
    NavigationBarItem(
      selected = section == PrivateSection.NOTES,
      onClick = { onSelectSection(PrivateSection.NOTES) },
      icon = {
        PrivateModuleGlyph(
          kind = PrivateGlyphKind.NOTES,
          contentDescription = "私密笔记",
          tint = if (section == PrivateSection.NOTES) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      },
      label = { Text("笔记") },
      colors = privateNavigationColors(),
      modifier = Modifier.testTag("private-nav-notes"),
    )
    NavigationBarItem(
      selected = section == PrivateSection.TODOS,
      onClick = { onSelectSection(PrivateSection.TODOS) },
      icon = {
        PrivateModuleGlyph(
          kind = PrivateGlyphKind.TODOS,
          contentDescription = "私密待办",
          tint = if (section == PrivateSection.TODOS) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      },
      label = { Text("待办") },
      colors = privateNavigationColors(),
      modifier = Modifier.testTag("private-nav-todos"),
    )
  }
}

@Composable
private fun privateNavigationColors() = NavigationBarItemDefaults.colors(
  selectedIconColor = MaterialTheme.colorScheme.primary,
  selectedTextColor = MaterialTheme.colorScheme.onSurface,
  indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
  unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
  unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

private enum class PrivateGlyphKind { BACK, LOCK, NOTES, TODOS }

@Composable
private fun PrivateModuleGlyph(
  kind: PrivateGlyphKind,
  contentDescription: String,
  tint: androidx.compose.ui.graphics.Color,
) {
  Canvas(
    modifier = Modifier.size(24.dp).semantics { this.contentDescription = contentDescription },
  ) {
    val strokeWidth = 1.8.dp.toPx()
    val color = tint
    when (kind) {
      PrivateGlyphKind.BACK -> {
        drawLine(color, Offset(size.width * 0.82f, size.height * 0.5f), Offset(size.width * 0.2f, size.height * 0.5f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.5f), Offset(size.width * 0.48f, size.height * 0.22f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.5f), Offset(size.width * 0.48f, size.height * 0.78f), strokeWidth, StrokeCap.Round)
      }
      PrivateGlyphKind.LOCK -> {
        val shackle = Path().apply {
          moveTo(size.width * 0.3f, size.height * 0.46f)
          lineTo(size.width * 0.3f, size.height * 0.36f)
          quadraticTo(size.width * 0.3f, size.height * 0.12f, size.width * 0.5f, size.height * 0.12f)
          quadraticTo(size.width * 0.7f, size.height * 0.12f, size.width * 0.7f, size.height * 0.36f)
          lineTo(size.width * 0.7f, size.height * 0.46f)
        }
        drawPath(shackle, color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        drawRoundRect(
          color = color,
          topLeft = Offset(size.width * 0.2f, size.height * 0.42f),
          size = Size(size.width * 0.6f, size.height * 0.46f),
          cornerRadius = CornerRadius(size.width * 0.09f),
          style = Stroke(width = strokeWidth),
        )
      }
      PrivateGlyphKind.NOTES -> {
        drawRoundRect(
          color = color,
          topLeft = Offset(size.width * 0.17f, size.height * 0.13f),
          size = Size(size.width * 0.66f, size.height * 0.74f),
          cornerRadius = CornerRadius(size.width * 0.1f),
          style = Stroke(width = strokeWidth),
        )
        repeat(3) { row ->
          val y = size.height * (0.36f + row * 0.17f)
          drawLine(color, Offset(size.width * 0.31f, y), Offset(size.width * 0.69f, y), strokeWidth, StrokeCap.Round)
        }
      }
      PrivateGlyphKind.TODOS -> {
        repeat(3) { row ->
          val top = size.height * (0.14f + row * 0.25f)
          drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.13f, top),
            size = Size(size.width * 0.25f, size.height * 0.2f),
            cornerRadius = CornerRadius(size.width * 0.045f),
            style = Stroke(width = strokeWidth),
          )
          val y = top + size.height * 0.1f
          drawLine(color, Offset(size.width * 0.5f, y), Offset(size.width * 0.87f, y), strokeWidth, StrokeCap.Round)
        }
      }
    }
  }
}

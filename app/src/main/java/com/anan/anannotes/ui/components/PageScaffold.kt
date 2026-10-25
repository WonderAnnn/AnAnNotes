package com.anan.anannotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Draws the page background edge-to-edge and constrains interactive content to one safe area. */
@Composable
fun PageScaffold(
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.background,
  windowInsets: WindowInsets = WindowInsets.safeDrawing,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier = Modifier.fillMaxSize().background(containerColor).then(modifier),
  ) {
    Box(
      modifier = Modifier.fillMaxSize().pageSafeDrawingInsets(windowInsets),
      content = content,
    )
  }
}

package com.anan.anannotes.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Applies the supplied page-safe area once and passes the consumed insets to descendants. */
@Composable
fun Modifier.pageSafeDrawingInsets(
  insets: WindowInsets = WindowInsets.safeDrawing,
): Modifier = windowInsetsPadding(insets)

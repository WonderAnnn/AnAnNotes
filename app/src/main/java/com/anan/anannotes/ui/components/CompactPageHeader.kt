package com.anan.anannotes.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CompactPageHeader(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  testTag: String = "secondary-page-header",
) {
  Row(
    modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(testTag),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(
      onClick = onBack,
      modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = "返回" }
        .testTag("page-back-arrow"),
    ) {
      Text("‹", color = MaterialTheme.colorScheme.onSurface, fontSize = 36.sp, lineHeight = 38.sp)
    }
    Spacer(Modifier.width(4.dp))
    Text(
      text = title,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

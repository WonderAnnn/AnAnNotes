package com.anan.anannotes.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.ui.theme.NoteSkin

@Composable
fun FormattedTextBlockField(
  value: TextFieldValue,
  formatting: BlockFormattingState,
  textStyle: TextStyle,
  cursorBrush: Brush,
  skin: NoteSkin,
  modifier: Modifier = Modifier,
  placeholder: String? = null,
  onValueChange: (TextFieldValue) -> Unit,
  onResetUnsupportedFormatting: () -> Unit,
) {
  val supported = formatting as? BlockFormattingState.Supported
  val isUnsupported = supported == null
  val fieldTextStyle = if (supported?.value?.presentation == TextBlockPresentation.HEADING) {
    textStyle.copy(
      fontSize = textStyle.fontSize * 1.15f,
      fontWeight = FontWeight.SemiBold,
      lineHeight = textStyle.lineHeight * 1.12f,
    )
  } else {
    textStyle
  }
  val annotated = remember(value.text, formatting) {
    buildAnnotatedString {
      append(value.text)
      supported?.value?.inlineStyles?.forEach { range ->
        addStyle(
          style = range.style.toSpanStyle(),
          start = range.startUtf16,
          end = range.endUtf16,
        )
      }
    }
  }
  val formattedValue = remember(annotated, value.selection, value.composition) {
    TextFieldValue(
      annotatedString = annotated,
      selection = value.selection,
      composition = value.composition,
    )
  }
  var confirmReset by remember { mutableStateOf(false) }

  Column(modifier = Modifier.fillMaxWidth()) {
    if (isUnsupported) {
      Text(
        text = "格式版本不兼容，当前仅可复制正文",
        color = skin.secondaryText,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 6.dp),
      )
    }
    BasicTextField(
      value = formattedValue,
      onValueChange = onValueChange,
      modifier = modifier.fillMaxWidth(),
      textStyle = fieldTextStyle,
      cursorBrush = cursorBrush,
      readOnly = isUnsupported,
      decorationBox = { innerTextField ->
        Box(modifier = Modifier.fillMaxWidth()) {
          if (formattedValue.text.isBlank() && placeholder != null) {
            Text(
              text = placeholder,
              style = fieldTextStyle,
              color = skin.secondaryText.copy(alpha = 0.72f),
            )
          }
          innerTextField()
        }
      },
    )
    if (isUnsupported) {
      TextButton(
        onClick = { confirmReset = true },
        modifier = Modifier.testTag("reset-unsupported-format"),
      ) {
        Text("移除不兼容格式并继续", color = skin.secondaryText)
      }
    }
  }

  if (confirmReset) {
    AlertDialog(
      onDismissRequest = { confirmReset = false },
      title = { Text("移除不兼容格式？") },
      text = { Text("这只会清除此文字块的格式元数据，正文会保留。") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmReset = false
            onResetUnsupportedFormatting()
          },
          modifier = Modifier.testTag("confirm-reset-unsupported-format"),
        ) {
          Text("移除格式并编辑")
        }
      },
      dismissButton = {
        TextButton(onClick = { confirmReset = false }) { Text("取消") }
      },
    )
  }
}

private fun InlineStyle.toSpanStyle(): SpanStyle = when (this) {
  InlineStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
  InlineStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
  InlineStyle.STRIKETHROUGH -> SpanStyle(textDecoration = TextDecoration.LineThrough)
}

package com.anan.anannotes.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.anan.anannotes.core.SearchRules

object SearchHighlight {
  fun annotate(text: String, query: String, accent: Color): AnnotatedString {
    val ranges = SearchRules.matchingRanges(text, query)
    return if (ranges.isEmpty()) AnnotatedString(text)
    else buildAnnotatedString {
      var cursor = 0
      ranges.forEach { range ->
        if (range.first >= cursor) {
          append(text.substring(cursor, range.first))
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent)) {
            append(text.substring(range.first, range.last + 1))
          }
          cursor = range.last + 1
        }
      }
      append(text.substring(cursor))
    }
  }
}

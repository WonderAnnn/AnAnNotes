package com.anan.anannotes.feature.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHighlightTest {
  @Test
  fun matchedTermsAreBoldAndUseTheSuppliedThemeAccentWithoutChangingText() {
    val accent = Color(0xFF718292)

    val annotated = SearchHighlight.annotate("Read this 计划 today", "计划 read", accent)

    assertEquals("Read this 计划 today", annotated.text)
    assertEquals(2, annotated.spanStyles.size)
    assertTrue(annotated.spanStyles.all { it.item.fontWeight == FontWeight.Bold })
    assertTrue(annotated.spanStyles.all { it.item.color == accent })
    assertEquals(listOf(0..3, 10..11), annotated.spanStyles.map { it.start..(it.end - 1) })
  }

  @Test
  fun blankOrUnmatchedQueriesDoNotAddHighlightStyles() {
    assertTrue(SearchHighlight.annotate("正文内容", " ", Color.Blue).spanStyles.isEmpty())
    assertTrue(SearchHighlight.annotate("正文内容", "missing", Color.Blue).spanStyles.isEmpty())
  }
}

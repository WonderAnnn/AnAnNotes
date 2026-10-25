package com.anan.anannotes.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRulesTest {
  @Test
  fun ftsExpressionTreatsInputAsTermsInsteadOfFtsSyntax() {
    assertEquals(
      "\"meeting\"* AND \"tomorrow\"*",
      SearchRules.ftsExpression("meeting tomorrow"),
    )
    assertEquals(
      "\"needle\"* AND \"OR\"* AND \"secret\"*",
      SearchRules.ftsExpression("needle\" OR secret"),
    )
  }

  @Test
  fun matchingRangesHighlightEveryQueryTermWithoutChangingText() {
    assertEquals(
      listOf(0..5, 8..13, 19..22),
      SearchRules.matchingRanges("Needle, needle and plan", "needle plan"),
    )
    assertTrue(SearchRules.matchingRanges("没有命中", "needle").isEmpty())
  }

  @Test
  fun aFullTextMatchMayPlaceQueryTermsInDifferentFieldsOrOrders() {
    assertTrue(SearchRules.matches("标题里的课程\n正文复习计划", "计划 课程"))
    assertEquals(false, SearchRules.matches("标题里的课程\n正文复习计划", "计划 缺失"))
  }

  @Test
  fun snippetCentersAroundChineseSubstringAndRetainsMatchedText() {
    val source = "开头内容。" + "无关内容".repeat(20) + "需要查找的关键词" + "后续内容".repeat(20)

    val snippet = SearchRules.snippet(source, "关键词", maxLength = 36)

    assertTrue(snippet.length <= 36)
    assertTrue(snippet.contains("关键词"))
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun searchInputWaitsForThreeHundredMillisecondsOfQuietAndEmitsLatestValue() = runTest {
    val input = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val results = mutableListOf<String>()
    val collector = backgroundScope.launch { input.debouncedSearchQueries().toList(results) }

    input.emit("n")
    runCurrent()
    advanceTimeBy(200)
    input.emit("笔记")
    runCurrent()
    advanceTimeBy(299)
    assertTrue(results.isEmpty())
    advanceTimeBy(1)
    runCurrent()

    assertEquals(listOf("笔记"), results)
    collector.cancel()
  }

  @Test
  fun blankQueriesDoNotProduceFtsExpressionsOrHighlights() {
    assertNull(SearchRules.ftsExpression("  \n "))
    assertTrue(SearchRules.matchingRanges("正文", " ").isEmpty())
  }
}

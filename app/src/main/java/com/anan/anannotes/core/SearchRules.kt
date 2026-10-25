package com.anan.anannotes.core

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object SearchRules {
  private val termPattern = Regex("[\\p{L}\\p{N}]+")

  fun normalizeQuery(query: String): String = query.trim().replace(Regex("\\s+"), " ")

  fun terms(query: String): List<String> = termPattern.findAll(normalizeQuery(query)).map { it.value }.toList()

  fun ftsExpression(query: String): String? = terms(query)
    .takeIf(List<String>::isNotEmpty)
    ?.joinToString(" AND ") { term -> "\"${term.replace("\"", "\"\"")}\"*" }

  fun matches(text: String, query: String): Boolean {
    val normalizedTerms = terms(query)
    return normalizedTerms.isNotEmpty() && normalizedTerms.all { text.contains(it, ignoreCase = true) }
  }

  fun matchingRanges(text: String, query: String): List<IntRange> {
    val ranges = terms(query).flatMap { term ->
      buildList {
        var startIndex = 0
        while (startIndex < text.length) {
          val matchStart = text.indexOf(term, startIndex, ignoreCase = true)
          if (matchStart < 0) break
          add(matchStart until (matchStart + term.length))
          startIndex = matchStart + term.length.coerceAtLeast(1)
        }
      }
    }.sortedBy { it.first }

    if (ranges.isEmpty()) return emptyList()
    return buildList {
      var current = ranges.first()
      ranges.drop(1).forEach { next ->
        if (next.first <= current.last + 1) current = current.first..maxOf(current.last, next.last)
        else {
          add(current)
          current = next
        }
      }
      add(current)
    }
  }

  fun snippet(text: String, query: String, maxLength: Int = 120): String {
    if (maxLength <= 0 || text.isEmpty()) return ""
    if (text.length <= maxLength) return text
    val matchStart = terms(query).mapNotNull { term ->
      text.indexOf(term, ignoreCase = true).takeIf { it >= 0 }
    }.minOrNull() ?: 0
    val contextLength = (maxLength - 2).coerceAtLeast(1)
    var start = (matchStart - contextLength / 3).coerceAtLeast(0)
    var end = (start + contextLength).coerceAtMost(text.length)
    if (end == text.length) start = (end - contextLength).coerceAtLeast(0)
    end = (start + contextLength).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
  }
}

@OptIn(FlowPreview::class)
fun Flow<String>.debouncedSearchQueries(): Flow<String> =
  debounce(SEARCH_DEBOUNCE_MILLIS).map(SearchRules::normalizeQuery).distinctUntilChanged()

const val SEARCH_DEBOUNCE_MILLIS = 300L

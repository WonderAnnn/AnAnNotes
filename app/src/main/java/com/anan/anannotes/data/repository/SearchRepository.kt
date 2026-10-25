package com.anan.anannotes.data.repository

import com.anan.anannotes.core.SearchRules
import com.anan.anannotes.core.model.NoteSearchResult
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.NoteSearchHitRow

class SearchRepository(private val database: AnAnDatabase) {
  suspend fun search(rawQuery: String): List<NoteSearchResult> {
    val query = SearchRules.normalizeQuery(rawQuery)
    val expression = SearchRules.ftsExpression(query) ?: return emptyList()
    val dao = database.noteSearchDao()
    val rows = (dao.searchFts(expression) + dao.searchSubstring(query))
      .distinctBy(NoteSearchHitRow::noteId)
      .filter { row -> SearchRules.matches("${row.title}\n${row.content}", query) }

    return rows
      .sortedWith(
        compareByDescending<NoteSearchHitRow> { SearchRules.matches(it.title, query) }
          .thenByDescending(NoteSearchHitRow::updatedAtMillis)
          .thenBy(NoteSearchHitRow::noteId),
      )
      .map { row ->
        val snippetSource = if (SearchRules.matchingRanges(row.content, query).isNotEmpty()) row.content else row.title
        NoteSearchResult(
          noteId = row.noteId,
          title = row.title,
          folderName = row.folderName,
          snippet = SearchRules.snippet(snippetSource, query),
          updatedAtMillis = row.updatedAtMillis,
        )
      }
  }
}

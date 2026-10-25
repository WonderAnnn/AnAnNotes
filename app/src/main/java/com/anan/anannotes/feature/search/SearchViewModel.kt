package com.anan.anannotes.feature.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.SearchRules
import com.anan.anannotes.core.debouncedSearchQueries
import com.anan.anannotes.core.model.NoteSearchResult
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.SearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
  val query: String = "",
  val results: List<NoteSearchResult> = emptyList(),
  val isSearching: Boolean = false,
  val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = SearchRepository(AnAnDatabase.getInstance(application))
  private val rawQuery = MutableStateFlow("")
  private val _uiState = MutableStateFlow(SearchUiState())
  val uiState = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      rawQuery.debouncedSearchQueries().collectLatest { query ->
        if (query.isBlank()) {
          _uiState.update { state ->
            if (SearchRules.normalizeQuery(state.query).isBlank()) state.copy(results = emptyList(), isSearching = false)
            else state
          }
          return@collectLatest
        }
        runCatching { repository.search(query) }
          .onSuccess { results ->
            _uiState.update { state ->
              if (SearchRules.normalizeQuery(state.query) == query) state.copy(results = results, isSearching = false, errorMessage = null)
              else state
            }
          }
          .onFailure {
            _uiState.update { state ->
              if (SearchRules.normalizeQuery(state.query) == query) {
                state.copy(results = emptyList(), isSearching = false, errorMessage = "搜索失败，请重试")
              } else state
            }
          }
      }
    }
  }

  fun onQueryChanged(query: String) {
    _uiState.update { it.copy(query = query, results = emptyList(), isSearching = SearchRules.normalizeQuery(query).isNotBlank(), errorMessage = null) }
    rawQuery.value = query
  }
}

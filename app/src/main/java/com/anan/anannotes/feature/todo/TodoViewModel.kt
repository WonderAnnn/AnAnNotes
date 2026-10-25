package com.anan.anannotes.feature.todo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.model.TodoFeedItem
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.TodoRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodoUiState(
  val showCompleted: Boolean = false,
  val items: List<TodoFeedItem> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = TodoRepository(AnAnDatabase.getInstance(application))
  private val showCompleted = MutableStateFlow(false)

  val uiState = showCompleted.flatMapLatest { includeCompleted ->
    repository.observe(includeCompleted).map { items -> TodoUiState(includeCompleted, items) }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoUiState())

  fun setShowCompleted(show: Boolean) {
    showCompleted.value = show
  }

  fun setCompleted(todoId: String, completed: Boolean) {
    viewModelScope.launch { repository.setCompleted(todoId, completed) }
  }
}

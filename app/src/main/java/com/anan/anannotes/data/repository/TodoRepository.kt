package com.anan.anannotes.data.repository

import com.anan.anannotes.core.model.TodoFeedItem
import com.anan.anannotes.data.local.AnAnDatabase
import kotlinx.coroutines.flow.Flow

class TodoRepository(
  private val database: AnAnDatabase,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  fun observe(includeCompleted: Boolean): Flow<List<TodoFeedItem>> =
    database.todoDao().observeFeed(includeCompleted)

  suspend fun setCompleted(todoId: String, completed: Boolean) {
    database.todoDao().setCompleted(todoId, completed, nowMillis())
  }
}

package com.anan.anannotes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import com.anan.anannotes.MainActivity
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchAndTodoTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val createdNoteIds = mutableListOf<String>()

  @Test
  fun searchShowsAndOpensMatchingNoteWithHighlightedSnippet() {
    val noteId = createNote(
      NoteDraft(
        title = "搜索结果标题",
        titleManuallySet = true,
        blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "这段正文包含可搜索的计划关键词。")),
      ),
    )

    composeRule.onNodeWithTag("search-entry").performClick()
    composeRule.onNodeWithTag("search-query").performTextInput("计划关键词")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("search-result-$noteId", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("search-result-$noteId").assertIsDisplayed()
    composeRule.onNodeWithTag("search-result-highlight-$noteId", useUnmergedTree = true).assertIsDisplayed()
    composeRule.onNodeWithTag("search-result-$noteId").performClick()
    composeRule.onNodeWithTag("screen-editor").assertIsDisplayed()
    composeRule.onNodeWithText("这段正文包含可搜索的计划关键词。", substring = true).assertIsDisplayed()
  }

  @Test
  fun todoPageHidesCompletedByDefaultAndCanToggleAndOpenOriginalNote() {
    val noteId = createNote(
      NoteDraft(
        title = "课程安排",
        titleManuallySet = true,
        blocks = listOf(
          NoteBlockDraft(id = "todo-open-ui", type = BlockType.TODO, text = "复习今天的课程"),
          NoteBlockDraft(id = "todo-done-ui", type = BlockType.TODO, text = "已经完成的作业"),
        ),
      ),
    )
    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    val doneTodo = runBlocking { database.todoDao().getByNote(noteId).single { it.blockId == "todo-done-ui" } }
    runBlocking { NoteRepository(database).setTodoCompleted(doneTodo.id, completed = true) }
    val openTodoId = runBlocking { database.todoDao().getByNote(noteId).single { it.blockId == "todo-open-ui" }.id }

    composeRule.onNodeWithTag("nav-todo").performClick()
    composeRule.onNodeWithTag("todo-item-$openTodoId").assertIsDisplayed()
    composeRule.onAllNodesWithText("已经完成的作业").assertCountEquals(0)
    composeRule.onNodeWithTag("todo-show-completed").performClick()
    composeRule.onNodeWithTag("todo-item-${doneTodo.id}").assertIsDisplayed()
    composeRule.onNodeWithTag("todo-checkbox-$openTodoId").performClick()
    composeRule.waitUntil(5_000) {
      runBlocking { database.todoDao().getByNote(noteId).single { it.id == openTodoId }.isCompleted }
    }
    composeRule.onNodeWithTag("todo-item-$openTodoId").performClick()
    composeRule.onNodeWithTag("screen-editor").assertIsDisplayed()
    composeRule.onNodeWithText("课程安排").assertIsDisplayed()
  }

  @After
  fun removeTestNotes() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = NoteRepository(AnAnDatabase.getInstance(context))
    createdNoteIds.forEach { noteId -> repository.deletePermanently(noteId) }
  }

  private fun createNote(draft: NoteDraft): String {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val noteId = checkNotNull(runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(draft) })
    createdNoteIds += noteId
    return noteId
  }
}

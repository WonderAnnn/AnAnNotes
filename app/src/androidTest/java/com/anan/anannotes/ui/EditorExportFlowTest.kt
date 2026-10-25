package com.anan.anannotes.ui

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.ExportHistoryRepository
import com.anan.anannotes.data.repository.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EditorExportFlowTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val token = UUID.randomUUID().toString()
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val database = AnAnDatabase.getInstance(context)
  private var initialNoteIds = emptySet<String>()
  private var exportedUri: Uri? = null

  @Before
  fun recordExistingNotesBeforeTest() = runBlocking {
    initialNoteIds = database.noteDao().observeAllActive().first().mapTo(mutableSetOf()) { it.id }
  }

  @Test
  fun exportingImmediatelyAfterTypingUsesTheCommittedCurrentRevision() {
    val title = "快速导出-$token"
    val finalText = "$title\n点击导出前最后输入的正文-$token"

    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput(finalText)
    composeRule.onNodeWithTag("editor-export-button").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("editor-export-format-sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("export-format-png-social-square").performClick()

    composeRule.waitUntil(30_000) {
      val noteId = noteIdForTitle(title) ?: return@waitUntil false
      val history = runBlocking { ExportHistoryRepository(database).latest() }
        .firstOrNull { it.noteId == noteId && it.succeeded }
      if (history != null) exportedUri = Uri.parse(history.destinationUri)
      history != null
    }

    val noteId = checkNotNull(noteIdForTitle(title))
    val document = runBlocking { NoteRepository(database).getDocumentSnapshot(noteId) }
    assertNotNull(document)
    assertTrue(checkNotNull(document).blocks.any { it.text == finalText })
    val history = runBlocking { ExportHistoryRepository(database).latest() }
      .firstOrNull { it.noteId == noteId && it.succeeded }
    assertNotNull(history)
    assertEquals("PNG_SOCIAL_SQUARE", checkNotNull(history).format.name)
  }

  @Test
  fun emptyNewDraftDoesNotCreateANoteFileOrExportRecord() {
    val imageCountBefore = exportedImageCount()
    openNewNote()
    composeRule.onNodeWithTag("editor-export-button").assertIsDisplayed().performClick()
    composeRule.onNodeWithTag("export-format-png-social-square").performClick()
    composeRule.onNodeWithText("笔记还没有内容，暂时无法导出。").assertIsDisplayed()

    val savedNoteIds = runBlocking { database.noteDao().observeAllActive().first().mapTo(mutableSetOf()) { it.id } }
    assertEquals(initialNoteIds, savedNoteIds)
    assertTrue(runBlocking { ExportHistoryRepository(database).latest() }.none { it.fileName.contains(token) })
    assertEquals(imageCountBefore, exportedImageCount())
  }

  @Test
  fun emptyExistingNoteCannotBeExported() {
    val title = "清空后禁止导出-$token"
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("$title\n正文")
    composeRule.waitUntil(30_000) { noteIdForTitle(title) != null }
    val noteId = checkNotNull(noteIdForTitle(title))

    composeRule.onNodeWithTag("editor-body").performTextClearance()
    composeRule.onNodeWithTag("editor-export-button").performClick()
    composeRule.onNodeWithTag("export-format-png-social-square").performClick()
    composeRule.onNodeWithText("笔记还没有内容，暂时无法导出。").assertIsDisplayed()

    assertTrue(runBlocking { ExportHistoryRepository(database).latest() }.none { it.noteId == noteId })
  }

  @Test
  fun exportWaitsForPendingTodoCompletionWrite() {
    val title = "待办状态快速导出-$token"
    val todoText = "立即完成-$token"
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("$title\n正文")
    composeRule.onNodeWithTag("editor-insert-todo").performClick()
    composeRule.onNodeWithTag("editor-todo-text").performTextInput(todoText)
    composeRule.waitUntil(30_000) {
      val noteId = noteIdForTitle(title) ?: return@waitUntil false
      runBlocking { database.todoDao().getByNote(noteId).any { it.text == todoText } }
    }
    val noteId = checkNotNull(noteIdForTitle(title))

    composeRule.onNodeWithTag("editor-todo-checkbox").performClick()
    composeRule.onNodeWithTag("editor-export-button").performClick()
    composeRule.onNodeWithTag("export-format-png-social-square").performClick()
    composeRule.waitUntil(30_000) {
      val history = runBlocking { ExportHistoryRepository(database).latest() }
        .firstOrNull { it.noteId == noteId && it.succeeded }
      if (history != null) exportedUri = Uri.parse(history.destinationUri)
      history != null
    }

    assertTrue(runBlocking { database.todoDao().getByNote(noteId).single { it.text == todoText }.isCompleted })
  }

  @After
  fun removeOnlyNotesCreatedDuringThisTest() = runBlocking {
    exportedUri?.let { context.contentResolver.delete(it, null, null) }
    val newNoteIds = database.noteDao().observeAllActive().first().map { it.id }.toSet() - initialNoteIds
    newNoteIds.forEach { noteId ->
      database.noteDao().deletePermanently(noteId)
      database.openHelper.writableDatabase.execSQL("DELETE FROM export_history WHERE noteId = ?", arrayOf(noteId))
    }
  }

  private fun noteIdForTitle(title: String): String? = runBlocking {
    database.noteDao().observeAllActive().first().firstOrNull { it.title == title }?.id
  }

  private fun openNewNote() {
    composeRule.onNodeWithTag("new-note-button").performClick()
    composeRule.waitUntil(10_000) {
      composeRule.onAllNodesWithTag("editor-metadata").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun exportedImageCount(): Int = context.contentResolver.query(
    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    arrayOf(MediaStore.Images.Media._ID),
    "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
    arrayOf("Pictures/AnAn Notes/Exports/"),
    null,
  )?.use { it.count } ?: 0
}

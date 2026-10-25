package com.anan.anannotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.systemFolderCallback
import com.anan.anannotes.data.repository.FolderRepository
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.data.repository.SearchRepository
import com.anan.anannotes.data.repository.TodoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchAndTodoRepositoryTest {
  private lateinit var database: AnAnDatabase
  private lateinit var notes: NoteRepository
  private lateinit var search: SearchRepository
  private lateinit var todos: TodoRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, AnAnDatabase::class.java)
      .allowMainThreadQueries()
      .addCallback(systemFolderCallback)
      .build()
    notes = NoteRepository(database)
    search = SearchRepository(database)
    todos = TodoRepository(database)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun ordinarySearchFindsTitleTextQuoteTodoAndSuccessfulOcrIncludingChineseSubstrings() = runBlocking {
    val noteId = checkNotNull(notes.saveDraft(
      NoteDraft(
        title = "普通标题关键词",
        titleManuallySet = true,
        blocks = listOf(
          NoteBlockDraft(type = BlockType.TEXT, text = "正文中的英文needle"),
          NoteBlockDraft(type = BlockType.QUOTE, text = "引用中的引用命中"),
          NoteBlockDraft(type = BlockType.TODO, text = "待办中的购买牛奶"),
          NoteBlockDraft(type = BlockType.OCR_TEXT, text = "识别出的票据号码", ocrStatus = OcrStatus.SUCCEEDED),
          NoteBlockDraft(type = BlockType.OCR_TEXT, text = "不应索引失败OCR内容", ocrStatus = OcrStatus.FAILED),
          NoteBlockDraft(type = BlockType.IMAGE, assetPath = "images/never-index-image-bytes.jpg"),
        ),
      ),
    ))

    assertEquals(noteId, search.search("标题关键").single().noteId)
    assertEquals(noteId, search.search("needle").single().noteId)
    assertEquals(noteId, search.search("引用命中").single().noteId)
    assertEquals(noteId, search.search("购买牛奶").single().noteId)
    assertEquals(noteId, search.search("票据号码").single().noteId)
    assertTrue(search.search("失败OCR").isEmpty())
    assertTrue(search.search("never-index-image").isEmpty())
  }

  @Test
  fun ordinarySearchHidesTrashedNotesAndRestoresTheirSearchability() = runBlocking {
    val noteId = checkNotNull(notes.saveDraft(
      NoteDraft(title = "唯一回收站检索词", titleManuallySet = true),
    ))
    assertEquals(noteId, search.search("回收站检索").single().noteId)

    notes.moveToTrash(noteId)
    assertTrue(search.search("回收站检索").isEmpty())

    notes.restore(noteId)
    assertEquals(noteId, search.search("回收站检索").single().noteId)

    notes.deletePermanently(noteId)
    assertTrue(search.search("回收站检索").isEmpty())
  }

  @Test
  fun todoFeedHidesCompletedItemsByDefaultAndIncludesNoteAndFolderContext() = runBlocking {
    val folder = FolderRepository(database, newId = { "folder-study" }).createFolder("学习资料")
    val noteId = checkNotNull(notes.saveDraft(
      NoteDraft(
        folderId = folder.id,
        title = "英语复习",
        titleManuallySet = true,
        blocks = listOf(
          NoteBlockDraft(id = "todo-open-block", type = BlockType.TODO, text = "复习单词"),
          NoteBlockDraft(id = "todo-done-block", type = BlockType.TODO, text = "整理笔记"),
        ),
      ),
    ))
    val noteTodos = database.todoDao().getByNote(noteId)
    val completeId = checkNotNull(noteTodos.first { it.blockId == "todo-done-block" }.id)
    notes.setTodoCompleted(completeId, completed = true)

    val defaultItems = todos.observe(includeCompleted = false).first()
    val allItems = todos.observe(includeCompleted = true).first()

    assertEquals(listOf("复习单词"), defaultItems.map { it.text })
    assertEquals("英语复习", defaultItems.single().noteTitle)
    assertEquals("学习资料", defaultItems.single().folderName)
    assertEquals(setOf("复习单词", "整理笔记"), allItems.map { it.text }.toSet())
    assertTrue(allItems.single { it.isCompleted }.isCompleted)
  }
}

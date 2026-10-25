package com.anan.anannotes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.systemFolderCallback
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.repository.FolderRepository
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.data.repository.NoteSaveResult
import com.anan.anannotes.feature.ocr.OcrJobEvent
import com.anan.anannotes.data.repository.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class NoteRepositoryTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  private lateinit var database: AnAnDatabase
  private var clock = 100L
  private var nextId = 0

  private val idGenerator: () -> String = { "generated-${++nextId}" }

  private lateinit var notes: NoteRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database =
      Room.inMemoryDatabaseBuilder(context, AnAnDatabase::class.java)
        .allowMainThreadQueries()
        .addCallback(systemFolderCallback)
        .build()
    notes = NoteRepository(database, nowMillis = { ++clock }, newId = idGenerator)
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun savesBlocksAndTodosThenPreservesNoteAndCompletionWhenEdited() = runBlocking {
    val draft =
      NoteDraft(
        title = "混合笔记",
        blocks = listOf(
          NoteBlockDraft(type = BlockType.TEXT, text = "正文内容"),
          NoteBlockDraft(type = BlockType.IMAGE, assetPath = "images/original.jpg", widthPercent = 73f, aspectRatio = 1.5f),
          NoteBlockDraft(type = BlockType.TODO, text = "待办内容"),
        ),
      )

    val noteId = notes.saveDraft(draft)
    assertNotNull(noteId)
    assertNull(notes.saveDraft(NoteDraft(title = "  ")))

    val saved = notes.observeNote(checkNotNull(noteId)).first()
    assertEquals(3, saved?.blocks?.size)
    assertEquals(73f, saved?.blocks?.get(1)?.widthPercent)
    assertEquals(1.5f, saved?.blocks?.get(1)?.aspectRatio)
    assertFalse(saved?.todos?.single()?.isCompleted ?: true)
    val preview = notes.observeAllPreviews().first().single()
    assertEquals("正文内容", preview.summaryText)
    assertNull(preview.firstImagePreviewPath)
    assertEquals("images/original.jpg", preview.firstImageAssetPath)
    assertEquals(1.5f, preview.firstImageAspectRatio)

    val todo = database.todoDao().getByNote(checkNotNull(noteId)).single()
    notes.setTodoCompleted(todo.id, completed = true)
    val editedId = notes.saveDraft(draft.copy(title = "修改后的标题"), existingNoteId = noteId)

    assertEquals(noteId, editedId)
    assertTrue(notes.observeNote(checkNotNull(noteId)).first()?.todos?.single()?.isCompleted ?: false)
    assertEquals("修改后的标题", notes.observeNote(checkNotNull(noteId)).first()?.note?.title)
  }

  @Test
  fun temporarilyBlankTodoKeepsItsCompletionAndRemainsHiddenFromTodoFeed() = runBlocking {
    val body = NoteBlockDraft(id = "body-before-todo", type = BlockType.TEXT, text = "正文")
    val todo = NoteBlockDraft(id = "completed-todo", type = BlockType.TODO, text = "已完成任务")
    val draft = NoteDraft(title = "改写已完成待办", titleManuallySet = true, blocks = listOf(body, todo))
    val noteId = checkNotNull(notes.saveDraft(draft))
    val originalTodo = database.todoDao().getByNote(noteId).single()
    notes.setTodoCompleted(originalTodo.id, completed = true)

    notes.saveDraft(draft.copy(blocks = listOf(body, todo.copy(text = ""))), existingNoteId = noteId)

    val blankTodo = database.todoDao().getByNote(noteId).single()
    assertEquals(originalTodo.id, blankTodo.id)
    assertEquals("", blankTodo.text)
    assertTrue("清空编辑中的待办后应保留其完成状态", blankTodo.isCompleted)
    assertTrue("空白待办不应出现在待办页", database.todoDao().observeFeed(includeCompleted = true).first().isEmpty())

    notes.saveDraft(draft.copy(blocks = listOf(body, todo.copy(text = "改写后的任务"))), existingNoteId = noteId)

    val rewrittenTodo = database.todoDao().getByNote(noteId).single()
    assertEquals(originalTodo.id, rewrittenTodo.id)
    assertEquals("改写后的任务", rewrittenTodo.text)
    assertTrue("同一待办块改写后应保留完成状态", rewrittenTodo.isCompleted)
    assertEquals(1, database.todoDao().observeFeed(includeCompleted = true).first().size)
  }

  @Test
  fun readsAnExportDocumentSnapshotWithBlocksAndTodoState() = runBlocking {
    val noteId = checkNotNull(
      notes.saveDraft(
        NoteDraft(
          title = "导出快照",
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(type = BlockType.TEXT, text = "快照正文"),
            NoteBlockDraft(type = BlockType.TODO, text = "已完成事项"),
          ),
        ),
      ),
    )
    val todo = database.todoDao().getByNote(noteId).single()
    notes.setTodoCompleted(todo.id, completed = true)

    val snapshot = notes.getDocumentSnapshot(noteId)

    assertEquals("导出快照", snapshot?.note?.title)
    assertEquals(listOf("快照正文", "已完成事项"), snapshot?.blocks?.map { it.text })
    assertTrue(snapshot?.todos?.single()?.isCompleted == true)
  }

  @Test
  fun pendingOcrJobRetainsAdjacentImageAsItsSourceWhenAvailable() = runBlocking {
    val image = NoteBlockDraft(type = BlockType.IMAGE, assetPath = "notes/assets/ocr-source.jpg")
    val ocr = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)
    val noteId = checkNotNull(notes.saveDraft(NoteDraft(blocks = listOf(image, ocr))))

    val jobs = database.blockDao().getPendingOcrJobs()

    assertEquals(1, jobs.size)
    assertEquals(noteId, jobs.single().noteId)
    assertEquals(ocr.id, jobs.single().ocrBlockId)
    assertEquals("notes/assets/ocr-source.jpg", jobs.single().imageAssetPath)
  }

  @Test
  fun pendingTextOnlyOcrJobIsIncludedForRestartRecovery() = runBlocking {
    val pendingOcr = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)
    val pendingNoteId = checkNotNull(notes.saveDraft(NoteDraft(blocks = listOf(pendingOcr))))
    val interruptedOcr = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)
    val interruptedNoteId = checkNotNull(notes.saveDraft(NoteDraft(blocks = listOf(interruptedOcr))))
    assertEquals(OcrStatus.RUNNING, notes.transitionOcrJob(interruptedNoteId, interruptedOcr.id, OcrJobEvent.START))

    val jobs = notes.pendingOcrJobs()

    assertEquals(setOf(pendingOcr.id, interruptedOcr.id), jobs.map { it.ocrBlockId }.toSet())
    assertEquals(setOf(pendingNoteId, interruptedNoteId), jobs.map { it.noteId }.toSet())
    assertTrue("纯文字 OCR 应由稳定块 ID 对应的持久化暂存图提供输入", jobs.all { it.imageAssetPath == null })
  }

  @Test
  fun cancelledOcrCannotBeOverwrittenByLateWorkerCompletion() = runBlocking {
    val image = NoteBlockDraft(type = BlockType.IMAGE, assetPath = "notes/assets/ocr-cancel.jpg")
    val ocr = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)
    val noteId = checkNotNull(notes.saveDraft(NoteDraft(blocks = listOf(image, ocr))))

    assertEquals(OcrStatus.RUNNING, notes.transitionOcrJob(noteId, ocr.id, OcrJobEvent.START))
    assertEquals(OcrStatus.CANCELLED, notes.transitionOcrJob(noteId, ocr.id, OcrJobEvent.CANCEL))
    assertEquals(OcrStatus.CANCELLED, notes.transitionOcrJob(noteId, ocr.id, OcrJobEvent.SUCCEED, "must not appear"))

    val stored = notes.getDocumentSnapshot(noteId)?.blocks?.single { it.id == ocr.id }
    assertEquals(OcrStatus.CANCELLED, stored?.ocrStatus)
    assertNull(stored?.text)
  }

  @Test
  fun successfulOcrPreservesLineBreaksAndEntersSearchIndex() = runBlocking {
    val image = NoteBlockDraft(type = BlockType.IMAGE, assetPath = "notes/assets/ocr-success.jpg")
    val ocr = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)
    val noteId = checkNotNull(notes.saveDraft(NoteDraft(title = "票据", blocks = listOf(image, ocr))))

    assertEquals(OcrStatus.RUNNING, notes.transitionOcrJob(noteId, ocr.id, OcrJobEvent.START))
    assertEquals(OcrStatus.SUCCEEDED, notes.transitionOcrJob(noteId, ocr.id, OcrJobEvent.SUCCEED, "第一行\r\n第二行"))

    assertEquals("第一行\n第二行", notes.getDocumentSnapshot(noteId)?.blocks?.single { it.id == ocr.id }?.text)
    assertEquals(noteId, SearchRepository(database).search("第二行").single().noteId)
  }

  @Test
  fun staleEditorSaveCannotEraseOcrResultCommittedByBackgroundWorker() = runBlocking {
    val image = NoteBlockDraft(type = BlockType.IMAGE, assetPath = "notes/assets/ocr-stale-save.jpg")
    val ocr = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)
    val draft = NoteDraft(title = "异步识别", titleManuallySet = true, blocks = listOf(image, ocr))
    val saved = notes.saveVersioned(draft) as NoteSaveResult.Saved
    notes.transitionOcrJob(saved.noteId, ocr.id, OcrJobEvent.START)
    notes.transitionOcrJob(saved.noteId, ocr.id, OcrJobEvent.SUCCEED, "扫描出的文字")

    val staleSave = notes.saveVersioned(
      draft,
      existingNoteId = saved.noteId,
      expectedContentRevision = saved.contentRevision,
    )

    assertTrue(staleSave is NoteSaveResult.Saved)
    assertEquals("扫描出的文字", notes.getDocumentSnapshot(saved.noteId)?.blocks?.single { it.id == ocr.id }?.text)
    assertEquals(saved.noteId, SearchRepository(database).search("扫描出的文字").single().noteId)
  }

  @Test
  fun savesFormattingSeparatelyAndKeepsSearchOnPlainText() = runBlocking {
    val formatting = BlockFormatting(
      presentation = TextBlockPresentation.HEADING,
      inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD)),
    )
    val noteId = checkNotNull(notes.saveDraft(
      NoteDraft(
        title = "格式往返",
        blocks = listOf(
          NoteBlockDraft(
            id = "formatted-block",
            type = BlockType.TEXT,
            text = "格式搜索正文",
            formatting = BlockFormattingState.Supported(formatting),
          ),
        ),
      ),
    ))

    val block = notes.observeNote(noteId).first()?.blocks?.single()
    val entity = database.blockDao().getByNote(noteId).single()

    assertEquals("格式搜索正文", block?.text)
    assertEquals(BlockFormattingState.Supported(formatting), block?.formatting)
    assertNotNull(entity.formattingBlob)
    assertEquals("格式搜索正文", entity.text)
    assertEquals(noteId, SearchRepository(database).search("格式搜索正文").single().noteId)
  }

  @Test
  fun defaultBodyFormattingIsStoredAsNull() = runBlocking {
    val noteId = checkNotNull(notes.saveDraft(
      NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "普通正文"))),
    ))

    assertNull(database.blockDao().getByNote(noteId).single().formattingBlob)
  }

  @Test
  fun invalidFormattingDoesNotCommitBodyOrAdvanceRevision() = runBlocking {
    val saved = notes.saveVersioned(
      NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "已保存正文"))),
    ) as NoteSaveResult.Saved

    val failure = runCatching {
      notes.saveVersioned(
        NoteDraft(
          blocks = listOf(
            NoteBlockDraft(
              type = BlockType.TEXT,
              text = "不应提交的正文",
              formatting = BlockFormattingState.Supported(
                BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 99, InlineStyle.BOLD))),
              ),
            ),
          ),
        ),
        existingNoteId = saved.noteId,
        expectedContentRevision = saved.contentRevision,
      )
    }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertEquals(1L, notes.observeNote(saved.noteId).first()?.note?.contentRevision)
    assertEquals("已保存正文", notes.observeNote(saved.noteId).first()?.blocks?.single()?.text)
  }

  @Test
  fun unsupportedFormattingBlobIsPreservedAcrossRepositoryRoundTrip() = runBlocking {
    val originalBlob = byteArrayOf(
      0x41, 0x4e, 0x46, 0x4d,
      0x7f,
      0x00,
      0x00, 0x00, 0x00, 0x00,
    )
    val noteId = checkNotNull(notes.saveDraft(
      NoteDraft(
        blocks = listOf(
          NoteBlockDraft(
            type = BlockType.TEXT,
            text = "未知格式仍保留",
            formatting = BlockFormattingState.Unsupported(originalBlob, com.anan.anannotes.core.model.FormattingDecodeError.UNSUPPORTED_VERSION),
          ),
        ),
      ),
    ))

    val persistedBlob = database.blockDao().getByNote(noteId).single().formattingBlob
    val loadedFormatting = notes.observeNote(noteId).first()?.blocks?.single()?.formatting

    assertEquals(originalBlob.toList(), persistedBlob?.toList())
    assertTrue(loadedFormatting is BlockFormattingState.Unsupported)
    assertEquals(originalBlob.toList(), (loadedFormatting as BlockFormattingState.Unsupported).originalBlob.toList())
  }

  @Test
  fun deletingFolderMovesNotesToUncategorizedWithoutDeletingThem() = runBlocking {
    val folders = FolderRepository(database, nowMillis = { 50L }, newId = { "folder-study" })
    val folder = folders.createFolder("学习资料")
    val noteId = notes.saveDraft(
      NoteDraft(folderId = folder.id, blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "保留"))),
    )

    assertTrue(folders.deleteFolder(folder.id))
    val saved = notes.observeNote(checkNotNull(noteId)).first()
    assertEquals(UNCATEGORIZED_FOLDER_ID, saved?.note?.folderId)
    assertNotNull(saved)
  }

  @Test
  fun versionedSaveRejectsStaleEditorWithoutOverwritingCurrentNote() = runBlocking {
    val original = notes.saveVersioned(
      NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "当前版本"))),
    ) as NoteSaveResult.Saved
    assertEquals(1L, original.contentRevision)

    val updated = notes.saveVersioned(
      NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "更新后的正式版本"))),
      existingNoteId = original.noteId,
      expectedContentRevision = original.contentRevision,
    ) as NoteSaveResult.Saved
    assertEquals(2L, updated.contentRevision)

    val conflict = notes.saveVersioned(
      NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "旧编辑器内容"))),
      existingNoteId = original.noteId,
      expectedContentRevision = original.contentRevision,
    )

    assertEquals(NoteSaveResult.Conflict(currentRevision = 2L), conflict)
    assertEquals(
      "更新后的正式版本",
      notes.observeNote(original.noteId).first()?.blocks?.single()?.text,
    )
  }

  @Test
  fun replayingImportRequestReturnsOriginalNoteWithoutOverwritingIt() = runBlocking {
    val requestId = "a809a591-b413-43cb-986d-158693604ec2"
    val original = notes.saveVersioned(
      NoteDraft(
        title = "首次导入",
        titleManuallySet = true,
        blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "首次导入正文")),
      ),
      importRequestId = requestId,
    ) as NoteSaveResult.Saved

    val replay = notes.saveVersioned(
      NoteDraft(
        title = "不应覆盖的重复导入",
        titleManuallySet = true,
        blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "重复载荷")),
      ),
      importRequestId = requestId,
    ) as NoteSaveResult.Saved

    assertEquals(original.noteId, replay.noteId)
    assertEquals(requestId, database.noteDao().getById(original.noteId)?.importRequestId)
    assertEquals("首次导入", notes.observeNote(original.noteId).first()?.note?.title)
    assertEquals("首次导入正文", notes.observeNote(original.noteId).first()?.blocks?.single()?.text)
    assertEquals(1, notes.observeAllNotes().first().count { it.id == original.noteId })
  }

  @Test
  fun trashedNotesAreHiddenFromOrdinaryTodoFeedAndCanBeRestored() = runBlocking {
    val noteId = notes.saveDraft(NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.TODO, text = "要处理"))))
    val todo = database.todoDao().getByNote(checkNotNull(noteId)).single()

    notes.moveToTrash(checkNotNull(noteId))
    assertTrue(database.todoDao().observeIncomplete().first().isEmpty())

    notes.restore(checkNotNull(noteId))
    assertEquals(1, database.todoDao().observeIncomplete().first().size)
    assertEquals(todo.id, database.todoDao().getByNote(checkNotNull(noteId)).single().id)
  }

  @Test
  fun originalAssetSurvivesTrashAndSharedAssetIsDeletedOnlyAfterLastReference() = runBlocking {
    val store = OriginalImageStore(temporaryFolder.newFolder("assets"))
    val asset = store.import(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "jpg")
    val repository = NoteRepository(database, newId = idGenerator, assetStore = store)
    val draft = NoteDraft(blocks = listOf(NoteBlockDraft(type = BlockType.IMAGE, assetPath = asset.relativePath, aspectRatio = 1f)))
    val firstNoteId = checkNotNull(repository.saveDraft(draft))
    val secondNoteId = checkNotNull(repository.saveDraft(draft))

    repository.moveToTrash(firstNoteId)
    assertTrue(store.resolve(asset.relativePath).exists())
    repository.restore(firstNoteId)
    repository.deletePermanently(firstNoteId)
    assertTrue(store.resolve(asset.relativePath).exists())

    repository.deletePermanently(secondNoteId)
    assertFalse(store.resolve(asset.relativePath).exists())
  }
}

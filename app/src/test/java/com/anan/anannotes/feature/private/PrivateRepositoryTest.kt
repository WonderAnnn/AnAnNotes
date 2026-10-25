package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.security.KeyDeriver
import com.anan.anannotes.security.PrivateCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PrivateRepositoryTest {
  @Test
  fun privateExportHistoryKeepsOnlyTheLatestHundredEntriesInTheUnlockedRepository() = runBlocking {
    val root = Files.createTempDirectory("anan-private-export-history-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()))
      val access = repository.setup("private-password".toCharArray(), null)

      repeat(105) { index ->
        repository.recordPrivateExport(
          access,
          PrivateExportHistoryRecord(
            id = "entry-$index",
            noteId = PrivateNoteId("note-private-$index"),
            noteTitle = "私密记录 $index",
            format = ExportFormat.TXT,
            fileName = "note-$index.txt",
            destinationUri = "content://exports/$index",
            createdAtMillis = index.toLong(),
            succeeded = true,
          ),
        )
      }

      val history = repository.listPrivateExportHistory(access)
      assertEquals(100, history.size)
      assertEquals("entry-104", history.first().id)
      assertEquals("entry-5", history.last().id)
      repository.lock()
      assertTrue(!access.isActive)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun exportGrantIsMintedOnlyForTheCurrentSessionAfterPasswordAuthentication() = runBlocking {
    val root = Files.createTempDirectory("anan-private-export-auth-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()))
      val access = repository.setup("private-password".toCharArray(), "recovery-key".toCharArray())
      val scope = PrivateExportScope(PrivateNoteId("selected-note"), ExportFormat.TXT, "content://selected/tree")

      val denied = repository.authorizeExport(access, "recovery-key".toCharArray(), scope, nowMillis = 1_000L)
      assertEquals(null, denied)
      assertTrue(access.isActive)

      val grant = repository.authorizeExport(access, "private-password".toCharArray(), scope, nowMillis = 2_000L)
      assertTrue(grant != null)
      assertTrue(grant!!.consume(access, scope, nowMillis = 2_001L))
      repository.lock()
      assertFalse(access.isActive)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun privateSearchAndTrashOperateOnlyOnUnlockedPrivateSnapshot() = runBlocking {
    val root = Files.createTempDirectory("anan-private-repository-").toFile()
    try {
      val store = PrivateStore(root, testCrypto())
      val repository = PrivateRepository(store, nowMillis = { 2_000L })
      val access = repository.setup("private-password".toCharArray(), null)
      val saveResult = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "draft-1",
          noteId = null,
          folderId = "private-uncategorized",
          title = "检查清单",
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(id = "body-1", type = BlockType.TEXT, text = "离线私密内容"),
            NoteBlockDraft(id = "todo-1", type = BlockType.TODO, text = "预约复查"),
          ),
          skinId = "deep-sea",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 1_500L,
        ),
      )

      assertTrue(saveResult is PrivateSaveResult.Saved)
      val savedId = (saveResult as PrivateSaveResult.Saved).noteId
      assertEquals(listOf(savedId), repository.search(access, "复查"))
      assertEquals(1, repository.listPreviews(access).size)

      repository.moveToTrash(access, savedId)
      assertTrue(repository.listPreviews(access).isEmpty())
      repository.restore(access, savedId)
      assertEquals("检查清单", repository.listPreviews(access).single().title)
      repository.lock()
      assertTrue(!access.isActive)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun blankNewPrivateNoteIsNotPersisted() = runBlocking {
    val root = Files.createTempDirectory("anan-private-empty-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()))
      val access = repository.setup("private-password".toCharArray(), null)
      val result = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "empty-draft",
          noteId = null,
          folderId = "private-uncategorized",
          title = "",
          titleManuallySet = false,
          blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "  ")),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 0L,
          savedAtMillis = 1L,
        ),
      )
      assertEquals(PrivateSaveResult.EmptyNotSaved, result)
      assertTrue(repository.listPreviews(access).isEmpty())
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun privateOcrResultStaysInEncryptedDraftAndDeletesItsEncryptedSourceWhenDone() = runBlocking {
    val root = Files.createTempDirectory("anan-private-ocr-").toFile()
    try {
      val store = PrivateStore(root, testCrypto())
      val repository = PrivateRepository(store)
      val access = repository.setup("private-ocr-password".toCharArray(), null)
      val sourceText = "private OCR source must never be plaintext on disk"
      val sourceBytes = sourceText.toByteArray()
      val sourceReference = store.writePrivateAsset(access, sourceBytes)
      sourceBytes.fill(0)
      repository.writeAtomic(
        access,
        PrivateEditSnapshot(
          draftId = "private-ocr-draft",
          noteId = null,
          folderId = "private-uncategorized",
          title = "OCR",
          titleManuallySet = true,
          blocks = listOf(NoteBlockDraft(
            id = "private-ocr-block",
            type = BlockType.OCR_TEXT,
            assetPath = sourceReference,
            ocrStatus = OcrStatus.PENDING,
          )),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 1L,
        ),
      )

      val releasedSource = repository.updatePrivateOcrBlock(
        access,
        blockId = "private-ocr-block",
        status = OcrStatus.SUCCEEDED,
        text = "识别结果第一行\nSecond line",
      )

      val persisted = repository.listRecoverable(access).single().blocks.single()
      assertEquals(OcrStatus.SUCCEEDED, persisted.ocrStatus)
      assertEquals("识别结果第一行\nSecond line", persisted.text)
      assertEquals(null, persisted.assetPath)
      assertEquals(sourceReference, releasedSource)
      assertTrue(store.deletePrivateAssetIfUnreferenced(access, releasedSource!!))
      assertTrue(root.walkTopDown().filter { it.isFile }.none { file ->
        file.readBytes().toString(Charsets.ISO_8859_1).contains(sourceText)
      })
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun privateTodoCompletionIsStoredOnlyInThePrivateVault() = runBlocking {
    val root = Files.createTempDirectory("anan-private-todos-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()), nowMillis = { 3_000L })
      val access = repository.setup("private-password".toCharArray(), null)
      val saved = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "todo-draft",
          noteId = null,
          folderId = "private-uncategorized",
          title = "课程安排",
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(id = "todo-a", type = BlockType.TODO, text = "复习章节"),
            NoteBlockDraft(id = "todo-b", type = BlockType.TODO, text = "提交作业"),
          ),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 2_000L,
        ),
      ) as PrivateSaveResult.Saved

      assertEquals(2, repository.listTodos(access).size)
      assertTrue(repository.setTodoCompleted(access, saved.noteId, "todo-a", completed = true))
      assertEquals(listOf("提交作业"), repository.listTodos(access).map { it.text })
      val completed = repository.listTodos(access, includeCompleted = true).single { it.todoId == "todo-a" }
      assertTrue(completed.completed)
      assertEquals("课程安排", completed.noteTitle)
      assertFalse(repository.setTodoCompleted(access, saved.noteId, "missing-todo", completed = true))
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun temporarilyBlankCompletedPrivateTodoKeepsCompletionButIsHiddenFromTodoList() = runBlocking {
    val root = Files.createTempDirectory("anan-private-blank-todo-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()), nowMillis = { 5_000L })
      val access = repository.setup("private-password".toCharArray(), null)
      val body = NoteBlockDraft(id = "body", type = BlockType.TEXT, text = "正文")
      val todo = NoteBlockDraft(id = "todo-done", type = BlockType.TODO, text = "已完成任务")
      val created = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "blank-private-todo",
          noteId = null,
          folderId = "private-uncategorized",
          title = "私密待办改写",
          titleManuallySet = true,
          blocks = listOf(body, todo),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 4_000L,
        ),
      ) as PrivateSaveResult.Saved
      repository.setTodoCompleted(access, created.noteId, todo.id, completed = true)
      val note = checkNotNull(repository.read(access, created.noteId))

      val blankBlocks = PrivateEditorRules.mergeBlocks(
        existing = note.blocks,
        blocks = note.blocks.map { if (it.id == todo.id) it.copy(text = "") else it },
        todos = listOf(PrivateTodoDraft(blockId = todo.id, text = "")),
      )
      repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "blank-private-todo",
          noteId = note.id,
          folderId = note.folderId,
          title = note.title,
          titleManuallySet = note.titleManuallySet,
          blocks = blankBlocks,
          skinId = note.skinId,
          baseContentRevision = note.contentRevision,
          localRevision = 2L,
          savedAtMillis = 4_500L,
        ),
      )

      val blankNote = checkNotNull(repository.read(access, note.id))
      assertTrue(blankNote.todos.single().completed)
      assertTrue(repository.listTodos(access, includeCompleted = true).isEmpty())

      val restoredText = "重新输入后的任务"
      repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "blank-private-todo",
          noteId = blankNote.id,
          folderId = blankNote.folderId,
          title = blankNote.title,
          titleManuallySet = blankNote.titleManuallySet,
          blocks = PrivateEditorRules.mergeBlocks(
            existing = blankNote.blocks,
            blocks = blankNote.blocks.map { if (it.id == todo.id) it.copy(text = restoredText) else it },
            todos = listOf(PrivateTodoDraft(blockId = todo.id, text = restoredText)),
          ),
          skinId = blankNote.skinId,
          baseContentRevision = blankNote.contentRevision,
          localRevision = 3L,
          savedAtMillis = 4_750L,
        ),
      )

      val rewrittenTodo = repository.listTodos(access, includeCompleted = true).single()
      assertEquals(restoredText, rewrittenTodo.text)
      assertTrue(rewrittenTodo.completed)
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun revokingAStaleSessionDoesNotLockTheCurrentPrivateSession() = runBlocking {
    val root = Files.createTempDirectory("anan-private-sessions-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()))
      val oldAccess = repository.setup("private-password".toCharArray(), null)
      val currentAccess = repository.unlockWithPassword("private-password".toCharArray())

      repository.revoke(oldAccess)

      assertTrue(currentAccess.isActive)
      assertEquals(PrivateModuleStatus.UNLOCKED, repository.status.value)
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun completedTodoStateFollowsStableBlockIdAfterInsertionAndReordering() = runBlocking {
    val root = Files.createTempDirectory("anan-private-todo-identity-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()), nowMillis = { 4_000L })
      val access = repository.setup("private-password".toCharArray(), null)
      val created = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "todo-identity-draft",
          noteId = null,
          folderId = "private-uncategorized",
          title = "安排",
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(id = "todo-a", type = BlockType.TODO, text = "任务 A"),
            NoteBlockDraft(id = "todo-b", type = BlockType.TODO, text = "任务 B"),
          ),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 3_000L,
        ),
      ) as PrivateSaveResult.Saved
      repository.setTodoCompleted(access, created.noteId, "todo-a", completed = true)
      val note = repository.read(access, created.noteId)!!
      val reorderedBlocks = PrivateEditorRules.mergeBlocks(
        existing = note.blocks,
        blocks = note.blocks,
        todos = listOf(
          PrivateTodoDraft(blockId = "todo-new", text = "新任务"),
          PrivateTodoDraft(blockId = "todo-b", text = "任务 B"),
          PrivateTodoDraft(blockId = "todo-a", text = "任务 A"),
        ),
      )

      val result = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "todo-identity-draft",
          noteId = note.id,
          folderId = note.folderId,
          title = note.title,
          titleManuallySet = note.titleManuallySet,
          blocks = reorderedBlocks,
          skinId = note.skinId,
          baseContentRevision = note.contentRevision,
          localRevision = 3L,
          savedAtMillis = 3_500L,
        ),
      )

      assertTrue(result is PrivateSaveResult.Saved)
      val todosById = repository.listTodos(access, includeCompleted = true).associateBy { it.todoId }
      assertEquals(listOf("todo-new", "todo-b", "todo-a"), repository.listTodos(access, true).map { it.todoId })
      assertTrue(todosById.getValue("todo-a").completed)
      assertFalse(todosById.getValue("todo-b").completed)
      assertFalse(todosById.getValue("todo-new").completed)
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun formattedPrivateDraftIsEncryptedRecoverableAndRevisionProtected() = runBlocking {
    val root = Files.createTempDirectory("anan-private-formatted-draft-").toFile()
    try {
      val repository = PrivateRepository(PrivateStore(root, testCrypto()))
      val access = repository.setup("private-draft-password".toCharArray(), null)
      val draft = PrivateEditSnapshot(
        draftId = "private-rich-draft-id",
        noteId = PrivateNoteId("private-note-a"),
        folderId = "private-uncategorized",
        title = "私密草稿标题-不应明文",
        titleManuallySet = true,
        blocks = listOf(
          NoteBlockDraft(
            id = "private-draft-block",
            type = BlockType.TEXT,
            text = "私密草稿正文-不应明文",
            formatting = BlockFormattingState.Supported(
              BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD))),
            ),
          ),
        ),
        skinId = "minimal-white",
        baseContentRevision = 9L,
        localRevision = 12L,
        savedAtMillis = 500L,
      )
      repository.writeAtomic(access, draft)

      assertTrue(root.walkTopDown().filter { it.isFile }.none { file ->
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        bytes.contains(draft.title) || bytes.contains(draft.blocks.single().text!!)
      })
      assertEquals(draft, repository.listRecoverable(access).single())
      repository.clearIfAtMost(access, draft.draftId, draft.localRevision - 1L)
      assertEquals(draft, repository.listRecoverable(access).single())

      repository.lock()
      val reopened = repository.unlockWithPassword("private-draft-password".toCharArray())
      assertEquals(draft, repository.listRecoverable(reopened).single())
      repository.clearIfAtMost(reopened, draft.draftId, draft.localRevision)
      assertTrue(repository.listRecoverable(reopened).isEmpty())
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  private fun testCrypto(): PrivateCrypto {
    val kdf = KeyDeriver { credential, salt, _ ->
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(salt.copyBytes(), "HmacSHA256"))
      val bytes = String(credential).toByteArray(Charsets.UTF_8)
      try {
        mac.doFinal(bytes)
      } finally {
        bytes.fill(0)
      }
    }
    return PrivateCrypto(SecureRandom(), kdf)
  }
}

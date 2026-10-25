package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.model.NoteDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class EditorDraftRestoreTransactionTest {
  private val root = Files.createTempDirectory("anan-draft-restore-").toFile()
  private val drafts = FileEditorDraftStore(File(root, "drafts"))
  private val transactions = EditorDraftRestoreTransactionStore(File(root, "drafts"))

  @After
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun stagedDraftIsNotVisibleAndRollbackRestoresOnlyReplacedDrafts() = runBlocking {
    val existing = snapshot("${UUID.randomUUID()}", "当前草稿")
    val unrelated = snapshot("${UUID.randomUUID()}", "其他草稿")
    val incoming = existing.copy(localRevision = 5L, draft = NoteDraft(title = "备份草稿"))
    drafts.writeAtomic(existing)
    drafts.writeAtomic(unrelated)
    val transactionId = UUID.randomUUID().toString()

    transactions.stage(transactionId, listOf(incoming))
    assertEquals(
      mapOf(existing.draftId to "当前草稿", unrelated.draftId to "其他草稿"),
      drafts.listRecoverable().associate { it.draftId to it.draft.title },
    )

    transactions.publish(transactionId)
    assertEquals(setOf("备份草稿", "其他草稿"), drafts.listRecoverable().map { it.draft.title }.toSet())

    transactions.rollback(transactionId)
    assertEquals(setOf("当前草稿", "其他草稿"), drafts.listRecoverable().map { it.draft.title }.toSet())
  }

  @Test
  fun committedDraftPublicationCanBeFinalizedWithoutRollingBack() = runBlocking {
    val transactionId = UUID.randomUUID().toString()
    val incoming = snapshot(UUID.randomUUID().toString(), "恢复草稿")
    transactions.stage(transactionId, listOf(incoming))
    transactions.publish(transactionId)

    transactions.finish(transactionId, committed = true)

    assertEquals(listOf(incoming), drafts.listRecoverable())
    assertTrue(File(root, "drafts").listFiles().orEmpty().none { it.name.startsWith(".restore-$transactionId") })
  }

  private fun snapshot(id: String, title: String) = EditorDraftSnapshot(
    draftId = id,
    noteId = null,
    folderId = "folder-1",
    baseContentRevision = 0L,
    localRevision = 1L,
    savedAtMillis = 10L,
    draft = NoteDraft(folderId = "folder-1", title = title),
  )
}

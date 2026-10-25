package com.anan.anannotes.feature.editor

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.data.repository.NoteSaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EditorImportRecoveryTest {
  @Test
  fun recreatingEditorForSavedImportReopensSameNoteInsteadOfImportingAgain() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val repository = NoteRepository(AnAnDatabase.getInstance(application))
    val requestId = UUID.randomUUID().toString()
    val title = "分享恢复-${requestId.take(8)}"
    val payload = "$title\n这是同一次分享请求的正文"
    val firstOwner = TestViewModelStoreOwner()
    val secondOwner = TestViewModelStoreOwner()

    try {
      val firstEditor = editor(firstOwner, application, requestId, payload)
      val firstSaved = withTimeout(10_000L) {
        firstEditor.uiState.first {
          it.isLoaded && it.noteId != null && it.saveStatus == EditorSaveStatus.SAVED
        }
      }
      val originalNoteId = checkNotNull(firstSaved.noteId)

      val restoredEditor = editor(secondOwner, application, requestId, payload)
      val restored = withTimeout(10_000L) {
        restoredEditor.uiState.first { it.isLoaded }
      }

      assertEquals(originalNoteId, restored.noteId)
      assertEquals(title, restored.titleForSave)
      assertEquals(1, repository.observeAllNotes().first().count { it.title == title })
      assertEquals(requestId, AnAnDatabase.getInstance(application).noteDao().getById(originalNoteId)?.importRequestId)
    } finally {
      firstOwner.viewModelStore.clear()
      secondOwner.viewModelStore.clear()
      repository.observeAllNotes().first()
        .filter { it.title == title }
        .forEach { repository.deletePermanently(it.id) }
    }
  }

  @Test
  fun matchingImportDraftIsOfferedForRecoveryWithoutReplayingPayload() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val repository = NoteRepository(AnAnDatabase.getInstance(application))
    val requestId = UUID.randomUUID().toString()
    val draftId = UUID.randomUUID().toString()
    val title = "导入草稿-${requestId.take(8)}"
    val restoredBody = "进程退出前已落盘的导入正文"
    val rawPayload = "原分享内容不能再次拼接"
    val store = FileEditorDraftStore(File(application.filesDir, "drafts"))
    val owner = TestViewModelStoreOwner()
    val snapshot = EditorDraftSnapshot(
      draftId = draftId,
      noteId = null,
      importRequestId = requestId,
      folderId = UNCATEGORIZED_FOLDER_ID,
      baseContentRevision = 0L,
      localRevision = 5L,
      savedAtMillis = System.currentTimeMillis(),
      draft = NoteDraft(
        folderId = UNCATEGORIZED_FOLDER_ID,
        title = title,
        titleManuallySet = true,
        blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = restoredBody)),
      ),
    )

    try {
      store.writeAtomic(snapshot)
      val viewModel = editor(owner, application, requestId, rawPayload)
      val pending = withTimeout(10_000L) {
        viewModel.uiState.first { it.isLoaded && it.pendingRecovery != null }
      }

      assertEquals(snapshot, pending.pendingRecovery)
      assertNull(pending.noteId)
      assertTrue(repository.observeAllNotes().first().none { it.title == title })

      viewModel.restorePendingDraft()
      val saved = withTimeout(10_000L) {
        viewModel.uiState.first {
          it.noteId != null && it.saveStatus == EditorSaveStatus.SAVED
        }
      }
      val note = repository.observeNote(checkNotNull(saved.noteId)).first()
      assertEquals(title, note?.note?.title)
      assertEquals(restoredBody, note?.blocks?.single { it.type == BlockType.TEXT }?.text)
      assertTrue(note?.blocks?.none { it.text == rawPayload } == true)
    } finally {
      owner.viewModelStore.clear()
      store.clearIfAtMost(draftId, Long.MAX_VALUE)
      repository.observeAllNotes().first()
        .filter { it.title == title }
        .forEach { repository.deletePermanently(it.id) }
    }
  }

  @Test
  fun committedImportWithUnlinkedEquivalentDraftDoesNotOfferDuplicateRecovery() = runBlocking {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val repository = NoteRepository(AnAnDatabase.getInstance(application))
    val requestId = UUID.randomUUID().toString()
    val draftId = UUID.randomUUID().toString()
    val title = "提交后崩溃-${requestId.take(8)}"
    val body = "已经提交的导入正文"
    val store = FileEditorDraftStore(File(application.filesDir, "drafts"))
    val owner = TestViewModelStoreOwner()
    var savedNoteId: String? = null
    val draft = NoteDraft(
      folderId = UNCATEGORIZED_FOLDER_ID,
      title = title,
      titleManuallySet = true,
      blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = body)),
    )

    try {
      val saved = repository.saveVersioned(draft, importRequestId = requestId) as NoteSaveResult.Saved
      savedNoteId = saved.noteId
      store.writeAtomic(
        EditorDraftSnapshot(
          draftId = draftId,
          noteId = null,
          folderId = UNCATEGORIZED_FOLDER_ID,
          baseContentRevision = 0L,
          localRevision = 1L,
          savedAtMillis = System.currentTimeMillis(),
          draft = draft,
          importRequestId = requestId,
        ),
      )

      val viewModel = editor(owner, application, requestId, "$title\n不同的重放载荷")
      val restored = withTimeout(10_000L) {
        viewModel.uiState.first { it.isLoaded }
      }

      assertEquals(saved.noteId, restored.noteId)
      assertNull(restored.pendingRecovery)
      assertEquals("已经提交的导入正文", repository.observeNote(saved.noteId).first()?.blocks?.single()?.text)
      assertTrue(store.listRecoverable().none { it.draftId == draftId })
    } finally {
      owner.viewModelStore.clear()
      store.clearIfAtMost(draftId, Long.MAX_VALUE)
      savedNoteId?.let { repository.deletePermanently(it) }
    }
  }

  private fun editor(
    owner: TestViewModelStoreOwner,
    application: Application,
    requestId: String,
    payload: String,
  ): EditorViewModel = ViewModelProvider(
    owner,
    EditorViewModelFactory(
      application = application,
      noteId = null,
      folderId = UNCATEGORIZED_FOLDER_ID,
      initialImportText = payload,
      importRequestId = requestId,
    ),
  )[EditorViewModel::class.java]

  private class TestViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
  }
}

package com.anan.anannotes.feature.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.BackupRestoreCommitEntity
import com.anan.anannotes.data.local.FolderEntity
import com.anan.anannotes.feature.editor.EditorDraftRestoreTransactionStore
import com.anan.anannotes.feature.editor.FileEditorDraftStore
import com.anan.anannotes.feature.privatemodule.PrivateStore
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordKind
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordSetSummary
import com.anan.anannotes.security.BackupRecordStreamSummary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppBackupRestoreStoreTest {
  private lateinit var database: AnAnDatabase
  private lateinit var filesRoot: File
  private lateinit var drafts: FileEditorDraftStore
  private lateinit var draftTransactions: EditorDraftRestoreTransactionStore
  private lateinit var privateStore: PrivateStore

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    database = Room.inMemoryDatabaseBuilder(context, AnAnDatabase::class.java).allowMainThreadQueries().build()
    filesRoot = Files.createTempDirectory("anan-restore-target-test").toFile()
    drafts = FileEditorDraftStore(File(filesRoot, "drafts"))
    draftTransactions = EditorDraftRestoreTransactionStore(File(filesRoot, "drafts"))
    privateStore = PrivateStore(filesRoot)
    runBlocking {
      database.folderDao().upsert(FolderEntity("system-uncategorized", "未分类", FolderKind.UNCATEGORIZED.name, 0L, Int.MIN_VALUE))
    }
  }

  @After
  fun tearDown() {
    database.close()
    filesRoot.deleteRecursively()
  }

  @Test
  fun typedRestoreAtomicallyWritesNotesTodosSearchAndRoomCommitMarker() = runBlocking {
    val summary = BackupRecordSetSummary(
      ordinaryFolders = 2,
      ordinaryNotes = 1,
      ordinaryBlocks = 2,
      ordinaryTodos = 1,
      ordinaryDrafts = 0,
      ordinaryDraftBlocks = 0,
      ordinaryAssets = 0,
      ordinaryAssetBytes = 0,
      privateNotes = 0,
      privateBlocks = 0,
      privateTodos = 0,
      privateDrafts = 0,
      privateDraftBlocks = 0,
      privateAssets = 0,
      privateAssetBytes = 0,
    )
    val folderKey = BackupObjectKey(BackupRecordDomain.ORDINARY, BackupRecordKind.FOLDER, "folder-study")
    val noteKey = BackupObjectKey(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, "note-restored")
    val resolution = ResolvedBackupConflictPlan(
      backupSessionId = "instrumentation-session",
      targetRevision = 0L,
      newObjects = setOf(folderKey, noteKey),
      identicalObjects = emptySet(),
      keepCurrent = emptySet(),
      replaceCurrent = emptySet(),
      keepBoth = emptySet(),
    )
    var completedTransactionId: String? = null
    val target = AppBackupRestoreStore(
      database = database,
      ordinaryDraftStore = drafts,
      ordinaryDraftTransactions = draftTransactions,
      originalImages = OriginalImageStore(filesRoot),
      privateStore = privateStore,
      currentPrivateAccess = null,
      newPrivatePassword = null,
      newPrivateRecoveryKey = null,
      previewSummary = summary,
      verifyCurrentTarget = {},
      createCommitProtocol = { restoreId, _, verify, applyRows ->
        completedTransactionId = restoreId
        BackupRestoreCommitProtocol(
          journal = BackupRestoreJournal(filesRoot),
          transactionId = restoreId,
          hasCommitMarker = database.backupRestoreCommitDao()::contains,
          clearCommitMarker = database.backupRestoreCommitDao()::delete,
          verifyTargetUnchanged = verify,
          publishPrivate = {},
          publishOrdinary = draftTransactions::publish,
          commitRoomRowsAndMarker = { id ->
            database.withTransaction {
              applyRows()
              database.backupRestoreCommitDao().insert(BackupRestoreCommitEntity(id))
            }
          },
          rollbackOrdinary = { id ->
            OriginalImageStore(filesRoot).deleteRestoreGroup(id)
            draftTransactions.finish(id, committed = false)
          },
          rollbackPrivate = {},
          finalizeCommittedOrdinary = { id -> draftTransactions.finish(id, committed = true) },
          finalizeCommittedPrivate = {},
        )
      },
    )

    target.prepareConflictResolution(resolution)
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder(
      "system-uncategorized", "未分类", FolderKind.UNCATEGORIZED, 0L, Int.MIN_VALUE,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder(
      "folder-study", "学习", FolderKind.USER, 10L, 0,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Note(
      id = "note-restored", folderId = "folder-study", title = "恢复验收", titleManuallySet = true,
      createdAtMillis = 10L, updatedAtMillis = 20L, skinId = "minimal-white",
      fontSizeSpOverride = null, lineSpacingMultiplierOverride = null, isPinned = false,
      deletedAtMillis = null, contentRevision = 1L, importRequestId = null,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Block(
      id = "block-text", noteId = "note-restored", position = 0, type = BlockType.TEXT,
      text = "restorable searchable phrase", assetRef = null, widthPercent = 100f,
      alignment = BlockAlignment.START, aspectRatio = null, ocrStatus = null, formattingBlob = null,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Block(
      id = "block-todo", noteId = "note-restored", position = 1, type = BlockType.TODO,
      text = "buy milk", assetRef = null, widthPercent = 100f,
      alignment = BlockAlignment.START, aspectRatio = null, ocrStatus = null, formattingBlob = null,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Todo(
      id = "todo-1", noteId = "note-restored", blockId = "block-todo", text = "buy milk",
      isCompleted = false, updatedAtMillis = 20L,
    ))
    target.commit(BackupRecordStreamSummary(0L, 0L, 6L, 6L, 0L), summary)

    assertEquals("恢复验收", database.noteDao().getById("note-restored")?.title)
    assertEquals(listOf("block-text", "block-todo"), database.blockDao().getByNote("note-restored").map { it.id })
    assertEquals("buy milk", database.todoDao().getByNote("note-restored").single().text)
    assertEquals("folder-study", database.noteDao().getById("note-restored")?.folderId)
    assertEquals("note-restored", database.noteSearchDao().searchFts("restorable").single().noteId)
    assertFalse(database.backupRestoreCommitDao().contains(checkNotNull(completedTransactionId)))
    assertTrue(BackupRestoreJournal(filesRoot).read() == null)
  }

  @Test
  fun ordinaryDraftUsesResolvedFolderWhenTargetAlreadyHasSameFolderName() = runBlocking {
    database.folderDao().upsert(FolderEntity("folder-local", "学习", FolderKind.USER.name, 5L, 0))
    val draftId = java.util.UUID.randomUUID().toString()
    val summary = BackupRecordSetSummary(
      ordinaryFolders = 2, ordinaryNotes = 0, ordinaryBlocks = 0, ordinaryTodos = 0,
      ordinaryDrafts = 1, ordinaryDraftBlocks = 1, ordinaryAssets = 0, ordinaryAssetBytes = 0,
      privateNotes = 0, privateBlocks = 0, privateTodos = 0, privateDrafts = 0,
      privateDraftBlocks = 0, privateAssets = 0, privateAssetBytes = 0,
    )
    val folderKey = BackupObjectKey(BackupRecordDomain.ORDINARY, BackupRecordKind.FOLDER, "folder-backup")
    val resolution = ResolvedBackupConflictPlan(
      backupSessionId = "draft-folder-collision",
      targetRevision = 0L,
      newObjects = setOf(folderKey),
      identicalObjects = emptySet(), keepCurrent = emptySet(), replaceCurrent = emptySet(), keepBoth = emptySet(),
    )
    val target = AppBackupRestoreStore(
      database = database,
      ordinaryDraftStore = drafts,
      ordinaryDraftTransactions = draftTransactions,
      originalImages = OriginalImageStore(filesRoot),
      privateStore = privateStore,
      currentPrivateAccess = null,
      newPrivatePassword = null,
      newPrivateRecoveryKey = null,
      previewSummary = summary,
      verifyCurrentTarget = {},
      createCommitProtocol = { restoreId, _, verify, applyRows ->
        BackupRestoreCommitProtocol(
          journal = BackupRestoreJournal(filesRoot),
          transactionId = restoreId,
          hasCommitMarker = database.backupRestoreCommitDao()::contains,
          clearCommitMarker = database.backupRestoreCommitDao()::delete,
          verifyTargetUnchanged = verify,
          publishPrivate = {},
          publishOrdinary = draftTransactions::publish,
          commitRoomRowsAndMarker = { id ->
            database.withTransaction {
              applyRows()
              database.backupRestoreCommitDao().insert(BackupRestoreCommitEntity(id))
            }
          },
          rollbackOrdinary = { id ->
            OriginalImageStore(filesRoot).deleteRestoreGroup(id)
            draftTransactions.finish(id, committed = false)
          },
          rollbackPrivate = {},
          finalizeCommittedOrdinary = { id -> draftTransactions.finish(id, committed = true) },
          finalizeCommittedPrivate = {},
        )
      },
    )

    target.prepareConflictResolution(resolution)
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder(
      "system-uncategorized", "未分类", FolderKind.UNCATEGORIZED, 0L, Int.MIN_VALUE,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder(
      "folder-backup", "学习", FolderKind.USER, 10L, 0,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.Draft(
      draftId = draftId, noteId = null, folderId = "folder-backup", baseContentRevision = null,
      localRevision = 1L, savedAtMillis = 10L, importRequestId = null, title = "草稿", titleManuallySet = true,
      skinId = "minimal-white", fontSizeSpOverride = null, lineSpacingMultiplierOverride = null, blockCount = 1,
    ))
    target.onRecord(BackupRecordDomain.ORDINARY, BackupRecordPayload.DraftBlock(
      draftId = draftId, position = 0, blockId = "draft-text", type = BlockType.TEXT, text = "内容",
      assetRef = null, widthPercent = 100f, alignment = BlockAlignment.START, aspectRatio = null,
      ocrStatus = null, formattingBlob = null,
    ))
    target.commit(BackupRecordStreamSummary(0L, 0L, 4L, 4L, 0L), summary)

    val restoredDraft = drafts.listRecoverable().single()
    assertEquals("folder-local", restoredDraft.folderId)
    assertEquals("folder-local", restoredDraft.draft.folderId)
  }

  @Test
  fun privateRestoreCreatesNewVaultAndKeepsAttachmentEncryptedAndReadable() = runBlocking {
    val originalBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 4, 5, 6)
    val summary = BackupRecordSetSummary(
      ordinaryFolders = 0, ordinaryNotes = 0, ordinaryBlocks = 0, ordinaryTodos = 0,
      ordinaryDrafts = 0, ordinaryDraftBlocks = 0, ordinaryAssets = 0, ordinaryAssetBytes = 0,
      privateNotes = 1, privateBlocks = 1, privateTodos = 0, privateDrafts = 0,
      privateDraftBlocks = 0, privateAssets = 1, privateAssetBytes = originalBytes.size.toLong(),
    )
    val noteKey = BackupObjectKey(BackupRecordDomain.PRIVATE, BackupRecordKind.NOTE, "private-note")
    val resolution = ResolvedBackupConflictPlan(
      backupSessionId = "private-new-vault",
      targetRevision = 0L,
      newObjects = setOf(noteKey),
      identicalObjects = emptySet(), keepCurrent = emptySet(), replaceCurrent = emptySet(), keepBoth = emptySet(),
    )
    var completedTransactionId: String? = null
    val target = AppBackupRestoreStore(
      database = database,
      ordinaryDraftStore = drafts,
      ordinaryDraftTransactions = draftTransactions,
      originalImages = OriginalImageStore(filesRoot),
      privateStore = privateStore,
      currentPrivateAccess = null,
      newPrivatePassword = "new-private-password".toCharArray(),
      newPrivateRecoveryKey = null,
      previewSummary = summary,
      verifyCurrentTarget = {},
      createCommitProtocol = { restoreId, includesPrivate, verify, applyRows ->
        assertTrue(includesPrivate)
        completedTransactionId = restoreId
        BackupRestoreCommitProtocol(
          journal = BackupRestoreJournal(filesRoot),
          transactionId = restoreId,
          hasCommitMarker = database.backupRestoreCommitDao()::contains,
          clearCommitMarker = database.backupRestoreCommitDao()::delete,
          verifyTargetUnchanged = verify,
          publishPrivate = privateStore::publishRestoreVault,
          publishOrdinary = draftTransactions::publish,
          commitRoomRowsAndMarker = { id ->
            database.withTransaction {
              applyRows()
              database.backupRestoreCommitDao().insert(BackupRestoreCommitEntity(id))
            }
          },
          rollbackOrdinary = { id ->
            OriginalImageStore(filesRoot).deleteRestoreGroup(id)
            draftTransactions.finish(id, committed = false)
          },
          rollbackPrivate = { id ->
            privateStore.finishRestoreVault(id, committed = false)
            check(privateStore.deleteUncommittedPrivateAssetRestoreGroup(id))
          },
          finalizeCommittedOrdinary = { id -> draftTransactions.finish(id, committed = true) },
          finalizeCommittedPrivate = { id -> privateStore.finishRestoreVault(id, committed = true) },
        )
      },
    )
    val asset = BackupRecordPayload.Asset(
      assetId = "private-image",
      mimeType = "image/png",
      byteCount = originalBytes.size.toLong(),
      sha256 = MessageDigest.getInstance("SHA-256").digest(originalBytes),
    )

    target.prepareConflictResolution(resolution)
    target.onRecord(BackupRecordDomain.PRIVATE, BackupRecordPayload.Note(
      id = "private-note", folderId = "private-uncategorized", title = "私密恢复", titleManuallySet = true,
      createdAtMillis = 1L, updatedAtMillis = 2L, skinId = "minimal-white",
      fontSizeSpOverride = null, lineSpacingMultiplierOverride = null, isPinned = false,
      deletedAtMillis = null, contentRevision = 1L, importRequestId = null,
    ))
    target.onRecord(BackupRecordDomain.PRIVATE, BackupRecordPayload.Block(
      id = "private-image-block", noteId = "private-note", position = 0, type = BlockType.IMAGE,
      text = null, assetRef = asset.assetId, widthPercent = 100f,
      alignment = BlockAlignment.CENTER, aspectRatio = 1f, ocrStatus = null, formattingBlob = null,
    ))
    target.onAssetBytes(BackupRecordDomain.PRIVATE, asset, originalBytes, 0, originalBytes.size)
    target.onRecord(BackupRecordDomain.PRIVATE, asset)
    target.commit(BackupRecordStreamSummary(0L, 0L, 3L, 0L, 3L), summary)

    assertTrue(privateStore.isConfigured())
    val access = privateStore.unlockWithPassword("new-private-password".toCharArray())
    try {
      val restored = privateStore.readSnapshot(access)
      assertEquals("私密恢复", restored.notes.single().title)
      val restoredImageReference = restored.notes.single().blocks.single().assetPath
      assertTrue(restoredImageReference?.contains(checkNotNull(completedTransactionId)) == true)
      assertTrue(privateStore.readPrivateAsset(access, checkNotNull(restoredImageReference)).contentEquals(originalBytes))
    } finally {
      privateStore.lock(access)
    }
  }
}

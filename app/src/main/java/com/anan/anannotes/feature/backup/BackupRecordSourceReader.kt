package com.anan.anannotes.feature.backup

import androidx.room.withTransaction
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.feature.editor.EditorDraftStore
import com.anan.anannotes.feature.privatemodule.PrivateStore
import com.anan.anannotes.feature.privatemodule.PrivateVaultSnapshot
import com.anan.anannotes.security.PrivateAccess
import java.io.IOException

/** Reads a consistent Room snapshot and keeps private material behind the active private grant. */
internal class BackupRecordSourceReader(
  private val database: AnAnDatabase,
  private val ordinaryDraftStore: EditorDraftStore,
  private val privateStore: PrivateStore,
  filesDirectory: java.io.File,
) {
  private val ordinaryAssets = OriginalImageStore(filesDirectory)

  suspend fun read(privateAccess: PrivateAccess? = null): BackupRecordSnapshot {
    val roomSnapshot = database.withTransaction {
      RoomSnapshot(
        folders = database.folderDao().getAll(),
        notes = database.noteDao().getAll(),
        blocks = database.blockDao().getAll(),
        todos = database.todoDao().getAll(),
      )
    }
    val drafts = ordinaryDraftStore.listRecoverable()
    val vaultConfigured = privateStore.isConfigured()
    val privateSnapshot = when {
      vaultConfigured && privateAccess == null -> throw PrivateBackupAccessRequiredException()
      vaultConfigured -> privateStore.readSnapshot(checkNotNull(privateAccess))
      privateAccess != null -> throw IOException("私密库状态已变化，请重新确认后重试")
      else -> PrivateVaultSnapshot()
    }

    return BackupRecordSnapshotMapper.map(
      folders = roomSnapshot.folders,
      notes = roomSnapshot.notes,
      blocks = roomSnapshot.blocks,
      todos = roomSnapshot.todos,
      ordinaryDrafts = drafts,
      privateSnapshot = privateSnapshot,
      openOrdinaryAsset = { reference -> ordinaryAssets.resolve(reference).inputStream() },
      openPrivateAsset = { access, reference -> privateStore.openPrivateAssetForExport(access, reference) },
      privateAccess = privateAccess,
    )
  }

  private data class RoomSnapshot(
    val folders: List<com.anan.anannotes.data.local.FolderEntity>,
    val notes: List<com.anan.anannotes.data.local.NoteEntity>,
    val blocks: List<com.anan.anannotes.data.local.BlockEntity>,
    val todos: List<com.anan.anannotes.data.local.TodoEntity>,
  )
}

internal class PrivateBackupAccessRequiredException : IOException("请先解锁私密笔记，再创建包含私密数据的备份")

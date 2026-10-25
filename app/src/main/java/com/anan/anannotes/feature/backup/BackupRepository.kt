package com.anan.anannotes.feature.backup

import android.content.Context
import androidx.room.withTransaction
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.files.SafBackupDocumentStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.BackupRestoreCommitEntity
import com.anan.anannotes.feature.editor.FileEditorDraftStore
import com.anan.anannotes.feature.editor.EditorDraftRestoreTransactionStore
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.feature.privatemodule.PrivateStoreInstances
import com.anan.anannotes.feature.privatemodule.PrivateAuthThrottle
import com.anan.anannotes.feature.privatemodule.SharedPreferencesPrivateAuthThrottleStore
import com.anan.anannotes.security.PrivateAuthenticationException
import com.anan.anannotes.security.BackupContainerCodec
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.BackupRecordSetSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Android-facing SAF backup facade. It intentionally exposes no network or general-file paths. */
internal class BackupRepository(context: Context) {
  private val appContext = context.applicationContext
  private val privateStore = PrivateStoreInstances.get(appContext.filesDir)
  private val ordinaryDraftRestoreTransactions = EditorDraftRestoreTransactionStore(
    File(appContext.filesDir, EDITOR_DRAFT_DIRECTORY),
  )
  private val ordinaryDraftStore = FileEditorDraftStore(File(appContext.filesDir, EDITOR_DRAFT_DIRECTORY))
  private val sourceReader = BackupRecordSourceReader(
    database = AnAnDatabase.getInstance(appContext),
    ordinaryDraftStore = ordinaryDraftStore,
    privateStore = privateStore,
    filesDirectory = appContext.filesDir,
  )
  private val coordinator = BackupCoordinator(
    containerCodec = BackupContainerCodec(),
    documents = SafBackupDocumentStore(appContext.contentResolver),
    noBackupDirectory = appContext.noBackupFilesDir,
  )
  private val restoreJournal = BackupRestoreJournal(appContext.noBackupFilesDir)
  private val privateAuthThrottle = PrivateAuthThrottle(SharedPreferencesPrivateAuthThrottleStore(appContext))

  suspend fun isPrivateVaultConfigured(): Boolean = privateStore.isConfigured()

  /** Backup entry-point authentication shares the private module's persisted failure backoff. */
  suspend fun unlockPrivateForBackup(password: CharArray): PrivateAccess {
    try {
      return withContext(Dispatchers.IO) {
        val retryAfter = privateAuthThrottle.remainingMillis(System.currentTimeMillis())
        if (retryAfter > 0L) throw PrivateBackupAuthThrottledException(retryAfter)
        try {
          val access = privateStore.unlockWithPassword(password)
          try {
            privateAuthThrottle.recordSuccess()
            access
          } catch (failure: Throwable) {
            access.revoke()
            throw failure
          }
        } catch (failure: PrivateAuthenticationException) {
          privateAuthThrottle.recordFailure(System.currentTimeMillis())
          throw failure
        }
      }
    } finally {
      MemoryWipe.chars(password)
    }
  }

  fun lockPrivateBackupAccess(access: PrivateAccess?) {
    access?.let(privateStore::lock)
  }

  suspend fun createBackup(
    target: OutputTarget.DocumentTree,
    requestedName: String,
    backupPassword: CharArray,
    privateAccess: PrivateAccess? = null,
  ): BackupCreationResult {
    try {
      return withContext(Dispatchers.IO) {
        var snapshot: BackupRecordSnapshot? = null
        try {
          val captured = sourceReader.read(privateAccess)
          snapshot = captured
          coordinator.createBackup(target, requestedName, backupPassword) { writer -> captured.writeTo(writer) }
        } finally {
          snapshot?.close()
        }
      }
    } finally {
      MemoryWipe.chars(backupPassword)
    }
  }

  suspend fun inspectBackup(
    sourceUriString: String,
    backupPassword: CharArray,
    currentPrivateAccess: PrivateAccess? = null,
  ): InspectedBackup {
    var inspected: InspectedBackup? = null
    try {
      return withContext(Dispatchers.IO) {
        val authenticated = coordinator.inspect(sourceUriString, backupPassword)
        inspected = authenticated
        // A configured private vault must be unlocked to include its current aggregates in the
        // conflict preview. An unconfigured vault is represented by an empty private domain.
        val currentFingerprints = sourceReader.read(currentPrivateAccess).use(BackupRecordSnapshot::fingerprints)
        authenticated.bindTargetSnapshot(
          currentFingerprints = currentFingerprints,
          targetRevision = BackupTargetRevision.from(currentFingerprints),
        )
        authenticated
      }
    } catch (failure: Throwable) {
      inspected?.close()
      throw failure
    } finally {
      MemoryWipe.chars(backupPassword)
    }
  }

  suspend fun restoreVerifiedBackup(
    inspected: InspectedBackup,
    ordinaryChoice: BackupConflictChoice,
    privateChoice: BackupConflictChoice,
    currentPrivateAccess: PrivateAccess? = null,
    newPrivatePassword: CharArray? = null,
    newPrivateRecoveryKey: CharArray? = null,
  ): BackupRecordSetSummary {
    try {
      return withContext(Dispatchers.IO) {
        val currentFingerprints = sourceReader.read(currentPrivateAccess).use(BackupRecordSnapshot::fingerprints)
        val resolution = inspected.resolveConflictChoicesByDomain(
          currentFingerprints = currentFingerprints,
          ordinaryChoice = ordinaryChoice,
          privateChoice = privateChoice,
        )
        val database = AnAnDatabase.getInstance(appContext)
        val target = AppBackupRestoreStore(
          database = database,
          ordinaryDraftStore = ordinaryDraftStore,
          ordinaryDraftTransactions = ordinaryDraftRestoreTransactions,
          originalImages = OriginalImageStore(appContext.filesDir),
          privateStore = privateStore,
          currentPrivateAccess = currentPrivateAccess,
          newPrivatePassword = newPrivatePassword,
          newPrivateRecoveryKey = newPrivateRecoveryKey,
          previewSummary = inspected.preview.recordSet,
          verifyCurrentTarget = {
            val latest = sourceReader.read(currentPrivateAccess).use(BackupRecordSnapshot::fingerprints)
            check(latest == currentFingerprints) { "恢复目标已变化，请重新检查备份" }
          },
          createCommitProtocol = { transactionId, includesPrivateSnapshot, verify, applyRows ->
            newRestoreCommitProtocol(
              transactionId = transactionId,
              includesPrivateSnapshot = includesPrivateSnapshot,
              verifyTargetUnchanged = verify,
              applyRoomRows = applyRows,
            )
          },
        )
        coordinator.restore(inspected, target, resolution)
      }
    } finally {
      inspected.close()
      MemoryWipe.chars(newPrivatePassword)
      MemoryWipe.chars(newPrivateRecoveryKey)
    }
  }

  /**
   * Creates the app-owned commit boundary used by the concrete typed restore target. The callback
   * applies ordinary folders/notes/blocks/todos/search rows; this repository inserts the durable
   * recovery marker in that same Room transaction. Call begin() before the target stages any files.
   */
  internal fun newRestoreCommitProtocol(
    transactionId: String,
    includesPrivateSnapshot: Boolean,
    verifyTargetUnchanged: suspend () -> Unit = {},
    applyRoomRows: suspend () -> Unit,
  ): BackupRestoreCommitProtocol {
    val database = AnAnDatabase.getInstance(appContext)
    val ordinaryImages = OriginalImageStore(appContext.filesDir)
    return BackupRestoreCommitProtocol(
      journal = restoreJournal,
      transactionId = transactionId,
      hasCommitMarker = database.backupRestoreCommitDao()::contains,
      clearCommitMarker = database.backupRestoreCommitDao()::delete,
      verifyTargetUnchanged = verifyTargetUnchanged,
      publishPrivate = { id ->
        if (includesPrivateSnapshot) privateStore.publishRestoreVault(id)
      },
      publishOrdinary = ordinaryDraftRestoreTransactions::publish,
      commitRoomRowsAndMarker = { id ->
        database.withTransaction {
          applyRoomRows()
          database.backupRestoreCommitDao().insert(BackupRestoreCommitEntity(id))
        }
      },
      rollbackOrdinary = { id ->
        rollbackOrdinaryRestore(id, ordinaryImages)
      },
      rollbackPrivate = { id ->
        privateStore.finishRestoreVault(id, committed = false)
        check(privateStore.deleteUncommittedPrivateAssetRestoreGroup(id)) { "私密图片恢复暂存无法清理" }
      },
      finalizeCommittedOrdinary = { id -> ordinaryDraftRestoreTransactions.finish(id, committed = true) },
      finalizeCommittedPrivate = { id ->
        if (includesPrivateSnapshot) privateStore.finishRestoreVault(id, committed = true)
      },
    )
  }

  suspend fun recoverAbandonedInspectionFiles(): Int = withContext(Dispatchers.IO) {
    coordinator.recoverAbandonedInspectionFiles()
  }

  /** Must complete before navigation can read ordinary or private restore-owned data. */
  suspend fun recoverPendingRestore(): BackupRestoreRecoveryResult = withContext(Dispatchers.IO) {
    val database = AnAnDatabase.getInstance(appContext)
    val ordinaryImages = OriginalImageStore(appContext.filesDir)
    BackupRestoreRecovery(
      journal = restoreJournal,
      hasCommitMarker = database.backupRestoreCommitDao()::contains,
      clearCommitMarkers = { transactionId ->
        if (transactionId == null) database.backupRestoreCommitDao().deleteAll()
        else database.backupRestoreCommitDao().delete(transactionId)
      },
      rollbackOrdinary = { transactionId ->
        rollbackOrdinaryRestore(transactionId, ordinaryImages)
      },
      rollbackPrivate = { transactionId ->
        privateStore.finishRestoreVault(transactionId, committed = false)
        check(privateStore.deleteUncommittedPrivateAssetRestoreGroup(transactionId)) { "私密图片恢复暂存无法清理" }
      },
      finalizeCommittedOrdinary = { id -> ordinaryDraftRestoreTransactions.finish(id, committed = true) },
      finalizeCommittedPrivate = { transactionId ->
        privateStore.finishRestoreVault(transactionId, committed = true)
      },
    ).recover()
  }

  private suspend fun rollbackOrdinaryRestore(transactionId: String, ordinaryImages: OriginalImageStore) {
    var failure: Throwable? = null
    try {
      check(ordinaryImages.deleteRestoreGroup(transactionId)) { "普通图片恢复暂存无法清理" }
    } catch (next: Throwable) {
      failure = next
    }
    try {
      ordinaryDraftRestoreTransactions.finish(transactionId, committed = false)
    } catch (next: Throwable) {
      val current = failure
      if (current == null) failure = next else current.addSuppressed(next)
    }
    failure?.let { throw it }
  }

  private companion object {
    const val EDITOR_DRAFT_DIRECTORY = "drafts"
  }
}

internal class PrivateBackupAuthThrottledException(val retryAfterMillis: Long) :
  Exception("Private authentication is temporarily delayed")

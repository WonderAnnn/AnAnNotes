package com.anan.anannotes.feature.backup

/** Recovery follows the Room commit marker, never the journal phase alone. */
internal class BackupRestoreRecovery(
  private val journal: BackupRestoreJournal,
  private val hasCommitMarker: suspend (String) -> Boolean,
  /** A null ID removes stale markers only when no journal exists. */
  private val clearCommitMarkers: suspend (String?) -> Unit,
  private val rollbackOrdinary: suspend (String) -> Unit,
  private val rollbackPrivate: suspend (String) -> Unit,
  private val finalizeCommittedOrdinary: suspend (String) -> Unit = {},
  private val finalizeCommittedPrivate: suspend (String) -> Unit = {},
) {
  suspend fun recover(): BackupRestoreRecoveryResult {
    val entry = journal.read() ?: run {
      clearCommitMarkers(null)
      return BackupRestoreRecoveryResult.NO_PENDING_TRANSACTION
    }
    val committed = hasCommitMarker(entry.transactionId)
    if (committed) {
      check(entry.phase.wireId >= BackupRestorePhase.PRIVATE_PUBLISHED.wireId) {
        "Restore commit marker is inconsistent with its journal"
      }
      finalizeCommittedOrdinary(entry.transactionId)
      finalizeCommittedPrivate(entry.transactionId)
      // Keep the journal until Room has acknowledged cleanup. If the process dies after deleting
      // the journal, the remaining marker is harmless and is cleared on the next no-journal pass.
      journal.finish(entry.transactionId)
      clearCommitMarkers(entry.transactionId)
      return BackupRestoreRecoveryResult.COMMITTED
    }
    check(entry.phase != BackupRestorePhase.DATABASE_COMMITTED) {
      "Restore journal claims a database commit that Room cannot confirm"
    }
    rollbackOrdinary(entry.transactionId)
    rollbackPrivate(entry.transactionId)
    journal.finish(entry.transactionId)
    return BackupRestoreRecoveryResult.ROLLED_BACK
  }
}

internal enum class BackupRestoreRecoveryResult {
  NO_PENDING_TRANSACTION,
  COMMITTED,
  ROLLED_BACK,
}

package com.anan.anannotes.feature.backup

/**
 * Coordinates the final publication boundary for a restore whose payloads and files are already
 * staged. The Room callback must apply every ordinary row and insert the supplied transaction's
 * commit marker in one Room transaction. Startup recovery uses that marker as the durable truth.
 */
internal class BackupRestoreCommitProtocol(
  private val journal: BackupRestoreJournal,
  private val transactionId: String,
  private val hasCommitMarker: suspend (String) -> Boolean,
  private val clearCommitMarker: suspend (String) -> Unit,
  private val verifyTargetUnchanged: suspend () -> Unit = {},
  private val publishPrivate: suspend (String) -> Unit,
  private val publishOrdinary: suspend (String) -> Unit = {},
  private val commitRoomRowsAndMarker: suspend (String) -> Unit,
  private val rollbackOrdinary: suspend (String) -> Unit,
  private val rollbackPrivate: suspend (String) -> Unit,
  private val finalizeCommittedOrdinary: suspend (String) -> Unit = {},
  private val finalizeCommittedPrivate: suspend (String) -> Unit,
) {
  private var started = false
  private var terminal = false

  /** Must run before writing any restore-owned ordinary or private staging data. */
  suspend fun begin() {
    check(!started && !terminal) { "Restore transaction has already started" }
    check(!hasCommitMarker(transactionId)) { "Restore transaction already has a Room commit marker" }
    journal.begin(transactionId)
    started = true
  }

  /** Publishes the private snapshot, then atomically commits Room rows and its recovery marker. */
  suspend fun commit() {
    check(started && !terminal) { "Restore transaction is not active" }
    try {
      journal.advance(transactionId, BackupRestorePhase.PREPARED)
      verifyTargetUnchanged()
      publishPrivate(transactionId)
      journal.advance(transactionId, BackupRestorePhase.PRIVATE_PUBLISHED)
      publishOrdinary(transactionId)
      commitRoomRowsAndMarker(transactionId)
      check(hasCommitMarker(transactionId)) { "Room restore transaction did not persist its commit marker" }
      journal.advance(transactionId, BackupRestorePhase.DATABASE_COMMITTED)
      finalizeCommittedOrdinary(transactionId)
      finalizeCommittedPrivate(transactionId)
      journal.finish(transactionId)
      terminal = true
      // With no journal, an orphan marker is safe and is cleared by startup recovery.
      runCatching { clearCommitMarker(transactionId) }
    } catch (failure: Throwable) {
      settleAfterFailure(failure)
      throw failure
    }
  }

  /** Aborts only before the durable Room marker; after that boundary startup must finish forward. */
  suspend fun abort() {
    if (!started || terminal) return
    if (hasCommitMarker(transactionId)) {
      terminal = true
      return
    }
    val failure = rollbackUncommitted()
    if (failure != null) throw failure
  }

  private suspend fun settleAfterFailure(original: Throwable) {
    val committed = try {
      hasCommitMarker(transactionId)
    } catch (markerFailure: Throwable) {
      original.addSuppressed(markerFailure)
      true // Unable to determine the commit boundary: preserve files and journal, fail closed.
    }
    if (committed) {
      terminal = true
      return
    }
    val cleanupFailure = rollbackUncommitted()
    if (cleanupFailure != null) original.addSuppressed(cleanupFailure)
  }

  private suspend fun rollbackUncommitted(): Throwable? {
    var failure: Throwable? = null
    suspend fun perform(action: suspend (String) -> Unit) {
      try {
        action(transactionId)
      } catch (next: Throwable) {
        val current = failure
        if (current == null) failure = next else current.addSuppressed(next)
      }
    }
    perform(rollbackOrdinary)
    perform(rollbackPrivate)
    if (failure == null) {
      try {
        journal.finish(transactionId)
        terminal = true
      } catch (next: Throwable) {
        failure = next
      }
    }
    return failure
  }
}

package com.anan.anannotes.feature.backup

import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordKind
import com.anan.anannotes.security.CryptoBytes

/** Stable top-level identity used to compare a verified backup with one target snapshot. */
data class BackupObjectKey(
  val domain: BackupRecordDomain,
  val kind: BackupRecordKind,
  val id: String,
) {
  init {
    require(kind == BackupRecordKind.FOLDER || kind == BackupRecordKind.NOTE) {
      "Conflict decisions are made for folders and complete note aggregates only"
    }
    require(kind != BackupRecordKind.FOLDER || domain == BackupRecordDomain.ORDINARY) {
      "Private folders are not part of backup schema v1"
    }
    require(id.isNotBlank() && id.length <= MAX_OBJECT_ID_CHARS) { "Invalid backup object ID" }
  }

  private companion object {
    const val MAX_OBJECT_ID_CHARS = 256
  }
}

enum class BackupConflictChoice {
  KEEP_CURRENT,
  USE_BACKUP,
  KEEP_BOTH,
}

class ResolvedBackupConflictPlan internal constructor(
  internal val backupSessionId: String,
  internal val targetRevision: Long,
  val newObjects: Set<BackupObjectKey>,
  val identicalObjects: Set<BackupObjectKey>,
  val keepCurrent: Set<BackupObjectKey>,
  val replaceCurrent: Set<BackupObjectKey>,
  val keepBoth: Set<BackupObjectKey>,
)

/**
 * Immutable, revision-bound merge proposal. Digests must represent a canonical serialization of
 * the whole object aggregate (for notes: note, blocks, todos and referenced asset hashes).
 */
class BackupConflictPlan private constructor(
  private val backupSessionId: String,
  private val targetRevision: Long,
  private val targetFingerprints: Map<BackupObjectKey, CryptoBytes>,
  val newObjects: Set<BackupObjectKey>,
  val identicalObjects: Set<BackupObjectKey>,
  val conflicts: Map<BackupObjectKey, ConflictDigests>,
) {
  internal val conflictKeys: Set<BackupObjectKey> get() = conflicts.keys

  class ConflictDigests internal constructor(
    val current: CryptoBytes,
    val backup: CryptoBytes,
  )

  /** Enforces one explicit choice for every conflict and rejects stale target/backup sessions. */
  fun resolve(
    currentSessionId: String,
    currentTargetRevision: Long,
    currentTargetFingerprints: Map<BackupObjectKey, CryptoBytes>,
    choices: Map<BackupObjectKey, BackupConflictChoice>,
  ): ResolvedBackupConflictPlan {
    check(currentSessionId == backupSessionId) { "Conflict decisions belong to another backup session" }
    check(currentTargetRevision == targetRevision) { "Restore target changed; inspect the backup again" }
    check(currentTargetFingerprints == targetFingerprints) { "Restore target contents changed; inspect the backup again" }
    require(choices.keys == conflicts.keys) { "Provide exactly one choice for each conflict" }

    val keepCurrent = linkedSetOf<BackupObjectKey>()
    val replaceCurrent = linkedSetOf<BackupObjectKey>()
    val keepBoth = linkedSetOf<BackupObjectKey>()
    for ((key, choice) in choices) {
      when (choice) {
        BackupConflictChoice.KEEP_CURRENT -> keepCurrent += key
        BackupConflictChoice.USE_BACKUP -> replaceCurrent += key
        BackupConflictChoice.KEEP_BOTH -> keepBoth += key
      }
    }
    return ResolvedBackupConflictPlan(
      backupSessionId = backupSessionId,
      targetRevision = targetRevision,
      newObjects = newObjects,
      identicalObjects = identicalObjects,
      keepCurrent = keepCurrent,
      replaceCurrent = replaceCurrent,
      keepBoth = keepBoth,
    )
  }

  companion object {
    private const val SHA256_BYTES = 32
    private const val MAX_SESSION_ID_CHARS = 128

    /** Compares stable IDs against canonical aggregate SHA-256 digests; plaintext is never stored. */
    fun compare(
      backupSessionId: String,
      targetRevision: Long,
      current: Map<BackupObjectKey, CryptoBytes>,
      incoming: Map<BackupObjectKey, CryptoBytes>,
    ): BackupConflictPlan {
      require(backupSessionId.isNotBlank() && backupSessionId.length <= MAX_SESSION_ID_CHARS) {
        "Invalid backup session ID"
      }
      require(targetRevision >= 0L) { "Invalid restore target revision" }
      validateDigests(current)
      validateDigests(incoming)

      val newObjects = linkedSetOf<BackupObjectKey>()
      val identicalObjects = linkedSetOf<BackupObjectKey>()
      val conflicts = linkedMapOf<BackupObjectKey, ConflictDigests>()
      for ((key, backupDigest) in incoming) {
        val currentDigest = current[key]
        when {
          currentDigest == null -> newObjects += key
          currentDigest == backupDigest -> identicalObjects += key
          else -> conflicts[key] = ConflictDigests(currentDigest, backupDigest)
        }
      }
      return BackupConflictPlan(
        backupSessionId = backupSessionId,
        targetRevision = targetRevision,
        targetFingerprints = current.toMap(),
        newObjects = newObjects.toSet(),
        identicalObjects = identicalObjects.toSet(),
        conflicts = conflicts.toMap(),
      )
    }

    private fun validateDigests(values: Map<BackupObjectKey, CryptoBytes>) {
      require(values.values.all { it.size == SHA256_BYTES }) { "Object fingerprints must be SHA-256" }
    }
  }
}

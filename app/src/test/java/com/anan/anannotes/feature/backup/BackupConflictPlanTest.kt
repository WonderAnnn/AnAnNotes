package com.anan.anannotes.feature.backup

import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordKind
import com.anan.anannotes.security.CryptoBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupConflictPlanTest {
  @Test
  fun detectsNewIdenticalAndChangedAggregatesWithoutRetainingPlaintext() {
    val folder = key(BackupRecordDomain.ORDINARY, BackupRecordKind.FOLDER, "folder-1")
    val sameNote = key(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, "note-same")
    val conflictNote = key(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, "note-conflict")
    val newNote = key(BackupRecordDomain.PRIVATE, BackupRecordKind.NOTE, "private-note")
    val plan = BackupConflictPlan.compare(
      backupSessionId = "session-1",
      targetRevision = 8L,
      current = mapOf(folder to digest(1), sameNote to digest(2), conflictNote to digest(3)),
      incoming = mapOf(folder to digest(1), sameNote to digest(2), conflictNote to digest(4), newNote to digest(5)),
    )

    assertEquals(setOf(newNote), plan.newObjects)
    assertEquals(setOf(folder, sameNote), plan.identicalObjects)
    assertEquals(setOf(conflictNote), plan.conflicts.keys)
  }

  @Test
  fun acceptsExactlyOneChoicePerConflictAndBindsItToTargetRevisionAndBackupSession() {
    val note = key(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, "note-1")
    val plan = BackupConflictPlan.compare("session-1", 8L, mapOf(note to digest(1)), mapOf(note to digest(2)))

    val current = mapOf(note to digest(1))
    val resolution = plan.resolve(
      currentSessionId = "session-1",
      currentTargetRevision = 8L,
      currentTargetFingerprints = current,
      choices = mapOf(note to BackupConflictChoice.KEEP_BOTH),
    )

    assertEquals(setOf(note), resolution.keepBoth)
    assertEquals(emptySet<BackupObjectKey>(), resolution.replaceCurrent)
  }

  @Test
  fun rejectsMissingExtraOrStaleConflictChoices() {
    val note = key(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, "note-1")
    val folder = key(BackupRecordDomain.ORDINARY, BackupRecordKind.FOLDER, "folder-1")
    val current = mapOf(note to digest(1))
    val plan = BackupConflictPlan.compare("session-1", 8L, current, mapOf(note to digest(2)))

    assertThrows(IllegalArgumentException::class.java) {
      plan.resolve("session-1", 8L, current, emptyMap())
    }
    assertThrows(IllegalArgumentException::class.java) {
      plan.resolve("session-1", 8L, current, mapOf(note to BackupConflictChoice.USE_BACKUP, folder to BackupConflictChoice.KEEP_CURRENT))
    }
    assertThrows(IllegalStateException::class.java) {
      plan.resolve("session-1", 9L, current, mapOf(note to BackupConflictChoice.USE_BACKUP))
    }
    assertThrows(IllegalStateException::class.java) {
      plan.resolve("different-session", 8L, current, mapOf(note to BackupConflictChoice.USE_BACKUP))
    }
    assertThrows(IllegalStateException::class.java) {
      plan.resolve("session-1", 8L, mapOf(note to digest(9)), mapOf(note to BackupConflictChoice.USE_BACKUP))
    }
  }

  @Test
  fun rejectsUnsupportedObjectKindsAndMalformedDigests() {
    assertThrows(IllegalArgumentException::class.java) {
      key(BackupRecordDomain.PRIVATE, BackupRecordKind.FOLDER, "private-folder")
    }
    assertThrows(IllegalArgumentException::class.java) {
      BackupConflictPlan.compare(
        "session", 1L,
        emptyMap(),
        mapOf(key(BackupRecordDomain.ORDINARY, BackupRecordKind.BLOCK, "block") to digest(1)),
      )
    }
    assertThrows(IllegalArgumentException::class.java) {
      BackupConflictPlan.compare(
        "session", 1L,
        emptyMap(),
        mapOf(key(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, "note") to CryptoBytes.of(byteArrayOf(1))),
      )
    }
  }

  private fun key(domain: BackupRecordDomain, kind: BackupRecordKind, id: String) = BackupObjectKey(domain, kind, id)
  private fun digest(value: Int) = CryptoBytes.of(ByteArray(32) { value.toByte() })
}

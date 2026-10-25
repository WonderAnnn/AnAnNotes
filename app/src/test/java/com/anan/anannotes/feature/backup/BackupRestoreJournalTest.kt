package com.anan.anannotes.feature.backup

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreJournalTest {
  @Test
  fun restorePhaseSurvivesJournalReopenWithoutPersistingContentOrPaths() {
    val root = Files.createTempDirectory("anan-restore-journal").toFile()
    try {
      val transactionId = "123e4567-e89b-12d3-a456-426614174000"
      val journal = BackupRestoreJournal(root)
      journal.begin(transactionId)
      journal.advance(transactionId, BackupRestorePhase.PREPARED)
      journal.advance(transactionId, BackupRestorePhase.PRIVATE_PUBLISHED)

      val reopened = BackupRestoreJournal(root)
      assertEquals(BackupRestoreJournalEntry(transactionId, BackupRestorePhase.PRIVATE_PUBLISHED), reopened.read())
      val persistedBytes = File(root, "backup-restore.journal").readBytes()
      assertEquals(26, persistedBytes.size)
      assertFalse(persistedBytes.toString(Charsets.ISO_8859_1).contains("note title"))
      assertFalse(persistedBytes.toString(Charsets.ISO_8859_1).contains("private/assets"))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun journalRequiresCanonicalTransactionIdsAndMonotonicPhases() {
    val root = Files.createTempDirectory("anan-restore-journal-invalid").toFile()
    try {
      val journal = BackupRestoreJournal(root)
      assertThrows(IllegalArgumentException::class.java) { journal.begin("../../notes") }

      val transactionId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000").toString()
      journal.begin(transactionId)
      assertThrows(IllegalStateException::class.java) {
        journal.advance(transactionId, BackupRestorePhase.DATABASE_COMMITTED)
      }
      assertThrows(IllegalStateException::class.java) {
        journal.advance(UUID.randomUUID().toString(), BackupRestorePhase.PREPARED)
      }
      assertThrows(IllegalStateException::class.java) { journal.begin(UUID.randomUUID().toString()) }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun journalRejectsCorruptionAndOnlyDeletesTheMatchingTransaction() {
    val root = Files.createTempDirectory("anan-restore-journal-corrupt").toFile()
    try {
      val journal = BackupRestoreJournal(root)
      val transactionId = "123e4567-e89b-12d3-a456-426614174000"
      journal.begin(transactionId)
      assertThrows(IllegalStateException::class.java) { journal.finish(UUID.randomUUID().toString()) }
      assertEquals(transactionId, journal.read()?.transactionId)

      val file = File(root, "backup-restore.journal")
      file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() })
      assertThrows(IllegalStateException::class.java) { journal.read() }
      assertTrue(file.exists())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun finishRemovesOnlyTheCurrentJournal() {
    val root = Files.createTempDirectory("anan-restore-journal-finish").toFile()
    try {
      val journal = BackupRestoreJournal(root)
      val transactionId = "123e4567-e89b-12d3-a456-426614174000"
      journal.begin(transactionId)
      journal.advance(transactionId, BackupRestorePhase.PREPARED)

      journal.finish(transactionId)

      assertNull(journal.read())
      assertFalse(File(root, "backup-restore.journal").exists())
    } finally {
      root.deleteRecursively()
    }
  }
}

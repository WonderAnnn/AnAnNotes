package com.anan.anannotes.feature.backup

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRestoreRecoveryTest {
  @Test
  fun reopenedRecoveryRollsBackPersistedFilesAfterProcessInterruptionBeforeRoomCommit() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    journal.begin(transactionId)
    journal.advance(transactionId, BackupRestorePhase.PREPARED)

    val ordinaryGroup = File(root, "notes/assets/restore-$transactionId").apply { mkdirs() }
    val privateGroup = File(root, "private/assets/restore-$transactionId").apply { mkdirs() }
    val ordinaryPartial = File(ordinaryGroup, "partial-image.tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val privatePartial = File(privateGroup, "partial-ciphertext.tmp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
    val existing = File(root, "notes/assets/existing.png").apply { writeBytes("keep".toByteArray()) }

    // A fresh journal/recovery pair models startup after all process-local transaction state was lost.
    val reopenedRecovery = BackupRestoreRecovery(
      journal = BackupRestoreJournal(root),
      hasCommitMarker = { false },
      clearCommitMarkers = {},
      rollbackOrdinary = { check(ordinaryGroup.deleteRecursively()) },
      rollbackPrivate = { check(privateGroup.deleteRecursively()) },
    )

    assertEquals(BackupRestoreRecoveryResult.ROLLED_BACK, runBlocking { reopenedRecovery.recover() })
    assertNull(BackupRestoreJournal(root).read())
    assertFalse(ordinaryPartial.exists())
    assertFalse(privatePartial.exists())
    assertEquals("keep", existing.readText())
  }

  @Test
  fun journalWithoutRoomMarkerRollsBackBothFileDomainsBeforeFinishing() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    journal.begin(transactionId)
    journal.advance(transactionId, BackupRestorePhase.PREPARED)
    val events = mutableListOf<String>()

    val result = runBlocking {
      recovery(journal, committed = emptySet(), events).recover()
    }

    assertEquals(BackupRestoreRecoveryResult.ROLLED_BACK, result)
    assertEquals(listOf("ordinary:$transactionId", "private:$transactionId"), events)
    assertNull(journal.read())
  }

  @Test
  fun roomMarkerMakesRestoreCommittedEvenIfJournalAdvanceWasInterrupted() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    journal.begin(transactionId)
    journal.advance(transactionId, BackupRestorePhase.PREPARED)
    journal.advance(transactionId, BackupRestorePhase.PRIVATE_PUBLISHED)
    val events = mutableListOf<String>()

    val result = runBlocking {
      recovery(journal, committed = setOf(transactionId), events) { id -> events += "ordinary-commit:$id" }.recover()
    }

    assertEquals(BackupRestoreRecoveryResult.COMMITTED, result)
    assertEquals(listOf("ordinary-commit:$transactionId", "private-commit:$transactionId", "clear:$transactionId"), events)
    assertNull(journal.read())
  }

  @Test
  fun committedJournalWithoutRoomMarkerFailsClosed() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    journal.begin(transactionId)
    journal.advance(transactionId, BackupRestorePhase.PREPARED)
    journal.advance(transactionId, BackupRestorePhase.PRIVATE_PUBLISHED)
    journal.advance(transactionId, BackupRestorePhase.DATABASE_COMMITTED)
    val events = mutableListOf<String>()

    assertThrows(IllegalStateException::class.java) {
      runBlocking { recovery(journal, committed = emptySet(), events).recover() }
    }
    assertEquals(emptyList<String>(), events)
    assertEquals(transactionId, journal.read()?.transactionId)
  }

  @Test
  fun orphanRoomMarkersAreClearedWithoutTouchingAnyFilesWhenJournalIsAbsent() = withRoot { root ->
    val journal = BackupRestoreJournal(root)
    val events = mutableListOf<String>()

    val result = runBlocking {
      recovery(journal, committed = setOf(UUID.randomUUID().toString()), events).recover()
    }

    assertEquals(BackupRestoreRecoveryResult.NO_PENDING_TRANSACTION, result)
    assertEquals(listOf("clear:all"), events)
  }

  private fun recovery(
    journal: BackupRestoreJournal,
    committed: Set<String>,
    events: MutableList<String>,
    finalizeOrdinary: suspend (String) -> Unit = {},
  ) = BackupRestoreRecovery(
    journal = journal,
    hasCommitMarker = { committed.contains(it) },
    clearCommitMarkers = { id -> events += "clear:${id ?: "all"}" },
    rollbackOrdinary = { id -> events += "ordinary:$id" },
    rollbackPrivate = { id -> events += "private:$id" },
    finalizeCommittedOrdinary = finalizeOrdinary,
    finalizeCommittedPrivate = { id -> events += "private-commit:$id" },
  )

  private fun withRoot(block: (File) -> Unit) {
    val root = Files.createTempDirectory("anan-backup-recovery").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }
}

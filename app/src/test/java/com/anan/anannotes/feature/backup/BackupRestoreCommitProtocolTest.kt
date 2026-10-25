package com.anan.anannotes.feature.backup

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreCommitProtocolTest {
  @Test
  fun lowStorageDuringStagingRollsBackBothDomainsAndPreservesExistingFiles() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    val ordinaryGroup = File(root, "ordinary/restore-$transactionId")
    val privateGroup = File(root, "private/assets/restore-$transactionId")
    val ordinaryPartial = File(ordinaryGroup, "partial-image.tmp")
    val privatePartial = File(privateGroup, "partial-encrypted-image.tmp")
    val existing = File(root, "ordinary/existing-image.bin").apply {
      parentFile!!.mkdirs()
      writeBytes("existing image remains unchanged".toByteArray())
    }
    var roomMarker = false
    val protocol = BackupRestoreCommitProtocol(
      journal = journal,
      transactionId = transactionId,
      hasCommitMarker = { roomMarker },
      clearCommitMarker = { roomMarker = false },
      publishPrivate = {},
      publishOrdinary = {},
      commitRoomRowsAndMarker = { roomMarker = true },
      rollbackOrdinary = { check(ordinaryGroup.deleteRecursively()) },
      rollbackPrivate = { check(privateGroup.deleteRecursively()) },
      finalizeCommittedPrivate = {},
    )

    assertThrows(IOException::class.java) {
      runBlocking {
        protocol.begin()
        ordinaryGroup.mkdirs()
        ordinaryPartial.writeBytes(byteArrayOf(1, 2, 3))
        privateGroup.mkdirs()
        try {
          FileOutputStream(privatePartial).use { output ->
            output.write(byteArrayOf(4, 5))
            throw IOException("No space left on device (ENOSPC)")
          }
        } catch (failure: IOException) {
          protocol.abort()
          throw failure
        }
      }
    }

    assertFalse(ordinaryGroup.exists())
    assertFalse(privateGroup.exists())
    assertEquals("existing image remains unchanged", existing.readText())
    assertFalse(roomMarker)
    assertNull(journal.read())
  }

  @Test
  fun publishesPrivateThenCommitsRoomMarkerBeforeFinalizingOldPrivateFiles() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    val events = mutableListOf<String>()
    var marker = false
    var rowsCommitted = false
    val protocol = protocol(
      journal = journal,
      transactionId = transactionId,
      hasMarker = { marker },
      clearMarker = { events += "clear-marker"; marker = false },
      events = events,
      publishPrivate = { events += "publish-private" },
      roomTransaction = {
        events += "room-transaction"
        rowsCommitted = true
        marker = true
      },
      finalizePrivate = { events += "finalize-private" },
    )

    runBlocking {
      protocol.begin()
      protocol.commit()
    }

    assertEquals(
      listOf("publish-private", "room-transaction", "finalize-private", "clear-marker"),
      events,
    )
    assertTrue(rowsCommitted)
    assertFalse(marker)
    assertNull(journal.read())
  }

  @Test
  fun publishesBothFileDomainsBeforeRoomMarkerAndFinalizesBothAfterCommit() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    val events = mutableListOf<String>()
    var marker = false
    val protocol = BackupRestoreCommitProtocol(
      journal = journal,
      transactionId = transactionId,
      hasCommitMarker = { marker },
      clearCommitMarker = { events += "clear-marker"; marker = false },
      publishPrivate = { events += "publish-private" },
      publishOrdinary = { events += "publish-ordinary" },
      commitRoomRowsAndMarker = { events += "room-transaction"; marker = true },
      rollbackOrdinary = { events += "rollback-ordinary" },
      rollbackPrivate = { events += "rollback-private" },
      finalizeCommittedOrdinary = { events += "finalize-ordinary" },
      finalizeCommittedPrivate = { events += "finalize-private" },
    )

    runBlocking {
      protocol.begin()
      protocol.commit()
    }

    assertEquals(
      listOf("publish-private", "publish-ordinary", "room-transaction", "finalize-ordinary", "finalize-private", "clear-marker"),
      events,
    )
  }

  @Test
  fun targetSnapshotIsRevalidatedImmediatelyBeforeEitherDomainIsPublished() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val events = mutableListOf<String>()
    var marker = false
    val protocol = BackupRestoreCommitProtocol(
      journal = BackupRestoreJournal(root),
      transactionId = transactionId,
      hasCommitMarker = { marker },
      clearCommitMarker = { marker = false },
      verifyTargetUnchanged = { events += "verify-target" },
      publishPrivate = { events += "publish-private" },
      publishOrdinary = { events += "publish-ordinary" },
      commitRoomRowsAndMarker = { events += "room"; marker = true },
      rollbackOrdinary = {},
      rollbackPrivate = {},
      finalizeCommittedOrdinary = {},
      finalizeCommittedPrivate = {},
    )

    runBlocking {
      protocol.begin()
      protocol.commit()
    }

    assertEquals(listOf("verify-target", "publish-private", "publish-ordinary", "room"), events)
  }

  @Test
  fun rollsBackBothFileDomainsWhenRoomTransactionFailsBeforeCommitMarker() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    val events = mutableListOf<String>()
    var marker = false
    val protocol = protocol(
      journal = journal,
      transactionId = transactionId,
      hasMarker = { marker },
      clearMarker = { events += "clear-marker"; marker = false },
      events = events,
      publishPrivate = { events += "publish-private" },
      roomTransaction = { events += "room-transaction"; error("database transaction failed") },
    )

    runBlocking {
      protocol.begin()
      assertThrows(IllegalStateException::class.java) { runBlocking { protocol.commit() } }
    }

    assertEquals(
      listOf("publish-private", "room-transaction", "rollback-ordinary", "rollback-private"),
      events,
    )
    assertFalse(marker)
    assertNull(journal.read())
  }

  @Test
  fun keepsJournalAndNeverRollsBackWhenRoomMarkerExistsAfterCommitBoundaryFailure() = withRoot { root ->
    val transactionId = UUID.randomUUID().toString()
    val journal = BackupRestoreJournal(root)
    val events = mutableListOf<String>()
    var marker = false
    val protocol = protocol(
      journal = journal,
      transactionId = transactionId,
      hasMarker = { marker },
      clearMarker = { events += "clear-marker"; marker = false },
      events = events,
      publishPrivate = { events += "publish-private" },
      roomTransaction = {
        // Models Room committing rows and its marker atomically, followed by an interruption.
        marker = true
        error("process interrupted after the Room commit")
      },
    )

    runBlocking {
      protocol.begin()
      assertThrows(IllegalStateException::class.java) { runBlocking { protocol.commit() } }
    }

    assertEquals(listOf("publish-private"), events)
    assertEquals(BackupRestorePhase.PRIVATE_PUBLISHED, journal.read()?.phase)
    assertTrue(marker)

    val recovery = BackupRestoreRecovery(
      journal = journal,
      hasCommitMarker = { marker },
      clearCommitMarkers = { marker = false },
      rollbackOrdinary = { events += "rollback-ordinary" },
      rollbackPrivate = { events += "rollback-private" },
      finalizeCommittedPrivate = { events += "finalize-private" },
    )
    assertEquals(BackupRestoreRecoveryResult.COMMITTED, runBlocking { recovery.recover() })
    assertEquals(listOf("publish-private", "finalize-private"), events)
    assertFalse(marker)
    assertNull(journal.read())
  }

  private fun protocol(
    journal: BackupRestoreJournal,
    transactionId: String,
    hasMarker: suspend (String) -> Boolean,
    clearMarker: suspend (String) -> Unit,
    events: MutableList<String>,
    publishPrivate: suspend (String) -> Unit,
    roomTransaction: suspend (String) -> Unit,
    finalizePrivate: suspend (String) -> Unit = { events += "finalize-private" },
  ) = BackupRestoreCommitProtocol(
    journal = journal,
    transactionId = transactionId,
    hasCommitMarker = hasMarker,
    clearCommitMarker = clearMarker,
    publishPrivate = publishPrivate,
    commitRoomRowsAndMarker = roomTransaction,
    rollbackOrdinary = { events += "rollback-ordinary" },
    rollbackPrivate = { events += "rollback-private" },
    finalizeCommittedPrivate = finalizePrivate,
  )

  private fun withRoot(block: (File) -> Unit) {
    val root = Files.createTempDirectory("anan-restore-commit").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }
}

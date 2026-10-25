package com.anan.anannotes.feature.backup

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.zip.CRC32

internal enum class BackupRestorePhase(val wireId: Int) {
  PREPARING(1),
  PREPARED(2),
  PRIVATE_PUBLISHED(3),
  DATABASE_COMMITTED(4),
  ;

  companion object {
    fun fromWireId(id: Int): BackupRestorePhase = entries.firstOrNull { it.wireId == id }
      ?: throw IllegalStateException("Restore journal phase is invalid")
  }
}

internal data class BackupRestoreJournalEntry(
  val transactionId: String,
  val phase: BackupRestorePhase,
)

/** Durable, content-free recovery marker. It stores only a random transaction UUID and phase. */
internal class BackupRestoreJournal(private val directory: File) {
  private val journalFile get() = File(directory, FILE_NAME)

  @Synchronized
  fun begin(transactionId: String = UUID.randomUUID().toString()): BackupRestoreJournalEntry {
    validateTransactionId(transactionId)
    check(!journalFile.exists()) { "Another restore transaction is already active" }
    val entry = BackupRestoreJournalEntry(transactionId, BackupRestorePhase.PREPARING)
    write(entry)
    return entry
  }

  @Synchronized
  fun advance(transactionId: String, phase: BackupRestorePhase): BackupRestoreJournalEntry {
    validateTransactionId(transactionId)
    val current = read() ?: throw IllegalStateException("Restore journal is missing")
    check(current.transactionId == transactionId) { "Restore journal belongs to another transaction" }
    check(phase.wireId == current.phase.wireId + 1) { "Restore journal phase transition is invalid" }
    val next = BackupRestoreJournalEntry(transactionId, phase)
    write(next)
    return next
  }

  @Synchronized
  fun read(): BackupRestoreJournalEntry? {
    if (!journalFile.exists()) return null
    val encoded = try {
      journalFile.readBytes()
    } catch (failure: IOException) {
      throw IllegalStateException("Restore journal cannot be read", failure)
    }
    if (encoded.size != ENCODED_BYTES) throw IllegalStateException("Restore journal length is invalid")

    val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
    if (buffer.int != MAGIC) throw IllegalStateException("Restore journal format is invalid")
    if (buffer.get().toInt() and 0xff != VERSION) throw IllegalStateException("Restore journal version is unsupported")
    val most = buffer.long
    val least = buffer.long
    val phase = BackupRestorePhase.fromWireId(buffer.get().toInt() and 0xff)
    val expectedCrc = buffer.int.toLong() and 0xffff_ffffL
    val crc = CRC32().apply { update(encoded, 0, CRC_BYTES_OFFSET) }.value
    if (crc != expectedCrc) throw IllegalStateException("Restore journal checksum is invalid")
    val transactionId = UUID(most, least).toString()
    return BackupRestoreJournalEntry(transactionId, phase)
  }

  @Synchronized
  fun finish(transactionId: String) {
    validateTransactionId(transactionId)
    val current = read() ?: throw IllegalStateException("Restore journal is missing")
    check(current.transactionId == transactionId) { "Restore journal belongs to another transaction" }
    try {
      Files.delete(journalFile.toPath())
    } catch (failure: IOException) {
      throw IllegalStateException("Restore journal cannot be removed", failure)
    }
  }

  private fun write(entry: BackupRestoreJournalEntry) {
    if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create restore journal directory")
    val encoded = ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.BIG_ENDIAN)
      .putInt(MAGIC)
      .put(VERSION.toByte())
      .putLong(UUID.fromString(entry.transactionId).mostSignificantBits)
      .putLong(UUID.fromString(entry.transactionId).leastSignificantBits)
      .put(entry.phase.wireId.toByte())
      .apply {
        val crc = CRC32().apply { update(array(), 0, CRC_BYTES_OFFSET) }.value
        putInt(crc.toInt())
      }
      .array()
    val temporary = File(directory, ".$FILE_NAME.${entry.transactionId}.tmp")
    try {
      FileOutputStream(temporary).use { file ->
        DataOutputStream(file).use { output ->
          output.write(encoded)
          output.flush()
          file.fd.sync()
        }
      }
      try {
        Files.move(temporary.toPath(), journalFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), journalFile.toPath(), REPLACE_EXISTING)
      }
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  private fun validateTransactionId(transactionId: String) {
    require(runCatching { UUID.fromString(transactionId).toString() == transactionId }.getOrDefault(false)) {
      "Restore transaction ID is invalid"
    }
  }

  private companion object {
    const val FILE_NAME = "backup-restore.journal"
    const val MAGIC = 0x414E524A // ANRJ
    const val VERSION = 1
    const val ENCODED_BYTES = Int.SIZE_BYTES + 1 + 2 * Long.SIZE_BYTES + 1 + Int.SIZE_BYTES
    const val CRC_BYTES_OFFSET = ENCODED_BYTES - Int.SIZE_BYTES
  }
}

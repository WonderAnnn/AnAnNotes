package com.anan.anannotes.data.files

import com.anan.anannotes.feature.export.OutputTarget
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafBackupDocumentStoreTest {
  @Test
  fun createsUniqueSafFileAndKeepsItOnlyAfterSuccessfulWrite() {
    val access = FakeSafDocuments(existingNames = setOf("anan-backup.anbk"))
    val store = SafBackupDocumentStore(access)

    val receipt = store.writeBackup(
      OutputTarget.DocumentTree("content://tree/backups"),
      requestedName = "anan-backup.anbk",
    ) { output -> output.write(byteArrayOf(1, 2, 3)) }

    assertEquals("anan-backup (2).anbk", receipt.fileName)
    assertArrayEquals(byteArrayOf(1, 2, 3), access.created.getValue(receipt.uriString).toByteArray())
    assertFalse(access.deletedUris.contains(receipt.uriString))
  }

  @Test
  fun failedWriteDeletesOnlyTheNewPartialDocument() {
    val access = FakeSafDocuments(existingNames = setOf("keep.anbk"))
    val store = SafBackupDocumentStore(access)

    assertThrows(IOException::class.java) {
      store.writeBackup(OutputTarget.DocumentTree("tree"), "new.anbk") { output ->
        output.write(11)
        throw IOException("simulated provider failure")
      }
    }

    assertEquals(1, access.deletedUris.size)
    assertTrue(access.deletedUris.single().startsWith("saf://created/"))
    assertEquals(setOf("keep.anbk"), access.existingNames)
  }

  @Test
  fun lowStorageFailureDeletesOnlyTheNewPartialBackupDocument() {
    val access = FakeSafDocuments(existingNames = setOf("keep.anbk"))
    val store = SafBackupDocumentStore(access)

    assertThrows(IOException::class.java) {
      store.writeBackup(OutputTarget.DocumentTree("tree"), "new.anbk") { output ->
        output.write(byteArrayOf(9, 8, 7))
        throw IOException("No space left on device (ENOSPC)")
      }
    }

    assertTrue(access.created.isEmpty())
    assertEquals(1, access.deletedUris.size)
    assertEquals(setOf("keep.anbk"), access.existingNames)
  }

  @Test
  fun stagesOnlyBoundedCiphertextAndDeletesThePrivateTempFileOnClose() {
    val access = FakeSafDocuments().also { it.sources["content://backup"] = byteArrayOf(4, 5, 6, 7) }
    val store = SafBackupDocumentStore(access)
    val directory = Files.createTempDirectory("anan-backup-test").toFile()

    val staged = store.stageEncryptedSource("content://backup", directory, maxBytes = 8L)
    val stagedFile = directory.listFiles().orEmpty().single()
    assertArrayEquals(byteArrayOf(4, 5, 6, 7), staged.openInputStream().use { it.readBytes() })
    assertTrue(stagedFile.exists())
    staged.close()
    assertFalse(stagedFile.exists())

    directory.deleteRecursively()
  }

  @Test
  fun sourceLargerThanLimitIsRejectedAndTemporaryFileIsRemoved() {
    val access = FakeSafDocuments().also { it.sources["content://backup"] = ByteArray(9) }
    val store = SafBackupDocumentStore(access)
    val directory = Files.createTempDirectory("anan-backup-limit-test").toFile()

    assertThrows(IOException::class.java) {
      store.stageEncryptedSource("content://backup", directory, maxBytes = 8L)
    }
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    directory.deleteRecursively()
  }

  @Test
  fun orphanCleanupTouchesOnlyOwnedRandomStageNames() {
    val directory = Files.createTempDirectory("anan-backup-recovery-test").toFile()
    val owned = directory.resolve(".anan-backup-inspect-12345678-1234-1234-1234-123456789012.stage")
    val unrelated = directory.resolve("user-file.stage")
    owned.writeBytes(byteArrayOf(1))
    unrelated.writeBytes(byteArrayOf(2))

    val deleted = SafBackupDocumentStore.cleanupOrphanedStagedFiles(directory)

    assertEquals(1, deleted)
    assertFalse(owned.exists())
    assertTrue(unrelated.exists())
    directory.deleteRecursively()
  }

  private class FakeSafDocuments(
    existingNames: Set<String> = emptySet(),
  ) : SafDocumentAccess {
    val existingNames: MutableSet<String> = existingNames.toMutableSet()
    val created = mutableMapOf<String, ByteArrayOutputStream>()
    val sources = mutableMapOf<String, ByteArray>()
    val deletedUris = mutableListOf<String>()

    override fun listNames(target: OutputTarget.DocumentTree): Set<String> = existingNames.toSet()

    override fun create(target: OutputTarget.DocumentTree, name: String, mimeType: String): SafDocumentRef {
      val uri = "saf://created/${created.size}"
      created[uri] = ByteArrayOutputStream()
      return SafDocumentRef(uri, name, "backups")
    }

    override fun openOutputStream(document: SafDocumentRef) = created[document.uriString]

    override fun openInputStream(uriString: String) = sources[uriString]?.let(::ByteArrayInputStream)

    override fun displayName(document: SafDocumentRef): String? = document.displayName

    override fun delete(document: SafDocumentRef): Boolean {
      deletedUris += document.uriString
      created.remove(document.uriString)
      return true
    }
  }
}

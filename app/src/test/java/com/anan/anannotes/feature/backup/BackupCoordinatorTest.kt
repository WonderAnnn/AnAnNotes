package com.anan.anannotes.feature.backup

import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.data.files.SafBackupDocumentStore
import com.anan.anannotes.data.files.SafDocumentAccess
import com.anan.anannotes.data.files.SafDocumentRef
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.security.BackupAuthenticationException
import com.anan.anannotes.security.BackupContainerCodec
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordSetSummary
import com.anan.anannotes.security.BackupRecordStreamSummary
import com.anan.anannotes.security.CryptoBytes
import com.anan.anannotes.security.KeyDeriver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCoordinatorTest {
  @Test
  fun createThenInspectRevealsCountsOnlyAfterFullValidationAndRestoreCommitsOnce() {
    val source = FakeDocuments()
    val stageDirectory = Files.createTempDirectory("backup-coordinate").toFile()
    val coordinator = coordinator(source, stageDirectory)
    val created = coordinator.createBackup(
      OutputTarget.DocumentTree("content://tree/backups"),
      "anan-backup.anbk",
      "backup-password".toCharArray(),
    ) { writer ->
      writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("folder-1", "学习", FolderKind.USER, 1L, 0))
      writer.write(BackupRecordDomain.ORDINARY, note())
    }

    val inspected = coordinator.inspect(created.destination.uriString, "backup-password".toCharArray())
    assertEquals(1L, inspected.preview.recordSet.ordinaryNotes)
    assertEquals(1L, inspected.preview.recordSet.ordinaryFolders)
    assertTrue(inspected.preview.authenticatedPayloadBytes > 0L)

    val restoreStore = RecordingRestoreStore()
    val targetSnapshot = emptyMap<BackupObjectKey, CryptoBytes>()
    inspected.bindTargetSnapshot(targetSnapshot, BackupTargetRevision.from(targetSnapshot))
    val resolution = inspected.resolveConflictChoices(targetSnapshot, emptyMap())
    val restored = coordinator.restore(inspected, restoreStore, resolution)
    assertEquals(inspected.preview.recordSet, restored)
    assertTrue(restoreStore.committed)
    assertTrue(restoreStore.preparedResolution === resolution)
    assertFalse(restoreStore.aborted)
    assertEquals(2, restoreStore.recordCount)
    assertTrue(stageDirectory.listFiles().orEmpty().isEmpty())
    stageDirectory.deleteRecursively()
  }

  @Test
  fun wrongPasswordDoesNotReturnPreviewAndRemovesCiphertextStage() {
    val docs = FakeDocuments()
    val directory = Files.createTempDirectory("backup-wrong-password").toFile()
    val coordinator = coordinator(docs, directory)
    val created = createSimpleBackup(coordinator)

    assertThrows(BackupAuthenticationException::class.java) {
      coordinator.inspect(created.destination.uriString, "incorrect-password".toCharArray())
    }
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    directory.deleteRecursively()
  }

  @Test
  fun mutationAfterInspectAbortsRestoreAndNeverPublishes() {
    val docs = FakeDocuments()
    val directory = Files.createTempDirectory("backup-changed-after-inspect").toFile()
    val coordinator = coordinator(docs, directory)
    val created = createSimpleBackup(coordinator)
    val inspected = coordinator.inspect(created.destination.uriString, "backup-password".toCharArray())
    val targetSnapshot = emptyMap<BackupObjectKey, CryptoBytes>()
    inspected.bindTargetSnapshot(targetSnapshot, BackupTargetRevision.from(targetSnapshot))
    val resolution = inspected.resolveConflictChoices(targetSnapshot, emptyMap())
    directory.listFiles().orEmpty().single().writeBytes(byteArrayOf(0, 1, 2, 3))
    val target = RecordingRestoreStore()

    assertThrows(Exception::class.java) { coordinator.restore(inspected, target, resolution) }

    assertFalse(target.committed)
    assertTrue(target.aborted)
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    directory.deleteRecursively()
  }

  @Test
  fun inspectedCapabilityCannotBeReused() {
    val docs = FakeDocuments()
    val directory = Files.createTempDirectory("backup-single-use").toFile()
    val coordinator = coordinator(docs, directory)
    val created = createSimpleBackup(coordinator)
    val inspected = coordinator.inspect(created.destination.uriString, "backup-password".toCharArray())
    val target = RecordingRestoreStore()
    val current = emptyMap<BackupObjectKey, CryptoBytes>()
    inspected.bindTargetSnapshot(current, BackupTargetRevision.from(current))
    val resolution = inspected.resolveConflictChoices(current, emptyMap())
    coordinator.restore(inspected, target, resolution)

    assertThrows(IllegalStateException::class.java) {
      coordinator.restore(inspected, RecordingRestoreStore(), resolution)
    }
    directory.deleteRecursively()
  }

  @Test
  fun domainConflictPoliciesResolveEveryConflictWithoutExposingPrivateObjectIdsToUi() {
    val docs = FakeDocuments()
    val directory = Files.createTempDirectory("backup-domain-conflicts").toFile()
    val coordinator = coordinator(docs, directory)
    val created = coordinator.createBackup(
      OutputTarget.DocumentTree("tree"), "mixed.anbk", "backup-password".toCharArray(),
    ) { writer ->
      writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("folder-1", "备份文件夹", FolderKind.USER, 1L, 0))
      writer.write(
        BackupRecordDomain.PRIVATE,
        BackupRecordPayload.Note(
          id = "private-note-1", folderId = "private-uncategorized", title = "私密标题",
          titleManuallySet = false, createdAtMillis = 1L, updatedAtMillis = 2L,
          skinId = "minimal-white", fontSizeSpOverride = null, lineSpacingMultiplierOverride = null,
          isPinned = false, deletedAtMillis = null, contentRevision = 1L, importRequestId = null,
        ),
      )
    }
    val inspected = coordinator.inspect(created.destination.uriString, "backup-password".toCharArray())
    val folderKey = BackupObjectKey(BackupRecordDomain.ORDINARY, com.anan.anannotes.security.BackupRecordKind.FOLDER, "folder-1")
    val privateNoteKey = BackupObjectKey(BackupRecordDomain.PRIVATE, com.anan.anannotes.security.BackupRecordKind.NOTE, "private-note-1")
    val current = mapOf(folderKey to CryptoBytes.of(ByteArray(32) { 1 }), privateNoteKey to CryptoBytes.of(ByteArray(32) { 2 }))
    inspected.bindTargetSnapshot(current, BackupTargetRevision.from(current))

    assertEquals(1, inspected.preview.conflictSummary?.ordinaryConflicts)
    assertEquals(1, inspected.preview.conflictSummary?.privateConflicts)
    val resolution = inspected.resolveConflictChoicesByDomain(
      currentFingerprints = current,
      ordinaryChoice = BackupConflictChoice.USE_BACKUP,
      privateChoice = BackupConflictChoice.KEEP_BOTH,
    )

    assertEquals(setOf(folderKey), resolution.replaceCurrent)
    assertEquals(setOf(privateNoteKey), resolution.keepBoth)
    assertTrue(directory.listFiles().orEmpty().isNotEmpty())
    inspected.close()
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    directory.deleteRecursively()
  }

  @Test
  fun failedTargetPreparationAbortsAndDeletesEncryptedStage() {
    val docs = FakeDocuments()
    val directory = Files.createTempDirectory("backup-prepare-failure").toFile()
    val coordinator = coordinator(docs, directory)
    val created = createSimpleBackup(coordinator)
    val inspected = coordinator.inspect(created.destination.uriString, "backup-password".toCharArray())
    val current = emptyMap<BackupObjectKey, CryptoBytes>()
    inspected.bindTargetSnapshot(current, BackupTargetRevision.from(current))
    val resolution = inspected.resolveConflictChoices(current, emptyMap())
    val target = RecordingRestoreStore(failPreparation = true)

    assertThrows(IllegalStateException::class.java) { coordinator.restore(inspected, target, resolution) }

    assertTrue(target.aborted)
    assertTrue(directory.listFiles().orEmpty().isEmpty())
    directory.deleteRecursively()
  }

  private fun createSimpleBackup(coordinator: BackupCoordinator) = coordinator.createBackup(
    OutputTarget.DocumentTree("tree"), "anan-backup.anbk", "backup-password".toCharArray(),
  ) { writer ->
    writer.write(BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("folder-1", "学习", FolderKind.USER, 1L, 0))
    writer.write(BackupRecordDomain.ORDINARY, note())
  }

  private fun coordinator(documents: FakeDocuments, staging: File): BackupCoordinator = BackupCoordinator(
    containerCodec = BackupContainerCodec(SecureRandom(), TestKeyDeriver),
    documents = SafBackupDocumentStore(documents),
    noBackupDirectory = staging,
  )

  private fun note() = BackupRecordPayload.Note(
    id = "note-1", folderId = "folder-1", title = "title", titleManuallySet = false,
    createdAtMillis = 1L, updatedAtMillis = 2L, skinId = "minimal-white",
    fontSizeSpOverride = null, lineSpacingMultiplierOverride = null, isPinned = false,
    deletedAtMillis = null, contentRevision = 1L, importRequestId = null,
  )

  private class RecordingRestoreStore(private val failPreparation: Boolean = false) : ConflictAwareBackupRestoreStore {
    var committed = false
    var aborted = false
    var recordCount = 0
    var preparedResolution: ResolvedBackupConflictPlan? = null

    override fun prepareConflictResolution(resolution: ResolvedBackupConflictPlan) {
      preparedResolution = resolution
      if (failPreparation) error("test preparation failure")
    }

    override fun onRecord(domain: BackupRecordDomain, payload: BackupRecordPayload) {
      recordCount++
    }

    override fun onAssetBytes(domain: BackupRecordDomain, asset: BackupRecordPayload.Asset, bytes: ByteArray, offset: Int, length: Int) = Unit

    override fun commit(streamSummary: BackupRecordStreamSummary, recordSetSummary: BackupRecordSetSummary) {
      committed = true
    }

    override fun abort() {
      aborted = true
    }
  }

  private class FakeDocuments : SafDocumentAccess {
    private val outputFiles = linkedMapOf<String, ByteArrayOutputStream>()
    private val sources = mutableMapOf<String, ByteArray>()
    private var sequence = 0

    override fun listNames(target: OutputTarget.DocumentTree): Set<String> = emptySet()

    override fun create(target: OutputTarget.DocumentTree, name: String, mimeType: String): SafDocumentRef {
      val uri = "content://backup/${sequence++}"
      outputFiles[uri] = ByteArrayOutputStream()
      return SafDocumentRef(uri, name, "backups")
    }

    override fun openOutputStream(document: SafDocumentRef): OutputStream? =
      outputFiles[document.uriString]

    override fun openInputStream(uriString: String): InputStream? =
      (outputFiles[uriString]?.toByteArray() ?: sources[uriString])?.let(::ByteArrayInputStream)

    override fun displayName(document: SafDocumentRef): String? = document.displayName

    override fun delete(document: SafDocumentRef): Boolean {
      outputFiles.remove(document.uriString)
      sources.remove(document.uriString)
      return true
    }

    fun snapshot(uri: String) = outputFiles[uri]?.toByteArray()
  }

  private object TestKeyDeriver : KeyDeriver {
    override fun derive(credential: CharArray, salt: CryptoBytes, parameters: com.anan.anannotes.security.KdfParameters): ByteArray {
      val passwordBytes = String(credential).toByteArray(Charsets.UTF_8)
      val saltBytes = salt.copyBytes()
      return try {
        MessageDigest.getInstance("SHA-256").apply {
          update(passwordBytes)
          update(saltBytes)
        }.digest()
      } finally {
        passwordBytes.fill(0)
        saltBytes.fill(0)
      }
    }
  }
}

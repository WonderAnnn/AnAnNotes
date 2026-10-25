package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.FormattingDecodeError
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.security.KeyDeriver
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.PrivateCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PrivateStoreTest {
  @Test
  fun restoreCanEncryptAssetsBeforeSnapshotIsKnownAndThenPublishTheCompleteVault() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val transactionId = "083e4567-e89b-12d3-a456-426614174000"
    val bytes = "private asset restored before metadata".toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

    val session = runBlocking {
      store.beginRestoreVault(transactionId, null, "restore-password".toCharArray(), null)
    }
    val reference = runBlocking {
      store.writePrivateAssetFromStream(
        session.access,
        java.io.ByteArrayInputStream(bytes),
        bytes.size.toLong(),
        digest,
        transactionId,
      )
    }
    val restoredSnapshot = PrivateVaultSnapshot(
      notes = listOf(
        PrivateNoteDocument(
          id = PrivateNoteId("private-note"),
          title = "restored private note",
          blocks = listOf(NoteBlockDraft(type = BlockType.IMAGE, assetPath = reference)),
          createdAtMillis = 1L,
          updatedAtMillis = 2L,
          contentRevision = 1L,
        ),
      ),
    )

    runBlocking {
      store.stageRestoreVaultSnapshot(transactionId, session.access, restoredSnapshot)
      store.publishRestoreVault(transactionId)
      assertEquals(restoredSnapshot, store.readSnapshot(session.access))
      store.finishRestoreVault(transactionId, committed = true)
    }
    session.close()

    val unlocked = runBlocking { store.unlockWithPassword("restore-password".toCharArray()) }
    assertArrayEquals(bytes, runBlocking { store.readPrivateAsset(unlocked, reference) })
    store.lock(unlocked)
  }

  @Test
  fun newRestoreVaultStaysInvisibleUntilPublishAndRollsBackToUnconfigured() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val transactionId = "c7a8f8a4-5d45-4d4a-bb3c-92c7672da102"
    val restoredSnapshot = PrivateVaultSnapshot()

    val session = runBlocking {
      store.prepareRestoreVault(transactionId, null, "new-target-password".toCharArray(), null, restoredSnapshot)
    }
    assertFalse(runBlocking { store.isConfigured() })
    runBlocking { store.publishRestoreVault(transactionId) }
    assertTrue(runBlocking { store.isConfigured() })
    assertEquals(restoredSnapshot, runBlocking { store.readSnapshot(session.access) })

    runBlocking { store.finishRestoreVault(transactionId, committed = false) }

    assertFalse(runBlocking { store.isConfigured() })
    assertFalse(File(filesRoot, "private/restore-vault-$transactionId").exists())
    session.close()
  }

  @Test
  fun existingRestoreVaultUsesItsKeyAndRollbackRestoresOriginalEncryptedSnapshot() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("existing-target-password".toCharArray(), null) }
    val original = PrivateVaultSnapshot()
    val replacement = PrivateVaultSnapshot()
    runBlocking { store.writeSnapshot(access, original) }
    val snapshotPath = File(filesRoot, "private/vault.snapshot")
    val originalCiphertext = snapshotPath.readBytes()
    val transactionId = "69a1d4ca-041d-4559-a2b3-0d44a7632ae5"

    val session = runBlocking {
      store.prepareRestoreVault(transactionId, access, null, null, replacement)
    }
    assertTrue(session.access === access)
    runBlocking { store.publishRestoreVault(transactionId) }
    assertEquals(replacement, runBlocking { store.readSnapshot(access) })
    runBlocking { store.finishRestoreVault(transactionId, committed = false) }

    assertArrayEquals(originalCiphertext, snapshotPath.readBytes())
    assertEquals(original, runBlocking { store.readSnapshot(access) })
    session.close()
    store.lock(access)
  }

  @Test
  fun committedNewRestoreVaultCanBeUnlockedWithTheNewPassword() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val transactionId = "8f2cc42c-0c74-4159-86d2-83e39858d102"
    val restoredSnapshot = PrivateVaultSnapshot()
    val session = runBlocking {
      store.prepareRestoreVault(transactionId, null, "restored-vault-password".toCharArray(), null, restoredSnapshot)
    }

    runBlocking {
      store.publishRestoreVault(transactionId)
      store.finishRestoreVault(transactionId, committed = true)
    }
    session.close()

    val unlocked = runBlocking { store.unlockWithPassword("restored-vault-password".toCharArray()) }
    assertEquals(restoredSnapshot, runBlocking { store.readSnapshot(unlocked) })
    assertFalse(File(filesRoot, "private/restore-vault-$transactionId").exists())
    store.lock(unlocked)
  }

  @Test
  fun privateExportHistoryRoundTripsOnlyInsideTheEncryptedVault() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("history-password".toCharArray(), null) }
    val history = PrivateExportHistoryRecord(
      id = "history-entry-id",
      noteId = PrivateNoteId("private-note-id"),
      noteTitle = "私密标题-不得明文",
      format = ExportFormat.PDF,
      fileName = "私密标题.pdf",
      destinationUri = "content://private-destination/document/7",
      createdAtMillis = 55L,
      succeeded = true,
    )
    val snapshot = PrivateVaultSnapshot(exportHistory = listOf(history))

    runBlocking { store.writeSnapshot(access, snapshot) }

    assertEquals(snapshot, runBlocking { store.readSnapshot(access) })
    assertTrue(filesRoot.walkTopDown().filter(File::isFile).none { file ->
      val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
      bytes.contains(history.noteTitle) || bytes.contains(history.fileName) || bytes.contains(history.destinationUri)
    })
    store.lock(access)
    val reopened = runBlocking { store.unlockWithPassword("history-password".toCharArray()) }
    assertEquals(listOf(history), runBlocking { store.readSnapshot(reopened) }.exportHistory)
    store.lock(reopened)
  }

  @Test
  fun versionTwoPrivateVaultSnapshotMigratesWithEmptyExportHistory() {
    val decoded = PrivateVaultSnapshotCodec.decode(legacyV2SnapshotBytes())

    assertEquals("旧版私密正文", decoded.notes.single().blocks.single().text)
    assertTrue(decoded.exportHistory.isEmpty())
  }

  @Test
  fun privateSnapshotIsEncryptedAtomicAndRecoveryKeyCanUnlockIt() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val password = "vault-password".toCharArray()
    val recovery = "vault-recovery-key".toCharArray()
    val access = runBlocking { store.create(password, recovery) }
    val snapshot = PrivateVaultSnapshot(
      notes = listOf(
        PrivateNoteDocument(
          id = PrivateNoteId("private-note-1"),
          folderId = "private-uncategorized",
          title = "随手记：诊疗信息",
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(
              id = "block-1",
              type = BlockType.TEXT,
              text = "只应留在私密库里的正文",
              formatting = BlockFormattingState.Supported(
                BlockFormatting(
                  presentation = TextBlockPresentation.HEADING,
                  inlineStyles = listOf(InlineStyleRange(0, 3, InlineStyle.BOLD)),
                ),
              ),
            ),
            NoteBlockDraft(
              id = "unknown-format-block",
              type = BlockType.TEXT,
              text = "未知格式也需加密保留",
              formatting = BlockFormattingState.Unsupported(
                byteArrayOf(0x41, 0x4e, 0x46, 0x4d, 99),
                FormattingDecodeError.UNSUPPORTED_VERSION,
              ),
            ),
            NoteBlockDraft(id = "todo-1", type = BlockType.TODO, text = "复查报告"),
          ),
          todos = listOf(PrivateTodo("todo-1", "todo-1", "复查报告", completed = false, updatedAtMillis = 100L)),
          skinId = "deep-sea",
          createdAtMillis = 50L,
          updatedAtMillis = 100L,
          contentRevision = 1L,
        ),
      ),
    )
    runBlocking { store.writeSnapshot(access, snapshot) }

    val privateFiles = File(filesRoot, "private")
    val serializedFiles = privateFiles.walkTopDown().filter(File::isFile).toList()
    assertTrue(serializedFiles.isNotEmpty())
    serializedFiles.forEach { file ->
      val bytes = file.readBytes()
      assertFalse(bytes.containsUtf8("随手记：诊疗信息"))
      assertFalse(bytes.containsUtf8("只应留在私密库里的正文"))
    }
    assertEquals(snapshot, runBlocking { store.readSnapshot(access) })

    store.lock(access)
    assertThrows(IllegalStateException::class.java) {
      runBlocking { store.readSnapshot(access) }
    }
    val restored = runBlocking { store.unlockWithRecoveryKey("vault-recovery-key".toCharArray()) }
    assertEquals(snapshot, runBlocking { store.readSnapshot(restored) })
    store.lock(restored)
  }

  @Test
  fun privateSnapshotV1FixtureLoadsWithDefaultFormatting() {
    val decoded = PrivateVaultSnapshotCodec.decode(legacyV1SnapshotBytes())

    assertEquals(1, decoded.notes.size)
    assertEquals("旧版私密正文", decoded.notes.single().blocks.single().text)
    assertEquals(
      BlockFormattingState.Supported(BlockFormatting()),
      decoded.notes.single().blocks.single().formatting,
    )
  }

  @Test
  fun failedAuthenticationDoesNotReturnAnAccessSession() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    runBlocking { store.create("right-password".toCharArray(), null) }

    assertThrows(Exception::class.java) {
      runBlocking { store.unlockWithPassword("wrong-password".toCharArray()) }
    }
  }

  @Test
  fun passwordReauthenticationConfirmsTheCurrentKeyWithoutReplacingItsSession() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("right-password".toCharArray(), "recovery-key".toCharArray()) }
    val presentedPassword = "right-password".toCharArray()

    assertTrue(runBlocking { store.verifyPassword(access, presentedPassword) })
    assertTrue(presentedPassword.all { it == '\u0000' })
    assertTrue(access.isActive)
    assertEquals(0, runBlocking { store.readSnapshot(access) }.notes.size)

    store.lock(access)
  }

  @Test
  fun wrongOrRecoveryCredentialCannotAuthorizeAnExportAndDoesNotLockTheVault() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("right-password".toCharArray(), "recovery-key".toCharArray()) }
    val wrongPassword = "wrong-password".toCharArray()
    val recoveryKey = "recovery-key".toCharArray()

    assertFalse(runBlocking { store.verifyPassword(access, wrongPassword) })
    assertFalse(runBlocking { store.verifyPassword(access, recoveryKey) })
    assertTrue(wrongPassword.all { it == '\u0000' })
    assertTrue(recoveryKey.all { it == '\u0000' })
    assertTrue(access.isActive)

    store.lock(access)
  }

  @Test
  fun credentialIsWipedEvenWhenTheEnvelopeCannotBeRead() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val password = "do-not-retain".toCharArray()

    assertThrows(Exception::class.java) { runBlocking { store.unlockWithPassword(password) } }
    assertTrue(password.all { it == '\u0000' })
  }

  @Test
  fun envelopeAndSnapshotStaySeparateFromOrdinaryNoteFiles() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access: PrivateAccess = runBlocking { store.create("local-only".toCharArray(), null) }
    val note = PrivateNoteDocument(
      id = PrivateNoteId("opaque-random-id"),
      title = "health details",
      blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "never index this text")),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    runBlocking { store.writeSnapshot(access, PrivateVaultSnapshot(notes = listOf(note))) }

    assertFalse(File(filesRoot, "anan-notes.db").exists())
    assertFalse(File(filesRoot, "drafts").exists())
    assertTrue(File(File(filesRoot, "private"), "vault.snapshot").exists())
    assertArrayEquals(
      PrivateVaultSnapshot(notes = listOf(note)).notes.single().blocks.single().text!!.toByteArray(),
      runBlocking { store.readSnapshot(access) }.notes.single().blocks.single().text!!.toByteArray(),
    )
    store.lock(access)
  }

  @Test
  fun privateImageAssetsAreEncryptedAndCannotBeDeletedWhileReferenced() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("image-password".toCharArray(), "image-recovery".toCharArray()) }
    val original = "original image bytes must remain private".toByteArray(Charsets.UTF_8)
    val reference = runBlocking { store.writePrivateAsset(access, original) }
    val note = PrivateNoteDocument(
      id = PrivateNoteId("note-with-image"),
      title = "image note",
      blocks = listOf(NoteBlockDraft(id = "image-1", type = BlockType.IMAGE, assetPath = reference)),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    runBlocking { store.writeSnapshot(access, PrivateVaultSnapshot(notes = listOf(note))) }

    assertFalse(File(filesRoot, "private/assets").walkTopDown().filter(File::isFile).any { file ->
      file.readBytes().containsUtf8("original image bytes must remain private")
    })
    assertArrayEquals(original, runBlocking { store.readPrivateAsset(access, reference) })
    assertFalse(runBlocking { store.deletePrivateAssetIfUnreferenced(access, reference) })
    runBlocking { store.writeSnapshot(access, PrivateVaultSnapshot()) }
    assertTrue(runBlocking { store.deletePrivateAssetIfUnreferenced(access, reference) })
    store.lock(access)
  }

  @Test
  fun privateAssetCanBeStreamedForExportWithoutWritingDecryptedBytesToDisk() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("asset-export-password".toCharArray(), null) }
    val original = "private image bytes used only while exporting".toByteArray(Charsets.UTF_8)
    val reference = runBlocking { store.writePrivateAsset(access, original) }

    val exported = store.openPrivateAssetForExport(access, reference).use { it.readBytes() }

    assertArrayEquals(original, exported)
    assertTrue(File(filesRoot, "private").walkTopDown().filter(File::isFile).none { file ->
      file.readBytes().containsUtf8("private image bytes used only while exporting")
    })
    store.lock(access)
  }

  @Test
  fun privateAssetRestoreEncryptsBoundedChunksAndVerifiesTheWholeAsset() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("stream-restore-password".toCharArray(), null) }
    val original = ByteArray(512 * 1024) { index -> (index % 239).toByte() }
    val plaintextMarker = "streamed-private-plaintext-sentinel"
    plaintextMarker.toByteArray(Charsets.UTF_8).copyInto(original)
    var largestRequestedRead = 0
    val input = object : java.io.ByteArrayInputStream(original) {
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        largestRequestedRead = maxOf(largestRequestedRead, length)
        return super.read(buffer, offset, length)
      }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(original)

    val reference = runBlocking {
      store.writePrivateAssetFromStream(access, input, original.size.toLong(), digest)
    }

    assertArrayEquals(original, runBlocking { store.readPrivateAsset(access, reference) })
    assertTrue(largestRequestedRead <= 64 * 1024)
    assertTrue(File(filesRoot, "private/assets").walkTopDown().filter(File::isFile).none { file ->
      file.readBytes().containsUtf8(plaintextMarker)
    })
    store.lock(access)
  }

  @Test
  fun privateAssetRestoreRejectsDigestMismatchAndLeavesNoPublishedAsset() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("stream-restore-password".toCharArray(), null) }
    val original = "secret attachment from the backup".toByteArray(Charsets.UTF_8)
    val wrongDigest = ByteArray(32) { 0x5a }

    assertThrows(java.io.IOException::class.java) {
      runBlocking {
        store.writePrivateAssetFromStream(
          access,
          java.io.ByteArrayInputStream(original),
          original.size.toLong(),
          wrongDigest,
        )
      }
    }

    assertTrue(File(filesRoot, "private/assets").listFiles().orEmpty().isEmpty())
    store.lock(access)
  }

  @Test
  fun privateAssetRestoreStagesCiphertextUnderAValidatedTransactionDirectory() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("stream-restore-password".toCharArray(), null) }
    val transactionId = "123e4567-e89b-12d3-a456-426614174000"
    val original = "private backup asset staged only as ciphertext".toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256").digest(original)

    val reference = runBlocking {
      store.writePrivateAssetFromStream(
        access,
        java.io.ByteArrayInputStream(original),
        original.size.toLong(),
        digest,
        transactionId,
      )
    }

    assertTrue(reference.startsWith("private-asset:restore-$transactionId/"))
    assertArrayEquals(original, runBlocking { store.readPrivateAsset(access, reference) })
    val stagingDirectory = File(filesRoot, "private/assets/restore-$transactionId")
    assertTrue(stagingDirectory.isDirectory)
    assertTrue(stagingDirectory.walkTopDown().filter(File::isFile).all { file ->
      !file.readBytes().containsUtf8("private backup asset staged only as ciphertext")
    })

    runBlocking { store.deleteUncommittedPrivateAssetRestoreGroup(transactionId) }

    assertFalse(stagingDirectory.exists())
    store.lock(access)
  }

  @Test
  fun committedRestoreCanPruneOnlyAnUnreferencedAssetOwnedByThatTransaction() = withTempRoot { filesRoot ->
    val store = PrivateStore(filesRoot, testCrypto())
    val access = runBlocking { store.create("stream-restore-password".toCharArray(), null) }
    val transactionId = "423e4567-e89b-12d3-a456-426614174000"
    val bytes = "staged private asset".toByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val reference = runBlocking {
      store.writePrivateAssetFromStream(access, java.io.ByteArrayInputStream(bytes), bytes.size.toLong(), digest, transactionId)
    }

    assertTrue(runBlocking { store.deletePrivateAssetFromRestoreGroup(transactionId, reference) })
    assertFalse(runBlocking { store.hasPrivateAsset(access, reference) })
    assertTrue(runBlocking { store.deletePrivateAssetFromRestoreGroup(transactionId, reference) })
    store.lock(access)
  }

  private fun testCrypto(): PrivateCrypto {
    val kdf = KeyDeriver { credential, salt, _ ->
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(salt.copyBytes(), "HmacSHA256"))
      val credentialBytes = String(credential).toByteArray(Charsets.UTF_8)
      try {
        mac.doFinal(credentialBytes)
      } finally {
        credentialBytes.fill(0)
      }
    }
    return PrivateCrypto(SecureRandom(), kdf)
  }

  private fun withTempRoot(block: (File) -> Unit) {
    val root = Files.createTempDirectory("anan-private-test-").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun ByteArray.containsUtf8(value: String): Boolean {
    val haystack = toString(Charsets.ISO_8859_1)
    return haystack.contains(value.toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1))
  }

  private fun legacyV1SnapshotBytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out ->
      out.writeInt(0x414e5653) // ANVS
      out.writeInt(1)
      out.writeInt(1) // notes
      out.writeString("legacy-private-note")
      out.writeString("private-uncategorized")
      out.writeString("旧版私密笔记")
      out.writeBoolean(true)
      out.writeInt(1) // blocks
      out.writeString("legacy-text-block")
      out.writeInt(BlockType.TEXT.ordinal)
      out.writeNullableString("旧版私密正文")
      out.writeNullableString(null)
      out.writeNullableString(null)
      out.writeFloat(100f)
      out.writeInt(BlockAlignment.CENTER.ordinal)
      out.writeNullableFloat(null)
      out.writeInt(-1)
      out.writeInt(0) // todos
      out.writeString("minimal-white")
      out.writeNullableFloat(null)
      out.writeNullableFloat(null)
      out.writeLong(10L)
      out.writeLong(11L)
      out.writeBoolean(false)
      out.writeBoolean(false)
      out.writeLong(1L)
      out.writeInt(0) // drafts
    }
    bytes.toByteArray()
  }

  private fun legacyV2SnapshotBytes(): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out ->
      out.writeInt(0x414e5653) // ANVS
      out.writeInt(2)
      out.writeInt(1)
      out.writeString("legacy-private-note-v2")
      out.writeString("private-uncategorized")
      out.writeString("旧版私密笔记")
      out.writeBoolean(true)
      out.writeInt(1)
      out.writeString("legacy-v2-block")
      out.writeInt(BlockType.TEXT.ordinal)
      out.writeNullableString("旧版私密正文")
      out.writeNullableString(null)
      out.writeNullableString(null)
      out.writeFloat(100f)
      out.writeInt(BlockAlignment.CENTER.ordinal)
      out.writeNullableFloat(null)
      out.writeInt(-1)
      out.writeInt(-1) // no formatting payload
      out.writeInt(0) // todos
      out.writeString("minimal-white")
      out.writeNullableFloat(null)
      out.writeNullableFloat(null)
      out.writeLong(10L)
      out.writeLong(11L)
      out.writeBoolean(false)
      out.writeBoolean(false)
      out.writeLong(1L)
      out.writeInt(0) // drafts
    }
    bytes.toByteArray()
  }

  private fun DataOutputStream.writeString(value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
  }

  private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
  }

  private fun DataOutputStream.writeNullableFloat(value: Float?) {
    writeBoolean(value != null)
    if (value != null) writeFloat(value)
  }
}

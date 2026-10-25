package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingCodec
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.security.CredentialKind
import com.anan.anannotes.security.CryptoBytes
import com.anan.anannotes.security.EncryptedPrivateRecord
import com.anan.anannotes.security.KeyEnvelopeCodec
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.PrivateAuthenticationException
import com.anan.anannotes.security.PrivateCrypto
import com.anan.anannotes.security.PrivateRecordContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.UUID

@JvmInline
value class PrivateNoteId(val value: String)

data class PrivateTodo(
  val id: String,
  val blockId: String,
  val text: String,
  val completed: Boolean,
  val updatedAtMillis: Long,
)

data class PrivateTodoItem(
  val noteId: PrivateNoteId,
  val noteTitle: String,
  val todoId: String,
  val text: String,
  val completed: Boolean,
  val updatedAtMillis: Long,
)

data class PrivateNoteDocument(
  val id: PrivateNoteId,
  val folderId: String = PRIVATE_UNCATEGORIZED_FOLDER_ID,
  val title: String,
  val titleManuallySet: Boolean = false,
  val blocks: List<NoteBlockDraft>,
  val todos: List<PrivateTodo> = emptyList(),
  val skinId: String = "minimal-white",
  val fontSizeSpOverride: Float? = null,
  val lineSpacingMultiplierOverride: Float? = null,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val isPinned: Boolean = false,
  val deletedAtMillis: Long? = null,
  val contentRevision: Long,
)

data class PrivateNotePreview(
  val id: PrivateNoteId,
  val title: String,
  val updatedAtMillis: Long,
  val folderId: String,
)

data class PrivateExportHistoryRecord(
  val id: String,
  val noteId: PrivateNoteId,
  val noteTitle: String,
  val format: ExportFormat,
  val fileName: String,
  val destinationUri: String,
  val createdAtMillis: Long,
  val succeeded: Boolean,
  val errorCode: String? = null,
)

data class PrivateEditSnapshot(
  val draftId: String,
  val noteId: PrivateNoteId?,
  val folderId: String,
  val title: String,
  val titleManuallySet: Boolean,
  val blocks: List<NoteBlockDraft>,
  val skinId: String,
  val fontSizeSpOverride: Float? = null,
  val lineSpacingMultiplierOverride: Float? = null,
  val baseContentRevision: Long?,
  val localRevision: Long,
  val savedAtMillis: Long,
)

data class PrivateVaultSnapshot(
  val notes: List<PrivateNoteDocument> = emptyList(),
  val drafts: List<PrivateEditSnapshot> = emptyList(),
  val exportHistory: List<PrivateExportHistoryRecord> = emptyList(),
)

/** Owns a newly-created key only when restoring into a device without an existing vault. */
internal class PrivateVaultRestoreSession(
  val access: PrivateAccess,
  private val ownsAccess: Boolean,
) : AutoCloseable {
  override fun close() {
    if (ownsAccess) access.revoke()
  }
}

sealed interface PrivateSaveResult {
  data class Saved(val noteId: PrivateNoteId, val contentRevision: Long) : PrivateSaveResult
  data object EmptyNotSaved : PrivateSaveResult
  data class Conflict(val currentRevision: Long) : PrivateSaveResult
}

class PrivateStore(
  filesDirectory: File,
  private val crypto: PrivateCrypto = PrivateCrypto(),
  private val random: SecureRandom = SecureRandom(),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
  private val privateDirectory = File(filesDirectory, PRIVATE_DIRECTORY)
  private val privateAssetsDirectory = File(privateDirectory, PRIVATE_ASSETS_DIRECTORY)
  private val envelopeFile = File(privateDirectory, ENVELOPE_FILE)
  private val snapshotFile = File(privateDirectory, SNAPSHOT_FILE)
  private val mutex = Mutex()

  suspend fun isConfigured(): Boolean = withContext(ioDispatcher) { envelopeFile.isFile }

  suspend fun create(password: CharArray, recoveryKey: CharArray?): PrivateAccess {
    val dataKey = ByteArray(PrivateCrypto.DATA_KEY_BYTES).also(random::nextBytes)
    val envelope = try {
      withContext(cryptoDispatcher) { crypto.createKeyEnvelope(dataKey, password, recoveryKey) }
    } catch (failure: Throwable) {
      MemoryWipe.bytes(dataKey)
      MemoryWipe.chars(password)
      MemoryWipe.chars(recoveryKey)
      throw failure
    }
    val access = PrivateAccess(dataKey)
    try {
      mutex.withLock {
        withContext(ioDispatcher) {
          check(!envelopeFile.exists()) { "Private module is already configured" }
          if (!privateDirectory.exists() && !privateDirectory.mkdirs()) {
            throw IOException("Cannot create private storage directory")
          }
          val emptySnapshot = PrivateVaultSnapshotCodec.encode(PrivateVaultSnapshot())
          try {
            val sealed = access.withDataKey { key ->
              crypto.encrypt(key, emptySnapshot, VAULT_CONTEXT)
            }
            writeAtomically(snapshotFile, EncryptedRecordCodec.encode(sealed))
            writeAtomically(envelopeFile, KeyEnvelopeCodec.encode(envelope))
          } finally {
            MemoryWipe.bytes(emptySnapshot)
          }
        }
      }
      return access
    } catch (failure: Throwable) {
      access.revoke()
      throw failure
    } finally {
      MemoryWipe.bytes(dataKey)
      MemoryWipe.chars(password)
      MemoryWipe.chars(recoveryKey)
    }
  }

  suspend fun unlockWithPassword(password: CharArray): PrivateAccess =
    unlock(password, CredentialKind.PASSWORD)

  suspend fun unlockWithRecoveryKey(recoveryKey: CharArray): PrivateAccess =
    unlock(recoveryKey, CredentialKind.RECOVERY)

  /** Rechecks the password against the active vault without replacing or extending the session. */
  suspend fun verifyPassword(access: PrivateAccess, password: CharArray): Boolean {
    var candidateKey: ByteArray? = null
    try {
      access.withDataKey { Unit }
      val encodedEnvelope = withContext(ioDispatcher) { readBounded(envelopeFile, MAX_ENVELOPE_BYTES) }
      val envelope = try {
        KeyEnvelopeCodec.decode(encodedEnvelope)
      } finally {
        MemoryWipe.bytes(encodedEnvelope)
      }
      candidateKey = withContext(cryptoDispatcher) {
        crypto.unlockDataKey(envelope, password, CredentialKind.PASSWORD)
      }
      return access.withDataKey { currentKey -> MessageDigest.isEqual(currentKey, candidateKey) }
    } catch (_: PrivateAuthenticationException) {
      return false
    } finally {
      MemoryWipe.bytes(candidateKey)
      MemoryWipe.chars(password)
    }
  }

  private suspend fun unlock(credential: CharArray, kind: CredentialKind): PrivateAccess {
    try {
      val encodedEnvelope = withContext(ioDispatcher) { readBounded(envelopeFile, MAX_ENVELOPE_BYTES) }
      val envelope = try {
        KeyEnvelopeCodec.decode(encodedEnvelope)
      } finally {
        MemoryWipe.bytes(encodedEnvelope)
      }
      val dataKey = withContext(cryptoDispatcher) { crypto.unlockDataKey(envelope, credential, kind) }
      val access = PrivateAccess(dataKey)
      MemoryWipe.bytes(dataKey)
      try {
        readSnapshot(access)
        return access
      } catch (failure: Throwable) {
        access.revoke()
        if (failure is PrivateAuthenticationException) throw failure
        throw failure
      }
    } finally {
      MemoryWipe.chars(credential)
    }
  }

  suspend fun readSnapshot(access: PrivateAccess): PrivateVaultSnapshot = mutex.withLock {
    readSnapshotUnlocked(access)
  }

  internal suspend fun <T> mutateSnapshot(
    access: PrivateAccess,
    transform: suspend (PrivateVaultSnapshot) -> Pair<PrivateVaultSnapshot, T>,
  ): T = mutex.withLock {
    val current = readSnapshotUnlocked(access)
    val (updated, result) = transform(current)
    writeSnapshotUnlocked(access, updated)
    result
  }

  private suspend fun readSnapshotUnlocked(access: PrivateAccess): PrivateVaultSnapshot {
    val encoded = withContext(ioDispatcher) { readBounded(snapshotFile, MAX_SNAPSHOT_FILE_BYTES) }
    val encrypted = try {
      EncryptedRecordCodec.decode(encoded)
    } finally {
      MemoryWipe.bytes(encoded)
    }
    val plaintext = withContext(cryptoDispatcher) {
      access.withDataKey { key -> crypto.decrypt(key, encrypted, VAULT_CONTEXT) }
    }
    return try {
      PrivateVaultSnapshotCodec.decode(plaintext)
    } finally {
      MemoryWipe.bytes(plaintext)
    }
  }

  suspend fun writeSnapshot(access: PrivateAccess, snapshot: PrivateVaultSnapshot) = mutex.withLock {
    writeSnapshotUnlocked(access, snapshot)
  }

  /** Prepares a replacement private snapshot without publishing it to the live vault. */
  internal suspend fun prepareRestoreVault(
    restoreTransactionId: String,
    existingAccess: PrivateAccess?,
    newPassword: CharArray?,
    newRecoveryKey: CharArray?,
    snapshot: PrivateVaultSnapshot,
  ): PrivateVaultRestoreSession {
    val session = beginRestoreVault(restoreTransactionId, existingAccess, newPassword, newRecoveryKey)
    try {
      stageRestoreVaultSnapshot(restoreTransactionId, session.access, snapshot)
      return session
    } catch (failure: Throwable) {
      session.close()
      runCatching { finishRestoreVault(restoreTransactionId, committed = false) }
        .exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    }
  }

  /** Creates or reuses the target DEK and a rollback snapshot before streamed assets are restored. */
  internal suspend fun beginRestoreVault(
    restoreTransactionId: String,
    existingAccess: PrivateAccess?,
    newPassword: CharArray?,
    newRecoveryKey: CharArray?,
  ): PrivateVaultRestoreSession {
    validateRestoreTransactionId(restoreTransactionId)
    var ownedAccess: PrivateAccess? = null
    var dataKey: ByteArray? = null
    try {
      return mutex.withLock {
        withContext(ioDispatcher) {
          val stage = restoreVaultDirectory(restoreTransactionId)
          require(!stage.exists()) { "Private restore transaction already exists" }
          if (!privateDirectory.exists() && !privateDirectory.mkdirs()) throw IOException("Cannot create private storage directory")
          if (!stage.mkdirs()) throw IOException("Cannot create private restore staging directory")

          val configured = envelopeFile.isFile
          val access: PrivateAccess
          val ownsAccess: Boolean
          if (configured) {
            require(existingAccess != null) { "Unlock the target private vault before restoring" }
            require(newPassword == null && newRecoveryKey == null) { "Existing private vault credentials cannot be replaced during restore" }
            existingAccess.withDataKey { Unit }
            if (!snapshotFile.isFile) throw IOException("Target private snapshot is missing")
            access = existingAccess
            ownsAccess = false
          } else {
            require(existingAccess == null) { "Target private vault state changed; inspect the backup again" }
            require(newPassword != null) { "A new private password is required" }
            require(!snapshotFile.exists()) { "Unconfigured private vault contains unexpected snapshot data" }
            dataKey = ByteArray(PrivateCrypto.DATA_KEY_BYTES).also(random::nextBytes)
            val envelope = withContext(cryptoDispatcher) {
              crypto.createKeyEnvelope(dataKey!!, newPassword, newRecoveryKey)
            }
            access = PrivateAccess(dataKey!!)
            ownedAccess = access
            ownsAccess = true
            val encodedEnvelope = KeyEnvelopeCodec.encode(envelope)
            try {
              writeAtomically(File(stage, NEW_ENVELOPE_FILE), encodedEnvelope)
            } finally {
              MemoryWipe.bytes(encodedEnvelope)
            }
          }

          val hadEnvelope = envelopeFile.isFile
          val hadSnapshot = snapshotFile.isFile
          if (configured && (!hadEnvelope || !hadSnapshot)) throw IOException("Target private vault is incomplete")
          if (hadEnvelope) copyFileDurably(envelopeFile, File(stage, OLD_ENVELOPE_FILE))
          if (hadSnapshot) copyFileDurably(snapshotFile, File(stage, OLD_SNAPSHOT_FILE))

          // The private-only marker lets recovery restore old files even if the process dies while
          // the typed records and encrypted assets are still being staged.
          writeRestoreVaultManifest(stage, hadEnvelope, hadSnapshot)
          PrivateVaultRestoreSession(access, ownsAccess)
        }
      }
    } catch (failure: Throwable) {
      ownedAccess?.revoke()
      runCatching { deleteRestoreVaultDirectory(restoreTransactionId) }.exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    } finally {
      MemoryWipe.bytes(dataKey)
      MemoryWipe.chars(newPassword)
      MemoryWipe.chars(newRecoveryKey)
    }
  }

  /** Writes the completed private snapshot under the session DEK, never to ordinary staging. */
  internal suspend fun stageRestoreVaultSnapshot(
    restoreTransactionId: String,
    access: PrivateAccess,
    snapshot: PrivateVaultSnapshot,
  ) = mutex.withLock {
    validateRestoreTransactionId(restoreTransactionId)
    withContext(ioDispatcher) {
      val stage = restoreVaultDirectory(restoreTransactionId)
      readRestoreVaultManifest(stage)
      val stagedSnapshot = File(stage, NEW_SNAPSHOT_FILE)
      require(!stagedSnapshot.exists()) { "Private restore snapshot is already staged" }
      val plaintext = withContext(cryptoDispatcher) { PrivateVaultSnapshotCodec.encode(snapshot) }
      try {
        val encrypted = withContext(cryptoDispatcher) {
          access.withDataKey { key -> crypto.encrypt(key, plaintext, VAULT_CONTEXT) }
        }
        val encodedSnapshot = EncryptedRecordCodec.encode(encrypted)
        try {
          writeAtomically(stagedSnapshot, encodedSnapshot)
        } finally {
          MemoryWipe.bytes(encodedSnapshot)
        }
      } finally {
        MemoryWipe.bytes(plaintext)
      }
    }
  }

  /** Publishes only staged ciphertext/envelope files; startup remains gated until Room commits. */
  internal suspend fun publishRestoreVault(restoreTransactionId: String) = mutex.withLock {
    validateRestoreTransactionId(restoreTransactionId)
    withContext(ioDispatcher) {
      val stage = restoreVaultDirectory(restoreTransactionId)
      readRestoreVaultManifest(stage)
      val stagedEnvelope = File(stage, NEW_ENVELOPE_FILE)
      if (stagedEnvelope.isFile) copyFileAtomically(stagedEnvelope, envelopeFile)
      val stagedSnapshot = File(stage, NEW_SNAPSHOT_FILE)
      if (!stagedSnapshot.isFile) throw IOException("Prepared private restore snapshot is missing")
      copyFileAtomically(stagedSnapshot, snapshotFile)
    }
  }

  /** On rollback restores byte-for-byte prior vault files; on commit drops only the saved copies. */
  internal suspend fun finishRestoreVault(restoreTransactionId: String, committed: Boolean) = mutex.withLock {
    validateRestoreTransactionId(restoreTransactionId)
    withContext(ioDispatcher) {
      val stage = restoreVaultDirectory(restoreTransactionId)
      if (!stage.exists()) return@withContext
      val manifest = readRestoreVaultManifestOrNull(stage)
      if (!committed && manifest != null) {
        val (hadEnvelope, hadSnapshot) = manifest
        if (hadEnvelope) {
          restoreBackupFile(File(stage, OLD_ENVELOPE_FILE), envelopeFile)
        } else if (envelopeFile.exists() && !envelopeFile.delete()) {
          throw IOException("Cannot roll back new private key envelope")
        }
        if (hadSnapshot) {
          restoreBackupFile(File(stage, OLD_SNAPSHOT_FILE), snapshotFile)
        } else if (snapshotFile.exists() && !snapshotFile.delete()) {
          throw IOException("Cannot roll back new private snapshot")
        }
      }
      if (!deleteRestoreVaultDirectory(restoreTransactionId)) throw IOException("Cannot clean private restore staging")
    }
  }

  /** Encrypts an original attachment before it is written; decrypted previews stay memory-only. */
  suspend fun writePrivateAsset(access: PrivateAccess, originalBytes: ByteArray): String {
    require(originalBytes.isNotEmpty() && originalBytes.size <= MAX_PRIVATE_ASSET_BYTES) {
      "Private image is outside the supported size limit"
    }
    val assetId = UUID.randomUUID().toString()
    val encrypted = withContext(cryptoDispatcher) {
      access.withDataKey { key -> crypto.encrypt(key, originalBytes, assetContext(assetId)) }
    }
    val encoded = EncryptedRecordCodec.encode(encrypted)
    try {
      withContext(ioDispatcher) {
        if (!privateAssetsDirectory.exists() && !privateAssetsDirectory.mkdirs()) {
          throw IOException("Cannot create private attachment directory")
        }
        writeAtomically(assetFile(assetId), encoded)
      }
      return "$PRIVATE_ASSET_PREFIX$assetId"
    } finally {
      MemoryWipe.bytes(encoded)
    }
  }

  /** Streams verified restore bytes into an atomic private ciphertext file; plaintext is never staged. */
  internal suspend fun writePrivateAssetFromStream(
    access: PrivateAccess,
    source: InputStream,
    expectedByteCount: Long,
    expectedSha256: ByteArray,
    restoreTransactionId: String? = null,
  ): String = source.use { plaintext ->
    require(expectedByteCount in 1..MAX_PRIVATE_ASSET_BYTES.toLong()) {
      "Private image is outside the supported size limit"
    }
    require(expectedSha256.size == 32) { "Attachment digest must be SHA-256" }
    restoreTransactionId?.let(::validateRestoreTransactionId)
    mutex.withLock {
      val assetId = UUID.randomUUID().toString()
      withContext(ioDispatcher) {
        val assetDirectory = restoreTransactionId?.let(::restoreAssetDirectory) ?: privateAssetsDirectory
        if (!assetDirectory.exists() && !assetDirectory.mkdirs()) {
          throw IOException("Cannot create private attachment directory")
        }
        val target = File(assetDirectory, "$assetId.bin")
        val temporary = File(assetDirectory, ".$assetId.restore.tmp")
        val contextId = privateAssetContextId(assetId, restoreTransactionId)
        try {
          val outputFile = FileOutputStream(temporary)
          try {
            val output = DataOutputStream(outputFile)
            access.withDataKey { key ->
              crypto.encryptStream(
                dataKey = key,
                plaintext = plaintext,
                destination = output,
                expectedPlaintextBytes = expectedByteCount,
                expectedSha256 = expectedSha256,
                context = assetContext(contextId),
                writeHeader = { nonce, ciphertextBytes ->
                  EncryptedRecordCodec.writeHeader(output, nonce, ciphertextBytes)
                },
              )
            }
            output.flush()
            outputFile.fd.sync()
          } finally {
            outputFile.close()
          }
          try {
            Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE)
          } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath())
          }
          "$PRIVATE_ASSET_PREFIX${privateAssetReferenceId(assetId, restoreTransactionId)}"
        } finally {
          if (temporary.exists()) temporary.delete()
        }
      }
    }
  }

  suspend fun readPrivateAsset(access: PrivateAccess, reference: String): ByteArray {
    val location = privateAssetLocation(reference)
    val encoded = withContext(ioDispatcher) { readBounded(location.file, MAX_PRIVATE_ASSET_FILE_BYTES) }
    val encrypted = try {
      EncryptedRecordCodec.decode(encoded)
    } finally {
      MemoryWipe.bytes(encoded)
    }
    return withContext(cryptoDispatcher) {
      access.withDataKey { key -> crypto.decrypt(key, encrypted, assetContext(location.contextId)) }
    }
  }

  /** Opens a one-shot decrypted stream for a renderer; closing it wipes the plaintext buffer. */
  internal fun openPrivateAssetForExport(access: PrivateAccess, reference: String): InputStream {
    access.withDataKey { Unit }
    val location = privateAssetLocation(reference)
    val encoded = readBounded(location.file, MAX_PRIVATE_ASSET_FILE_BYTES)
    val encrypted = try {
      EncryptedRecordCodec.decode(encoded)
    } finally {
      MemoryWipe.bytes(encoded)
    }
    val plaintext = access.withDataKey { key -> crypto.decrypt(key, encrypted, assetContext(location.contextId)) }
    return object : ByteArrayInputStream(plaintext) {
      private var closed = false

      override fun close() {
        if (!closed) {
          closed = true
          MemoryWipe.bytes(buf)
          pos = count
        }
      }
    }
  }

  suspend fun hasPrivateAsset(access: PrivateAccess, reference: String): Boolean {
    val location = privateAssetLocation(reference)
    access.withDataKey { Unit }
    return withContext(ioDispatcher) {
      val file = location.file
      file.isFile && file.length() in 1..MAX_PRIVATE_ASSET_FILE_BYTES.toLong()
    }
  }

  suspend fun deletePrivateAssetIfUnreferenced(access: PrivateAccess, reference: String): Boolean = mutex.withLock {
    val location = privateAssetLocation(reference)
    val snapshot = readSnapshotUnlocked(access)
    val referenced = snapshot.notes.any { note -> note.blocks.any { it.assetPath == reference } } ||
      snapshot.drafts.any { draft -> draft.blocks.any { it.assetPath == reference } }
    if (referenced) return@withLock false
    withContext(ioDispatcher) {
      val removed = if (!location.file.exists()) true else location.file.delete()
      if (removed) location.restoreDirectory?.let { directory ->
        if (directory.isDirectory && directory.listFiles().orEmpty().isEmpty()) directory.delete()
      }
      removed
    }
  }

  /** Removes only an uncommitted, transaction-owned encrypted attachment group during rollback. */
  internal suspend fun deleteUncommittedPrivateAssetRestoreGroup(restoreTransactionId: String): Boolean {
    validateRestoreTransactionId(restoreTransactionId)
    val group = restoreAssetDirectory(restoreTransactionId)
    val assetsRoot = privateAssetsDirectory.canonicalFile.toPath()
    val groupPath = group.canonicalFile.toPath()
    require(groupPath.startsWith(assetsRoot) && groupPath != assetsRoot) { "Invalid private restore group" }
    return mutex.withLock {
      withContext(ioDispatcher) {
        if (!group.exists()) true else group.deleteRecursively()
      }
    }
  }

  /** Deletes one unused ciphertext staged by this transaction after a committed merge decision. */
  internal suspend fun deletePrivateAssetFromRestoreGroup(
    restoreTransactionId: String,
    reference: String,
  ): Boolean {
    validateRestoreTransactionId(restoreTransactionId)
    val expectedDirectory = restoreAssetDirectory(restoreTransactionId)
    val location = privateAssetLocation(reference)
    require(location.restoreDirectory?.canonicalFile == expectedDirectory.canonicalFile) {
      "Private attachment is not owned by this restore transaction"
    }
    val root = privateAssetsDirectory.canonicalFile.toPath()
    val group = expectedDirectory.canonicalFile.toPath()
    require(!Files.isSymbolicLink(privateAssetsDirectory.toPath()) && !Files.isSymbolicLink(expectedDirectory.toPath())) {
      "Private restore asset directory cannot be a symbolic link"
    }
    require(group.parent == root && group.fileName.toString() == "$RESTORE_ASSET_DIRECTORY_PREFIX$restoreTransactionId") {
      "Private restore attachment is outside its transaction directory"
    }
    return mutex.withLock {
      withContext(ioDispatcher) {
        if (!location.file.exists()) true else Files.deleteIfExists(location.file.toPath())
      }
    }
  }

  private suspend fun writeSnapshotUnlocked(access: PrivateAccess, snapshot: PrivateVaultSnapshot) {
    val plaintext = withContext(cryptoDispatcher) { PrivateVaultSnapshotCodec.encode(snapshot) }
    try {
      val encrypted = withContext(cryptoDispatcher) {
        access.withDataKey { key -> crypto.encrypt(key, plaintext, VAULT_CONTEXT) }
      }
      val encoded = EncryptedRecordCodec.encode(encrypted)
      try {
        withContext(ioDispatcher) { writeAtomically(snapshotFile, encoded) }
      } finally {
        MemoryWipe.bytes(encoded)
      }
    } finally {
      MemoryWipe.bytes(plaintext)
    }
  }

  private fun restoreVaultDirectory(restoreTransactionId: String): File {
    validateRestoreTransactionId(restoreTransactionId)
    val stage = File(privateDirectory, "$RESTORE_VAULT_DIRECTORY_PREFIX$restoreTransactionId")
    require(!Files.isSymbolicLink(privateDirectory.toPath()) && !Files.isSymbolicLink(stage.toPath())) {
      "Private restore staging cannot be a symbolic link"
    }
    val rootPath = privateDirectory.canonicalFile.toPath()
    val stagePath = stage.canonicalFile.toPath()
    require(stagePath.parent == rootPath && stagePath.fileName.toString() == "$RESTORE_VAULT_DIRECTORY_PREFIX$restoreTransactionId") {
      "Private restore staging is outside its owner directory"
    }
    return stage
  }

  private fun writeRestoreVaultManifest(stage: File, hadEnvelope: Boolean, hadSnapshot: Boolean) {
    val bytes = byteArrayOf(
      (RESTORE_VAULT_MANIFEST_MAGIC ushr 24).toByte(),
      (RESTORE_VAULT_MANIFEST_MAGIC ushr 16).toByte(),
      (RESTORE_VAULT_MANIFEST_MAGIC ushr 8).toByte(),
      RESTORE_VAULT_MANIFEST_MAGIC.toByte(),
      ((if (hadEnvelope) 1 else 0) or (if (hadSnapshot) 2 else 0)).toByte(),
    )
    try {
      writeAtomically(File(stage, RESTORE_VAULT_MANIFEST_FILE), bytes)
    } finally {
      MemoryWipe.bytes(bytes)
    }
  }

  private fun readRestoreVaultManifest(stage: File): Pair<Boolean, Boolean> =
    readRestoreVaultManifestOrNull(stage) ?: throw IOException("Private restore staging manifest is missing")

  private fun readRestoreVaultManifestOrNull(stage: File): Pair<Boolean, Boolean>? {
    if (!stage.isDirectory) return null
    val file = File(stage, RESTORE_VAULT_MANIFEST_FILE)
    if (!file.isFile) return null
    val encoded = file.readBytes()
    try {
      if (encoded.size != RESTORE_VAULT_MANIFEST_BYTES) throw IOException("Private restore staging manifest is invalid")
      val magic = ((encoded[0].toInt() and 0xff) shl 24) or
        ((encoded[1].toInt() and 0xff) shl 16) or
        ((encoded[2].toInt() and 0xff) shl 8) or
        (encoded[3].toInt() and 0xff)
      if (magic != RESTORE_VAULT_MANIFEST_MAGIC || (encoded[4].toInt() and 0xfc) != 0) {
        throw IOException("Private restore staging manifest is invalid")
      }
      val flags = encoded[4].toInt() and 0xff
      return (flags and 1 != 0) to (flags and 2 != 0)
    } finally {
      MemoryWipe.bytes(encoded)
    }
  }

  private fun copyFileDurably(source: File, destination: File) {
    Files.copy(source.toPath(), destination.toPath(), REPLACE_EXISTING)
    FileOutputStream(destination, true).use { it.fd.sync() }
  }

  private fun copyFileAtomically(source: File, destination: File) {
    val parent = destination.parentFile ?: throw IOException("Private restore target has no parent")
    if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create private restore target directory")
    val temporary = File(parent, ".${destination.name}.${UUID.randomUUID()}.restore.tmp")
    try {
      Files.copy(source.toPath(), temporary.toPath(), REPLACE_EXISTING)
      FileOutputStream(temporary, true).use { it.fd.sync() }
      try {
        Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), destination.toPath(), REPLACE_EXISTING)
      }
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  private fun restoreBackupFile(backup: File, destination: File) {
    if (!backup.isFile) throw IOException("Previous private vault file is missing during rollback")
    copyFileAtomically(backup, destination)
  }

  private fun deleteRestoreVaultDirectory(restoreTransactionId: String): Boolean {
    val stage = restoreVaultDirectory(restoreTransactionId)
    return !stage.exists() || stage.deleteRecursively()
  }

  fun lock(access: PrivateAccess) {
    access.revoke()
  }

  private fun readBounded(file: File, maximumBytes: Int): ByteArray {
    if (!file.isFile) throw PrivateAuthenticationException()
    val length = file.length()
    if (length !in 1..maximumBytes.toLong()) throw IOException("Private storage record has an invalid size")
    return file.readBytes().also { require(it.size.toLong() == length) { "Private storage changed while reading" } }
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    val parent = target.parentFile ?: throw IOException("Private storage target has no parent")
    val temp = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
    try {
      FileOutputStream(temp).use { stream ->
        stream.write(bytes)
        stream.fd.sync()
      }
      try {
        Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING)
      }
    } finally {
      if (temp.exists()) temp.delete()
    }
  }

  private fun privateAssetLocation(reference: String): PrivateAssetLocation {
    require(reference.startsWith(PRIVATE_ASSET_PREFIX)) { "Invalid private attachment reference" }
    val path = reference.removePrefix(PRIVATE_ASSET_PREFIX)
    val parts = path.split('/')
    return when (parts.size) {
      1 -> {
        validateAssetId(parts[0])
        PrivateAssetLocation(assetFile(parts[0]), parts[0], null)
      }
      2 -> {
        val group = parts[0]
        require(group.startsWith(RESTORE_ASSET_DIRECTORY_PREFIX)) { "Invalid private attachment reference" }
        val transactionId = group.removePrefix(RESTORE_ASSET_DIRECTORY_PREFIX)
        validateRestoreTransactionId(transactionId)
        validateAssetId(parts[1])
        PrivateAssetLocation(
          file = File(restoreAssetDirectory(transactionId), "${parts[1]}.bin"),
          contextId = "$group/${parts[1]}",
          restoreDirectory = restoreAssetDirectory(transactionId),
        )
      }
      else -> throw IllegalArgumentException("Invalid private attachment reference")
    }
  }

  private fun assetFile(assetId: String) = File(privateAssetsDirectory, "$assetId.bin")

  private fun assetContext(assetId: String) = PrivateRecordContext(assetId, PRIVATE_IMAGE_BLOCK_TYPE, 1)

  private fun privateAssetReferenceId(assetId: String, transactionId: String?): String =
    transactionId?.let { "$RESTORE_ASSET_DIRECTORY_PREFIX$it/$assetId" } ?: assetId

  private fun privateAssetContextId(assetId: String, transactionId: String?): String =
    privateAssetReferenceId(assetId, transactionId)

  private fun restoreAssetDirectory(transactionId: String) =
    File(privateAssetsDirectory, "$RESTORE_ASSET_DIRECTORY_PREFIX$transactionId")

  private fun validateRestoreTransactionId(transactionId: String) {
    require(runCatching { UUID.fromString(transactionId).toString() == transactionId }.getOrDefault(false)) {
      "Private restore transaction ID is invalid"
    }
  }

  private fun validateAssetId(assetId: String) {
    require(runCatching { UUID.fromString(assetId).toString() == assetId }.getOrDefault(false)) {
      "Invalid private attachment reference"
    }
  }

  private data class PrivateAssetLocation(
    val file: File,
    val contextId: String,
    val restoreDirectory: File?,
  )

  private companion object {
    const val PRIVATE_DIRECTORY = "private"
    const val PRIVATE_ASSETS_DIRECTORY = "assets"
    const val ENVELOPE_FILE = "key-envelope.bin"
    const val SNAPSHOT_FILE = "vault.snapshot"
    const val MAX_ENVELOPE_BYTES = 256
    const val MAX_SNAPSHOT_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_PRIVATE_ASSET_BYTES = 64 * 1024 * 1024 - 16
    const val MAX_PRIVATE_ASSET_FILE_BYTES = 64 * 1024 * 1024 + 64
    const val PRIVATE_ASSET_PREFIX = "private-asset:"
    const val RESTORE_ASSET_DIRECTORY_PREFIX = "restore-"
    const val RESTORE_VAULT_DIRECTORY_PREFIX = "restore-vault-"
    private const val RESTORE_VAULT_MANIFEST_FILE = "restore-state.bin"
    private const val RESTORE_VAULT_MANIFEST_MAGIC = 0x414E5256 // ANRV
    private const val RESTORE_VAULT_MANIFEST_BYTES = 5
    private const val NEW_ENVELOPE_FILE = "new-envelope.bin"
    private const val NEW_SNAPSHOT_FILE = "new-snapshot.bin"
    private const val OLD_ENVELOPE_FILE = "old-envelope.bin"
    private const val OLD_SNAPSHOT_FILE = "old-snapshot.bin"
    const val PRIVATE_IMAGE_BLOCK_TYPE = "PRIVATE_IMAGE"
    val VAULT_CONTEXT = PrivateRecordContext("vault-snapshot", "PRIVATE_VAULT", 1)
  }
}

private object EncryptedRecordCodec {
  private const val MAGIC = 0x414E5052 // ANPR
  private const val VERSION = 1
  private const val NONCE_BYTES = 12
  private const val TAG_BYTES = 16
  private const val MAX_BYTES = 64 * 1024 * 1024

  fun encode(record: EncryptedPrivateRecord): ByteArray {
    require(record.version == VERSION)
    require(record.nonce.size == NONCE_BYTES)
    require(record.ciphertext.size in TAG_BYTES..MAX_BYTES)
    return ByteArrayOutputStream().use { bytes ->
      DataOutputStream(bytes).use { out ->
        val ciphertext = record.ciphertext.copyBytes()
        val nonce = record.nonce.copyBytes()
        try {
          writeHeader(out, nonce, ciphertext.size, record.version)
          out.write(ciphertext)
        } finally {
          MemoryWipe.bytes(nonce)
          MemoryWipe.bytes(ciphertext)
        }
      }
      bytes.toByteArray()
    }
  }

  fun writeHeader(output: DataOutputStream, nonce: ByteArray, ciphertextLength: Int, version: Int = VERSION) {
    require(version == VERSION && nonce.size == NONCE_BYTES)
    require(ciphertextLength in TAG_BYTES..MAX_BYTES)
    output.writeInt(MAGIC)
    output.writeInt(version)
    output.write(nonce)
    output.writeInt(ciphertextLength)
  }

  fun decode(encoded: ByteArray): EncryptedPrivateRecord {
    require(encoded.size in 8 + NONCE_BYTES + 4 + TAG_BYTES..MAX_BYTES + 8 + NONCE_BYTES + 4)
    DataInputStream(ByteArrayInputStream(encoded)).use { input ->
      require(input.readInt() == MAGIC) { "Invalid private snapshot header" }
      val version = input.readInt()
      if (version != VERSION) throw IllegalArgumentException("Unsupported private snapshot version")
      val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
      val ciphertextLength = input.readInt()
      require(ciphertextLength in TAG_BYTES..MAX_BYTES && input.available() == ciphertextLength) {
        "Invalid private snapshot ciphertext length"
      }
      val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
      return try {
        EncryptedPrivateRecord(version, CryptoBytes.of(nonce), CryptoBytes.of(ciphertext))
      } finally {
        MemoryWipe.bytes(nonce)
        MemoryWipe.bytes(ciphertext)
      }
    }
  }
}

internal object PrivateVaultSnapshotCodec {
  private const val MAGIC = 0x414E5653 // ANVS
  private const val VERSION = 3
  private const val LEGACY_VERSION = 1
  private const val FORMATTING_VERSION = 2
  private const val MAX_BYTES = 64 * 1024 * 1024
  private const val MAX_NOTES = 50_000
  private const val MAX_BLOCKS_PER_NOTE = 100_000
  private const val MAX_DRAFTS = 5_000
  private const val MAX_TODOS = 100_000
  private const val MAX_EXPORT_HISTORY = 100
  private const val MAX_STRING_BYTES = 4 * 1024 * 1024

  fun encode(snapshot: PrivateVaultSnapshot): ByteArray {
    require(snapshot.notes.size <= MAX_NOTES && snapshot.drafts.size <= MAX_DRAFTS)
    return ByteArrayOutputStream().use { bytes ->
      DataOutputStream(bytes).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(snapshot.notes.size)
        snapshot.notes.forEach { note -> writeNote(out, note) }
        out.writeInt(snapshot.drafts.size)
        snapshot.drafts.forEach { draft -> writeDraft(out, draft) }
        require(snapshot.exportHistory.size <= MAX_EXPORT_HISTORY)
        out.writeInt(snapshot.exportHistory.size)
        snapshot.exportHistory.forEach { record ->
          writeString(out, record.id)
          writeString(out, record.noteId.value)
          writeString(out, record.noteTitle)
          writeString(out, record.format.name)
          writeString(out, record.fileName)
          writeString(out, record.destinationUri)
          out.writeLong(record.createdAtMillis)
          out.writeBoolean(record.succeeded)
          writeNullableString(out, record.errorCode)
        }
      }
      require(bytes.size() <= MAX_BYTES) { "Private snapshot exceeds supported size" }
      bytes.toByteArray()
    }
  }

  fun decode(plaintext: ByteArray): PrivateVaultSnapshot {
    require(plaintext.size in 9..MAX_BYTES) { "Invalid private snapshot size" }
    DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
      require(input.readInt() == MAGIC) { "Invalid private snapshot data" }
      val version = input.readInt().also { require(it in LEGACY_VERSION..VERSION) { "Unsupported private snapshot data version" } }
      val notesCount = input.readInt().also { require(it in 0..MAX_NOTES) }
      val notes = List(notesCount) { readNote(input, version) }
      val draftsCount = input.readInt().also { require(it in 0..MAX_DRAFTS) }
      val drafts = List(draftsCount) { readDraft(input, version) }
      val exportHistory = if (version >= VERSION) {
        val count = input.readInt().also { require(it in 0..MAX_EXPORT_HISTORY) }
        List(count) {
          PrivateExportHistoryRecord(
            id = readString(input),
            noteId = PrivateNoteId(readString(input)),
            noteTitle = readString(input),
            format = ExportFormat.valueOf(readString(input)),
            fileName = readString(input),
            destinationUri = readString(input),
            createdAtMillis = input.readLong(),
            succeeded = input.readBoolean(),
            errorCode = readNullableString(input),
          )
        }
      } else emptyList()
      require(input.available() == 0) { "Trailing data in private snapshot" }
      return PrivateVaultSnapshot(notes, drafts, exportHistory)
    }
  }

  private fun writeNote(out: DataOutputStream, note: PrivateNoteDocument) {
    writeString(out, note.id.value)
    writeString(out, note.folderId)
    writeString(out, note.title)
    out.writeBoolean(note.titleManuallySet)
    writeBlocks(out, note.blocks)
    require(note.todos.size <= MAX_TODOS)
    out.writeInt(note.todos.size)
    note.todos.forEach { todo ->
      writeString(out, todo.id)
      writeString(out, todo.blockId)
      writeString(out, todo.text)
      out.writeBoolean(todo.completed)
      out.writeLong(todo.updatedAtMillis)
    }
    writeString(out, note.skinId)
    writeNullableFloat(out, note.fontSizeSpOverride)
    writeNullableFloat(out, note.lineSpacingMultiplierOverride)
    out.writeLong(note.createdAtMillis)
    out.writeLong(note.updatedAtMillis)
    out.writeBoolean(note.isPinned)
    writeNullableLong(out, note.deletedAtMillis)
    out.writeLong(note.contentRevision)
  }

  private fun readNote(input: DataInputStream, version: Int): PrivateNoteDocument {
    val id = PrivateNoteId(readString(input))
    val folderId = readString(input)
    val title = readString(input)
    val titleManuallySet = input.readBoolean()
    val blocks = readBlocks(input, version)
    val todosCount = input.readInt().also { require(it in 0..MAX_TODOS) }
    val todos = List(todosCount) {
      PrivateTodo(readString(input), readString(input), readString(input), input.readBoolean(), input.readLong())
    }
    return PrivateNoteDocument(
      id = id,
      folderId = folderId,
      title = title,
      titleManuallySet = titleManuallySet,
      blocks = blocks,
      todos = todos,
      skinId = readString(input),
      fontSizeSpOverride = readNullableFloat(input),
      lineSpacingMultiplierOverride = readNullableFloat(input),
      createdAtMillis = input.readLong(),
      updatedAtMillis = input.readLong(),
      isPinned = input.readBoolean(),
      deletedAtMillis = readNullableLong(input),
      contentRevision = input.readLong(),
    )
  }

  private fun writeDraft(out: DataOutputStream, draft: PrivateEditSnapshot) {
    writeString(out, draft.draftId)
    writeNullableString(out, draft.noteId?.value)
    writeString(out, draft.folderId)
    writeString(out, draft.title)
    out.writeBoolean(draft.titleManuallySet)
    writeBlocks(out, draft.blocks)
    writeString(out, draft.skinId)
    writeNullableFloat(out, draft.fontSizeSpOverride)
    writeNullableFloat(out, draft.lineSpacingMultiplierOverride)
    writeNullableLong(out, draft.baseContentRevision)
    out.writeLong(draft.localRevision)
    out.writeLong(draft.savedAtMillis)
  }

  private fun readDraft(input: DataInputStream, version: Int) = PrivateEditSnapshot(
    draftId = readString(input),
    noteId = readNullableString(input)?.let(::PrivateNoteId),
    folderId = readString(input),
    title = readString(input),
    titleManuallySet = input.readBoolean(),
    blocks = readBlocks(input, version),
    skinId = readString(input),
    fontSizeSpOverride = readNullableFloat(input),
    lineSpacingMultiplierOverride = readNullableFloat(input),
    baseContentRevision = readNullableLong(input),
    localRevision = input.readLong(),
    savedAtMillis = input.readLong(),
  )

  private fun writeBlocks(out: DataOutputStream, blocks: List<NoteBlockDraft>) {
    require(blocks.size <= MAX_BLOCKS_PER_NOTE)
    out.writeInt(blocks.size)
    blocks.forEach { block ->
      writeString(out, block.id)
      out.writeInt(block.type.ordinal)
      writeNullableString(out, block.text)
      writeNullableString(out, block.assetPath)
      writeNullableString(out, block.previewPath)
      out.writeFloat(block.widthPercent)
      out.writeInt(block.alignment.ordinal)
      writeNullableFloat(out, block.aspectRatio)
      out.writeInt(block.ocrStatus?.ordinal ?: -1)
      val formattingBlob = when (val formatting = block.formatting) {
        is BlockFormattingState.Supported -> {
          if (formatting.value == BlockFormatting()) null
          else BlockFormattingCodec.encode(block.text.orEmpty(), block.type, formatting.value)
        }
        is BlockFormattingState.Unsupported -> formatting.originalBlob
      }
      writeNullableBytes(out, formattingBlob, BlockFormattingCodec.MAX_BLOB_BYTES)
    }
  }

  private fun readBlocks(input: DataInputStream, snapshotVersion: Int): List<NoteBlockDraft> {
    val count = input.readInt().also { require(it in 0..MAX_BLOCKS_PER_NOTE) }
    return List(count) {
      val id = readString(input)
      val type = enumValue<BlockType>(input.readInt())
      val text = readNullableString(input)
      val assetPath = readNullableString(input)
      val previewPath = readNullableString(input)
      val widthPercent = input.readFloat().also { require(it.isFinite() && it in 0f..100f) }
      val alignment = enumValue<BlockAlignment>(input.readInt())
      val aspectRatio = readNullableFloat(input)
      val ocrOrdinal = input.readInt()
      val ocrStatus = if (ocrOrdinal == -1) null else enumValue<OcrStatus>(ocrOrdinal)
      val formatting = if (snapshotVersion < FORMATTING_VERSION) {
        BlockFormattingState.Supported(BlockFormatting())
      } else {
        readNullableBytes(input, BlockFormattingCodec.MAX_BLOB_BYTES)?.let { blob ->
          try {
            BlockFormattingCodec.decode(blob, text.orEmpty(), type)
          } finally {
            MemoryWipe.bytes(blob)
          }
        } ?: BlockFormattingState.Supported(BlockFormatting())
      }
      NoteBlockDraft(id, type, text, assetPath, previewPath, widthPercent, alignment, aspectRatio, ocrStatus, formatting)
    }
  }

  private inline fun <reified T : Enum<T>> enumValue(index: Int): T =
    enumValues<T>().getOrNull(index) ?: throw IllegalArgumentException("Invalid private snapshot enum value")

  private fun writeString(out: DataOutputStream, value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    require(encoded.size <= MAX_STRING_BYTES)
    out.writeInt(encoded.size)
    out.write(encoded)
  }

  private fun readString(input: DataInputStream): String {
    val size = input.readInt().also { require(it in 0..MAX_STRING_BYTES && it <= input.available()) }
    val bytes = ByteArray(size).also(input::readFully)
    return try {
      bytes.toString(Charsets.UTF_8)
    } finally {
      MemoryWipe.bytes(bytes)
    }
  }

  private fun writeNullableString(out: DataOutputStream, value: String?) {
    out.writeBoolean(value != null)
    value?.let { writeString(out, it) }
  }

  private fun readNullableString(input: DataInputStream): String? = if (input.readBoolean()) readString(input) else null

  private fun writeNullableFloat(out: DataOutputStream, value: Float?) {
    out.writeBoolean(value != null)
    value?.let(out::writeFloat)
  }

  private fun readNullableFloat(input: DataInputStream): Float? =
    if (input.readBoolean()) input.readFloat().also { require(it.isFinite()) } else null

  private fun writeNullableLong(out: DataOutputStream, value: Long?) {
    out.writeBoolean(value != null)
    value?.let(out::writeLong)
  }

  private fun readNullableLong(input: DataInputStream): Long? = if (input.readBoolean()) input.readLong() else null

  private fun writeNullableBytes(out: DataOutputStream, value: ByteArray?, maximumBytes: Int) {
    if (value == null) {
      out.writeInt(-1)
      return
    }
    require(value.size <= maximumBytes) { "Private formatting blob exceeds supported size" }
    out.writeInt(value.size)
    out.write(value)
  }

  private fun readNullableBytes(input: DataInputStream, maximumBytes: Int): ByteArray? {
    val size = input.readInt()
    if (size == -1) return null
    require(size in 0..maximumBytes && size <= input.available()) { "Invalid private formatting blob size" }
    return ByteArray(size).also(input::readFully)
  }
}

private const val PRIVATE_UNCATEGORIZED_FOLDER_ID = "private-uncategorized"

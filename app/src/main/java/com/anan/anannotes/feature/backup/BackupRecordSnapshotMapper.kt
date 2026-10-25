package com.anan.anannotes.feature.backup

import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingCodec
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.data.local.BlockEntity
import com.anan.anannotes.data.local.FolderEntity
import com.anan.anannotes.data.local.NoteEntity
import com.anan.anannotes.data.local.TodoEntity
import com.anan.anannotes.feature.editor.EditorDraftSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateEditSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateNoteDocument
import com.anan.anannotes.feature.privatemodule.PrivateVaultSnapshot
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordPayloadException
import com.anan.anannotes.security.BackupRecordPayloadWriter
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.BackupRecordStreamCodec
import java.io.InputStream
import java.security.MessageDigest

internal data class BackupRecordEnvelope(
  val domain: BackupRecordDomain,
  val payload: BackupRecordPayload,
)

internal class BackupAssetExportSource(
  val domain: BackupRecordDomain,
  val backupAssetId: String,
  val mimeType: String?,
  val sourceFactory: () -> InputStream,
)

/** One immutable metadata snapshot; original assets are reopened and streamed only when written. */
internal class BackupRecordSnapshot(
  val records: List<BackupRecordEnvelope>,
  private val assets: List<BackupAssetExportSource>,
) : AutoCloseable {
  fun writeTo(writer: BackupRecordPayloadWriter) {
    check(!closed) { "Backup snapshot is closed" }
    records.forEach { writer.write(it.domain, it.payload) }
    assets.forEach { asset ->
      if (asset.mimeType != null) {
        writer.writeAsset(asset.domain, asset.backupAssetId, asset.mimeType, asset.sourceFactory)
      } else {
        writer.writeAssetDetectedMimeType(
          domain = asset.domain,
          assetId = asset.backupAssetId,
          source = asset.sourceFactory,
          resolveMimeType = BackupImageMimeSniffer::detect,
        )
      }
    }
  }

  /** Computes current-target conflict digests by hashing canonical records and reopening each original once. */
  fun fingerprints(): Map<BackupObjectKey, com.anan.anannotes.security.CryptoBytes> {
    check(!closed) { "Backup snapshot is closed" }
    val collector = BackupObjectFingerprintCollector()
    try {
      records.forEach { collector.accept(it.domain, it.payload) }
      assets.forEach { asset ->
        val maxBytes = if (asset.domain == BackupRecordDomain.PRIVATE) {
          BackupRecordStreamCodec.MAX_PRIVATE_ASSET_BYTES
        } else BackupRecordStreamCodec.MAX_ORDINARY_ASSET_BYTES
        val buffer = ByteArray(ASSET_HASH_BUFFER_BYTES)
        val signature = ByteArray(MIME_SIGNATURE_BYTES)
        val digest = MessageDigest.getInstance(SHA_256)
        var copied = 0L
        var signatureLength = 0
        try {
          asset.sourceFactory().use { input ->
            while (true) {
              val count = input.read(buffer)
              if (count < 0) break
              if (count == 0) continue
              if (count.toLong() > maxBytes - copied) {
                throw BackupRecordPayloadException("Backup attachment exceeds its size limit")
              }
              digest.update(buffer, 0, count)
              val toCopy = minOf(count, signature.size - signatureLength)
              if (toCopy > 0) {
                buffer.copyInto(signature, signatureLength, 0, toCopy)
                signatureLength += toCopy
              }
              copied += count.toLong()
            }
          }
          if (copied == 0L) throw BackupRecordPayloadException("Backup attachment is empty")
          val exactSignature = signature.copyOf(signatureLength)
          val sha256 = digest.digest()
          try {
            val mimeType = asset.mimeType ?: BackupImageMimeSniffer.detect(exactSignature)
            collector.accept(
              asset.domain,
              BackupRecordPayload.Asset(asset.backupAssetId, mimeType, copied, sha256),
            )
          } finally {
            MemoryWipe.bytes(exactSignature)
            MemoryWipe.bytes(sha256)
          }
        } finally {
          MemoryWipe.bytes(buffer)
          MemoryWipe.bytes(signature)
        }
      }
      return collector.finish()
    } catch (failure: Throwable) {
      collector.close()
      throw failure
    }
  }

  private var closed = false

  private companion object {
    const val ASSET_HASH_BUFFER_BYTES = 64 * 1024
    const val MIME_SIGNATURE_BYTES = 16
    const val SHA_256 = "SHA-256"
  }

  override fun close() {
    if (closed) return
    closed = true
    records.forEach { record ->
      when (val payload = record.payload) {
        is BackupRecordPayload.Block -> MemoryWipe.bytes(payload.formattingBlob)
        is BackupRecordPayload.DraftBlock -> MemoryWipe.bytes(payload.formattingBlob)
        else -> Unit
      }
    }
  }
}

/** Maps persisted ordinary and private records into the wire schema without including previews or histories. */
internal object BackupRecordSnapshotMapper {
  private const val PRIVATE_ASSET_PREFIX = "private-asset:"
  private const val ORDINARY_ASSET_PREFIX = "notes/assets/"

  fun map(
    folders: List<FolderEntity>,
    notes: List<NoteEntity>,
    blocks: List<BlockEntity>,
    todos: List<TodoEntity>,
    ordinaryDrafts: List<EditorDraftSnapshot>,
    privateSnapshot: PrivateVaultSnapshot,
    openOrdinaryAsset: (String) -> InputStream,
    openPrivateAsset: (PrivateAccess, String) -> InputStream,
    privateAccess: PrivateAccess? = null,
  ): BackupRecordSnapshot {
    val records = ArrayList<BackupRecordEnvelope>()
    val assetBindings = linkedMapOf<Pair<BackupRecordDomain, String>, BackupAssetExportSource>()
    val assetIds = hashMapOf<Pair<BackupRecordDomain, String>, String>()

    fun assetRef(domain: BackupRecordDomain, localReference: String?): String? {
      if (localReference == null) return null
      val backupId = when (domain) {
        BackupRecordDomain.ORDINARY -> {
          require(localReference.startsWith(ORDINARY_ASSET_PREFIX)) { "Ordinary attachment reference is outside the originals directory" }
          localReference.substringAfterLast('/').also(::requireSafeAssetId)
        }
        BackupRecordDomain.PRIVATE -> {
          require(localReference.startsWith(PRIVATE_ASSET_PREFIX)) { "Private attachment reference is invalid" }
          localReference.removePrefix(PRIVATE_ASSET_PREFIX).also(::requireSafeAssetId)
        }
      }
      val identity = domain to backupId
      val previousReference = assetIds[identity]
      require(previousReference == null || previousReference == localReference) { "Attachment IDs collide in backup data" }
      assetIds[identity] = localReference
      if (identity !in assetBindings) {
        val source = when (domain) {
          BackupRecordDomain.ORDINARY -> BackupAssetExportSource(
            domain = domain,
            backupAssetId = backupId,
            mimeType = ordinaryMimeType(localReference),
            sourceFactory = { openOrdinaryAsset(localReference) },
          )
          BackupRecordDomain.PRIVATE -> {
            val access = checkNotNull(privateAccess) { "Private backup requires an unlocked private module" }
            BackupAssetExportSource(
              domain = domain,
              backupAssetId = backupId,
              mimeType = null,
              sourceFactory = { openPrivateAsset(access, localReference) },
            )
          }
        }
        assetBindings[identity] = source
      }
      return backupId
    }

    folders.sortedWith(compareBy<FolderEntity> { it.sortPosition }.thenBy { it.id }).forEach { folder ->
      records += BackupRecordEnvelope(
        BackupRecordDomain.ORDINARY,
        BackupRecordPayload.Folder(
          id = folder.id,
          name = folder.name,
          kind = FolderKind.valueOf(folder.kind),
          createdAtMillis = folder.createdAtMillis,
          sortPosition = folder.sortPosition,
        ),
      )
    }
    notes.sortedBy(NoteEntity::id).forEach { note -> records += BackupRecordEnvelope(BackupRecordDomain.ORDINARY, note.toBackupPayload()) }
    blocks.sortedWith(compareBy<BlockEntity> { it.noteId }.thenBy { it.position }.thenBy { it.id }).forEach { block ->
      records += BackupRecordEnvelope(
        BackupRecordDomain.ORDINARY,
        block.toBackupPayload(BackupRecordDomain.ORDINARY, ::assetRef),
      )
    }
    todos.sortedWith(compareBy<TodoEntity> { it.noteId }.thenBy { it.id }).forEach { todo ->
      records += BackupRecordEnvelope(BackupRecordDomain.ORDINARY, todo.toBackupPayload())
    }
    ordinaryDrafts.sortedBy(EditorDraftSnapshot::draftId).forEach { draft ->
      records += BackupRecordEnvelope(BackupRecordDomain.ORDINARY, draft.toBackupPayload())
      draft.draft.blocks.forEachIndexed { position, block ->
        records += BackupRecordEnvelope(
          BackupRecordDomain.ORDINARY,
          block.toBackupDraftBlock(draft.draftId, position, BackupRecordDomain.ORDINARY, ::assetRef),
        )
      }
    }

    privateSnapshot.notes.sortedBy { it.id.value }.forEach { note ->
      records += BackupRecordEnvelope(BackupRecordDomain.PRIVATE, note.toBackupPayload())
      note.blocks.forEachIndexed { position, block ->
        records += BackupRecordEnvelope(
          BackupRecordDomain.PRIVATE,
          block.toBackupBlock(note.id.value, position, BackupRecordDomain.PRIVATE, ::assetRef),
        )
      }
      note.todos.sortedBy { it.id }.forEach { todo ->
        records += BackupRecordEnvelope(
          BackupRecordDomain.PRIVATE,
          BackupRecordPayload.Todo(
            id = todo.id,
            noteId = note.id.value,
            blockId = todo.blockId,
            text = todo.text,
            isCompleted = todo.completed,
            updatedAtMillis = todo.updatedAtMillis,
          ),
        )
      }
    }
    privateSnapshot.drafts.sortedBy(PrivateEditSnapshot::draftId).forEach { draft ->
      records += BackupRecordEnvelope(BackupRecordDomain.PRIVATE, draft.toBackupPayload())
      draft.blocks.forEachIndexed { position, block ->
        records += BackupRecordEnvelope(
          BackupRecordDomain.PRIVATE,
          block.toBackupDraftBlock(draft.draftId, position, BackupRecordDomain.PRIVATE, ::assetRef),
        )
      }
    }

    return BackupRecordSnapshot(
      records = records,
      assets = assetBindings.values.sortedWith(compareBy<BackupAssetExportSource> { it.domain.wireSortOrder() }.thenBy { it.backupAssetId }),
    )
  }

  private fun NoteEntity.toBackupPayload() = BackupRecordPayload.Note(
    id = id,
    folderId = folderId,
    title = title,
    titleManuallySet = titleManuallySet,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    skinId = skinId,
    fontSizeSpOverride = fontSizeSpOverride,
    lineSpacingMultiplierOverride = lineSpacingMultiplierOverride,
    isPinned = isPinned,
    deletedAtMillis = deletedAtMillis,
    contentRevision = contentRevision,
    importRequestId = importRequestId,
  )

  private fun BlockEntity.toBackupPayload(
    domain: BackupRecordDomain,
    resolveAssetRef: (BackupRecordDomain, String?) -> String?,
  ) = BackupRecordPayload.Block(
    id = id,
    noteId = noteId,
    position = position,
    type = BlockType.valueOf(type),
    text = text,
    assetRef = resolveAssetRef(domain, assetPath),
    widthPercent = widthPercent,
    alignment = com.anan.anannotes.core.model.BlockAlignment.valueOf(alignment),
    aspectRatio = aspectRatio,
    ocrStatus = ocrStatus?.let(com.anan.anannotes.core.model.OcrStatus::valueOf),
    formattingBlob = formattingBlob?.clone(),
  )

  private fun TodoEntity.toBackupPayload() = BackupRecordPayload.Todo(
    id = id,
    noteId = noteId,
    blockId = blockId,
    text = text,
    isCompleted = isCompleted,
    updatedAtMillis = updatedAtMillis,
  )

  private fun EditorDraftSnapshot.toBackupPayload(): BackupRecordPayload.Draft {
    val draft = this.draft
    require(folderId == draft.folderId) { "Editor draft folder fields disagree" }
    return BackupRecordPayload.Draft(
      draftId = draftId,
      noteId = noteId,
      folderId = draft.folderId,
      baseContentRevision = baseContentRevision,
      localRevision = localRevision,
      savedAtMillis = savedAtMillis,
      importRequestId = importRequestId,
      title = draft.title,
      titleManuallySet = draft.titleManuallySet,
      skinId = draft.skinId,
      fontSizeSpOverride = draft.fontSizeSpOverride,
      lineSpacingMultiplierOverride = draft.lineSpacingMultiplierOverride,
      blockCount = draft.blocks.size,
    )
  }

  private fun PrivateNoteDocument.toBackupPayload() = BackupRecordPayload.Note(
    id = id.value,
    folderId = folderId,
    title = title,
    titleManuallySet = titleManuallySet,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    skinId = skinId,
    fontSizeSpOverride = fontSizeSpOverride,
    lineSpacingMultiplierOverride = lineSpacingMultiplierOverride,
    isPinned = isPinned,
    deletedAtMillis = deletedAtMillis,
    contentRevision = contentRevision,
    importRequestId = null,
  )

  private fun PrivateEditSnapshot.toBackupPayload() = BackupRecordPayload.Draft(
    draftId = draftId,
    noteId = noteId?.value,
    folderId = folderId,
    baseContentRevision = baseContentRevision,
    localRevision = localRevision,
    savedAtMillis = savedAtMillis,
    importRequestId = null,
    title = title,
    titleManuallySet = titleManuallySet,
    skinId = skinId,
    fontSizeSpOverride = fontSizeSpOverride,
    lineSpacingMultiplierOverride = lineSpacingMultiplierOverride,
    blockCount = blocks.size,
  )

  private fun NoteBlockDraft.toBackupBlock(
    noteId: String,
    position: Int,
    domain: BackupRecordDomain,
    resolveAssetRef: (BackupRecordDomain, String?) -> String?,
  ): BackupRecordPayload.Block = BackupRecordPayload.Block(
    id = id,
    noteId = noteId,
    position = position,
    type = type,
    text = text,
    assetRef = resolveAssetRef(domain, assetPath),
    widthPercent = widthPercent,
    alignment = alignment,
    aspectRatio = aspectRatio,
    ocrStatus = ocrStatus,
    formattingBlob = formattingBlobForBackup(),
  )

  private fun NoteBlockDraft.toBackupDraftBlock(
    draftId: String,
    position: Int,
    domain: BackupRecordDomain,
    resolveAssetRef: (BackupRecordDomain, String?) -> String?,
  ): BackupRecordPayload.DraftBlock = BackupRecordPayload.DraftBlock(
      draftId = draftId,
      position = position,
      blockId = id,
      type = type,
      text = text,
      assetRef = resolveAssetRef(domain, assetPath),
      widthPercent = widthPercent,
      alignment = alignment,
      aspectRatio = aspectRatio,
      ocrStatus = ocrStatus,
      formattingBlob = formattingBlobForBackup(),
    )

  private fun NoteBlockDraft.formattingBlobForBackup(): ByteArray? = when (val state = formatting) {
    is BlockFormattingState.Supported -> {
      if (state.value == BlockFormatting()) null else BlockFormattingCodec.encode(text.orEmpty(), type, state.value)
    }
    is BlockFormattingState.Unsupported -> state.originalBlob
  }

  private fun BackupRecordDomain.wireSortOrder() = when (this) {
    BackupRecordDomain.ORDINARY -> 0
    BackupRecordDomain.PRIVATE -> 1
  }

  private fun requireSafeAssetId(value: String) {
    require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() || it == '/' || it == '\\' }) {
      "Attachment ID is invalid"
    }
  }

  private fun ordinaryMimeType(reference: String): String = when (reference.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "gif" -> "image/gif"
    else -> throw BackupRecordPayloadException("Ordinary attachment type is not recognized")
  }
}

/** Sniffs only a tiny signature prefix; private block records do not persist MIME metadata. */
private object BackupImageMimeSniffer {
  fun detect(signature: ByteArray): String = when {
    signature.size >= 3 && signature[0] == 0xff.toByte() && signature[1] == 0xd8.toByte() && signature[2] == 0xff.toByte() -> "image/jpeg"
    signature.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
    signature.size >= 6 && (signature.asciiAt(0, "GIF87a") || signature.asciiAt(0, "GIF89a")) -> "image/gif"
    signature.size >= 12 && signature.asciiAt(0, "RIFF") && signature.asciiAt(8, "WEBP") -> "image/webp"
    signature.size >= 12 && signature.asciiAt(4, "ftyp") && signature.asciiAt(8, "heic", "heix", "hevc", "hevx") -> "image/heic"
    signature.size >= 12 && signature.asciiAt(4, "ftyp") && signature.asciiAt(8, "heif", "mif1", "msf1") -> "image/heif"
    else -> throw BackupRecordPayloadException("Private attachment image type cannot be detected")
  }

  private fun ByteArray.asciiAt(start: Int, vararg values: String): Boolean =
    values.any { value ->
      value.length <= size - start && value.indices.all { this[start + it] == value[it].code.toByte() }
    }

  private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

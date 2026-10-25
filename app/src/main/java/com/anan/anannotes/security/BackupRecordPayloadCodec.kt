package com.anan.anannotes.security

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.OcrStatus
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface BackupRecordPayload {
  data class Folder(
    val id: String,
    val name: String,
    val kind: FolderKind,
    val createdAtMillis: Long,
    val sortPosition: Int,
  ) : BackupRecordPayload

  data class Note(
    val id: String,
    val folderId: String,
    val title: String,
    val titleManuallySet: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val skinId: String,
    val fontSizeSpOverride: Float?,
    val lineSpacingMultiplierOverride: Float?,
    val isPinned: Boolean,
    val deletedAtMillis: Long?,
    val contentRevision: Long,
    val importRequestId: String?,
  ) : BackupRecordPayload

  data class Block(
    val id: String,
    val noteId: String,
    val position: Int,
    val type: BlockType,
    val text: String?,
    val assetRef: String?,
    val widthPercent: Float,
    val alignment: BlockAlignment,
    val aspectRatio: Float?,
    val ocrStatus: OcrStatus?,
    val formattingBlob: ByteArray?,
  ) : BackupRecordPayload

  data class Todo(
    val id: String,
    val noteId: String,
    val blockId: String,
    val text: String,
    val isCompleted: Boolean,
    val updatedAtMillis: Long,
  ) : BackupRecordPayload

  data class Draft(
    val draftId: String,
    val noteId: String?,
    val folderId: String,
    val baseContentRevision: Long?,
    val localRevision: Long,
    val savedAtMillis: Long,
    val importRequestId: String?,
    val title: String,
    val titleManuallySet: Boolean,
    val skinId: String,
    val fontSizeSpOverride: Float?,
    val lineSpacingMultiplierOverride: Float?,
    val blockCount: Int,
  ) : BackupRecordPayload

  data class DraftBlock(
    val draftId: String,
    val position: Int,
    val blockId: String,
    val type: BlockType,
    val text: String?,
    val assetRef: String?,
    val widthPercent: Float,
    val alignment: BlockAlignment,
    val aspectRatio: Float?,
    val ocrStatus: OcrStatus?,
    val formattingBlob: ByteArray?,
  ) : BackupRecordPayload

  data class Asset(
    val assetId: String,
    val mimeType: String,
    val byteCount: Long,
    val sha256: ByteArray,
  ) : BackupRecordPayload
}

fun interface BackupAssetByteSink {
  /** The provided buffer is borrowed and must be consumed synchronously. */
  fun write(asset: BackupRecordPayload.Asset, bytes: ByteArray, offset: Int, length: Int)
}

class BackupRecordPayloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Fixed-field schema v1 layered inside the ANRD frame; attachment bytes stay streamed. */
object BackupRecordPayloadCodec {
  const val SCHEMA_VERSION = 1
  const val SHA256_BYTES = 32
  const val MAX_IDENTIFIER_UTF8_BYTES = 128
  const val MAX_FOLDER_NAME_UTF8_BYTES = 4 * 1024
  const val MAX_TITLE_UTF8_BYTES = 1024 * 1024
  const val MAX_FORMATTING_BYTES = 4 * 1024 * 1024
  const val MAX_BLOCKS_PER_PARENT = 100_000
  const val MAX_MIME_UTF8_BYTES = 64

  private const val MAX_ORDINARY_TEXT_BYTES = 7 * 1024 * 1024
  private const val MAX_PRIVATE_TEXT_BYTES = 4 * 1024 * 1024
  private const val ASSET_READ_BUFFER_BYTES = 64 * 1024
  private val allowedImageMimeTypes = setOf(
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "image/heic",
    "image/heif",
    "image/gif",
  )

  fun encode(domain: BackupRecordDomain, value: BackupRecordPayload): ByteArray {
    if (value is BackupRecordPayload.Asset) {
      throw BackupRecordPayloadException("Asset bodies must be encoded with encodeAssetPrefix and a streaming source")
    }
    val bytes = WipeableByteArrayOutputStream()
    try {
      DataOutputStream(bytes).use { output ->
        when (value) {
          is BackupRecordPayload.Folder -> writeFolder(output, value)
          is BackupRecordPayload.Note -> writeNote(output, domain, value)
          is BackupRecordPayload.Block -> writeBlock(output, domain, value)
          is BackupRecordPayload.Todo -> writeTodo(output, domain, value)
          is BackupRecordPayload.Draft -> writeDraft(output, domain, value)
          is BackupRecordPayload.DraftBlock -> writeDraftBlock(output, domain, value)
          is BackupRecordPayload.Asset -> error("handled above")
        }
      }
      val encoded = bytes.toByteArray()
      val kind = value.kind()
      val maximum = BackupRecordStreamCodec.maxPayloadBytes(domain, kind)
      if (encoded.size.toLong() > maximum) {
        MemoryWipe.bytes(encoded)
        throw BackupRecordPayloadException("Backup record payload exceeds its type limit")
      }
      return encoded
    } catch (failure: BackupRecordPayloadException) {
      throw failure
    } catch (failure: Exception) {
      throw BackupRecordPayloadException("Backup record fields are invalid", failure)
    } finally {
      bytes.wipe()
    }
  }

  fun encodeAssetPrefix(domain: BackupRecordDomain, asset: BackupRecordPayload.Asset): ByteArray {
    validateIdentifier(asset.assetId, "asset ID")
    validateMimeType(asset.mimeType)
    validateAssetLength(domain, asset.byteCount)
    if (asset.sha256.size != SHA256_BYTES) throw BackupRecordPayloadException("Asset digest must be SHA-256")
    val output = WipeableByteArrayOutputStream()
    try {
      DataOutputStream(output).use { data ->
        data.writeString(asset.assetId, MAX_IDENTIFIER_UTF8_BYTES, "asset ID")
        data.writeString(asset.mimeType, MAX_MIME_UTF8_BYTES, "asset MIME type")
        data.writeLong(asset.byteCount)
        data.write(asset.sha256)
      }
      return output.toByteArray()
    } finally {
      output.wipe()
    }
  }

  fun assetPayloadBytes(domain: BackupRecordDomain, prefixBytes: Long, asset: BackupRecordPayload.Asset): Long {
    validateAssetLength(domain, asset.byteCount)
    if (prefixBytes < 0L) throw BackupRecordPayloadException("Asset header length is invalid")
    return try {
      Math.addExact(prefixBytes, asset.byteCount)
    } catch (failure: ArithmeticException) {
      throw BackupRecordPayloadException("Asset record length overflow", failure)
    }
  }

  /** Decodes exactly one record body. For ASSET, only a bounded buffer is held at a time. */
  fun decode(
    domain: BackupRecordDomain,
    kind: BackupRecordKind,
    schemaVersion: Int,
    source: InputStream,
    payloadBytes: Long,
    onAssetBytes: BackupAssetByteSink? = null,
  ): BackupRecordPayload {
    if (schemaVersion != SCHEMA_VERSION) throw BackupRecordPayloadException("Unsupported backup record schema version")
    val maximum = BackupRecordStreamCodec.maxPayloadBytes(domain, kind)
    if (payloadBytes < 0L || payloadBytes > maximum) throw BackupRecordPayloadException("Backup record payload length exceeds its type limit")
    val limited = PayloadLimitedInputStream(source, payloadBytes)
    val input = PayloadDataInputStream(limited)
    try {
      val result = when (kind) {
        BackupRecordKind.FOLDER -> readFolder(input)
        BackupRecordKind.NOTE -> readNote(input, domain)
        BackupRecordKind.BLOCK -> readBlock(input, domain)
        BackupRecordKind.TODO -> readTodo(input, domain)
        BackupRecordKind.DRAFT -> readDraft(input, domain)
        BackupRecordKind.DRAFT_BLOCK -> readDraftBlock(input, domain)
        BackupRecordKind.ASSET -> readAsset(input, domain, payloadBytes, onAssetBytes)
      }
      if (limited.remaining != 0L || input.read() != -1) {
        throw BackupRecordPayloadException("Backup record contains trailing or unconsumed fields")
      }
      return result
    } catch (failure: BackupRecordPayloadException) {
      throw failure
    } catch (failure: EOFException) {
      throw BackupRecordPayloadException("Backup record is truncated", failure)
    } catch (failure: CharacterCodingException) {
      throw BackupRecordPayloadException("Backup record contains malformed UTF-8", failure)
    } catch (failure: Exception) {
      throw BackupRecordPayloadException("Backup record fields are invalid", failure)
    }
  }

  private fun writeFolder(out: DataOutputStream, value: BackupRecordPayload.Folder) {
    out.writeId(value.id, "folder ID")
    out.writeString(value.name, MAX_FOLDER_NAME_UTF8_BYTES, "folder name", allowEmpty = false)
    out.writeByte(folderKindId(value.kind))
    out.writeNonNegativeLong(value.createdAtMillis, "folder creation time")
    out.writeInt(value.sortPosition)
  }

  private fun readFolder(input: DataInputStream): BackupRecordPayload.Folder {
    val id = input.readId("folder ID")
    val name = input.readString(MAX_FOLDER_NAME_UTF8_BYTES, "folder name", allowEmpty = false)
    val kind = folderKindFromId(input.readUnsignedByte())
    val createdAt = input.readNonNegativeLong("folder creation time")
    return BackupRecordPayload.Folder(id, name, kind, createdAt, input.readInt())
  }

  private fun writeNote(out: DataOutputStream, domain: BackupRecordDomain, value: BackupRecordPayload.Note) {
    out.writeId(value.id, "note ID")
    out.writeId(value.folderId, "folder ID")
    out.writeString(value.title, MAX_TITLE_UTF8_BYTES, "note title")
    out.writeBooleanByte(value.titleManuallySet)
    out.writeNonNegativeLong(value.createdAtMillis, "note creation time")
    out.writeNonNegativeLong(value.updatedAtMillis, "note update time")
    out.writeId(value.skinId, "skin ID")
    out.writeNullableFloat(value.fontSizeSpOverride, "font size", 14f..32f)
    out.writeNullableFloat(value.lineSpacingMultiplierOverride, "line spacing", 1f..2f)
    out.writeBooleanByte(value.isPinned)
    out.writeNullableLong(value.deletedAtMillis, "deletion time")
    if (value.contentRevision < 1L) throw BackupRecordPayloadException("Note content revision is invalid")
    out.writeLong(value.contentRevision)
    out.writeNullableId(value.importRequestId, "import request ID")
    if (domain == BackupRecordDomain.PRIVATE && value.importRequestId != null) {
      throw BackupRecordPayloadException("Private notes cannot carry ordinary import request IDs")
    }
  }

  private fun readNote(input: DataInputStream, domain: BackupRecordDomain): BackupRecordPayload.Note {
    val id = input.readId("note ID")
    val folderId = input.readId("folder ID")
    val title = input.readString(MAX_TITLE_UTF8_BYTES, "note title")
    val manuallySet = input.readBooleanByte("title flag")
    val createdAt = input.readNonNegativeLong("note creation time")
    val updatedAt = input.readNonNegativeLong("note update time")
    val skinId = input.readId("skin ID")
    val fontSize = input.readNullableFloat("font size", 14f..32f)
    val lineSpacing = input.readNullableFloat("line spacing", 1f..2f)
    val pinned = input.readBooleanByte("pinned flag")
    val deletedAt = input.readNullableLong("deletion time")
    val revision = input.readLong().also {
      if (it < 1L) throw BackupRecordPayloadException("Note content revision is invalid")
    }
    val requestId = input.readNullableId("import request ID")
    if (domain == BackupRecordDomain.PRIVATE && requestId != null) {
      throw BackupRecordPayloadException("Private notes cannot carry ordinary import request IDs")
    }
    return BackupRecordPayload.Note(
      id, folderId, title, manuallySet, createdAt, updatedAt, skinId, fontSize,
      lineSpacing, pinned, deletedAt, revision, requestId,
    )
  }

  private fun writeBlock(out: DataOutputStream, domain: BackupRecordDomain, value: BackupRecordPayload.Block) {
    out.writeId(value.id, "block ID")
    out.writeId(value.noteId, "note ID")
    out.writeNonNegativeInt(value.position, "block position")
    out.writeByte(blockTypeId(value.type))
    out.writeNullableString(value.text, maxTextBytes(domain), "block text")
    out.writeNullableId(value.assetRef, "asset reference")
    out.writeFloatInRange(value.widthPercent, 1f..100f, "image width")
    out.writeByte(alignmentId(value.alignment))
    out.writeNullablePositiveFloat(value.aspectRatio, "image aspect ratio")
    out.writeNullableByte(value.ocrStatus?.let(::ocrStatusId), "OCR status")
    out.writeNullableBytes(value.formattingBlob, MAX_FORMATTING_BYTES, "formatting data")
    validateBlockAssetFields(value.type, value.assetRef, value.ocrStatus)
  }

  private fun readBlock(input: DataInputStream, domain: BackupRecordDomain): BackupRecordPayload.Block {
    val id = input.readId("block ID")
    val noteId = input.readId("note ID")
    val position = input.readNonNegativeInt("block position")
    val type = blockTypeFromId(input.readUnsignedByte())
    val text = input.readNullableString(maxTextBytes(domain), "block text")
    val assetRef = input.readNullableId("asset reference")
    val width = input.readFloatInRange(1f..100f, "image width")
    val alignment = alignmentFromId(input.readUnsignedByte())
    val aspectRatio = input.readNullablePositiveFloat("image aspect ratio")
    val ocrStatus = input.readNullableByte("OCR status")?.let(::ocrStatusFromId)
    val formatting = input.readNullableBytes(MAX_FORMATTING_BYTES, "formatting data")
    validateBlockAssetFields(type, assetRef, ocrStatus)
    return BackupRecordPayload.Block(id, noteId, position, type, text, assetRef, width, alignment, aspectRatio, ocrStatus, formatting)
  }

  private fun writeTodo(out: DataOutputStream, domain: BackupRecordDomain, value: BackupRecordPayload.Todo) {
    out.writeId(value.id, "todo ID")
    out.writeId(value.noteId, "note ID")
    out.writeId(value.blockId, "block ID")
    out.writeString(value.text, maxTextBytes(domain), "todo text")
    out.writeBooleanByte(value.isCompleted)
    out.writeNonNegativeLong(value.updatedAtMillis, "todo update time")
  }

  private fun readTodo(input: DataInputStream, domain: BackupRecordDomain): BackupRecordPayload.Todo = BackupRecordPayload.Todo(
    id = input.readId("todo ID"),
    noteId = input.readId("note ID"),
    blockId = input.readId("block ID"),
    text = input.readString(maxTextBytes(domain), "todo text"),
    isCompleted = input.readBooleanByte("todo completed flag"),
    updatedAtMillis = input.readNonNegativeLong("todo update time"),
  )

  private fun writeDraft(out: DataOutputStream, domain: BackupRecordDomain, value: BackupRecordPayload.Draft) {
    out.writeId(value.draftId, "draft ID")
    out.writeNullableId(value.noteId, "note ID")
    out.writeId(value.folderId, "folder ID")
    out.writeNullableLong(value.baseContentRevision, "draft base revision")
    out.writeNonNegativeLong(value.localRevision, "draft local revision")
    out.writeNonNegativeLong(value.savedAtMillis, "draft save time")
    out.writeNullableId(value.importRequestId, "import request ID")
    out.writeString(value.title, MAX_TITLE_UTF8_BYTES, "draft title")
    out.writeBooleanByte(value.titleManuallySet)
    out.writeId(value.skinId, "skin ID")
    out.writeNullableFloat(value.fontSizeSpOverride, "font size", 14f..32f)
    out.writeNullableFloat(value.lineSpacingMultiplierOverride, "line spacing", 1f..2f)
    out.writeNonNegativeInt(value.blockCount, "draft block count")
    if (value.blockCount > MAX_BLOCKS_PER_PARENT) throw BackupRecordPayloadException("Draft block count exceeds its limit")
    if (domain == BackupRecordDomain.PRIVATE && value.importRequestId != null) {
      throw BackupRecordPayloadException("Private drafts cannot carry ordinary import request IDs")
    }
  }

  private fun readDraft(input: DataInputStream, domain: BackupRecordDomain): BackupRecordPayload.Draft {
    val draftId = input.readId("draft ID")
    val noteId = input.readNullableId("note ID")
    val folderId = input.readId("folder ID")
    val baseRevision = input.readNullableLong("draft base revision")
    val localRevision = input.readNonNegativeLong("draft local revision")
    val savedAt = input.readNonNegativeLong("draft save time")
    val requestId = input.readNullableId("import request ID")
    val title = input.readString(MAX_TITLE_UTF8_BYTES, "draft title")
    val manuallySet = input.readBooleanByte("draft title flag")
    val skinId = input.readId("skin ID")
    val fontSize = input.readNullableFloat("font size", 14f..32f)
    val lineSpacing = input.readNullableFloat("line spacing", 1f..2f)
    val blockCount = input.readNonNegativeInt("draft block count")
    if (blockCount > MAX_BLOCKS_PER_PARENT) throw BackupRecordPayloadException("Draft block count exceeds its limit")
    if (domain == BackupRecordDomain.PRIVATE && requestId != null) {
      throw BackupRecordPayloadException("Private drafts cannot carry ordinary import request IDs")
    }
    return BackupRecordPayload.Draft(
      draftId, noteId, folderId, baseRevision, localRevision, savedAt, requestId,
      title, manuallySet, skinId, fontSize, lineSpacing, blockCount,
    )
  }

  private fun writeDraftBlock(out: DataOutputStream, domain: BackupRecordDomain, value: BackupRecordPayload.DraftBlock) {
    out.writeId(value.draftId, "draft ID")
    out.writeNonNegativeInt(value.position, "draft block position")
    out.writeId(value.blockId, "block ID")
    out.writeByte(blockTypeId(value.type))
    out.writeNullableString(value.text, maxTextBytes(domain), "draft block text")
    out.writeNullableId(value.assetRef, "asset reference")
    out.writeFloatInRange(value.widthPercent, 1f..100f, "image width")
    out.writeByte(alignmentId(value.alignment))
    out.writeNullablePositiveFloat(value.aspectRatio, "image aspect ratio")
    out.writeNullableByte(value.ocrStatus?.let(::ocrStatusId), "OCR status")
    out.writeNullableBytes(value.formattingBlob, MAX_FORMATTING_BYTES, "formatting data")
    validateBlockAssetFields(value.type, value.assetRef, value.ocrStatus)
  }

  private fun readDraftBlock(input: DataInputStream, domain: BackupRecordDomain): BackupRecordPayload.DraftBlock {
    val draftId = input.readId("draft ID")
    val position = input.readNonNegativeInt("draft block position")
    val blockId = input.readId("block ID")
    val type = blockTypeFromId(input.readUnsignedByte())
    val text = input.readNullableString(maxTextBytes(domain), "draft block text")
    val assetRef = input.readNullableId("asset reference")
    val width = input.readFloatInRange(1f..100f, "image width")
    val alignment = alignmentFromId(input.readUnsignedByte())
    val aspectRatio = input.readNullablePositiveFloat("image aspect ratio")
    val ocrStatus = input.readNullableByte("OCR status")?.let(::ocrStatusFromId)
    val formatting = input.readNullableBytes(MAX_FORMATTING_BYTES, "formatting data")
    validateBlockAssetFields(type, assetRef, ocrStatus)
    return BackupRecordPayload.DraftBlock(draftId, position, blockId, type, text, assetRef, width, alignment, aspectRatio, ocrStatus, formatting)
  }

  private fun readAsset(
    input: DataInputStream,
    domain: BackupRecordDomain,
    totalPayloadBytes: Long,
    sink: BackupAssetByteSink?,
  ): BackupRecordPayload.Asset {
    val assetId = input.readId("asset ID")
    val mimeType = input.readString(MAX_MIME_UTF8_BYTES, "asset MIME type", allowEmpty = false)
    validateMimeType(mimeType)
    val byteCount = input.readNonNegativeLong("asset byte count")
    validateAssetLength(domain, byteCount)
    val digest = ByteArray(SHA256_BYTES).also(input::readFully)
    val rawBytesRemaining = (input as? PayloadDataInputStream)?.remaining
      ?: throw BackupRecordPayloadException("Asset payload length accounting is unavailable")
    val prefixBytes = totalPayloadBytes - rawBytesRemaining
    if (prefixBytes < 0L || rawBytesRemaining != byteCount) {
      throw BackupRecordPayloadException("Asset byte count does not match its record length")
    }
    if (sink == null) throw BackupRecordPayloadException("Asset bytes require a protected streaming sink")
    val asset = BackupRecordPayload.Asset(assetId, mimeType, byteCount, digest)

    val hasher = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(ASSET_READ_BUFFER_BYTES)
    var remaining = byteCount
    try {
      while (remaining > 0L) {
        val request = minOf(buffer.size.toLong(), remaining).toInt()
        val count = input.read(buffer, 0, request)
        if (count < 0) throw BackupRecordPayloadException("Asset payload ended before its declared byte count")
        if (count == 0) {
          val one = input.read()
          if (one < 0) throw BackupRecordPayloadException("Asset payload ended before its declared byte count")
          buffer[0] = one.toByte()
          hasher.update(buffer, 0, 1)
          sink.write(asset, buffer, 0, 1)
          remaining--
          continue
        }
        hasher.update(buffer, 0, count)
        sink.write(asset, buffer, 0, count)
        remaining -= count.toLong()
      }
      if (!MessageDigest.isEqual(hasher.digest(), digest)) throw BackupRecordPayloadException("Asset SHA-256 does not match")
      return asset
    } finally {
      MemoryWipe.bytes(buffer)
    }
  }

  private fun BackupRecordPayload.kind(): BackupRecordKind = when (this) {
    is BackupRecordPayload.Folder -> BackupRecordKind.FOLDER
    is BackupRecordPayload.Note -> BackupRecordKind.NOTE
    is BackupRecordPayload.Block -> BackupRecordKind.BLOCK
    is BackupRecordPayload.Todo -> BackupRecordKind.TODO
    is BackupRecordPayload.Draft -> BackupRecordKind.DRAFT
    is BackupRecordPayload.DraftBlock -> BackupRecordKind.DRAFT_BLOCK
    is BackupRecordPayload.Asset -> BackupRecordKind.ASSET
  }

  private fun validateIdentifier(value: String, field: String) {
    val bytes = value.strictUtf8Bytes(field)
    try {
      if (bytes.isEmpty() || bytes.size > MAX_IDENTIFIER_UTF8_BYTES ||
        value.any { it.isISOControl() || it == '/' || it == '\\' }
      ) throw BackupRecordPayloadException("$field is invalid")
    } finally {
      MemoryWipe.bytes(bytes)
    }
  }

  private fun validateMimeType(value: String) {
    if (value !in allowedImageMimeTypes) throw BackupRecordPayloadException("Asset MIME type is not supported")
  }

  private fun validateAssetLength(domain: BackupRecordDomain, byteCount: Long) {
    val maximum = when (domain) {
      BackupRecordDomain.ORDINARY -> BackupRecordStreamCodec.MAX_ORDINARY_ASSET_BYTES
      BackupRecordDomain.PRIVATE -> BackupRecordStreamCodec.MAX_PRIVATE_ASSET_BYTES
    }
    if (byteCount !in 1L..maximum) throw BackupRecordPayloadException("Asset byte count exceeds its type limit")
  }

  private fun validateBlockAssetFields(type: BlockType, assetRef: String?, ocrStatus: OcrStatus?) {
    if ((type == BlockType.IMAGE) != (assetRef != null)) {
      throw BackupRecordPayloadException("Image blocks must reference exactly one asset")
    }
    if (type != BlockType.OCR_TEXT && ocrStatus != null) {
      throw BackupRecordPayloadException("OCR status is only valid on OCR text blocks")
    }
  }

  private fun maxTextBytes(domain: BackupRecordDomain) = when (domain) {
    BackupRecordDomain.ORDINARY -> MAX_ORDINARY_TEXT_BYTES
    BackupRecordDomain.PRIVATE -> MAX_PRIVATE_TEXT_BYTES
  }

  private fun folderKindId(value: FolderKind): Int = when (value) {
    FolderKind.USER -> 1
    FolderKind.UNCATEGORIZED -> 2
  }

  private fun folderKindFromId(value: Int): FolderKind = when (value) {
    1 -> FolderKind.USER
    2 -> FolderKind.UNCATEGORIZED
    else -> throw BackupRecordPayloadException("Folder kind is unknown")
  }

  private fun blockTypeId(value: BlockType): Int = when (value) {
    BlockType.TEXT -> 1
    BlockType.IMAGE -> 2
    BlockType.TODO -> 3
    BlockType.QUOTE -> 4
    BlockType.DIVIDER -> 5
    BlockType.OCR_TEXT -> 6
  }

  private fun blockTypeFromId(value: Int): BlockType = when (value) {
    1 -> BlockType.TEXT
    2 -> BlockType.IMAGE
    3 -> BlockType.TODO
    4 -> BlockType.QUOTE
    5 -> BlockType.DIVIDER
    6 -> BlockType.OCR_TEXT
    else -> throw BackupRecordPayloadException("Block type is unknown")
  }

  private fun alignmentId(value: BlockAlignment): Int = when (value) {
    BlockAlignment.START -> 1
    BlockAlignment.CENTER -> 2
    BlockAlignment.END -> 3
  }

  private fun alignmentFromId(value: Int): BlockAlignment = when (value) {
    1 -> BlockAlignment.START
    2 -> BlockAlignment.CENTER
    3 -> BlockAlignment.END
    else -> throw BackupRecordPayloadException("Block alignment is unknown")
  }

  private fun ocrStatusId(value: OcrStatus): Int = when (value) {
    OcrStatus.NOT_REQUESTED -> 1
    OcrStatus.PENDING, OcrStatus.RUNNING -> 2
    OcrStatus.SUCCEEDED -> 4
    OcrStatus.FAILED -> 5
    OcrStatus.CANCELLED -> 6
  }

  private fun ocrStatusFromId(value: Int): OcrStatus = when (value) {
    1 -> OcrStatus.NOT_REQUESTED
    2 -> OcrStatus.PENDING
    4 -> OcrStatus.SUCCEEDED
    5 -> OcrStatus.FAILED
    6 -> OcrStatus.CANCELLED
    else -> throw BackupRecordPayloadException("OCR status is unknown")
  }

  private fun DataOutputStream.writeString(value: String, maxBytes: Int, field: String, allowEmpty: Boolean = true) {
    val encoded = value.strictUtf8Bytes(field)
    try {
      if (encoded.size > maxBytes || (!allowEmpty && encoded.isEmpty())) {
        throw BackupRecordPayloadException("$field length is invalid")
      }
      writeInt(encoded.size)
      write(encoded)
    } finally {
      MemoryWipe.bytes(encoded)
    }
  }

  private fun DataOutputStream.writeId(value: String, field: String) {
    validateIdentifier(value, field)
    writeString(value, MAX_IDENTIFIER_UTF8_BYTES, field, allowEmpty = false)
  }

  private fun DataOutputStream.writeNullableId(value: String?, field: String) {
    writePresence(value != null)
    value?.let { writeId(it, field) }
  }

  private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int, field: String) {
    writePresence(value != null)
    value?.let { writeString(it, maxBytes, field) }
  }

  private fun DataOutputStream.writeBooleanByte(value: Boolean) = writeByte(if (value) 1 else 0)
  private fun DataOutputStream.writePresence(present: Boolean) = writeByte(if (present) 1 else 0)

  private fun DataOutputStream.writeNullableByte(value: Int?, field: String) {
    writePresence(value != null)
    value?.let {
      if (it !in 0..255) throw BackupRecordPayloadException("$field value is invalid")
      writeByte(it)
    }
  }

  private fun DataOutputStream.writeNullableFloat(value: Float?, field: String, range: ClosedFloatingPointRange<Float>) {
    writePresence(value != null)
    value?.let { writeFloatInRange(it, range, field) }
  }

  private fun DataOutputStream.writeNullablePositiveFloat(value: Float?, field: String) {
    writePresence(value != null)
    value?.let {
      if (!it.isFinite() || it <= 0f) throw BackupRecordPayloadException("$field is invalid")
      writeFloat(it)
    }
  }

  private fun DataOutputStream.writeFloatInRange(value: Float, range: ClosedFloatingPointRange<Float>, field: String) {
    if (!value.isFinite() || value !in range) throw BackupRecordPayloadException("$field is outside its supported range")
    writeFloat(value)
  }

  private fun DataOutputStream.writeNullableLong(value: Long?, field: String) {
    writePresence(value != null)
    value?.let {
      if (it < 0L) throw BackupRecordPayloadException("$field is invalid")
      writeLong(it)
    }
  }

  private fun DataOutputStream.writeNonNegativeLong(value: Long, field: String) {
    if (value < 0L) throw BackupRecordPayloadException("$field is invalid")
    writeLong(value)
  }

  private fun DataOutputStream.writeNonNegativeInt(value: Int, field: String) {
    if (value < 0) throw BackupRecordPayloadException("$field is invalid")
    writeInt(value)
  }

  private fun DataOutputStream.writeNullableBytes(value: ByteArray?, maxBytes: Int, field: String) {
    writePresence(value != null)
    value?.let {
      if (it.size > maxBytes) throw BackupRecordPayloadException("$field exceeds its size limit")
      writeInt(it.size)
      write(it)
    }
  }

  private fun DataInputStream.readString(maxBytes: Int, field: String, allowEmpty: Boolean = true): String {
    val length = readInt()
    if (length < 0 || length > maxBytes || length.toLong() > remainingBytes()) {
      throw BackupRecordPayloadException("$field length is invalid")
    }
    if (!allowEmpty && length == 0) throw BackupRecordPayloadException("$field cannot be empty")
    val bytes = ByteArray(length).also(::readFully)
    try {
      val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } finally {
      MemoryWipe.bytes(bytes)
    }
  }

  private fun DataInputStream.readId(field: String): String = readString(MAX_IDENTIFIER_UTF8_BYTES, field, allowEmpty = false).also {
    validateIdentifier(it, field)
  }

  private fun DataInputStream.readNullableId(field: String): String? = readPresence(field)?.let { readId(field) }

  private fun DataInputStream.readNullableString(maxBytes: Int, field: String): String? =
    readPresence(field)?.let { readString(maxBytes, field) }

  private fun DataInputStream.readBooleanByte(field: String): Boolean = when (val value = readUnsignedByte()) {
    0 -> false
    1 -> true
    else -> throw BackupRecordPayloadException("$field flag is invalid ($value)")
  }

  private fun DataInputStream.readPresence(field: String): Boolean? = when (val value = readUnsignedByte()) {
    0 -> null
    1 -> true
    else -> throw BackupRecordPayloadException("$field presence flag is invalid ($value)")
  }

  private fun DataInputStream.readNullableFloat(field: String, range: ClosedFloatingPointRange<Float>): Float? =
    readPresence(field)?.let { readFloatInRange(range, field) }

  private fun DataInputStream.readNullablePositiveFloat(field: String): Float? = readPresence(field)?.let {
    readFloat().also { value ->
      if (!value.isFinite() || value <= 0f) throw BackupRecordPayloadException("$field is invalid")
    }
  }

  private fun DataInputStream.readFloatInRange(range: ClosedFloatingPointRange<Float>, field: String): Float = readFloat().also {
    if (!it.isFinite() || it !in range) throw BackupRecordPayloadException("$field is outside its supported range")
  }

  private fun DataInputStream.readNullableLong(field: String): Long? = readPresence(field)?.let {
    readLong().also { value -> if (value < 0L) throw BackupRecordPayloadException("$field is invalid") }
  }

  private fun DataInputStream.readNonNegativeLong(field: String): Long = readLong().also {
    if (it < 0L) throw BackupRecordPayloadException("$field is invalid")
  }

  private fun DataInputStream.readNonNegativeInt(field: String): Int = readInt().also {
    if (it < 0) throw BackupRecordPayloadException("$field is invalid")
  }

  private fun DataInputStream.readNullableByte(field: String): Int? = readPresence(field)?.let { readUnsignedByte() }

  private fun DataInputStream.readNullableBytes(maxBytes: Int, field: String): ByteArray? = readPresence(field)?.let {
    val length = readInt()
    if (length < 0 || length > maxBytes || length.toLong() > remainingBytes()) {
      throw BackupRecordPayloadException("$field length is invalid")
    }
    ByteArray(length).also(::readFully)
  }

  private fun DataInputStream.remainingBytes(): Long = (this as? PayloadDataInputStream)?.remaining
    ?: throw IllegalStateException("Payload length accounting is unavailable")

  private fun String.strictUtf8Bytes(field: String): ByteArray = try {
    StandardCharsets.UTF_8.newEncoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .encode(java.nio.CharBuffer.wrap(this))
      .let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
  } catch (failure: CharacterCodingException) {
    throw BackupRecordPayloadException("$field contains invalid Unicode", failure)
  }

  private class PayloadLimitedInputStream(source: InputStream, length: Long) : InputStream() {
    private val input = source
    var remaining = length
      private set

    override fun read(): Int {
      if (remaining == 0L) return -1
      val value = input.read()
      if (value < 0) throw EOFException("Record payload ended early")
      remaining--
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
      if (length == 0) return 0
      if (remaining == 0L) return -1
      val requested = minOf(length.toLong(), remaining).toInt()
      val count = input.read(buffer, offset, requested)
      if (count < 0) throw EOFException("Record payload ended early")
      if (count > 0) remaining -= count.toLong()
      return count
    }
  }

  private class PayloadDataInputStream(private val limited: PayloadLimitedInputStream) : DataInputStream(limited) {
    val remaining: Long get() = limited.remaining
  }

  private class WipeableByteArrayOutputStream : ByteArrayOutputStream() {
    fun wipe() {
      MemoryWipe.bytes(buf)
      reset()
    }
  }
}

package com.anan.anannotes.feature.editor

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingCodec
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.OcrStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class EditorDraftSnapshot(
  val draftId: String,
  val noteId: String?,
  val folderId: String,
  val baseContentRevision: Long,
  val localRevision: Long,
  val savedAtMillis: Long,
  val draft: NoteDraft,
  val importRequestId: String? = null,
)

interface EditorDraftStore {
  suspend fun writeAtomic(snapshot: EditorDraftSnapshot)

  suspend fun listRecoverable(): List<EditorDraftSnapshot>

  suspend fun clearIfAtMost(draftId: String, committedRevision: Long): Boolean
}

/** Normal-note crash drafts only. Private-note drafts must use a separate encrypted store. */
class FileEditorDraftStore(
  private val directory: File,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EditorDraftStore {
  override suspend fun writeAtomic(snapshot: EditorDraftSnapshot) = withContext(ioDispatcher) {
    validateDraftId(snapshot.draftId)
    require(snapshot.localRevision >= 0L) { "草稿修订号无效" }
    if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建草稿目录")

    val target = draftFile(snapshot.draftId)
    val temporary = File(directory, ".${snapshot.draftId}.${UUID.randomUUID()}.tmp")
    try {
      FileOutputStream(temporary).use { file ->
        DataOutputStream(BufferedOutputStream(file)).use { output ->
          DraftCodec.write(output, snapshot)
          output.flush()
          file.fd.sync()
        }
      }
      if (temporary.length() > DraftCodec.MAX_FILE_BYTES) throw IOException("草稿超过安全写入上限")
      try {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      Unit
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  override suspend fun listRecoverable(): List<EditorDraftSnapshot> = withContext(ioDispatcher) {
    if (!directory.exists()) return@withContext emptyList()
    val files = directory.listFiles { file -> file.isFile && file.name.endsWith(FILE_SUFFIX) }
      ?: throw IOException("无法读取草稿目录")
    files.map { file ->
      readSnapshot(file)
    }.sortedByDescending(EditorDraftSnapshot::savedAtMillis)
  }

  override suspend fun clearIfAtMost(draftId: String, committedRevision: Long): Boolean = withContext(ioDispatcher) {
    validateDraftId(draftId)
    val file = draftFile(draftId)
    if (!file.exists()) return@withContext false
    val snapshot = readSnapshot(file)
    if (snapshot.localRevision > committedRevision) return@withContext false
    Files.deleteIfExists(file.toPath())
  }

  private fun draftFile(draftId: String) = File(directory, "$draftId$FILE_SUFFIX")

  private fun readSnapshot(file: File): EditorDraftSnapshot {
    if (file.length() > DraftCodec.MAX_FILE_BYTES) throw IOException("草稿文件超过安全读取上限")
    val snapshot = FileInputStream(file).use { input ->
      DataInputStream(BufferedInputStream(input)).use(DraftCodec::read)
    }
    runCatching { UUID.fromString(snapshot.draftId) }
      .getOrElse { throw IOException("草稿标识无效", it) }
    if (snapshot.baseContentRevision < 0L || snapshot.localRevision < 0L) throw IOException("草稿修订号无效")
    return snapshot
  }

  private fun validateDraftId(draftId: String) {
    require(runCatching { UUID.fromString(draftId) }.isSuccess) { "草稿标识无效" }
  }

  private companion object {
    const val FILE_SUFFIX = ".aan-draft"
  }
}

private object DraftCodec {
  private const val MAGIC = 0x414E414E // ANAN
  private const val VERSION = 4
  const val MAX_FILE_BYTES = 64 * 1024 * 1024
  private const val MAX_STRING_BYTES = 32 * 1024 * 1024
  private const val MAX_BLOCKS = 100_000

  fun write(output: DataOutputStream, snapshot: EditorDraftSnapshot) {
    output.writeInt(MAGIC)
    output.writeInt(VERSION)
    output.writeString(snapshot.draftId)
    output.writeNullableString(snapshot.noteId)
    output.writeString(snapshot.folderId)
    output.writeLong(snapshot.baseContentRevision)
    output.writeLong(snapshot.localRevision)
    output.writeLong(snapshot.savedAtMillis)
    output.writeNullableString(snapshot.importRequestId)

    val draft = snapshot.draft
    output.writeString(draft.folderId)
    output.writeString(draft.title)
    output.writeBoolean(draft.titleManuallySet)
    output.writeString(draft.skinId)
    output.writeNullableFloat(draft.fontSizeSpOverride)
    output.writeNullableFloat(draft.lineSpacingMultiplierOverride)
    require(draft.blocks.size <= MAX_BLOCKS) { "草稿内容块过多" }
    output.writeInt(draft.blocks.size)
    draft.blocks.forEach { block ->
      output.writeString(block.id)
      output.writeString(block.type.name)
      output.writeNullableString(block.text)
      output.writeNullableString(block.assetPath)
      output.writeNullableString(block.previewPath)
      output.writeFloat(block.widthPercent)
      output.writeString(block.alignment.name)
      output.writeNullableFloat(block.aspectRatio)
      output.writeNullableString(block.ocrStatus?.name)
      val formattingBlob = when (val state = block.formatting) {
        is BlockFormattingState.Supported -> {
          if (state.value == BlockFormatting()) null
          else BlockFormattingCodec.encode(block.text.orEmpty(), block.type, state.value)
        }
        is BlockFormattingState.Unsupported -> state.originalBlob
      }
      output.writeNullableBytes(formattingBlob)
    }
  }

  fun read(input: DataInputStream): EditorDraftSnapshot {
    val magic = input.readInt()
    if (magic != MAGIC) throw IOException("草稿文件格式无效")
    val version = input.readInt()
    if (version !in 1..VERSION) throw IOException("不支持的草稿版本：$version")

    val draftId = input.readString()
    val noteId = input.readNullableString()
    val folderId = input.readString()
    val baseContentRevision = input.readLong()
    val localRevision = input.readLong()
    val savedAtMillis = input.readLong()
    val importRequestId = if (version >= 4) input.readNullableString() else null
    val draftFolderId = input.readString()
    val title = input.readString()
    val titleManuallySet = input.readBoolean()
    val skinId = input.readString()
    val fontSize = input.readNullableFloat()
    val lineSpacing = input.readNullableFloat()
    val blockCount = input.readInt()
    if (blockCount !in 0..MAX_BLOCKS) throw IOException("草稿内容块数量无效")
    val blocks = List(blockCount) {
      val blockId = if (version >= 2) input.readString() else UUID.randomUUID().toString()
      val type = enumValueOf<BlockType>(input.readString())
      val text = input.readNullableString()
      val assetPath = input.readNullableString()
      val previewPath = input.readNullableString()
      val widthPercent = input.readFloat()
      val alignment = enumValueOf<BlockAlignment>(input.readString())
      val aspectRatio = input.readNullableFloat()
      val ocrStatus = input.readNullableString()?.let { enumValueOf<OcrStatus>(it) }
      val formatting = if (version >= 3) {
        input.readNullableBytes()?.let { blob ->
          BlockFormattingCodec.decode(blob, text.orEmpty(), type)
        } ?: BlockFormattingState.Supported(BlockFormatting())
      } else {
        BlockFormattingState.Supported(BlockFormatting())
      }
      NoteBlockDraft(
        id = blockId,
        type = type,
        text = text,
        assetPath = assetPath,
        previewPath = previewPath,
        widthPercent = widthPercent,
        alignment = alignment,
        aspectRatio = aspectRatio,
        ocrStatus = ocrStatus,
        formatting = formatting,
      )
    }
    if (input.available() > 0) throw IOException("草稿文件包含未知数据")
    return EditorDraftSnapshot(
      draftId = draftId,
      noteId = noteId,
      importRequestId = importRequestId,
      folderId = folderId,
      baseContentRevision = baseContentRevision,
      localRevision = localRevision,
      savedAtMillis = savedAtMillis,
      draft = NoteDraft(
        folderId = draftFolderId,
        title = title,
        titleManuallySet = titleManuallySet,
        blocks = blocks,
        skinId = skinId,
        fontSizeSpOverride = fontSize,
        lineSpacingMultiplierOverride = lineSpacing,
      ),
    )
  }

  private fun DataOutputStream.writeString(value: String) = writeBytes(value.toByteArray(Charsets.UTF_8))

  private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
  }

  private fun DataOutputStream.writeNullableBytes(value: ByteArray?) {
    writeBoolean(value != null)
    if (value != null) {
      require(value.size <= BlockFormattingCodec.MAX_BLOB_BYTES) { "格式数据超过草稿字段上限" }
      writeInt(value.size)
      write(value)
    }
  }

  private fun DataOutputStream.writeBytes(bytes: ByteArray) {
    require(bytes.size <= MAX_STRING_BYTES) { "草稿字段过大" }
    writeInt(bytes.size)
    write(bytes)
  }

  private fun DataOutputStream.writeNullableFloat(value: Float?) {
    writeBoolean(value != null)
    if (value != null) writeFloat(value)
  }

  private fun DataInputStream.readString(): String {
    val length = readInt()
    if (length !in 0..MAX_STRING_BYTES || length > available()) throw IOException("草稿字段长度无效")
    return ByteArray(length).also(::readFully).toString(Charsets.UTF_8)
  }

  private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null

  private fun DataInputStream.readNullableBytes(): ByteArray? {
    if (!readBoolean()) return null
    val length = readInt()
    if (length !in 0..BlockFormattingCodec.MAX_BLOB_BYTES || length > available()) {
      throw IOException("草稿格式字段长度无效")
    }
    return ByteArray(length).also(::readFully)
  }

  private fun DataInputStream.readNullableFloat(): Float? = if (readBoolean()) readFloat() else null
}

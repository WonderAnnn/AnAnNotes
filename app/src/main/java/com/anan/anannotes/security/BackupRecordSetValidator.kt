package com.anan.anannotes.security

import com.anan.anannotes.core.model.BlockType
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Counts are safe to display only after [BackupRecordSetValidator.finish] succeeds. */
data class BackupRecordSetSummary(
  val ordinaryFolders: Long,
  val ordinaryNotes: Long,
  val ordinaryBlocks: Long,
  val ordinaryTodos: Long,
  val ordinaryDrafts: Long,
  val ordinaryDraftBlocks: Long,
  val ordinaryAssets: Long,
  val ordinaryAssetBytes: Long,
  val privateNotes: Long,
  val privateBlocks: Long,
  val privateTodos: Long,
  val privateDrafts: Long,
  val privateDraftBlocks: Long,
  val privateAssets: Long,
  val privateAssetBytes: Long,
)

/**
 * Validates cross-record relationships after streaming decode. It retains bounded identifiers and
 * metadata only; attachment bytes are consumed by the caller's protected staging sink.
 */
class BackupRecordSetValidator(
  private val maxRecords: Long = MAX_VALIDATED_RECORDS,
) : AutoCloseable {
  private val ordinary = DomainState(BackupRecordDomain.ORDINARY)
  private val private = DomainState(BackupRecordDomain.PRIVATE)
  private var recordCount = 0L
  private var finished = false
  private var failed = false

  init {
    require(maxRecords in 1..MAX_VALIDATED_RECORDS) { "Backup record count limit is invalid" }
  }

  fun accept(domain: BackupRecordDomain, payload: BackupRecordPayload) {
    check(!finished && !failed) { "Backup record validator is not open" }
    if (recordCount >= maxRecords) throw BackupRecordPayloadException("Backup record count exceeds its limit")
    val state = if (domain == BackupRecordDomain.ORDINARY) ordinary else private
    try {
      when (payload) {
      is BackupRecordPayload.Folder -> {
        if (domain == BackupRecordDomain.PRIVATE) {
          throw BackupRecordPayloadException("Private records cannot contain folder records")
        }
        if (!state.folders.add(payload.id)) throw BackupRecordPayloadException("Duplicate folder ID")
        val nameKey = digestKey(payload.name.asciiLowercase())
        if (!state.folderNames.add(nameKey)) throw BackupRecordPayloadException("Folder names are duplicated")
      }
      is BackupRecordPayload.Note -> {
        state.unique(state.notes, payload.id, "note")
        if (domain == BackupRecordDomain.ORDINARY && payload.importRequestId != null &&
          !state.importRequestIds.add(payload.importRequestId)
        ) throw BackupRecordPayloadException("Import request IDs are duplicated")
        state.notes[payload.id] = NoteMeta(payload.folderId)
      }
      is BackupRecordPayload.Block -> {
        state.unique(state.blocks, payload.id, "block")
        state.addPosition(state.noteBlockPositions, payload.noteId, payload.position, "note block")
        payload.assetRef?.let(state.assetReferences::add)
        state.blocks[payload.id] = BlockMeta(
          payload.noteId,
          payload.type,
          payload.text?.let(::digestText),
          payload.text?.isNotBlank() == true,
        )
      }
      is BackupRecordPayload.Todo -> {
        state.unique(state.todos, payload.id, "todo")
        if (payload.text.isBlank()) throw BackupRecordPayloadException("Todo text cannot be blank")
        if (state.todoIdsByBlock.putIfAbsent(payload.blockId, payload.id) != null) {
          throw BackupRecordPayloadException("A TODO block has multiple todo records")
        }
        state.todos[payload.id] = TodoMeta(payload.noteId, payload.blockId, digestText(payload.text))
      }
      is BackupRecordPayload.Draft -> {
        state.unique(state.drafts, payload.draftId, "draft")
        state.drafts[payload.draftId] = DraftMeta(
          noteId = payload.noteId,
          folderId = payload.folderId,
          baseRevision = payload.baseContentRevision,
          declaredBlockCount = payload.blockCount,
        )
      }
      is BackupRecordPayload.DraftBlock -> {
        val key = payload.draftId
        state.addPosition(state.draftBlockPositions, key, payload.position, "draft block")
        val ids = state.draftBlockIds.getOrPut(key) { HashSet() }
        if (!ids.add(payload.blockId)) throw BackupRecordPayloadException("Draft block IDs are duplicated")
        payload.assetRef?.let(state.assetReferences::add)
      }
      is BackupRecordPayload.Asset -> {
        if (!state.assets.add(payload.assetId)) throw BackupRecordPayloadException("Duplicate asset ID")
        state.assetBytes = checkedAdd(state.assetBytes, payload.byteCount)
      }
      }
      recordCount++
    } catch (failure: Throwable) {
      failed = true
      throw failure
    }
  }

  /** Checks all forward references and ordering after the record stream terminator was read. */
  fun finish(): BackupRecordSetSummary {
    check(!finished && !failed) { "Backup record validator is not open" }
    try {
      validateDomain(ordinary)
      validateDomain(private)
    } catch (failure: Throwable) {
      failed = true
      clearMetadata()
      throw failure
    }
    val summary = BackupRecordSetSummary(
      ordinaryFolders = ordinary.folders.size.toLong(),
      ordinaryNotes = ordinary.notes.size.toLong(),
      ordinaryBlocks = ordinary.blocks.size.toLong(),
      ordinaryTodos = ordinary.todos.size.toLong(),
      ordinaryDrafts = ordinary.drafts.size.toLong(),
      ordinaryDraftBlocks = ordinary.draftBlockIds.values.sumOf { it.size.toLong() },
      ordinaryAssets = ordinary.assets.size.toLong(),
      ordinaryAssetBytes = ordinary.assetBytes,
      privateNotes = private.notes.size.toLong(),
      privateBlocks = private.blocks.size.toLong(),
      privateTodos = private.todos.size.toLong(),
      privateDrafts = private.drafts.size.toLong(),
      privateDraftBlocks = private.draftBlockIds.values.sumOf { it.size.toLong() },
      privateAssets = private.assets.size.toLong(),
      privateAssetBytes = private.assetBytes,
    )
    finished = true
    clearMetadata()
    return summary
  }

  override fun close() {
    clearMetadata()
    finished = true
  }

  private fun validateDomain(state: DomainState) {
    state.notes.values.forEach { note ->
      validateFolderReference(state, note.folderId)
    }
    state.blocks.values.forEach { block ->
      if (!state.notes.containsKey(block.noteId)) throw BackupRecordPayloadException("Block refers to a missing note")
    }
    state.noteBlockPositions.forEach { (noteId, positions) ->
      if (!state.notes.containsKey(noteId)) throw BackupRecordPayloadException("Block refers to a missing note")
      validateContiguous(positions, "note block")
    }
    state.todos.values.forEach { todo ->
      val block = state.blocks[todo.blockId]
        ?: throw BackupRecordPayloadException("Todo refers to a missing block")
      if (block.noteId != todo.noteId || block.type != BlockType.TODO || block.textDigest == null ||
        !MessageDigest.isEqual(block.textDigest, todo.textDigest)
      ) {
        throw BackupRecordPayloadException("Todo does not match its TODO block")
      }
    }
    validateTodoCoverage(state)
    state.drafts.forEach { (draftId, draft) ->
      validateFolderReference(state, draft.folderId)
      if (draft.noteId == null) {
        if (draft.baseRevision != null && draft.baseRevision != 0L) {
          throw BackupRecordPayloadException("New-note draft has an invalid base revision")
        }
      } else {
        if (!state.notes.containsKey(draft.noteId)) throw BackupRecordPayloadException("Draft refers to a missing note")
        if (draft.baseRevision == null || draft.baseRevision < 1L) {
          throw BackupRecordPayloadException("Existing-note draft has an invalid base revision")
        }
      }
      val positions = state.draftBlockPositions[draftId].orEmpty()
      if (positions.size != draft.declaredBlockCount) throw BackupRecordPayloadException("Draft block count does not match its header")
      if (positions.isNotEmpty()) validateContiguous(positions, "draft block")
    }
    if (state.draftBlockPositions.keys.any { it !in state.drafts }) {
      throw BackupRecordPayloadException("Draft block refers to a missing draft")
    }
    if (state.draftBlockIds.keys.any { it !in state.drafts }) {
      throw BackupRecordPayloadException("Draft block refers to a missing draft")
    }
    state.assetReferences.forEach { assetId ->
      if (!state.assets.contains(assetId)) throw BackupRecordPayloadException("Image block refers to a missing asset")
    }
    if (state.assets.any { it !in state.assetReferences }) {
      throw BackupRecordPayloadException("Backup contains an unreferenced asset")
    }
  }

  private fun validateTodoCoverage(state: DomainState) {
    state.blocks.forEach { (blockId, block) ->
      if (block.type == BlockType.TODO && block.hasNonBlankText &&
        !state.todoIdsByBlock.containsKey(blockId)
      ) throw BackupRecordPayloadException("TODO block has no todo record")
    }
  }

  private fun validateFolderReference(state: DomainState, folderId: String) {
    when (state.domain) {
      BackupRecordDomain.ORDINARY -> if (!state.folders.contains(folderId)) {
        throw BackupRecordPayloadException("Note refers to a missing folder")
      }
      BackupRecordDomain.PRIVATE -> if (folderId != PRIVATE_UNCATEGORIZED_FOLDER_ID) {
        throw BackupRecordPayloadException("Private note refers to an unsupported folder")
      }
    }
  }

  private fun validateContiguous(positions: Set<Int>, label: String) {
    if (positions.size > BackupRecordPayloadCodec.MAX_BLOCKS_PER_PARENT) {
      throw BackupRecordPayloadException("$label count exceeds its limit")
    }
    var expected = 0
    positions.sorted().forEach { actual ->
      if (actual != expected) throw BackupRecordPayloadException("$label positions are not contiguous")
      expected++
    }
  }

  private fun checkedAdd(a: Long, b: Long): Long = try {
    Math.addExact(a, b)
  } catch (failure: ArithmeticException) {
    throw BackupRecordPayloadException("Asset byte count overflow", failure)
  }

  private fun digestKey(value: String): DigestKey = DigestKey(digestText(value))

  private fun String.asciiLowercase(): String = buildString(length) {
    for (character in this@asciiLowercase) {
      append(if (character in 'A'..'Z') character + ('a' - 'A') else character)
    }
  }

  private fun clearMetadata() {
    listOf(ordinary, private).forEach { state ->
      state.blocks.values.forEach { MemoryWipe.bytes(it.textDigest) }
      state.todos.values.forEach { MemoryWipe.bytes(it.textDigest) }
      state.folderNames.forEach(DigestKey::wipe)
      state.folders.clear()
      state.folderNames.clear()
      state.notes.clear()
      state.importRequestIds.clear()
      state.blocks.clear()
      state.todos.clear()
      state.todoIdsByBlock.clear()
      state.drafts.clear()
      state.draftBlockIds.clear()
      state.noteBlockPositions.clear()
      state.draftBlockPositions.clear()
      state.assets.clear()
      state.assetReferences.clear()
    }
  }

  private fun digestText(value: String): ByteArray {
    val encoded = try {
      StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
    } catch (failure: Exception) {
      throw BackupRecordPayloadException("Record text contains invalid Unicode", failure)
    }
    return try {
      MessageDigest.getInstance("SHA-256").also { it.update(encoded) }.digest()
    } finally {
      if (encoded.hasArray()) encoded.array().fill(0)
    }
  }

  private class DomainState(val domain: BackupRecordDomain) {
    val folders = LinkedHashSet<String>()
    val folderNames = HashSet<DigestKey>()
    val notes = LinkedHashMap<String, NoteMeta>()
    val importRequestIds = HashSet<String>()
    val blocks = LinkedHashMap<String, BlockMeta>()
    val todos = LinkedHashMap<String, TodoMeta>()
    val todoIdsByBlock = HashMap<String, String>()
    val drafts = LinkedHashMap<String, DraftMeta>()
    val draftBlockIds = HashMap<String, MutableSet<String>>()
    val noteBlockPositions = HashMap<String, MutableSet<Int>>()
    val draftBlockPositions = HashMap<String, MutableSet<Int>>()
    val assets = LinkedHashSet<String>()
    val assetReferences = HashSet<String>()
    var assetBytes = 0L

    fun <T> unique(map: Map<String, T>, id: String, label: String) {
      if (map.containsKey(id)) throw BackupRecordPayloadException("Duplicate $label ID")
    }

    fun addPosition(
      byParent: MutableMap<String, MutableSet<Int>>,
      parentId: String,
      position: Int,
      label: String,
    ) {
      val positions = byParent.getOrPut(parentId) { HashSet() }
      if (positions.size >= BackupRecordPayloadCodec.MAX_BLOCKS_PER_PARENT) {
        throw BackupRecordPayloadException("$label count exceeds its limit")
      }
      if (!positions.add(position)) throw BackupRecordPayloadException("Duplicate $label position")
    }
  }

  private data class NoteMeta(val folderId: String)

  private data class BlockMeta(
    val noteId: String,
    val type: BlockType,
    val textDigest: ByteArray?,
    val hasNonBlankText: Boolean,
  )

  private data class TodoMeta(val noteId: String, val blockId: String, val textDigest: ByteArray)

  private data class DraftMeta(
    val noteId: String?,
    val folderId: String,
    val baseRevision: Long?,
    val declaredBlockCount: Int,
  )

  private class DigestKey(private val digest: ByteArray) {
    override fun equals(other: Any?): Boolean = other is DigestKey && MessageDigest.isEqual(digest, other.digest)

    override fun hashCode(): Int = digest.contentHashCode()

    fun wipe() = MemoryWipe.bytes(digest)
  }

  companion object {
    const val MAX_VALIDATED_RECORDS = 100_000L
    const val PRIVATE_UNCATEGORIZED_FOLDER_ID = "private-uncategorized"
  }
}

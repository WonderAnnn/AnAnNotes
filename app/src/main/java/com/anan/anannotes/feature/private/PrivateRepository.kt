package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.TRASH_RETENTION_MILLIS
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.feature.ocr.OcrJobRules
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.MemoryWipe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class PrivateModuleStatus {
  UNKNOWN,
  SETUP_REQUIRED,
  LOCKED,
  UNLOCKED,
}

/** Private data operations use only the encrypted vault; no ordinary Room/FTS/draft repository is referenced. */
class PrivateRepository(
  private val store: PrivateStore,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val sessionMonitor = Any()
  private val mutationMutex = Mutex()
  private val generation = MutableStateFlow(0L)
  private val mutableStatus = MutableStateFlow(PrivateModuleStatus.UNKNOWN)
  private val _status: StateFlow<PrivateModuleStatus> = mutableStatus.asStateFlow()
  private var activeAccess: PrivateAccess? = null

  val status: StateFlow<PrivateModuleStatus> get() = _status

  suspend fun refreshStatus(): PrivateModuleStatus {
    val next = if (store.isConfigured()) PrivateModuleStatus.LOCKED else PrivateModuleStatus.SETUP_REQUIRED
    synchronized(sessionMonitor) {
      if (activeAccess == null) mutableStatus.value = next
    }
    return mutableStatus.value
  }

  suspend fun setup(password: CharArray, recoveryKey: CharArray?): PrivateAccess {
    val old = synchronized(sessionMonitor) { activeAccess.also { activeAccess = null } }
    old?.revoke()
    return installSession(store.create(password, recoveryKey))
  }

  suspend fun unlockWithPassword(password: CharArray): PrivateAccess =
    installSession(store.unlockWithPassword(password))

  suspend fun unlockWithRecoveryKey(recoveryKey: CharArray): PrivateAccess =
    installSession(store.unlockWithRecoveryKey(recoveryKey))

  private fun installSession(access: PrivateAccess): PrivateAccess {
    val old = synchronized(sessionMonitor) {
      activeAccess.also {
        activeAccess = access
        mutableStatus.value = PrivateModuleStatus.UNLOCKED
      }
    }
    old?.revoke()
    generation.value += 1
    return access
  }

  fun lock() {
    val old = synchronized(sessionMonitor) {
      activeAccess.also {
        activeAccess = null
        if (mutableStatus.value == PrivateModuleStatus.UNLOCKED) mutableStatus.value = PrivateModuleStatus.LOCKED
      }
    }
    old?.let(store::lock)
    generation.value += 1
  }

  /** Revoke one session without affecting a newer session that may already have replaced it. */
  fun revoke(access: PrivateAccess) {
    val wasCurrent = synchronized(sessionMonitor) {
      if (activeAccess === access) {
        activeAccess = null
        mutableStatus.value = PrivateModuleStatus.LOCKED
        true
      } else {
        false
      }
    }
    store.lock(access)
    if (wasCurrent) generation.value += 1
  }

  suspend fun read(access: PrivateAccess, noteId: PrivateNoteId): PrivateNoteDocument? {
    requireCurrent(access)
    return store.readSnapshot(access).notes.firstOrNull { it.id == noteId && it.deletedAtMillis == null }
  }

  suspend fun authorizeExport(
    access: PrivateAccess,
    password: CharArray,
    scope: PrivateExportScope,
    nowMillis: Long = System.currentTimeMillis(),
  ): PrivateExportGrant? {
    try {
      requireCurrent(access)
      if (!store.verifyPassword(access, password)) return null
      requireCurrent(access)
      return PrivateExportGrant.issue(access, scope, issuedAtMillis = nowMillis)
    } finally {
      MemoryWipe.chars(password)
    }
  }

  suspend fun listPrivateExportHistory(access: PrivateAccess): List<PrivateExportHistoryRecord> {
    requireCurrent(access)
    return store.readSnapshot(access).exportHistory
      .sortedWith(compareByDescending<PrivateExportHistoryRecord> { it.createdAtMillis }.thenByDescending { it.id })
      .take(PRIVATE_EXPORT_HISTORY_LIMIT)
  }

  suspend fun recordPrivateExport(access: PrivateAccess, record: PrivateExportHistoryRecord) = mutate(access) { vault ->
    val history = (vault.exportHistory.filterNot { it.id == record.id } + record)
      .sortedWith(compareByDescending<PrivateExportHistoryRecord> { it.createdAtMillis }.thenByDescending { it.id })
      .take(PRIVATE_EXPORT_HISTORY_LIMIT)
    vault.copy(exportHistory = history) to Unit
  }

  suspend fun listPreviews(access: PrivateAccess): List<PrivateNotePreview> {
    requireCurrent(access)
    return store.readSnapshot(access).notes
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .sortedWith(compareByDescending<PrivateNoteDocument> { it.isPinned }.thenByDescending { it.updatedAtMillis })
      .map { PrivateNotePreview(it.id, it.title, it.updatedAtMillis, it.folderId) }
      .toList()
  }

  suspend fun listTodos(access: PrivateAccess, includeCompleted: Boolean = false): List<PrivateTodoItem> {
    requireCurrent(access)
    return store.readSnapshot(access).notes
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .flatMap { note ->
        note.todos.asSequence()
          .filter { it.text.isNotBlank() }
          .filter { includeCompleted || !it.completed }
          .map { todo ->
            PrivateTodoItem(
              noteId = note.id,
              noteTitle = note.title,
              todoId = todo.id,
              text = todo.text,
              completed = todo.completed,
              updatedAtMillis = todo.updatedAtMillis,
            )
          }
      }
      .sortedByDescending(PrivateTodoItem::updatedAtMillis)
      .toList()
  }

  suspend fun setTodoCompleted(
    access: PrivateAccess,
    noteId: PrivateNoteId,
    todoId: String,
    completed: Boolean,
  ): Boolean = mutate(access) { vault ->
    val noteIndex = vault.notes.indexOfFirst { it.id == noteId && it.deletedAtMillis == null }
    if (noteIndex < 0) return@mutate vault to false
    val note = vault.notes[noteIndex]
    val todoIndex = note.todos.indexOfFirst { it.id == todoId }
    if (todoIndex < 0) return@mutate vault to false
    val todo = note.todos[todoIndex]
    if (todo.completed == completed) return@mutate vault to true

    val now = nowMillis()
    val updatedTodos = note.todos.toMutableList().also { todos ->
      todos[todoIndex] = todo.copy(completed = completed, updatedAtMillis = now)
    }
    val updatedNote = note.copy(
      todos = updatedTodos,
      updatedAtMillis = now,
      contentRevision = note.contentRevision + 1L,
    )
    vault.copy(notes = vault.notes.mapIndexed { index, item -> if (index == noteIndex) updatedNote else item }) to true
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun observePreviews(access: PrivateAccess): Flow<List<PrivateNotePreview>> = generation.mapLatest {
    if (!access.isActive) emptyList() else listPreviews(access)
  }

  suspend fun save(access: PrivateAccess, snapshot: PrivateEditSnapshot): PrivateSaveResult = mutationMutex.withLock {
    requireCurrent(access)
    store.mutateSnapshot(access) { vault ->
      snapshot.blocks.filter { it.type == BlockType.IMAGE }.forEach { block ->
        val reference = block.assetPath
        require(!reference.isNullOrBlank() && store.hasPrivateAsset(access, reference)) {
          "Private image blocks must reference an encrypted private asset"
        }
      }
      val existing = snapshot.noteId?.let { noteId -> vault.notes.firstOrNull { it.id == noteId } }
      if (snapshot.noteId != null && existing == null) {
        return@mutateSnapshot vault to PrivateSaveResult.Conflict(0L)
      }
      if (existing != null && snapshot.baseContentRevision != existing.contentRevision) {
        return@mutateSnapshot vault to PrivateSaveResult.Conflict(existing.contentRevision)
      }
      if (existing == null && isBlank(snapshot)) return@mutateSnapshot vault to PrivateSaveResult.EmptyNotSaved

      val noteId = existing?.id ?: PrivateNoteId(newId())
      val now = nowMillis()
      val updatedTodos = todosFrom(snapshot.blocks, existing?.todos.orEmpty(), now)
      val saved = PrivateNoteDocument(
        id = noteId,
        folderId = snapshot.folderId.ifBlank { PRIVATE_UNCATEGORIZED_FOLDER_ID },
        title = snapshot.title,
        titleManuallySet = snapshot.titleManuallySet,
        blocks = snapshot.blocks.map(::privateBlock),
        todos = updatedTodos,
        skinId = snapshot.skinId,
        fontSizeSpOverride = snapshot.fontSizeSpOverride,
        lineSpacingMultiplierOverride = snapshot.lineSpacingMultiplierOverride,
        createdAtMillis = existing?.createdAtMillis ?: now,
        updatedAtMillis = now,
        isPinned = existing?.isPinned ?: false,
        deletedAtMillis = existing?.deletedAtMillis,
        contentRevision = (existing?.contentRevision ?: 0L) + 1L,
      )
      val notes = if (existing == null) vault.notes + saved else vault.notes.map { if (it.id == noteId) saved else it }
      vault.copy(notes = notes) to PrivateSaveResult.Saved(noteId, saved.contentRevision)
    }.also { generation.value += 1 }
  }

  suspend fun search(access: PrivateAccess, query: String): List<PrivateNoteId> {
    requireCurrent(access)
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) return emptyList()
    return store.readSnapshot(access).notes
      .asSequence()
      .filter { it.deletedAtMillis == null }
      .filter { note ->
        sequenceOf(note.title)
          .plus(note.blocks.asSequence().mapNotNull(NoteBlockDraft::text))
          .plus(note.todos.asSequence().map(PrivateTodo::text))
          .any { it.lowercase().contains(normalized) }
      }
      .map(PrivateNoteDocument::id)
      .toList()
  }

  suspend fun moveToTrash(access: PrivateAccess, noteId: PrivateNoteId) = mutate(access) { vault ->
    val notes = vault.notes.map { note ->
      if (note.id == noteId && note.deletedAtMillis == null) note.copy(deletedAtMillis = nowMillis()) else note
    }
    vault.copy(notes = notes) to Unit
  }

  suspend fun restore(access: PrivateAccess, noteId: PrivateNoteId) = mutate(access) { vault ->
    val notes = vault.notes.map { note ->
      if (note.id == noteId) note.copy(deletedAtMillis = null) else note
    }
    vault.copy(notes = notes) to Unit
  }

  suspend fun purgeExpiredTrash(access: PrivateAccess, now: Long = nowMillis()): List<PrivateNoteId> =
    mutate(access) { vault ->
      val cutoff = now - TRASH_RETENTION_MILLIS
      val expired = vault.notes.filter { it.deletedAtMillis != null && it.deletedAtMillis <= cutoff }.map { it.id }
      vault.copy(notes = vault.notes.filterNot { it.id in expired }) to expired
    }

  suspend fun writeAtomic(access: PrivateAccess, snapshot: PrivateEditSnapshot) = mutate(access) { vault ->
    val retained = vault.drafts.filterNot { it.draftId == snapshot.draftId }
    vault.copy(drafts = retained + snapshot) to Unit
  }

  suspend fun listRecoverable(access: PrivateAccess): List<PrivateEditSnapshot> {
    requireCurrent(access)
    return store.readSnapshot(access).drafts
  }

  suspend fun clearIfAtMost(access: PrivateAccess, draftId: String, revision: Long) = mutate(access) { vault ->
    val nextDrafts = vault.drafts.filterNot { it.draftId == draftId && it.localRevision <= revision }
    vault.copy(drafts = nextDrafts) to Unit
  }

  /** Updates OCR only inside the authenticated private snapshot; successful output releases its encrypted source reference. */
  suspend fun updatePrivateOcrBlock(
    access: PrivateAccess,
    blockId: String,
    status: OcrStatus,
    text: String? = null,
  ): String? {
    require(status != OcrStatus.RUNNING && status != OcrStatus.NOT_REQUESTED) { "Invalid private OCR status" }
    val normalizedText = if (status == OcrStatus.SUCCEEDED) {
      OcrJobRules.normalizeText(text.orEmpty()) ?: throw IllegalArgumentException("Private OCR result is empty")
    } else null
    return mutate(access) { vault ->
      var releasedAsset: String? = null
      var changed = false
      fun updateBlock(block: NoteBlockDraft): NoteBlockDraft {
        if (block.id != blockId || block.type != BlockType.OCR_TEXT) return block
        val allowed = when (status) {
          OcrStatus.PENDING -> block.ocrStatus == OcrStatus.FAILED || block.ocrStatus == OcrStatus.CANCELLED
          OcrStatus.SUCCEEDED, OcrStatus.FAILED, OcrStatus.CANCELLED -> block.ocrStatus == OcrStatus.PENDING
          else -> false
        }
        if (!allowed) return block
        changed = true
        if (status == OcrStatus.SUCCEEDED) releasedAsset = block.assetPath ?: releasedAsset
        return block.copy(
          text = normalizedText,
          assetPath = if (status == OcrStatus.SUCCEEDED) null else block.assetPath,
          ocrStatus = status,
        )
      }
      val now = nowMillis()
      val notes = vault.notes.map { note ->
        val blocks = note.blocks.map(::updateBlock)
        if (blocks == note.blocks) note else note.copy(
          blocks = blocks,
          updatedAtMillis = now,
          contentRevision = note.contentRevision + 1L,
        )
      }
      val drafts = vault.drafts.map { draft ->
        val blocks = draft.blocks.map(::updateBlock)
        if (blocks == draft.blocks) draft else draft.copy(
          blocks = blocks,
          localRevision = draft.localRevision + 1L,
          savedAtMillis = now,
        )
      }
      val next = if (changed) vault.copy(notes = notes, drafts = drafts) else vault
      next to releasedAsset
    }
  }

  private suspend fun <T> mutate(access: PrivateAccess, transform: (PrivateVaultSnapshot) -> Pair<PrivateVaultSnapshot, T>): T =
    mutationMutex.withLock {
      requireCurrent(access)
      store.mutateSnapshot(access, transform).also { generation.value += 1 }
    }

  private fun requireCurrent(access: PrivateAccess) {
    check(access.isActive && synchronized(sessionMonitor) { activeAccess === access }) {
      "Private access has been revoked"
    }
  }

  private fun isBlank(snapshot: PrivateEditSnapshot): Boolean =
    snapshot.title.isBlank() && snapshot.blocks.all { it.text.isNullOrBlank() && it.assetPath == null }

  private fun privateBlock(block: NoteBlockDraft): NoteBlockDraft {
    require(block.previewPath == null) { "Private preview files are not supported by the private vault" }
    if (block.type == BlockType.IMAGE) {
      require(!block.assetPath.isNullOrBlank() && block.assetPath.startsWith(PRIVATE_ASSET_PREFIX)) {
        "Private image blocks must reference encrypted private assets"
      }
    }
    return block.copy(previewPath = null)
  }

  private fun todosFrom(blocks: List<NoteBlockDraft>, previous: List<PrivateTodo>, now: Long): List<PrivateTodo> {
    val previousByBlock = previous.associateBy(PrivateTodo::blockId)
    return blocks.asSequence()
      .filter { it.type == BlockType.TODO }
      .map { block ->
        val old = previousByBlock[block.id]
        PrivateTodo(
          id = old?.id ?: block.id,
          blockId = block.id,
          text = block.text.orEmpty(),
          completed = old?.completed ?: false,
          updatedAtMillis = now,
        )
      }
      .toList()
  }

  private companion object {
    const val PRIVATE_EXPORT_HISTORY_LIMIT = 100
    const val PRIVATE_UNCATEGORIZED_FOLDER_ID = "private-uncategorized"
    const val PRIVATE_ASSET_PREFIX = "private-asset:"
  }
}

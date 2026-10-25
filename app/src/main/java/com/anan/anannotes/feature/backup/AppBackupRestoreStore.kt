package com.anan.anannotes.feature.backup

import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingCodec
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.BlockEntity
import com.anan.anannotes.data.local.FolderEntity
import com.anan.anannotes.data.local.NoteEntity
import com.anan.anannotes.data.local.NoteSearchFtsEntity
import com.anan.anannotes.data.local.TodoEntity
import com.anan.anannotes.feature.editor.EditorDraftRestoreTransactionStore
import com.anan.anannotes.feature.editor.EditorDraftSnapshot
import com.anan.anannotes.feature.editor.EditorDraftStore
import com.anan.anannotes.feature.privatemodule.PrivateEditSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateNoteDocument
import com.anan.anannotes.feature.privatemodule.PrivateNoteId
import com.anan.anannotes.feature.privatemodule.PrivateStore
import com.anan.anannotes.feature.privatemodule.PrivateTodo
import com.anan.anannotes.feature.privatemodule.PrivateVaultRestoreSession
import com.anan.anannotes.feature.privatemodule.PrivateVaultSnapshot
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordKind
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordPayloadCodec
import com.anan.anannotes.security.BackupRecordSetSummary
import com.anan.anannotes.security.BackupRecordStreamSummary
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.TypedBackupRecordStore
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future

/**
 * Concrete two-domain restore target. Ordinary Room rows and search documents commit with Room's
 * marker; original images and ordinary drafts are transaction-owned. Private rows, drafts and
 * attachments are merged into a snapshot encrypted by the destination DEK before publication.
 */
internal class AppBackupRestoreStore(
  private val database: AnAnDatabase,
  private val ordinaryDraftStore: EditorDraftStore,
  private val ordinaryDraftTransactions: EditorDraftRestoreTransactionStore,
  private val originalImages: OriginalImageStore,
  private val privateStore: PrivateStore,
  private val currentPrivateAccess: PrivateAccess?,
  private var newPrivatePassword: CharArray?,
  private var newPrivateRecoveryKey: CharArray?,
  private val previewSummary: BackupRecordSetSummary,
  private val verifyCurrentTarget: suspend () -> Unit,
  private val createCommitProtocol: (
    transactionId: String,
    includesPrivateSnapshot: Boolean,
    verifyTargetUnchanged: suspend () -> Unit,
    applyRoomRows: suspend () -> Unit,
  ) -> BackupRestoreCommitProtocol,
) : ConflictAwareBackupRestoreStore {
  private val records = ArrayList<BackupRecordEnvelope>()
  private val assetPipes = linkedMapOf<AssetKey, PendingAsset>()
  private val restoredAssetPaths = linkedMapOf<AssetKey, String>()
  private val assetExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "anan-backup-asset-restore").apply { isDaemon = true }
  }
  private var ordinaryRecordBytes = 0L
  private var privateRecordBytes = 0L
  private var resolution: ResolvedBackupConflictPlan? = null
  private var transactionId: String? = null
  private var commitProtocol: BackupRestoreCommitProtocol? = null
  private var privateSession: PrivateVaultRestoreSession? = null
  private var previousPrivateSnapshot: PrivateVaultSnapshot? = null
  private var terminal = false

  override fun prepareConflictResolution(resolution: ResolvedBackupConflictPlan) {
    check(this.resolution == null && !terminal) { "Restore target is already prepared" }
    this.resolution = resolution
    val includesPrivate = previewSummary.privateNotes + previewSummary.privateBlocks + previewSummary.privateTodos +
      previewSummary.privateDrafts + previewSummary.privateDraftBlocks + previewSummary.privateAssets > 0L
    val id = UUID.randomUUID().toString()
    transactionId = id
    val protocol = createCommitProtocol(id, includesPrivate, verifyCurrentTarget) { applyOrdinaryRows() }
    commitProtocol = protocol
    try {
      runBlocking {
        protocol.begin()
        if (includesPrivate) {
          val configured = privateStore.isConfigured()
          if (configured) {
            val access = checkNotNull(currentPrivateAccess) { "请先解锁目标私密库" }
            previousPrivateSnapshot = privateStore.readSnapshot(access)
            privateSession = privateStore.beginRestoreVault(id, access, null, null)
            MemoryWipe.chars(newPrivatePassword)
            MemoryWipe.chars(newPrivateRecoveryKey)
            newPrivatePassword = null
            newPrivateRecoveryKey = null
          } else {
            check(currentPrivateAccess == null) { "私密库状态已变化，请重新检查备份" }
            previousPrivateSnapshot = PrivateVaultSnapshot()
            privateSession = privateStore.beginRestoreVault(
              id,
              existingAccess = null,
              newPassword = newPrivatePassword,
              newRecoveryKey = newPrivateRecoveryKey,
            )
            newPrivatePassword = null
            newPrivateRecoveryKey = null
          }
        } else {
          MemoryWipe.chars(newPrivatePassword)
          MemoryWipe.chars(newPrivateRecoveryKey)
          newPrivatePassword = null
          newPrivateRecoveryKey = null
        }
      }
    } catch (failure: Throwable) {
      runCatching { runBlocking { protocol.abort() } }.exceptionOrNull()?.let(failure::addSuppressed)
      privateSession?.close()
      privateSession = null
      terminal = true
      throw failure
    }
  }

  @Synchronized
  override fun onRecord(domain: BackupRecordDomain, payload: BackupRecordPayload) {
    check(resolution != null && !terminal) { "Restore target is not active" }
    if (payload is BackupRecordPayload.Asset) {
      val key = AssetKey(domain, payload.assetId)
      val pending = assetPipes[key] ?: throw IOException("Backup attachment stream is missing")
      try {
        pending.output.close()
        restoredAssetPaths[key] = awaitAsset(pending)
        assetPipes.remove(key)
      } finally {
        MemoryWipe.bytes(payload.sha256)
        runCatching { pending.input.close() }
      }
      return
    }

    val encoded = BackupRecordPayloadCodec.encode(domain, payload)
    try {
      if (domain == BackupRecordDomain.ORDINARY) {
        ordinaryRecordBytes = checkedAdd(ordinaryRecordBytes, encoded.size.toLong())
        if (ordinaryRecordBytes > MAX_ORDINARY_RECORD_BYTES) throw IOException("普通笔记数据超过本机安全恢复容量")
      } else {
        privateRecordBytes = checkedAdd(privateRecordBytes, encoded.size.toLong())
        if (privateRecordBytes > MAX_PRIVATE_RECORD_BYTES) throw IOException("私密笔记数据超过私密库容量上限")
      }
      records += BackupRecordEnvelope(domain, payload)
    } finally {
      MemoryWipe.bytes(encoded)
    }
  }

  @Synchronized
  override fun onAssetBytes(
    domain: BackupRecordDomain,
    asset: BackupRecordPayload.Asset,
    bytes: ByteArray,
    offset: Int,
    length: Int,
  ) {
    check(resolution != null && !terminal) { "Restore target is not active" }
    val key = AssetKey(domain, asset.assetId)
    val pending = assetPipes[key] ?: createAssetPipe(key, asset).also { assetPipes[key] = it }
    try {
      pending.output.write(bytes, offset, length)
    } catch (failure: Throwable) {
      pending.future.cancel(true)
      throw failure
    }
  }

  override fun commit(streamSummary: BackupRecordStreamSummary, recordSetSummary: BackupRecordSetSummary) {
    check(resolution != null && !terminal && assetPipes.isEmpty()) { "Restore target is not ready to commit" }
    check(recordSetSummary == previewSummary) { "Authenticated backup record counts changed after inspection" }
    val id = checkNotNull(transactionId)
    val protocol = checkNotNull(commitProtocol)
    try {
      runBlocking {
        val currentDraftIds = ordinaryDraftStore.listRecoverable().mapTo(hashSetOf(), EditorDraftSnapshot::draftId)
        val draftFolderMapping = planFolders(database.folderDao().getAll()).sourceToTargetIds
        val draftResult = buildOrdinaryDrafts(currentDraftIds, draftFolderMapping)
        val mergedPrivate = privateSession?.let { buildPrivateSnapshot(checkNotNull(previousPrivateSnapshot)) }
        ordinaryDraftTransactions.stage(id, draftResult.snapshots)
        if (mergedPrivate != null) {
          privateStore.stageRestoreVaultSnapshot(id, checkNotNull(privateSession).access, mergedPrivate.snapshot)
        }
        pruneUnusedAssets(draftResult.retainedAssetPaths, mergedPrivate?.retainedAssetPaths.orEmpty())
        protocol.commit()
      }
      terminal = true
      privateSession?.close()
      privateSession = null
      stopAssetWorkers()
      wipeRecords()
      previousPrivateSnapshot = null
    } catch (failure: Throwable) {
      throw failure
    }
  }

  override fun abort() {
    if (terminal) return
    var failure = stopAssetWorkers()
    if (failure == null) {
      try {
        commitProtocol?.let { runBlocking { it.abort() } }
      } catch (next: Throwable) {
        failure = next
      }
    }
    try {
      privateSession?.close()
    } catch (next: Throwable) {
      val current = failure
      if (current == null) failure = next else current.addSuppressed(next)
    }
    privateSession = null
    MemoryWipe.chars(newPrivatePassword)
    MemoryWipe.chars(newPrivateRecoveryKey)
    newPrivatePassword = null
    newPrivateRecoveryKey = null
    wipeRecords()
    previousPrivateSnapshot = null
    terminal = true
    failure?.let { throw it }
  }

  private fun createAssetPipe(key: AssetKey, asset: BackupRecordPayload.Asset): PendingAsset {
    val input = PipedInputStream(ASSET_PIPE_BYTES)
    val output = PipedOutputStream(input)
    val digest = asset.sha256.clone()
    val future = try {
      assetExecutor.submit<String> {
        try {
          input.use { stream ->
            when (key.domain) {
              BackupRecordDomain.ORDINARY -> {
                val deterministicId = stableId("ordinary-asset:${key.id}:${transactionId}")
                originalImages.importRestore(
                  transactionId = checkNotNull(transactionId),
                  assetId = deterministicId,
                  input = stream,
                  extension = extensionForMime(asset.mimeType),
                  expectedByteCount = asset.byteCount,
                  expectedSha256 = digest,
                ).relativePath
              }
              BackupRecordDomain.PRIVATE -> runBlocking {
                privateStore.writePrivateAssetFromStream(
                  access = checkNotNull(privateSession) { "私密库恢复授权已失效" }.access,
                  source = stream,
                  expectedByteCount = asset.byteCount,
                  expectedSha256 = digest,
                  restoreTransactionId = checkNotNull(transactionId),
                )
              }
            }
          }
        } finally {
          MemoryWipe.bytes(digest)
        }
      }
    } catch (failure: Throwable) {
      MemoryWipe.bytes(digest)
      runCatching { output.close() }
      runCatching { input.close() }
      throw failure
    }
    return PendingAsset(input, output, future)
  }

  private fun awaitAsset(pending: PendingAsset): String = try {
    pending.future.get()
  } catch (failure: ExecutionException) {
    throw failure.cause ?: failure
  } catch (failure: InterruptedException) {
    Thread.currentThread().interrupt()
    throw IOException("恢复附件时被中断", failure)
  }

  private suspend fun applyOrdinaryRows() {
    val folders = database.folderDao().getAll()
    val notes = database.noteDao().getAll()
    val blocks = database.blockDao().getAll()
    val todos = database.todoDao().getAll()
    val folderPlan = planFolders(folders)
    folderPlan.rowsToUpsert.forEach { database.folderDao().upsert(it) }
    val imported = buildOrdinaryNoteRows(notes, blocks, todos, folderPlan.sourceToTargetIds)

    imported.replacedNoteIds.forEach { noteId ->
      database.todoDao().deleteByNote(noteId)
      database.blockDao().deleteByNote(noteId)
      database.noteSearchDao().deleteByNote(noteId)
    }
    imported.notes.forEach { database.noteDao().upsert(it) }
    if (imported.blocks.isNotEmpty()) database.blockDao().upsertAll(imported.blocks)
    if (imported.todos.isNotEmpty()) database.todoDao().upsertAll(imported.todos)
    imported.searchDocuments.forEach { database.noteSearchDao().insert(it) }
  }

  private fun planFolders(current: List<FolderEntity>): FolderRestorePlan {
    val incoming = records.asSequence().filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.Folder }.sortedBy(BackupRecordPayload.Folder::id).toList()
    val currentById = current.associateBy(FolderEntity::id)
    val occupiedNames = current.mapTo(hashSetOf()) { normalizeFolderName(it.name) }
    val mappings = linkedMapOf<String, String>()
    val toUpsert = mutableListOf<FolderEntity>()
    for (folder in incoming) {
      val key = BackupObjectKey(BackupRecordDomain.ORDINARY, BackupRecordKind.FOLDER, folder.id)
      val action = actionFor(key)
      val existing = currentById[folder.id]
      when {
        folder.id == UNCATEGORIZED_FOLDER_ID -> mappings[folder.id] = UNCATEGORIZED_FOLDER_ID
        action == MergeAction.SKIP -> {
          val retained = existing ?: current.firstOrNull { normalizeFolderName(it.name) == normalizeFolderName(folder.name) }
            ?: throw IllegalStateException("备份中的文件夹已不存在，请重新检查")
          mappings[folder.id] = retained.id
        }
        action == MergeAction.KEEP_BOTH -> {
          val duplicateId = stableId("folder-copy:${folder.id}")
          val duplicate = currentById[duplicateId]
          val name = duplicate?.name ?: uniqueRestoreCopyName(folder.name, occupiedNames)
          mappings[folder.id] = duplicateId
          if (duplicate == null) {
            occupiedNames += normalizeFolderName(name)
            toUpsert += FolderEntity(duplicateId, name, folder.kind.name, folder.createdAtMillis, folder.sortPosition)
          }
        }
        existing == null && current.firstOrNull { normalizeFolderName(it.name) == normalizeFolderName(folder.name) } != null -> {
          mappings[folder.id] = checkNotNull(current.firstOrNull { normalizeFolderName(it.name) == normalizeFolderName(folder.name) }).id
        }
        else -> {
          val otherWithName = current.firstOrNull {
            it.id != folder.id && normalizeFolderName(it.name) == normalizeFolderName(folder.name)
          }
          val name = if (otherWithName == null) folder.name else uniqueRestoreCopyName(folder.name, occupiedNames)
          existing?.let { occupiedNames.remove(normalizeFolderName(it.name)) }
          occupiedNames += normalizeFolderName(name)
          mappings[folder.id] = folder.id
          toUpsert += FolderEntity(folder.id, name, folder.kind.name, folder.createdAtMillis, folder.sortPosition)
        }
      }
    }
    return FolderRestorePlan(mappings, toUpsert)
  }

  private fun buildOrdinaryNoteRows(
    currentNotes: List<NoteEntity>,
    currentBlocks: List<BlockEntity>,
    currentTodos: List<TodoEntity>,
    folderMapping: Map<String, String>,
  ): OrdinaryRows {
    val notePayloads = records.filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.Note }
    val blockPayloads = records.filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.Block }.groupBy(BackupRecordPayload.Block::noteId)
    val todoPayloads = records.filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.Todo }.groupBy(BackupRecordPayload.Todo::noteId)
    val existingNotes = currentNotes.associateBy(NoteEntity::id)
    val importedNotes = mutableListOf<NoteEntity>()
    val importedBlocks = mutableListOf<BlockEntity>()
    val importedTodos = mutableListOf<TodoEntity>()
    val replacedIds = linkedSetOf<String>()
    val searchDocuments = mutableListOf<NoteSearchFtsEntity>()
    val targetNoteIds = linkedMapOf<String, String>()
    val selectedNotes = notePayloads.filter { payload -> actionFor(noteKey(payload.id)) != MergeAction.SKIP }
    selectedNotes.forEach { note ->
      targetNoteIds[note.id] = if (actionFor(noteKey(note.id)) == MergeAction.KEEP_BOTH) stableId("note-copy:${note.id}") else note.id
    }
    val replacementIds = targetNoteIds.values.filterTo(hashSetOf()) { targetId -> existingNotes.containsKey(targetId) }
    val usedBlockIds = currentBlocks.filterNot { it.noteId in replacementIds }.mapTo(hashSetOf(), BlockEntity::id)
    val usedTodoIds = currentTodos.filterNot { it.noteId in replacementIds }.mapTo(hashSetOf(), TodoEntity::id)
    val usedImportIds = currentNotes.filterNot { it.id in replacementIds }.mapNotNullTo(hashSetOf(), NoteEntity::importRequestId)

    for (payload in selectedNotes.sortedBy(BackupRecordPayload.Note::id)) {
      val targetNoteId = checkNotNull(targetNoteIds[payload.id])
      val clone = targetNoteId != payload.id
      val targetFolderId = folderMapping[payload.folderId] ?: throw IllegalStateException("备份引用的文件夹无法恢复")
      val importId = if (clone) null else payload.importRequestId?.takeIf(usedImportIds::add)
      val noteEntity = NoteEntity(
        id = targetNoteId,
        folderId = targetFolderId,
        title = payload.title,
        titleManuallySet = payload.titleManuallySet,
        createdAtMillis = payload.createdAtMillis,
        updatedAtMillis = payload.updatedAtMillis,
        skinId = payload.skinId,
        fontSizeSpOverride = payload.fontSizeSpOverride,
        lineSpacingMultiplierOverride = payload.lineSpacingMultiplierOverride,
        isPinned = payload.isPinned,
        deletedAtMillis = payload.deletedAtMillis,
        contentRevision = payload.contentRevision,
        importRequestId = importId,
      )
      importedNotes += noteEntity
      if (targetNoteId in existingNotes) replacedIds += targetNoteId

      val blockIdMapping = linkedMapOf<String, String>()
      val noteBlocks = blockPayloads[payload.id].orEmpty().sortedBy(BackupRecordPayload.Block::position).map { block ->
        val preferredId = if (clone) stableId("block-copy:${payload.id}:${block.id}") else block.id
        val localId = allocateId(preferredId, usedBlockIds, "block:${payload.id}:${block.id}")
        blockIdMapping[block.id] = localId
        BlockEntity(
          id = localId,
          noteId = targetNoteId,
          position = block.position,
          type = block.type.name,
          text = block.text,
          assetPath = block.assetRef?.let { requireAssetPath(BackupRecordDomain.ORDINARY, it) },
          previewPath = null,
          widthPercent = block.widthPercent,
          alignment = block.alignment.name,
          aspectRatio = block.aspectRatio,
          ocrStatus = block.ocrStatus?.name,
          formattingBlob = block.formattingBlob?.clone(),
        )
      }
      importedBlocks += noteBlocks
      importedTodos += todoPayloads[payload.id].orEmpty().sortedBy(BackupRecordPayload.Todo::id).map { todo ->
        val preferredId = if (clone) stableId("todo-copy:${payload.id}:${todo.id}") else todo.id
        val localId = allocateId(preferredId, usedTodoIds, "todo:${payload.id}:${todo.id}")
        TodoEntity(
          id = localId,
          noteId = targetNoteId,
          blockId = blockIdMapping[todo.blockId] ?: throw IllegalStateException("待办引用的内容块无法恢复"),
          text = todo.text,
          isCompleted = todo.isCompleted,
          updatedAtMillis = todo.updatedAtMillis,
        )
      }
      if (payload.deletedAtMillis == null) {
        val content = noteBlocks.asSequence()
          .filter { it.type in SEARCHABLE_BLOCK_TYPES }
          .filter { it.type != BlockType.OCR_TEXT.name || it.ocrStatus == OcrStatus.SUCCEEDED.name }
          .mapNotNull { it.text?.takeIf(String::isNotBlank) }
          .joinToString("\n")
        searchDocuments += NoteSearchFtsEntity(targetNoteId, payload.title, content)
      }
    }
    return OrdinaryRows(importedNotes, importedBlocks, importedTodos, replacedIds, searchDocuments)
  }

  private fun buildOrdinaryDrafts(
    existingDraftIds: Set<String>,
    folderMapping: Map<String, String>,
  ): DraftRestoreResult {
    val draftPayloads = records.filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.Draft }
    val draftBlocks = records.filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.DraftBlock }.groupBy(BackupRecordPayload.DraftBlock::draftId)
    val snapshots = draftPayloads.filterNot { it.draftId in existingDraftIds }.map { draft ->
      val folderId = folderMapping[draft.folderId] ?: throw IllegalStateException("草稿引用的文件夹无法恢复")
      val noteId = draft.noteId?.let { sourceId ->
        if (actionFor(noteKey(sourceId, BackupRecordDomain.ORDINARY)) == MergeAction.KEEP_BOTH) stableId("note-copy:$sourceId") else sourceId
      }
      val blocks = draftBlocks[draft.draftId].orEmpty().sortedBy(BackupRecordPayload.DraftBlock::position).map { block ->
        NoteBlockDraft(
          id = block.blockId,
          type = block.type,
          text = block.text,
          assetPath = block.assetRef?.let { requireAssetPath(BackupRecordDomain.ORDINARY, it) },
          previewPath = null,
          widthPercent = block.widthPercent,
          alignment = block.alignment,
          aspectRatio = block.aspectRatio,
          ocrStatus = block.ocrStatus,
          formatting = decodeFormatting(block.formattingBlob, block.text, block.type),
        )
      }
      EditorDraftSnapshot(
        draftId = draft.draftId,
        noteId = noteId,
        folderId = folderId,
        baseContentRevision = draft.baseContentRevision ?: 0L,
        localRevision = draft.localRevision,
        savedAtMillis = draft.savedAtMillis,
        importRequestId = draft.importRequestId,
        draft = NoteDraft(
          folderId = folderId,
          title = draft.title,
          titleManuallySet = draft.titleManuallySet,
          blocks = blocks,
          skinId = draft.skinId,
          fontSizeSpOverride = draft.fontSizeSpOverride,
          lineSpacingMultiplierOverride = draft.lineSpacingMultiplierOverride,
        ),
      )
    }
    val retainedPaths = snapshots.flatMapTo(hashSetOf()) { snapshot ->
      snapshot.draft.blocks.mapNotNull(NoteBlockDraft::assetPath)
    }
    return DraftRestoreResult(snapshots, retainedPaths)
  }

  private fun buildPrivateSnapshot(current: PrivateVaultSnapshot): PrivateSnapshotResult {
    val incomingNotes = records.filter { it.domain == BackupRecordDomain.PRIVATE }
      .mapNotNull { it.payload as? BackupRecordPayload.Note }
    val incomingBlocks = records.filter { it.domain == BackupRecordDomain.PRIVATE }
      .mapNotNull { it.payload as? BackupRecordPayload.Block }.groupBy(BackupRecordPayload.Block::noteId)
    val incomingTodos = records.filter { it.domain == BackupRecordDomain.PRIVATE }
      .mapNotNull { it.payload as? BackupRecordPayload.Todo }.groupBy(BackupRecordPayload.Todo::noteId)
    val notesById = current.notes.associateBy { it.id.value }.toMutableMap()
    for (note in incomingNotes.sortedBy(BackupRecordPayload.Note::id)) {
      val key = BackupObjectKey(BackupRecordDomain.PRIVATE, BackupRecordKind.NOTE, note.id)
      val action = actionFor(key)
      if (action == MergeAction.SKIP) continue
      val targetId = if (action == MergeAction.KEEP_BOTH) stableId("private-note-copy:${note.id}") else note.id
      val clone = targetId != note.id
      val blockIdMap = linkedMapOf<String, String>()
      val noteBlocks = incomingBlocks[note.id].orEmpty().sortedBy(BackupRecordPayload.Block::position).map { block ->
        val newId = if (clone) stableId("private-block-copy:${note.id}:${block.id}") else block.id
        blockIdMap[block.id] = newId
        NoteBlockDraft(
          id = newId,
          type = block.type,
          text = block.text,
          assetPath = block.assetRef?.let { requireAssetPath(BackupRecordDomain.PRIVATE, it) },
          previewPath = null,
          widthPercent = block.widthPercent,
          alignment = block.alignment,
          aspectRatio = block.aspectRatio,
          ocrStatus = block.ocrStatus,
          formatting = decodeFormatting(block.formattingBlob, block.text, block.type),
        )
      }
      val noteTodos = incomingTodos[note.id].orEmpty().sortedBy(BackupRecordPayload.Todo::id).map { todo ->
        PrivateTodo(
          id = if (clone) stableId("private-todo-copy:${note.id}:${todo.id}") else todo.id,
          blockId = blockIdMap[todo.blockId] ?: throw IllegalStateException("私密待办引用的内容块无法恢复"),
          text = todo.text,
          completed = todo.isCompleted,
          updatedAtMillis = todo.updatedAtMillis,
        )
      }
      notesById[targetId] = PrivateNoteDocument(
        id = PrivateNoteId(targetId),
        folderId = note.folderId,
        title = note.title,
        titleManuallySet = note.titleManuallySet,
        blocks = noteBlocks,
        todos = noteTodos,
        skinId = note.skinId,
        fontSizeSpOverride = note.fontSizeSpOverride,
        lineSpacingMultiplierOverride = note.lineSpacingMultiplierOverride,
        createdAtMillis = note.createdAtMillis,
        updatedAtMillis = note.updatedAtMillis,
        isPinned = note.isPinned,
        deletedAtMillis = note.deletedAtMillis,
        contentRevision = note.contentRevision,
      )
    }

    val existingDraftIds = current.drafts.mapTo(hashSetOf(), PrivateEditSnapshot::draftId)
    val draftPayloads = records.filter { it.domain == BackupRecordDomain.PRIVATE }
      .mapNotNull { it.payload as? BackupRecordPayload.Draft }
    val draftBlocks = records.filter { it.domain == BackupRecordDomain.PRIVATE }
      .mapNotNull { it.payload as? BackupRecordPayload.DraftBlock }.groupBy(BackupRecordPayload.DraftBlock::draftId)
    val addedDrafts = draftPayloads.filterNot { it.draftId in existingDraftIds }.map { draft ->
      PrivateEditSnapshot(
        draftId = draft.draftId,
        noteId = draft.noteId?.let { sourceId ->
          val noteAction = actionFor(noteKey(sourceId, BackupRecordDomain.PRIVATE))
          PrivateNoteId(if (noteAction == MergeAction.KEEP_BOTH) stableId("private-note-copy:$sourceId") else sourceId)
        },
        folderId = draft.folderId,
        title = draft.title,
        titleManuallySet = draft.titleManuallySet,
        blocks = draftBlocks[draft.draftId].orEmpty().sortedBy(BackupRecordPayload.DraftBlock::position).map { block ->
          NoteBlockDraft(
            id = block.blockId,
            type = block.type,
            text = block.text,
            assetPath = block.assetRef?.let { requireAssetPath(BackupRecordDomain.PRIVATE, it) },
            previewPath = null,
            widthPercent = block.widthPercent,
            alignment = block.alignment,
            aspectRatio = block.aspectRatio,
            ocrStatus = block.ocrStatus,
            formatting = decodeFormatting(block.formattingBlob, block.text, block.type),
          )
        },
        skinId = draft.skinId,
        fontSizeSpOverride = draft.fontSizeSpOverride,
        lineSpacingMultiplierOverride = draft.lineSpacingMultiplierOverride,
        baseContentRevision = draft.baseContentRevision,
        localRevision = draft.localRevision,
        savedAtMillis = draft.savedAtMillis,
      )
    }
    val snapshot = PrivateVaultSnapshot(
      notes = notesById.values.sortedBy { it.id.value },
      drafts = (current.drafts + addedDrafts).sortedBy(PrivateEditSnapshot::draftId),
      exportHistory = current.exportHistory,
    )
    val retainedPaths = (snapshot.notes.flatMap(PrivateNoteDocument::blocks) + snapshot.drafts.flatMap(PrivateEditSnapshot::blocks))
      .mapNotNullTo(hashSetOf(), NoteBlockDraft::assetPath)
    return PrivateSnapshotResult(snapshot, retainedPaths)
  }

  private suspend fun pruneUnusedAssets(ordinaryDraftPaths: Set<String>, privatePaths: Set<String>) {
    val ordinaryPaths = records.asSequence()
      .filter { it.domain == BackupRecordDomain.ORDINARY }
      .mapNotNull { it.payload as? BackupRecordPayload.Block }
      .filter { actionFor(noteKey(it.noteId, BackupRecordDomain.ORDINARY)) != MergeAction.SKIP }
      .mapNotNull { it.assetRef?.let { id -> restoredAssetPaths[AssetKey(BackupRecordDomain.ORDINARY, id)] } }
      .toMutableSet().apply { addAll(ordinaryDraftPaths) }
    val privateAssetPaths = records.asSequence()
      .filter { it.domain == BackupRecordDomain.PRIVATE }
      .mapNotNull { envelope ->
        when (val payload = envelope.payload) {
          is BackupRecordPayload.Block -> if (actionFor(noteKey(payload.noteId, BackupRecordDomain.PRIVATE)) == MergeAction.SKIP) null else payload.assetRef
          is BackupRecordPayload.DraftBlock -> payload.assetRef
          else -> null
        }
      }
      .mapNotNull { assetId -> restoredAssetPaths[AssetKey(BackupRecordDomain.PRIVATE, assetId)] }
      .filterNot(privatePaths::contains)
      .toSet()
    val tx = checkNotNull(transactionId)
    restoredAssetPaths.forEach { (key, path) ->
      when (key.domain) {
        BackupRecordDomain.ORDINARY -> if (path !in ordinaryPaths) check(originalImages.delete(path)) { "无法清理未使用的恢复图片" }
        BackupRecordDomain.PRIVATE -> if (path in privateAssetPaths) {
          check(privateStore.deletePrivateAssetFromRestoreGroup(tx, path)) { "无法清理未使用的私密恢复图片" }
        }
      }
    }
  }

  private fun actionFor(key: BackupObjectKey): MergeAction {
    val plan = checkNotNull(resolution)
    return when {
      key in plan.identicalObjects || key in plan.keepCurrent -> MergeAction.SKIP
      key in plan.keepBoth -> MergeAction.KEEP_BOTH
      else -> MergeAction.APPLY
    }
  }

  private fun noteKey(id: String, domain: BackupRecordDomain = BackupRecordDomain.ORDINARY) =
    BackupObjectKey(domain, BackupRecordKind.NOTE, id)

  private fun requireAssetPath(domain: BackupRecordDomain, assetId: String): String =
    restoredAssetPaths[AssetKey(domain, assetId)] ?: throw IllegalStateException("备份引用的图片未能恢复")

  private fun decodeFormatting(blob: ByteArray?, text: String?, type: BlockType): BlockFormattingState =
    blob?.let { BlockFormattingCodec.decode(it, text.orEmpty(), type) }
      ?: BlockFormattingState.Supported(BlockFormatting())

  private fun allocateId(preferredId: String, used: MutableSet<String>, seed: String): String {
    if (used.add(preferredId)) return preferredId
    var salt = 1
    while (true) {
      val candidate = stableId("$seed:$salt")
      if (used.add(candidate)) return candidate
      salt++
    }
  }

  private fun uniqueRestoreCopyName(name: String, occupied: Set<String>): String {
    var suffix = 1
    while (true) {
      val candidate = "$name（恢复副本 $suffix）"
      if (normalizeFolderName(candidate) !in occupied) return candidate
      suffix++
    }
  }

  private fun normalizeFolderName(value: String) = value.lowercase(Locale.ROOT)

  private fun stableId(seed: String) = UUID.nameUUIDFromBytes("anan-restore:$seed".toByteArray(Charsets.UTF_8)).toString()

  private fun extensionForMime(mimeType: String): String = when (mimeType) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/gif" -> "gif"
    else -> throw IOException("备份图片格式不受支持")
  }

  private fun checkedAdd(left: Long, right: Long): Long = try {
    Math.addExact(left, right)
  } catch (failure: ArithmeticException) {
    throw IOException("备份笔记数据长度溢出", failure)
  }

  private fun stopAssetWorkers(): Throwable? {
    var failure: Throwable? = null
    assetPipes.values.forEach { pending ->
      runCatching { pending.output.close() }.onFailure { failure = appendFailure(failure, it) }
      pending.future.cancel(true)
      runCatching { pending.input.close() }.onFailure { failure = appendFailure(failure, it) }
    }
    assetPipes.clear()
    assetExecutor.shutdownNow()
    try {
      if (!assetExecutor.awaitTermination(ASSET_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        failure = appendFailure(failure, IOException("恢复附件工作线程未能安全停止"))
      }
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      failure = appendFailure(failure, interrupted)
    }
    return failure
  }

  private fun wipeRecords() {
    records.forEach { envelope ->
      when (val payload = envelope.payload) {
        is BackupRecordPayload.Block -> MemoryWipe.bytes(payload.formattingBlob)
        is BackupRecordPayload.DraftBlock -> MemoryWipe.bytes(payload.formattingBlob)
        else -> Unit
      }
    }
    records.clear()
    restoredAssetPaths.clear()
  }

  private fun appendFailure(first: Throwable?, next: Throwable): Throwable {
    if (first == null) return next
    first.addSuppressed(next)
    return first
  }

  private data class AssetKey(val domain: BackupRecordDomain, val id: String)

  private data class PendingAsset(
    val input: PipedInputStream,
    val output: PipedOutputStream,
    val future: Future<String>,
  )

  private data class OrdinaryRows(
    val notes: List<NoteEntity>,
    val blocks: List<BlockEntity>,
    val todos: List<TodoEntity>,
    val replacedNoteIds: Set<String>,
    val searchDocuments: List<NoteSearchFtsEntity>,
  )

  private data class DraftRestoreResult(
    val snapshots: List<EditorDraftSnapshot>,
    val retainedAssetPaths: Set<String>,
  )

  private data class FolderRestorePlan(
    val sourceToTargetIds: Map<String, String>,
    val rowsToUpsert: List<FolderEntity>,
  )

  private data class PrivateSnapshotResult(
    val snapshot: PrivateVaultSnapshot,
    val retainedAssetPaths: Set<String>,
  )

  private enum class MergeAction { SKIP, APPLY, KEEP_BOTH }

  private companion object {
    const val UNCATEGORIZED_FOLDER_ID = "system-uncategorized"
    const val ASSET_PIPE_BYTES = 64 * 1024
    const val MAX_ORDINARY_RECORD_BYTES = 128L * 1024 * 1024
    const val MAX_PRIVATE_RECORD_BYTES = 48L * 1024 * 1024
    const val ASSET_SHUTDOWN_SECONDS = 10L
    val SEARCHABLE_BLOCK_TYPES = setOf(BlockType.TEXT.name, BlockType.QUOTE.name, BlockType.TODO.name, BlockType.OCR_TEXT.name)
  }
}

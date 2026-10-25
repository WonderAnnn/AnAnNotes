package com.anan.anannotes.data.repository

import androidx.room.withTransaction
import com.anan.anannotes.core.createNoteIfMeaningful
import com.anan.anannotes.core.isTrashExpired
import com.anan.anannotes.core.moveToTrash
import com.anan.anannotes.core.restoreFromTrash
import com.anan.anannotes.core.sortNotes
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.NotePreviewModel
import com.anan.anannotes.core.model.NoteSortOrder
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.BlockEntity
import com.anan.anannotes.data.local.NoteEntity
import com.anan.anannotes.data.local.NoteSearchFtsEntity
import com.anan.anannotes.data.local.TodoEntity
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.feature.ocr.OcrJobEvent
import com.anan.anannotes.feature.ocr.OcrJobRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.UUID

sealed interface NoteSaveResult {
  data class Saved(val noteId: String, val contentRevision: Long) : NoteSaveResult
  data object EmptyNotSaved : NoteSaveResult
  data class Conflict(val currentRevision: Long) : NoteSaveResult
}

class NoteRepository(
  private val database: AnAnDatabase,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
  private val assetStore: OriginalImageStore? = null,
) {
  fun observeNotes(folderId: String, order: NoteSortOrder = NoteSortOrder.RECENT_EDITED): Flow<List<NoteModel>> =
    database.noteDao().observeActiveByFolder(folderId).map { notes -> sortNotes(notes.map(NoteEntity::toModel), order) }

  fun observeAllNotes(order: NoteSortOrder = NoteSortOrder.RECENT_EDITED): Flow<List<NoteModel>> =
    database.noteDao().observeAllActive().map { notes -> sortNotes(notes.map(NoteEntity::toModel), order) }

  fun observePreviews(folderId: String, order: NoteSortOrder = NoteSortOrder.RECENT_EDITED): Flow<List<NotePreviewModel>> =
    database.noteDao().observePreviewsByFolder(folderId).map { previews -> sortPreviews(previews, order) }

  fun observeAllPreviews(order: NoteSortOrder = NoteSortOrder.RECENT_EDITED): Flow<List<NotePreviewModel>> =
    database.noteDao().observeAllPreviews().map { previews -> sortPreviews(previews, order) }

  fun observeTrash(): Flow<List<NoteModel>> =
    database.noteDao().observeTrash().map { notes -> notes.map(NoteEntity::toModel) }

  fun observeNote(noteId: String): Flow<NoteDocument?> =
    combine(
      database.noteDao().observeById(noteId),
      database.blockDao().observeByNote(noteId),
      database.todoDao().observeByNote(noteId),
    ) { note, blocks, todos ->
      note?.let {
        NoteDocument(
          note = it.toModel(),
          blocks = blocks.map { block -> block.toModel() },
          todos = todos.map { todo -> todo.toModel() },
        )
      }
    }

  suspend fun getDocumentSnapshot(noteId: String): NoteDocument? = database.withTransaction {
    val note = database.noteDao().getById(noteId) ?: return@withTransaction null
    NoteDocument(
      note = note.toModel(),
      blocks = database.blockDao().getByNote(noteId).map { it.toModel() },
      todos = database.todoDao().getByNote(noteId).map { it.toModel() },
    )
  }

  suspend fun saveDraft(draft: NoteDraft, existingNoteId: String? = null): String? {
    return when (val result = saveVersioned(draft, existingNoteId)) {
      is NoteSaveResult.Saved -> result.noteId
      NoteSaveResult.EmptyNotSaved -> null
      is NoteSaveResult.Conflict -> error("笔记版本已变化，请重新打开后再保存")
    }
  }

  suspend fun findImportedNoteId(requestId: String): String? =
    database.noteDao().getByImportRequestId(requestId)?.id

  suspend fun pendingOcrJobs(): List<OcrJobSource> = database.blockDao().getPendingOcrJobs().map { row ->
    OcrJobSource(row.noteId, row.ocrBlockId, row.imageAssetPath)
  }

  suspend fun ocrJobSource(noteId: String, ocrBlockId: String): OcrJobSource? =
    database.blockDao().getOcrJobSource(noteId, ocrBlockId)?.let { row ->
      OcrJobSource(row.noteId, row.ocrBlockId, row.imageAssetPath)
    }

  /** Commits one OCR transition; failed/late workers cannot overwrite cancellation or success. */
  suspend fun transitionOcrJob(
    noteId: String,
    ocrBlockId: String,
    event: OcrJobEvent,
    recognizedText: String? = null,
  ): OcrStatus? = database.withTransaction {
    val note = database.noteDao().getById(noteId)?.takeIf { it.deletedAtMillis == null } ?: return@withTransaction null
    val block = database.blockDao().getByNote(noteId).firstOrNull {
      it.id == ocrBlockId && it.type == BlockType.OCR_TEXT.name
    } ?: return@withTransaction null
    val current = block.ocrStatus?.let { runCatching { OcrStatus.valueOf(it) }.getOrNull() }
    val target = OcrJobRules.transition(current, event)
    if (target == current) return@withTransaction target

    val normalizedText = if (target == OcrStatus.SUCCEEDED) OcrJobRules.normalizeText(recognizedText.orEmpty()) else null
    val committedTarget = if (target == OcrStatus.SUCCEEDED && normalizedText == null) {
      OcrJobRules.transition(current, OcrJobEvent.FAIL)
    } else target
    val updated = if (committedTarget == OcrStatus.SUCCEEDED) {
      database.blockDao().updateOcrResult(noteId, ocrBlockId, committedTarget.name, normalizedText)
    } else {
      database.blockDao().updateOcrStatus(noteId, ocrBlockId, committedTarget.name)
    }
    if (updated == 0) return@withTransaction current
    if (committedTarget == OcrStatus.SUCCEEDED) {
      replaceSearchDocument(noteId, note.title, database.blockDao().getByNote(noteId))
    }
    committedTarget
  }

  suspend fun saveVersioned(
    draft: NoteDraft,
    existingNoteId: String? = null,
    expectedContentRevision: Long? = null,
    importRequestId: String? = null,
  ): NoteSaveResult {
    if (existingNoteId == null && createNoteIfMeaningful(draft, id = "preview", nowMillis = 0L) == null) {
      return NoteSaveResult.EmptyNotSaved
    }
    val now = nowMillis()
    val removedImagePaths = linkedSetOf<String>()
    val result = database.withTransaction {
      if (existingNoteId == null && importRequestId != null) {
        database.noteDao().getByImportRequestId(importRequestId)?.let { existingImport ->
          return@withTransaction NoteSaveResult.Saved(existingImport.id, existingImport.contentRevision)
        }
      }
      check(database.folderDao().getById(draft.folderId) != null) { "目标文件夹不存在" }
      val existingEntity = existingNoteId?.let { database.noteDao().getById(it) }
      require(existingNoteId == null || existingEntity != null) { "笔记不存在" }
      require(existingEntity?.deletedAtMillis == null) { "回收站中的笔记不能直接编辑" }
      if (existingEntity != null && expectedContentRevision != null &&
        existingEntity.contentRevision != expectedContentRevision
      ) {
        return@withTransaction NoteSaveResult.Conflict(existingEntity.contentRevision)
      }

      val note = if (existingEntity == null) {
        createNoteIfMeaningful(draft, id = newId(), nowMillis = now) ?: return@withTransaction NoteSaveResult.EmptyNotSaved
      } else {
        existingEntity.toModel().copy(
          folderId = draft.folderId,
          title = draft.title,
          titleManuallySet = draft.titleManuallySet,
          updatedAtMillis = now,
          contentRevision = existingEntity.contentRevision + 1L,
          skinId = draft.skinId,
          fontSizeSpOverride = draft.fontSizeSpOverride,
          lineSpacingMultiplierOverride = draft.lineSpacingMultiplierOverride,
        )
      }
      database.noteDao().upsert(
        note.toEntity().copy(importRequestId = existingEntity?.importRequestId ?: importRequestId),
      )

      val noteId = note.id
      val previousBlocks = if (existingEntity == null) emptyList() else database.blockDao().getByNote(noteId)
      val previousTodos = if (existingEntity == null) emptyList() else database.todoDao().getByNote(noteId)
      val nextImagePaths = draft.blocks.filter { it.type == BlockType.IMAGE }.mapNotNull { it.assetPath }.toSet()
      removedImagePaths += previousBlocks
        .filter { it.type == BlockType.IMAGE.name && it.assetPath != null && it.assetPath !in nextImagePaths }
        .mapNotNull { it.assetPath }
      val blocks = draft.blocks.mapIndexed { position, originalBlock ->
        // A worker may finish while this editor still holds an older pending placeholder.
        // Preserve its committed OCR text rather than letting a stale save erase it.
        val generatedOcr = previousBlocks.firstOrNull {
          it.id == originalBlock.id && it.type == BlockType.OCR_TEXT.name && it.ocrStatus == OcrStatus.SUCCEEDED.name
        }
        val block = if (
          originalBlock.type == BlockType.OCR_TEXT &&
          originalBlock.ocrStatus in setOf(OcrStatus.PENDING, OcrStatus.RUNNING) &&
          generatedOcr != null
        ) {
          originalBlock.copy(text = generatedOcr.text, ocrStatus = OcrStatus.SUCCEEDED)
        } else originalBlock
        val previous = previousBlocks.getOrNull(position)?.takeIf { it.type == block.type.name }
        block.toEntity(
          noteId = noteId,
          position = position,
          blockId = block.id.ifBlank { previous?.id ?: newId() },
        ).copy(
          widthPercent = block.widthPercent.coerceIn(1f, 100f),
          aspectRatio = block.aspectRatio?.takeIf { it > 0f },
        )
      }

      if (blocks.isEmpty()) {
        database.todoDao().deleteByNote(noteId)
        database.blockDao().deleteByNote(noteId)
      } else {
        database.blockDao().upsertAll(blocks)
        val todoBlocks = blocks.filter { it.type == BlockType.TODO.name }
        if (todoBlocks.isEmpty()) {
          database.todoDao().deleteByNote(noteId)
        } else {
          val previousTodoByBlock = previousTodos.associateBy { it.blockId }
          val todos = todoBlocks.mapNotNull { block ->
            val text = block.text.orEmpty()
            val previous = previousTodoByBlock[block.id]
            if (text.isBlank() && previous?.isCompleted != true) return@mapNotNull null
            TodoEntity(
              id = previous?.id ?: newId(),
              noteId = noteId,
              blockId = block.id,
              text = text,
              isCompleted = previous?.isCompleted ?: false,
              updatedAtMillis = if (previous?.text == text) previous.updatedAtMillis else now,
            )
          }
          if (todos.isNotEmpty()) database.todoDao().upsertAll(todos)
          val validTodoIds = todos.map { it.blockId }
          if (validTodoIds.isEmpty()) database.todoDao().deleteByNote(noteId)
          else database.todoDao().deleteExceptBlocks(noteId, validTodoIds)
        }
        val retainedBlockIds = blocks.map { it.id }
        database.blockDao().deleteExcept(noteId, retainedBlockIds)
      }
      replaceSearchDocument(noteId, note.title, blocks)
      NoteSaveResult.Saved(noteId, note.contentRevision)
    }
    cleanupUnreferencedAssets(removedImagePaths)
    return result
  }

  suspend fun moveNotesToFolder(noteIds: List<String>, folderId: String) {
    if (noteIds.isEmpty()) return
    database.withTransaction {
      check(database.folderDao().getById(folderId) != null) { "目标文件夹不存在" }
      database.noteDao().moveActiveToFolder(noteIds.distinct(), folderId)
    }
  }

  suspend fun setPinned(noteIds: List<String>, pinned: Boolean) {
    if (noteIds.isEmpty()) return
    database.noteDao().setPinned(noteIds.distinct(), pinned, nowMillis())
  }

  suspend fun moveToTrash(noteId: String) {
    val now = nowMillis()
    database.withTransaction {
      database.noteSearchDao().deleteByNote(noteId)
      database.noteDao().moveToTrash(noteId, now, now)
    }
  }

  suspend fun restore(noteId: String) {
    database.withTransaction {
      val note = database.noteDao().getById(noteId) ?: return@withTransaction
      if (note.deletedAtMillis != null) {
        val restored = restoreFromTrash(note.toModel()).copy(updatedAtMillis = nowMillis())
        database.noteDao().upsert(restored.toEntity())
        replaceSearchDocument(restored.id, restored.title, database.blockDao().getByNote(restored.id))
      }
    }
  }

  suspend fun deletePermanently(noteId: String) {
    val imagePaths = database.withTransaction {
      val paths = database.blockDao().getImageAssetPaths(noteId).toSet()
      database.noteSearchDao().deleteByNote(noteId)
      database.noteDao().deletePermanently(noteId)
      paths
    }
    cleanupUnreferencedAssets(imagePaths)
  }

  suspend fun purgeExpiredTrash(now: Long = nowMillis()): List<String> {
    val imagePaths = linkedSetOf<String>()
    val expiredIds = database.withTransaction {
      val ids = database.noteDao().getTrash().filter { isTrashExpired(it.toModel(), now) }.map { it.id }
      ids.forEach {
        imagePaths += database.blockDao().getImageAssetPaths(it)
        database.noteSearchDao().deleteByNote(it)
      }
      if (ids.isNotEmpty()) database.noteDao().deletePermanently(ids)
      ids
    }
    cleanupUnreferencedAssets(imagePaths)
    return expiredIds
  }

  private suspend fun cleanupUnreferencedAssets(paths: Set<String>) {
    val store = assetStore ?: return
    paths.forEach { path ->
      if (database.blockDao().countImageReferences(path) == 0) runCatching { store.delete(path) }
    }
  }

  suspend fun setTodoCompleted(todoId: String, completed: Boolean) {
    database.todoDao().setCompleted(todoId, completed, nowMillis())
  }

  suspend fun setTodoCompletedForBlock(noteId: String, blockId: String, completed: Boolean): Boolean =
    database.todoDao().setCompletedByBlock(noteId, blockId, completed, nowMillis()) > 0

  private suspend fun replaceSearchDocument(noteId: String, title: String, blocks: List<BlockEntity>) {
    database.noteSearchDao().deleteByNote(noteId)
    val content = blocks.asSequence()
      .filter { block -> block.type in SEARCHABLE_BLOCK_TYPES }
      .filter { block -> block.type != BlockType.OCR_TEXT.name || block.ocrStatus == "SUCCEEDED" }
      .mapNotNull { it.text?.takeIf(String::isNotBlank) }
      .joinToString("\n")
    database.noteSearchDao().insert(NoteSearchFtsEntity(noteId = noteId, title = title, content = content))
  }

  private fun sortPreviews(previews: List<NotePreviewModel>, order: NoteSortOrder): List<NotePreviewModel> =
    when (order) {
      NoteSortOrder.RECENT_EDITED -> previews.sortedWith(
        compareByDescending<NotePreviewModel> { it.isPinned }
          .thenByDescending { it.updatedAtMillis }
          .thenByDescending { it.createdAtMillis }
          .thenBy { it.id },
      )
      NoteSortOrder.CREATED -> previews.sortedWith(
        compareByDescending<NotePreviewModel> { it.isPinned }
          .thenByDescending { it.createdAtMillis }
          .thenByDescending { it.updatedAtMillis }
          .thenBy { it.id },
      )
    }
}

data class OcrJobSource(
  val noteId: String,
  val ocrBlockId: String,
  val imageAssetPath: String?,
)

private val SEARCHABLE_BLOCK_TYPES = setOf(
  BlockType.TEXT.name,
  BlockType.QUOTE.name,
  BlockType.TODO.name,
  BlockType.OCR_TEXT.name,
)

package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.TodoModel
import com.anan.anannotes.feature.export.ExportSnapshot
import com.anan.anannotes.security.PrivateAccess
import java.io.InputStream

/** Adapts a decrypted private snapshot for low-level renderers without entering ordinary repositories. */
class PrivateRepositoryExportDataSource(
  private val repository: PrivateRepository,
  private val store: PrivateStore,
  private val displaySettings: DisplaySettings = DisplaySettings(),
) : PrivateExportDataSource {
  override suspend fun read(access: PrivateAccess, noteId: PrivateNoteId): ExportSnapshot? {
    val privateNote = repository.read(access, noteId) ?: return null
    val document = NoteDocument(
      note = NoteModel(
        id = privateNote.id.value,
        folderId = privateNote.folderId,
        title = privateNote.title,
        titleManuallySet = privateNote.titleManuallySet,
        contentRevision = privateNote.contentRevision,
        createdAtMillis = privateNote.createdAtMillis,
        updatedAtMillis = privateNote.updatedAtMillis,
        skinId = privateNote.skinId,
        fontSizeSpOverride = privateNote.fontSizeSpOverride,
        lineSpacingMultiplierOverride = privateNote.lineSpacingMultiplierOverride,
        isPinned = privateNote.isPinned,
        deletedAtMillis = privateNote.deletedAtMillis,
      ),
      blocks = privateNote.blocks.mapIndexed { position, block ->
        BlockModel(
          id = block.id,
          noteId = privateNote.id.value,
          position = position,
          type = block.type,
          text = block.text,
          assetPath = block.assetPath,
          previewPath = null,
          widthPercent = block.widthPercent,
          alignment = block.alignment,
          aspectRatio = block.aspectRatio,
          ocrStatus = block.ocrStatus,
          formatting = block.formatting,
        )
      },
      todos = privateNote.todos.map { todo ->
        TodoModel(
          id = todo.id,
          noteId = privateNote.id.value,
          blockId = todo.blockId,
          text = todo.text,
          isCompleted = todo.completed,
          updatedAtMillis = todo.updatedAtMillis,
        )
      },
    )
    return ExportSnapshot(document, PRIVATE_FOLDER_LABEL, displaySettings)
  }

  override fun openImage(access: PrivateAccess, block: BlockModel): InputStream? =
    block.assetPath?.let { store.openPrivateAssetForExport(access, it) }

  private companion object {
    const val PRIVATE_FOLDER_LABEL = "私密笔记"
  }
}

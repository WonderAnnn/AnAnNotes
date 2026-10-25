package com.anan.anannotes.data.repository

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingCodec
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.FolderModel
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.NotePreviewModel
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TodoModel
import com.anan.anannotes.data.local.BlockEntity
import com.anan.anannotes.data.local.ExportHistoryEntity
import com.anan.anannotes.data.local.FolderEntity
import com.anan.anannotes.data.local.NoteEntity
import com.anan.anannotes.data.local.TodoEntity

internal fun FolderEntity.toModel() = FolderModel(
  id = id,
  name = name,
  kind = FolderKind.valueOf(kind),
  createdAtMillis = createdAtMillis,
  sortPosition = sortPosition,
)

internal fun NoteEntity.toModel() = NoteModel(
  id = id,
  folderId = folderId,
  title = title,
  titleManuallySet = titleManuallySet,
  contentRevision = contentRevision,
  createdAtMillis = createdAtMillis,
  updatedAtMillis = updatedAtMillis,
  skinId = skinId,
  fontSizeSpOverride = fontSizeSpOverride,
  lineSpacingMultiplierOverride = lineSpacingMultiplierOverride,
  isPinned = isPinned,
  deletedAtMillis = deletedAtMillis,
)

internal fun NoteModel.toEntity() = NoteEntity(
  id = id,
  folderId = folderId,
  title = title,
  titleManuallySet = titleManuallySet,
  contentRevision = contentRevision,
  createdAtMillis = createdAtMillis,
  updatedAtMillis = updatedAtMillis,
  skinId = skinId,
  fontSizeSpOverride = fontSizeSpOverride,
  lineSpacingMultiplierOverride = lineSpacingMultiplierOverride,
  isPinned = isPinned,
  deletedAtMillis = deletedAtMillis,
)

internal fun BlockEntity.toModel(): BlockModel {
  val blockType = BlockType.valueOf(type)
  val formattingState = formattingBlob?.let { blob ->
    BlockFormattingCodec.decode(blob, text.orEmpty(), blockType)
  } ?: BlockFormattingState.Supported(BlockFormatting())
  return BlockModel(
    id = id,
    noteId = noteId,
    position = position,
    type = blockType,
    text = text,
    assetPath = assetPath,
    previewPath = previewPath,
    widthPercent = widthPercent,
    alignment = BlockAlignment.valueOf(alignment),
    aspectRatio = aspectRatio,
    ocrStatus = ocrStatus?.let(OcrStatus::valueOf),
    formatting = formattingState,
  )
}

internal fun NoteBlockDraft.toEntity(noteId: String, position: Int, blockId: String): BlockEntity {
  val formattingBlob = when (val state = formatting) {
    is BlockFormattingState.Supported -> {
      if (state.value == BlockFormatting()) null
      else BlockFormattingCodec.encode(text.orEmpty(), type, state.value)
    }
    is BlockFormattingState.Unsupported -> state.originalBlob
  }
  return BlockEntity(
    id = blockId,
    noteId = noteId,
    position = position,
    type = type.name,
    text = text,
    assetPath = assetPath,
    previewPath = previewPath,
    widthPercent = widthPercent,
    alignment = alignment.name,
    aspectRatio = aspectRatio,
    ocrStatus = ocrStatus?.name,
    formattingBlob = formattingBlob,
  )
}

internal fun NoteEntity.toPreviewModel(
  summary: String? = null,
  imagePreviewPath: String? = null,
  imageAssetPath: String? = null,
  imageAspectRatio: Float? = null,
) =
  NotePreviewModel(
    id = id,
    folderId = folderId,
    title = title,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    isPinned = isPinned,
    summaryText = summary,
    firstImagePreviewPath = imagePreviewPath,
    firstImageAssetPath = imageAssetPath,
    firstImageAspectRatio = imageAspectRatio,
  )

internal fun TodoEntity.toModel() = TodoModel(
  id = id,
  noteId = noteId,
  blockId = blockId,
  text = text,
  isCompleted = isCompleted,
  updatedAtMillis = updatedAtMillis,
)

internal fun ExportHistoryEntity.toModel() = ExportHistoryModel(
  id = id,
  noteId = noteId,
  format = ExportFormat.valueOf(format),
  fileName = fileName,
  destinationUri = destinationUri,
  createdAtMillis = createdAtMillis,
  succeeded = succeeded,
  errorCode = errorCode,
)

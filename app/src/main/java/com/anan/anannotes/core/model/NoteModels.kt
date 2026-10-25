package com.anan.anannotes.core.model

const val UNCATEGORIZED_FOLDER_ID = "system-uncategorized"
const val DEFAULT_NOTE_SKIN_ID = "minimal-white"
const val SAKURA_PETAL_SKIN_ID = "sakura-petal-paper"
const val TRASH_RETENTION_MILLIS = 30L * 24L * 60L * 60L * 1000L

enum class NoteSortOrder {
  RECENT_EDITED,
  CREATED,
}

data class NoteModel(
  val id: String,
  val folderId: String,
  val title: String = "",
  val titleManuallySet: Boolean = false,
  val contentRevision: Long = 0L,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val skinId: String = DEFAULT_NOTE_SKIN_ID,
  val fontSizeSpOverride: Float? = null,
  val lineSpacingMultiplierOverride: Float? = null,
  val isPinned: Boolean = false,
  val deletedAtMillis: Long? = null,
)

data class NoteDraft(
  val folderId: String = UNCATEGORIZED_FOLDER_ID,
  val title: String = "",
  val titleManuallySet: Boolean = false,
  val blocks: List<NoteBlockDraft> = emptyList(),
  val skinId: String = DEFAULT_NOTE_SKIN_ID,
  val fontSizeSpOverride: Float? = null,
  val lineSpacingMultiplierOverride: Float? = null,
)

data class NoteDocument(
  val note: NoteModel,
  val blocks: List<BlockModel>,
  val todos: List<TodoModel>,
)

data class NotePreviewModel(
  val id: String,
  val folderId: String,
  val title: String,
  val createdAtMillis: Long,
  val updatedAtMillis: Long,
  val isPinned: Boolean,
  val summaryText: String?,
  val firstImagePreviewPath: String?,
  val firstImageAssetPath: String?,
  val firstImageAspectRatio: Float?,
)

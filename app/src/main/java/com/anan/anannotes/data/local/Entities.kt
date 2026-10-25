package com.anan.anannotes.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anan.anannotes.core.model.DEFAULT_FONT_SIZE_SP
import com.anan.anannotes.core.model.DEFAULT_LINE_SPACING_MULTIPLIER

@Entity(
  tableName = "folders",
  indices = [Index(value = ["kind"]), Index(value = ["name"], unique = true)],
)
data class FolderEntity(
  @PrimaryKey val id: String,
  val name: String,
  val kind: String,
  val createdAtMillis: Long,
  val sortPosition: Int,
)

@Entity(
  tableName = "notes",
  foreignKeys = [
    ForeignKey(
      entity = FolderEntity::class,
      parentColumns = ["id"],
      childColumns = ["folderId"],
      onDelete = ForeignKey.NO_ACTION,
    ),
  ],
  indices = [
    Index(value = ["folderId", "deletedAtMillis"]),
    Index(value = ["updatedAtMillis"]),
    Index(value = ["createdAtMillis"]),
    Index(value = ["importRequestId"], unique = true),
  ],
)
data class NoteEntity(
  @PrimaryKey val id: String,
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
  @ColumnInfo(defaultValue = "1") val contentRevision: Long = 1L,
  val importRequestId: String? = null,
)

@Entity(
  tableName = "note_blocks",
  foreignKeys = [
    ForeignKey(
      entity = NoteEntity::class,
      parentColumns = ["id"],
      childColumns = ["noteId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["noteId", "position"]), Index(value = ["noteId", "id"], unique = true)],
)
data class BlockEntity(
  @PrimaryKey val id: String,
  val noteId: String,
  val position: Int,
  val type: String,
  val text: String?,
  val assetPath: String?,
  val previewPath: String?,
  val widthPercent: Float,
  val alignment: String,
  val aspectRatio: Float?,
  val ocrStatus: String?,
  val formattingBlob: ByteArray? = null,
)

@Entity(
  tableName = "todos",
  foreignKeys = [
    ForeignKey(
      entity = NoteEntity::class,
      parentColumns = ["id"],
      childColumns = ["noteId"],
      onDelete = ForeignKey.CASCADE,
    ),
    ForeignKey(
      entity = BlockEntity::class,
      parentColumns = ["noteId", "id"],
      childColumns = ["noteId", "blockId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [Index(value = ["noteId", "isCompleted", "updatedAtMillis"]), Index(value = ["noteId", "blockId"])],
)
data class TodoEntity(
  @PrimaryKey val id: String,
  val noteId: String,
  val blockId: String,
  val text: String,
  val isCompleted: Boolean,
  val updatedAtMillis: Long,
)

@Entity(
  tableName = "export_history",
  indices = [Index(value = ["createdAtMillis"]), Index(value = ["noteId"])],
)
data class ExportHistoryEntity(
  @PrimaryKey val id: String,
  val noteId: String?,
  val format: String,
  val fileName: String,
  val destinationUri: String,
  val createdAtMillis: Long,
  val succeeded: Boolean,
  val errorCode: String?,
)

@Entity(tableName = "home_view_preferences")
data class HomeViewPreferenceEntity(
  @PrimaryKey val viewId: String,
  val sortOrder: String,
)

@Entity(tableName = "app_display_settings")
data class AppDisplaySettingsEntity(
  @PrimaryKey val id: String = GLOBAL_SETTINGS_ID,
  val defaultFontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
  val lineSpacingMultiplier: Float = DEFAULT_LINE_SPACING_MULTIPLIER,
  val themeMode: String = "SYSTEM",
  val defaultSkinId: String = "minimal-white",
) {
  companion object {
    const val GLOBAL_SETTINGS_ID = "global"
  }
}

/** Marker inserted in the same Room transaction as a committed backup restore. */
@Entity(tableName = "backup_restore_commits")
data class BackupRestoreCommitEntity(
  @PrimaryKey val transactionId: String,
)

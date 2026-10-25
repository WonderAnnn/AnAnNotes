package com.anan.anannotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.anan.anannotes.core.model.NotePreviewModel
import com.anan.anannotes.core.model.TodoFeedItem
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
  @Query("SELECT * FROM folders ORDER BY sortPosition ASC, id ASC")
  suspend fun getAll(): List<FolderEntity>

  @Query("SELECT * FROM folders ORDER BY sortPosition ASC, createdAtMillis ASC")
  fun observeFolders(): Flow<List<FolderEntity>>

  @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): FolderEntity?

  @Query("SELECT COUNT(*) FROM folders WHERE LOWER(name) = LOWER(:name)")
  suspend fun countByName(name: String): Int

  @Query("SELECT COUNT(*) FROM folders WHERE LOWER(name) = LOWER(:name) AND id != :exceptId")
  suspend fun countByNameExcept(name: String, exceptId: String): Int

  @Query("SELECT COALESCE(MAX(sortPosition), 0) + 1 FROM folders")
  suspend fun nextSortPosition(): Int

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(folder: FolderEntity)

  @Upsert
  suspend fun upsert(folder: FolderEntity)

  @Query("DELETE FROM folders WHERE id = :id")
  suspend fun deleteById(id: String)
}

@Dao
interface NoteDao {
  @Query("SELECT * FROM notes ORDER BY id ASC")
  suspend fun getAll(): List<NoteEntity>

  @Query(
    """SELECT n.id, n.folderId, n.title, n.createdAtMillis, n.updatedAtMillis, n.isPinned,
      (SELECT b.text FROM note_blocks b WHERE b.noteId = n.id
        AND b.type IN ('TEXT', 'QUOTE', 'TODO', 'OCR_TEXT') AND TRIM(COALESCE(b.text, '')) != ''
        ORDER BY b.position ASC LIMIT 1) AS summaryText,
      (SELECT b.previewPath FROM note_blocks b WHERE b.noteId = n.id
        AND b.type = 'IMAGE' ORDER BY b.position ASC LIMIT 1) AS firstImagePreviewPath,
      (SELECT b.assetPath FROM note_blocks b WHERE b.noteId = n.id
        AND b.type = 'IMAGE' ORDER BY b.position ASC LIMIT 1) AS firstImageAssetPath,
      (SELECT b.aspectRatio FROM note_blocks b WHERE b.noteId = n.id
        AND b.type = 'IMAGE' ORDER BY b.position ASC LIMIT 1) AS firstImageAspectRatio
      FROM notes n WHERE n.folderId = :folderId AND n.deletedAtMillis IS NULL""",
  )
  fun observePreviewsByFolder(folderId: String): Flow<List<NotePreviewModel>>

  @Query(
    """SELECT n.id, n.folderId, n.title, n.createdAtMillis, n.updatedAtMillis, n.isPinned,
      (SELECT b.text FROM note_blocks b WHERE b.noteId = n.id
        AND b.type IN ('TEXT', 'QUOTE', 'TODO', 'OCR_TEXT') AND TRIM(COALESCE(b.text, '')) != ''
        ORDER BY b.position ASC LIMIT 1) AS summaryText,
      (SELECT b.previewPath FROM note_blocks b WHERE b.noteId = n.id
        AND b.type = 'IMAGE' ORDER BY b.position ASC LIMIT 1) AS firstImagePreviewPath,
      (SELECT b.assetPath FROM note_blocks b WHERE b.noteId = n.id
        AND b.type = 'IMAGE' ORDER BY b.position ASC LIMIT 1) AS firstImageAssetPath,
      (SELECT b.aspectRatio FROM note_blocks b WHERE b.noteId = n.id
        AND b.type = 'IMAGE' ORDER BY b.position ASC LIMIT 1) AS firstImageAspectRatio
      FROM notes n WHERE n.deletedAtMillis IS NULL""",
  )
  fun observeAllPreviews(): Flow<List<NotePreviewModel>>

  @Query("SELECT * FROM notes WHERE folderId = :folderId AND deletedAtMillis IS NULL")
  fun observeActiveByFolder(folderId: String): Flow<List<NoteEntity>>

  @Query("SELECT * FROM notes WHERE deletedAtMillis IS NULL")
  fun observeAllActive(): Flow<List<NoteEntity>>

  @Query("SELECT * FROM notes WHERE deletedAtMillis IS NOT NULL ORDER BY deletedAtMillis DESC")
  fun observeTrash(): Flow<List<NoteEntity>>

  @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
  suspend fun getById(id: String): NoteEntity?

  @Query("SELECT * FROM notes WHERE importRequestId = :requestId LIMIT 1")
  suspend fun getByImportRequestId(requestId: String): NoteEntity?

  @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
  fun observeById(id: String): Flow<NoteEntity?>

  @Query("SELECT * FROM notes WHERE deletedAtMillis IS NOT NULL")
  suspend fun getTrash(): List<NoteEntity>

  @Upsert
  suspend fun upsert(note: NoteEntity)

  @Query("UPDATE notes SET folderId = :folderId WHERE id IN (:noteIds) AND deletedAtMillis IS NULL")
  suspend fun moveActiveToFolder(noteIds: List<String>, folderId: String)

  @Query("UPDATE notes SET folderId = :uncategorizedId WHERE folderId = :deletedFolderId")
  suspend fun reassignFolder(deletedFolderId: String, uncategorizedId: String)

  @Query("UPDATE notes SET isPinned = :isPinned, updatedAtMillis = :updatedAtMillis WHERE id IN (:noteIds) AND deletedAtMillis IS NULL")
  suspend fun setPinned(noteIds: List<String>, isPinned: Boolean, updatedAtMillis: Long)

  @Query("UPDATE notes SET deletedAtMillis = :deletedAtMillis, updatedAtMillis = :updatedAtMillis WHERE id = :noteId AND deletedAtMillis IS NULL")
  suspend fun moveToTrash(noteId: String, deletedAtMillis: Long, updatedAtMillis: Long)

  @Query("UPDATE notes SET deletedAtMillis = NULL, updatedAtMillis = :updatedAtMillis WHERE id = :noteId AND deletedAtMillis IS NOT NULL")
  suspend fun restore(noteId: String, updatedAtMillis: Long)

  @Query("DELETE FROM notes WHERE id = :noteId")
  suspend fun deletePermanently(noteId: String)

  @Query("DELETE FROM notes WHERE id IN (:noteIds)")
  suspend fun deletePermanently(noteIds: List<String>)
}

@Dao
interface BlockDao {
  @Query("SELECT * FROM note_blocks ORDER BY noteId ASC, position ASC, id ASC")
  suspend fun getAll(): List<BlockEntity>

  @Query("SELECT * FROM note_blocks WHERE noteId = :noteId ORDER BY position ASC")
  fun observeByNote(noteId: String): Flow<List<BlockEntity>>

  @Query("SELECT * FROM note_blocks WHERE noteId = :noteId ORDER BY position ASC")
  suspend fun getByNote(noteId: String): List<BlockEntity>

  @Query(
    """SELECT o.noteId AS noteId, o.id AS ocrBlockId, i.assetPath AS imageAssetPath
      FROM note_blocks o
      INNER JOIN notes n ON n.id = o.noteId
      LEFT JOIN note_blocks i ON i.noteId = o.noteId AND i.position = o.position - 1 AND i.type = 'IMAGE'
      WHERE o.type = 'OCR_TEXT' AND o.ocrStatus IN ('PENDING', 'RUNNING')
        AND (i.id IS NULL OR i.assetPath IS NOT NULL) AND n.deletedAtMillis IS NULL""",
  )
  suspend fun getPendingOcrJobs(): List<OcrJobSourceEntity>

  @Query(
    """SELECT o.noteId AS noteId, o.id AS ocrBlockId, i.assetPath AS imageAssetPath
      FROM note_blocks o
      INNER JOIN notes n ON n.id = o.noteId
      LEFT JOIN note_blocks i ON i.noteId = o.noteId AND i.position = o.position - 1 AND i.type = 'IMAGE'
      WHERE o.noteId = :noteId AND o.id = :ocrBlockId AND o.type = 'OCR_TEXT'
        AND o.ocrStatus IN ('PENDING', 'RUNNING') AND (i.id IS NULL OR i.assetPath IS NOT NULL)
        AND n.deletedAtMillis IS NULL LIMIT 1""",
  )
  suspend fun getOcrJobSource(noteId: String, ocrBlockId: String): OcrJobSourceEntity?

  @Query(
    """UPDATE note_blocks SET ocrStatus = :status, text = :text
      WHERE noteId = :noteId AND id = :blockId AND type = 'OCR_TEXT'
        AND ocrStatus IN ('PENDING', 'RUNNING', 'FAILED', 'CANCELLED')""",
  )
  suspend fun updateOcrResult(noteId: String, blockId: String, status: String, text: String?): Int

  @Query(
    """UPDATE note_blocks SET ocrStatus = :status
      WHERE noteId = :noteId AND id = :blockId AND type = 'OCR_TEXT'
        AND ocrStatus IN ('PENDING', 'RUNNING', 'FAILED', 'CANCELLED')""",
  )
  suspend fun updateOcrStatus(noteId: String, blockId: String, status: String): Int

  @Query("SELECT assetPath FROM note_blocks WHERE noteId = :noteId AND type = 'IMAGE' AND assetPath IS NOT NULL")
  suspend fun getImageAssetPaths(noteId: String): List<String>

  @Query("SELECT COUNT(*) FROM note_blocks WHERE type = 'IMAGE' AND assetPath = :assetPath")
  suspend fun countImageReferences(assetPath: String): Int

  @Upsert
  suspend fun upsertAll(blocks: List<BlockEntity>)

  @Query("DELETE FROM note_blocks WHERE noteId = :noteId")
  suspend fun deleteByNote(noteId: String)

  @Query("DELETE FROM note_blocks WHERE noteId = :noteId AND id NOT IN (:blockIds)")
  suspend fun deleteExcept(noteId: String, blockIds: List<String>)
}

data class OcrJobSourceEntity(
  val noteId: String,
  val ocrBlockId: String,
  val imageAssetPath: String?,
)

@Dao
interface TodoDao {
  @Query("SELECT * FROM todos ORDER BY noteId ASC, id ASC")
  suspend fun getAll(): List<TodoEntity>

  @Query("SELECT * FROM todos WHERE noteId = :noteId ORDER BY updatedAtMillis DESC")
  fun observeByNote(noteId: String): Flow<List<TodoEntity>>

  @Query("SELECT * FROM todos WHERE noteId = :noteId")
  suspend fun getByNote(noteId: String): List<TodoEntity>

  @Query("SELECT todos.* FROM todos INNER JOIN notes ON notes.id = todos.noteId WHERE todos.isCompleted = 0 AND trim(todos.text) <> '' AND notes.deletedAtMillis IS NULL ORDER BY todos.updatedAtMillis DESC")
  fun observeIncomplete(): Flow<List<TodoEntity>>

  @Query("SELECT todos.* FROM todos INNER JOIN notes ON notes.id = todos.noteId WHERE todos.isCompleted = 1 AND trim(todos.text) <> '' AND notes.deletedAtMillis IS NULL ORDER BY todos.updatedAtMillis DESC")
  fun observeCompleted(): Flow<List<TodoEntity>>

  @Query(
    """SELECT todos.id, todos.noteId, todos.blockId, todos.text, todos.isCompleted,
      todos.updatedAtMillis, notes.title AS noteTitle, folders.name AS folderName
      FROM todos
      INNER JOIN notes ON notes.id = todos.noteId
      INNER JOIN folders ON folders.id = notes.folderId
      WHERE notes.deletedAtMillis IS NULL AND trim(todos.text) <> '' AND (:includeCompleted = 1 OR todos.isCompleted = 0)
      ORDER BY todos.updatedAtMillis DESC, todos.id ASC""",
  )
  fun observeFeed(includeCompleted: Boolean): Flow<List<TodoFeedItem>>

  @Upsert
  suspend fun upsertAll(todos: List<TodoEntity>)

  @Query("DELETE FROM todos WHERE noteId = :noteId")
  suspend fun deleteByNote(noteId: String)

  @Query("DELETE FROM todos WHERE noteId = :noteId AND blockId NOT IN (:blockIds)")
  suspend fun deleteExceptBlocks(noteId: String, blockIds: List<String>)

  @Query("UPDATE todos SET isCompleted = :isCompleted, updatedAtMillis = :updatedAtMillis WHERE id = :todoId")
  suspend fun setCompleted(todoId: String, isCompleted: Boolean, updatedAtMillis: Long)

  @Query("UPDATE todos SET isCompleted = :isCompleted, updatedAtMillis = :updatedAtMillis WHERE noteId = :noteId AND blockId = :blockId")
  suspend fun setCompletedByBlock(noteId: String, blockId: String, isCompleted: Boolean, updatedAtMillis: Long): Int
}

@Dao
interface NoteSearchDao {
  @Insert
  suspend fun insert(document: NoteSearchFtsEntity)

  @Query("DELETE FROM note_search_fts WHERE noteId = :noteId")
  suspend fun deleteByNote(noteId: String)

  @Query("DELETE FROM note_search_fts")
  suspend fun clear()

  @Query(
    """SELECT note_search_fts.noteId AS noteId, note_search_fts.title AS title,
      note_search_fts.content AS content, folders.name AS folderName,
      notes.updatedAtMillis AS updatedAtMillis
      FROM note_search_fts
      INNER JOIN notes ON notes.id = note_search_fts.noteId
      INNER JOIN folders ON folders.id = notes.folderId
      WHERE note_search_fts MATCH :expression AND notes.deletedAtMillis IS NULL
      ORDER BY notes.updatedAtMillis DESC LIMIT 200""",
  )
  suspend fun searchFts(expression: String): List<NoteSearchHitRow>

  @Query(
    """SELECT note_search_fts.noteId AS noteId, note_search_fts.title AS title,
      note_search_fts.content AS content, folders.name AS folderName,
      notes.updatedAtMillis AS updatedAtMillis
      FROM note_search_fts
      INNER JOIN notes ON notes.id = note_search_fts.noteId
      INNER JOIN folders ON folders.id = notes.folderId
      WHERE notes.deletedAtMillis IS NULL
        AND (note_search_fts.title LIKE '%' || :query || '%' OR note_search_fts.content LIKE '%' || :query || '%')
      ORDER BY notes.updatedAtMillis DESC LIMIT 200""",
  )
  suspend fun searchSubstring(query: String): List<NoteSearchHitRow>
}

@Dao
interface ExportHistoryDao {
  @Query("SELECT * FROM export_history ORDER BY createdAtMillis DESC, rowid DESC LIMIT :limit")
  fun observeLatest(limit: Int): Flow<List<ExportHistoryEntity>>

  @Query("SELECT * FROM export_history ORDER BY createdAtMillis DESC, rowid DESC LIMIT :limit")
  suspend fun getLatest(limit: Int): List<ExportHistoryEntity>

  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(history: ExportHistoryEntity)

  @Query("DELETE FROM export_history WHERE id NOT IN (SELECT id FROM export_history ORDER BY createdAtMillis DESC, rowid DESC LIMIT :keepCount)")
  suspend fun keepLatest(keepCount: Int)

  @Query("DELETE FROM export_history")
  suspend fun clear()
}

@Dao
interface HomeViewPreferenceDao {
  @Query("SELECT * FROM home_view_preferences WHERE viewId = :viewId LIMIT 1")
  fun observeByView(viewId: String): Flow<HomeViewPreferenceEntity?>

  @Upsert
  suspend fun upsert(preference: HomeViewPreferenceEntity)

  @Query("DELETE FROM home_view_preferences WHERE viewId = :viewId")
  suspend fun deleteByView(viewId: String)
}

@Dao
interface AppDisplaySettingsDao {
  @Query("SELECT * FROM app_display_settings WHERE id = 'global' LIMIT 1")
  fun observe(): Flow<AppDisplaySettingsEntity?>

  @Upsert
  suspend fun upsert(settings: AppDisplaySettingsEntity)
}

@Dao
interface BackupRestoreCommitDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(marker: BackupRestoreCommitEntity)

  @Query("SELECT EXISTS(SELECT 1 FROM backup_restore_commits WHERE transactionId = :transactionId)")
  suspend fun contains(transactionId: String): Boolean

  @Query("SELECT transactionId FROM backup_restore_commits ORDER BY transactionId ASC")
  suspend fun allTransactionIds(): List<String>

  @Query("DELETE FROM backup_restore_commits WHERE transactionId = :transactionId")
  suspend fun delete(transactionId: String)

  @Query("DELETE FROM backup_restore_commits")
  suspend fun deleteAll()
}

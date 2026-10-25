package com.anan.anannotes.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [FolderEntity::class, NoteEntity::class, BlockEntity::class, TodoEntity::class, ExportHistoryEntity::class, HomeViewPreferenceEntity::class, AppDisplaySettingsEntity::class, NoteSearchFtsEntity::class, BackupRestoreCommitEntity::class],
  version = 8,
  exportSchema = true,
)
abstract class AnAnDatabase : RoomDatabase() {
  abstract fun folderDao(): FolderDao
  abstract fun noteDao(): NoteDao
  abstract fun blockDao(): BlockDao
  abstract fun todoDao(): TodoDao
  abstract fun exportHistoryDao(): ExportHistoryDao
  abstract fun homeViewPreferenceDao(): HomeViewPreferenceDao
  abstract fun displaySettingsDao(): AppDisplaySettingsDao
  abstract fun noteSearchDao(): NoteSearchDao
  abstract fun backupRestoreCommitDao(): BackupRestoreCommitDao

  companion object {
    private const val DATABASE_NAME = "anan-notes.db"

    @Volatile private var instance: AnAnDatabase? = null

    fun getInstance(context: Context): AnAnDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          AnAnDatabase::class.java,
          DATABASE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).addCallback(systemFolderCallback).build().also { instance = it }
      }
  }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS home_view_preferences (
        viewId TEXT NOT NULL,
        sortOrder TEXT NOT NULL,
        PRIMARY KEY(viewId)
      )""".trimIndent(),
    )
  }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE notes ADD COLUMN contentRevision INTEGER NOT NULL DEFAULT 1")
  }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS app_display_settings (
        id TEXT NOT NULL,
        defaultFontSizeSp REAL NOT NULL,
        lineSpacingMultiplier REAL NOT NULL,
        themeMode TEXT NOT NULL,
        defaultSkinId TEXT NOT NULL,
        PRIMARY KEY(id)
      )""".trimIndent(),
    )
  }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      "CREATE VIRTUAL TABLE IF NOT EXISTS `note_search_fts` USING FTS4(`noteId` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, tokenize=unicode61, notindexed=`noteId`)",
    )
    db.execSQL(
      """INSERT INTO note_search_fts(noteId, title, content)
        SELECT n.id, n.title, COALESCE((
          SELECT group_concat(searchable.text, char(10))
          FROM (
            SELECT b.text AS text FROM note_blocks b
            WHERE b.noteId = n.id
              AND b.type IN ('TEXT', 'QUOTE', 'TODO', 'OCR_TEXT')
              AND TRIM(COALESCE(b.text, '')) != ''
              AND (b.type != 'OCR_TEXT' OR b.ocrStatus = 'SUCCEEDED')
            ORDER BY b.position ASC
          ) AS searchable
        ), '')
        FROM notes n WHERE n.deletedAtMillis IS NULL""".trimIndent(),
    )
  }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE note_blocks ADD COLUMN formattingBlob BLOB")
  }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE notes ADD COLUMN importRequestId TEXT")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_notes_importRequestId ON notes(importRequestId)")
  }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL(
      """CREATE TABLE IF NOT EXISTS backup_restore_commits (
        transactionId TEXT NOT NULL,
        PRIMARY KEY(transactionId)
      )""".trimIndent(),
    )
  }
}

internal val systemFolderCallback = object : RoomDatabase.Callback() {
  override fun onCreate(db: SupportSQLiteDatabase) {
    super.onCreate(db)
    db.execSQL(
      """INSERT OR IGNORE INTO folders (id, name, kind, createdAtMillis, sortPosition)
        VALUES ('system-uncategorized', '未分类', 'UNCATEGORIZED', 0, -2147483648)""".trimIndent(),
    )
  }
}

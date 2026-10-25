package com.anan.anannotes.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.MIGRATION_1_2
import com.anan.anannotes.data.local.MIGRATION_2_3
import com.anan.anannotes.data.local.MIGRATION_3_4
import com.anan.anannotes.data.local.MIGRATION_4_5
import com.anan.anannotes.data.local.MIGRATION_5_6
import com.anan.anannotes.data.local.MIGRATION_6_7
import com.anan.anannotes.data.local.MIGRATION_7_8
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.data.repository.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlockFormattingMigrationTest {
  @Test
  fun schemaFiveMigrationsPreserveBodySearchAndDefaultImportKey() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    context.deleteDatabase(TEST_DATABASE)
    createVersionFiveDatabase()

    val database = Room.databaseBuilder(context, AnAnDatabase::class.java, TEST_DATABASE)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
      .build()
    try {
      assertEquals("v5 原标题", database.noteDao().getById("legacy-note")?.title)
      assertNull(database.noteDao().getById("legacy-note")?.importRequestId)
      val block = database.blockDao().getByNote("legacy-note").single()
      assertEquals("legacy-v5-search-token", block.text)
      assertNull(block.formattingBlob)
      assertEquals("legacy-note", SearchRepository(database).search("legacy-v5-search-token").single().noteId)

      val notes = NoteRepository(database)
      val migrated = notes.observeNote("legacy-note").first()
      assertEquals("legacy-v5-block", migrated?.blocks?.single()?.id)
      assertEquals(BlockFormattingState.Supported(BlockFormatting()), migrated?.blocks?.single()?.formatting)
    } finally {
      database.close()
      context.deleteDatabase(TEST_DATABASE)
    }
  }

  private fun createVersionFiveDatabase() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val schemaText = instrumentation.context.assets
      .open("com.anan.anannotes.data.local.AnAnDatabase/5.json")
      .bufferedReader()
      .use { it.readText() }
    val databaseSchema = JSONObject(schemaText).getJSONObject("database")
    val sqlite = SQLiteDatabase.openOrCreateDatabase(
      instrumentation.targetContext.getDatabasePath(TEST_DATABASE),
      null,
    )
    try {
      val entities = databaseSchema.getJSONArray("entities")
      for (index in 0 until entities.length()) {
        val entity = entities.getJSONObject(index)
        val tableName = entity.getString("tableName")
        sqlite.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
        val indices = entity.optJSONArray("indices") ?: continue
        for (indexInTable in 0 until indices.length()) {
          val createIndexSql = indices.getJSONObject(indexInTable).optString("createSql")
          if (createIndexSql.isNotEmpty()) sqlite.execSQL(createIndexSql.replace("${'$'}{TABLE_NAME}", tableName))
        }
      }
      val setupQueries = databaseSchema.getJSONArray("setupQueries")
      for (index in 0 until setupQueries.length()) sqlite.execSQL(setupQueries.getString(index))
      sqlite.execSQL(
        "INSERT INTO folders (id, name, kind, createdAtMillis, sortPosition) " +
          "VALUES ('legacy-folder', '迁移', 'USER', 1, 0)",
      )
      sqlite.execSQL(
        "INSERT INTO notes (id, folderId, title, titleManuallySet, createdAtMillis, updatedAtMillis, " +
          "skinId, fontSizeSpOverride, lineSpacingMultiplierOverride, isPinned, deletedAtMillis, contentRevision) " +
          "VALUES ('legacy-note', 'legacy-folder', 'v5 原标题', 0, 1, 2, 'minimal-white', NULL, NULL, 0, NULL, 7)",
      )
      sqlite.execSQL(
        "INSERT INTO note_blocks (id, noteId, position, type, text, assetPath, previewPath, widthPercent, alignment, aspectRatio, ocrStatus) " +
          "VALUES ('legacy-v5-block', 'legacy-note', 0, 'TEXT', 'legacy-v5-search-token', NULL, NULL, 100, 'CENTER', NULL, NULL)",
      )
      sqlite.execSQL(
        "INSERT INTO note_search_fts(noteId, title, content) " +
          "VALUES ('legacy-note', 'v5 原标题', 'legacy-v5-search-token')",
      )
      sqlite.version = 5
    } finally {
      sqlite.close()
    }
  }

  private companion object {
    const val TEST_DATABASE = "block-formatting-migration"
  }
}

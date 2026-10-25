package com.anan.anannotes.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.HomeViewPreferenceEntity
import com.anan.anannotes.data.local.MIGRATION_1_2
import com.anan.anannotes.data.local.MIGRATION_2_3
import com.anan.anannotes.data.local.MIGRATION_3_4
import com.anan.anannotes.data.local.MIGRATION_4_5
import com.anan.anannotes.data.local.MIGRATION_5_6
import com.anan.anannotes.data.local.MIGRATION_6_7
import com.anan.anannotes.data.local.MIGRATION_7_8
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ThemeMode
import com.anan.anannotes.data.repository.DisplaySettingsRepository
import com.anan.anannotes.data.repository.SearchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeViewPreferenceMigrationTest {
  @Test
  fun migrationAddsPreferencesWithoutDroppingExistingNotes() = runBlocking {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    context.deleteDatabase(TEST_DATABASE)
    createVersionOneDatabase()

    val database = Room.databaseBuilder(context, AnAnDatabase::class.java, TEST_DATABASE)
      .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
      .build()
    try {
      assertEquals("已有笔记", database.noteDao().getById("note-a")?.title)
      assertEquals(1L, database.noteDao().getById("note-a")?.contentRevision)
      assertEquals("note-a", SearchRepository(database).search("已有笔记").single().noteId)
      assertEquals("note-a", SearchRepository(database).search("legacy-body-search-7d93").single().noteId)
      database.homeViewPreferenceDao().upsert(HomeViewPreferenceEntity("folder-a", "CREATED"))
      database.homeViewPreferenceDao().upsert(HomeViewPreferenceEntity("folder-b", "RECENT_EDITED"))
      assertEquals("CREATED", database.homeViewPreferenceDao().observeByView("folder-a").first()?.sortOrder)
      assertEquals("RECENT_EDITED", database.homeViewPreferenceDao().observeByView("folder-b").first()?.sortOrder)
      assertNull(database.displaySettingsDao().observe().first())
      assertTrue(database.backupRestoreCommitDao().allTransactionIds().isEmpty())
      val settings = DisplaySettings(defaultFontSizeSp = 22f, lineSpacingMultiplier = 1.6f, themeMode = ThemeMode.DARK, defaultSkinId = "night-ink")
      DisplaySettingsRepository(database.displaySettingsDao()).save(settings)
      assertEquals("moonlit-sky", DisplaySettingsRepository(database.displaySettingsDao()).observe().first().defaultSkinId)
    } finally {
      database.close()
      context.deleteDatabase(TEST_DATABASE)
    }
  }

  private fun createVersionOneDatabase() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val schemaText = instrumentation.context.assets
      .open("com.anan.anannotes.data.local.AnAnDatabase/1.json")
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
      sqlite.execSQL("INSERT INTO folders (id, name, kind, createdAtMillis, sortPosition) VALUES ('folder-a', '学习', 'USER', 1, 0)")
      sqlite.execSQL(
        """INSERT INTO notes (
          id, folderId, title, titleManuallySet, createdAtMillis, updatedAtMillis,
          skinId, fontSizeSpOverride, lineSpacingMultiplierOverride, isPinned, deletedAtMillis
        ) VALUES ('note-a', 'folder-a', '已有笔记', 0, 1, 2, 'minimal-white', NULL, NULL, 0, NULL)""",
      )
      sqlite.execSQL(
        """INSERT INTO note_blocks (
          id, noteId, position, type, text, assetPath, previewPath,
          widthPercent, alignment, aspectRatio, ocrStatus
        ) VALUES ('block-a', 'note-a', 0, 'TEXT', 'legacy-body-search-7d93', NULL, NULL, 100, 'CENTER', NULL, NULL)""",
      )
      sqlite.version = 1
    } finally {
      sqlite.close()
    }
  }

  private companion object {
    const val TEST_DATABASE = "home-view-preference-migration"
  }
}

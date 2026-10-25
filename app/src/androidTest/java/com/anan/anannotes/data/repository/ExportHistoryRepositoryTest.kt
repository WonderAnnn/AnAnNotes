package com.anan.anannotes.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.data.local.AnAnDatabase
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportHistoryRepositoryTest {
  @get:Rule val temporaryFolder = TemporaryFolder()
  private lateinit var database: AnAnDatabase
  private lateinit var repository: ExportHistoryRepository
  private val nextId = AtomicInteger()
  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  @Before
  fun setUp() {
    database = Room.inMemoryDatabaseBuilder(context, AnAnDatabase::class.java).build()
    repository = newRepository()
  }

  @After
  fun tearDown() {
    database.close()
  }

  @Test
  fun successKeepsActualReceiptAndFailureKeepsOnlyMetadataAndCode() = runBlocking {
    repository.recordSuccess(
      ExportFormat.TXT,
      ExportEvent.ItemCompleted("note-success", "实际文件名.txt", "content://exports/actual", "文档"),
    )
    repository.recordFailure(
      ExportFormat.PDF,
      ExportEvent.ItemFailed("note-failed", "AN-EXP-003", "无需存储的详细错误信息"),
      "未完成.pdf",
    )

    val records = repository.latest()
    val success = records.single { it.succeeded }
    val failure = records.single { !it.succeeded }
    assertEquals("note-success", success.noteId)
    assertEquals(ExportFormat.TXT, success.format)
    assertEquals("实际文件名.txt", success.fileName)
    assertEquals("content://exports/actual", success.destinationUri)
    assertEquals(1234L, success.createdAtMillis)
    assertNull(success.errorCode)
    assertEquals("note-failed", failure.noteId)
    assertEquals(ExportFormat.PDF, failure.format)
    assertEquals("未完成.pdf", failure.fileName)
    assertEquals("", failure.destinationUri)
    assertEquals("AN-EXP-003", failure.errorCode)
    assertEquals(records, repository.observeLatest().first())
  }

  @Test
  fun retainsNewestOneHundredWithStableInsertionOrderWhenTimestampsTie() = runBlocking {
    repeat(125) { recordSuccess(it) }

    val records = repository.latest()

    assertEquals(100, records.size)
    assertEquals("note-124", records.first().noteId)
    assertEquals("note-25", records.last().noteId)
    assertEquals(100, database.exportHistoryDao().observeLatest(1000).first().size)
    assertEquals(records, repository.observeLatest().first())
    assertEquals(records, repository.latest())
  }

  @Test
  fun failedPruneRollsBackInsertionRatherThanLeavingOneHundredAndOneRows() = runBlocking {
    repeat(100) { recordSuccess(it) }
    database.openHelper.writableDatabase.execSQL(
      "CREATE TRIGGER reject_history_prune BEFORE DELETE ON export_history " +
        "BEGIN SELECT RAISE(ABORT, 'test prune failure'); END",
    )

    val failure = runCatching { recordSuccess(100) }

    assertTrue(failure.isFailure)
    val stored = database.exportHistoryDao().observeLatest(1000).first()
    assertEquals(100, stored.size)
    assertFalse(stored.any { it.noteId == "note-100" })
  }

  @Test
  fun concurrentRecordersStillPersistAtMostOneHundredRows() = runBlocking {
    coroutineScope {
      (0 until 125).map { index -> async(Dispatchers.Default) { recordSuccess(index) } }.awaitAll()
    }

    assertEquals(100, database.exportHistoryDao().observeLatest(1000).first().size)
    assertEquals(100, repository.latest().map { it.id }.distinct().size)
  }

  @Test
  fun clearAndPruningNeverDeleteAnExportedFile() = runBlocking {
    val export = temporaryFolder.newFile("ordinary-export.txt").apply { writeText("保留导出文件") }
    repository.recordSuccess(
      ExportFormat.TXT,
      ExportEvent.ItemCompleted("file-note", export.name, export.toURI().toString()),
    )
    repeat(100) { recordSuccess(it) }
    assertTrue(export.exists())

    repository.clear()

    assertTrue(repository.latest().isEmpty())
    assertTrue(repository.observeLatest().first().isEmpty())
    assertEquals("保留导出文件", export.readText())
  }

  @Test
  fun recordsOrderAndClearedStateSurviveDatabaseReopening() = runBlocking {
    database.close()
    val file = File(temporaryFolder.root, "export-history-test.db")
    database = Room.databaseBuilder(context, AnAnDatabase::class.java, file.absolutePath).build()
    repository = newRepository()
    recordSuccess(1)
    recordSuccess(2)
    val beforeReopen = repository.latest()
    database.close()

    database = Room.databaseBuilder(context, AnAnDatabase::class.java, file.absolutePath).build()
    repository = newRepository()
    assertEquals(beforeReopen, repository.latest())
    assertEquals(listOf("note-2", "note-1"), repository.latest().map { it.noteId })
    repository.clear()
    database.close()

    database = Room.databaseBuilder(context, AnAnDatabase::class.java, file.absolutePath).build()
    repository = newRepository()
    assertTrue(repository.latest().isEmpty())
  }

  private fun newRepository() = ExportHistoryRepository(
    database,
    nowMillis = { 1234L },
    // Reverse lexical IDs make ordering by UUID/id insufficient for tied timestamps.
    newId = { "history-${(1000 - nextId.incrementAndGet()).toString().padStart(4, '0')}" },
  )

  private suspend fun recordSuccess(index: Int) {
    repository.recordSuccess(
      ExportFormat.TXT,
      ExportEvent.ItemCompleted("note-$index", "导出-$index.txt", "content://exports/$index"),
    )
  }
}

package com.anan.anannotes.data.repository

import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.files.PreviewCacheStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageOverviewRepositoryTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun countsOnlyOrdinaryDatabaseSidecarsAssetsAndPreviewSubtree() = runBlocking {
    write("databases/anan-notes.db", 3)
    write("databases/anan-notes.db-wal", 5)
    write("databases/anan-notes.db-shm", 7)
    write("files/notes/assets/original.jpg", 11)
    write("files/notes/assets/nested/another.jpg", 13)
    write("cache/notes/previews/preview.jpg", 17)
    write("cache/notes/previews/nested/another.jpg", 19)
    write("databases/private-vault.db", 101)
    write("files/private/opaque-data", 103)
    write("files/notes/editor-drafts/draft", 107)
    write("files/unrelated-file", 109)
    write("cache/private/opaque-data", 113)
    write("cache/notes/other-cache/entry", 127)
    write("cache/import-staging/entry", 131)

    val result = repository().refresh()

    assertEquals(15L, result.databaseBytes)
    assertEquals(24L, result.assetBytes)
    assertEquals(36L, result.previewBytes)
    assertEquals(75L, result.internalBytes)
    assertEquals(0, result.internalUnreadableEntries)
    assertEquals(0, result.exportedFiles.recordedFileCount)
  }

  @Test
  fun missingOrdinaryDirectoriesAreEmptyWithoutCountingUnrelatedFiles() = runBlocking {
    write("files/private/opaque-data", 21)
    write("cache/unrelated-cache", 23)

    val result = repository().refresh()

    assertEquals(0L, result.internalBytes)
    assertEquals(0, result.internalUnreadableEntries)
  }

  @Test
  fun deduplicatesSuccessfulUrisAndSeparatesUnknownFromUnavailableSizes() = runBlocking {
    val records = listOf(
      record("first", "content://exports/known"),
      record("repeat", "content://exports/known"),
      record("unknown", "content://exports/unknown"),
      record("missing", "content://exports/missing"),
      record("failed", "content://exports/failed", succeeded = false),
    )
    val sizes = mapOf(
      "content://exports/known" to ExportFileSize.Known(128L),
      "content://exports/unknown" to ExportFileSize.Unknown,
      "content://exports/missing" to ExportFileSize.Unavailable,
    )

    val result = repository(records, ExportFileSizeReader { sizes.getValue(it) }).refresh()

    assertEquals(3, result.exportedFiles.recordedFileCount)
    assertEquals(128L, result.exportedFiles.knownBytes)
    assertEquals(1, result.exportedFiles.knownSizeCount)
    assertEquals(1, result.exportedFiles.unknownSizeCount)
    assertEquals(1, result.exportedFiles.unavailableCount)
    assertEquals(2, result.exportedFiles.accessibleFileCount)
  }

  @Test
  fun externalExportsAreNeverAddedToInternalTotal() = runBlocking {
    write("databases/anan-notes.db", 7)
    write("files/notes/assets/original.jpg", 11)
    write("cache/notes/previews/preview.jpg", 13)
    write("external/unrecorded-export.png", 1024)

    val result = repository(
      listOf(record("export", "content://exports/large")),
      ExportFileSizeReader { ExportFileSize.Known(999_999L) },
    ).refresh()

    assertEquals(31L, result.internalBytes)
    assertEquals(999_999L, result.exportedFiles.knownBytes)
    assertEquals(1, result.exportedFiles.recordedFileCount)
  }

  @Test
  fun inaccessibleProviderDoesNotBecomeAKnownZeroByteFile() = runBlocking {
    val result = repository(
      listOf(record("denied", "content://exports/denied")),
      ExportFileSizeReader { throw IOException("provider unavailable") },
    ).refresh()

    assertEquals(0, result.exportedFiles.knownSizeCount)
    assertEquals(0, result.exportedFiles.unknownSizeCount)
    assertEquals(1, result.exportedFiles.unavailableCount)
    assertEquals(0, result.exportedFiles.accessibleFileCount)
  }

  @Test
  fun genuinelyEmptyAccessibleExportIsKnownZeroBytes() = runBlocking {
    val result = repository(
      listOf(record("empty", "content://exports/empty")),
      ExportFileSizeReader { ExportFileSize.Known(0L) },
    ).refresh()

    assertEquals(1, result.exportedFiles.knownSizeCount)
    assertEquals(0L, result.exportedFiles.knownBytes)
    assertEquals(0, result.exportedFiles.unknownSizeCount)
    assertEquals(0, result.exportedFiles.unavailableCount)
  }

  @Test
  fun previewCleanupPreservesOriginalsOtherCachesAndExportedFiles() = runBlocking {
    val original = write("files/notes/assets/original.jpg", 11)
    val otherCache = write("cache/import-staging/entry", 13)
    val export = write("external/export.txt", 17)
    val cacheRoot = File(temporaryFolder.root, "cache")
    PreviewCacheStore(cacheRoot).write("preview-1", byteArrayOf(1, 2, 3))
    val repository = repository()

    assertEquals(1, repository.clearPreviewCache())
    val refreshed = repository.refresh()

    assertEquals(0L, refreshed.previewBytes)
    assertEquals(11L, refreshed.assetBytes)
    assertTrue(original.exists())
    assertEquals(13L, otherCache.length())
    assertEquals(17L, export.length())
  }

  private fun repository(
    records: List<ExportHistoryModel> = emptyList(),
    reader: ExportFileSizeReader = ExportFileSizeReader { ExportFileSize.Unavailable },
  ) = StorageOverviewRepository(
    databaseFile = File(temporaryFolder.root, "databases/anan-notes.db"),
    filesDir = File(temporaryFolder.root, "files"),
    cacheDir = File(temporaryFolder.root, "cache"),
    historyProvider = { records },
    exportFileSizeReader = reader,
  )

  private fun write(path: String, count: Int): File =
    File(temporaryFolder.root, path).apply {
      check(parentFile!!.isDirectory || parentFile!!.mkdirs())
      writeBytes(ByteArray(count) { 1 })
    }

  private fun record(id: String, uri: String, succeeded: Boolean = true) = ExportHistoryModel(
    id = id,
    noteId = "ordinary-note",
    format = ExportFormat.TXT,
    fileName = "$id.txt",
    destinationUri = uri,
    createdAtMillis = 1234L,
    succeeded = succeeded,
    errorCode = if (succeeded) null else "AN-EXP-003",
  )
}

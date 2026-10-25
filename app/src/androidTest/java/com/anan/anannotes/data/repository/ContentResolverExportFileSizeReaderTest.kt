package com.anan.anannotes.data.repository

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverExportFileSizeReaderTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun unknownProviderSizeFallsBackToAccessibleDescriptorStatSize() {
    assertEquals(ExportFileSize.Known(37L), reader().read("content://test-exports/unknown-stat"))
  }

  @Test
  fun negativeProviderSizeFallsBackToAccessibleDescriptorStatSize() {
    assertEquals(ExportFileSize.Known(37L), reader().read("content://test-exports/negative-stat"))
  }

  @Test
  fun accessiblePipeWithUnknownStatRemainsUnknownRatherThanZeroOrUnavailable() {
    assertEquals(ExportFileSize.Unknown, reader().read("content://test-exports/unknown-pipe"))
  }

  @Test
  fun unknownSizeWithDeniedAccessIsUnavailableRatherThanUnknown() {
    assertEquals(ExportFileSize.Unavailable, reader().read("content://test-exports/unknown-denied"))
  }

  @Test
  fun knownQuerySizeStillRequiresActualAccessibility() {
    assertEquals(ExportFileSize.Unavailable, reader().read("content://test-exports/known-denied"))
    assertEquals(ExportFileSize.Unavailable, reader().read("content://test-exports/known-missing"))
  }

  @Test
  fun missingSizeColumnStillChecksAccessibilityAndUsesStat() {
    assertEquals(ExportFileSize.Known(37L), reader().read("content://test-exports/no-size-column"))
  }

  private fun reader(): ContentResolverExportFileSizeReader {
    val file = temporaryFolder.newFile().apply { writeBytes(ByteArray(37) { 1 }) }
    return ContentResolverExportFileSizeReader(ContentResolver.wrap(SizeProvider(file)))
  }

  private class SizeProvider(private val file: File) : ContentProvider() {
    override fun onCreate() = true

    override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
    ): Cursor {
      if (uri.lastPathSegment == "no-size-column") {
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("export.txt")) }
      }
      val size: Long? = when (uri.lastPathSegment) {
        "known-denied", "known-missing" -> 37L
        "negative-stat" -> -1L
        else -> null
      }
      return MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any?>(size)) }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
      assertEquals("r", mode)
      return when (uri.lastPathSegment) {
        "unknown-denied", "known-denied" -> throw SecurityException("No read grant")
        "known-missing" -> throw FileNotFoundException("Export no longer exists")
        "unknown-pipe" -> ParcelFileDescriptor.createPipe().let { (read, write) ->
          write.close()
          read
        }
        else -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      }
    }

    override fun getType(uri: Uri) = "text/plain"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
  }
}

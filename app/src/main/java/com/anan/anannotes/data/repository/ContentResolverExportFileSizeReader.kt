package com.anan.anannotes.data.repository

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException

/** Call on an IO dispatcher. A readable descriptor is required even if SIZE is known. */
class ContentResolverExportFileSizeReader(private val resolver: ContentResolver) : ExportFileSizeReader {
  override fun read(uriString: String): ExportFileSize {
    val uri = Uri.parse(uriString)
    if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return ExportFileSize.Unavailable

    val queriedSize = try {
      resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
          cursor.getLong(column).takeIf { it >= 0L }
        } else null
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      null
    }

    return try {
      resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
        val bytes = queriedSize ?: try {
          descriptor.statSize.takeIf { it >= 0L }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          null
        }
        if (bytes == null) ExportFileSize.Unknown else ExportFileSize.Known(bytes)
      } ?: ExportFileSize.Unavailable
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      ExportFileSize.Unavailable
    }
  }
}

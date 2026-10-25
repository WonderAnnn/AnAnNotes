package com.anan.anannotes.feature.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ImportContentReader(private val contentResolver: ContentResolver) {
  suspend fun readText(uri: Uri): String = withContext(Dispatchers.IO) {
    val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment
    val mimeType = contentResolver.getType(uri)
    val input = contentResolver.openInputStream(uri) ?: throw IOException("无法打开所选文件")
    input.use { ImportTextRules.readUtf8(displayName, mimeType, it) }
  }
}

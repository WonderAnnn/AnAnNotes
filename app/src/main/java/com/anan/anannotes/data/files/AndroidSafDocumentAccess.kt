package com.anan.anannotes.data.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.anan.anannotes.feature.export.OutputTarget
import java.io.InputStream
import java.io.OutputStream

/** Android SAF adapter; kept separate so the file-store transaction can be tested deterministically. */
internal class AndroidSafDocumentAccess(
  private val contentResolver: ContentResolver,
) : SafDocumentAccess {
  override fun listNames(target: OutputTarget.DocumentTree): Set<String> {
    val treeUri = target.uriString.toUri()
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
    val projection = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    )
    val cursor = contentResolver.query(childrenUri, projection, null, null, null)
      ?: throw java.io.FileNotFoundException("无法读取所选文件夹")
    return cursor.use { rows ->
      val nameColumn = rows.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      buildSet {
        while (rows.moveToNext()) add(rows.getString(nameColumn))
      }
    }
  }

  override fun create(
    target: OutputTarget.DocumentTree,
    name: String,
    mimeType: String,
  ): SafDocumentRef? {
    val treeUri = target.uriString.toUri()
    val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
    val uri = DocumentsContract.createDocument(contentResolver, parentUri, mimeType, name) ?: return null
    return SafDocumentRef(uri.toString(), name, queryDisplayName(parentUri))
  }

  override fun openOutputStream(document: SafDocumentRef): OutputStream? =
    contentResolver.openOutputStream(document.uriString.toUri(), "w")

  override fun openInputStream(uriString: String): InputStream? = contentResolver.openInputStream(uriString.toUri())

  override fun displayName(document: SafDocumentRef): String? = queryDisplayName(document.uriString.toUri())

  private fun queryDisplayName(uri: Uri): String? = contentResolver.query(
    uri,
    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
    null,
    null,
    null,
  )?.use { cursor ->
    val column = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
  }

  override fun delete(document: SafDocumentRef): Boolean =
    DocumentsContract.deleteDocument(contentResolver, document.uriString.toUri())
}

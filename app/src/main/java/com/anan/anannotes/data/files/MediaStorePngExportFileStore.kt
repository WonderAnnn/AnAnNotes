package com.anan.anannotes.data.files

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.MediaStore
import androidx.core.net.toUri
import com.anan.anannotes.feature.export.ExportAssetReadException
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportFileReceipt
import com.anan.anannotes.feature.export.ExportLayout
import com.anan.anannotes.feature.export.ExportOperationException
import com.anan.anannotes.feature.export.OrdinaryExportService
import com.anan.anannotes.feature.export.SocialPngExportFileStore
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MediaStorePngDocumentRef(
  val uriString: String,
  val displayName: String,
)

internal interface MediaStorePngDocumentAccess {
  fun listNames(): Set<String>
  fun createPending(displayName: String): MediaStorePngDocumentRef?
  fun openOutputStream(document: MediaStorePngDocumentRef): OutputStream?
  fun displayName(document: MediaStorePngDocumentRef): String?
  fun publish(document: MediaStorePngDocumentRef): Boolean
  fun delete(document: MediaStorePngDocumentRef): Boolean
}

/** Writes a PNG to the public Pictures collection, publishing only after its bytes are complete. */
class MediaStorePngExportFileStore internal constructor(
  private val documents: MediaStorePngDocumentAccess,
) : SocialPngExportFileStore {
  constructor(contentResolver: ContentResolver) : this(AndroidMediaStorePngDocumentAccess(contentResolver))

  override suspend fun writePng(
    requestedName: String,
    content: (OutputStream) -> Unit,
  ): ExportFileReceipt = withContext(Dispatchers.IO) {
    var createdDocument: MediaStorePngDocumentRef? = null
    try {
      val uniqueName = ExportLayout.uniqueFileName(requestedName, documents.listNames())
      val document = documents.createPending(uniqueName)
        ?: throw ExportOperationException(
          ExportErrorCode.DESTINATION_UNAVAILABLE,
          "无法在系统相册创建图片，请检查存储空间后重试。",
        )
      createdDocument = document
      val output = documents.openOutputStream(document)
        ?: throw IOException("系统没有返回可写入的 PNG 文件流")
      output.use(content)
      if (!documents.publish(document)) throw IOException("MediaStore 无法发布完整图片")
      ExportFileReceipt(
        fileName = runCatching { documents.displayName(document) }.getOrNull() ?: document.displayName,
        location = document.uriString,
        destinationName = EXPORT_DESTINATION_NAME,
      )
    } catch (error: CancellationException) {
      if (!deletePartial(createdDocument)) {
        error.addSuppressed(IOException("无法清理不完整图片“${createdDocument?.displayName ?: requestedName}”"))
      }
      throw error
    } catch (error: Exception) {
      if (!deletePartial(createdDocument)) {
        val partialName = createdDocument?.displayName ?: requestedName
        throw ExportOperationException(
          ExportErrorCode.WRITE_FAILED,
          "导出失败且无法清理不完整图片“$partialName”，请在系统相册或文件管理器中手动删除后重试。",
          error,
        )
      }
      if (error is ExportOperationException) throw error
      throw mapFailure(error)
    }
  }

  private fun deletePartial(document: MediaStorePngDocumentRef?): Boolean =
    document?.let { runCatching { documents.delete(it) }.getOrDefault(false) } ?: true

  private fun mapFailure(error: Exception): ExportOperationException {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    val outOfSpace = causes.any {
      it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC
    } || causes.any {
      it.message?.let { message ->
        message.contains("ENOSPC", ignoreCase = true) || message.contains("no space left", ignoreCase = true)
      } == true
    }
    return when {
      outOfSpace -> ExportOperationException(
        ExportErrorCode.INSUFFICIENT_STORAGE,
        "系统相册存储空间不足，请清理空间后重试。",
        error,
      )
      causes.any { it is ExportAssetReadException } -> ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "笔记中的图片原图无法读取，请重新插入图片后重试。",
        error,
      )
      error is SecurityException || error is FileNotFoundException -> ExportOperationException(
        ExportErrorCode.DESTINATION_UNAVAILABLE,
        "无法访问系统相册，请检查系统存储状态后重试。",
        error,
      )
      else -> ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "写入 PNG 失败，请检查系统存储后重试。",
        error,
      )
    }
  }

  companion object {
    const val EXPORT_RELATIVE_PATH = "Pictures/AnAn Notes/Exports/"
    const val EXPORT_DESTINATION_NAME = "图片/安安简记/导出"
  }
}

private class AndroidMediaStorePngDocumentAccess(
  private val contentResolver: ContentResolver,
) : MediaStorePngDocumentAccess {
  private val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

  override fun listNames(): Set<String> {
    val cursor = contentResolver.query(
      collection,
      arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
      "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
      arrayOf(MediaStorePngExportFileStore.EXPORT_RELATIVE_PATH),
      null,
    ) ?: throw FileNotFoundException("无法读取系统相册导出目录")
    return cursor.use { rows ->
      val nameColumn = rows.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
      buildSet {
        while (rows.moveToNext()) add(rows.getString(nameColumn))
      }
    }
  }

  override fun createPending(displayName: String): MediaStorePngDocumentRef? {
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
      put(MediaStore.Images.Media.MIME_TYPE, OrdinaryExportService.MIME_PNG)
      put(MediaStore.Images.Media.RELATIVE_PATH, MediaStorePngExportFileStore.EXPORT_RELATIVE_PATH)
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = contentResolver.insert(collection, values) ?: return null
    return MediaStorePngDocumentRef(uri.toString(), displayName)
  }

  override fun openOutputStream(document: MediaStorePngDocumentRef): OutputStream? =
    contentResolver.openOutputStream(document.uriString.toUri(), "w")

  override fun displayName(document: MediaStorePngDocumentRef): String? = contentResolver.query(
    document.uriString.toUri(),
    arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
    null,
    null,
    null,
  )?.use { cursor ->
    val nameColumn = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
    if (nameColumn >= 0 && cursor.moveToFirst()) cursor.getString(nameColumn) else null
  }

  override fun publish(document: MediaStorePngDocumentRef): Boolean {
    val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
    return contentResolver.update(document.uriString.toUri(), values, null, null) > 0
  }

  override fun delete(document: MediaStorePngDocumentRef): Boolean =
    contentResolver.delete(document.uriString.toUri(), null, null) > 0
}

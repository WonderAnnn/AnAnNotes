package com.anan.anannotes.data.files

import android.content.ContentResolver
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportAssetReadException
import com.anan.anannotes.feature.export.ExportFileReceipt
import com.anan.anannotes.feature.export.ExportFileStore
import com.anan.anannotes.feature.export.ExportLayout
import com.anan.anannotes.feature.export.ExportOperationException
import com.anan.anannotes.feature.export.ExportSerializationException
import com.anan.anannotes.feature.export.OutputTarget
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SafDocumentRef(
  val uriString: String,
  val displayName: String,
  val destinationName: String? = null,
)

internal interface SafDocumentAccess {
  fun listNames(target: OutputTarget.DocumentTree): Set<String>
  fun create(target: OutputTarget.DocumentTree, name: String, mimeType: String): SafDocumentRef?
  fun openOutputStream(document: SafDocumentRef): OutputStream?
  fun openInputStream(uriString: String): InputStream? = null
  fun displayName(document: SafDocumentRef): String?
  fun delete(document: SafDocumentRef): Boolean
}

/** Writes one user-authorized SAF document and removes only that document if writing fails. */
class SafExportFileStore internal constructor(
  private val documents: SafDocumentAccess,
) : ExportFileStore {
  constructor(contentResolver: ContentResolver) : this(AndroidSafDocumentAccess(contentResolver))

  override suspend fun write(
    target: OutputTarget.DocumentTree,
    requestedName: String,
    mimeType: String,
    content: (OutputStream) -> Unit,
  ): ExportFileReceipt = withContext(Dispatchers.IO) {
    var createdDocument: SafDocumentRef? = null
    try {
      val existingNames = documents.listNames(target)
      val uniqueName = ExportLayout.uniqueFileName(requestedName, existingNames)
      val document = documents.create(target, uniqueName, mimeType)
        ?: throw ExportOperationException(
          ExportErrorCode.DESTINATION_UNAVAILABLE,
          "无法在所选文件夹创建文件，请重新选择保存位置。",
        )
      createdDocument = document
      val output = documents.openOutputStream(document)
        ?: throw IOException("系统没有返回可写入的文件流")
      output.use(content)
      ExportFileReceipt(
        fileName = runCatching { documents.displayName(document) }.getOrNull() ?: document.displayName,
        location = document.uriString,
        destinationName = document.destinationName,
      )
    } catch (error: CancellationException) {
      if (!deletePartial(createdDocument)) {
        error.addSuppressed(IOException("无法清理不完整文件“${createdDocument?.displayName ?: requestedName}”"))
      }
      throw error
    } catch (error: Exception) {
      if (!deletePartial(createdDocument)) {
        val partialName = createdDocument?.displayName ?: requestedName
        throw ExportOperationException(
          ExportErrorCode.WRITE_FAILED,
          "导出失败且无法清理不完整文件“$partialName”，请在保存文件夹中手动删除后重试。",
          error,
        )
      }
      if (error is ExportOperationException) throw error
      throw mapFailure(error)
    }
  }

  private fun deletePartial(document: SafDocumentRef?): Boolean =
    document?.let { runCatching { documents.delete(it) }.getOrDefault(false) } ?: true

  private fun mapFailure(error: Exception): ExportOperationException {
    val causes = generateSequence(error as Throwable?) { it.cause }.toList()
    val outOfSpace = causes.any { it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC } ||
      causes.any {
        it.message?.let { message ->
          message.contains("ENOSPC", ignoreCase = true) || message.contains("no space left", ignoreCase = true)
        } == true
      }
    return when {
      outOfSpace -> ExportOperationException(
        ExportErrorCode.INSUFFICIENT_STORAGE,
        "保存空间不足，请清理空间后重试。",
        error,
      )
      causes.any { it is ExportAssetReadException } -> ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "笔记中的图片原图无法读取，请重新插入图片后重试。",
        error,
      )
      causes.any { it is ExportSerializationException } -> ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "笔记正文格式异常，请先检查正文格式后重试。",
        error,
      )
      error is SecurityException || error is FileNotFoundException -> ExportOperationException(
        ExportErrorCode.DESTINATION_UNAVAILABLE,
        "无法访问所选文件夹，请重新选择保存位置。",
        error,
      )
      else -> ExportOperationException(
        ExportErrorCode.WRITE_FAILED,
        "写入文件失败，请检查保存位置后重试。",
        error,
      )
    }
  }
}

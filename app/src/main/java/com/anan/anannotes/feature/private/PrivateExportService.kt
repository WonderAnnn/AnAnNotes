package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.supportsAttribution
import com.anan.anannotes.feature.export.ExportAssetReadException
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportFileReceipt
import com.anan.anannotes.feature.export.ExportFileStore
import com.anan.anannotes.feature.export.ExportLayout
import com.anan.anannotes.feature.export.ExportOperationException
import com.anan.anannotes.feature.export.ExportSerializationException
import com.anan.anannotes.feature.export.ExportSnapshot
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.feature.export.PdfExportRenderer
import com.anan.anannotes.feature.export.SocialPngExportFileStore
import com.anan.anannotes.feature.export.SocialPngExportRenderer
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

data class PrivateExportRequest(
  val noteId: PrivateNoteId,
  val format: ExportFormat,
  val target: OutputTarget,
  val includeAttribution: Boolean = false,
) {
  fun scope(): PrivateExportScope = PrivateExportScope(
    noteId = noteId,
    format = format,
    destination = when (target) {
      is OutputTarget.DocumentTree -> target.uriString
      OutputTarget.MediaStorePictures -> MEDIA_STORE_SCOPE
    },
    includeAttribution = includeAttribution && format.supportsAttribution(),
  )

  private companion object {
    const val MEDIA_STORE_SCOPE = "media-store://Pictures/AnAn-Notes/Exports"
  }
}

sealed interface PrivateExportEvent {
  data object AuthorizationRequired : PrivateExportEvent
  data object Busy : PrivateExportEvent
  data class Progress(val completed: Int, val total: Int) : PrivateExportEvent
  data class ItemCompleted(
    val noteId: PrivateNoteId,
    val fileName: String,
    val location: String,
    val destinationName: String? = null,
  ) : PrivateExportEvent
  data class ItemFailed(val noteId: PrivateNoteId, val code: String, val message: String) : PrivateExportEvent
  data class Completed(val succeeded: Int, val failed: Int, val historyFailures: Int) : PrivateExportEvent
}

interface PrivateExportDataSource {
  suspend fun read(access: com.anan.anannotes.security.PrivateAccess, noteId: PrivateNoteId): ExportSnapshot?
  fun openImage(access: com.anan.anannotes.security.PrivateAccess, block: BlockModel): InputStream?
}

fun interface PrivateExportHistorySink {
  suspend fun record(
    access: com.anan.anannotes.security.PrivateAccess,
    record: PrivateExportHistoryRecord,
  )
}

/** Private export pipeline. It never calls OrdinaryExporter or an ordinary history repository. */
class PrivateExportService(
  private val data: PrivateExportDataSource,
  private val files: ExportFileStore,
  private val pngFiles: SocialPngExportFileStore?,
  private val history: PrivateExportHistorySink,
  private val pdfRenderer: PdfExportRenderer? = null,
  private val socialPngRenderer: SocialPngExportRenderer? = null,
  private val rasterPngRenderer: SocialPngExportRenderer? = null,
  private val today: () -> LocalDate = LocalDate::now,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) {
  private val mutex = Mutex()

  fun export(
    access: com.anan.anannotes.security.PrivateAccess,
    grant: PrivateExportGrant,
    request: PrivateExportRequest,
  ): Flow<PrivateExportEvent> = flow {
    if (!mutex.tryLock()) {
      emit(PrivateExportEvent.Busy)
      return@flow
    }
    try {
      if (!grant.consume(access, request.scope(), nowMillis())) {
        emit(PrivateExportEvent.AuthorizationRequired)
        return@flow
      }
      var historyFailures = 0
      var succeeded = 0
      var failed = 0
      var snapshot: ExportSnapshot? = null
      var plannedFileName = "无法导出的笔记"
      try {
        val currentSnapshot = data.read(access, request.noteId)
          ?: throw ExportOperationException(ExportErrorCode.NOTE_UNAVAILABLE, "笔记不存在或已移入回收站。")
        snapshot = currentSnapshot
        if (currentSnapshot.document.note.id != request.noteId.value) {
          throw ExportOperationException(ExportErrorCode.NOTE_UNAVAILABLE, "笔记不存在或已移入回收站。")
        }
        val isPng = request.format.isPng()
        val renderSnapshot = currentSnapshot.copy(
          includeAttribution = request.includeAttribution && request.format.supportsAttribution(),
        )
        if (isPng && request.target != OutputTarget.MediaStorePictures) {
          throw ExportOperationException(ExportErrorCode.DESTINATION_UNAVAILABLE, "PNG 将保存到系统相册，请从相册导出入口重试。")
        }
        val tree = request.target as? OutputTarget.DocumentTree
        if (!isPng && tree == null) {
          throw ExportOperationException(ExportErrorCode.DESTINATION_UNAVAILABLE, "此格式需要先选择文件夹。")
        }
        val baseName = ExportLayout.defaultBaseFileName("私密笔记", currentSnapshot.document.note.title, today())
        plannedFileName = "$baseName.${request.format.extension()}"
        if (isPng) {
          var pageCount = 0
          val renderer = (if (request.format.isSocialPng()) socialPngRenderer else rasterPngRenderer)
            ?: unsupportedFormat()
          val destination = pngFiles ?: unsupportedFormat()
          renderer.render(renderSnapshot, request.format, { block -> openPrivateImage(access, block) }) { pageNumber, content ->
            check(pageNumber == pageCount + 1) { "PNG 页码必须连续" }
            if (request.format == ExportFormat.PNG_LONG_IMAGE) check(pageNumber == 1) { "单张长图不能被拆分" }
            val kind = request.format.pageKind()
            val requestedName = when (request.format) {
              ExportFormat.PNG_LONG_IMAGE -> "${baseName}_${kind}.png"
              ExportFormat.PNG_PAGED_600_DPI -> "${baseName}_${kind}_${pageNumber.toString().padStart(3, '0')}.png"
              else -> "${baseName}_${kind}_${pageNumber.toString().padStart(2, '0')}.png"
            }
            plannedFileName = requestedName
            val receipt = destination.writePng(requestedName, content)
            pageCount++
            succeeded++
            val completed = receipt.toPrivateEvent(request.noteId)
            emit(completed)
            if (!recordHistory(access, request, currentSnapshot, receipt)) historyFailures++
            receipt
          }
          check(pageCount > 0) { "PNG 渲染器没有生成页面" }
        } else {
          val output = checkNotNull(tree)
          val mimeType = when (request.format) {
            ExportFormat.PDF -> PDF_MIME
            ExportFormat.TXT -> TEXT_MIME
            ExportFormat.MARKDOWN_ZIP -> ZIP_MIME
            else -> unsupportedFormat()
          }
          val receipt = files.write(output, plannedFileName, mimeType) { stream ->
            when (request.format) {
              ExportFormat.PDF -> (pdfRenderer ?: unsupportedFormat()).render(renderSnapshot, { block -> openPrivateImage(access, block) }, stream)
              ExportFormat.TXT -> stream.write(ExportLayout.toPlainText(currentSnapshot.document).toByteArray(Charsets.UTF_8))
              ExportFormat.MARKDOWN_ZIP -> try {
                ExportLayout.writeMarkdownZip(stream, currentSnapshot.document) { block -> openPrivateImage(access, block) }
              } catch (error: IllegalArgumentException) {
                throw ExportSerializationException("笔记正文格式无效", error)
              }
            }
          }
          succeeded++
          emit(receipt.toPrivateEvent(request.noteId))
          if (!recordHistory(access, request, currentSnapshot, receipt)) historyFailures++
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        failed++
        val code = (error as? ExportOperationException)?.code ?: ExportErrorCode.WRITE_FAILED
        val message = error.message?.takeIf(String::isNotBlank) ?: "写入文件失败，请检查保存位置后重试。"
        val item = PrivateExportEvent.ItemFailed(request.noteId, code, message)
        emit(item)
        if (!recordFailure(access, request, snapshot, plannedFileName, item)) historyFailures++
      }
      emit(PrivateExportEvent.Progress(completed = 1, total = 1))
      emit(PrivateExportEvent.Completed(succeeded, failed, historyFailures))
    } finally {
      mutex.unlock()
    }
  }

  private fun openPrivateImage(
    access: com.anan.anannotes.security.PrivateAccess,
    block: BlockModel,
  ): InputStream = try {
    data.openImage(access, block) ?: throw ExportAssetReadException("私密图片原图无法读取")
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (error: Exception) {
    throw ExportAssetReadException("私密图片原图无法读取", error)
  }

  private suspend fun recordHistory(
    access: com.anan.anannotes.security.PrivateAccess,
    request: PrivateExportRequest,
    snapshot: ExportSnapshot,
    receipt: ExportFileReceipt,
  ): Boolean = try {
    history.record(access, PrivateExportHistoryRecord(
      id = newId(), noteId = request.noteId, noteTitle = snapshot.document.note.title,
      format = request.format, fileName = receipt.fileName, destinationUri = receipt.location,
      createdAtMillis = nowMillis(), succeeded = true,
    ))
    true
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    false
  }

  private suspend fun recordFailure(
    access: com.anan.anannotes.security.PrivateAccess,
    request: PrivateExportRequest,
    snapshot: ExportSnapshot?,
    fileName: String,
    failure: PrivateExportEvent.ItemFailed,
  ): Boolean = try {
    history.record(access, PrivateExportHistoryRecord(
      id = newId(), noteId = request.noteId, noteTitle = snapshot?.document?.note?.title.orEmpty(),
      format = request.format, fileName = fileName, destinationUri = request.scope().destination,
      createdAtMillis = nowMillis(), succeeded = false, errorCode = failure.code,
    ))
    true
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    false
  }

  private fun ExportFileReceipt.toPrivateEvent(noteId: PrivateNoteId) =
    PrivateExportEvent.ItemCompleted(noteId, fileName, location, destinationName)

  private fun ExportFormat.isPng() = this in setOf(
    ExportFormat.PNG_PAGED_600_DPI,
    ExportFormat.PNG_LONG_IMAGE,
    ExportFormat.PNG_SOCIAL_SQUARE,
    ExportFormat.PNG_SOCIAL_PORTRAIT,
  )

  private fun ExportFormat.isSocialPng() =
    this == ExportFormat.PNG_SOCIAL_SQUARE || this == ExportFormat.PNG_SOCIAL_PORTRAIT

  private fun ExportFormat.pageKind() = when (this) {
    ExportFormat.PNG_SOCIAL_SQUARE -> "方图"
    ExportFormat.PNG_SOCIAL_PORTRAIT -> "竖图"
    ExportFormat.PNG_PAGED_600_DPI -> "A4"
    ExportFormat.PNG_LONG_IMAGE -> "长图"
    else -> unsupportedFormat()
  }

  private fun ExportFormat.extension() = when (this) {
    ExportFormat.PDF -> "pdf"
    ExportFormat.TXT -> "txt"
    ExportFormat.MARKDOWN_ZIP -> "zip"
    else -> "png"
  }

  private fun unsupportedFormat(): Nothing = throw ExportOperationException(
    ExportErrorCode.WRITE_FAILED,
    "此导出格式尚未完成。",
  )

  private companion object {
    const val PDF_MIME = "application/pdf"
    const val TEXT_MIME = "text/plain"
    const val ZIP_MIME = "application/zip"
  }
}

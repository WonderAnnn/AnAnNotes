package com.anan.anannotes.feature.export

import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.supportsAttribution
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.data.repository.DisplaySettingsRepository
import com.anan.anannotes.data.repository.FolderRepository
import com.anan.anannotes.data.repository.NoteRepository
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex

sealed interface OutputTarget {
  /** A string keeps this contract testable on the local JVM without Android Uri stubs. */
  data class DocumentTree(val uriString: String) : OutputTarget
  data object MediaStorePictures : OutputTarget
}

data class ExportRequest(
  val noteIds: List<String>,
  val format: ExportFormat,
  val target: OutputTarget,
  val includeAttribution: Boolean = false,
)

sealed interface ExportEvent {
  data class Progress(val completed: Int, val total: Int) : ExportEvent
  data class ItemCompleted(
    val noteId: String,
    val fileName: String,
    val location: String,
    val destinationName: String? = null,
  ) : ExportEvent
  data class ItemFailed(val noteId: String, val code: String, val message: String) : ExportEvent
  data class Completed(
    val succeeded: Int,
    val failed: Int,
    val completedFiles: List<ItemCompleted> = emptyList(),
    val failedItems: List<ItemFailed> = emptyList(),
    val historyFailures: Int = 0,
  ) : ExportEvent
  data object Busy : ExportEvent
}

interface OrdinaryExporter {
  fun export(request: ExportRequest): Flow<ExportEvent>
}

data class ExportSnapshot(
  val document: NoteDocument,
  val folderName: String,
  val displaySettings: DisplaySettings = DisplaySettings(),
  val includeAttribution: Boolean = false,
)

fun interface PdfExportRenderer {
  fun render(
    snapshot: ExportSnapshot,
    openImage: (BlockModel) -> InputStream,
    output: OutputStream,
  )
}

fun interface SocialPngExportRenderer {
  suspend fun render(
    snapshot: ExportSnapshot,
    format: ExportFormat,
    openImage: (BlockModel) -> InputStream,
    writePage: suspend (pageNumber: Int, content: (OutputStream) -> Unit) -> ExportFileReceipt,
  )
}

fun interface SocialPngExportFileStore {
  suspend fun writePng(
    requestedName: String,
    content: (OutputStream) -> Unit,
  ): ExportFileReceipt
}

fun interface ExportSnapshotProvider {
  suspend fun read(noteId: String): ExportSnapshot?
}

class RoomExportSnapshotProvider(
  private val notes: NoteRepository,
  private val folders: FolderRepository,
  private val displaySettings: DisplaySettingsRepository? = null,
) : ExportSnapshotProvider {
  override suspend fun read(noteId: String): ExportSnapshot? {
    val document = notes.getDocumentSnapshot(noteId) ?: return null
    if (document.note.deletedAtMillis != null) return null
    val folderName = folders.observeFolders().first()
      .firstOrNull { it.id == document.note.folderId }
      ?.name
      ?: "未分类"
    return ExportSnapshot(
      document = document,
      folderName = folderName,
      displaySettings = displaySettings?.observe()?.first() ?: DisplaySettings(),
    )
  }
}

interface ExportFileStore {
  suspend fun write(
    target: OutputTarget.DocumentTree,
    requestedName: String,
    mimeType: String,
    content: (OutputStream) -> Unit,
  ): ExportFileReceipt
}

data class ExportFileReceipt(
  val fileName: String,
  val location: String,
  val destinationName: String? = null,
)

object ExportErrorCode {
  const val NOTE_UNAVAILABLE = "AN-EXP-001"
  const val INSUFFICIENT_STORAGE = "AN-EXP-002"
  const val DESTINATION_UNAVAILABLE = "AN-EXP-003"
  const val WRITE_FAILED = "AN-EXP-004"
}

class ExportOperationException(
  val code: String,
  override val message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

class ExportAssetReadException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ExportSerializationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Exports one immutable snapshot per note; a failure never rolls back another note's file. */
class OrdinaryExportService(
  private val snapshots: ExportSnapshotProvider,
  private val files: ExportFileStore,
  private val openImage: (BlockModel) -> InputStream?,
  private val pdfRenderer: PdfExportRenderer? = null,
  private val socialPngRenderer: SocialPngExportRenderer? = null,
  private val socialPngFiles: SocialPngExportFileStore? = null,
  private val today: () -> LocalDate = LocalDate::now,
  private val rasterPngRenderer: SocialPngExportRenderer? = null,
  private val history: ExportHistoryRecorder? = null,
) : OrdinaryExporter {
  private val batchMutex = Mutex()

  override fun export(request: ExportRequest): Flow<ExportEvent> = flow {
    if (!batchMutex.tryLock()) {
      emit(ExportEvent.Busy)
      return@flow
    }
    try {
    val noteIds = request.noteIds.distinct()
    var succeeded = 0
    var failed = 0
    var historyFailures = 0
    val completedFiles = mutableListOf<ExportEvent.ItemCompleted>()
    val failedItems = mutableListOf<ExportEvent.ItemFailed>()

    suspend fun recordHistory(action: suspend (ExportHistoryRecorder) -> Unit) {
      val recorder = history ?: return
      try { action(recorder) }
      catch (error: CancellationException) { throw error }
      catch (_: Exception) { historyFailures++ }
    }

    noteIds.forEachIndexed { index, noteId ->
      var historyFileName = "无法导出的笔记"
      val result = try {
        val isSocialPng = request.format.isSocialPng()
        val isPng = isSocialPng || request.format == ExportFormat.PNG_PAGED_600_DPI || request.format == ExportFormat.PNG_LONG_IMAGE
        if (isPng && request.target != OutputTarget.MediaStorePictures) {
          throw ExportOperationException(
            ExportErrorCode.DESTINATION_UNAVAILABLE,
            "PNG 将保存到系统相册，请从相册导出入口重试。",
          )
        }
        val documentTree = request.target as? OutputTarget.DocumentTree
        if (!isPng && documentTree == null) {
          throw ExportOperationException(
            ExportErrorCode.DESTINATION_UNAVAILABLE,
            "此格式需要先选择文件夹。",
          )
        }
        val snapshot = snapshots.read(noteId)
          ?: throw ExportOperationException(ExportErrorCode.NOTE_UNAVAILABLE, "笔记不存在或已移入回收站。")
        val renderSnapshot = snapshot.copy(
          includeAttribution = request.includeAttribution && request.format.supportsAttribution(),
        )
        val document = snapshot.document
        val baseName = ExportLayout.defaultBaseFileName(
          folderName = snapshot.folderName,
          title = document.note.title,
          date = today(),
        )
        historyFileName = "$baseName.${if (isPng) "png" else extensionFor(request.format)}"
        if (isPng) {
          val renderer = (if (isSocialPng) socialPngRenderer else rasterPngRenderer) ?: unsupportedFormat()
          val pngFiles = socialPngFiles ?: unsupportedFormat()
          var writtenPages = 0
          renderer.render(renderSnapshot, request.format, ::openImageForExport) { pageNumber, content ->
            require(pageNumber > 0) { "PNG 页码必须从 1 开始" }
            check(pageNumber == writtenPages + 1) { "PNG 页码必须连续" }
            if (request.format == ExportFormat.PNG_LONG_IMAGE) check(pageNumber == 1) { "单张长图不能被拆分成多个文件" }
            val pageKind = when (request.format) {
              ExportFormat.PNG_SOCIAL_SQUARE -> "方图"
              ExportFormat.PNG_SOCIAL_PORTRAIT -> "竖图"
              ExportFormat.PNG_PAGED_600_DPI -> "A4"
              ExportFormat.PNG_LONG_IMAGE -> "长图"
              else -> unsupportedFormat()
            }
            val fileName = when (request.format) {
              ExportFormat.PNG_LONG_IMAGE -> "${baseName}_${pageKind}.png"
              ExportFormat.PNG_PAGED_600_DPI -> "${baseName}_${pageKind}_${pageNumber.toString().padStart(3, '0')}.png"
              else -> "${baseName}_${pageKind}_${pageNumber.toString().padStart(2, '0')}.png"
            }
            historyFileName = fileName
            val receipt = pngFiles.writePng(fileName, content)
            val item = ExportEvent.ItemCompleted(
              noteId = noteId,
              fileName = receipt.fileName,
              location = receipt.location,
              destinationName = receipt.destinationName,
            )
            writtenPages++
            succeeded++
            completedFiles += item
            recordHistory { it.recordSuccess(request.format, item) }
            emit(item)
            receipt
          }
          check(writtenPages > 0) { "PNG 渲染器没有生成页面" }
          null
        } else {
          val target = checkNotNull(documentTree)
          val extension = extensionFor(request.format)
          val fileName = "$baseName.$extension"
          val mimeType = when (request.format) {
            ExportFormat.TXT -> MIME_TEXT
            ExportFormat.MARKDOWN_ZIP -> MIME_ZIP
            ExportFormat.PDF -> MIME_PDF
            ExportFormat.PNG_PAGED_600_DPI,
            ExportFormat.PNG_LONG_IMAGE,
            ExportFormat.PNG_SOCIAL_SQUARE,
            ExportFormat.PNG_SOCIAL_PORTRAIT,
            -> unsupportedFormat()
          }
          val receipt = files.write(target, fileName, mimeType) { output ->
            when (request.format) {
              ExportFormat.TXT -> writeUtf8(output, ExportLayout.toPlainText(document))
              ExportFormat.MARKDOWN_ZIP -> try {
                ExportLayout.writeMarkdownZip(output, document, ::openImageForExport)
              } catch (error: IllegalArgumentException) {
                throw ExportSerializationException("笔记正文格式无效", error)
              }
              ExportFormat.PDF -> (pdfRenderer ?: unsupportedFormat())
                .render(renderSnapshot, ::openImageForExport, output)
              ExportFormat.PNG_PAGED_600_DPI,
              ExportFormat.PNG_LONG_IMAGE,
              ExportFormat.PNG_SOCIAL_SQUARE,
              ExportFormat.PNG_SOCIAL_PORTRAIT,
              -> unsupportedFormat()
            }
          }
          ExportEvent.ItemCompleted(noteId, receipt.fileName, receipt.location, receipt.destinationName)
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: ExportOperationException) {
        ExportEvent.ItemFailed(noteId, error.code, error.message)
      } catch (error: Exception) {
        ExportEvent.ItemFailed(
          noteId = noteId,
          code = ExportErrorCode.WRITE_FAILED,
          message = error.message?.takeIf(String::isNotBlank) ?: "写入文件失败，请检查保存位置后重试。",
        )
      }

      when (result) {
        null -> Unit
        is ExportEvent.ItemCompleted -> {
          succeeded++; completedFiles += result
          recordHistory { it.recordSuccess(request.format, result) }
          emit(result)
        }
        is ExportEvent.ItemFailed -> {
          failed++; failedItems += result
          recordHistory { it.recordFailure(request.format, result, historyFileName) }
        }
        is ExportEvent.Progress, is ExportEvent.Completed -> error("导出单项结果类型无效")
        ExportEvent.Busy -> error("导出单项结果类型无效")
      }
      if (result is ExportEvent.ItemFailed) emit(result)
      emit(ExportEvent.Progress(completed = index + 1, total = noteIds.size))
    }
    emit(ExportEvent.Completed(succeeded, failed, completedFiles.toList(), failedItems.toList(), historyFailures))
    } finally {
      batchMutex.unlock()
    }
  }

  private fun extensionFor(format: ExportFormat): String = when (format) {
    ExportFormat.TXT -> "txt"
    ExportFormat.MARKDOWN_ZIP -> "zip"
    ExportFormat.PDF -> "pdf"
    ExportFormat.PNG_PAGED_600_DPI,
    ExportFormat.PNG_LONG_IMAGE,
    ExportFormat.PNG_SOCIAL_SQUARE,
    ExportFormat.PNG_SOCIAL_PORTRAIT,
    -> unsupportedFormat()
  }

  private fun unsupportedFormat(): Nothing = throw ExportOperationException(
    ExportErrorCode.WRITE_FAILED,
    "此导出格式尚未完成。",
  )

  private fun ExportFormat.isSocialPng(): Boolean =
    this == ExportFormat.PNG_SOCIAL_SQUARE || this == ExportFormat.PNG_SOCIAL_PORTRAIT

  private fun writeUtf8(output: OutputStream, text: String) {
    output.write(text.toByteArray(Charsets.UTF_8))
    output.flush()
  }

  private fun openImageForExport(block: BlockModel): InputStream {
    val stream = try {
      openImage(block)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      throw ExportAssetReadException("读取笔记图片失败", error)
    } ?: throw ExportAssetReadException("笔记图片没有可读取的原图")
    return object : InputStream() {
      override fun read(): Int = try {
        stream.read()
      } catch (error: IOException) {
        throw ExportAssetReadException("读取笔记图片失败", error)
      }

      override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        stream.read(buffer, offset, length)
      } catch (error: IOException) {
        throw ExportAssetReadException("读取笔记图片失败", error)
      }

      override fun close() {
        try {
          stream.close()
        } catch (error: IOException) {
          throw ExportAssetReadException("关闭笔记图片失败", error)
        }
      }
    }
  }

  companion object {
    const val MIME_TEXT = "text/plain"
    const val MIME_ZIP = "application/zip"
    const val MIME_PDF = "application/pdf"
    const val MIME_PNG = "image/png"
  }
}

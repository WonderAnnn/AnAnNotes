package com.anan.anannotes.feature.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.repository.ContentResolverExportFileSizeReader
import com.anan.anannotes.data.repository.ExportFileSize
import com.anan.anannotes.data.repository.ExportFileSizeReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Checks the receipt URI before launching, granting temporary read access only. */
class ExportHistoryFileActions(
  private val context: Context,
  private val fileSizeReader: ExportFileSizeReader = ContentResolverExportFileSizeReader(context.contentResolver),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  suspend fun open(record: ExportHistoryModel): String? = launch(record, share = false)

  suspend fun share(record: ExportHistoryModel): String? = launch(record, share = true)

  private suspend fun launch(record: ExportHistoryModel, share: Boolean): String? {
    if (!record.succeeded) return "该记录导出失败，没有可用的导出文件。"
    val uri = Uri.parse(record.destinationUri)
    if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
      return "导出文件无法访问，请重新导出。"
    }
    val availability = try {
      withContext(ioDispatcher) { fileSizeReader.read(record.destinationUri) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      ExportFileSize.Unavailable
    }
    if (availability == ExportFileSize.Unavailable) {
      return "文件已不存在或无法访问，请检查文件和访问授权，或重新导出。"
    }

    val fileIntent = if (share) {
      Intent(Intent.ACTION_SEND).setType(record.format.mimeType()).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
      Intent(Intent.ACTION_VIEW).setDataAndType(uri, record.format.mimeType())
    }
    fileIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    fileIntent.clipData = ClipData.newRawUri(record.fileName, uri)
    val launchIntent = if (share) {
      Intent.createChooser(fileIntent, "分享导出文件").apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = fileIntent.clipData
      }
    } else fileIntent
    if (context !is Activity) launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
      withContext(Dispatchers.Main) { context.startActivity(launchIntent) }
      null
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: ActivityNotFoundException) {
      if (share) "没有可分享此文件的应用。" else "没有可打开此文件的应用。"
    } catch (_: SecurityException) {
      "导出文件无法访问，请检查访问授权或重新导出。"
    } catch (_: Exception) {
      if (share) "无法分享导出文件，请重试。" else "无法打开导出文件，请重试。"
    }
  }

  private fun ExportFormat.mimeType(): String = when (this) {
    ExportFormat.PDF -> "application/pdf"
    ExportFormat.TXT -> "text/plain"
    ExportFormat.MARKDOWN_ZIP -> "application/zip"
    ExportFormat.PNG_PAGED_600_DPI, ExportFormat.PNG_LONG_IMAGE,
    ExportFormat.PNG_SOCIAL_SQUARE, ExportFormat.PNG_SOCIAL_PORTRAIT -> "image/png"
  }
}

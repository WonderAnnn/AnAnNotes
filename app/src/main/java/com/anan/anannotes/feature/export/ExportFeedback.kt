package com.anan.anannotes.feature.export

/** Short user-facing completion summary; keeps file names, location and error codes together. */
object ExportFeedback {
  fun format(event: ExportEvent.Completed): String {
    val destination = event.completedFiles.mapNotNull { it.destinationName?.takeIf(String::isNotBlank) }
      .distinct()
      .singleOrNull()
      ?: "所选文件夹"
    val names = event.completedFiles.take(2).joinToString("、") { it.fileName } +
      if (event.completedFiles.size > 2) " 等" else ""
    val failures = event.failedItems.take(2).joinToString("；") { "${it.code}：${it.message}" }

    val summary = when {
      event.failed == 0 -> "已导出 ${event.succeeded} 个文件到“$destination”：$names"
      event.succeeded == 0 -> "导出失败：$failures"
      else -> "导出完成：成功 ${event.succeeded}，失败 ${event.failed}；保存到“$destination”：$names。$failures"
    }
    return summary + if (event.historyFailures > 0) "。${event.historyFailures} 条导出记录未保存，已导出的文件不受影响。" else ""
  }
}

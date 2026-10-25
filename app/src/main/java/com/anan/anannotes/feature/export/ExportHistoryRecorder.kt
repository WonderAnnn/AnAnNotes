package com.anan.anannotes.feature.export

import com.anan.anannotes.core.model.ExportFormat

/** Ordinary export metadata only. Private exports must use a separately encrypted store. */
interface ExportHistoryRecorder {
  suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted)
  suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String)
}

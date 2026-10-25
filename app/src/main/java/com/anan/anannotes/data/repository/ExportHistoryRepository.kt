package com.anan.anannotes.data.repository

import androidx.room.withTransaction
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.local.ExportHistoryEntity
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportHistoryRecorder
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Stores ordinary export metadata; deleting metadata never accesses exported files. */
class ExportHistoryRepository(
  private val database: AnAnDatabase,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val newId: () -> String = { UUID.randomUUID().toString() },
) : ExportHistoryRecorder {
  private val dao = database.exportHistoryDao()

  fun observeLatest(): Flow<List<ExportHistoryModel>> =
    dao.observeLatest(HISTORY_LIMIT).map { records -> records.map { it.toModel() } }

  suspend fun latest(): List<ExportHistoryModel> = dao.getLatest(HISTORY_LIMIT).map { it.toModel() }

  suspend fun clear() {
    dao.clear()
  }

  override suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted) {
    record(format, item.noteId, item.fileName, item.location, succeeded = true, errorCode = null)
  }

  override suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String) {
    record(format, item.noteId, fileName, destinationUri = "", succeeded = false, errorCode = item.code)
  }

  private suspend fun record(
    format: ExportFormat,
    noteId: String,
    fileName: String,
    destinationUri: String,
    succeeded: Boolean,
    errorCode: String?,
  ) {
    database.withTransaction {
      dao.insert(
        ExportHistoryEntity(
          id = newId(),
          noteId = noteId,
          format = format.name,
          fileName = fileName,
          destinationUri = destinationUri,
          createdAtMillis = nowMillis(),
          succeeded = succeeded,
          errorCode = errorCode,
        ),
      )
      dao.keepLatest(HISTORY_LIMIT)
    }
  }

  private fun ExportHistoryEntity.toModel() = ExportHistoryModel(
    id = id,
    noteId = noteId,
    format = ExportFormat.valueOf(format),
    fileName = fileName,
    destinationUri = destinationUri,
    createdAtMillis = createdAtMillis,
    succeeded = succeeded,
    errorCode = errorCode,
  )

  private companion object {
    const val HISTORY_LIMIT = 100
  }
}

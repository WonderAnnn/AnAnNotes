package com.anan.anannotes.feature.export

import android.app.Application
import com.anan.anannotes.data.files.MediaStorePngExportFileStore
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.files.SafExportFileStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.DisplaySettingsRepository
import com.anan.anannotes.data.repository.ExportHistoryRepository
import com.anan.anannotes.data.repository.FolderRepository
import com.anan.anannotes.data.repository.NoteRepository

/** Builds the regular, non-private export pipeline for any ordinary-note entry point. */
object OrdinaryExportServiceFactory {
  fun create(application: Application): OrdinaryExportService {
    val database = AnAnDatabase.getInstance(application)
    val images = OriginalImageStore(application.filesDir)
    val notes = NoteRepository(database, assetStore = images)
    val folders = FolderRepository(database)
    return OrdinaryExportService(
      snapshots = RoomExportSnapshotProvider(
        notes = notes,
        folders = folders,
        displaySettings = DisplaySettingsRepository(database.displaySettingsDao()),
      ),
      files = SafExportFileStore(application.contentResolver),
      socialPngFiles = MediaStorePngExportFileStore(application.contentResolver),
      openImage = { block -> block.assetPath?.let { path -> images.resolve(path).inputStream() } },
      pdfRenderer = AndroidPdfExportRenderer(application.resources),
      socialPngRenderer = AndroidSocialPngExportRenderer(application.resources),
      rasterPngRenderer = AndroidRasterPngExportRenderer(application.resources),
      history = ExportHistoryRepository(database),
    )
  }
}

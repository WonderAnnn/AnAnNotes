package com.anan.anannotes.feature.privatemodule

import android.app.Application
import com.anan.anannotes.data.files.MediaStorePngExportFileStore
import com.anan.anannotes.data.files.SafExportFileStore
import com.anan.anannotes.feature.export.AndroidPdfExportRenderer
import com.anan.anannotes.feature.export.AndroidRasterPngExportRenderer
import com.anan.anannotes.feature.export.AndroidSocialPngExportRenderer

/** Creates an export path that depends only on the encrypted private repository and generic file writers. */
internal object PrivateExportServiceFactory {
  fun create(
    application: Application,
    repository: PrivateRepository,
    store: PrivateStore,
  ): PrivateExportService = PrivateExportService(
    data = PrivateRepositoryExportDataSource(repository, store),
    files = SafExportFileStore(application.contentResolver),
    pngFiles = MediaStorePngExportFileStore(application.contentResolver),
    history = PrivateExportHistorySink(repository::recordPrivateExport),
    pdfRenderer = AndroidPdfExportRenderer(application.resources),
    socialPngRenderer = AndroidSocialPngExportRenderer(application.resources),
    rasterPngRenderer = AndroidRasterPngExportRenderer(application.resources),
  )
}

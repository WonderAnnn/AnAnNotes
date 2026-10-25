package com.anan.anannotes.data.repository

/** Unknown bytes and inaccessible files remain distinct from an accessible empty file. */
sealed interface ExportFileSize {
  data class Known(val bytes: Long) : ExportFileSize {
    init { require(bytes >= 0L) }
  }
  data object Unknown : ExportFileSize
  data object Unavailable : ExportFileSize
}

fun interface ExportFileSizeReader {
  fun read(uriString: String): ExportFileSize
}

data class ExportStorageSummary(
  val recordedFileCount: Int = 0,
  val knownBytes: Long = 0L,
  val knownSizeCount: Int = 0,
  val unknownSizeCount: Int = 0,
  val unavailableCount: Int = 0,
) {
  val accessibleFileCount: Int get() = knownSizeCount + unknownSizeCount
}

data class StorageOverview(
  val databaseBytes: Long = 0L,
  val assetBytes: Long = 0L,
  val previewBytes: Long = 0L,
  val internalUnreadableEntries: Int = 0,
  val exportedFiles: ExportStorageSummary = ExportStorageSummary(),
) {
  /** Only the three ordinary internal categories; external exports are excluded. */
  val internalBytes: Long get() = databaseBytes + assetBytes + previewBytes
}

package com.anan.anannotes.core.model

enum class ExportFormat {
  PDF,
  PNG_PAGED_600_DPI,
  PNG_LONG_IMAGE,
  PNG_SOCIAL_SQUARE,
  PNG_SOCIAL_PORTRAIT,
  TXT,
  MARKDOWN_ZIP,
}

fun ExportFormat.supportsAttribution(): Boolean = when (this) {
  ExportFormat.PDF,
  ExportFormat.PNG_PAGED_600_DPI,
  ExportFormat.PNG_LONG_IMAGE,
  ExportFormat.PNG_SOCIAL_SQUARE,
  ExportFormat.PNG_SOCIAL_PORTRAIT,
  -> true
  ExportFormat.TXT,
  ExportFormat.MARKDOWN_ZIP,
  -> false
}

data class ExportHistoryModel(
  val id: String,
  val noteId: String?,
  val format: ExportFormat,
  val fileName: String,
  val destinationUri: String,
  val createdAtMillis: Long,
  val succeeded: Boolean,
  val errorCode: String? = null,
)

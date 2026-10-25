package com.anan.anannotes.core.model

data class NoteSearchResult(
  val noteId: String,
  val title: String,
  val folderName: String,
  val snippet: String,
  val updatedAtMillis: Long,
)

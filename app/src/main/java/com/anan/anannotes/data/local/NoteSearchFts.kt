package com.anan.anannotes.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4

@Fts4(tokenizer = androidx.room.FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["noteId"])
@Entity(tableName = "note_search_fts")
data class NoteSearchFtsEntity(
  val noteId: String,
  val title: String,
  val content: String,
)

data class NoteSearchHitRow(
  val noteId: String,
  val title: String,
  val content: String,
  @ColumnInfo(name = "folderName") val folderName: String,
  val updatedAtMillis: Long,
)

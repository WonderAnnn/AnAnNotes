package com.anan.anannotes.core.model

data class TodoModel(
  val id: String,
  val noteId: String,
  val blockId: String,
  val text: String,
  val isCompleted: Boolean = false,
  val updatedAtMillis: Long,
)

data class TodoFeedItem(
  val id: String,
  val noteId: String,
  val blockId: String,
  val text: String,
  val isCompleted: Boolean,
  val updatedAtMillis: Long,
  val noteTitle: String,
  val folderName: String,
)


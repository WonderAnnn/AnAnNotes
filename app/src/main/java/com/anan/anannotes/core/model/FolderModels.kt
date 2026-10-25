package com.anan.anannotes.core.model

enum class FolderKind {
  USER,
  UNCATEGORIZED,
}

data class FolderModel(
  val id: String,
  val name: String,
  val kind: FolderKind,
  val createdAtMillis: Long,
  val sortPosition: Int,
)

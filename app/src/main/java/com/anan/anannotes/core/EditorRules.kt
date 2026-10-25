package com.anan.anannotes.core

fun autoTitleFromBody(body: String): String = body.lineSequence().firstOrNull()?.trim().orEmpty()

fun editorTitle(body: String, manualTitle: String?, hasManualTitle: Boolean): String {
  val title = if (hasManualTitle) manualTitle.orEmpty() else autoTitleFromBody(body)
  return title.ifBlank { "无标题笔记" }
}

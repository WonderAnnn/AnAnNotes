package com.anan.anannotes.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorRulesTest {
  @Test
  fun firstBodyLineBecomesTitleWithoutChangingBody() {
    val body = "第一行标题\n正文仍保留"

    assertEquals("第一行标题", autoTitleFromBody(body))
    assertEquals("第一行标题\n正文仍保留", body)
  }

  @Test
  fun manualTitleOverridesBodyTitle() {
    assertEquals("手动标题", editorTitle(body = "正文第一行", manualTitle = "手动标题", hasManualTitle = true))
  }

  @Test
  fun emptyBodyUsesTemporaryUntitledLabel() {
    assertEquals("无标题笔记", editorTitle(body = "\n  ", manualTitle = null, hasManualTitle = false))
  }
}

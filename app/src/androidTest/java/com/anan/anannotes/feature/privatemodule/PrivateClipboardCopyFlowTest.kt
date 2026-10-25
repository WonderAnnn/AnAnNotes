package com.anan.anannotes.feature.privatemodule

import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.MainActivity
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.feature.export.ClipboardTextWriter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateClipboardCopyFlowTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  @SdkSuppress(minSdkVersion = 33)
  fun privateCopyNeedsConfirmationThenWritesSensitivePlainText() {
    val activity = composeRule.activity
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      ClipboardTextWriter.copy(activity, "原有剪贴板内容", sensitive = false)
    }
    val note = PrivateNoteDocument(
      id = PrivateNoteId("private-clipboard-flow"),
      title = "私密标题",
      blocks = listOf(NoteBlockDraft(id = "private-copy-body", type = BlockType.TEXT, text = "私密正文")),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    activity.runOnUiThread {
      activity.addContentView(
        ComposeView(activity).apply {
          setContent {
            PrivateEditorScreen(
              note = note,
              isSaving = false,
              errorMessage = null,
              onBack = {},
              onSave = { _, _, _ -> },
              onCopyText = { PrivateClipboardWriter.copy(activity.applicationContext, it) },
            )
          }
        },
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
      )
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("private-editor-more").performClick()
    composeRule.onNodeWithTag("private-editor-toolbar").performTouchInput { swipeLeft() }
    composeRule.onNodeWithTag("private-editor-copy-full-text").performClick()
    composeRule.onNodeWithTag("private-copy-warning").assertIsDisplayed()
    assertClipboard("原有剪贴板内容")

    composeRule.onNodeWithTag("private-copy-confirm").performClick()
    composeRule.waitUntil(timeoutMillis = 5_000) {
      val clip = activity.getSystemService(Context.CLIPBOARD_SERVICE)
        .let { it as ClipboardManager }
        .primaryClip
      clip?.getItemAt(0)?.coerceToText(activity)?.toString() == "私密标题\n\n私密正文"
    }
    // The writer's ClipDescription sensitive-extra contract is covered directly in ClipboardTextWriterTest.
    // The API 35 SystemUI clipboard overlay may replace extras with its own suppression metadata in UI tests.
    assertClipboard("私密标题\n\n私密正文")
  }

  private fun assertClipboard(expectedText: String) {
    val activity = composeRule.activity
    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    composeRule.waitUntil(timeoutMillis = 5_000) {
      clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString() == expectedText
    }
    val clip = checkNotNull(clipboard.primaryClip)
    assertEquals(expectedText, clip.getItemAt(0).coerceToText(activity).toString())
  }
}

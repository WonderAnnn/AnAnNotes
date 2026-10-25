package com.anan.anannotes.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FormattingDecodeError
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.feature.privatemodule.PrivateEditorScreen
import com.anan.anannotes.feature.privatemodule.PrivateEditSnapshot
import com.anan.anannotes.feature.privatemodule.PrivateNoteDocument
import com.anan.anannotes.feature.privatemodule.PrivateNoteId
import com.anan.anannotes.feature.privatemodule.PrivateTodo
import com.anan.anannotes.feature.privatemodule.PrivateTodoDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateRichTextEditorTest {
  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun privateExportIsAvailableOnlyForAnUnmodifiedSavedNote() {
    val exported = mutableStateOf(false)
    val note = PrivateNoteDocument(
      id = PrivateNoteId("private-export-ui-test"),
      title = "已保存标题",
      blocks = listOf(NoteBlockDraft(id = "private-export-body", type = BlockType.TEXT, text = "已保存内容")),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    composeRule.setContent {
      PrivateEditorScreen(
        note = note,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, _, _ -> },
        onExport = { exported.value = true },
      )
    }

    composeRule.onNodeWithText("更多").performClick()
    composeRule.onNodeWithTag("private-editor-toolbar").performTouchInput { swipeLeft() }
    composeRule.onNodeWithTag("private-editor-export").assertIsDisplayed().assertIsEnabled().performClick()
    assertTrue(exported.value)
    composeRule.onNodeWithTag("private-body-field").performTextInput("未保存")
    composeRule.onNodeWithTag("private-editor-export").assertIsNotEnabled()
    composeRule.onNodeWithTag("private-editor-export-hint").assertIsDisplayed()
  }

  @Test
  fun privateEditorCanSelectSakuraSkinAndReportsThePersistableSkinId() {
    val selectedSkin = mutableStateOf<String?>(null)
    val note = PrivateNoteDocument(
      id = PrivateNoteId("private-sakura-skin-test"),
      title = "私密春日",
      skinId = "minimal-white",
      blocks = listOf(NoteBlockDraft(id = "private-sakura-body", type = BlockType.TEXT, text = "花下记录")),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    composeRule.setContent {
      PrivateEditorScreen(
        note = note,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, _, _ -> },
        onSkinChange = { selectedSkin.value = it },
      )
    }

    composeRule.onNodeWithTag("private-editor-skin-button").performClick()
    composeRule.onNodeWithTag("skin-picker-sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-sakura-petal-paper"))
    composeRule.onNodeWithTag("skin-option-sakura-petal-paper").performClick()
    composeRule.onNodeWithTag("skin-option-sakura-petal-paper").assertIsSelected()
    assertEquals("sakura-petal-paper", selectedSkin.value)
  }

  @Test
  fun privateFullTextCopyRequiresConfirmationAndKeepsTodoStatus() {
    val copiedText = mutableStateOf<String?>(null)
    val note = PrivateNoteDocument(
      id = PrivateNoteId("private-copy-confirmation-test"),
      title = "私密标题",
      blocks = listOf(
        NoteBlockDraft(id = "copy-body", type = BlockType.TEXT, text = "正文"),
        NoteBlockDraft(id = "copy-quote", type = BlockType.QUOTE, text = "引用内容"),
        NoteBlockDraft(id = "copy-ocr", type = BlockType.OCR_TEXT, text = "识别结果"),
        NoteBlockDraft(id = "copy-done", type = BlockType.TODO, text = "已完成事项"),
        NoteBlockDraft(id = "copy-open", type = BlockType.TODO, text = "待办事项"),
        NoteBlockDraft(id = "copy-image", type = BlockType.IMAGE, assetPath = "encrypted:private-image"),
      ),
      todos = listOf(
        PrivateTodo(
          id = "completed-private-todo",
          blockId = "copy-done",
          text = "已完成事项",
          completed = true,
          updatedAtMillis = 5L,
        ),
      ),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    composeRule.setContent {
      PrivateEditorScreen(
        note = note,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, _, _ -> },
        onCopyText = { copiedText.value = it },
      )
    }

    composeRule.onNodeWithTag("private-editor-more").performClick()
    composeRule.onNodeWithTag("private-editor-toolbar").performTouchInput { swipeLeft() }
    composeRule.onNodeWithTag("private-editor-copy-full-text").assertIsDisplayed().performClick()

    composeRule.onNodeWithTag("private-copy-warning").assertIsDisplayed()
    assertEquals(null, copiedText.value)
    composeRule.onNodeWithTag("private-copy-cancel").performClick()
    composeRule.onAllNodesWithTag("private-copy-warning").assertCountEquals(0)
    assertEquals(null, copiedText.value)
    composeRule.onNodeWithTag("private-editor-copy-full-text").performClick()
    composeRule.onNodeWithTag("private-copy-warning").assertIsDisplayed()
    composeRule.onNodeWithTag("private-copy-confirm").performClick()
    assertEquals(
      "私密标题\n\n正文\n\n> 引用内容\n\n识别结果\n\n- [x] 已完成事项\n\n- [ ] 待办事项",
      copiedText.value,
    )
  }

  @Test
  fun newPrivateNoteCannotExportBeforeItHasBeenSaved() {
    composeRule.setContent {
      PrivateEditorScreen(
        note = null,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, _, _ -> },
      )
    }

    composeRule.onNodeWithText("更多").performClick()
    composeRule.onNodeWithTag("private-editor-export").assertIsNotEnabled()
    composeRule.onNodeWithText("保存笔记后可导出").assertIsDisplayed()
  }

  @Test
  fun privateEditorOffersTheSameThreeImageImportModes() {
    composeRule.setContent {
      PrivateEditorScreen(
        note = null,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, _, _ -> },
      )
    }

    composeRule.onNodeWithTag("private-editor-image").performClick()
    composeRule.onNodeWithTag("ocr-import-image-only").assertIsDisplayed()
    composeRule.onNodeWithTag("ocr-import-image-and-text").assertIsDisplayed()
    composeRule.onNodeWithTag("ocr-import-text-only").assertIsDisplayed()
  }

  @Test
  fun privateOcrFailureShowsRetryWithoutExposingSourceAsset() {
    var retried = false
    val block = NoteBlockDraft(
      id = "private-failed-ocr",
      type = BlockType.OCR_TEXT,
      assetPath = "private-asset:encrypted-source",
      ocrStatus = OcrStatus.FAILED,
    )
    composeRule.setContent {
      PrivateEditorScreen(
        note = PrivateNoteDocument(
          id = PrivateNoteId("private-ocr-ui"),
          title = "OCR",
          blocks = listOf(block),
          createdAtMillis = 1L,
          updatedAtMillis = 1L,
          contentRevision = 1L,
        ),
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, _, _ -> },
        onRetryOcr = { retried = true },
      )
    }

    composeRule.onNodeWithTag("private-ocr-status-private-failed-ocr").assertIsDisplayed()
    composeRule.onNodeWithTag("private-ocr-retry-private-failed-ocr").performClick()
    assertTrue(retried)
    assertTrue(composeRule.onAllNodesWithText("private-asset:encrypted-source").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun privateRichTextEditingPreservesBlockIdsImagesAndFormatting() {
    val quote = NoteBlockDraft(
      id = "private-quote-id",
      type = BlockType.QUOTE,
      text = "引用文字",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.ITALIC))),
      ),
    )
    val image = NoteBlockDraft(id = "private-image-id", type = BlockType.IMAGE, assetPath = "private-asset:stable")
    val ocr = NoteBlockDraft(
      id = "private-ocr-id",
      type = BlockType.OCR_TEXT,
      text = "识别文字",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.STRIKETHROUGH))),
      ),
    )
    val todo = NoteBlockDraft(id = "private-todo-id", type = BlockType.TODO, text = "私密待办")
    val captured = mutableStateOf<List<NoteBlockDraft>?>(null)
    val note = PrivateNoteDocument(
      id = PrivateNoteId("private-rich-editor-test"),
      title = "私密富文本测试",
      titleManuallySet = true,
      blocks = listOf(NoteBlockDraft(id = "private-text-id", type = BlockType.TEXT, text = "私密正文"), quote, image, ocr, todo),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )

    composeRule.setContent {
      PrivateEditorScreen(
        note = note,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, blocks, _ -> captured.value = blocks },
      )
    }
    composeRule.onNodeWithTag("private-body-field").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 2))
    composeRule.onNodeWithTag("private-editor-bold").performClick()
    composeRule.onNodeWithTag("private-editor-bold").assertIsSelected()
    composeRule.onNodeWithText("更多").performClick()
    composeRule.onNodeWithTag("private-editor-toolbar").performTouchInput { swipeLeft() }
    composeRule.onNodeWithTag("private-editor-quote").performClick()
    composeRule.onNodeWithTag("private-editor-save").performClick()

    composeRule.waitUntil(5_000) { captured.value != null }
    val savedBlocks = captured.value!!
    assertEquals(BlockFormattingState.Supported(BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD)))), savedBlocks[0].formatting)
    assertEquals(quote.id, savedBlocks[1].id)
    assertEquals(quote, savedBlocks[1])
    assertEquals(image, savedBlocks[2])
    assertEquals(ocr, savedBlocks[3])
    assertEquals(todo, savedBlocks[4])
  }

  @Test
  fun privateUnsupportedFormattingRequiresExplicitResetBeforeEditing() {
    val blob = byteArrayOf(0x41, 0x4e, 0x46, 0x4d, 99)
    val captured = mutableStateOf<List<NoteBlockDraft>?>(null)
    val note = PrivateNoteDocument(
      id = PrivateNoteId("private-unsupported-test"),
      title = "未知格式私密笔记",
      titleManuallySet = true,
      blocks = listOf(
        NoteBlockDraft(
          id = "private-unsupported-text",
          type = BlockType.TEXT,
          text = "兼容性正文",
          formatting = BlockFormattingState.Unsupported(blob, FormattingDecodeError.UNSUPPORTED_VERSION),
        ),
      ),
      createdAtMillis = 1L,
      updatedAtMillis = 1L,
      contentRevision = 1L,
    )
    composeRule.setContent {
      PrivateEditorScreen(
        note = note,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, blocks, _ -> captured.value = blocks },
      )
    }

    assertFalse(composeRule.onNodeWithTag("private-body-field").fetchSemanticsNode().config.contains(SemanticsActions.SetText))
    composeRule.onNodeWithTag("reset-unsupported-format").assertIsDisplayed()
    composeRule.onNodeWithTag("reset-unsupported-format").performClick()
    composeRule.onNodeWithText("取消").performClick()
    assertFalse(composeRule.onNodeWithTag("private-body-field").fetchSemanticsNode().config.contains(SemanticsActions.SetText))
    composeRule.onNodeWithTag("reset-unsupported-format").performClick()
    composeRule.onNodeWithTag("confirm-reset-unsupported-format").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onNodeWithTag("private-body-field").fetchSemanticsNode().config.contains(SemanticsActions.SetText)
    }
    composeRule.onNodeWithTag("private-body-field").performTextInput("可编辑")
    composeRule.onNodeWithTag("private-editor-save").performClick()

    composeRule.waitUntil(5_000) { captured.value != null }
    assertEquals("兼容性正文可编辑", captured.value!!.single().text)
    assertEquals(BlockFormattingState.Supported(BlockFormatting()), captured.value!!.single().formatting)
  }

  @Test
  fun matchingEncryptedDraftCanBeRestoredIntoPrivateEditor() {
    val captured = mutableStateOf<List<NoteBlockDraft>?>(null)
    val restored = mutableStateOf(false)
    val draft = PrivateEditSnapshot(
      draftId = "private-recovery-draft",
      noteId = null,
      folderId = "private-uncategorized",
      title = "恢复的私密标题",
      titleManuallySet = true,
      blocks = listOf(
        NoteBlockDraft(
          id = "private-recovery-block",
          type = BlockType.TEXT,
          text = "恢复的私密正文",
          formatting = BlockFormattingState.Supported(
            BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 3, InlineStyle.BOLD))),
          ),
        ),
      ),
      skinId = "minimal-white",
      baseContentRevision = null,
      localRevision = 4L,
      savedAtMillis = 100L,
    )
    composeRule.setContent {
      PrivateEditorScreen(
        note = null,
        isSaving = false,
        errorMessage = null,
        onBack = {},
        onSave = { _, blocks, _ -> captured.value = blocks },
        pendingRecoveryDraft = draft,
        onRecoveryRestored = { restored.value = true },
      )
    }

    composeRule.onNodeWithTag("private-draft-recovery-dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("private-draft-recovery-restore").performClick()
    composeRule.onNodeWithTag("private-body-field").assertTextContains("恢复的私密正文")
    composeRule.onNodeWithTag("private-editor-save").performClick()

    composeRule.waitUntil(5_000) { captured.value != null }
    assertTrue(restored.value)
    assertEquals(draft.blocks.single(), captured.value!!.single())
  }
}

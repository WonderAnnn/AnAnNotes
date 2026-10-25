package com.anan.anannotes.ui

import android.content.Context
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.anan.anannotes.MainActivity
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.DEFAULT_NOTE_SKIN_ID
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.FormattingDecodeError
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.feature.editor.EditorDraftSnapshot
import com.anan.anannotes.feature.editor.FileEditorDraftStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class EditorScreenTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val recoveryDraftId = "b4c71d4e-a620-4c43-a930-a32a2f1e8fe4"
  private val createdNoteIds = mutableListOf<String>()

  @Test
  fun quickCreateOpensTitleBodyAndBasicToolbar() {
    openNewNote()
    composeRule.onNodeWithTag("screen-editor").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-title").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-body").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-basic-toolbar").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-export-button").assertIsDisplayed()
  }

  @Test
  fun editorCopiesTheCurrentTitleAndBodyDirectlyToSystemClipboard() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.onNodeWithTag("editor-title").performTextInput("复制标题")
    composeRule.onNodeWithTag("editor-body").performTextInput("复制正文")
    composeRule.onNodeWithText("更多").performClick()
    composeRule.onNodeWithTag("editor-basic-toolbar").performTouchInput { swipeLeft() }
    composeRule.onNodeWithTag("editor-copy-full-text").assertIsDisplayed().performClick()

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已复制全文").fetchSemanticsNodes().isNotEmpty()
    }
    // The app writes the clip on its own dispatcher; polling avoids reading a stale clip.
    composeRule.waitUntil(5_000) {
      clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() == "复制标题\n\n复制正文"
    }
    val copied = checkNotNull(clipboard.primaryClip).getItemAt(0).coerceToText(context).toString()
    assertEquals("复制标题\n\n复制正文", copied)
  }

  @Test
  @Suppress("DEPRECATION")
  fun editorActivityUsesResizeModeForKeyboardInsets() {
    val adjustmentMode = composeRule.activity.window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
    assertEquals(
      "主 Activity 必须声明 adjustResize 才能向 Compose 分发 IME inset",
      WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
      adjustmentMode,
    )
  }

  @Test
  fun editorToolbarStaysWithinEightDpOfVisibleKeyboardTop() {
    openNewNote()
    val initialToolbarBottomPx = composeRule.onNodeWithTag("editor-basic-toolbar")
      .fetchSemanticsNode().boundsInWindow.bottom
    composeRule.onNodeWithTag("editor-body").performClick()

    val decorView = composeRule.activity.window.decorView
    waitForToolbarAtImeTop("editor-basic-toolbar", decorView, composeRule.activity.resources.displayMetrics.density)

    val rootInsets = checkNotNull(ViewCompat.getRootWindowInsets(decorView))
    val imeBottomPx = rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    val density = composeRule.activity.resources.displayMetrics.density
    val imeTopInWindowPx = decorView.height - imeBottomPx
    val toolbarBottomPx = composeRule.onNodeWithTag("editor-basic-toolbar")
      .fetchSemanticsNode().boundsInWindow.bottom
    val bodyBottomPx = composeRule.onNodeWithTag("editor-content-area")
      .fetchSemanticsNode().boundsInWindow.bottom
    val toolbarTopPx = composeRule.onNodeWithTag("editor-basic-toolbar")
      .fetchSemanticsNode().boundsInWindow.top
    val gapDp = (imeTopInWindowPx - toolbarBottomPx) / density
    val bodyToToolbarGapDp = (toolbarTopPx - bodyBottomPx) / density

    try {
      assertTrue(
        "工具栏应贴合键盘顶边（最多 8dp 间距），实测间隙为 ${"%.1f".format(gapDp)}dp；" +
          "windowBottom=${decorView.height}px、IME bottom=$imeBottomPx px、" +
          "toolbarBottom=${toolbarBottomPx.toInt()}px、density=$density",
        abs(gapDp) <= 8f,
      )
      assertTrue(
        "正文与工具栏之间只能保留紧凑留白：实测间隙=${"%.1f".format(bodyToToolbarGapDp)}dp",
        abs(bodyToToolbarGapDp) <= 16f,
      )
    } finally {
      composeRule.runOnIdle {
        val inputMethodManager = composeRule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(decorView.windowToken, 0)
      }
    }
    composeRule.waitUntil(5_000) {
      ViewCompat.getRootWindowInsets(decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == false
    }
    waitForToolbarAtRest(
      "editor-basic-toolbar",
      decorView,
      initialToolbarBottomPx,
      density,
    )
    composeRule.waitForIdle()
    val finalToolbarBottomPx = composeRule.onNodeWithTag("editor-basic-toolbar")
      .fetchSemanticsNode().boundsInWindow.bottom
    assertTrue(
      "键盘收起后工具栏应恢复到初始位置，不能残留 IME 高度：初始=${initialToolbarBottomPx}px、收起后=${finalToolbarBottomPx}px",
      abs(initialToolbarBottomPx - finalToolbarBottomPx) <= 8f * density,
    )
  }

  @Test
  fun imageMultiSelectActionBarAlsoTracksVisibleKeyboardTop() {
    createImageNote(
      title = "图片多选键盘贴合验证",
      blocks = listOf(
        NoteBlockDraft(id = "ime-image-body", type = BlockType.TEXT, text = "图片前正文"),
        NoteBlockDraft(id = "ime-image-block", type = BlockType.IMAGE, assetPath = "notes/assets/ime-image.jpg"),
      ),
    )
    val decorView = composeRule.activity.window.decorView
    openNote("图片多选键盘贴合验证")
    val imageBlock = composeRule.onNodeWithTag("image-block-ime-image-block")
    imageBlock.performScrollTo().performTouchInput { longClick() }
    composeRule.onNodeWithTag("editor-image-selection-actions").assertIsDisplayed()
    composeRule.waitForIdle()
    val density = composeRule.activity.resources.displayMetrics.density
    val hiddenKeyboardActionBottomPx = composeRule.onNodeWithTag("editor-image-selection-actions")
      .fetchSemanticsNode().boundsInWindow.bottom
    composeRule.onNodeWithTag("editor-cancel-image-selection").performClick()
    composeRule.onNodeWithTag("editor-title").performClick()
    composeRule.onNodeWithTag("editor-body").performClick()

    composeRule.waitUntil(8_000) {
      ViewCompat.getRootWindowInsets(decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    imageBlock.performScrollTo().performTouchInput { longClick() }
    composeRule.onNodeWithTag("editor-image-selection-actions").assertIsDisplayed()
    composeRule.waitUntil(5_000) {
      ViewCompat.getRootWindowInsets(decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    waitForToolbarAtImeTop(
      "editor-image-selection-actions",
      decorView,
      composeRule.activity.resources.displayMetrics.density,
    )

    val rootInsets = checkNotNull(ViewCompat.getRootWindowInsets(decorView))
    val imeBottomPx = rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
    val imeTopInWindowPx = decorView.height - imeBottomPx
    val actionBottomPx = composeRule.onNodeWithTag("editor-image-selection-actions")
      .fetchSemanticsNode().boundsInWindow.bottom
    val gapDp = (imeTopInWindowPx - actionBottomPx) / density

    try {
      assertTrue(
        "图片多选操作栏应贴合键盘顶边，实测间隙 ${"%.1f".format(gapDp)}dp；windowBottom=${decorView.height}px imeBottom=$imeBottomPx actionBottom=$actionBottomPx density=$density",
        abs(gapDp) <= 8f,
      )
    } finally {
      composeRule.runOnIdle {
        val inputMethodManager = composeRule.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(decorView.windowToken, 0)
      }
      composeRule.waitUntil(5_000) {
        ViewCompat.getRootWindowInsets(decorView)
          ?.isVisible(WindowInsetsCompat.Type.ime()) == false
      }
      waitForToolbarAtRest(
        "editor-image-selection-actions",
        decorView,
        hiddenKeyboardActionBottomPx,
        density,
      )
      composeRule.waitForIdle()
    }
    val restoredActionBottomPx = composeRule.onNodeWithTag("editor-image-selection-actions")
      .fetchSemanticsNode().boundsInWindow.bottom
    assertTrue(
      "图片多选操作栏在键盘收起后应恢复到原底部位置：初始=$hiddenKeyboardActionBottomPx px、收起后=$restoredActionBottomPx px",
      abs(hiddenKeyboardActionBottomPx - restoredActionBottomPx) <= 8f * density,
    )
  }

  @Test
  fun toolbarInsertsEditableTodoAtSelectionAndCompletionSyncsToTodoPage() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val originalText = "前选中文本后正文"
    val noteId = createImageNote(
      title = "编辑页待办插入验证",
      blocks = listOf(
        NoteBlockDraft(
          id = "todo-insert-source",
          type = BlockType.TEXT,
          text = originalText,
          formatting = BlockFormattingState.Supported(
            BlockFormatting(
              inlineStyles = listOf(
                InlineStyleRange(0, originalText.length, InlineStyle.BOLD),
                InlineStyleRange(5, originalText.length, InlineStyle.ITALIC),
              ),
            ),
          ),
        ),
      ),
    )

    openNote("编辑页待办插入验证")
    composeRule.onNodeWithTag("editor-body").performClick()
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(1, 5))
    composeRule.onNodeWithTag("editor-insert-todo").performClick()
    waitForEditorTodoField()
    composeRule.onNodeWithTag("editor-todo-text").performTextInput("测试待办")

    val repository = NoteRepository(AnAnDatabase.getInstance(context))
    composeRule.waitUntil(5_000) {
      runBlocking { repository.observeNote(noteId).first() }
        ?.blocks?.any { it.type == BlockType.TODO && it.text == "测试待办" } == true
    }
    val inserted = runBlocking { repository.observeNote(noteId).first()!! }
    assertEquals(listOf("前", "测试待办", "后正文"), inserted.blocks.map { it.text.orEmpty() })
    assertEquals(listOf(BlockType.TEXT, BlockType.TODO, BlockType.TEXT), inserted.blocks.map { it.type })
    assertEquals(
      BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 1, InlineStyle.BOLD))),
      ),
      inserted.blocks[0].formatting,
    )
    assertEquals(
      BlockFormattingState.Supported(
        BlockFormatting(
          inlineStyles = listOf(
            InlineStyleRange(0, 3, InlineStyle.BOLD),
            InlineStyleRange(0, 3, InlineStyle.ITALIC),
          ),
        ),
      ),
      inserted.blocks[2].formatting,
    )

    composeRule.onNodeWithTag("editor-todo-checkbox").performClick()
    val database = AnAnDatabase.getInstance(context)
    composeRule.waitUntil(5_000) {
      runBlocking { database.todoDao().getByNote(noteId).singleOrNull { it.blockId == inserted.blocks[1].id }?.isCompleted == true }
    }
    composeRule.onNodeWithTag("editor-todo-checkbox").assertIsOn()
    composeRule.onNodeWithTag("editor-back").performClick()
    composeRule.onNodeWithTag("nav-todo").performClick()
    composeRule.onNodeWithTag("todo-show-completed").performClick()
    composeRule.onNodeWithText("测试待办").assertIsDisplayed()
    composeRule.onNodeWithText("测试待办").performClick()
    composeRule.onNodeWithTag("editor-todo-checkbox").assertIsOn()
  }

  @Test
  fun insertingTodoWhileEditingTodoAddsItAfterTheFocusedTask() {
    val noteId = createImageNote(
      title = "待办中继续插入验证",
      blocks = listOf(
        NoteBlockDraft(id = "todo-focus-before", type = BlockType.TEXT, text = "前文"),
        NoteBlockDraft(id = "todo-focus-current", type = BlockType.TODO, text = "当前任务"),
        NoteBlockDraft(id = "todo-focus-after", type = BlockType.TEXT, text = "后文"),
      ),
    )

    openNote("待办中继续插入验证")
    composeRule.onNodeWithTag("editor-todo-text").performClick()
    composeRule.onNodeWithTag("editor-insert-todo").performClick()

    val repository = NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
    composeRule.waitUntil(5_000) {
      val typesAndText = runBlocking { repository.observeNote(noteId).first() }?.blocks
        ?.map { it.type to it.text.orEmpty() }
      typesAndText == listOf(
        BlockType.TEXT to "前文",
        BlockType.TODO to "当前任务",
        BlockType.TODO to "",
        BlockType.TEXT to "后文",
      )
    }
  }

  @Test
  fun pressingEnterInTodoCreatesANewIndependentTodoBlock() {
    val noteId = createImageNote(
      title = "回车新增独立待办验证",
      blocks = listOf(NoteBlockDraft(id = "todo-enter-body", type = BlockType.TEXT, text = "正文保留")),
    )
    openNote("回车新增独立待办验证")
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(4))
    composeRule.onNodeWithTag("editor-insert-todo").performClick()
    waitForEditorTodoField()
    composeRule.onNodeWithTag("editor-todo-text").performTextInput("第一项任务")
    composeRule.onNodeWithTag("editor-todo-text").performImeAction()
    composeRule.onAllNodesWithTag("editor-todo-text").get(1).assertIsFocused()
      .performTextInput("第二项任务")

    val repository = NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
    composeRule.waitUntil(5_000) {
      runBlocking { repository.observeNote(noteId).first() }?.blocks
        ?.count { it.type == BlockType.TODO && !it.text.isNullOrBlank() } == 2
    }
    val blocks = runBlocking { repository.observeNote(noteId).first() }!!.blocks
    assertEquals(listOf(BlockType.TEXT, BlockType.TODO, BlockType.TODO, BlockType.TEXT), blocks.map { it.type })
    assertEquals(listOf("正文保留", "第一项任务", "第二项任务", ""), blocks.map { it.text.orEmpty() })
    composeRule.onAllNodesWithTag("editor-todo-text").assertCountEquals(2)
    composeRule.onAllNodesWithTag("editor-todo-checkbox").assertCountEquals(2)
    composeRule.onAllNodesWithTag("editor-todo-checkbox").get(1).assertIsDisplayed()

    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    val storedTodos = runBlocking { database.todoDao().getByNote(noteId) }
    assertEquals(2, storedTodos.size)
    composeRule.onNodeWithTag("editor-back").performClick()
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("nav-todo").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithTag("nav-todo").performClick()
    storedTodos.forEach { todo -> composeRule.onNodeWithTag("todo-item-${todo.id}").assertIsDisplayed() }
    val secondTodo = storedTodos.single { it.text == "第二项任务" }
    composeRule.onNodeWithTag("todo-checkbox-${secondTodo.id}").performClick()
    composeRule.waitUntil(5_000) {
      runBlocking { database.todoDao().getByNote(noteId).single { it.id == secondTodo.id }.isCompleted }
    }
    assertFalse(runBlocking { database.todoDao().getByNote(noteId).single { it.text == "第一项任务" }.isCompleted })
  }

  @Test
  fun repeatedTodoImeActionsCreateSeparateCheckboxesWithoutAbsorbingBodyText() {
    val noteId = createImageNote(
      title = "连续回车待办块隔离验证",
      blocks = listOf(NoteBlockDraft(id = "todo-repeat-source", type = BlockType.TEXT, text = "正文起始")),
    )
    openNote("连续回车待办块隔离验证")
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(4))
    composeRule.onNodeWithTag("editor-insert-todo").performClick()
    waitForEditorTodoField()

    listOf("第一项", "第二项", "第三项").forEachIndexed { index, text ->
      composeRule.onAllNodesWithTag("editor-todo-text").get(index).performTextInput(text)
      if (index < 2) {
        composeRule.onAllNodesWithTag("editor-todo-text").get(index).performImeAction()
        composeRule.onAllNodesWithTag("editor-todo-text").get(index + 1).assertIsFocused()
      }
    }

    composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(6)
    composeRule.onAllNodes(hasSetTextAction()).get(5).performTextInput("后续正文")
    composeRule.onAllNodes(hasSetTextAction()).get(5).assertTextContains("后续正文")

    val repository = NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
    composeRule.waitUntil(5_000) {
      runBlocking { repository.observeNote(noteId).first() }?.blocks
        ?.map { it.text.orEmpty() } == listOf("正文起始", "第一项", "第二项", "第三项", "后续正文")
    }
    val blocks = runBlocking { repository.observeNote(noteId).first() }!!.blocks
    assertEquals(
      listOf(BlockType.TEXT, BlockType.TODO, BlockType.TODO, BlockType.TODO, BlockType.TEXT),
      blocks.map { it.type },
    )
    assertEquals(listOf("正文起始", "第一项", "第二项", "第三项", "后续正文"), blocks.map { it.text.orEmpty() })
    composeRule.onAllNodesWithTag("editor-todo-checkbox").assertCountEquals(3)
    composeRule.onAllNodesWithTag("editor-todo-text").assertCountEquals(3)
  }

  @Test
  @OptIn(ExperimentalTestApi::class)
  fun pressingPhysicalEnterInTodoCreatesANewIndependentBlock() {
    val noteId = createImageNote(
      title = "物理回车待办块验证",
      blocks = listOf(NoteBlockDraft(id = "todo-key-enter-source", type = BlockType.TEXT, text = "正文")),
    )
    openNote("物理回车待办块验证")
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(2))
    composeRule.onNodeWithTag("editor-insert-todo").performClick()
    composeRule.onNodeWithTag("editor-todo-text").performTextInput("第一项")
    composeRule.onNodeWithTag("editor-todo-text").performKeyInput { pressKey(Key.Enter) }

    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("editor-todo-text").fetchSemanticsNodes().size == 2
    }
    composeRule.onAllNodesWithTag("editor-todo-text").get(1).assertIsFocused()
      .performTextInput("第二项")

    val repository = NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
    composeRule.waitUntil(5_000) {
      runBlocking { repository.observeNote(noteId).first() }?.blocks
        ?.count { it.type == BlockType.TODO && !it.text.isNullOrBlank() } == 2
    }
    val blocks = runBlocking { repository.observeNote(noteId).first() }!!.blocks
    assertEquals(listOf(BlockType.TEXT, BlockType.TODO, BlockType.TODO, BlockType.TEXT), blocks.map { it.type })
    assertEquals(listOf("正文", "第一项", "第二项", ""), blocks.map { it.text.orEmpty() })
    composeRule.onAllNodesWithTag("editor-todo-checkbox").assertCountEquals(2)
  }

  @Test
  fun emptyTodoCheckboxRemainsVisiblyContrastedAcrossAllSkins() {
    val skinIds = listOf(
      "minimal-white", "warm-paper", "mint-green", "sakura-petal-paper", "bamboo-shadow",
      "misty-tide", "osmanthus-note", "moonlit-sky", "wisteria-cloud",
      "camellia-letter", "southern-ginkgo",
    )

    skinIds.forEach { skinId ->
      val title = "空待办对比验证-$skinId"
      val noteId = createImageNote(
        title = title,
        skinId = skinId,
        blocks = listOf(
          NoteBlockDraft(id = "filled-$skinId", type = BlockType.TODO, text = "已填写任务"),
          NoteBlockDraft(id = "empty-$skinId", type = BlockType.TODO, text = ""),
        ),
      )

      val gridTag = listOf("note-grid-single", "note-grid-two").first { tag ->
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.waitUntil(10_000) {
        composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithTag(gridTag).performScrollToNode(hasTestTag("note-card-$noteId"))
      openNote(title)
      val emptyCheckbox = composeRule.onAllNodesWithTag("editor-todo-checkbox").get(1)
      emptyCheckbox.assertIsDisplayed()

      val pixels = emptyCheckbox.captureToImage().toPixelMap()
      val background = pixels[0, 0]
      val maximumChannelDifference = (0 until pixels.height).maxOf { y ->
        (0 until pixels.width).maxOf { x ->
          val pixel = pixels[x, y]
          maxOf(abs(pixel.red - background.red), abs(pixel.green - background.green), abs(pixel.blue - background.blue))
        }
      }
      assertTrue("$skinId 皮肤下空待办的禁用复选框边框仍应清楚可见", maximumChannelDifference >= 0.20f)

      composeRule.onNodeWithTag("editor-back").performClick()
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("screen-editor").fetchSemanticsNodes().isEmpty()
      }
    }
  }

  @Test
  fun quicklyReplacingCompletedTodoTextKeepsDatabaseAndEditorCompletionInSync() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val noteId = createImageNote(
      title = "快速改写完成待办验证",
      blocks = listOf(
        NoteBlockDraft(id = "completed-todo-edit", type = BlockType.TODO, text = "原已完成任务"),
        NoteBlockDraft(id = "completed-todo-body", type = BlockType.TEXT, text = "保留正文"),
      ),
    )
    val database = AnAnDatabase.getInstance(context)
    runBlocking {
      val todo = database.todoDao().getByNote(noteId).single { it.blockId == "completed-todo-edit" }
      NoteRepository(database).setTodoCompleted(todo.id, completed = true)
    }

    composeRule.onNodeWithTag("nav-home").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("快速改写完成待办验证").fetchSemanticsNodes().isNotEmpty()
    }
    openNote("快速改写完成待办验证")
    composeRule.onNodeWithTag("editor-todo-checkbox").assertIsOn()
    composeRule.onNodeWithTag("editor-todo-text").performClick()
    composeRule.onNodeWithTag("editor-todo-text").performTextClearance()
    composeRule.onNodeWithTag("editor-todo-text").performTextInput("改写后的任务")
    composeRule.onNodeWithTag("editor-todo-text").assertTextContains("改写后的任务")

    val savedAfterEdit = runCatching {
      composeRule.waitUntil(5_000) {
      runBlocking {
        database.todoDao().getByNote(noteId)
          .singleOrNull { it.blockId == "completed-todo-edit" }
          ?.let { it.text == "改写后的任务" && it.isCompleted } == true
      }
      }
    }.isSuccess
    if (!savedAfterEdit) {
      val saveStatus = composeRule.onNodeWithTag("editor-save-status")
        .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
      val todoRows = runBlocking { database.todoDao().getByNote(noteId) }
      assertTrue("待办修改未写入数据库；saveStatus=$saveStatus, todos=$todoRows", savedAfterEdit)
    }
    composeRule.onNodeWithTag("editor-todo-checkbox").assertIsOn()
  }

  @Test
  fun noteWithTrailingImageProvidesEditableSpaceBelowIt() {
    val noteId = createImageNote(
      title = "图片后续文字区域验证",
      blocks = listOf(
        NoteBlockDraft(
          id = "tail-image",
          type = BlockType.IMAGE,
          assetPath = "notes/assets/missing-tail-image.jpg",
          aspectRatio = 1.5f,
        ),
      ),
    )

    openNote("图片后续文字区域验证")
    composeRule.onNodeWithTag("editor-body-after-image").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("editor-body-after-image").performTextInput("图片下方可继续书写")
    composeRule.waitUntil(5_000) {
      runBlocking { NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())).observeNote(noteId).first() }
        ?.blocks?.lastOrNull()?.text == "图片下方可继续书写"
    }
  }

  @Test
  fun longPressImageEnablesMultiSelectAndDeletesOnlySelectedImages() {
    val noteId = createImageNote(
      title = "图片多选删除验证",
      blocks = listOf(
        NoteBlockDraft(id = "multi-image-one", type = BlockType.IMAGE, assetPath = "notes/assets/missing-one.jpg", widthPercent = 35f, aspectRatio = 1f),
        NoteBlockDraft(id = "multi-image-two", type = BlockType.IMAGE, assetPath = "notes/assets/missing-two.jpg", widthPercent = 35f, aspectRatio = 1f),
        NoteBlockDraft(id = "multi-image-three", type = BlockType.IMAGE, assetPath = "notes/assets/missing-three.jpg", widthPercent = 35f, aspectRatio = 1f),
      ),
    )

    openNote("图片多选删除验证")
    composeRule.onNodeWithTag("image-block-multi-image-one").performTouchInput { longClick() }
    composeRule.onNodeWithText("已选 1 张图片").assertIsDisplayed()
    composeRule.onNodeWithTag("image-block-multi-image-two").performScrollTo().performClick()
    composeRule.onNodeWithText("已选 2 张图片").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-delete-selected-images").performClick()
    composeRule.onNodeWithTag("confirm-delete-images").performClick()

    composeRule.waitUntil(5_000) {
      runBlocking { NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())).observeNote(noteId).first() }
        ?.blocks?.filter { it.type == BlockType.IMAGE }?.map { it.id } == listOf("multi-image-three")
    }
  }

  @Test
  fun oneImageCanBeDeletedFromItsAdjustmentSheetAndUsesSimplifiedChinese() {
    val noteId = createImageNote(
      title = "单张图片删除验证",
      blocks = listOf(
        NoteBlockDraft(id = "single-image-text", type = BlockType.TEXT, text = "图片前文字"),
        NoteBlockDraft(id = "single-image", type = BlockType.IMAGE, assetPath = "notes/assets/missing-single.jpg", aspectRatio = 1f),
      ),
    )

    openNote("单张图片删除验证")
    composeRule.onNodeWithTag("image-block-single-image").performScrollTo().performClick()
    composeRule.onNodeWithText("图片大小").assertIsDisplayed()
    composeRule.onNodeWithText("删除图片").performClick()
    composeRule.onNodeWithTag("confirm-delete-images").performClick()

    composeRule.waitUntil(5_000) {
      runBlocking { NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())).observeNote(noteId).first() }
        ?.blocks?.none { it.type == BlockType.IMAGE } == true
    }
  }

  @Test
  fun imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard() {
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation
    // Keep this title short: landscape note cards ellipsize long titles, and openNote
    // matches the exact text — a longer unique suffix never composes as-is.
    val title = "横屏图片百分比输入验证"

    try {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
      }
      // Let the rotated home screen finish composing before seeding and opening the note;
      // openNote's title wait otherwise races the post-rotation relayout under load.
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("new-note-button").fetchSemanticsNodes().isNotEmpty()
      }
      val noteId = createImageNote(
        title = title,
        blocks = listOf(NoteBlockDraft(id = "landscape-image", type = BlockType.IMAGE, assetPath = "notes/assets/missing.jpg")),
      )
      // This suite occasionally hits bursts of emulator slowness right after rotation;
      // double the usual window for the freshly seeded note to reach the list.
      openNote(title, timeoutMillis = 20_000)
      composeRule.onNodeWithTag("image-block-landscape-image").performScrollTo().performClick()
      composeRule.onNodeWithText("图片大小").assertIsDisplayed()

      composeRule.onNodeWithTag("image-width-percent-input").performClick()
      composeRule.onNodeWithTag("image-width-percent-input").performTextClearance()
      composeRule.onNodeWithTag("image-width-percent-input").performTextInput("73")
      composeRule.onNodeWithTag("image-width-percent-input").assertTextContains("73")
      val decorView = composeRule.activity.window.decorView
      composeRule.waitUntil(8_000) {
        ViewCompat.getRootWindowInsets(decorView)
          ?.isVisible(WindowInsetsCompat.Type.ime()) == true
      }
      composeRule.onNodeWithTag("image-width-percent-input").assertIsDisplayed()
      composeRule.onNodeWithText("完成").performScrollTo()
      val rootInsets = checkNotNull(ViewCompat.getRootWindowInsets(decorView))
      val imeTopInWindowPx = decorView.height - rootInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
      val sheetBounds = composeRule.onNodeWithTag("image-adjustment-sheet").fetchSemanticsNode().boundsInWindow
      val completeBounds = composeRule.onNodeWithText("完成").fetchSemanticsNode().boundsInWindow
      assertTrue(
        "图片比例面板的完成操作必须位于可视键盘上方；windowHeight=${decorView.height}px、" +
          "imeTop=$imeTopInWindowPx、sheet=$sheetBounds、complete=$completeBounds",
        completeBounds.bottom <= imeTopInWindowPx,
      )
      assertTrue(
        "图片比例面板的完成操作必须完整落在面板内容范围内；sheet=$sheetBounds、complete=$completeBounds",
        completeBounds.top >= sheetBounds.top && completeBounds.bottom <= sheetBounds.bottom,
      )
      composeRule.onNodeWithText("完成").assertIsDisplayed().performClick()

      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
      }
      val persistedWidth = runBlocking {
        NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
          .observeNote(noteId).first()?.blocks?.firstOrNull { it.type == BlockType.IMAGE }?.widthPercent
      }
      assertEquals("输入的图片宽度百分比应自动保存到该图片块", 73f, persistedWidth ?: -1f, 0f)
    } finally {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = originalRequestedOrientation
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == originalConfigurationOrientation
      }
    }
  }

  @Test
  fun editorUsesSingleTitleLayerWithFocusedWritingChrome() {
    openNewNote()

    composeRule.onAllNodes(hasTestTag("editor-title-preview")).assertCountEquals(0)
    composeRule.onNodeWithTag("editor-metadata").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-skin-button").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-content-area").assertIsDisplayed()
  }

  @Test
  fun selectedSkinUpdatesPreviewAndPersistsWithNote() {
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("皮肤预览和保存验证")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("editor-skin-button").performClick()
    composeRule.onNodeWithTag("skin-picker-sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-sakura-petal-paper"))
    composeRule.onNodeWithTag("skin-option-sakura-petal-paper").performClick()
    composeRule.onNodeWithTag("skin-option-sakura-petal-paper").assertIsSelected()
    composeRule.onNodeWithTag("skin-live-preview").assertIsDisplayed()
    composeRule.onNodeWithTag("skin-picker-close").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("editor-back").performClick()
    openNote("皮肤预览和保存验证")
    composeRule.onNodeWithTag("editor-skin-button").performClick()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-sakura-petal-paper"))
    composeRule.onNodeWithTag("skin-option-sakura-petal-paper").assertIsSelected()
  }

  @Test
  fun camelliaAndGinkgoSkinsCanBeSelectedAndPersisted() {
    val noteTitle = "新皮肤选择保存验证"
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput(noteTitle)
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("editor-skin-button").performClick()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-camellia-letter"))
    composeRule.onNodeWithTag("skin-option-camellia-letter").performClick().assertIsSelected()
    composeRule.onNodeWithTag("skin-picker-close").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor-back").performClick()

    openNote(noteTitle)
    composeRule.onNodeWithTag("editor-skin-button").performClick()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-camellia-letter"))
    composeRule.onNodeWithTag("skin-option-camellia-letter").assertIsSelected()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-southern-ginkgo"))
    composeRule.onNodeWithTag("skin-option-southern-ginkgo").performClick().assertIsSelected()
    composeRule.onNodeWithTag("skin-picker-close").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor-back").performClick()

    openNote(noteTitle)
    composeRule.onNodeWithTag("editor-skin-button").performClick()
    composeRule.onNodeWithTag("skin-picker-options")
      .performScrollToNode(hasTestTag("skin-option-southern-ginkgo"))
    composeRule.onNodeWithTag("skin-option-southern-ginkgo").assertIsSelected()
  }

  @Test
  fun skinPickerShowsPreviewAndControlsInLandscapeWithKeyboard() {
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation

    try {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
      }
      openNewNote()
      composeRule.onNodeWithTag("editor-body").performClick()
      val decorView = composeRule.activity.window.decorView
      composeRule.waitUntil(8_000) {
        ViewCompat.getRootWindowInsets(decorView)
          ?.isVisible(WindowInsetsCompat.Type.ime()) == true
      }
      composeRule.onNodeWithTag("editor-skin-button").performClick()

      composeRule.onNodeWithTag("skin-live-preview").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("skin-picker-close").assertIsDisplayed()
      composeRule.onNodeWithTag("skin-option-$DEFAULT_NOTE_SKIN_ID").assertIsDisplayed()
      composeRule.onNodeWithTag("skin-picker-close").performClick()
    } finally {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = originalRequestedOrientation
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == originalConfigurationOrientation
      }
    }
  }

  @Test
  fun typingKeepsFirstLineAsTitleAndAutosavesCard() {
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("自动保存验证\n正文保持完整")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor-back").performClick()

    composeRule.onNodeWithText("自动保存验证").assertIsDisplayed()
    composeRule.onNodeWithText("正文保持完整", substring = true).assertIsDisplayed()
  }

  @Test
  fun userCanRestorePersistedCrashDraft() {
    runBlocking {
      val store = draftStore()
      store.writeAtomic(
        EditorDraftSnapshot(
          draftId = recoveryDraftId,
          noteId = null,
          folderId = UNCATEGORIZED_FOLDER_ID,
          baseContentRevision = 0L,
          localRevision = 12L,
          savedAtMillis = System.currentTimeMillis(),
          draft = NoteDraft(
            folderId = UNCATEGORIZED_FOLDER_ID,
            title = "崩溃恢复标题",
            titleManuallySet = true,
            blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "进程退出前的正文内容")),
          ),
        ),
      )
    }

    composeRule.onNodeWithTag("new-note-button").performClick()
    composeRule.onNodeWithTag("draft-recovery-dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("draft-recovery-restore").performClick()
    composeRule.onNodeWithTag("editor-body").assertTextContains("进程退出前的正文内容")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
  }

  @Test
  fun userCanDiscardPersistedCrashDraft() {
    runBlocking {
      draftStore().writeAtomic(
        EditorDraftSnapshot(
          draftId = recoveryDraftId,
          noteId = null,
          folderId = UNCATEGORIZED_FOLDER_ID,
          baseContentRevision = 0L,
          localRevision = 13L,
          savedAtMillis = System.currentTimeMillis(),
          draft = NoteDraft(
            folderId = UNCATEGORIZED_FOLDER_ID,
            title = "准备丢弃的草稿",
            blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "此内容不应进入笔记")),
          ),
        ),
      )
    }

    composeRule.onNodeWithTag("new-note-button").performClick()
    composeRule.onNodeWithTag("draft-recovery-discard").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("draft-recovery-dialog").fetchSemanticsNodes().isEmpty()
    }
    composeRule.onAllNodesWithTag("draft-recovery-dialog").assertCountEquals(0)
    assertFalse(runBlocking { draftStore().listRecoverable().any { it.draftId == recoveryDraftId } })
  }

  @Test
  fun editingNotePreservesSavedTypographyOverrides() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val noteId = runBlocking {
      checkNotNull(
        NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(
          NoteDraft(
            title = "显示设置保留验证",
            titleManuallySet = true,
            blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "显示设置保留验证正文")),
            fontSizeSpOverride = 19f,
            lineSpacingMultiplierOverride = 1.4f,
          ),
        ),
      )
    }
    createdNoteIds += noteId

    openNote("显示设置保留验证")
    composeRule.onNodeWithTag("editor-body").performTextInput("追加内容")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }

    val saved = runBlocking { AnAnDatabase.getInstance(context).noteDao().getById(noteId) }
    assertEquals(19f, saved?.fontSizeSpOverride ?: -1f, 0f)
    assertEquals(1.4f, saved?.lineSpacingMultiplierOverride ?: -1f, 0f)
  }

  @Test
  fun typographySheetCanSavePerNoteOverrides() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.onNodeWithTag("editor-text-settings").performClick()
    composeRule.onNodeWithTag("text-settings-sheet").assertIsDisplayed()
    composeRule.onNodeWithText("仅本条笔记").performClick()
    composeRule.onNodeWithTag("editor-font-size-slider").performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
      assertEquals(true, setProgress(22f))
    }
    composeRule.onNodeWithTag("editor-line-spacing-slider").performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
      assertEquals(true, setProgress(1.7f))
    }
    composeRule.onNodeWithText("完成").performClick()
    composeRule.onNodeWithTag("editor-body").performTextInput("单条字体覆盖保存验证")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    val saved = runBlocking {
      AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == "单条字体覆盖保存验证" }
    }
    createdNoteIds += saved.id
    assertEquals(22f, saved.fontSizeSpOverride ?: -1f, 0f)
    assertEquals(1.7f, saved.lineSpacingMultiplierOverride ?: -1f, 0.0001f)
  }

  @Test
  fun typographySheetKeepsActionsAccessibleInLandscapeWindow() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val noteId = checkNotNull(
      runBlocking {
        NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(
          NoteDraft(
            title = "横屏字体面板滚动验证",
            titleManuallySet = true,
            blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "字体面板正文")),
            fontSizeSpOverride = 19f,
            lineSpacingMultiplierOverride = 1.4f,
          ),
        )
      },
    )
    createdNoteIds += noteId
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation

    try {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
      }
      openNote("横屏字体面板滚动验证")
      composeRule.onNodeWithTag("editor-text-settings").performClick()
      composeRule.onNodeWithText("仅本条笔记").performClick()

      composeRule.onNodeWithTag("editor-reset-typography").performScrollTo().assertIsDisplayed()
      val sheetBounds = composeRule.onNodeWithTag("text-settings-sheet").fetchSemanticsNode().boundsInWindow
      val resetBounds = composeRule.onNodeWithTag("editor-reset-typography").fetchSemanticsNode().boundsInWindow
      assertTrue(
        "横屏时恢复默认操作必须位于面板可见区域内；sheet=$sheetBounds、action=$resetBounds",
        resetBounds.top >= sheetBounds.top && resetBounds.bottom <= sheetBounds.bottom,
      )
      composeRule.onNodeWithTag("editor-reset-typography").performClick()
      composeRule.onNodeWithText("完成").performClick()
    } finally {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = originalRequestedOrientation
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == originalConfigurationOrientation
      }
    }
  }

  @Test
  fun selectingTextAndTogglingBoldPersistsFormattingRange() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("加粗范围验证")
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 2))
    composeRule.onNodeWithText("B").performClick()
    composeRule.onNodeWithTag("editor-bold").assertIsSelected()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }

    val saved = runBlocking {
      AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == "加粗范围验证" }
    }
    createdNoteIds += saved.id
    val block = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(saved.id).first()!!.blocks.single() }

    assertEquals(
      BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD))),
      ),
      block.formatting,
    )
  }

  @Test
  fun togglingBoldOffRemovesFormattingFromSelectedRange() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("取消加粗验证")
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 2))
    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.onNodeWithTag("editor-bold").assertIsSelected()
    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.onNodeWithTag("editor-bold").assertIsNotSelected()

    composeRule.waitUntil(5_000) {
      val note = runBlocking {
        AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().firstOrNull { it.title == "取消加粗验证" }
      }
      note?.let {
        runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(it.id).first() }
          ?.blocks?.singleOrNull()?.formatting == BlockFormattingState.Supported(BlockFormatting())
      } == true
    }

    val saved = runBlocking {
      AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == "取消加粗验证" }
    }
    createdNoteIds += saved.id
    val block = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(saved.id).first()!!.blocks.single() }
    assertEquals(BlockFormattingState.Supported(BlockFormatting()), block.formatting)
  }

  @Test
  fun unsupportedFormattingRequiresConfirmationAndDoesNotBlockOtherBlocks() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val unsupportedBlob = byteArrayOf(0x41, 0x4e, 0x46, 0x4d, 2)
    val otherBlockId = "rich-text-other-block"
    val noteId = runBlocking {
      NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(
        NoteDraft(
          title = "未知格式隔离验证",
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(
              type = BlockType.TEXT,
              text = "受保护格式正文",
              formatting = BlockFormattingState.Unsupported(unsupportedBlob, FormattingDecodeError.UNSUPPORTED_VERSION),
            ),
            NoteBlockDraft(id = otherBlockId, type = BlockType.TEXT, text = "正常文字块"),
          ),
        ),
      )
    }!!
    createdNoteIds += noteId

    openNote("未知格式隔离验证")
    composeRule.onNodeWithTag("editor-body").assertTextContains("受保护格式正文")
    assertFalse(
      composeRule.onNodeWithTag("editor-body").fetchSemanticsNode().config.contains(SemanticsActions.SetText),
    )
    composeRule.onNodeWithTag("editor-body-$otherBlockId").performTextInput("仍可编辑")
    composeRule.waitUntil(5_000) {
      val document = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(noteId).first() }
      document?.blocks?.firstOrNull()?.text == "受保护格式正文" &&
        document.blocks.getOrNull(1)?.text == "正常文字块仍可编辑"
    }

    val protectedBeforeReset = runBlocking {
      AnAnDatabase.getInstance(context).blockDao().getByNote(noteId).first()
    }
    assertEquals(unsupportedBlob.toList(), protectedBeforeReset.formattingBlob?.toList())

    composeRule.onNodeWithTag("reset-unsupported-format").performClick()
    composeRule.onNodeWithText("取消").performClick()
    assertFalse(
      composeRule.onNodeWithTag("editor-body").fetchSemanticsNode().config.contains(SemanticsActions.SetText),
    )
    composeRule.onNodeWithTag("editor-body").assertTextContains("受保护格式正文")

    composeRule.onNodeWithTag("reset-unsupported-format").performClick()
    composeRule.onNodeWithTag("confirm-reset-unsupported-format").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onNodeWithTag("editor-body").fetchSemanticsNode().config.contains(SemanticsActions.SetText)
    }
    composeRule.onNodeWithTag("editor-body").performTextInput("已确认编辑")
    composeRule.onNodeWithTag("editor-body").assertTextContains("受保护格式正文已确认编辑")
    composeRule.waitUntil(5_000) {
      val block = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(noteId).first() }
        ?.blocks?.firstOrNull()
      block?.text == "受保护格式正文已确认编辑" && block.formatting == BlockFormattingState.Supported(BlockFormatting())
    }
  }

  @Test
  fun headingToolChangesBlockPresentationWithoutChangingNoteTitle() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("标题呈现验证")
    composeRule.onNodeWithText("H").performClick()
    // The "已保存" indicator also covers the earlier text-only autosave; wait for the
    // heading presentation itself to reach the database instead of racing the indicator.
    composeRule.waitUntil(5_000) {
      val note = runBlocking {
        AnAnDatabase.getInstance(context).noteDao().observeAllActive().first()
          .firstOrNull { it.title == "标题呈现验证" }
      }
      note != null && runBlocking {
        NoteRepository(AnAnDatabase.getInstance(context)).observeNote(note.id).first()
      }?.blocks?.singleOrNull()?.formatting ==
        BlockFormattingState.Supported(BlockFormatting(presentation = TextBlockPresentation.HEADING))
    }

    val saved = runBlocking {
      AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == "标题呈现验证" }
    }
    createdNoteIds += saved.id
    val block = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(saved.id).first()!!.blocks.single() }

    assertEquals("标题呈现验证", saved.title)
    assertEquals(
      BlockFormattingState.Supported(BlockFormatting(presentation = TextBlockPresentation.HEADING)),
      block.formatting,
    )

    composeRule.onNodeWithTag("editor-heading").performClick()
    composeRule.waitUntil(5_000) {
      runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(saved.id).first() }
        ?.blocks?.singleOrNull()?.formatting == BlockFormattingState.Supported(BlockFormatting())
    }
  }

  @Test
  fun typingStyleOverrideAppliesToFutureTextUntilToggledOff() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("editor-bold").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor-bold").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.onNodeWithTag("editor-bold").assertIsSelected()
    composeRule.onNodeWithTag("editor-body").performTextInput("粗体")
    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.onNodeWithTag("editor-bold").assertIsNotSelected()
    composeRule.onNodeWithTag("editor-body").performTextInput("普通")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }

    val saved = runBlocking {
      AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == "粗体普通" }
    }
    createdNoteIds += saved.id
    val block = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(saved.id).first()!!.blocks.single() }

    assertEquals(
      BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD))),
      ),
      block.formatting,
    )
  }

  @Test
  fun mixedSelectionShowsPartialStateAndClickUnifiesFormatting() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val content = "混合状态文字"
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput(content)
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 2))
    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(0, content.length))
    assertEquals(
      "部分应用",
      composeRule.onNodeWithTag("editor-bold").fetchSemanticsNode().config[SemanticsProperties.StateDescription],
    )

    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.onNodeWithTag("editor-bold").assertIsSelected()
    composeRule.waitUntil(5_000) {
      val note = runBlocking {
        AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().firstOrNull { it.title == content }
      }
      note?.let {
        runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(it.id).first() }
          ?.blocks?.singleOrNull()?.formatting == BlockFormattingState.Supported(
            BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, content.length, InlineStyle.BOLD))),
          )
      } == true
    }
    val saved = runBlocking {
      AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == content }
    }
    createdNoteIds += saved.id
  }

  @Test
  fun quoteConversionKeepsBlockIdentityTextAndInlineFormatting() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    openNewNote()
    composeRule.onNodeWithTag("editor-body").performTextInput("引用内容保留")
    composeRule.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(0, 2))
    composeRule.onNodeWithTag("editor-bold").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    val before = runBlocking {
      val saved = AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().single { it.title == "引用内容保留" }
      createdNoteIds += saved.id
      NoteRepository(AnAnDatabase.getInstance(context)).observeNote(saved.id).first()!!
    }

    composeRule.onNodeWithText("更多").performClick()
    composeRule.onNodeWithTag("editor-basic-toolbar").performTouchInput { swipeLeft() }
    composeRule.onNodeWithTag("editor-quote").performClick()
    composeRule.onNodeWithTag("editor-quote").assertIsSelected()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    val after = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(before.note.id).first()!! }

    assertEquals(before.blocks.single().id, after.blocks.single().id)
    assertEquals(BlockType.QUOTE, after.blocks.single().type)
    assertEquals("引用内容保留", after.blocks.single().text)
    assertEquals(
      BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 2, InlineStyle.BOLD))),
      ),
      after.blocks.single().formatting,
    )

    composeRule.onNodeWithTag("editor-quote").performClick()
    composeRule.waitUntil(5_000) {
      val reverted = runBlocking { NoteRepository(AnAnDatabase.getInstance(context)).observeNote(before.note.id).first() }
      reverted?.blocks?.singleOrNull()?.type == BlockType.TEXT &&
        reverted.blocks.single().formatting == after.blocks.single().formatting
    }
  }

  @After
  fun removeIntegrationTestNote() = runBlocking {
    draftStore().clearIfAtMost(recoveryDraftId, Long.MAX_VALUE)
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = AnAnDatabase.getInstance(context)
    createdNoteIds.forEach { database.noteDao().deletePermanently(it) }
    database.noteDao().observeAllActive().first()
      .filter {
        it.title.startsWith("显示设置保留验证") || it.title in setOf(
          "自动保存验证",
          "皮肤预览和保存验证",
          "崩溃恢复标题",
          "进程退出前的正文内容",
          "显示设置保留验证",
          "加粗范围验证",
          "取消加粗验证",
          "标题呈现验证",
          "粗体普通",
          "引用内容保留",
          "未知格式隔离验证",
        )
      }
      .forEach { database.noteDao().deletePermanently(it.id) }
  }

  private fun draftStore() = FileEditorDraftStore(
    File(ApplicationProvider.getApplicationContext<Context>().filesDir, "drafts"),
  )

  private fun waitForToolbarAtImeTop(tag: String, decorView: android.view.View, density: Float) {
    composeRule.waitUntil(8_000) {
      val insets = ViewCompat.getRootWindowInsets(decorView) ?: return@waitUntil false
      if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return@waitUntil false
      val toolbarBottom = runCatching {
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.bottom
      }.getOrNull() ?: return@waitUntil false
      val imeTop = decorView.height - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
      abs(imeTop - toolbarBottom) <= 8f * density
    }
  }

  private fun waitForToolbarAtRest(
    tag: String,
    decorView: android.view.View,
    expectedBottomPx: Float,
    density: Float,
  ) {
    composeRule.waitUntil(8_000) {
      val insets = ViewCompat.getRootWindowInsets(decorView) ?: return@waitUntil false
      if (insets.isVisible(WindowInsetsCompat.Type.ime())) return@waitUntil false
      val toolbarBottom = runCatching {
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow.bottom
      }.getOrNull() ?: return@waitUntil false
      abs(expectedBottomPx - toolbarBottom) <= 8f * density
    }
  }

  private fun waitForEditorTodoField() {
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("editor-todo-text").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor-todo-text").assertIsDisplayed()
  }

  private fun openNewNote() {
    composeRule.onNodeWithTag("new-note-button").performClick()
    waitForEditorReady()
  }

  private fun waitForEditorReady() {
    composeRule.waitUntil(10_000) {
      composeRule.onAllNodesWithTag("editor-metadata").fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun openNote(title: String, timeoutMillis: Long = 10_000) {
    composeRule.waitUntil(timeoutMillis) {
      composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
    }
    try {
      composeRule.onNodeWithText(title).performClick()
    } catch (error: AssertionError) {
      // Under emulator load bursts the card can still be relayouting when the click
      // injects; settle briefly and retry once before giving up.
      if (!error.message.orEmpty().contains("Failed to inject touch input")) throw error
      composeRule.waitUntil(2_000) { true }
      composeRule.onNodeWithText(title).performClick()
    }
    waitForEditorReady()
  }

  private fun createImageNote(
    title: String,
    blocks: List<NoteBlockDraft>,
    skinId: String = DEFAULT_NOTE_SKIN_ID,
  ): String {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val noteId = checkNotNull(
      runBlocking {
        NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(
          NoteDraft(title = title, titleManuallySet = true, blocks = blocks, skinId = skinId),
        )
      },
    )
    createdNoteIds += noteId
    return noteId
  }
}

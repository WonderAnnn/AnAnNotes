package com.anan.anannotes.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.geometry.Rect
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.FolderRepository
import com.anan.anannotes.data.repository.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()
  private val testToken = UUID.randomUUID().toString().take(8)
  private val testNoteIds = mutableListOf<String>()
  private val testFolderIds = mutableListOf<String>()
  private val testImageFiles = mutableListOf<File>()

  @Test
  fun homeShowsFolderStripAndPlainEmptyState() {
    val emptyFolder = createFolder("空状态验证")
    selectFolder(emptyFolder.id)

    composeRule.onNodeWithTag("folder-strip").fetchSemanticsNode()
    composeRule.onNodeWithTag("home-empty-state").assertIsDisplayed()
    composeRule.onNodeWithText("点击右下角“+”开始记录").assertIsDisplayed()
    composeRule.onNodeWithTag("new-note-button").assertIsDisplayed()
  }

  @Test
  fun homeAndTodoPageTitlesStayAtTheSameVerticalPosition() {
    composeRule.waitForIdle()
    val homeTitleTop = pageTitleBounds("笔记").top
    val homeHeaderBounds = composeRule.onNodeWithTag("home-title-header").fetchSemanticsNode().boundsInRoot

    composeRule.onNodeWithTag("nav-todo").performClick()
    composeRule.waitForIdle()
    val todoTitleTop = pageTitleBounds("待办").top
    val todoHeaderBounds = composeRule.onNodeWithTag("todo-title-header").fetchSemanticsNode().boundsInRoot

    assertEquals(
      "首页标题与待办页标题应从同一垂直位置开始；homeHeader=$homeHeaderBounds todoHeader=$todoHeaderBounds",
      homeTitleTop,
      todoTitleTop,
      0.5f,
    )

    composeRule.onNodeWithTag("nav-home").performClick()
    composeRule.waitForIdle()
    assertEquals(
      "切回首页后标题应回到同一位置",
      homeTitleTop,
      pageTitleBounds("笔记").top,
      0.5f,
    )
  }

  @Test
  fun bulkActionButtonsStayClearOfLandscapeDisplayCutout() {
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation
    try {
      val noteId = createDatabaseNote("system-uncategorized", "横屏切口批量栏验证-$testToken")
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation ==
          android.content.res.Configuration.ORIENTATION_LANDSCAPE
      }
      composeRule.waitForIdle()

      var cutoutInsets = Insets.NONE
      composeRule.runOnIdle {
        val rootInsets = ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
          ?: error("窗口尚未提供根 Insets")
        cutoutInsets = rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
      }
      assumeTrue("当前设备未启用实际/模拟打孔切口，跳过横屏切口断言。Insets=$cutoutInsets", cutoutInsets.left > 0 || cutoutInsets.right > 0)

      val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
      val moveButton = composeRule.onNodeWithText("移动").fetchSemanticsNode().boundsInRoot
      val deleteButton = composeRule.onNodeWithText("删除").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "批量栏左侧按钮应避开横屏切口 cutout=$cutoutInsets root=$root button=$moveButton",
        moveButton.left >= root.left + cutoutInsets.left,
      )
      assertTrue(
        "批量栏右侧按钮应避开横屏切口 cutout=$cutoutInsets root=$root button=$deleteButton",
        deleteButton.right <= root.right - cutoutInsets.right,
      )
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
  fun sortAndLayoutAreAvailableBesideFolderTitle() {
    composeRule.onNodeWithTag("home-sort-button").performClick()
    composeRule.onNodeWithTag("home-sort-sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("sort-created").fetchSemanticsNode()
    composeRule.onNodeWithTag("sort-recent-edited").fetchSemanticsNode()
  }

  @Test
  fun sortSheetShowsBothChoicesAndLayoutHintInLandscapeWindow() {
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation
    try {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation ==
          android.content.res.Configuration.ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        val rootBounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        rootBounds.width > rootBounds.height
      }
      val sortButtonBounds = composeRule.onNodeWithTag("home-sort-button").fetchSemanticsNode().boundsInRoot
      val newNoteButtonBounds = composeRule.onNodeWithTag("new-note-button").fetchSemanticsNode().boundsInRoot
      assertTrue(
        "Sort and new-note controls must not overlap in a short landscape window: " +
          "sort=$sortButtonBounds newNote=$newNoteButtonBounds",
        sortButtonBounds.right <= newNoteButtonBounds.left ||
          sortButtonBounds.left >= newNoteButtonBounds.right ||
          sortButtonBounds.bottom <= newNoteButtonBounds.top ||
          sortButtonBounds.top >= newNoteButtonBounds.bottom,
      )
      composeRule.onNodeWithTag("home-sort-button").performClick()
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("sort-recent-edited").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithTag("sort-recent-edited").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithTag("sort-created").performScrollTo().assertIsDisplayed()
      composeRule.onNodeWithContentDescription("Drag handle").performTouchInput { swipeUp() }
      composeRule.onNodeWithText("卡片布局").performScrollTo()
      composeRule.onNodeWithText("卡片布局").assertIsDisplayed()
      composeRule.onNodeWithText("根据屏幕宽度和系统字体大小自动调整为双列或单列。")
        .performScrollTo()
        .assertIsDisplayed()
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
  fun tappingNoteCardOpensItsEditor() {
    createNote("首页卡片点击验证")

    composeRule.onAllNodesWithText("首页卡片点击验证").get(0).performClick()

    composeRule.onNodeWithTag("screen-editor").assertIsDisplayed()
    composeRule.onNodeWithTag("editor-body").assertIsDisplayed()
  }

  @Test
  fun noteCardShowsTitleTimeSummaryAndFirstImageThumbnail() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val imageFile = File(context.filesDir, "home-preview-$testToken.png")
    val bitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
    FileOutputStream(imageFile).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
    bitmap.recycle()
    testImageFiles += imageFile
    val createdAt = System.currentTimeMillis()
    val noteId = runBlocking {
      checkNotNull(
        NoteRepository(AnAnDatabase.getInstance(context), nowMillis = { createdAt }).saveDraft(
          NoteDraft(
            title = "首页图文卡片预览验证",
            titleManuallySet = true,
            blocks = listOf(
              NoteBlockDraft(type = BlockType.TEXT, text = "卡片摘要应显示在标题与时间之后"),
              NoteBlockDraft(type = BlockType.IMAGE, assetPath = imageFile.absolutePath, aspectRatio = 2f),
            ),
          ),
        ),
      )
    }
    testNoteIds += noteId

    val formattedTime = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(createdAt))
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithContentDescription("笔记图片缩略图").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithText("首页图文卡片预览验证").assertIsDisplayed()
    composeRule.onNodeWithText(formattedTime).assertIsDisplayed()
    composeRule.onNodeWithText("卡片摘要应显示在标题与时间之后").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("笔记图片缩略图").assertIsDisplayed()
  }

  @Test
  fun noteCardLoadsRelativePreviewFromPreviewCacheDirectory() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val assetId = "home-cache-$testToken"
    val previewFile = File(context.cacheDir, "notes/previews/$assetId.jpg")
    previewFile.parentFile?.mkdirs()
    val bitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
    FileOutputStream(previewFile).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output) }
    bitmap.recycle()
    testImageFiles += previewFile
    val noteId = runBlocking {
      checkNotNull(
        NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(
          NoteDraft(
            title = "相对缓存路径图片预览-$testToken",
            titleManuallySet = true,
            blocks = listOf(
              NoteBlockDraft(
                type = BlockType.IMAGE,
                assetPath = "notes/assets/$assetId.png",
                previewPath = "notes/previews/$assetId.jpg",
                aspectRatio = 2f,
              ),
            ),
          ),
        ),
      )
    }
    testNoteIds += noteId

    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithContentDescription("笔记图片缩略图").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("笔记图片缩略图").assertIsDisplayed()
  }

  @Test
  fun noteCardFallsBackToOriginalWhenPreviewCacheIsMissing() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val assetId = "home-original-$testToken"
    val originalFile = File(context.filesDir, "notes/assets/$assetId.jpg")
    originalFile.parentFile?.mkdirs()
    val bitmap = Bitmap.createBitmap(48, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
    FileOutputStream(originalFile).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output) }
    bitmap.recycle()
    testImageFiles += originalFile
    val noteId = runBlocking {
      checkNotNull(
        NoteRepository(AnAnDatabase.getInstance(context)).saveDraft(
          NoteDraft(
            title = "原图回退预览-$testToken",
            titleManuallySet = true,
            blocks = listOf(
              NoteBlockDraft(
                type = BlockType.IMAGE,
                assetPath = "notes/assets/$assetId.jpg",
                previewPath = "notes/previews/missing-$assetId.jpg",
                aspectRatio = 2f,
              ),
            ),
          ),
        ),
      )
    }
    testNoteIds += noteId

    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithContentDescription("笔记图片缩略图").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("笔记图片缩略图").assertIsDisplayed()
  }

  @Test
  fun folderCanBeCreatedRenamedAndDeletedWithoutDeletingItsNotes() {
    val originalName = "首页文件夹-$testToken"
    val renamedName = "首页已重命名-$testToken"

    composeRule.onNodeWithTag("create-folder-button").performClick()
    composeRule.onNode(hasSetTextAction()).performTextInput(originalName)
    composeRule.onNodeWithText("创建").performClick()

    val folder = awaitFolder(originalName)
    selectFolder(folder.id)
    val noteId = createDatabaseNote(folder.id, "文件夹删除后仍保留的笔记-$testToken")
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
    }

    composeRule.onNodeWithTag("folder-${folder.id}").performTouchInput { longClick() }
    composeRule.onNode(hasSetTextAction()).performTextClearance()
    composeRule.onNode(hasSetTextAction()).performTextInput(renamedName)
    composeRule.onNodeWithText("保存名称").performClick()
    composeRule.waitUntil(5_000) {
      runBlocking { FolderRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())).observeFolders().first() }
        .any { it.id == folder.id && it.name == renamedName }
    }

    composeRule.onNodeWithTag("folder-${folder.id}").performTouchInput { longClick() }
    composeRule.onNodeWithText("删除文件夹").performClick()
    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    composeRule.waitUntil(5_000) {
      runBlocking {
        database.folderDao().getById(folder.id) == null &&
          database.noteDao().getById(noteId)?.folderId == "system-uncategorized"
      }
    }
    composeRule.onNodeWithTag("folder-uncategorized").performClick()
    composeRule.onNodeWithTag("note-card-$noteId").assertIsDisplayed()
  }

  @Test
  fun createFolderUsesBottomSheetWithEqualActionButtons() {
    composeRule.onNodeWithTag("create-folder-button").performClick()

    composeRule.onNodeWithTag("create-folder-sheet").assertIsDisplayed()
    val cancelWidth = composeRule.onNodeWithTag("create-folder-cancel")
      .fetchSemanticsNode().boundsInRoot.width
    val createWidth = composeRule.onNodeWithTag("create-folder-confirm")
      .fetchSemanticsNode().boundsInRoot.width

    assertEquals("取消和创建按钮应等宽", cancelWidth, createWidth, 0.5f)
  }

  @Test
  fun createFolderKeepsInputAndActionsVisibleAboveKeyboard() {
    val folderName = "键盘适配-$testToken"
    val decorView = composeRule.activity.window.decorView
    composeRule.onNodeWithTag("create-folder-button").performClick()

    composeRule.waitUntil(8_000) {
      ViewCompat.getRootWindowInsets(decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    composeRule.onNodeWithTag("folder-name-input").performTextInput(folderName)
    composeRule.onNodeWithTag("folder-name-input").assertTextContains(folderName)

    val imeBottomPx = checkNotNull(ViewCompat.getRootWindowInsets(decorView))
      .getInsets(WindowInsetsCompat.Type.ime()).bottom
    val imeTopInWindowPx = decorView.height - imeBottomPx
    val cancelBounds = composeRule.onNodeWithTag("create-folder-cancel")
      .fetchSemanticsNode().boundsInWindow
    val createBounds = composeRule.onNodeWithTag("create-folder-confirm")
      .fetchSemanticsNode().boundsInWindow
    val density = composeRule.activity.resources.displayMetrics.density
    val actionGapDp = (imeTopInWindowPx - maxOf(cancelBounds.bottom, createBounds.bottom)) / density

    assertTrue(
      "键盘弹出时取消/创建按钮必须留在键盘上方；间距=${"%.1f".format(actionGapDp)}dp，" +
        "IME top=$imeTopInWindowPx px、取消=${cancelBounds.bottom}px、创建=${createBounds.bottom}px",
      actionGapDp >= -8f,
    )
    assertEquals("键盘弹出后两个操作按钮仍应等宽", cancelBounds.width, createBounds.width, 0.5f)

    composeRule.onNodeWithTag("create-folder-confirm").performClick()
    composeRule.waitUntil(5_000) { runBlocking { FolderRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())).observeFolders().first() }.any { it.name == folderName } }
  }

  @Test
  fun createFolderSheetCanBeDismissedByDownwardSwipe() {
    composeRule.onNodeWithTag("create-folder-button").performClick()
    composeRule.onNodeWithTag("create-folder-sheet").assertIsDisplayed()

    composeRule.onNodeWithTag("create-folder-sheet").performTouchInput { swipeDown() }

    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("create-folder-sheet").fetchSemanticsNodes().isEmpty()
    }
  }

  @Test
  fun bulkMovePinAndDeleteApplyToSelectedNote() {
    val sourceFolder = createFolder("批量操作来源")
    val targetFolder = createFolder("批量操作目标")
    selectFolder(sourceFolder.id)
    val noteId = createDatabaseNote(sourceFolder.id, "首页批量操作验证-$testToken")
    val otherNoteId = createDatabaseNote(sourceFolder.id, "首页批量全选验证-$testToken")

    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithText("已选 1").assertIsDisplayed()
    composeRule.onNodeWithText("全选").performClick()
    composeRule.onNodeWithText("已选 2").assertIsDisplayed()
    composeRule.onNodeWithText("取消").performClick()
    composeRule.onNodeWithTag("new-note-button").assertIsDisplayed()

    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithText("移动").performClick()
    composeRule.onNodeWithText("移动到文件夹").assertIsDisplayed()
    composeRule.onNodeWithTag("move-folder-${targetFolder.id}").performClick()

    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    composeRule.waitUntil(5_000) { runBlocking { database.noteDao().getById(noteId)?.folderId == targetFolder.id } }
    selectFolder(targetFolder.id)
    composeRule.waitUntil(5_000) {
      composeRule.onNodeWithTag("folder-${targetFolder.id}").fetchSemanticsNode()
        .config[SemanticsProperties.Selected]
    }
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithText("置顶").performClick()
    composeRule.waitUntil(5_000) { runBlocking { database.noteDao().getById(noteId)?.isPinned == true } }

    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithText("取消置顶").performClick()
    composeRule.waitUntil(5_000) { runBlocking { database.noteDao().getById(noteId)?.isPinned == false } }

    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithText("删除").performClick()
    composeRule.onNodeWithText("移入回收站？").assertIsDisplayed()
    composeRule.onNodeWithText("将 1 条笔记移入回收站，保留 30 天，可在回收站恢复。", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("移入回收站").performClick()
    composeRule.waitUntil(5_000) { runBlocking { database.noteDao().getById(noteId)?.deletedAtMillis != null } }
  }

  @Test
  fun longPressShowsSelectionCountAboveBulkActions() {
    createNote("首页长按多选验证")

    composeRule.onAllNodesWithText("首页长按多选验证").get(0).performTouchInput { longClick() }

    composeRule.onNodeWithTag("home-selection-header").assertIsDisplayed()
    composeRule.onNodeWithText("已选 1").assertIsDisplayed()
    composeRule.onNodeWithText("移动").assertIsDisplayed()
    composeRule.onNodeWithText("置顶").assertIsDisplayed()
    composeRule.onNodeWithText("删除").assertIsDisplayed()
  }

  @Test
  fun sortOrderIsRememberedSeparatelyForEachFolder() {
    val folderA = createFolder("A")
    val folderB = createFolder("B")
    val aOlderRecentlyEdited = createSortNote(folderA.id, "排序A 较早创建 最近编辑", 100L, 400L)
    val aNewerLessRecentlyEdited = createSortNote(folderA.id, "排序A 较晚创建 较早编辑", 400L, 100L)
    val bOlderRecentlyEdited = createSortNote(folderB.id, "排序B 较早创建 最近编辑", 100L, 400L)
    val bNewerLessRecentlyEdited = createSortNote(folderB.id, "排序B 较晚创建 较早编辑", 400L, 100L)

    selectFolder(folderA.id)
    assertNoteBefore(aOlderRecentlyEdited, aNewerLessRecentlyEdited)
    composeRule.onNodeWithTag("home-sort-button").performClick()
    composeRule.onNodeWithTag("sort-created").performClick()
    awaitNoteBefore(aNewerLessRecentlyEdited, aOlderRecentlyEdited)

    selectFolder(folderB.id)
    awaitNoteBefore(bOlderRecentlyEdited, bNewerLessRecentlyEdited)

    selectFolder(folderA.id)
    awaitNoteBefore(aNewerLessRecentlyEdited, aOlderRecentlyEdited)
  }

  private fun createNote(body: String) {
    composeRule.onNodeWithTag("new-note-button").performClick()
    // The editor body is nested inside a merged content container on API 35/36. Query the
    // unmerged text field node for input; the merged-tree tag remains suitable for visibility checks.
    composeRule.onNodeWithTag("editor-body", useUnmergedTree = true).performTextInput(body)
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText("已保存").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("editor-back").performClick()
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onAllNodesWithText(body).get(0).assertIsDisplayed()
  }

  private fun pageTitleBounds(title: String): Rect {
    return composeRule.onAllNodesWithText(title, useUnmergedTree = true)
      .fetchSemanticsNodes()
      .minByOrNull { it.boundsInRoot.top }
      ?.boundsInRoot
      ?: error("找不到页面标题：$title")
  }

  private fun createFolder(suffix: String) = runBlocking {
    val folder = FolderRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
      .createFolder("排序测试-$testToken-$suffix")
    testFolderIds += folder.id
    folder
  }

  private fun createSortNote(folderId: String, body: String, createdAt: Long, updatedAt: Long): String = runBlocking {
    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    val noteId = checkNotNull(
      NoteRepository(database).saveDraft(
        NoteDraft(folderId = folderId, blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = body))),
      ),
    )
    val note = checkNotNull(database.noteDao().getById(noteId))
    database.noteDao().upsert(note.copy(createdAtMillis = createdAt, updatedAtMillis = updatedAt))
    testNoteIds += noteId
    noteId
  }

  private fun createDatabaseNote(folderId: String, title: String): String = runBlocking {
    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    checkNotNull(
      NoteRepository(database).saveDraft(
        NoteDraft(
          folderId = folderId,
          title = title,
          titleManuallySet = true,
          blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "首页交互测试正文")),
        ),
      ),
    ).also { testNoteIds += it }
  }

  private fun awaitFolder(name: String) = runBlocking {
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
    }
    val folder = FolderRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
      .observeFolders().first().first { it.name == name }
    testFolderIds += folder.id
    folder
  }

  private fun selectFolder(folderId: String) {
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("folder-$folderId").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("folder-strip").performScrollToNode(hasTestTag("folder-$folderId"))
    composeRule.onNodeWithTag("folder-$folderId").performClick()
  }

  private fun assertNoteBefore(firstId: String, secondId: String) {
    val gridTag = listOf("note-grid-single", "note-grid-two").first { tag ->
      composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag(gridTag).performScrollToIndex(0)
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$firstId").fetchSemanticsNodes().isNotEmpty()
    }
    val firstBounds = composeRule.onAllNodesWithTag("note-card-$firstId").fetchSemanticsNodes().map { it.boundsInRoot }
    val secondBounds = composeRule.onAllNodesWithTag("note-card-$secondId").fetchSemanticsNodes().map { it.boundsInRoot }
    if (secondBounds.isNotEmpty()) {
      assertTrue("Expected $firstId before $secondId; first=$firstBounds second=$secondBounds", notePrecedes(firstId, secondId))
    } else {
      val gridBounds = composeRule.onNodeWithTag(gridTag).fetchSemanticsNode().boundsInRoot
      val firstAtGridStart = firstBounds.firstOrNull()?.let { kotlin.math.abs(it.top - gridBounds.top) < 1f } == true
      assertTrue(
        "At large font scale, the first sorted note must occupy the first visible grid position; " +
          "fontScale=${composeRule.activity.resources.configuration.fontScale} first=$firstBounds grid=$gridBounds",
        firstAtGridStart,
      )
    }
  }

  private fun awaitNoteBefore(firstId: String, secondId: String) = assertNoteBefore(firstId, secondId)

  private fun notePrecedes(firstId: String, secondId: String): Boolean {
    val first = composeRule.onAllNodesWithTag("note-card-$firstId").fetchSemanticsNodes()
    val second = composeRule.onAllNodesWithTag("note-card-$secondId").fetchSemanticsNodes()
    if (first.isEmpty() || second.isEmpty()) return false
    val firstRect: Rect = first.first().boundsInRoot
    val secondRect: Rect = second.first().boundsInRoot
    return firstRect.top < secondRect.top || (firstRect.top == secondRect.top && firstRect.left < secondRect.left)
  }

  @After
  fun removeIntegrationTestNotes() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val database = AnAnDatabase.getInstance(context)
    testNoteIds.forEach { database.noteDao().deletePermanently(it) }
    testFolderIds.forEach { FolderRepository(database).deleteFolder(it) }
    testImageFiles.forEach(File::delete)
    database.noteDao().observeAllActive().first()
      .filter { it.title == "首页卡片点击验证" || it.title == "首页长按多选验证" }
      .forEach { database.noteDao().deletePermanently(it.id) }
  }
}

package com.anan.anannotes.feature.privatemodule

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteBlockDraft
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateExportDocumentsUiRoundTripTest {
  @get:Rule
  val composeRule = createComposeRule()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
  private val password = "documents-ui-private-password"

  @Test
  fun choosingSafFolderLocksPrivateVaultThenResumesOnlyAfterUnlockAndExportReauthentication() {
    val token = UUID.randomUUID().toString().replace("-", "")
    val privateRoot = File(context.cacheDir, "private-documents-ui-$token")
    val store = PrivateStore(privateRoot)
    val repository = PrivateRepository(store)
    val noteTitle = "SAF lifecycle $token"
    val noteBody = "DocumentsUI round trip body $token"
    var viewModel: PrivateModuleViewModel? = null
    val pickedTree = AtomicReference<Uri?>(null)
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    try {
      val access = kotlinx.coroutines.runBlocking {
        repository.setup(password.toCharArray(), null)
      }
      val saved = kotlinx.coroutines.runBlocking {
        repository.save(
          access,
          PrivateEditSnapshot(
            draftId = "documents-ui-$token",
            noteId = null,
            folderId = "private-uncategorized",
            title = noteTitle,
            titleManuallySet = true,
            blocks = listOf(NoteBlockDraft(id = "body-$token", type = BlockType.TEXT, text = noteBody)),
            skinId = "minimal-white",
            baseContentRevision = null,
            localRevision = 1L,
            savedAtMillis = System.currentTimeMillis(),
          ),
        ) as PrivateSaveResult.Saved
      }
      repository.revoke(access)
      val isolatedViewModel = PrivateModuleViewModel(
        context,
        privateRoot,
        object : PrivateAuthThrottleStore {
          private var state = PrivateAuthThrottleState()
          override fun read() = state
          override fun write(state: PrivateAuthThrottleState) { this.state = state }
        },
      )
      viewModel = isolatedViewModel

      composeRule.setContent {
        PrivateUnlockScreen(
          onBack = {},
          viewModelOverride = isolatedViewModel,
          onPrivateExportTreeSelected = pickedTree::set,
        )
      }
      composeRule.onNodeWithTag("private-access-form").assertIsDisplayed()
      composeRule.onNodeWithTag("private-password-field").performTextInput(password)
      composeRule.onNodeWithTag("private-auth-submit").assertIsDisplayed()
      composeRule.onNodeWithTag("private-auth-submit").performClick()
      waitForTag("private-note-${saved.noteId.value}")
      composeRule.onNodeWithTag("private-note-${saved.noteId.value}").assertIsDisplayed().performClick()
      waitForTag("private-editor-screen")
      composeRule.onNodeWithTag("private-editor-more").performClick()
      waitForTag("private-editor-export")
      composeRule.onNodeWithTag("private-editor-toolbar").performTouchInput { swipeLeft() }
      composeRule.onNodeWithTag("private-editor-export").assertIsDisplayed()
      composeRule.onNodeWithTag("private-editor-export").performClick()
      waitForTag("private-export-format-list")
      composeRule.onNodeWithTag("private-export-format-list")
        .performScrollToNode(hasTestTag("private-export-format-markdown"))
      composeRule.onNodeWithTag("private-export-format-markdown").performClick()

      val rootsToggle = waitForPickerNodeByLabel(
        device,
        10_000,
        "Show roots",
        "Open navigation drawer",
        "显示根目录",
        "打开导航抽屉",
      )
      if (rootsToggle != null) {
        rootsToggle.click()
      } else {
        val rootsAlreadyOpen = waitForPickerNodeByLabel(
          device,
          1_000,
          "Hide roots",
          "Close navigation drawer",
          "隐藏根目录",
          "关闭导航抽屉",
        )
        assertNotNull(
          "系统目录选择器应提供位置抽屉，避免沿用上次打开的子目录；当前包=${device.currentPackageName}，可见文字=${visiblePickerText(device)}，节点=${visiblePickerNodeSummary(device)}",
          rootsAlreadyOpen,
        )
      }
      val internalStorage = waitForAnyText(
        device,
        5_000,
        "sdk_gphone64_x86_64",
        "Internal storage",
        "内部存储",
      )
      assertNotNull(
        "DocumentsUI 位置抽屉应列出设备内部存储；可见文字=${visiblePickerText(device)}",
        internalStorage,
      )
      assertTrue(
        "应能从位置抽屉打开设备内部存储；节点=${visiblePickerNodeSummary(device)}",
        clickSmallestClickableContainingText(
          device,
          "sdk_gphone64_x86_64",
          "Internal storage",
          "内部存储",
        ),
      )
      val storageHeader = waitForPickerHeaderContaining(device, "sdk_gphone", 10_000)
      assertNotNull("应进入设备内部存储；可见文字=${visiblePickerText(device)}", storageHeader)

      val downloadsDirectory = waitForAnyText(device, 10_000, "Download", "Downloads", "下载")
      assertNotNull(
        "设备内部存储中应显示 Download 文件夹；可见文字=${visiblePickerText(device)}",
        downloadsDirectory,
      )
      assertTrue(
        "应能打开 Download 目录卡片；节点=${visiblePickerNodeSummary(device)}",
        clickSmallestClickableContainingText(device, "Download", "Downloads", "下载"),
      )
      val downloadsHeader = waitForPickerHeaderContaining(device, "Download", 10_000)
      assertNotNull(
        "点击后应确认已进入 Downloads 子目录；可见文字=${visiblePickerText(device)}",
        downloadsHeader,
      )
      val downloadsUseFolder = waitForFolderAccessAction(device, 10_000)
      assertNotNull(
        "进入 Downloads 后系统应允许选择该子目录；可见文字=${visiblePickerText(device)}",
        downloadsUseFolder,
      )

      var createFolder = waitForAnyText(device, 1_500, "CREATE NEW FOLDER", "新建文件夹", "New folder")
      if (createFolder == null) {
        val overflow = device.findObject(By.desc("More options"))
          ?: device.findObject(By.desc("更多选项"))
        assertNotNull("系统目录选择器应提供更多操作菜单", overflow)
        overflow!!.click()
        createFolder = waitForAnyText(device, 5_000, "CREATE NEW FOLDER", "新建文件夹", "New folder")
      }
      val pickerText = device.findObjects(By.pkg("com.google.android.documentsui"))
        .mapNotNull { it.text ?: it.contentDescription }
        .filter(String::isNotBlank)
      assertNotNull(
        "Download 中应提供新建专用测试目录的入口；当前包=${device.currentPackageName}，可见文字=$pickerText",
        createFolder,
      )
      createFolder!!.click()
      val folderName = "AnAnNotes-SAF-$token"
      val folderNameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
      assertNotNull(
        "系统新建文件夹对话框应显示名称输入框；当前包=${device.currentPackageName}，可见文字=${visiblePickerText(device)}，节点=${visiblePickerNodeSummary(device)}",
        folderNameField,
      )
      folderNameField!!.text = folderName
      val confirmCreateFolder = waitForAnyText(device, 5_000, "创建", "确定", "Create", "CREATE", "OK")
      val createDialogText = device.findObjects(By.pkg("com.google.android.documentsui"))
        .mapNotNull { it.text ?: it.contentDescription }
        .filter(String::isNotBlank)
      assertNotNull("系统新建文件夹对话框应提供确认按钮；可见文字=$createDialogText", confirmCreateFolder)
      confirmCreateFolder!!.click()
      val createdFolder = device.wait(Until.findObject(By.text(folderName)), 10_000)
      assertNotNull("系统 DocumentsUI 应显示刚创建的专用测试目录", createdFolder)
      val folderRow = device.findObjects(By.res("com.android.documentsui", "item_root"))
        .firstOrNull { row -> runCatching { row.findObject(By.text(folderName)) != null }.getOrDefault(false) }
      val createdFolderBounds = (folderRow ?: createdFolder!!).visibleBounds
      device.waitForIdle(5_000)
      assertTrue(
        "系统 DocumentsUI 应能触摸打开新建的子目录；bounds=$createdFolderBounds",
        device.click(createdFolderBounds.centerX(), createdFolderBounds.centerY()),
      )
      if (waitForFolderAccessAction(device, 1_500) == null) {
        device.click(createdFolderBounds.centerX(), createdFolderBounds.centerY())
      }
      val useFolder = waitForFolderAccessAction(device, 10_000)
      assertNotNull(
        "系统目录选择器应允许选择专用子目录；当前包=${device.currentPackageName}，可见文字=${visiblePickerText(device)}",
        useFolder,
      )
      useFolder!!.click()
      waitForAnyText(device, 3_000, "允许", "ALLOW")?.click()
      val returnedToApp = device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000)
      val foregroundPackage = device.currentPackageName
      val foregroundText = device.findObjects(By.pkg(foregroundPackage))
        .mapNotNull { it.text ?: it.contentDescription }
        .filter(String::isNotBlank)
      assertTrue(
        "系统目录选择器应返回安安简记；当前包=$foregroundPackage，可见文字=$foregroundText",
        returnedToApp,
      )

      composeRule.onNodeWithTag("private-export-resume-notice").assertIsDisplayed()
      composeRule.onNodeWithTag("private-access-form").assertIsDisplayed()
      composeRule.onNodeWithTag("private-password-field").performTextInput(password)
      composeRule.onNodeWithTag("private-auth-submit").performClick()
      waitForTag("private-export-password-dialog")
      composeRule.onNodeWithTag("private-export-password-dialog").assertIsDisplayed()
      composeRule.onNodeWithTag("private-export-password-field").performTextInput(password)
      composeRule.onNodeWithTag("private-export-password-submit").performClick()
      waitForTag("private-export-plaintext-warning")
      composeRule.onNodeWithTag("private-export-plaintext-warning").assertIsDisplayed()
      composeRule.onNodeWithTag("private-export-plaintext-confirm").performClick()

      var exported: SafDocumentRecord? = null
      var entries: Map<String, String> = emptyMap()
      composeRule.waitUntil(20_000) {
        val candidate = findSafDocuments(pickedTree.get()).firstOrNull { it.name.contains(token) }
        if (candidate != null) {
          exported = candidate
          entries = tryReadSafZip(candidate.uri)
        }
        entries.entries.any { (name, content) -> name.endsWith(".md") && content.contains(noteBody) }
      }
      val exportedDocument = checkNotNull(exported) {
        "所选 SAF 文件夹中未出现导出文件；tree=${pickedTree.get()}"
      }
      assertTrue("导出文件名应为 ZIP：${exportedDocument.name}", exportedDocument.name.endsWith(".zip"))
      val markdownEntry = entries.entries.firstOrNull { it.key.endsWith(".md") }
      assertNotNull("ZIP 应包含 Markdown 正文文件；实际条目=${entries.keys}", markdownEntry)
      val markdown = checkNotNull(markdownEntry).value
      assertTrue(markdown.contains(noteBody))
    } finally {
      device.pressBack()
      viewModel?.lock()
      pickedTree.get()?.let { tree ->
        runCatching {
          findSafDocuments(tree).forEach { DocumentsContract.deleteDocument(context.contentResolver, it.uri) }
        }
        val treeDocument = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, treeDocument) }
      }
      privateRoot.deleteRecursively()
    }
  }

  private fun waitForTag(tag: String) {
    composeRule.waitUntil(15_000) {
      composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private fun waitForAnyText(device: UiDevice, timeoutMs: Long, vararg labels: String): UiObject2? {
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() <= deadline) {
      labels.forEach { label -> device.findObject(By.text(label))?.let { return it } }
      android.os.SystemClock.sleep(150)
    }
    return null
  }

  private fun waitForFolderAccessAction(device: UiDevice, timeoutMs: Long): UiObject2? {
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() <= deadline) {
      visibleTextNodes(device).forEach { node ->
        val label = runCatching { node.text }.getOrNull().orEmpty()
        if (label.equals("使用此文件夹", ignoreCase = true) ||
          label.equals("USE THIS FOLDER", ignoreCase = true) ||
          label.contains("ALLOW ACCESS TO", ignoreCase = true) ||
          label.equals("ALLOW", ignoreCase = true) ||
          label.contains("允许访问") ||
          label == "允许"
        ) return node
      }
      android.os.SystemClock.sleep(150)
    }
    return null
  }

  private fun visiblePickerText(device: UiDevice): List<String> = visibleTextNodes(device)
    .mapNotNull { runCatching { it.text }.getOrNull() }
    .filter(String::isNotBlank)

  private fun findPickerNodeByLabel(device: UiDevice, vararg labels: String): UiObject2? =
    device.findObjects(By.pkg("com.google.android.documentsui")).firstOrNull { node ->
      val nodeLabels = listOfNotNull(
        runCatching { node.text }.getOrNull(),
        runCatching { node.contentDescription }.getOrNull(),
        runCatching { node.resourceName }.getOrNull(),
      )
      nodeLabels.any { nodeLabel -> labels.any { label -> nodeLabel.equals(label, ignoreCase = true) } }
    }

  private fun waitForPickerNodeByLabel(device: UiDevice, timeoutMs: Long, vararg labels: String): UiObject2? {
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() <= deadline) {
      findPickerNodeByLabel(device, *labels)?.let { return it }
      android.os.SystemClock.sleep(150)
    }
    return null
  }

  private fun waitForPickerHeaderContaining(device: UiDevice, text: String, timeoutMs: Long): UiObject2? {
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() <= deadline) {
      val header = visibleTextNodes(device)
        .firstOrNull { node -> runCatching { node.text.contains(text, ignoreCase = true) }.getOrDefault(false) }
      if (header != null) return header
      android.os.SystemClock.sleep(150)
    }
    return null
  }

  private fun clickSmallestClickableContainingText(device: UiDevice, vararg labels: String): Boolean {
    val target = device.findObjects(By.pkg("com.google.android.documentsui"))
      .asSequence()
      .filter { node -> runCatching { node.isClickable }.getOrDefault(false) }
      .filter { node ->
        labels.any { label -> runCatching { node.findObject(By.text(label)) != null }.getOrDefault(false) }
      }
      .minByOrNull { node ->
        runCatching {
          val bounds = node.visibleBounds
          bounds.width().toLong() * bounds.height().toLong()
        }.getOrDefault(Long.MAX_VALUE)
      }
    return runCatching {
      if (target == null) false else {
        target.click()
        true
      }
    }.getOrDefault(false)
  }

  private fun visiblePickerNodeSummary(device: UiDevice): List<String> =
    device.findObjects(By.pkg("com.google.android.documentsui")).take(80).map { node ->
      val details = listOfNotNull(
        runCatching { node.className }.getOrNull(),
        runCatching { node.resourceName }.getOrNull(),
        runCatching { node.text }.getOrNull()?.takeIf(String::isNotBlank),
        runCatching { node.contentDescription }.getOrNull()?.takeIf(String::isNotBlank),
      ).joinToString("|")
      "$details clickable=${runCatching { node.isClickable }.getOrDefault(false)} bounds=${runCatching { node.visibleBounds }.getOrNull()}"
    }

  private fun visibleTextNodes(device: UiDevice): List<UiObject2> =
    device.findObjects(By.text(Pattern.compile(".+")))

  private fun tryReadSafZip(uri: Uri): Map<String, String> = runCatching {
    val raw = checkNotNull(context.contentResolver.openInputStream(uri))
    ZipInputStream(raw).use { zip ->
      buildMap {
        while (true) {
          val entry = zip.nextEntry ?: break
          put(entry.name, zip.readBytes().toString(Charsets.UTF_8))
          zip.closeEntry()
        }
      }
    }
  }.getOrDefault(emptyMap())

  private data class SafDocumentRecord(val uri: Uri, val name: String)

  private fun findSafDocuments(tree: Uri?): List<SafDocumentRecord> {
    tree ?: return emptyList()
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
    return context.contentResolver.query(
      children,
      arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
      null,
      null,
      null,
    )?.use { cursor ->
      val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      buildList {
        while (cursor.moveToNext()) {
          add(
            SafDocumentRecord(
              DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)),
              cursor.getString(nameColumn),
            ),
          )
        }
      }
    }.orEmpty()
  }
}

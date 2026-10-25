package com.anan.anannotes.feature.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.anan.anannotes.MainActivity
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the real DocumentsUI picker across separate seed and verify emulator phases. */
@RunWith(AndroidJUnit4::class)
class PrivateSafBackupRestoreDocumentsUiTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  private val context: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

  @Test
  fun privateBackupCanBeCreatedAndRestoredThroughDocumentsUi() {
    val arguments = InstrumentationRegistry.getArguments()
    val phase = arguments.getString(PHASE_ARGUMENT)
    assumeTrue("Run with $PHASE_ARGUMENT=seed or verify", phase == "seed" || phase == "verify")
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    openBackupRestoreScreen()
    if (phase == "seed") {
      createBackupThroughDocumentsUi(device, arguments)
    } else {
      restoreBackupThroughDocumentsUi(device, arguments)
    }
  }

  private fun openBackupRestoreScreen() {
    composeRule.onNodeWithTag("home-settings-button").performClick()
    composeRule.onNodeWithTag("settings-backup-restore").performScrollTo().performClick()
    composeRule.onNodeWithTag("backup-restore-screen").assertIsDisplayed()
    waitForTag("backup-password")
  }

  private fun createBackupThroughDocumentsUi(
    device: UiDevice,
    arguments: android.os.Bundle,
  ) {
    val privatePassword = requiredArgument(arguments, PRIVATE_PASSWORD_ARGUMENT)
    composeRule.onNodeWithTag("backup-password").performScrollTo().performTextInput(requiredArgument(arguments, BACKUP_PASSWORD_ARGUMENT))
    composeRule.onNodeWithTag("backup-password-confirmation").performScrollTo().performTextInput(requiredArgument(arguments, BACKUP_PASSWORD_ARGUMENT))
    composeRule.onNodeWithTag("backup-private-password").performScrollTo().performTextInput(privatePassword)
    device.pressBack()
    composeRule.onNodeWithTag("backup-choose-destination").performScrollTo().performClick()
    chooseDownloadsRoot(device)
    waitForTag("backup-create-action")
    composeRule.onNodeWithTag("backup-create-action").performScrollTo().performClick()
    waitForTag("backup-created-result", 60_000)

    val tree = context.contentResolver.persistedUriPermissions
      .firstOrNull { it.isWritePermission }
      ?.uri
    assertNotNull("SAF 目录授权应保留到当前页面生命周期结束", tree)
    val backup = queryChildren(requireNotNull(tree))
      .filter { it.name.startsWith(BACKUP_FILE_PREFIX) && it.name.endsWith(".anbk") }
      .maxByOrNull(BackupFile::name)
    assertNotNull("所选 SAF 目录中应创建 ANBK 文件", backup)
    val bytes = context.contentResolver.openInputStream(requireNotNull(backup).uri)!!.use { it.readBytes() }
    assertTrue("加密备份文件应包含有效容器头", bytes.size > 4)
    assertEquals("ANBK", String(bytes.copyOfRange(0, 4), StandardCharsets.US_ASCII))
    assertTrue("私密标题不应以明文出现在备份文件", !String(bytes, StandardCharsets.ISO_8859_1).contains(PRIVATE_NOTE_TITLE))
  }

  private fun restoreBackupThroughDocumentsUi(
    device: UiDevice,
    arguments: android.os.Bundle,
  ) {
    composeRule.onNodeWithTag("backup-choose-source").performScrollTo().performClick()
    chooseExistingBackup(device)
    composeRule.onNodeWithTag("backup-restore-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("restore-backup-password").performScrollTo()
      .performTextInput(requiredArgument(arguments, BACKUP_PASSWORD_ARGUMENT))
    device.pressBack()
    composeRule.onNodeWithTag("backup-inspect-action").performScrollTo().performClick()
    waitForTag("backup-verified-preview", 60_000)
    composeRule.onNodeWithTag("backup-verified-preview").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("restore-new-private-password").performScrollTo()
      .performTextInput(requiredArgument(arguments, NEW_PRIVATE_PASSWORD_ARGUMENT))
    composeRule.onNodeWithTag("restore-confirm-private-password").performScrollTo()
      .performTextInput(requiredArgument(arguments, NEW_PRIVATE_PASSWORD_ARGUMENT))
    device.pressBack()
    composeRule.onNodeWithTag("backup-restore-action").performScrollTo().performClick()
    waitForTag("backup-restore-success", 60_000)
    composeRule.onNodeWithTag("backup-restore-success").performScrollTo().assertIsDisplayed()
  }

  /** DocumentsUI differs by release: Android 10 lists roots with a per-root ALLOW ACCESS
   * action; Android 15+ may reopen at the remembered directory (USE THIS FOLDER — or the
   * storage root, which it refuses to grant) or show the roots drawer where picking a
   * root reveals SAVE HERE. Drive any of those shapes. */
  private fun chooseDownloadsRoot(device: UiDevice) {
    var downloadsClicks = 0
    val deadline = android.os.SystemClock.uptimeMillis() + 20_000
    while (android.os.SystemClock.uptimeMillis() < deadline) {
      if (device.currentPackageName == context.packageName) return
      // The raw-filesystem root and its top-level Download folder are refused on
      // Android 11+ ("Can't use this folder"); recover by opening the roots drawer,
      // where the dedicated downloads root is grantable.
      val refused = visiblePickerText(device).contains("Can't use this folder")
      if (refused) {
        device.findObject(By.desc("Show roots"))?.let { toggle ->
          toggle.click()
          downloadsClicks = 0
          android.os.SystemClock.sleep(600)
          continue
        }
      }
      if (downloadsClicks < 2) {
        val downloads = firstClickableLabel(device, "Download", "Downloads", "downloads", "下载")
        if (downloads != null) {
          downloads.click()
          downloadsClicks++
          android.os.SystemClock.sleep(600)
          continue
        }
      }
      val access = waitForFolderAccessAction(device, 1_500)
      if (access != null) {
        access.click()
        android.os.SystemClock.sleep(600)
        continue
      }
      device.findObject(By.desc("Show roots"))?.click() ?: android.os.SystemClock.sleep(300)
      android.os.SystemClock.sleep(300)
    }
    assertTrue("目录选择后应返回安安简记", device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
  }

  private fun firstClickableLabel(device: UiDevice, vararg labels: String): UiObject2? {
    labels.forEach { label ->
      var node = device.findObject(By.text(label))
      while (node != null && !node.isClickable) node = node.parent
      node?.let { return it }
    }
    return null
  }

  private fun visiblePickerText(device: UiDevice): String =
    device.findObjects(By.pkg("com.google.android.documentsui"))
      .mapNotNull { it.text }
      .filter(String::isNotBlank)
      .joinToString(",")

  private fun chooseExistingBackup(device: UiDevice) {
    val downloads = waitForAnyText(device, 1_500, "Download", "Downloads", "下载")
    if (downloads == null) {
      val roots = device.findObject(By.desc("Show roots"))
        ?: device.findObject(By.desc("Open navigation drawer"))
        ?: device.findObject(By.desc("显示根目录"))
        ?: device.findObject(By.desc("打开导航抽屉"))
      assertNotNull("文件选择器应能打开位置列表", roots)
      roots!!.click()
    }
    val downloadRoot = waitForAnyText(device, 10_000, "Download", "Downloads", "下载")
    assertNotNull("文件选择器应显示 Downloads 根目录", downloadRoot)
    if (downloadRoot!!.isClickable) downloadRoot.click()

    val backup = device.wait(
      Until.findObject(By.text(Pattern.compile("AnAnNotes-backup-.*\\.anbk"))),
      20_000,
    )
    assertNotNull("Downloads 中应显示加密备份文件", backup)
    backup!!.click()
    assertTrue("选中文件后应返回安安简记", device.wait(Until.hasObject(By.pkg(context.packageName)), 10_000))
  }

  private fun queryChildren(tree: Uri): List<BackupFile> {
    val documentId = DocumentsContract.getTreeDocumentId(tree)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
    return context.contentResolver.query(
      childrenUri,
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
            BackupFile(
              DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn)),
              cursor.getString(nameColumn),
            ),
          )
        }
      }
    }.orEmpty()
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
      device.findObjects(By.text(Pattern.compile(".+"))).forEach { node ->
        val label = runCatching { node.text }.getOrNull().orEmpty()
        if (label.equals("使用此文件夹", ignoreCase = true) ||
          label.equals("USE THIS FOLDER", ignoreCase = true) ||
          label.equals("SAVE HERE", ignoreCase = true) ||
          label.contains("保存到此") ||
          label.contains("ALLOW ACCESS TO", ignoreCase = true) ||
          label.equals("ALLOW", ignoreCase = true) ||
          label.contains("允许访问") || label == "允许"
        ) return node
      }
      android.os.SystemClock.sleep(150)
    }
    return null
  }

  private fun requiredArgument(arguments: android.os.Bundle, name: String): String =
    requireNotNull(arguments.getString(name)) { "$name is required" }

  private fun waitForTag(tag: String, timeoutMs: Long = 15_000) {
    composeRule.waitUntil(timeoutMs) {
      composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }
  }

  private data class BackupFile(val uri: Uri, val name: String)

  private companion object {
    const val PHASE_ARGUMENT = "c5SafUiPhase"
    const val BACKUP_PASSWORD_ARGUMENT = "c5BackupPassword"
    const val PRIVATE_PASSWORD_ARGUMENT = "c5FixturePassword"
    const val NEW_PRIVATE_PASSWORD_ARGUMENT = "c5NewPrivatePassword"
    const val BACKUP_FILE_PREFIX = "AnAnNotes-backup-"
    const val PRIVATE_NOTE_TITLE = "C5_Private_SAF"
  }
}

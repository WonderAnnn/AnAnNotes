package com.anan.anannotes.ui

import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ExportFlowTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()
  private val exportToken = UUID.randomUUID().toString()
  private val noteId = runBlocking {
    checkNotNull(
      NoteRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
        .saveDraft(
          NoteDraft(
            title = "社交图-$exportToken",
            titleManuallySet = true,
            blocks = listOf(NoteBlockDraft(type = BlockType.TEXT, text = "导出入口正文-$exportToken")),
          ),
        ),
    )
  }
  private var exportedPngUri: Uri? = null

  @Test
  fun selectedNotesExposeTextAndMarkdownZipExportChoices() {
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithTag("home-export-button").assertIsDisplayed().performClick()

    composeRule.onNodeWithTag("home-export-format-sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("export-format-pdf").assertIsDisplayed()
    listOf("export-format-png-a4", "export-format-png-long", "export-format-png-social-square",
      "export-format-png-social-portrait", "export-format-txt", "export-format-markdown-zip").forEach { tag ->
      // Lazy items outside the viewport do not yet exist in the semantics tree.
      composeRule.onNodeWithTag("home-export-format-list").performScrollToNode(hasTestTag(tag))
      composeRule.onNodeWithTag(tag).assertIsDisplayed()
    }
  }

  @Test
  fun exportFormatListKeepsLastOptionReachableInLandscapeWindow() {
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation
    try {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
      }
      if (composeRule.activity.resources.configuration.screenHeightDp < 440) {
        composeRule.onNodeWithTag("home-private-button").assertIsDisplayed()
      }
      composeRule.onNodeWithTag("note-card-$noteId").assertIsDisplayed()
      composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag("home-selection-header").fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithTag("home-export-button").performClick()

      composeRule.onNodeWithTag("home-export-format-sheet").assertIsDisplayed()
      composeRule.onNodeWithTag("home-export-format-list")
        .performScrollToNode(hasTestTag("export-format-markdown-zip"))
      composeRule.onNodeWithTag("export-format-markdown-zip").assertIsDisplayed()
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
  fun standardHeightLandscapeKeepsPrivateEntryAndNoteCardVisible() {
    val originalRequestedOrientation = composeRule.activity.requestedOrientation
    val originalConfigurationOrientation = composeRule.activity.resources.configuration.orientation
    try {
      composeRule.runOnIdle {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
      }
      composeRule.waitUntil(5_000) {
        composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
      }
      val privateEntryTag =
        if (composeRule.activity.resources.configuration.screenHeightDp < 440) "home-private-button"
        else "private-entry-button"
      composeRule.waitUntil(5_000) {
        composeRule.onAllNodesWithTag(privateEntryTag).fetchSemanticsNodes().isNotEmpty()
      }
      composeRule.onNodeWithTag(privateEntryTag).assertIsDisplayed()
      composeRule.onNodeWithTag("note-card-$noteId").assertIsDisplayed()
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
  fun squareExportWritesAPublished1080PixelImageToTheGalleryFolder() {
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithTag("home-export-button").performClick()
    composeRule.onNodeWithTag("export-format-png-social-square").performClick()

    composeRule.waitUntil(15_000) { findPublishedImage(exportToken) != null }
    val uri = checkNotNull(findPublishedImage(exportToken))
    exportedPngUri = uri
    val bitmap = ApplicationProvider.getApplicationContext<android.content.Context>()
      .contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(checkNotNull(input)) }
    try {
      org.junit.Assert.assertEquals(1080, bitmap.width)
      org.junit.Assert.assertEquals(1080, bitmap.height)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun portraitExportWritesAPublished1080By1350ImageToTheGalleryFolder() {
    composeRule.waitUntil(5_000) {
      composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty()
    }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithTag("home-export-button").performClick()
    composeRule.onNodeWithTag("export-format-png-social-portrait").performScrollTo().performClick()

    composeRule.waitUntil(15_000) { findPublishedImage(exportToken) != null }
    val uri = checkNotNull(findPublishedImage(exportToken))
    exportedPngUri = uri
    val bitmap = ApplicationProvider.getApplicationContext<android.content.Context>()
      .contentResolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(checkNotNull(input)) }
    try {
      org.junit.Assert.assertEquals(1080, bitmap.width)
      org.junit.Assert.assertEquals(1350, bitmap.height)
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun settingsExposeTheRememberedExportFolderControl() {
    composeRule.onNodeWithTag("home-settings-button").performClick()

    composeRule.onNodeWithTag("settings-export-directory").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithText("作者：WonderAnn").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun a4ExportPublishesExactHighResolutionImageAndPersistentHistory() {
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithTag("home-export-button").performClick()
    composeRule.onNodeWithTag("export-format-png-a4").performScrollTo().performClick()
    composeRule.waitUntil(30_000) { findPublishedImage(exportToken) != null }
    val uri = checkNotNull(findPublishedImage(exportToken))
    exportedPngUri = uri
    val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    org.junit.Assert.assertEquals(4961, bounds.outWidth)
    org.junit.Assert.assertEquals(7016, bounds.outHeight)
    composeRule.waitUntil(5_000) {
      runBlocking {
        com.anan.anannotes.data.repository.ExportHistoryRepository(AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext()))
          .latest().any { it.noteId == noteId && it.destinationUri == uri.toString() && it.succeeded }
      }
    }
  }

  @Test
  fun longImageExportPublishesOne1080PixelFileWithoutFolderPicker() {
    composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("note-card-$noteId").fetchSemanticsNodes().isNotEmpty() }
    composeRule.onNodeWithTag("note-card-$noteId").performTouchInput { longClick() }
    composeRule.onNodeWithTag("home-export-button").performClick()
    composeRule.onNodeWithTag("export-format-png-long").performScrollTo().performClick()
    composeRule.waitUntil(20_000) { findPublishedImage(exportToken) != null }
    val uri = checkNotNull(findPublishedImage(exportToken))
    exportedPngUri = uri
    val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
    org.junit.Assert.assertEquals(1080, bounds.outWidth)
    org.junit.Assert.assertTrue(bounds.outHeight > 0)
    resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
      "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("%$exportToken%"), null)?.use {
      org.junit.Assert.assertEquals(1, it.count)
    } ?: error("系统相册未返回文件")
  }

  @After
  fun removeExportTestData() = runBlocking {
    exportedPngUri?.let {
      ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver.delete(it, null, null)
    }
    val database = AnAnDatabase.getInstance(ApplicationProvider.getApplicationContext())
    database.noteDao().deletePermanently(noteId)
    // This test's unique note only; never clear the user's unrelated export history.
    database.openHelper.writableDatabase.execSQL("DELETE FROM export_history WHERE noteId = ?", arrayOf(noteId))
  }

  private fun findPublishedImage(token: String): Uri? {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val cursor = context.contentResolver.query(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      arrayOf(MediaStore.Images.Media._ID),
      "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND " +
        "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
      arrayOf("%$token%", "Pictures/AnAn Notes/Exports/"),
      null,
    ) ?: return null
    return cursor.use { rows ->
      if (!rows.moveToFirst()) return@use null
      ContentUris.withAppendedId(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        rows.getLong(rows.getColumnIndexOrThrow(MediaStore.Images.Media._ID)),
      )
    }
  }
}

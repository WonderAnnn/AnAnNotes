package com.anan.anannotes.feature.imports

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareImportHandlerTest {
  @Test
  fun extractsSharedTextWithoutTrimmingUserContent() {
    val intent = Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, "  原样保留\n第二行  ")

    val payload = ShareImportHandler.parse(intent)

    assertEquals("  原样保留\n第二行  ", payload?.text)
    assertTrue(payload?.uris.isNullOrEmpty())
  }

  @Test
  fun extractsTextFromClipDataWhenTheSenderOmitsExtraText() {
    val intent = Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .apply { clipData = ClipData.newPlainText("shared text", "剪贴板分享的文字\n原样保留") }

    val payload = ShareImportHandler.parse(intent)

    assertEquals("剪贴板分享的文字\n原样保留", payload?.text)
  }

  @Test
  fun collectsAndDeduplicatesMultipleSharedUrisInClipOrder() {
    val first = Uri.parse("content://share/image/1")
    val second = Uri.parse("content://share/image/2")
    val clipData = ClipData("shared images", arrayOf("image/*"), ClipData.Item(first)).apply {
      addItem(ClipData.Item(second))
    }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
      .setType("image/*")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(first, second))
      .apply { setClipData(clipData) }

    val payload = ShareImportHandler.parse(intent)

    assertEquals(listOf(first, second), payload?.uris)
  }

  @Test
  fun ignoresUnsupportedActionsAndEmptySharePayloads() {
    assertNull(ShareImportHandler.parse(Intent(Intent.ACTION_VIEW)))
    assertNull(ShareImportHandler.parse(Intent(Intent.ACTION_SEND).setType("text/plain")))
    assertNull(
      ShareImportHandler.parse(
        Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_TEXT, "not supported"),
      ),
    )
  }

  @Test
  fun appIsDiscoverableForTextAndImageSharesWithoutBroadPermissions() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val textShare = Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .setPackage(context.packageName)
    val imageShare = Intent(Intent.ACTION_SEND)
      .setType("image/jpeg")
      .setPackage(context.packageName)
    val multipleImageShare = Intent(Intent.ACTION_SEND_MULTIPLE)
      .setType("image/png")
      .setPackage(context.packageName)
    val unsupportedPdfShare = Intent(Intent.ACTION_SEND)
      .setType("application/pdf")
      .setPackage(context.packageName)

    assertResolvesToApp(context, textShare)
    assertResolvesToApp(context, imageShare)
    assertResolvesToApp(context, multipleImageShare)
    assertNull(context.packageManager.resolveActivity(unsupportedPdfShare, 0))
  }

  private fun assertResolvesToApp(context: Context, intent: Intent) {
    val resolved = context.packageManager.resolveActivity(intent, 0)
    assertEquals(
      ComponentName(context.packageName, "com.anan.anannotes.MainActivity"),
      resolved?.activityInfo?.let { ComponentName(it.packageName, it.name) },
    )
  }
}

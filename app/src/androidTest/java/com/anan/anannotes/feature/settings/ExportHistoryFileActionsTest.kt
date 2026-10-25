package com.anan.anannotes.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.repository.ExportFileSize
import com.anan.anannotes.data.repository.ExportFileSizeReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportHistoryFileActionsTest {
  @Test
  fun openPassesActualContentUriWithReadGrantAndNoWriteGrant() = runBlocking {
    val context = LaunchContext()
    val actions = ExportHistoryFileActions(context, ExportFileSizeReader { ExportFileSize.Known(17L) })

    assertNull(actions.open(record()))

    val intent = checkNotNull(context.launched)
    assertEquals(Intent.ACTION_VIEW, intent.action)
    assertEquals(Uri.parse("content://exports/actual"), intent.data)
    assertEquals("text/plain", intent.type)
    assertReadOnlyGrant(intent)
    assertEquals(intent.data, intent.clipData?.getItemAt(0)?.uri)
  }

  @Test
  fun shareGrantsReadAccessOnBothSendIntentAndChooser() = runBlocking {
    val context = LaunchContext()
    val actions = ExportHistoryFileActions(context, ExportFileSizeReader { ExportFileSize.Unknown })

    assertNull(actions.share(record().copy(format = ExportFormat.PDF)))

    val chooser = checkNotNull(context.launched)
    assertEquals(Intent.ACTION_CHOOSER, chooser.action)
    @Suppress("DEPRECATION")
    val send = checkNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
    assertEquals(Intent.ACTION_SEND, send.action)
    assertEquals("application/pdf", send.type)
    @Suppress("DEPRECATION")
    val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    assertEquals(Uri.parse("content://exports/actual"), stream)
    assertEquals(Uri.parse("content://exports/actual"), send.clipData?.getItemAt(0)?.uri)
    assertReadOnlyGrant(send)
    assertReadOnlyGrant(chooser)
  }

  @Test
  fun failedRecordGivesFeedbackWithoutStartingAnActivity() = runBlocking {
    val context = LaunchContext()
    val actions = ExportHistoryFileActions(context, ExportFileSizeReader { ExportFileSize.Known(17L) })

    val message = actions.open(record().copy(succeeded = false, errorCode = "AN-EXP-003", destinationUri = ""))

    assertNotNull(message)
    assertTrue(message!!.contains("导出失败"))
    assertNull(context.launched)
  }

  @Test
  fun missingOrInaccessibleExportGivesFeedbackWithoutStartingAnActivity() = runBlocking {
    val context = LaunchContext()
    val actions = ExportHistoryFileActions(context, ExportFileSizeReader { ExportFileSize.Unavailable })

    val message = actions.share(record())

    assertNotNull(message)
    assertTrue(message!!.contains("无法访问"))
    assertNull(context.launched)
  }

  @Test
  fun noViewingAppReturnsUsefulFeedbackInsteadOfThrowing() = runBlocking {
    val context = LaunchContext(noApp = true)
    val actions = ExportHistoryFileActions(context, ExportFileSizeReader { ExportFileSize.Known(17L) })

    val message = actions.open(record())

    assertNotNull(message)
    assertTrue(message!!.contains("应用"))
    assertTrue(message.contains("打开"))
  }

  @Test
  fun nonContentUriIsRejectedWithoutExposingAnInternalFilePath() = runBlocking {
    val context = LaunchContext()
    val actions = ExportHistoryFileActions(context, ExportFileSizeReader { ExportFileSize.Known(17L) })

    val message = actions.open(record().copy(destinationUri = "file:///data/user/0/app/files/export.txt"))

    assertNotNull(message)
    assertNull(context.launched)
    assertTrue(!message!!.contains("/data/user/"))
  }

  private fun assertReadOnlyGrant(intent: Intent) {
    assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
  }

  private fun record() = ExportHistoryModel(
    id = "ordinary-record",
    noteId = "ordinary-note",
    format = ExportFormat.TXT,
    fileName = "实际文件.txt",
    destinationUri = "content://exports/actual",
    createdAtMillis = 1234L,
    succeeded = true,
  )

  private class LaunchContext(private val noApp: Boolean = false) :
    ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
    var launched: Intent? = null

    override fun startActivity(intent: Intent) {
      if (noApp) throw ActivityNotFoundException("No handler installed")
      launched = intent
    }
  }
}

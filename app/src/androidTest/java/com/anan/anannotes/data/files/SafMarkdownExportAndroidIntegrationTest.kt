package com.anan.anannotes.data.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportRequest
import com.anan.anannotes.feature.export.ExportSnapshot
import com.anan.anannotes.feature.export.ExportSnapshotProvider
import com.anan.anannotes.feature.export.OrdinaryExportService
import com.anan.anannotes.feature.export.OutputTarget
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafMarkdownExportAndroidIntegrationTest {
  @Test
  fun markdownZipRoundTripsThroughDocumentsContractWithoutOverwritingDuplicateNames() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val treeUri = TestSafDocumentsProvider.treeUri()
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )
    val document = NoteDocument(
      note = NoteModel(
        id = "markdown-saf-note",
        folderId = "markdown-saf-folder",
        title = "SAF Markdown",
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
      ),
      blocks = listOf(
        BlockModel(
          id = "markdown-saf-text",
          noteId = "markdown-saf-note",
          position = 0,
          type = BlockType.TEXT,
          text = "真实 SAF 往返\n字面 **星号**\r\nemoji 😀",
        ),
      ),
      todos = emptyList(),
    )
    val exporter = OrdinaryExportService(
      snapshots = ExportSnapshotProvider { noteId ->
        document.takeIf { noteId == document.note.id }?.let { ExportSnapshot(it, "可靠性测试") }
      },
      files = SafExportFileStore(context.contentResolver),
      openImage = { null },
      today = { LocalDate.of(2026, 10, 3) },
    )
    val request = ExportRequest(
      noteIds = listOf(document.note.id),
      format = ExportFormat.MARKDOWN_ZIP,
      target = OutputTarget.DocumentTree(treeUri.toString()),
    )

    try {
      val firstEvents = exporter.export(request).toList()
      val first = firstEvents.filterIsInstance<ExportEvent.ItemCompleted>().single()
      assertEquals(1, firstEvents.filterIsInstance<ExportEvent.Completed>().single().succeeded)
      assertEquals("Markdown SAF Test", first.destinationName)
      assertEquals(TestSafDocumentsProvider.AUTHORITY, Uri.parse(first.location).authority)
      assertTrue(first.fileName.endsWith("可靠性测试_SAF Markdown_2026-10-03.zip"))
      val firstZip = readDocument(context, first.location)
      assertEquals("application/zip", context.contentResolver.getType(Uri.parse(first.location)))
      assertEquals(setOf("SAF Markdown.md"), firstZip.keys)
      assertArrayEquals(
        "# SAF Markdown\n\n真实 SAF 往返\n字面 \\*\\*星号\\*\\*\r\nemoji 😀".toByteArray(Charsets.UTF_8),
        firstZip.getValue("SAF Markdown.md"),
      )

      val secondEvents = exporter.export(request).toList()
      val second = secondEvents.filterIsInstance<ExportEvent.ItemCompleted>().single()
      assertEquals(1, secondEvents.filterIsInstance<ExportEvent.Completed>().single().succeeded)
      assertEquals("可靠性测试_SAF Markdown_2026-10-03 (2).zip", second.fileName)
      assertTrue("同名导出不得覆盖 SAF 中的首个文件", first.location != second.location)
      assertArrayEquals(firstZip.getValue("SAF Markdown.md"), readDocument(context, first.location).getValue("SAF Markdown.md"))
      assertArrayEquals(firstZip.getValue("SAF Markdown.md"), readDocument(context, second.location).getValue("SAF Markdown.md"))

      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
      )
      context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
          val names = buildSet {
            while (cursor.moveToNext()) add(cursor.getString(nameColumn))
          }
          assertEquals(setOf(first.fileName, second.fileName), names)
        } ?: error("测试 DocumentsProvider 未返回目录内容")
    } finally {
      DocumentsContract.deleteDocument(context.contentResolver, rootDocumentUri)
    }
  }

  @Test
  fun failedSafWriteRemovesTheCreatedPartialDocument() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val treeUri = TestSafDocumentsProvider.treeUri()
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )
    val noteId = "markdown-saf-partial-write"
    val document = NoteDocument(
      note = NoteModel(
        id = noteId,
        folderId = "markdown-saf-folder",
        title = "部分写入清理",
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
      ),
      blocks = listOf(
        BlockModel(
          id = "markdown-saf-partial-write-text",
          noteId = noteId,
          position = 0,
          type = BlockType.TEXT,
          text = "在创建 SAF 文档后模拟打开写入流失败",
        ),
      ),
      todos = emptyList(),
    )
    val exporter = OrdinaryExportService(
      snapshots = ExportSnapshotProvider { requestedId ->
        document.takeIf { requestedId == noteId }?.let { ExportSnapshot(it, "可靠性测试") }
      },
      files = SafExportFileStore(context.contentResolver),
      openImage = { null },
      today = { LocalDate.of(2026, 10, 3) },
    )

    try {
      TestSafDocumentsProvider.failNextOpenForTest()
      val events = exporter.export(
        ExportRequest(
          noteIds = listOf(noteId),
          format = ExportFormat.MARKDOWN_ZIP,
          target = OutputTarget.DocumentTree(treeUri.toString()),
        ),
      ).toList()

      val failure = events.filterIsInstance<ExportEvent.ItemFailed>().single()
      val completed = events.filterIsInstance<ExportEvent.Completed>().single()
      assertEquals(0, completed.succeeded)
      assertEquals(1, completed.failed)
      assertEquals(ExportErrorCode.DESTINATION_UNAVAILABLE, failure.code)

      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
      )
      context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        ?.use { cursor -> assertEquals("失败的 SAF 导出不能遗留半成品", 0, cursor.count) }
        ?: error("测试 DocumentsProvider 未返回目录内容")
    } finally {
      DocumentsContract.deleteDocument(context.contentResolver, rootDocumentUri)
    }
  }

  private fun readDocument(context: Context, uriString: String): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    context.contentResolver.openInputStream(Uri.parse(uriString)).use { input ->
      ZipInputStream(checkNotNull(input)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          entries[entry.name] = zip.readBytes()
          zip.closeEntry()
        }
      }
    }
    return entries
  }
}

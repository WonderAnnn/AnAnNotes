package com.anan.anannotes.feature.imports

import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.provider.MediaStore
import java.nio.charset.StandardCharsets
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.MainActivity
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportFileReceipt
import com.anan.anannotes.feature.export.ExportFileStore
import com.anan.anannotes.feature.export.ExportRequest
import com.anan.anannotes.feature.export.ExportSnapshot
import com.anan.anannotes.feature.export.ExportSnapshotProvider
import com.anan.anannotes.feature.export.OrdinaryExportService
import com.anan.anannotes.feature.export.OutputTarget
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ShareImportFlowTest {
  @Test
  fun sharedTextOpensASingleLocalNoteInTheUncategorizedFolder() {
    runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = NoteRepository(AnAnDatabase.getInstance(context))
    val expectedBody = "分享流程验收\n来自 QQ 或微信的内容"
    val intent = Intent(context, MainActivity::class.java)
      .setAction(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, expectedBody)

    var importedId: String? = null
    ActivityScenario.launch<MainActivity>(intent).use {
      val deadline = SystemClock.elapsedRealtime() + 8_000L
      while (importedId == null && SystemClock.elapsedRealtime() < deadline) {
        importedId = repository.observeAllNotes().first()
          .firstOrNull { it.title == "分享流程验收" }
          ?.id
        if (importedId == null) delay(100)
      }

      assertNotNull("分享内容没有保存为笔记", importedId)
      val document = repository.observeNote(checkNotNull(importedId)).first()
      assertEquals(UNCATEGORIZED_FOLDER_ID, document?.note?.folderId)
      assertEquals(expectedBody, document?.blocks?.single { it.type == BlockType.TEXT }?.text)
    }

      importedId?.let { repository.deletePermanently(it) }
    }
  }

  @Test
  fun sharedImageBecomesAnOriginalImageBlockWithItsAspectRatio() {
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val resolver = context.contentResolver
      val repository = NoteRepository(AnAnDatabase.getInstance(context))
      val existingIds = repository.observeAllNotes().first().mapTo(mutableSetOf()) { it.id }
      val imageUri = resolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, "anan-share-${SystemClock.elapsedRealtime()}.png")
          put(MediaStore.Images.Media.MIME_TYPE, "image/png")
          put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AnAnNotes/Test")
        },
      )
      assertNotNull("无法创建本地图片分享测试文件", imageUri)

      var importedNoteId: String? = null
      try {
        resolver.openOutputStream(checkNotNull(imageUri)).use { output ->
          checkNotNull(output)
          val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
          bitmap.eraseColor(Color.WHITE)
          assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
          bitmap.recycle()
        }
        val intent = Intent(context, MainActivity::class.java)
          .setAction(Intent.ACTION_SEND)
          .setType("image/png")
          .putExtra(Intent.EXTRA_STREAM, imageUri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        ActivityScenario.launch<MainActivity>(intent).use {
          val deadline = SystemClock.elapsedRealtime() + 12_000L
          while (importedNoteId == null && SystemClock.elapsedRealtime() < deadline) {
            importedNoteId = repository.observeAllNotes().first()
              .firstOrNull { it.id !in existingIds }
              ?.id
            if (importedNoteId == null) delay(100)
          }
          assertNotNull("分享图片没有保存为笔记", importedNoteId)
          val document = repository.observeNote(checkNotNull(importedNoteId)).first()
          val image = document?.blocks?.singleOrNull { it.type == BlockType.IMAGE }
          assertNotNull("导入结果缺少图片块", image)
          assertEquals(2f, image?.aspectRatio ?: 0f, 0.01f)
          assertTrue(image?.assetPath?.startsWith("notes/assets/") == true)
          assertTrue(document?.blocks?.lastOrNull()?.type == BlockType.TEXT)
        }
      } finally {
        importedNoteId?.let { repository.deletePermanently(it) }
        resolver.delete(checkNotNull(imageUri), null, null)
      }
    }
  }

  @Test
  fun sharedMarkdownDocumentImportsUtf8TextAndPreservesLineBreaks() {
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val resolver = context.contentResolver
      val repository = NoteRepository(AnAnDatabase.getInstance(context))
      val existingIds = repository.observeAllNotes().first().mapTo(mutableSetOf()) { it.id }
      val expectedBody = "文件首行\nMarkdown **正文**\r\n末行"
      val fileUri = resolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, "anan-import-${SystemClock.elapsedRealtime()}.md")
          put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
          put(MediaStore.Downloads.RELATIVE_PATH, "Download/AnAnNotes/Test")
        },
      )
      assertNotNull("无法创建 Markdown 导入测试文件", fileUri)

      var importedNoteId: String? = null
      try {
        val output = checkNotNull(resolver.openOutputStream(checkNotNull(fileUri)))
        output.use { it.write(expectedBody.toByteArray(StandardCharsets.UTF_8)) }
        val intent = Intent(context, MainActivity::class.java)
          .setAction(Intent.ACTION_SEND)
          .setType("text/plain")
          .putExtra(Intent.EXTRA_STREAM, fileUri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        ActivityScenario.launch<MainActivity>(intent).use {
          val deadline = SystemClock.elapsedRealtime() + 8_000L
          while (importedNoteId == null && SystemClock.elapsedRealtime() < deadline) {
            importedNoteId = repository.observeAllNotes().first()
              .firstOrNull { it.id !in existingIds }
              ?.id
            if (importedNoteId == null) delay(100)
          }
          assertNotNull("Markdown 文件没有导入为笔记", importedNoteId)
          val document = repository.observeNote(checkNotNull(importedNoteId)).first()
          assertEquals(expectedBody, document?.blocks?.single { it.type == BlockType.TEXT }?.text)
        }
      } finally {
        importedNoteId?.let { repository.deletePermanently(it) }
        resolver.delete(checkNotNull(fileUri), null, null)
      }
    }
  }

  @Test
  fun sharedMarkdownNoteExportsAsUtf8MarkdownInsidePortableZip() {
    runBlocking {
      val context = ApplicationProvider.getApplicationContext<Context>()
      val resolver = context.contentResolver
      val database = AnAnDatabase.getInstance(context)
      val repository = NoteRepository(database)
      val existingIds = repository.observeAllNotes().first().mapTo(mutableSetOf()) { it.id }
      val expectedBody = "导入标题\n原样保留 **星号**、[链接](https://example.test)\r\n末行\n代码：`x < y` 😀"
      val fileUri = resolver.insert(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, "anan-roundtrip-${SystemClock.elapsedRealtime()}.md")
          put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
          put(MediaStore.Downloads.RELATIVE_PATH, "Download/AnAnNotes/Test")
        },
      )
      assertNotNull("无法创建 Markdown 往返测试文件", fileUri)

      var importedNoteId: String? = null
      try {
        resolver.openOutputStream(checkNotNull(fileUri)).use { output ->
          checkNotNull(output).write(expectedBody.toByteArray(StandardCharsets.UTF_8))
        }
        val shareIntent = Intent(context, MainActivity::class.java)
          .setAction(Intent.ACTION_SEND)
          .setType("text/markdown")
          .putExtra(Intent.EXTRA_STREAM, fileUri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        ActivityScenario.launch<MainActivity>(shareIntent).use {
          val deadline = SystemClock.elapsedRealtime() + 8_000L
          while (importedNoteId == null && SystemClock.elapsedRealtime() < deadline) {
            importedNoteId = repository.observeAllNotes().first()
              .firstOrNull { it.id !in existingIds }
              ?.id
            if (importedNoteId == null) delay(100)
          }
        }

        val importedId = checkNotNull(importedNoteId) { "Markdown 分享没有生成笔记" }
        val importedDocument = checkNotNull(repository.getDocumentSnapshot(importedId))
        assertEquals(expectedBody, importedDocument.blocks.single { it.type == BlockType.TEXT }.text)

        val output = RecordingMarkdownExportFileStore()
        val exporter = OrdinaryExportService(
          snapshots = ExportSnapshotProvider { noteId ->
            repository.getDocumentSnapshot(noteId)?.let { ExportSnapshot(it, "未分类") }
          },
          files = output,
          openImage = { null },
          today = { LocalDate.of(2026, 10, 3) },
        )
        val events = exporter.export(
          ExportRequest(
            noteIds = listOf(importedId),
            format = ExportFormat.MARKDOWN_ZIP,
            target = OutputTarget.DocumentTree("content://anan-test/markdown-export"),
          ),
        ).toList()

        assertEquals("application/zip", output.mimeType)
        assertTrue(output.fileName.orEmpty().endsWith(".zip"))
        val completed = events.filterIsInstance<ExportEvent.Completed>().single()
        assertEquals(1, completed.succeeded)
        assertEquals(0, completed.failed)

        val entries = mutableMapOf<String, ByteArray>()
        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(checkNotNull(output.bytes))).use { zip ->
          while (true) {
            val entry = zip.nextEntry ?: break
            entryNames += entry.name
            entries[entry.name] = zip.readBytes()
            zip.closeEntry()
          }
        }
        val markdownFiles = entries.filterKeys { it.endsWith(".md") }
        assertEquals("ZIP 应包含且只包含一份 Markdown 正文", 1, markdownFiles.size)
        assertEquals("导入标题", importedDocument.note.title)
        assertEquals(setOf("导入标题.md"), entries.keys)
        assertEquals(listOf("导入标题.md"), entryNames)
        val expectedMarkdown = "# 导入标题\n\n导入标题\n" +
          "原样保留 \\*\\*星号\\*\\*、\\[链接\\]\\(https://example\\.test\\)\r\n" +
          "末行\n代码：\\`x &lt; y\\` 😀"
        assertArrayEquals(expectedMarkdown.toByteArray(StandardCharsets.UTF_8), markdownFiles.values.single())
      } finally {
        importedNoteId?.let { repository.deletePermanently(it) }
        resolver.delete(checkNotNull(fileUri), null, null)
      }
    }
  }

  private class RecordingMarkdownExportFileStore : ExportFileStore {
    var fileName: String? = null
      private set
    var mimeType: String? = null
      private set
    var bytes: ByteArray? = null
      private set

    override suspend fun write(
      target: OutputTarget.DocumentTree,
      requestedName: String,
      mimeType: String,
      content: (OutputStream) -> Unit,
    ): ExportFileReceipt {
      fileName = requestedName
      this.mimeType = mimeType
      bytes = ByteArrayOutputStream().also(content).toByteArray()
      return ExportFileReceipt(requestedName, "content://anan-test/exported-markdown.zip", "测试目录")
    }
  }
}

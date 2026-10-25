package com.anan.anannotes.feature.privatemodule

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.data.files.TestSafDocumentsProvider
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.ExportHistoryRepository
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.OutputTarget
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateExportSafIntegrationTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun privateMarkdownAndOriginalImageRoundTripThroughSafWithoutOrdinaryDataLeakage() = runBlocking {
    val token = UUID.randomUUID().toString().replace("-", "")
    val title = "PrivateSafQA_$token"
    val password = "private-saf-test-password"
    val privateRoot = File(context.cacheDir, "private-saf-qa-$token")
    val store = PrivateStore(privateRoot)
    val repository = PrivateRepository(store)
    val treeUri = TestSafDocumentsProvider.treeUri()
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )
    var access: com.anan.anannotes.security.PrivateAccess? = null
    try {
      access = repository.setup(password.toCharArray(), null)
      val imageBytes = createFixturePng()
      val imageReference = store.writePrivateAsset(checkNotNull(access), imageBytes)
      val saved = repository.save(
        checkNotNull(access),
        PrivateEditSnapshot(
          draftId = "private-saf-qa-$token",
          noteId = null,
          folderId = "private-uncategorized",
          title = title,
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(id = "text-$token", type = BlockType.TEXT, text = "私密正文 $token"),
            NoteBlockDraft(
              id = "image-$token",
              type = BlockType.IMAGE,
              assetPath = imageReference,
              widthPercent = 64f,
              aspectRatio = 1f,
            ),
          ),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = System.currentTimeMillis(),
        ),
      ) as PrivateSaveResult.Saved
      val request = PrivateExportRequest(
        saved.noteId,
        ExportFormat.MARKDOWN_ZIP,
        OutputTarget.DocumentTree(treeUri.toString()),
      )
      val service = PrivateExportServiceFactory.create(context.applicationContext as Application, repository, store)

      val firstEvents = exportWithPassword(service, repository, checkNotNull(access), password, request)
      val first = firstEvents.filterIsInstance<PrivateExportEvent.ItemCompleted>().single()
      assertEquals(1, firstEvents.filterIsInstance<PrivateExportEvent.Completed>().single().succeeded)
      assertEquals("Markdown SAF Test", first.destinationName)
      assertEquals(TestSafDocumentsProvider.AUTHORITY, Uri.parse(first.location).authority)
      val firstZip = readZip(Uri.parse(first.location))
      assertEquals(setOf("$title.md", "images/", "images/image-001.bin"), firstZip.keys)
      val markdown = String(firstZip.getValue("$title.md"), Charsets.UTF_8)
      assertTrue("私密正文应保留在导出 Markdown 中", markdown.contains("私密正文 $token"))
      assertArrayEquals(imageBytes, firstZip.getValue("images/image-001.bin"))

      val secondEvents = exportWithPassword(service, repository, checkNotNull(access), password, request)
      val second = secondEvents.filterIsInstance<PrivateExportEvent.ItemCompleted>().single()
      assertEquals(1, secondEvents.filterIsInstance<PrivateExportEvent.Completed>().single().succeeded)
      assertTrue("重复导出必须创建新文件而不能覆盖", first.location != second.location)
      assertTrue(second.fileName.endsWith("(2).zip"))
      assertArrayEquals(firstZip.getValue("$title.md"), readZip(Uri.parse(second.location)).getValue("$title.md"))

      val names = childNames(treeUri)
      assertEquals(setOf(first.fileName, second.fileName), names)
      val privateHistory = repository.listPrivateExportHistory(checkNotNull(access))
        .filter { it.noteId == saved.noteId && it.succeeded }
      assertEquals(2, privateHistory.size)
      assertTrue(privateHistory.all { it.destinationUri.startsWith("content://") })
      val ordinaryHistory = ExportHistoryRepository(AnAnDatabase.getInstance(context)).latest()
      assertFalse("私密 SAF 导出历史不得写入普通 Room", ordinaryHistory.any { it.fileName.contains(token) })
      assertFalse(
        "私密标题不得进入普通笔记表",
        AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().any { it.title == title },
      )
    } finally {
      runCatching { DocumentsContract.deleteDocument(context.contentResolver, rootUri) }
      access?.let(repository::revoke)
      privateRoot.deleteRecursively()
    }
  }

  @Test
  fun failedPrivateSafWriteRemovesPartialDocumentAndRecordsOnlyPrivateFailure() = runBlocking {
    val token = UUID.randomUUID().toString().replace("-", "")
    val title = "PrivateSafFailureQA_$token"
    val password = "private-saf-failure-password"
    val privateRoot = File(context.cacheDir, "private-saf-failure-qa-$token")
    val store = PrivateStore(privateRoot)
    val repository = PrivateRepository(store)
    val treeUri = TestSafDocumentsProvider.treeUri()
    val rootUri = DocumentsContract.buildDocumentUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )
    var access: com.anan.anannotes.security.PrivateAccess? = null
    try {
      access = repository.setup(password.toCharArray(), null)
      val saved = repository.save(
        checkNotNull(access),
        PrivateEditSnapshot(
          draftId = "private-saf-failure-qa-$token",
          noteId = null,
          folderId = "private-uncategorized",
          title = title,
          titleManuallySet = true,
          blocks = listOf(NoteBlockDraft(id = "text-$token", type = BlockType.TEXT, text = "failure fixture")),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = System.currentTimeMillis(),
        ),
      ) as PrivateSaveResult.Saved
      val request = PrivateExportRequest(
        saved.noteId,
        ExportFormat.TXT,
        OutputTarget.DocumentTree(treeUri.toString()),
      )
      val service = PrivateExportServiceFactory.create(context.applicationContext as Application, repository, store)
      val grant = checkNotNull(
        repository.authorizeExport(checkNotNull(access), password.toCharArray(), request.scope()),
      )
      TestSafDocumentsProvider.failNextOpenForTest()

      val events = service.export(checkNotNull(access), grant, request).toList()
      val failure = events.filterIsInstance<PrivateExportEvent.ItemFailed>().single()
      val completed = events.filterIsInstance<PrivateExportEvent.Completed>().single()
      assertEquals(ExportErrorCode.DESTINATION_UNAVAILABLE, failure.code)
      assertEquals(0, completed.succeeded)
      assertEquals(1, completed.failed)
      assertTrue("创建文档后写入失败必须删除该半成品", childNames(treeUri).isEmpty())

      val failureHistory = repository.listPrivateExportHistory(checkNotNull(access))
        .filter { it.noteId == saved.noteId && !it.succeeded }
      assertEquals(1, failureHistory.size)
      assertEquals(ExportErrorCode.DESTINATION_UNAVAILABLE, failureHistory.single().errorCode)
      assertFalse("失败记录不得进入普通导出历史", ExportHistoryRepository(AnAnDatabase.getInstance(context)).latest()
        .any { it.fileName.contains(token) })
    } finally {
      runCatching { DocumentsContract.deleteDocument(context.contentResolver, rootUri) }
      access?.let(repository::revoke)
      privateRoot.deleteRecursively()
    }
  }

  private suspend fun exportWithPassword(
    service: PrivateExportService,
    repository: PrivateRepository,
    access: com.anan.anannotes.security.PrivateAccess,
    password: String,
    request: PrivateExportRequest,
  ): List<PrivateExportEvent> {
    val grant = repository.authorizeExport(access, password.toCharArray(), request.scope())
    assertNotNull("每次私密导出都必须重新验证密码", grant)
    return service.export(access, checkNotNull(grant), request).toList()
  }

  private fun childNames(treeUri: Uri): Set<String> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
      treeUri,
      DocumentsContract.getTreeDocumentId(treeUri),
    )
    return context.contentResolver.query(
      childrenUri,
      arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
      null,
      null,
      null,
    )?.use { cursor ->
      val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      buildSet {
        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
      }
    } ?: error("测试 DocumentsProvider 未返回目录内容")
  }

  private fun readZip(uri: Uri): Map<String, ByteArray> {
    val entries = linkedMapOf<String, ByteArray>()
    context.contentResolver.openInputStream(uri).use { raw ->
      ZipInputStream(checkNotNull(raw)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          entries[entry.name] = zip.readBytes()
          zip.closeEntry()
        }
      }
    }
    return entries
  }

  private fun createFixturePng(): ByteArray {
    val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
      .apply { eraseColor(0xff25a7a0.toInt()) }
    return ByteArrayOutputStream().use { output ->
      check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
      bitmap.recycle()
      output.toByteArray()
    }
  }
}

package com.anan.anannotes.data.files

import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.InlineStyleRange
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportOperationException
import com.anan.anannotes.feature.export.ExportRequest
import com.anan.anannotes.feature.export.ExportSnapshot
import com.anan.anannotes.feature.export.ExportSnapshotProvider
import com.anan.anannotes.feature.export.OrdinaryExportService
import com.anan.anannotes.feature.export.OutputTarget
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafExportFileStoreTest {
  @Test
  fun duplicateNameCreatesNewFileAndPreservesExistingBytes() = runTest {
    val access = MemoryDocumentAccess().apply { seed("note.txt", "旧内容".encodeToByteArray()) }
    val store = SafExportFileStore(access)

    val receipt = store.write(target, "note.txt", "text/plain") { it.write("新内容".encodeToByteArray()) }

    assertEquals("note (2).txt", receipt.fileName)
    assertArrayEquals("旧内容".encodeToByteArray(), access.contents("note.txt"))
    assertArrayEquals("新内容".encodeToByteArray(), access.contents(receipt.fileName))
  }

  @Test
  fun failedPartialWriteRemovesOnlyPartialFileAndKeepsEarlierSuccess() = runTest {
    val access = MemoryDocumentAccess()
    val store = SafExportFileStore(access)
    store.write(target, "first.txt", "text/plain") { it.write("保留".encodeToByteArray()) }

    val failure = runCatching {
      store.write(target, "partial.txt", "text/plain") { output ->
        output.write("不完整".encodeToByteArray())
        throw IOException("模拟中途写入失败")
      }
    }.exceptionOrNull()

    assertTrue(failure is ExportOperationException)
    assertEquals(ExportErrorCode.WRITE_FAILED, (failure as ExportOperationException).code)
    assertEquals(setOf("first.txt"), access.names())
    assertArrayEquals("保留".encodeToByteArray(), access.contents("first.txt"))
  }

  @Test
  fun outOfSpaceDeletesPartialSafDocumentAndReturnsStableStorageError() = runTest {
    var deleted = false
    val access = object : SafDocumentAccess {
      override fun listNames(target: OutputTarget.DocumentTree) = emptySet<String>()
      override fun create(
        target: OutputTarget.DocumentTree,
        name: String,
        mimeType: String,
      ) = SafDocumentRef("content://test/root/$name", name)
      override fun openOutputStream(document: SafDocumentRef): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
          throw IOException("No space left on device (ENOSPC)")
        }
      }
      override fun displayName(document: SafDocumentRef) = document.displayName
      override fun delete(document: SafDocumentRef): Boolean {
        deleted = true
        return true
      }
    }

    val error = runCatching {
      SafExportFileStore(access).write(target, "out-of-space.txt", "text/plain") { it.write(byteArrayOf(1)) }
    }.exceptionOrNull() as ExportOperationException

    assertEquals(ExportErrorCode.INSUFFICIENT_STORAGE, error.code)
    assertTrue(error.message.contains("空间不足"))
    assertTrue("失败后应删除本次 SAF 部分文件", deleted)
  }

  @Test
  fun failedCleanupTellsTheUserThatAPartialFileMayRemain() = runTest {
    val access = MemoryDocumentAccess(failDelete = true)
    val store = SafExportFileStore(access)

    val failure = runCatching {
      store.write(target, "partial.txt", "text/plain") { output ->
        output.write("残留文件".encodeToByteArray())
        throw IOException("模拟中途写入失败")
      }
    }.exceptionOrNull()

    assertTrue(failure is ExportOperationException)
    assertEquals(ExportErrorCode.WRITE_FAILED, (failure as ExportOperationException).code)
    assertTrue(failure.message.contains("手动删除"))
    assertTrue(access.names().contains("partial.txt"))
  }

  @Test
  fun inaccessibleDestinationUsesStableDestinationError() = runTest {
    val store = SafExportFileStore(MemoryDocumentAccess(failListing = true))

    val failure = runCatching {
      store.write(target, "note.txt", "text/plain") { it.write(1) }
    }.exceptionOrNull()

    assertTrue(failure is ExportOperationException)
    assertEquals(ExportErrorCode.DESTINATION_UNAVAILABLE, (failure as ExportOperationException).code)
  }

  @Test
  fun missingImageIsNotReportedAsAnUnavailableDestination() = runTest {
    val image = BlockModel(
      id = "image-1",
      noteId = "note-1",
      position = 0,
      type = BlockType.IMAGE,
      assetPath = "missing/original.jpg",
    )
    val document = NoteDocument(
      note = NoteModel(id = "note-1", folderId = "folder-1", title = "含图片", createdAtMillis = 1L, updatedAtMillis = 1L),
      blocks = listOf(image),
      todos = emptyList(),
    )
    val service = OrdinaryExportService(
      snapshots = ExportSnapshotProvider { ExportSnapshot(document, "学习") },
      files = SafExportFileStore(MemoryDocumentAccess()),
      openImage = {
        object : InputStream() {
          override fun read(): Int = throw FileNotFoundException("原图缺失")
          override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw FileNotFoundException("原图缺失")
        }
      },
    )

    val failed = service.export(
      ExportRequest(listOf("note-1"), ExportFormat.MARKDOWN_ZIP, target),
    ).toList().filterIsInstance<ExportEvent.ItemFailed>().single()

    assertEquals(ExportErrorCode.WRITE_FAILED, failed.code)
    assertTrue(failed.message.contains("图片原图"))
  }

  @Test
  fun absentImageStreamIsReportedAsMissingSourceContent() = runTest {
    val image = BlockModel(
      id = "image-1",
      noteId = "note-1",
      position = 0,
      type = BlockType.IMAGE,
      assetPath = null,
    )
    val document = NoteDocument(
      note = NoteModel(id = "note-1", folderId = "folder-1", title = "缺图", createdAtMillis = 1L, updatedAtMillis = 1L),
      blocks = listOf(image),
      todos = emptyList(),
    )
    val service = OrdinaryExportService(
      snapshots = ExportSnapshotProvider { ExportSnapshot(document, "学习") },
      files = SafExportFileStore(MemoryDocumentAccess()),
      openImage = { null },
    )

    val failed = service.export(
      ExportRequest(listOf("note-1"), ExportFormat.MARKDOWN_ZIP, target),
    ).toList().filterIsInstance<ExportEvent.ItemFailed>().single()

    assertEquals(ExportErrorCode.WRITE_FAILED, failed.code)
    assertTrue(failed.message.contains("图片原图"))
  }

  @Test
  fun invalidMarkdownFormattingIsNotReportedAsAnUnavailableDestination() = runTest {
    val text = BlockModel(
      id = "text-1",
      noteId = "note-1",
      position = 0,
      type = BlockType.TEXT,
      text = "短",
      formatting = BlockFormattingState.Supported(
        BlockFormatting(inlineStyles = listOf(InlineStyleRange(0, 3, InlineStyle.BOLD))),
      ),
    )
    val document = NoteDocument(
      note = NoteModel(id = "note-1", folderId = "folder-1", title = "格式错误", createdAtMillis = 1L, updatedAtMillis = 1L),
      blocks = listOf(text),
      todos = emptyList(),
    )
    val service = OrdinaryExportService(
      snapshots = ExportSnapshotProvider { ExportSnapshot(document, "学习") },
      files = SafExportFileStore(MemoryDocumentAccess()),
      openImage = { null },
    )

    val failed = service.export(
      ExportRequest(listOf("note-1"), ExportFormat.MARKDOWN_ZIP, target),
    ).toList().filterIsInstance<ExportEvent.ItemFailed>().single()

    assertEquals(ExportErrorCode.WRITE_FAILED, failed.code)
    assertTrue(failed.message.contains("正文格式"))
  }

  private val target = OutputTarget.DocumentTree("content://test/root")

  private class MemoryDocumentAccess(
    private val failListing: Boolean = false,
    private val failDelete: Boolean = false,
  ) : SafDocumentAccess {
    private val files = linkedMapOf<String, ByteArray>()

    fun seed(name: String, bytes: ByteArray) {
      files[name] = bytes
    }

    fun names() = files.keys.toSet()
    fun contents(name: String) = files.getValue(name)

    override fun listNames(target: OutputTarget.DocumentTree): Set<String> {
      if (failListing) throw SecurityException("授权已撤销")
      return files.keys.toSet()
    }

    override fun create(
      target: OutputTarget.DocumentTree,
      name: String,
      mimeType: String,
    ): SafDocumentRef = SafDocumentRef("content://test/root/$name", name)

    override fun openOutputStream(document: SafDocumentRef): OutputStream = object : ByteArrayOutputStream() {
      override fun close() {
        super.close()
        files[document.displayName] = toByteArray()
      }
    }

    override fun displayName(document: SafDocumentRef): String = document.displayName

    override fun delete(document: SafDocumentRef): Boolean =
      if (failDelete) false else files.remove(document.displayName) != null
  }
}

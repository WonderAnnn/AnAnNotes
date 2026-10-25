package com.anan.anannotes.data.files

import com.anan.anannotes.feature.export.ExportOperationException
import com.anan.anannotes.feature.export.ExportErrorCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStorePngExportFileStoreTest {
  @Test
  fun choosesUniqueNameWritesThenPublishesMediaStoreItem() = runBlocking {
    val access = RecordingMediaStorePngDocumentAccess(existingNames = setOf("note.png"))
    val store = MediaStorePngExportFileStore(access)
    val bytes = byteArrayOf(0x11, 0x22, 0x33)

    val receipt = store.writePng("note.png") { it.write(bytes) }

    assertEquals("note (2).png", access.createdName)
    assertArrayEquals(bytes, access.output.toByteArray())
    assertTrue("文件内容关闭后才发布", access.published)
    assertFalse(access.deleted)
    assertEquals("content://media/images/42", receipt.location)
    assertEquals("note (2).png", receipt.fileName)
    assertEquals("图片/安安简记/导出", receipt.destinationName)
  }

  @Test
  fun failedWriteDeletesPendingMediaStoreItem() = runBlocking {
    val access = RecordingMediaStorePngDocumentAccess()
    val store = MediaStorePngExportFileStore(access)

    val error = runCatching {
      store.writePng("partial.png") { output ->
        output.write(byteArrayOf(1, 2))
        throw IOException("模拟编码失败")
      }
    }.exceptionOrNull()

    assertTrue(error is ExportOperationException)
    assertEquals("partial.png", access.createdName)
    assertTrue(access.deleted)
    assertFalse(access.published)
  }

  @Test
  fun outOfSpaceDeletesPendingItemAndReturnsStableStorageError() = runBlocking {
    var deleted = false
    var published = false
    val access = object : MediaStorePngDocumentAccess {
      override fun listNames() = emptySet<String>()
      override fun createPending(displayName: String) = MediaStorePngDocumentRef("content://media/pending/1", displayName)
      override fun openOutputStream(document: MediaStorePngDocumentRef): OutputStream = object : OutputStream() {
        override fun write(value: Int) {
          throw IOException("No space left on device (ENOSPC)")
        }
      }
      override fun displayName(document: MediaStorePngDocumentRef) = document.displayName
      override fun publish(document: MediaStorePngDocumentRef): Boolean {
        published = true
        return true
      }
      override fun delete(document: MediaStorePngDocumentRef): Boolean {
        deleted = true
        return true
      }
    }

    val error = runCatching {
      MediaStorePngExportFileStore(access).writePng("out-of-space.png") { it.write(byteArrayOf(1)) }
    }.exceptionOrNull() as ExportOperationException

    assertEquals(ExportErrorCode.INSUFFICIENT_STORAGE, error.code)
    assertTrue(error.message.orEmpty().contains("空间不足"))
    assertTrue("失败后应删除未发布的 MediaStore 项", deleted)
    assertFalse("失败的部分文件不得发布到系统相册", published)
  }

  @Test
  fun reportsPartialFileWhenCleanupFails() = runBlocking {
    val access = RecordingMediaStorePngDocumentAccess(deleteResult = false)
    val store = MediaStorePngExportFileStore(access)

    val error = runCatching {
      store.writePng("incomplete.png") { throw IOException("disk write failed") }
    }.exceptionOrNull() as ExportOperationException

    assertTrue(error.message.orEmpty().contains("incomplete.png"))
    assertTrue(error.message.orEmpty().contains("手动删除"))
    assertTrue(access.deleted)
  }

  private class RecordingMediaStorePngDocumentAccess(
    private val existingNames: Set<String> = emptySet(),
    private val deleteResult: Boolean = true,
  ) : MediaStorePngDocumentAccess {
    val output = ByteArrayOutputStream()
    var createdName: String? = null
      private set
    var published = false
      private set
    var deleted = false
      private set

    override fun listNames(): Set<String> = existingNames

    override fun createPending(displayName: String): MediaStorePngDocumentRef {
      createdName = displayName
      return MediaStorePngDocumentRef("content://media/images/42", displayName)
    }

    override fun openOutputStream(document: MediaStorePngDocumentRef): OutputStream = output

    override fun displayName(document: MediaStorePngDocumentRef): String = document.displayName

    override fun publish(document: MediaStorePngDocumentRef): Boolean {
      published = true
      return true
    }

    override fun delete(document: MediaStorePngDocumentRef): Boolean {
      deleted = true
      return deleteResult
    }
  }
}

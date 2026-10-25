package com.anan.anannotes.feature.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.data.files.MediaStorePngDocumentAccess
import com.anan.anannotes.data.files.MediaStorePngDocumentRef
import com.anan.anannotes.data.files.MediaStorePngExportFileStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.ExportHistoryRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RasterPngCancellationChainTest {
  @Test(timeout = 180_000L)
  fun realJobCancellationAfterSecondPageIdatDeletesOnlyPendingAndKeepsSuccessHistory() = runBlocking {
    val database = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext<Context>(), AnAnDatabase::class.java,
    ).build()
    var running: Job? = null
    try {
      val history = ExportHistoryRepository(database)
      val documents = DeterministicDocuments()
      val store = MediaStorePngExportFileStore(documents)
      val longNote = snapshot("cancel-note", (0 until 130).joinToString("\n") {
        "真实取消第${it.toString().padStart(3, '0')}行：中文文字在第二张图片写入时保留先前完整页面。"
      })
      val nextNote = snapshot("next-note", "取消之后的新批次仍然可以导出。")
      val service = OrdinaryExportService(
        snapshots = ExportSnapshotProvider { if (it == "cancel-note") longNote else nextNote },
        files = object : ExportFileStore {
          override suspend fun write(target: OutputTarget.DocumentTree, requestedName: String, mimeType: String,
            content: (OutputStream) -> Unit): ExportFileReceipt = error("PNG must use actual MediaStore store")
        },
        openImage = { error("fixture has no images") },
        socialPngFiles = store,
        rasterPngRenderer = AndroidRasterPngExportRenderer(exportTestResources()),
        history = history,
        today = { LocalDate.of(2026, 9, 28) },
      )
      val events = mutableListOf<ExportEvent>()
      lateinit var exportJob: Job
      var completionCause: Throwable? = null
      documents.onSecondCompleteIdat = {
        // This callback runs only after real Writer IDAT payload AND CRC have reached storage.
        // The destination does not throw cancellation or IO errors; cancel the running Job.
        exportJob.cancel(CancellationException("cancel after second-page IDAT"))
      }
      exportJob = launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
        service.export(ExportRequest(listOf("cancel-note"), ExportFormat.PNG_PAGED_600_DPI,
          OutputTarget.MediaStorePictures)).toList(events)
      }
      running = exportJob
      exportJob.invokeOnCompletion { completionCause = it }
      exportJob.start()
      withTimeout(120_000L) { exportJob.join() }

      assertTrue("real export Job must be cancelled", exportJob.isCancelled)
      assertTrue(completionCause is CancellationException)
      assertEquals("only first and currently cancelled second page created", 2, documents.created.size)
      val first = documents.created[0]
      val second = documents.created[1]
      assertTrue(first.published)
      assertFalse(first.deleted)
      assertTrue(second.deleted)
      assertFalse("cancelled second pending item must never be published", second.published)
      assertTrue(first.output.closed)
      assertTrue(second.output.closed)
      assertEquals(listOf(first.reference.uriString), documents.publishedUris)
      assertEquals(listOf(second.reference.uriString), documents.deletedUris)
      assertEquals(setOf(first.reference.uriString), documents.live.keys)

      val completeFirst = parsePng(first.output.bytes.toByteArray())
      assertEquals(4961, completeFirst.width)
      assertEquals(7016, completeFirst.height)
      assertEquals("IEND", completeFirst.types.last())
      val (firstInflated, firstFinished) = inflateCount(completeFirst.idat)
      assertTrue("first page zlib stream must be complete", firstFinished)
      assertEquals(7016L * (4961L * 3L + 1L), firstInflated)
      val partialSecond = parsePng(second.output.bytes.toByteArray())
      assertTrue("a real complete IDAT chunk was written before cancellation", "IDAT" in partialSecond.types)
      assertFalse("cancelled output has no IEND", "IEND" in partialSecond.types)
      val (partialInflated, partialFinished) = inflateCount(partialSecond.idat)
      assertTrue("cancel during pixel encoding, not before content or after finish: inflated=$partialInflated", partialInflated > 0L && partialInflated < 7016L * (4961L * 3L + 1L))
      assertFalse(partialFinished)

      val completed = events.filterIsInstance<ExportEvent.ItemCompleted>()
      assertEquals(1, completed.size)
      assertEquals(first.reference.uriString, completed.single().location)
      assertEquals(first.reference.displayName, completed.single().fileName)
      assertTrue(events.none { it is ExportEvent.ItemFailed || it is ExportEvent.Completed || it == ExportEvent.Busy })
      val saved = history.latest()
      assertEquals(1, saved.size)
      assertTrue(saved.single().succeeded)
      assertEquals(first.reference.uriString, saved.single().destinationUri)
      assertEquals(first.reference.displayName, saved.single().fileName)
      assertEquals(null, saved.single().errorCode)

      val nextEvents = withTimeout(30_000L) {
        service.export(ExportRequest(listOf("next-note"), ExportFormat.PNG_LONG_IMAGE,
          OutputTarget.MediaStorePictures)).toList()
      }
      assertTrue("batch mutex released by real cancellation", ExportEvent.Busy !in nextEvents)
      val summary = nextEvents.filterIsInstance<ExportEvent.Completed>().single()
      assertEquals(1, summary.succeeded)
      assertEquals(0, summary.failed)
      assertEquals(0, summary.historyFailures)
      assertEquals(2, history.latest().size)
      assertTrue(history.latest().all { it.succeeded })
      assertTrue("previous complete page remains after next batch", documents.live.containsKey(first.reference.uriString))
      assertEquals(2, documents.live.size)
      assertEquals(listOf(second.reference.uriString), documents.deletedUris)
    } finally {
      running?.cancelAndJoin()
      database.close()
    }
  }

  private fun snapshot(id: String, text: String) = ExportSnapshot(
    NoteDocument(NoteModel(id, "test-folder", id, createdAtMillis = 1L, updatedAtMillis = 2L),
      listOf(BlockModel("body-$id", id, 0, BlockType.TEXT, text = text)), emptyList()), "测试目录",
  )

  private data class StoredDocument(val reference: MediaStorePngDocumentRef, val output: ChunkOutput,
    var published: Boolean = false, var deleted: Boolean = false)

  /** Only the platform document destination is replaced; renderer/Writer/store/service/history are real. */
  private class DeterministicDocuments : MediaStorePngDocumentAccess {
    val created = mutableListOf<StoredDocument>()
    val live = linkedMapOf<String, StoredDocument>()
    val publishedUris = mutableListOf<String>()
    val deletedUris = mutableListOf<String>()
    var onSecondCompleteIdat: () -> Unit = {}
    private var cancellationTriggered = false

    override fun listNames(): Set<String> = live.values.map { it.reference.displayName }.toSet()
    override fun createPending(displayName: String): MediaStorePngDocumentRef {
      val number = created.size + 1
      val reference = MediaStorePngDocumentRef("content://test-destination/images/$number", "实际-$number-$displayName")
      val output = ChunkOutput { type, length ->
        // Deflater can emit a two-byte zlib header before any compressed pixel data.
        if (number == 2 && type == "IDAT" && length > 2 && !cancellationTriggered) {
          cancellationTriggered = true
          onSecondCompleteIdat()
        }
      }
      val document = StoredDocument(reference, output)
      created += document
      live[reference.uriString] = document
      return reference
    }
    override fun openOutputStream(document: MediaStorePngDocumentRef): OutputStream = live.getValue(document.uriString).output
    override fun displayName(document: MediaStorePngDocumentRef): String = document.displayName
    override fun publish(document: MediaStorePngDocumentRef): Boolean {
      val stored = live.getValue(document.uriString)
      check(stored.output.closed) { "must close output before publishing" }
      stored.published = true
      publishedUris += document.uriString
      return true
    }
    override fun delete(document: MediaStorePngDocumentRef): Boolean {
      val stored = live.remove(document.uriString) ?: return false
      check(stored.output.closed) { "cancel must close partial output before deletion" }
      stored.deleted = true
      deletedUris += document.uriString
      return true
    }
  }

  private class ChunkOutput(private val onChunk: (String, Int) -> Unit) : OutputStream() {
    val bytes = ByteArrayOutputStream()
    var closed = false
    private var nextChunk = 8
    override fun write(value: Int) { check(!closed); bytes.write(value); observeChunks() }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { check(!closed); bytes.write(buffer, offset, length); observeChunks() }
    override fun close() { closed = true }

    private fun observeChunks() {
      val snapshot = bytes.toByteArray()
      while (snapshot.size >= nextChunk + 12) {
        val length = readInt(snapshot, nextChunk)
        check(length >= 0)
        val end = nextChunk + 12 + length
        if (end > snapshot.size) return
        val type = snapshot.copyOfRange(nextChunk + 4, nextChunk + 8).toString(Charsets.US_ASCII)
        val crc = CRC32().apply { update(snapshot, nextChunk + 4, length + 4) }.value.toInt()
        check(crc == readInt(snapshot, nextChunk + 8 + length)) { "actual IDAT CRC must arrive before triggering cancellation" }
        nextChunk = end
        onChunk(type, length)
      }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
      ((bytes[offset].toInt() and 255) shl 24) or ((bytes[offset + 1].toInt() and 255) shl 16) or
        ((bytes[offset + 2].toInt() and 255) shl 8) or (bytes[offset + 3].toInt() and 255)
  }

  private data class ParsedPng(val width: Int, val height: Int, val types: List<String>, val idat: ByteArray)

  private fun parsePng(bytes: ByteArray): ParsedPng = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
    assertEquals(listOf(137, 80, 78, 71, 13, 10, 26, 10), (0 until 8).map { input.readUnsignedByte() })
    var width = 0
    var height = 0
    val types = mutableListOf<String>()
    val idat = ByteArrayOutputStream()
    while (input.available() > 0) {
      val length = input.readInt()
      val typeBytes = ByteArray(4).also(input::readFully)
      val type = typeBytes.toString(Charsets.US_ASCII)
      val payload = ByteArray(length).also(input::readFully)
      val crc = input.readInt()
      assertEquals("actual $type chunk CRC", CRC32().apply { update(typeBytes); update(payload) }.value.toInt(), crc)
      types += type
      if (type == "IHDR") DataInputStream(ByteArrayInputStream(payload)).use { header -> width = header.readInt(); height = header.readInt() }
      if (type == "IDAT") idat.write(payload)
    }
    ParsedPng(width, height, types, idat.toByteArray())
  }

  private fun inflateCount(compressed: ByteArray): Pair<Long, Boolean> {
    val inflater = Inflater()
    return try {
      inflater.setInput(compressed)
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (!inflater.finished() && !inflater.needsInput()) {
        val count = inflater.inflate(buffer)
        total += count
        check(count > 0 || inflater.finished() || inflater.needsInput()) { "zlib made no progress" }
      }
      total to inflater.finished()
    } finally { inflater.end() }
  }
}

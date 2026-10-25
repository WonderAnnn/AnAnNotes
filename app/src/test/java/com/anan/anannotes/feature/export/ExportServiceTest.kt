package com.anan.anannotes.feature.export

import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.TodoModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportServiceTest {
  @Test
  fun a4AndSingleLongPngUseRasterRendererAndPicturesWithCorrectFileNames() = runTest {
    listOf(ExportFormat.PNG_PAGED_600_DPI, ExportFormat.PNG_LONG_IMAGE).forEach { format ->
      val saf = RecordingExportFileStore()
      val pictures = RecordingSocialPngExportFileStore()
      val exporter = service(mapOf("note-1" to snapshot(document(text = "正文"), "学习")), saf,
        rasterPngRenderer = SocialPngExportRenderer { _, actualFormat, _, writePage ->
          assertEquals(format, actualFormat)
          writePage(1) { it.write("完整图片".toByteArray()) }
          if (format == ExportFormat.PNG_PAGED_600_DPI) writePage(2) { it.write("续页".toByteArray()) }
        }, socialPngFiles = pictures)
      val result = exporter.export(request("note-1", format = format, target = OutputTarget.MediaStorePictures)).toList()
      assertTrue(saf.files.isEmpty())
      val names = if (format == ExportFormat.PNG_LONG_IMAGE) listOf("学习_一条笔记_2026-09-28_长图.png") else
        listOf("学习_一条笔记_2026-09-28_A4_001.png", "学习_一条笔记_2026-09-28_A4_002.png")
      assertEquals(names, pictures.files.map { it.name })
      assertEquals(names.size, (result.last() as ExportEvent.Completed).succeeded)
    }
  }

  @Test
  fun longRendererCannotSilentlyPublishSecondFile() = runTest {
    val pictures = RecordingSocialPngExportFileStore()
    val exporter = service(mapOf("note-1" to snapshot(document(text = "正文"), "学习")), RecordingExportFileStore(),
      rasterPngRenderer = SocialPngExportRenderer { _, _, _, page ->
        page(1) { it.write(1) }; page(2) { it.write(2) }
      }, socialPngFiles = pictures)
    val events = exporter.export(request("note-1", format = ExportFormat.PNG_LONG_IMAGE,
      target = OutputTarget.MediaStorePictures)).toList()
    assertEquals(1, pictures.files.size)
    assertEquals(1, (events.last() as ExportEvent.Completed).succeeded)
    assertEquals(1, (events.last() as ExportEvent.Completed).failed)
  }

  @Test
  fun historyRecordsActualSuccessfulReceiptAndFailureWithoutNoteBody() = runTest {
    val history = RecordingHistory()
    val exporter = service(mapOf("note-1" to snapshot(document(text = "正文不能写入历史"), "工作")),
      RecordingExportFileStore(actualName = { "$it (2)" }), history = history)
    val events = exporter.export(request("note-1", "missing", format = ExportFormat.TXT)).toList()
    assertEquals(listOf(ExportFormat.TXT), history.formats)
    assertEquals(events.filterIsInstance<ExportEvent.ItemCompleted>(), history.success)
    assertEquals(events.filterIsInstance<ExportEvent.ItemFailed>(), history.failures)
    assertTrue(history.success.single().fileName.endsWith("(2)"))
    assertTrue(history.failedNames.none { it.contains("正文") })
  }

  @Test
  fun historyFailureDoesNotTurnPublishedFileIntoFailedExport() = runTest {
    val history = object : ExportHistoryRecorder {
      override suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted) { error("历史不可写") }
      override suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String) { error("历史不可写") }
    }
    val exporter = service(mapOf("note-1" to snapshot(document(text = "正文"), "工作")), RecordingExportFileStore(), history = history)
    val done = exporter.export(request("note-1", format = ExportFormat.TXT)).toList().last() as ExportEvent.Completed
    assertEquals(1, done.succeeded); assertEquals(0, done.failed)
    assertEquals(1, done.historyFailures)
  }

  @Test
  fun pngHistoryIncludesEachPageBeforeLaterPageFailure() = runTest {
    val history = RecordingHistory()
    val exporter = service(mapOf("note-1" to snapshot(document(text = "正文"), "学习")), RecordingExportFileStore(),
      rasterPngRenderer = SocialPngExportRenderer { _, _, _, page ->
        page(1) { it.write(1) }
        throw ExportOperationException(ExportErrorCode.INSUFFICIENT_STORAGE, "空间不足")
      }, socialPngFiles = RecordingSocialPngExportFileStore(), history = history)
    val done = exporter.export(request("note-1", format = ExportFormat.PNG_PAGED_600_DPI,
      target = OutputTarget.MediaStorePictures)).toList().last() as ExportEvent.Completed
    assertEquals(1, history.success.size); assertEquals(1, history.failures.size)
    assertEquals(1, done.succeeded); assertEquals(1, done.failed)
  }

  @Test
  fun cancellationDoesNotRecordFalseFailureAndReleasesTheBatchLock() = runTest {
    val history = RecordingHistory()
    var attempts = 0
    val pngStore = SocialPngExportFileStore { name, content ->
      attempts++
      if (attempts == 1) throw CancellationException("用户取消")
      content(ByteArrayOutputStream())
      ExportFileReceipt(name, "content://media/$attempts")
    }
    val exporter = service(mapOf("note-1" to snapshot(document(text = "正文"), "学习")), RecordingExportFileStore(),
      rasterPngRenderer = SocialPngExportRenderer { _, _, _, page -> page(1) { it.write(1) } },
      socialPngFiles = pngStore, history = history)
    val request = request("note-1", format = ExportFormat.PNG_PAGED_600_DPI, target = OutputTarget.MediaStorePictures)
    try { exporter.export(request).toList(); error("取消必须传播") }
    catch (_: CancellationException) { /* expected */ }
    assertTrue(history.failures.isEmpty()); assertTrue(history.success.isEmpty())
    val done = exporter.export(request).toList().last() as ExportEvent.Completed
    assertEquals(1, done.succeeded); assertEquals(0, done.failed)
  }

  @Test
  fun txtExportWritesUtf8ContentAndFolderTitleDateFileName() = runTest {
    val output = RecordingExportFileStore()
    val service = service(
      documents = mapOf("note-1" to snapshot(document(text = "记下一件事"), folderName = "生活")),
      output = output,
    )

    val events = service.export(request("note-1", format = ExportFormat.TXT, includeAttribution = true)).toList()

    assertEquals("生活_一条笔记_2026-09-28.txt", output.files.single().name)
    assertEquals(
      "生活_一条笔记_2026-09-28.txt",
      events.filterIsInstance<ExportEvent.ItemCompleted>().single().fileName,
    )
    assertEquals("一条笔记\n\n记下一件事\n\n- [x] 待办项", output.files.single().bytes.toString(Charsets.UTF_8))
    assertEquals(1, (events.last() as ExportEvent.Completed).succeeded)
    assertEquals(0, (events.last() as ExportEvent.Completed).failed)
  }

  @Test
  fun pdfExportUsesVectorRendererAndPdfDestinationMetadata() = runTest {
    val output = RecordingExportFileStore()
    var renderedNoteId: String? = null
    val service = service(
      documents = mapOf("note-1" to snapshot(document(text = "矢量正文"), folderName = "工作")),
      output = output,
      pdfRenderer = PdfExportRenderer { document, _, destination ->
        renderedNoteId = document.document.note.id
        destination.write("%PDF-test".toByteArray(Charsets.US_ASCII))
      },
    )

    val events = service.export(request("note-1", format = ExportFormat.PDF)).toList()

    assertEquals("note-1", renderedNoteId)
    assertEquals("工作_一条笔记_2026-09-28.pdf", output.files.single().name)
    assertEquals(OrdinaryExportService.MIME_PDF, output.files.single().mimeType)
    assertEquals("%PDF-test", output.files.single().bytes.toString(Charsets.US_ASCII))
    assertEquals(1, (events.last() as ExportEvent.Completed).succeeded)
  }

  @Test
  fun attributionChoiceIsForwardedToPdfRenderer() = runTest {
    var includeAttribution = false
    val service = service(
      documents = mapOf("note-1" to snapshot(document(text = "署名测试正文"), folderName = "工作")),
      output = RecordingExportFileStore(),
      pdfRenderer = PdfExportRenderer { exportedSnapshot, _, destination ->
        includeAttribution = exportedSnapshot.includeAttribution
        destination.write("%PDF-test".toByteArray(Charsets.US_ASCII))
      },
    )

    service.export(request("note-1", format = ExportFormat.PDF, includeAttribution = true)).toList()

    assertTrue(includeAttribution)
  }

  @Test
  fun socialPngExportWritesEveryRenderedPageToMediaStoreAndReportsEachFile() = runTest {
    val saf = RecordingExportFileStore()
    val pictures = RecordingSocialPngExportFileStore()
    val service = service(
      documents = mapOf("note-1" to snapshot(document(text = "完整保留的长笔记"), folderName = "学习")),
      output = saf,
      socialPngFiles = pictures,
      socialPngRenderer = SocialPngExportRenderer { _, format, _, writePage ->
        assertEquals(ExportFormat.PNG_SOCIAL_SQUARE, format)
        writePage(1) { it.write("第一张".toByteArray()) }
        writePage(2) { it.write("第二张".toByteArray()) }
      },
    )

    val events = service.export(
      request("note-1", format = ExportFormat.PNG_SOCIAL_SQUARE, target = OutputTarget.MediaStorePictures),
    ).toList()

    assertTrue("社交 PNG 不应经过 SAF", saf.files.isEmpty())
    assertEquals(
      listOf(
        "学习_一条笔记_2026-09-28_方图_01.png",
        "学习_一条笔记_2026-09-28_方图_02.png",
      ),
      pictures.files.map { it.name },
    )
    assertEquals(listOf("第一张", "第二张"), pictures.files.map { it.bytes.toString(Charsets.UTF_8) })
    assertEquals(listOf("图片/安安简记/导出", "图片/安安简记/导出"),
      events.filterIsInstance<ExportEvent.ItemCompleted>().map { it.destinationName })
    val completed = events.filterIsInstance<ExportEvent.Completed>().single()
    assertEquals(2, completed.succeeded)
    assertEquals(0, completed.failed)
    assertEquals(2, completed.completedFiles.size)
  }

  @Test
  fun socialPngRequiresThePicturesDestination() = runTest {
    val pictures = RecordingSocialPngExportFileStore()
    val service = service(
      documents = mapOf("note-1" to snapshot(document(text = "正文"), folderName = "学习")),
      output = RecordingExportFileStore(),
      socialPngFiles = pictures,
      socialPngRenderer = SocialPngExportRenderer { _, _, _, _ -> error("目标无效时不能开始渲染") },
    )

    val events = service.export(request("note-1", format = ExportFormat.PNG_SOCIAL_PORTRAIT)).toList()

    assertEquals(ExportErrorCode.DESTINATION_UNAVAILABLE,
      events.filterIsInstance<ExportEvent.ItemFailed>().single().code)
    assertTrue(pictures.files.isEmpty())
  }

  @Test
  fun completedEventUsesTheNameActuallyCreatedByTheDestination() = runTest {
    val output = RecordingExportFileStore(actualName = { "$it (2)" }, destinationName = "Selected folder")
    val service = service(
      documents = mapOf("note-1" to snapshot(document(text = "重名文件"), folderName = "生活")),
      output = output,
    )

    val completed = service.export(request("note-1", format = ExportFormat.TXT))
      .toList()
      .filterIsInstance<ExportEvent.ItemCompleted>()
      .single()

    assertEquals("生活_一条笔记_2026-09-28.txt (2)", completed.fileName)
    assertEquals("Selected folder", completed.destinationName)
  }

  @Test
  @OptIn(ExperimentalCoroutinesApi::class)
  fun concurrentRequestDoesNotInterleaveWithTheActiveExportBatch() = runTest {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val output = BlockingExportFileStore(started, release)
    val service = service(
      documents = mapOf(
        "note-1" to snapshot(document(text = "第一批"), folderName = "生活"),
        "note-2" to snapshot(document(text = "第二批"), folderName = "生活"),
      ),
      output = output,
    )

    val firstBatch = async { service.export(request("note-1", format = ExportFormat.TXT)).toList() }
    started.await()
    val secondBatch = async { service.export(request("note-2", format = ExportFormat.TXT)).toList() }
    runCurrent()
    assertEquals(1, output.writeCount)
    release.complete(Unit)
    val firstEvents = firstBatch.await()
    val secondEvents = secondBatch.await()

    assertEquals(listOf(ExportEvent.Busy), secondEvents)
    assertEquals(listOf("note-1"), firstEvents.filterIsInstance<ExportEvent.ItemCompleted>().map { it.noteId })
    assertEquals(1, output.writeCount)
  }

  @Test
  fun markdownZipContainsRelativeImageReferenceAndOriginalImageBytes() = runTest {
    val imageBytes = byteArrayOf(2, 4, 8, 16)
    val document = document(
      blocks = listOf(
        BlockModel(
          id = "image-1",
          noteId = "note-1",
          position = 0,
          type = BlockType.IMAGE,
          assetPath = "notes/assets/original.png",
        ),
      ),
    )
    val output = RecordingExportFileStore()
    val service = service(
      documents = mapOf("note-1" to snapshot(document, folderName = "项目")),
      output = output,
      imageBytes = imageBytes,
    )

    service.export(request("note-1", format = ExportFormat.MARKDOWN_ZIP)).toList()

    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(output.files.single().bytes.inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        entries[entry.name] = zip.readBytes()
        zip.closeEntry()
      }
    }
    assertTrue(output.files.single().name.endsWith(".zip"))
    assertTrue(entries.keys.any { it.endsWith(".md") })
    assertTrue(entries.getValue(entries.keys.first { it.endsWith(".md") }).toString(Charsets.UTF_8)
      .contains("![图片](images/image-001.png)"))
    assertArrayEquals(imageBytes, entries["images/image-001.png"])
  }

  @Test
  fun failedNoteDoesNotStopNextExportAndDuplicateIdsAreExportedOnce() = runTest {
    val output = RecordingExportFileStore()
    val service = service(
      documents = mapOf("note-2" to snapshot(document(text = "继续导出"), folderName = "学习")),
      output = output,
    )

    val events = service.export(request("missing", "note-2", "note-2", format = ExportFormat.TXT)).toList()

    assertEquals(2, events.filterIsInstance<ExportEvent.ItemFailed>().size +
      events.filterIsInstance<ExportEvent.ItemCompleted>().size)
    assertEquals(ExportErrorCode.NOTE_UNAVAILABLE, events.filterIsInstance<ExportEvent.ItemFailed>().single().code)
    assertEquals(1, events.filterIsInstance<ExportEvent.ItemCompleted>().size)
    assertEquals(1, output.files.size)
    assertEquals(1, (events.last() as ExportEvent.Completed).succeeded)
    assertEquals(1, (events.last() as ExportEvent.Completed).failed)
  }

  @Test
  fun successFailureSuccessBatchKeepsBothCompletedFiles() = runTest {
    val output = FailSecondExportFileStore()
    val service = service(
      documents = listOf("note-1", "note-2", "note-3").associateWith {
        snapshot(document(text = it), folderName = "学习")
      },
      output = output,
    )

    val events = service.export(request("note-1", "note-2", "note-3", format = ExportFormat.TXT)).toList()

    assertEquals(listOf("note-1", "note-3"), events.filterIsInstance<ExportEvent.ItemCompleted>().map { it.noteId })
    assertEquals("note-2", events.filterIsInstance<ExportEvent.ItemFailed>().single().noteId)
    assertEquals(2, output.files.size)
    assertEquals(2, (events.last() as ExportEvent.Completed).succeeded)
    assertEquals(1, (events.last() as ExportEvent.Completed).failed)
  }

  private fun service(
    documents: Map<String, ExportSnapshot>,
    output: ExportFileStore,
    imageBytes: ByteArray = byteArrayOf(),
    pdfRenderer: PdfExportRenderer? = null,
    socialPngRenderer: SocialPngExportRenderer? = null,
    socialPngFiles: SocialPngExportFileStore? = null,
    rasterPngRenderer: SocialPngExportRenderer? = null,
    history: ExportHistoryRecorder? = null,
  ) = OrdinaryExportService(
    snapshots = ExportSnapshotProvider { noteId -> documents[noteId] },
    files = output,
    openImage = { block ->
      if (block.assetPath == "notes/assets/original.png") ByteArrayInputStream(imageBytes) else null
    },
    pdfRenderer = pdfRenderer,
    socialPngRenderer = socialPngRenderer,
    socialPngFiles = socialPngFiles,
    rasterPngRenderer = rasterPngRenderer,
    history = history,
    today = { LocalDate.of(2026, 9, 28) },
  )

  private fun request(
    vararg noteIds: String,
    format: ExportFormat,
    target: OutputTarget = OutputTarget.DocumentTree("content://test/export-root"),
    includeAttribution: Boolean = false,
  ) = ExportRequest(
    noteIds = noteIds.toList(),
    format = format,
    target = target,
    includeAttribution = includeAttribution,
  )

  private fun snapshot(document: NoteDocument, folderName: String) = ExportSnapshot(document, folderName)

  private fun document(
    text: String? = null,
    blocks: List<BlockModel> = listOfNotNull(
      text?.let {
        BlockModel(
          id = "text-1",
          noteId = "note-1",
          position = 0,
          type = BlockType.TEXT,
          text = it,
        )
      },
      text?.let {
        BlockModel(
          id = "todo-1",
          noteId = "note-1",
          position = 1,
          type = BlockType.TODO,
          text = "待办项",
        )
      },
    ),
  ) = NoteDocument(
    note = NoteModel(
      id = "note-1",
      folderId = "folder-1",
      title = "一条笔记",
      createdAtMillis = 1L,
      updatedAtMillis = 2L,
    ),
    blocks = blocks,
    todos = listOf(
      TodoModel(
        id = "todo-1",
        noteId = "note-1",
        blockId = "todo-1",
        text = "待办项",
        isCompleted = true,
        updatedAtMillis = 2L,
      ),
    ).takeIf { blocks.any { block -> block.type == BlockType.TODO } }.orEmpty(),
  )

  private class RecordingExportFileStore(
    private val actualName: (String) -> String = { it },
    private val destinationName: String? = null,
  ) : ExportFileStore {
    data class WrittenFile(val name: String, val mimeType: String, val bytes: ByteArray)

    val files = mutableListOf<WrittenFile>()

    override suspend fun write(
      target: OutputTarget.DocumentTree,
      requestedName: String,
      mimeType: String,
      content: (OutputStream) -> Unit,
    ): ExportFileReceipt {
      val output = ByteArrayOutputStream()
      content(output)
      files += WrittenFile(requestedName, mimeType, output.toByteArray())
      val createdName = actualName(requestedName)
      return ExportFileReceipt(
        fileName = createdName,
        location = "${target.uriString}/$createdName",
        destinationName = destinationName,
      )
    }
  }

  private class RecordingHistory : ExportHistoryRecorder {
    val success = mutableListOf<ExportEvent.ItemCompleted>()
    val failures = mutableListOf<ExportEvent.ItemFailed>()
    val formats = mutableListOf<ExportFormat>()
    val failedNames = mutableListOf<String>()
    override suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted) { formats += format; success += item }
    override suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String) { failures += item; failedNames += fileName }
  }

  private class RecordingSocialPngExportFileStore : SocialPngExportFileStore {
    data class WrittenFile(val name: String, val bytes: ByteArray)

    val files = mutableListOf<WrittenFile>()

    override suspend fun writePng(
      requestedName: String,
      content: (OutputStream) -> Unit,
    ): ExportFileReceipt {
      val output = ByteArrayOutputStream()
      content(output)
      files += WrittenFile(requestedName, output.toByteArray())
      return ExportFileReceipt(
        fileName = requestedName,
        location = "content://media/external/images/media/${files.size}",
        destinationName = "图片/安安简记/导出",
      )
    }
  }

  private class BlockingExportFileStore(
    private val started: CompletableDeferred<Unit>,
    private val release: CompletableDeferred<Unit>,
  ) : ExportFileStore {
    var writeCount = 0
      private set

    override suspend fun write(
      target: OutputTarget.DocumentTree,
      requestedName: String,
      mimeType: String,
      content: (OutputStream) -> Unit,
    ): ExportFileReceipt {
      writeCount++
      started.complete(Unit)
      release.await()
      content(ByteArrayOutputStream())
      return ExportFileReceipt(requestedName, "${target.uriString}/$requestedName")
    }
  }

  private class FailSecondExportFileStore : ExportFileStore {
    val files = mutableListOf<String>()
    private var attempts = 0

    override suspend fun write(
      target: OutputTarget.DocumentTree,
      requestedName: String,
      mimeType: String,
      content: (OutputStream) -> Unit,
    ): ExportFileReceipt {
      attempts++
      if (attempts == 2) throw ExportOperationException(ExportErrorCode.WRITE_FAILED, "模拟单项写入失败")
      content(ByteArrayOutputStream())
      files += requestedName
      return ExportFileReceipt(requestedName, "${target.uriString}/$requestedName")
    }
  }
}

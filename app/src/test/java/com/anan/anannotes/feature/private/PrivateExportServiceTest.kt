package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.TodoModel
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.feature.export.ExportFileReceipt
import com.anan.anannotes.feature.export.ExportFileStore
import com.anan.anannotes.feature.export.ExportSnapshot
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.KeyDeriver
import com.anan.anannotes.security.PrivateCrypto
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PrivateExportServiceTest {
  @Test
  fun privatePdfAttributionChoiceSurvivesPasswordGrantAndReachesRenderer() = runBlocking {
    val access = PrivateAccess(ByteArray(32) { 21 })
    val request = PrivateExportRequest(
      noteId = PrivateNoteId("private-pdf-note"),
      format = ExportFormat.PDF,
      target = OutputTarget.DocumentTree("content://private/pdf"),
      includeAttribution = true,
    )
    val grant = PrivateExportGrant.issue(access, request.scope(), issuedAtMillis = 1_000L)
    var rendererReceivedAttribution = false
    val service = PrivateExportService(
      data = object : PrivateExportDataSource {
        override suspend fun read(access: PrivateAccess, noteId: PrivateNoteId) = privateSnapshot(noteId)
        override fun openImage(access: PrivateAccess, block: BlockModel): InputStream? = null
      },
      files = RecordingPrivateFileStore(),
      pngFiles = null,
      history = PrivateExportHistorySink { _, _ -> },
      pdfRenderer = com.anan.anannotes.feature.export.PdfExportRenderer { snapshot, _, output ->
        rendererReceivedAttribution = snapshot.includeAttribution
        output.write("%PDF-private".toByteArray(Charsets.US_ASCII))
      },
      nowMillis = { 1_001L },
    )

    val events = service.export(access, grant, request).toList()

    assertTrue(rendererReceivedAttribution)
    assertTrue(events.any { it is PrivateExportEvent.ItemCompleted })
    access.revoke()
  }

  @Test
  fun encryptedRepositorySnapshotCanExportThroughItsPrivateOnlyDataSource() = runBlocking {
    val root = Files.createTempDirectory("anan-private-export-integration-").toFile()
    try {
      val store = PrivateStore(root, testCrypto())
      val repository = PrivateRepository(store, nowMillis = { 2_000L })
      val access = repository.setup("private-password".toCharArray(), null)
      val saved = repository.save(
        access,
        PrivateEditSnapshot(
          draftId = "private-export-draft",
          noteId = null,
          folderId = "private-uncategorized",
          title = "隔离导出标题",
          titleManuallySet = true,
          blocks = listOf(NoteBlockDraft(id = "private-export-text", type = BlockType.TEXT, text = "只从私密库读取的正文")),
          skinId = "deep-sea",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = 1_500L,
        ),
      ) as PrivateSaveResult.Saved
      val scope = PrivateExportScope(saved.noteId, ExportFormat.TXT, "content://private/export-folder")
      val grant = repository.authorizeExport(access, "private-password".toCharArray(), scope, nowMillis = 1_000L)!!
      val files = RecordingPrivateFileStore()
      val service = PrivateExportService(
        data = PrivateRepositoryExportDataSource(repository, store),
        files = files,
        pngFiles = null,
        history = PrivateExportHistorySink(repository::recordPrivateExport),
        today = { LocalDate.of(2026, 10, 1) },
        nowMillis = { 2_000L },
      )

      val events = service.export(
        access,
        grant,
        PrivateExportRequest(saved.noteId, ExportFormat.TXT, OutputTarget.DocumentTree(scope.destination)),
      ).toList()

      assertTrue(files.lastBytes!!.toString(Charsets.UTF_8).contains("隔离导出标题"))
      assertTrue(files.lastBytes!!.toString(Charsets.UTF_8).contains("只从私密库读取的正文"))
      assertEquals(1, repository.listPrivateExportHistory(access).size)
      assertFalse(java.io.File(root, "anan-notes.db").exists())
      assertTrue(events.any { it is PrivateExportEvent.ItemCompleted })
      repository.lock()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun exactAuthorizedPrivateRequestWritesDocumentAndRecordsOnlyPrivateHistory() = runBlocking {
    val access = PrivateAccess(ByteArray(32) { 11 })
    val scope = PrivateExportScope(PrivateNoteId("vault-note-1"), ExportFormat.TXT, "content://user/tree")
    val grant = PrivateExportGrant.issue(access, scope, issuedAtMillis = 1_000L)
    val files = RecordingPrivateFileStore()
    val history = mutableListOf<PrivateExportHistoryRecord>()
    val snapshot = privateSnapshot(scope.noteId)
    var imageReads = 0
    val service = PrivateExportService(
      data = object : PrivateExportDataSource {
        override suspend fun read(access: PrivateAccess, noteId: PrivateNoteId) = snapshot
        override fun openImage(access: PrivateAccess, block: BlockModel): InputStream? {
          imageReads++
          return null
        }
      },
      files = files,
      pngFiles = null,
      history = PrivateExportHistorySink { _, record -> history += record },
      today = { LocalDate.of(2026, 10, 1) },
      nowMillis = { 2_000L },
      newId = { "history-id" },
    )

    val events = service.export(
      access,
      grant,
      PrivateExportRequest(scope.noteId, scope.format, OutputTarget.DocumentTree(scope.destination), includeAttribution = true),
    ).toList()

    assertTrue(files.lastBytes!!.toString(Charsets.UTF_8).contains("私密正文"))
    assertTrue(files.lastBytes!!.toString(Charsets.UTF_8).contains("私密标题"))
    assertFalse(files.lastBytes!!.toString(Charsets.UTF_8).contains("来自安安简记"))
    assertEquals(0, imageReads)
    assertEquals(1, history.size)
    assertEquals("history-id", history.single().id)
    assertEquals("vault-note-1", history.single().noteId.value)
    assertEquals("content://user/document/1", history.single().destinationUri)
    assertEquals(1, events.filterIsInstance<PrivateExportEvent.ItemCompleted>().size)
    assertEquals("vault-note-1", events.filterIsInstance<PrivateExportEvent.ItemCompleted>().single().noteId.value)
    assertTrue(events.last() is PrivateExportEvent.Completed)

    access.revoke()
  }

  @Test
  fun mismatchedPrivateExportRequestIsRejectedBeforeReadingOrCreatingAnyFile() = runBlocking {
    val access = PrivateAccess(ByteArray(32) { 12 })
    val authorizedScope = PrivateExportScope(PrivateNoteId("authorized-note"), ExportFormat.TXT, "content://user/tree")
    val grant = PrivateExportGrant.issue(access, authorizedScope, issuedAtMillis = 1_000L)
    val files = RecordingPrivateFileStore()
    var reads = 0
    val service = PrivateExportService(
      data = object : PrivateExportDataSource {
        override suspend fun read(access: PrivateAccess, noteId: PrivateNoteId): ExportSnapshot? {
          reads++
          return null
        }
        override fun openImage(access: PrivateAccess, block: BlockModel): InputStream? = null
      },
      files = files,
      pngFiles = null,
      history = PrivateExportHistorySink { _, _ -> error("No history should be written") },
      nowMillis = { 1_001L },
    )

    val events = service.export(
      access,
      grant,
      PrivateExportRequest(PrivateNoteId("another-note"), ExportFormat.TXT, OutputTarget.DocumentTree("content://user/tree")),
    ).toList()

    assertEquals(0, reads)
    assertEquals(0, files.writeCount)
    assertTrue(events.any { it is PrivateExportEvent.AuthorizationRequired })
    assertFalse(events.any { it is PrivateExportEvent.ItemCompleted })
    access.revoke()
  }

  @Test
  fun cancellationWhileWritingPrivateHistoryPropagatesToCaller() = runBlocking {
    val access = PrivateAccess(ByteArray(32) { 13 })
    val scope = PrivateExportScope(PrivateNoteId("cancelled-history-note"), ExportFormat.TXT, "content://user/tree")
    val grant = PrivateExportGrant.issue(access, scope, issuedAtMillis = 1_000L)
    val service = PrivateExportService(
      data = object : PrivateExportDataSource {
        override suspend fun read(access: PrivateAccess, noteId: PrivateNoteId) = privateSnapshot(noteId)
        override fun openImage(access: PrivateAccess, block: BlockModel): InputStream? = null
      },
      files = RecordingPrivateFileStore(),
      pngFiles = null,
      history = PrivateExportHistorySink { _, _ -> throw CancellationException("scope cancelled") },
      nowMillis = { 1_001L },
    )

    val thrown = runCatching {
      service.export(
        access,
        grant,
        PrivateExportRequest(scope.noteId, scope.format, OutputTarget.DocumentTree(scope.destination)),
      ).toList()
    }.exceptionOrNull()

    assertTrue(thrown is CancellationException)
    access.revoke()
  }

  private fun privateSnapshot(id: PrivateNoteId) = ExportSnapshot(
    document = NoteDocument(
      note = NoteModel(
        id = id.value,
        folderId = "private-uncategorized",
        title = "私密标题",
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
        skinId = "deep-sea",
      ),
      blocks = listOf(BlockModel("text-1", id.value, 0, BlockType.TEXT, "私密正文")),
      todos = emptyList<TodoModel>(),
    ),
    folderName = "私密笔记",
    displaySettings = DisplaySettings(),
  )

  private class RecordingPrivateFileStore : ExportFileStore {
    var writeCount = 0
    var lastBytes: ByteArray? = null

    override suspend fun write(
      target: OutputTarget.DocumentTree,
      requestedName: String,
      mimeType: String,
      content: (OutputStream) -> Unit,
    ): ExportFileReceipt {
      writeCount++
      val output = ByteArrayOutputStream()
      content(output)
      lastBytes = output.toByteArray()
      return ExportFileReceipt(requestedName, "content://user/document/1", "测试目录")
    }
  }

  private fun testCrypto(): PrivateCrypto {
    val kdf = KeyDeriver { credential, salt, _ ->
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(salt.copyBytes(), "HmacSHA256"))
      val bytes = String(credential).toByteArray(Charsets.UTF_8)
      try { mac.doFinal(bytes) } finally { bytes.fill(0) }
    }
    return PrivateCrypto(SecureRandom(), kdf)
  }
}

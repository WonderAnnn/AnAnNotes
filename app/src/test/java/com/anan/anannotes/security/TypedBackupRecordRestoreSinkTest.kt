package com.anan.anannotes.security

import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.FolderKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedBackupRecordRestoreSinkTest {
  @Test
  fun decodesRecordsAcrossChunkBoundariesAndCommitsOnlyAfterSemanticValidation() {
    val assetBytes = byteArrayOf(9, 8, 7, 6)
    val records = backupStream(assetBytes)
    val store = RecordingStore()
    val consumer = BackupRecordPayloadConsumer(TypedBackupRecordRestoreSink(store))

    feed(consumer, records)
    consumer.commit()

    assertTrue(store.committed)
    assertFalse(store.aborted)
    assertEquals(4, store.records.size)
    assertEquals(assetBytes.toList(), store.assetBytes.toByteArray().toList())
    assertEquals(1L, store.recordSetSummary?.ordinaryNotes)
    assertEquals(1L, store.recordSetSummary?.ordinaryAssets)
  }

  @Test
  fun danglingReferenceAbortsAndNeverPublishesStagedRecords() {
    val raw = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(raw)
    writePayload(writer, BackupRecordDomain.ORDINARY, BackupRecordPayload.Note(
        "note-1", "missing-folder", "title", false, 1L, 1L, "minimal-white",
        null, null, false, null, 1L, null,
      ))
    writer.finish()
    val store = RecordingStore()
    val consumer = BackupRecordPayloadConsumer(TypedBackupRecordRestoreSink(store))

    feed(consumer, raw.toByteArray())
    assertThrows(BackupRecordPayloadException::class.java) { consumer.commit() }
    consumer.abort()

    assertFalse(store.committed)
    assertTrue(store.aborted)
  }

  @Test
  fun badAssetDigestNeverReachesRecordStoreAndAborts() {
    val bytes = byteArrayOf(1, 2, 3)
    val raw = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(raw)
    writePayload(writer, BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("folder-1", "资料", FolderKind.USER, 1L, 0))
    writePayload(writer, BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Note("note-1", "folder-1", "title", false, 1L, 1L, "minimal-white", null, null, false, null, 1L, null))
    val block = BackupRecordPayload.Block(
      "image-1", "note-1", 0, BlockType.IMAGE, null, "asset-1", 100f,
      BlockAlignment.CENTER, 1f, null, null,
    )
    writePayload(writer, BackupRecordDomain.ORDINARY, block)
    val badAsset = BackupRecordPayload.Asset("asset-1", "image/png", bytes.size.toLong(), ByteArray(32))
    val prefix = BackupRecordPayloadCodec.encodeAssetPrefix(BackupRecordDomain.ORDINARY, badAsset)
    writer.writeRecord(
      BackupRecordDomain.ORDINARY,
      BackupRecordKind.ASSET,
      BackupRecordPayloadCodec.SCHEMA_VERSION,
      prefix.size.toLong() + bytes.size,
      ByteArrayInputStream(prefix + bytes),
    )
    writer.finish()

    val store = RecordingStore()
    val consumer = BackupRecordPayloadConsumer(TypedBackupRecordRestoreSink(store))
    assertThrows(Exception::class.java) { feed(consumer, raw.toByteArray()) }
    consumer.abort()

    assertFalse(store.committed)
    assertTrue(store.aborted)
  }

  @Test
  fun assetChunksRetainTheirDomainWhenDomainsReuseTheSameAssetId() {
    val ordinaryBytes = byteArrayOf(1, 2, 3, 4)
    val privateBytes = byteArrayOf(8, 7, 6)
    val raw = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(raw)
    writePayload(writer, BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("folder-1", "资料", FolderKind.USER, 1L, 0))
    writePayload(
      writer,
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Note("ordinary-note", "folder-1", "普通", false, 1L, 1L, "minimal-white", null, null, false, null, 1L, null),
    )
    writePayload(
      writer,
      BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Block("ordinary-image", "ordinary-note", 0, BlockType.IMAGE, null, "shared-asset", 100f, BlockAlignment.CENTER, 1f, null, null),
    )
    writePayload(
      writer,
      BackupRecordDomain.PRIVATE,
      BackupRecordPayload.Note("private-note", "private-uncategorized", "私密", false, 1L, 1L, "minimal-white", null, null, false, null, 1L, null),
    )
    writePayload(
      writer,
      BackupRecordDomain.PRIVATE,
      BackupRecordPayload.Block("private-image", "private-note", 0, BlockType.IMAGE, null, "shared-asset", 100f, BlockAlignment.CENTER, 1f, null, null),
    )
    writeAsset(writer, BackupRecordDomain.ORDINARY, "shared-asset", ordinaryBytes)
    writeAsset(writer, BackupRecordDomain.PRIVATE, "shared-asset", privateBytes)
    writer.finish()

    val store = RecordingStore()
    val consumer = BackupRecordPayloadConsumer(TypedBackupRecordRestoreSink(store))
    feed(consumer, raw.toByteArray())
    consumer.commit()

    assertEquals(ordinaryBytes.toList(), store.assetBytesByDomain.getValue(BackupRecordDomain.ORDINARY).toByteArray().toList())
    assertEquals(privateBytes.toList(), store.assetBytesByDomain.getValue(BackupRecordDomain.PRIVATE).toByteArray().toList())
    assertEquals(1L, store.recordSetSummary?.ordinaryAssets)
    assertEquals(1L, store.recordSetSummary?.privateAssets)
  }

  private fun backupStream(assetBytes: ByteArray): ByteArray {
    val raw = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(raw)
    writePayload(writer, BackupRecordDomain.ORDINARY, BackupRecordPayload.Folder("folder-1", "资料", FolderKind.USER, 1L, 0))
    writePayload(writer, BackupRecordDomain.ORDINARY,
      BackupRecordPayload.Note("note-1", "folder-1", "title", false, 1L, 1L, "minimal-white", null, null, false, null, 1L, null))
    val block = BackupRecordPayload.Block(
      "image-1", "note-1", 0, BlockType.IMAGE, null, "asset-1", 100f,
      BlockAlignment.CENTER, 1f, null, null,
    )
    writePayload(writer, BackupRecordDomain.ORDINARY, block)
    val asset = BackupRecordPayload.Asset(
      "asset-1", "image/png", assetBytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(assetBytes),
    )
    val prefix = BackupRecordPayloadCodec.encodeAssetPrefix(BackupRecordDomain.ORDINARY, asset)
    writer.writeRecord(
      BackupRecordDomain.ORDINARY,
      BackupRecordKind.ASSET,
      BackupRecordPayloadCodec.SCHEMA_VERSION,
      prefix.size.toLong() + assetBytes.size,
      ByteArrayInputStream(prefix + assetBytes),
    )
    writer.finish()
    return raw.toByteArray()
  }

  private fun writePayload(writer: BackupRecordStreamWriter, domain: BackupRecordDomain, payload: BackupRecordPayload) {
    val kind = when (payload) {
      is BackupRecordPayload.Folder -> BackupRecordKind.FOLDER
      is BackupRecordPayload.Note -> BackupRecordKind.NOTE
      is BackupRecordPayload.Block -> BackupRecordKind.BLOCK
      is BackupRecordPayload.Todo -> BackupRecordKind.TODO
      is BackupRecordPayload.Draft -> BackupRecordKind.DRAFT
      is BackupRecordPayload.DraftBlock -> BackupRecordKind.DRAFT_BLOCK
      is BackupRecordPayload.Asset -> error("Assets use the streaming writer path")
    }
    val bytes = BackupRecordPayloadCodec.encode(domain, payload)
    writer.writeRecord(domain, kind, BackupRecordPayloadCodec.SCHEMA_VERSION, bytes.size.toLong(), ByteArrayInputStream(bytes))
  }

  private fun writeAsset(writer: BackupRecordStreamWriter, domain: BackupRecordDomain, assetId: String, bytes: ByteArray) {
    val asset = BackupRecordPayload.Asset(
      assetId,
      "image/png",
      bytes.size.toLong(),
      MessageDigest.getInstance("SHA-256").digest(bytes),
    )
    val prefix = BackupRecordPayloadCodec.encodeAssetPrefix(domain, asset)
    writer.writeRecord(
      domain,
      BackupRecordKind.ASSET,
      BackupRecordPayloadCodec.SCHEMA_VERSION,
      prefix.size.toLong() + bytes.size,
      ByteArrayInputStream(prefix + bytes),
    )
  }

  private fun feed(consumer: BackupPayloadConsumer, raw: ByteArray) {
    var offset = 0
    while (offset < raw.size) {
      val size = minOf(37, raw.size - offset)
      consumer.onChunk(raw.copyOfRange(offset, offset + size))
      offset += size
    }
  }

  private class RecordingStore : TypedBackupRecordStore {
    val records = mutableListOf<Pair<BackupRecordDomain, BackupRecordPayload>>()
    val assetBytes = ByteArrayOutputStream()
    val assetBytesByDomain = mutableMapOf<BackupRecordDomain, ByteArrayOutputStream>()
    var committed = false
    var aborted = false
    var recordSetSummary: BackupRecordSetSummary? = null

    override fun onRecord(domain: BackupRecordDomain, payload: BackupRecordPayload) {
      records += domain to payload
    }

    override fun onAssetBytes(domain: BackupRecordDomain, asset: BackupRecordPayload.Asset, bytes: ByteArray, offset: Int, length: Int) {
      assetBytes.write(bytes, offset, length)
      assetBytesByDomain.getOrPut(domain, ::ByteArrayOutputStream).write(bytes, offset, length)
    }

    override fun commit(streamSummary: BackupRecordStreamSummary, recordSetSummary: BackupRecordSetSummary) {
      this.recordSetSummary = recordSetSummary
      committed = true
    }

    override fun abort() {
      aborted = true
    }
  }
}

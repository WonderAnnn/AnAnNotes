package com.anan.anannotes.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupRecordStreamCodecTest {
  @Test
  fun recordsRoundTripInOrderAcrossFragmentedReadsAndStreamBoundaries() {
    val attachment = ByteArray(BackupRecordStreamCodec.READ_BUFFER_BYTES * 2 + 31) { (it % 251).toByte() }
    val output = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(output, maxStreamBytes = BackupRecordCodecTestLimits.LIMIT)
    writer.writeRecord(
      BackupRecordDomain.ORDINARY,
      BackupRecordKind.FOLDER,
      schemaVersion = 1,
      payloadBytes = 3,
      payload = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
    )
    writer.writeRecord(
      BackupRecordDomain.PRIVATE,
      BackupRecordKind.ASSET,
      schemaVersion = 1,
      payloadBytes = attachment.size.toLong(),
      payload = ByteArrayInputStream(attachment),
    )
    writer.finish()

    val receivedKinds = mutableListOf<Pair<BackupRecordDomain, BackupRecordKind>>()
    val receivedAttachment = ByteArrayOutputStream()
    val summary = BackupRecordStreamReader(maxStreamBytes = output.size().toLong()).readAll(
      ByteArrayInputStream(output.toByteArray()),
    ) { descriptor, payload ->
      receivedKinds += descriptor.domain to descriptor.kind
      assertEquals(1, descriptor.schemaVersion)
      when (descriptor.kind) {
        BackupRecordKind.FOLDER -> assertEquals(byteArrayOf(1, 2, 3).toList(), payload.readBytes().toList())
        BackupRecordKind.ASSET -> payload.copyTo(receivedAttachment)
        else -> error("Unexpected record kind")
      }
    }

    assertEquals(
      listOf(
        BackupRecordDomain.ORDINARY to BackupRecordKind.FOLDER,
        BackupRecordDomain.PRIVATE to BackupRecordKind.ASSET,
      ),
      receivedKinds,
    )
    assertTrue(attachment.contentEquals(receivedAttachment.toByteArray()))
    assertEquals(2L, summary.recordCount)
    assertEquals(attachment.size.toLong() + 3L, summary.payloadBytes)
    assertEquals(output.size().toLong(), summary.streamBytes)
    assertEquals(1L, summary.ordinaryRecordCount)
    assertEquals(1L, summary.privateRecordCount)
  }

  @Test
  fun writerRequiresExactPayloadLengthAndAStreamTerminator() {
    val output = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(output, maxStreamBytes = 64)
    assertThrows(BackupRecordStreamException::class.java) {
      writer.writeRecord(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.NOTE,
        1,
        payloadBytes = 4,
        payload = ByteArrayInputStream(byteArrayOf(7, 8, 9)),
      )
    }

    val incompleteOutput = ByteArrayOutputStream()
    val incompleteWriter = BackupRecordStreamWriter(incompleteOutput, maxStreamBytes = 64)
    incompleteWriter.writeRecord(
      BackupRecordDomain.ORDINARY,
      BackupRecordKind.NOTE,
      1,
      payloadBytes = 2,
      payload = ByteArrayInputStream(byteArrayOf(7, 8)),
    )
    val incomplete = incompleteOutput.toByteArray()
    assertThrows(BackupRecordStreamException::class.java) {
      BackupRecordStreamReader(maxStreamBytes = 64).readAll(ByteArrayInputStream(incomplete)) { _, payload ->
        payload.readBytes()
      }
    }
  }

  @Test
  fun readerRejectsTruncatedPayloadUnconsumedPayloadTrailingBytesAndUnknownSchema() {
    val valid = encodeOneRecord(byteArrayOf(1, 2, 3, 4))
    assertThrows(BackupRecordStreamException::class.java) {
      BackupRecordStreamReader(maxStreamBytes = 1024).readAll(ByteArrayInputStream(valid.copyOf(valid.size - 13))) { _, payload ->
        payload.readBytes()
      }
    }
    assertThrows(BackupRecordStreamException::class.java) {
      BackupRecordStreamReader(maxStreamBytes = 1024).readAll(ByteArrayInputStream(valid)) { _, _ -> Unit }
    }

    val trailing = valid + byteArrayOf(0x55)
    assertThrows(BackupRecordStreamException::class.java) {
      BackupRecordStreamReader(maxStreamBytes = 1024).readAll(ByteArrayInputStream(trailing)) { _, payload ->
        payload.readBytes()
      }
    }

    val unknownSchema = valid.clone().also { it[7] = 2 }
    assertThrows(BackupRecordStreamException::class.java) {
      BackupRecordStreamReader(maxStreamBytes = 1024).readAll(ByteArrayInputStream(unknownSchema)) { _, payload ->
        payload.readBytes()
      }
    }
  }

  @Test
  fun streamReaderHandlesShortAndZeroLengthReads() {
    val encoded = encodeOneRecord("short reads are okay".toByteArray())
    val summary = BackupRecordStreamReader(maxStreamBytes = 1024).readAll(FragmentedInputStream(encoded)) { _, body ->
      assertEquals("short reads are okay", body.readBytes().toString(Charsets.UTF_8))
    }
    assertEquals(1L, summary.recordCount)
  }

  @Test
  fun recordCountAndStreamSizeLimitsAreCheckedBeforePayloadRead() {
    val source = CountingInputStream(ByteArray(100))
    val output = ByteArrayOutputStream()
    val writer = BackupRecordStreamWriter(output, maxStreamBytes = 31)
    assertThrows(BackupRecordStreamException::class.java) {
      writer.writeRecord(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, 1, 3, source)
    }
    assertEquals("Only the stream header may have been emitted; payload must not be read", 0, source.bytesRead)

    val tooSmall = ByteArrayOutputStream()
    assertThrows(BackupRecordStreamException::class.java) {
      BackupRecordStreamWriter(tooSmall, maxStreamBytes = 16)
    }
  }

  @Test
  fun perDomainAttachmentCapsAreEnforcedWithoutBufferingTheAttachment() {
    val ordinaryOversize = CountingInputStream(ByteArray(1))
    val ordinaryWriter = BackupRecordStreamWriter(ByteArrayOutputStream())
    assertThrows(BackupRecordStreamException::class.java) {
      ordinaryWriter.writeRecord(
        BackupRecordDomain.ORDINARY,
        BackupRecordKind.ASSET,
        1,
        BackupRecordStreamCodec.MAX_ORDINARY_ASSET_BYTES + 1,
        ordinaryOversize,
      )
    }
    assertEquals(0, ordinaryOversize.bytesRead)

    val privateOversize = CountingInputStream(ByteArray(1))
    val privateWriter = BackupRecordStreamWriter(ByteArrayOutputStream())
    assertThrows(BackupRecordStreamException::class.java) {
      privateWriter.writeRecord(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.ASSET,
        1,
        BackupRecordStreamCodec.MAX_PRIVATE_ASSET_BYTES + 1,
        privateOversize,
      )
    }
    assertEquals(0, privateOversize.bytesRead)
  }

  @Test
  fun readerRejectsInvalidDomainKindAndNegativeLengthBeforeOpeningPayload() {
    val valid = encodeOneRecord(byteArrayOf(1))
    val invalidDomain = valid.clone().also { it[5] = 3 }
    val invalidKind = valid.clone().also { it[6] = 99 }
    val negativeLength = valid.clone().also { it[9] = 0xff.toByte() }

    listOf(invalidDomain, invalidKind, negativeLength).forEach { bytes ->
      assertThrows(BackupRecordStreamException::class.java) {
        BackupRecordStreamReader(maxStreamBytes = 1024).readAll(ByteArrayInputStream(bytes)) { _, payload ->
          payload.readBytes()
        }
      }
    }
  }

  @Test
  fun aHandlerFailureIsPropagatedAndNeverReportedAsSuccessfulSummary() {
    val encoded = encodeOneRecord(byteArrayOf(1, 2, 3))
    val thrown = IllegalStateException("staging failed")
    val caught = assertThrows(IllegalStateException::class.java) {
      BackupRecordStreamReader(maxStreamBytes = 1024).readAll(ByteArrayInputStream(encoded)) { _, _ -> throw thrown }
    }
    assertTrue(caught === thrown)
  }

  @Test
  fun incrementalDecoderBridgesAnbkChunkBoundariesWithoutCombiningPlaintext() {
    val attachment = ByteArray(BackupContainerCodec.CHUNK_BYTES + 29) { (it * 13).toByte() }
    val encoded = ByteArrayOutputStream().also { output ->
      val writer = BackupRecordStreamWriter(output, maxStreamBytes = BackupRecordCodecTestLimits.LIMIT)
      writer.writeRecord(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.ASSET,
        1,
        attachment.size.toLong(),
        ByteArrayInputStream(attachment),
      )
      writer.finish()
    }.toByteArray()
    val handler = RecordingIncrementalHandler()
    val decoder = BackupRecordStreamDecoder(handler, maxStreamBytes = BackupRecordCodecTestLimits.LIMIT)

    var offset = 0
    while (offset < encoded.size) {
      val next = minOf(offset + BackupContainerCodec.CHUNK_BYTES, encoded.size)
      decoder.accept(encoded, offset, next - offset)
      offset = next
    }
    val summary = decoder.finish()

    assertEquals(listOf("start:PRIVATE:ASSET", "end:ASSET"), handler.events)
    assertTrue(attachment.contentEquals(handler.asset.toByteArray()))
    assertEquals(1L, summary.recordCount)
    assertEquals(attachment.size.toLong(), summary.payloadBytes)
    assertEquals(encoded.size.toLong(), summary.streamBytes)
  }

  @Test
  fun incrementalDecoderRejectsMissingEndTruncatedBodyAndBytesAfterTerminator() {
    val valid = encodeOneRecord(byteArrayOf(4, 5, 6))
    val missingEnd = valid.copyOf(valid.size - BackupRecordStreamCodec.FRAME_HEADER_BYTES)
    val handler = RecordingIncrementalHandler()
    val incomplete = BackupRecordStreamDecoder(handler, maxStreamBytes = 1024)
    incomplete.accept(missingEnd)
    assertThrows(BackupRecordStreamException::class.java) { incomplete.finish() }

    val truncatedBody = valid.copyOf(valid.size - BackupRecordStreamCodec.FRAME_HEADER_BYTES - 1)
    val truncated = BackupRecordStreamDecoder(RecordingIncrementalHandler(), maxStreamBytes = 1024)
    truncated.accept(truncatedBody)
    assertThrows(BackupRecordStreamException::class.java) { truncated.finish() }

    val trailing = BackupRecordStreamDecoder(RecordingIncrementalHandler(), maxStreamBytes = 1024)
    assertThrows(BackupRecordStreamException::class.java) { trailing.accept(valid + byteArrayOf(1)) }
  }

  @Test
  fun incrementalDecoderRejectsOversizeDescriptorBeforeStartingRecord() {
    val output = ByteArrayOutputStream()
    output.write("ANRD".toByteArray(Charsets.US_ASCII))
    output.write(1)
    output.write(byteArrayOf(2, 7, 0, 1)) // PRIVATE / ASSET / schema 1
    output.write(java.nio.ByteBuffer.allocate(8).putLong(BackupRecordStreamCodec.MAX_PRIVATE_ASSET_BYTES + 1).array())
    val handler = RecordingIncrementalHandler()
    val decoder = BackupRecordStreamDecoder(handler, maxStreamBytes = BackupRecordCodecTestLimits.LIMIT)

    assertThrows(BackupRecordStreamException::class.java) { decoder.accept(output.toByteArray()) }
    assertTrue(handler.events.isEmpty())
  }

  @Test
  fun streamByteLimitIsCheckedFromTheDescriptorBeforeReadingItsPayload() {
    val encoded = encodeOneRecord(byteArrayOf(1, 2, 3))
    val handler = RecordingIncrementalHandler()
    val decoder = BackupRecordStreamDecoder(handler, maxStreamBytes = 31)

    assertThrows(BackupRecordStreamException::class.java) {
      decoder.accept(encoded, offset = 0, length = BackupRecordStreamCodec.STREAM_HEADER_BYTES + BackupRecordStreamCodec.FRAME_HEADER_BYTES)
    }

    assertTrue("Record callback must not begin when body plus terminator cannot fit", handler.events.isEmpty())
  }

  private fun encodeOneRecord(payload: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
      writer.writeRecord(BackupRecordDomain.PRIVATE, BackupRecordKind.NOTE, 1, payload.size.toLong(), ByteArrayInputStream(payload))
      writer.finish()
    }
    return output.toByteArray()
  }

  private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var bytesRead = 0
      private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
      super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
  }

  private class FragmentedInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    private var calls = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      calls++
      if (calls % 7 == 0) return 0
      return super.read(buffer, offset, minOf(length, 5))
    }
  }

  private class RecordingIncrementalHandler : BackupRecordStreamHandler {
    val events = mutableListOf<String>()
    val asset = ByteArrayOutputStream()

    override fun onRecordStart(descriptor: BackupRecordDescriptor) {
      events += "start:${descriptor.domain}:${descriptor.kind}"
    }

    override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
      if (descriptor.kind == BackupRecordKind.ASSET) asset.write(bytes, offset, length)
    }

    override fun onRecordEnd(descriptor: BackupRecordDescriptor) {
      events += "end:${descriptor.kind}"
    }
  }

  private object BackupRecordCodecTestLimits {
    const val LIMIT = 16L * 1024L * 1024L
  }
}

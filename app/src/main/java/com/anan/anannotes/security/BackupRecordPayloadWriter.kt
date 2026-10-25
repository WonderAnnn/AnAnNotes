package com.anan.anannotes.security

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.security.MessageDigest

/** Typed writer: fields are schema-encoded and the complete relationship set is checked at finish. */
internal class BackupRecordPayloadWriter(
  destination: OutputStream,
  maxStreamBytes: Long = BackupRecordStreamCodec.MAX_STREAM_BYTES,
  private val validator: BackupRecordSetValidator = BackupRecordSetValidator(),
) {
  private val frames = BackupRecordStreamWriter(destination, maxStreamBytes)
  private var finished = false
  private var failed = false

  fun write(domain: BackupRecordDomain, payload: BackupRecordPayload) {
    ensureOpen()
    if (payload is BackupRecordPayload.Asset) {
      throw BackupRecordPayloadException("Assets must be written with writeAsset and a reopenable source")
    }
    val encoded = BackupRecordPayloadCodec.encode(domain, payload)
    try {
      frames.writeRecord(domain, payload.kind(), BackupRecordPayloadCodec.SCHEMA_VERSION, encoded.size.toLong(), ByteArrayInputStream(encoded))
      validator.accept(domain, payload)
    } catch (failure: Throwable) {
      failed = true
      throw failure
    } finally {
      MemoryWipe.bytes(encoded)
    }
  }

  /**
   * Hashes one bounded pass to construct the asset header, then streams a second pass into ANRD
   * while recomputing length and SHA-256. If the source changes between passes, the outer SAF
   * transaction must discard the incomplete encrypted container.
   */
  fun writeAsset(
    domain: BackupRecordDomain,
    assetId: String,
    mimeType: String,
    source: () -> InputStream,
  ) = writeAssetInternal(domain, assetId, source) { mimeType }

  /** Resolves the type from a bounded signature sample collected during the first streaming pass. */
  fun writeAssetDetectedMimeType(
    domain: BackupRecordDomain,
    assetId: String,
    source: () -> InputStream,
    resolveMimeType: (ByteArray) -> String,
  ) = writeAssetInternal(domain, assetId, source, resolveMimeType)

  private fun writeAssetInternal(
    domain: BackupRecordDomain,
    assetId: String,
    source: () -> InputStream,
    resolveMimeType: (ByteArray) -> String,
  ) {
    ensureOpen()
    var expectedDigest: ByteArray? = null
    var signatureSample: ByteArray? = null
    var prefix: ByteArray? = null
    try {
      val firstPass = hashSource(domain, source)
      expectedDigest = firstPass.digest
      signatureSample = firstPass.signatureSample
      val asset = BackupRecordPayload.Asset(
        assetId = assetId,
        mimeType = resolveMimeType(firstPass.signatureSample),
        byteCount = firstPass.byteCount,
        sha256 = firstPass.digest,
      )
      prefix = BackupRecordPayloadCodec.encodeAssetPrefix(domain, asset)
      val prefixBytes = prefix.size.toLong()
      val payloadBytes = BackupRecordPayloadCodec.assetPayloadBytes(domain, prefixBytes, asset)
      val secondDigest = MessageDigest.getInstance(SHA_256)
      val checkedSource = VerifyingAssetInputStream(source(), asset.byteCount, secondDigest)
      val recordInput = SequenceInputStream(ByteArrayInputStream(prefix), checkedSource)
      try {
        frames.writeRecord(
          domain,
          BackupRecordKind.ASSET,
          BackupRecordPayloadCodec.SCHEMA_VERSION,
          payloadBytes,
          recordInput,
        )
        checkedSource.verifyEnd(asset.sha256)
        validator.accept(domain, asset)
      } finally {
        recordInput.close()
        MemoryWipe.bytes(asset.sha256)
      }
    } catch (failure: Throwable) {
      failed = true
      if (failure is BackupRecordPayloadException) throw failure
      if (failure is IOException) throw BackupRecordPayloadException("Unable to stream backup attachment", failure)
      throw failure
    } finally {
      MemoryWipe.bytes(expectedDigest)
      MemoryWipe.bytes(signatureSample)
      MemoryWipe.bytes(prefix)
    }
  }

  fun finish(): BackupRecordStreamSummary {
    ensureOpen()
    try {
      validator.finish()
      return frames.finish().also { finished = true }
    } catch (failure: Throwable) {
      failed = true
      throw failure
    }
  }

  private fun hashSource(domain: BackupRecordDomain, sourceFactory: () -> InputStream): HashedAsset {
    val maximum = if (domain == BackupRecordDomain.PRIVATE) {
      BackupRecordStreamCodec.MAX_PRIVATE_ASSET_BYTES
    } else {
      BackupRecordStreamCodec.MAX_ORDINARY_ASSET_BYTES
    }
    val digest = MessageDigest.getInstance(SHA_256)
    val buffer = ByteArray(ASSET_BUFFER_BYTES)
    val signatureSample = ByteArray(MAX_MIME_SIGNATURE_BYTES)
    var signatureLength = 0
    var byteCount = 0L
    try {
      sourceFactory().use { source ->
        while (true) {
          val count = source.read(buffer)
          if (count < 0) break
          if (count == 0) continue
          if (count.toLong() > maximum - byteCount) throw BackupRecordPayloadException("Backup attachment exceeds its size limit")
          digest.update(buffer, 0, count)
          val sampleCount = minOf(count, signatureSample.size - signatureLength)
          if (sampleCount > 0) {
            buffer.copyInto(signatureSample, signatureLength, 0, sampleCount)
            signatureLength += sampleCount
          }
          byteCount += count.toLong()
        }
      }
      if (byteCount == 0L) throw BackupRecordPayloadException("Backup attachment is empty")
      return HashedAsset(byteCount, digest.digest(), signatureSample.copyOf(signatureLength))
    } finally {
      MemoryWipe.bytes(buffer)
      MemoryWipe.bytes(signatureSample)
    }
  }

  private fun ensureOpen() {
    check(!finished && !failed) { "Backup payload writer is not open" }
  }

  private fun BackupRecordPayload.kind(): BackupRecordKind = when (this) {
    is BackupRecordPayload.Folder -> BackupRecordKind.FOLDER
    is BackupRecordPayload.Note -> BackupRecordKind.NOTE
    is BackupRecordPayload.Block -> BackupRecordKind.BLOCK
    is BackupRecordPayload.Todo -> BackupRecordKind.TODO
    is BackupRecordPayload.Draft -> BackupRecordKind.DRAFT
    is BackupRecordPayload.DraftBlock -> BackupRecordKind.DRAFT_BLOCK
    is BackupRecordPayload.Asset -> throw BackupRecordPayloadException("Use writeAsset for attachment records")
  }

  private class VerifyingAssetInputStream(
    private val source: InputStream,
    private val expectedBytes: Long,
    private val digest: MessageDigest,
  ) : InputStream() {
    private var consumedBytes = 0L
    private var verified = false

    override fun read(): Int {
      if (consumedBytes >= expectedBytes) return -1
      val result = source.read()
      if (result < 0) throw IOException("Backup attachment became shorter while exporting")
      digest.update(result.toByte())
      consumedBytes++
      return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
      if (length == 0) return 0
      if (consumedBytes >= expectedBytes) return -1
      val requested = minOf(length.toLong(), expectedBytes - consumedBytes).toInt()
      val count = source.read(buffer, offset, requested)
      if (count < 0) throw IOException("Backup attachment became shorter while exporting")
      if (count == 0) {
        val one = read()
        if (one < 0) return -1
        buffer[offset] = one.toByte()
        return 1
      }
      digest.update(buffer, offset, count)
      consumedBytes += count.toLong()
      return count
    }

    fun verifyEnd(expectedDigest: ByteArray) {
      if (consumedBytes != expectedBytes) throw IOException("Backup attachment length changed while exporting")
      if (source.read() != -1) throw IOException("Backup attachment grew while exporting")
      if (!MessageDigest.isEqual(digest.digest(), expectedDigest)) {
        throw BackupRecordPayloadException("Backup attachment changed while exporting")
      }
      verified = true
    }

    override fun close() {
      try {
        source.close()
      } finally {
        if (!verified) digest.reset()
      }
    }
  }

  private data class HashedAsset(val byteCount: Long, val digest: ByteArray, val signatureSample: ByteArray)

  private companion object {
    const val ASSET_BUFFER_BYTES = 64 * 1024
    const val MAX_MIME_SIGNATURE_BYTES = 16
    const val SHA_256 = "SHA-256"
  }
}

package com.anan.anannotes.feature.backup

import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordKind
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordPayloadCodec
import com.anan.anannotes.security.BackupRecordPayloadException
import com.anan.anannotes.security.CryptoBytes
import com.anan.anannotes.security.MemoryWipe
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Builds canonical conflict fingerprints without retaining note text or attachment bytes. */
internal class BackupObjectFingerprintCollector : AutoCloseable {
  private val folders = linkedMapOf<BackupObjectKey, ByteArray>()
  private val notes = linkedMapOf<BackupObjectKey, NoteAggregate>()
  private val assets = linkedMapOf<Pair<BackupRecordDomain, String>, ByteArray>()
  private var finished = false

  fun accept(domain: BackupRecordDomain, payload: BackupRecordPayload) {
    check(!finished) { "Fingerprint collector is closed" }
    when (payload) {
      is BackupRecordPayload.Folder -> {
        val key = BackupObjectKey(domain, BackupRecordKind.FOLDER, payload.id)
        val digest = recordDigest(domain, BackupRecordKind.FOLDER, payload)
        if (folders.putIfAbsent(key, digest) != null) {
          MemoryWipe.bytes(digest)
          throw BackupRecordPayloadException("Duplicate folder fingerprint")
        }
      }
      is BackupRecordPayload.Note -> {
        val key = BackupObjectKey(domain, BackupRecordKind.NOTE, payload.id)
        val aggregate = notes.getOrPut(key) { NoteAggregate() }
        if (aggregate.noteSeen) throw BackupRecordPayloadException("Duplicate note fingerprint")
        aggregate.noteSeen = true
        aggregate.parts += RecordPart(BackupRecordKind.NOTE, payload.id, recordDigest(domain, BackupRecordKind.NOTE, payload))
      }
      is BackupRecordPayload.Block -> {
        val key = BackupObjectKey(domain, BackupRecordKind.NOTE, payload.noteId)
        val aggregate = notes.getOrPut(key) { NoteAggregate() }
        aggregate.parts += RecordPart(BackupRecordKind.BLOCK, payload.id, recordDigest(domain, BackupRecordKind.BLOCK, payload))
        payload.assetRef?.let(aggregate.assetIds::add)
      }
      is BackupRecordPayload.Todo -> {
        val key = BackupObjectKey(domain, BackupRecordKind.NOTE, payload.noteId)
        val aggregate = notes.getOrPut(key) { NoteAggregate() }
        aggregate.parts += RecordPart(BackupRecordKind.TODO, payload.id, recordDigest(domain, BackupRecordKind.TODO, payload))
      }
      is BackupRecordPayload.Asset -> {
        val key = domain to payload.assetId
        val digest = recordDigest(domain, BackupRecordKind.ASSET, payload)
        if (assets.putIfAbsent(key, digest) != null) {
          MemoryWipe.bytes(digest)
          throw BackupRecordPayloadException("Duplicate attachment fingerprint")
        }
      }
      is BackupRecordPayload.Draft,
      is BackupRecordPayload.DraftBlock -> Unit
    }
  }

  fun finish(): Map<BackupObjectKey, CryptoBytes> {
    check(!finished) { "Fingerprint collector is closed" }
    val result = linkedMapOf<BackupObjectKey, CryptoBytes>()
    try {
      folders.forEach { (key, digest) -> result[key] = CryptoBytes.of(digest) }
      notes.forEach { (key, aggregate) ->
        if (!aggregate.noteSeen) throw BackupRecordPayloadException("Note child has no note fingerprint")
        val parts = aggregate.parts.toMutableList()
        aggregate.assetIds.forEach { assetId ->
          val digest = assets[key.domain to assetId]
            ?: throw BackupRecordPayloadException("Note fingerprint refers to a missing attachment")
          parts += RecordPart(BackupRecordKind.ASSET, assetId, digest)
        }
        val digest = aggregateDigest(key.domain, parts)
        result[key] = CryptoBytes.of(digest)
        MemoryWipe.bytes(digest)
      }
      finished = true
      return result.toMap()
    } catch (failure: Throwable) {
      result.clear()
      throw failure
    } finally {
      clearMetadata()
    }
  }

  override fun close() {
    if (finished) return
    finished = true
    clearMetadata()
  }

  private fun recordDigest(domain: BackupRecordDomain, kind: BackupRecordKind, payload: BackupRecordPayload): ByteArray {
    val encoded = when (payload) {
      is BackupRecordPayload.Asset -> BackupRecordPayloadCodec.encodeAssetPrefix(domain, payload)
      else -> BackupRecordPayloadCodec.encode(domain, payload)
    }
    return try {
      MessageDigest.getInstance(SHA_256).apply {
        update(domain.wireId.toByte())
        update(kind.wireId.toByte())
        update(encoded)
      }.digest()
    } finally {
      MemoryWipe.bytes(encoded)
    }
  }

  private fun aggregateDigest(domain: BackupRecordDomain, parts: List<RecordPart>): ByteArray {
    val digest = MessageDigest.getInstance(SHA_256)
    digest.update(domain.wireId.toByte())
    parts.sortedWith(compareBy<RecordPart> { it.kind.wireId }.thenBy { it.id }).forEach { part ->
      digest.update(part.kind.wireId.toByte())
      val id = part.id.toByteArray(StandardCharsets.UTF_8)
      try {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(id.size).array())
        digest.update(id)
      } finally {
        MemoryWipe.bytes(id)
      }
      digest.update(part.digest)
    }
    return digest.digest()
  }

  private fun clearMetadata() {
    folders.values.forEach(MemoryWipe::bytes)
    folders.clear()
    notes.values.forEach { aggregate -> aggregate.parts.forEach { MemoryWipe.bytes(it.digest) } }
    notes.clear()
    assets.values.forEach(MemoryWipe::bytes)
    assets.clear()
  }

  private data class RecordPart(val kind: BackupRecordKind, val id: String, val digest: ByteArray)

  private class NoteAggregate {
    var noteSeen = false
    val parts = mutableListOf<RecordPart>()
    val assetIds = linkedSetOf<String>()
  }

  private companion object {
    const val SHA_256 = "SHA-256"
  }
}

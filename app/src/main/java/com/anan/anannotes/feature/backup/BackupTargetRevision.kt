package com.anan.anannotes.feature.backup

import com.anan.anannotes.security.CryptoBytes
import com.anan.anannotes.security.MemoryWipe
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable optimistic-concurrency token derived from the canonical object fingerprint map. */
internal object BackupTargetRevision {
  fun from(fingerprints: Map<BackupObjectKey, CryptoBytes>): Long {
    val digest = MessageDigest.getInstance(SHA_256)
    fingerprints.entries.sortedWith(
      compareBy<Map.Entry<BackupObjectKey, CryptoBytes>> { it.key.domain.wireId }
        .thenBy { it.key.kind.wireId }
        .thenBy { it.key.id },
    ).forEach { (key, value) ->
      digest.update(key.domain.wireId.toByte())
      digest.update(key.kind.wireId.toByte())
      val idBytes = key.id.toByteArray(StandardCharsets.UTF_8)
      try {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(idBytes.size).array())
        digest.update(idBytes)
      } finally {
        MemoryWipe.bytes(idBytes)
      }
      val fingerprint = value.copyBytes()
      try {
        digest.update(fingerprint)
      } finally {
        MemoryWipe.bytes(fingerprint)
      }
    }
    val result = digest.digest()
    return try {
      // Keep the token non-negative; the full fingerprint map is also compared at restore time.
      ByteBuffer.wrap(result, 0, java.lang.Long.BYTES).long and Long.MAX_VALUE
    } finally {
      MemoryWipe.bytes(result)
    }
  }

  private const val SHA_256 = "SHA-256"
}

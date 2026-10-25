package com.anan.anannotes.security

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class BackupContainerException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BackupAuthenticationException(cause: Throwable? = null) :
  BackupContainerException("Backup password or authenticated data is invalid", cause)

class InvalidBackupContainerException(message: String, cause: Throwable? = null) :
  BackupContainerException(message, cause)

data class BackupContainerSummary(
  val containerBytes: Long,
  val plaintextBytes: Long,
  val chunkCount: Long,
)

/**
 * Transactional receiver for a fully verified payload pass.
 *
 * [onChunk] receives one short-lived plaintext buffer at a time; the codec wipes it as soon as the
 * callback returns. Implementations must stage writes and expose them only from [commit]. They must
 * discard their own staged state from [abort]. [commit] must publish atomically, or leave all state
 * rollback-capable if it throws; [abort] must erase any pending state. This is what lets restore
 * route private records directly into target-key encryption without writing decrypted private
 * bytes to ordinary files.
 */
internal interface BackupPayloadConsumer {
  fun onChunk(plaintext: ByteArray)
  fun commit()
  fun abort()
}

/**
 * Streaming ANBK v1 container. The caller supplies an operation-specific size ceiling derived from
 * the selected file and available storage; the container itself also has a fixed V1 ceiling.
 */
class BackupContainerCodec internal constructor(
  private val random: SecureRandom,
  private val keyDeriver: KeyDeriver,
) {
  constructor() : this(SecureRandom(), Argon2idKeyDeriver())

  /** Writes ciphertext only. A failing write must be directed to a temporary destination and discarded. */
  fun encrypt(
    plaintext: InputStream,
    destination: OutputStream,
    backupPassword: CharArray,
    maxPlaintextBytes: Long,
  ): BackupContainerSummary {
    var salt: ByteArray? = null
    var noncePrefix: ByteArray? = null
    var key: ByteArray? = null
    val chunk = ByteArray(CHUNK_BYTES)
    try {
      require(maxPlaintextBytes in 0..MAX_PLAINTEXT_BYTES) { "Backup plaintext size limit is invalid" }
      validatePassword(backupPassword)
      salt = ByteArray(SALT_BYTES).also(random::nextBytes)
      noncePrefix = ByteArray(NONCE_PREFIX_BYTES).also(random::nextBytes)
      key = keyDeriver.derive(backupPassword, CryptoBytes.of(salt), KdfParameters.DEFAULT)
      require(key.size == KEY_BYTES) { "Backup key derivation returned an invalid length" }

      val header = encodeHeader(salt, noncePrefix)
      destination.write(header)
      var containerBytes = header.size.toLong()
      var plaintextBytes = 0L
      var chunkCount = 0L

      while (true) {
        val size = readChunk(plaintext, chunk)
        if (size == 0) break
        plaintextBytes = checkedAdd(plaintextBytes, size.toLong(), "Backup plaintext length overflow")
        require(plaintextBytes <= maxPlaintextBytes && plaintextBytes <= MAX_PLAINTEXT_BYTES) {
          "Backup plaintext exceeds the allowed size"
        }
        require(chunkCount < MAX_DATA_CHUNKS) { "Backup contains too many chunks" }

        val frameHeader = encodeFrameHeader(TYPE_DATA, chunkCount, size)
        val ciphertext = encryptFrame(key, noncePrefix, header, frameHeader, chunk, size, chunkCount)
        try {
          destination.write(frameHeader)
          destination.write(ciphertext)
        } finally {
          MemoryWipe.bytes(ciphertext)
          MemoryWipe.bytes(chunk)
        }
        containerBytes = checkedAdd(containerBytes, frameHeader.size.toLong() + size + GCM_TAG_BYTES, "Backup size overflow")
        chunkCount++
      }

      val finalHeader = encodeFrameHeader(TYPE_FINAL, chunkCount, 0)
      val finalTag = encryptFrame(key, noncePrefix, header, finalHeader, ByteArray(0), 0, chunkCount)
      try {
        destination.write(finalHeader)
        destination.write(finalTag)
      } finally {
        MemoryWipe.bytes(finalTag)
      }
      containerBytes = checkedAdd(containerBytes, finalHeader.size.toLong() + GCM_TAG_BYTES, "Backup size overflow")
      check(containerBytes <= MAX_CONTAINER_BYTES) { "Backup container exceeds the V1 size limit" }
      return BackupContainerSummary(containerBytes, plaintextBytes, chunkCount)
    } finally {
      MemoryWipe.chars(backupPassword)
      MemoryWipe.bytes(salt)
      MemoryWipe.bytes(noncePrefix)
      MemoryWipe.bytes(key)
      MemoryWipe.bytes(chunk)
    }
  }

  /**
   * Authenticates every chunk, the mandatory FINAL record, and EOF without exposing plaintext.
   * The returned capability retains the derived key until closed.
   */
  fun verify(
    source: InputStream,
    backupPassword: CharArray,
    maxContainerBytes: Long,
  ): VerifiedBackup {
    var key: ByteArray? = null
    try {
      validateContainerLimit(maxContainerBytes)
      validatePassword(backupPassword)
      val digest = MessageDigest.getInstance(SHA_256)
      val wire = BoundedDigestInput(source, maxContainerBytes, digest)
      val rawHeader = wire.readFully(HEADER_BYTES)
      val header = decodeHeader(rawHeader)
      key = keyDeriver.derive(backupPassword, header.salt, header.parameters)
      if (key.size != KEY_BYTES) throw InvalidBackupContainerException("Invalid backup key length")

      val result = processRecords(wire, header, key, consumer = null)
      return VerifiedBackup(
        key = key,
        header = header.bytes,
        fingerprint = result.fingerprint,
        summary = result.summary,
        maxContainerBytes = maxContainerBytes,
      ).also { key = null }
    } catch (failure: BackupContainerException) {
      throw failure
    } catch (failure: IOException) {
      throw failure
    } catch (failure: Exception) {
      throw InvalidBackupContainerException("Unable to verify backup container", failure)
    } finally {
      MemoryWipe.chars(backupPassword)
      MemoryWipe.bytes(key)
    }
  }

  /**
   * Performs the consuming pass against a previously verified container. It authenticates each
   * chunk again, binds the pass to the verified header and ciphertext fingerprint, and commits the
   * consumer only after FINAL, EOF, and the fingerprint all match.
   */
  internal fun decryptVerified(verified: VerifiedBackup, source: InputStream, consumer: BackupPayloadConsumer) {
    var committed = false
    try {
      verified.withKey { key ->
        val digest = MessageDigest.getInstance(SHA_256)
        val wire = BoundedDigestInput(source, verified.maxContainerBytes, digest)
        val rawHeader = wire.readFully(HEADER_BYTES)
        if (!rawHeader.contentEquals(verified.header)) {
          throw InvalidBackupContainerException("Verified backup changed before restore")
        }
        val header = decodeHeader(rawHeader)
        val result = processRecords(wire, header, key, consumer)
        if (!MessageDigest.isEqual(result.fingerprint, verified.fingerprint.copyBytes())) {
          throw InvalidBackupContainerException("Verified backup changed during restore")
        }
        if (result.summary != verified.summary) {
          throw InvalidBackupContainerException("Verified backup summary changed during restore")
        }
        consumer.commit()
        committed = true
      }
    } catch (failure: Throwable) {
      if (!committed) {
        try {
          consumer.abort()
        } catch (cleanupFailure: Throwable) {
          if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
      }
      throw failure
    }
  }

  private fun processRecords(
    wire: BoundedDigestInput,
    header: ParsedHeader,
    key: ByteArray,
    consumer: BackupPayloadConsumer?,
  ): ProcessedContainer {
    var expectedIndex = 0L
    var plaintextBytes = 0L
    var chunkCount = 0L
    var sawShortDataChunk = false

    while (true) {
      val frameHeader = wire.readFully(FRAME_HEADER_BYTES)
      val frame = decodeFrameHeader(frameHeader)
      if (frame.index != expectedIndex) throw InvalidBackupContainerException("Backup records are not sequential")

      when (frame.type) {
        TYPE_DATA -> {
          if (chunkCount >= MAX_DATA_CHUNKS) throw InvalidBackupContainerException("Too many backup chunks")
          if (sawShortDataChunk) throw InvalidBackupContainerException("A short data chunk is not the final data chunk")
          if (frame.plaintextLength !in 1..CHUNK_BYTES) throw InvalidBackupContainerException("Invalid backup chunk length")
          if (frame.plaintextLength < CHUNK_BYTES) sawShortDataChunk = true
          plaintextBytes = checkedAdd(
            plaintextBytes,
            frame.plaintextLength.toLong(),
            "Backup plaintext length overflow",
          )
          if (plaintextBytes > MAX_PLAINTEXT_BYTES) throw InvalidBackupContainerException("Backup payload is too large")

          val ciphertext = wire.readFully(frame.plaintextLength + GCM_TAG_BYTES)
          val plaintext = try {
            decryptFrame(key, header, frameHeader, ciphertext, frame.plaintextLength, frame.index)
          } finally {
            MemoryWipe.bytes(ciphertext)
          }
          try {
            if (plaintext.size != frame.plaintextLength) throw InvalidBackupContainerException("Invalid decrypted chunk length")
            consumer?.onChunk(plaintext)
          } finally {
            MemoryWipe.bytes(plaintext)
          }
          chunkCount++
          expectedIndex = nextIndex(expectedIndex)
        }

        TYPE_FINAL -> {
          if (frame.plaintextLength != 0) throw InvalidBackupContainerException("Invalid FINAL record length")
          val ciphertext = wire.readFully(GCM_TAG_BYTES)
          val finalPlaintext = try {
            decryptFrame(key, header, frameHeader, ciphertext, 0, frame.index)
          } finally {
            MemoryWipe.bytes(ciphertext)
          }
          try {
            if (finalPlaintext.isNotEmpty()) throw InvalidBackupContainerException("Invalid FINAL record payload")
          } finally {
            MemoryWipe.bytes(finalPlaintext)
          }
          if (expectedIndex == Long.MAX_VALUE) throw InvalidBackupContainerException("Backup record index overflow")
          wire.requireEndOfInput()
          return ProcessedContainer(
            summary = BackupContainerSummary(wire.bytesRead, plaintextBytes, chunkCount),
            fingerprint = wire.finishDigest(),
          )
        }

        else -> throw InvalidBackupContainerException("Unknown backup record type")
      }
    }
  }

  private fun encryptFrame(
    key: ByteArray,
    noncePrefix: ByteArray,
    header: ByteArray,
    frameHeader: ByteArray,
    plaintext: ByteArray,
    plaintextLength: Int,
    index: Long,
  ): ByteArray = aesGcm(Cipher.ENCRYPT_MODE, key, nonce(noncePrefix, index), header, frameHeader)
    .doFinal(plaintext, 0, plaintextLength)

  private fun decryptFrame(
    key: ByteArray,
    header: ParsedHeader,
    frameHeader: ByteArray,
    ciphertext: ByteArray,
    plaintextLength: Int,
    index: Long,
  ): ByteArray {
    val plaintext = try {
      aesGcm(Cipher.DECRYPT_MODE, key, nonce(header.noncePrefix, index), header.bytes, frameHeader).doFinal(ciphertext)
    } catch (failure: AEADBadTagException) {
      throw BackupAuthenticationException(failure)
    } catch (failure: BackupContainerException) {
      throw failure
    } catch (failure: Exception) {
      throw InvalidBackupContainerException("Unable to authenticate backup record", failure)
    }
    if (plaintext.size != plaintextLength) {
      MemoryWipe.bytes(plaintext)
      throw InvalidBackupContainerException("Decrypted backup chunk has an invalid length")
    }
    return plaintext
  }

  private fun aesGcm(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray, frameHeader: ByteArray): Cipher {
    val aad = associatedData(header, frameHeader)
    try {
      return Cipher.getInstance(AES_GCM).apply {
        init(mode, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
        updateAAD(aad)
      }
    } finally {
      MemoryWipe.bytes(nonce)
      MemoryWipe.bytes(aad)
    }
  }

  private fun associatedData(header: ByteArray, frameHeader: ByteArray): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
      output.write(AAD_DOMAIN)
      output.write(header)
      output.write(frameHeader)
    }
    bytes.toByteArray()
  }

  private fun nonce(prefix: ByteArray, index: Long): ByteArray =
    ByteBuffer.allocate(GCM_NONCE_BYTES).order(ByteOrder.BIG_ENDIAN).put(prefix).putLong(index).array()

  private fun encodeHeader(salt: ByteArray, noncePrefix: ByteArray): ByteArray =
    ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
      .put(MAGIC)
      .put(FORMAT_VERSION.toByte())
      .put(KDF_ARGON2ID.toByte())
      .putInt(KdfParameters.DEFAULT.memoryKiB)
      .putInt(KdfParameters.DEFAULT.iterations)
      .putInt(KdfParameters.DEFAULT.parallelism)
      .put(KdfParameters.DEFAULT.argonVersion.toByte())
      .put(salt)
      .put(noncePrefix)
      .putInt(CHUNK_BYTES)
      .array()

  private fun decodeHeader(raw: ByteArray): ParsedHeader {
    if (raw.size != HEADER_BYTES) throw InvalidBackupContainerException("Malformed backup header")
    try {
      val input = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
      val magic = ByteArray(MAGIC_BYTES).also(input::get)
      if (!magic.contentEquals(MAGIC)) throw InvalidBackupContainerException("Invalid backup magic")
      val version = input.get().toInt() and 0xff
      if (version != FORMAT_VERSION) throw InvalidBackupContainerException("Unsupported backup format version")
      if ((input.get().toInt() and 0xff) != KDF_ARGON2ID) throw InvalidBackupContainerException("Unsupported backup KDF")
      val memoryKiB = input.int
      val iterations = input.int
      val parallelism = input.int
      val argonVersion = input.get().toInt() and 0xff
      val saltBytes = ByteArray(SALT_BYTES).also(input::get)
      val prefix = ByteArray(NONCE_PREFIX_BYTES).also(input::get)
      val chunkBytes = input.int
      if (chunkBytes != CHUNK_BYTES || input.hasRemaining()) {
        MemoryWipe.bytes(saltBytes)
        MemoryWipe.bytes(prefix)
        throw InvalidBackupContainerException("Unsupported backup chunk profile")
      }
      val parameters = try {
        KdfParameters(
          memoryKiB = memoryKiB,
          iterations = iterations,
          parallelism = parallelism,
          outputBytes = KEY_BYTES,
          argonVersion = argonVersion,
        ).also(KdfParameters::validateForKeyWrapper)
      } catch (failure: Exception) {
        MemoryWipe.bytes(saltBytes)
        MemoryWipe.bytes(prefix)
        throw InvalidBackupContainerException("Invalid backup KDF parameters", failure)
      }
      return ParsedHeader(raw.clone(), parameters, CryptoBytes.of(saltBytes), prefix)
        .also { MemoryWipe.bytes(saltBytes) }
    } catch (failure: BackupContainerException) {
      throw failure
    } catch (failure: Exception) {
      throw InvalidBackupContainerException("Malformed backup header", failure)
    }
  }

  private fun encodeFrameHeader(type: Int, index: Long, plaintextLength: Int): ByteArray =
    ByteBuffer.allocate(FRAME_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
      .put(type.toByte())
      .putLong(index)
      .putInt(plaintextLength)
      .array()

  private fun decodeFrameHeader(raw: ByteArray): ParsedFrame {
    if (raw.size != FRAME_HEADER_BYTES) throw InvalidBackupContainerException("Malformed backup record")
    val input = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
    return ParsedFrame(input.get().toInt() and 0xff, input.long, input.int)
  }

  private fun validatePassword(password: CharArray) {
    require(password.isNotEmpty() && password.size <= MAX_PASSWORD_CHARS) { "Backup password length is invalid" }
  }

  private fun validateContainerLimit(maxContainerBytes: Long) {
    require(maxContainerBytes in MIN_CONTAINER_BYTES..MAX_CONTAINER_BYTES) { "Backup size limit is invalid" }
  }

  private fun readChunk(input: InputStream, buffer: ByteArray): Int {
    var count = 0
    while (count < buffer.size) {
      val read = input.read(buffer, count, buffer.size - count)
      if (read < 0) break
      if (read == 0) {
        val one = input.read()
        if (one < 0) break
        buffer[count++] = one.toByte()
      } else {
        count += read
      }
    }
    return count
  }

  private fun checkedAdd(left: Long, right: Long, message: String): Long =
    try {
      Math.addExact(left, right)
    } catch (failure: ArithmeticException) {
      throw InvalidBackupContainerException(message, failure)
    }

  private fun nextIndex(current: Long): Long {
    if (current == Long.MAX_VALUE) throw InvalidBackupContainerException("Backup record index overflow")
    return current + 1L
  }

  private data class ParsedHeader(
    val bytes: ByteArray,
    val parameters: KdfParameters,
    val salt: CryptoBytes,
    val noncePrefix: ByteArray,
  )

  private data class ParsedFrame(val type: Int, val index: Long, val plaintextLength: Int)

  private data class ProcessedContainer(val summary: BackupContainerSummary, val fingerprint: ByteArray)

  private class BoundedDigestInput(
    private val source: InputStream,
    private val maxBytes: Long,
    private val digest: MessageDigest,
  ) {
    var bytesRead: Long = 0L
      private set

    fun readFully(size: Int): ByteArray {
      if (size < 0) throw InvalidBackupContainerException("Negative backup field length")
      if (size.toLong() > maxBytes - bytesRead) {
        throw InvalidBackupContainerException("Backup field exceeds the remaining size limit")
      }
      val result = ByteArray(size)
      var offset = 0
      while (offset < size) {
        val count = source.read(result, offset, size - offset)
        if (count < 0) {
          MemoryWipe.bytes(result)
          throw InvalidBackupContainerException("Backup container is truncated", EOFException())
        }
        if (count == 0) {
          val one = source.read()
          if (one < 0) {
            MemoryWipe.bytes(result)
            throw InvalidBackupContainerException("Backup container is truncated", EOFException())
          }
          result[offset++] = one.toByte()
          consume(1, result, offset - 1)
        } else {
          consume(count, result, offset)
          offset += count
        }
      }
      return result
    }

    fun requireEndOfInput() {
      val one = source.read()
      if (one >= 0) {
        consume(1, byteArrayOf(one.toByte()), 0)
        throw InvalidBackupContainerException("Trailing bytes after authenticated FINAL record")
      }
    }

    fun finishDigest(): ByteArray = digest.digest()

    private fun consume(count: Int, bytes: ByteArray, offset: Int) {
      if (count.toLong() > maxBytes - bytesRead) throw InvalidBackupContainerException("Backup container exceeds its size limit")
      bytesRead += count.toLong()
      digest.update(bytes, offset, count)
    }
  }

  companion object {
    const val CHUNK_BYTES = 4 * 1024 * 1024
    const val MAX_DATA_CHUNKS = 4_096L
    const val MAX_PLAINTEXT_BYTES = CHUNK_BYTES.toLong() * MAX_DATA_CHUNKS
    const val HEADER_BYTES = 43

    private const val MAGIC_BYTES = 4
    private const val FORMAT_VERSION = 1
    private const val KDF_ARGON2ID = 1
    private const val SALT_BYTES = 16
    private const val NONCE_PREFIX_BYTES = 4
    private const val NONCE_INDEX_BYTES = 8
    private const val GCM_NONCE_BYTES = NONCE_PREFIX_BYTES + NONCE_INDEX_BYTES
    private const val GCM_TAG_BYTES = 16
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private const val FRAME_HEADER_BYTES = 1 + NONCE_INDEX_BYTES + 4
    private const val TYPE_DATA = 1
    private const val TYPE_FINAL = 2
    private const val KEY_BYTES = 32
    private const val MAX_PASSWORD_CHARS = 4_096
    private const val SHA_256 = "SHA-256"
    private const val AES_ALGORITHM = "AES"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private val MAGIC = "ANBK".toByteArray(Charsets.US_ASCII)
    private val AAD_DOMAIN = "AnAnNotes/BackupContainer/v1".toByteArray(Charsets.US_ASCII)
    internal val MAX_CONTAINER_BYTES = HEADER_BYTES.toLong() + MAX_PLAINTEXT_BYTES +
      (FRAME_HEADER_BYTES.toLong() + GCM_TAG_BYTES) * (MAX_DATA_CHUNKS + 1L)
    private val MIN_CONTAINER_BYTES = HEADER_BYTES.toLong() + FRAME_HEADER_BYTES + GCM_TAG_BYTES
  }
}

/** Short-lived authorization produced only after a complete verification pass. */
class VerifiedBackup internal constructor(
  key: ByteArray,
  header: ByteArray,
  fingerprint: ByteArray,
  val summary: BackupContainerSummary,
  internal val maxContainerBytes: Long,
) : AutoCloseable {
  private val monitor = Any()
  private val backupKey = key
  internal val header: ByteArray = header.clone()
  internal val fingerprint = CryptoBytes.of(fingerprint)
  @Volatile private var closed = false

  val plaintextBytes: Long get() = summary.plaintextBytes
  val chunkCount: Long get() = summary.chunkCount

  internal fun <T> withKey(block: (ByteArray) -> T): T = synchronized(monitor) {
    check(!closed) { "Verified backup capability is closed" }
    block(backupKey)
  }

  override fun close() = synchronized(monitor) {
    if (!closed) {
      closed = true
      MemoryWipe.bytes(backupKey)
    }
  }
}


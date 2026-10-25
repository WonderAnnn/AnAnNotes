package com.anan.anannotes.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

enum class CredentialKind(val wireId: Int) {
  PASSWORD(1),
  RECOVERY(2),
  ;

  companion object {
    fun fromWireId(value: Int): CredentialKind = entries.firstOrNull { it.wireId == value }
      ?: throw IllegalArgumentException("Unknown credential wrapper")
  }
}

/** Argon2id v1.3 parameters. Values are stored with every wrapper for future format migration. */
data class KdfParameters(
  val memoryKiB: Int,
  val iterations: Int,
  val parallelism: Int,
  val outputBytes: Int = 32,
  val argonVersion: Int = ARGON2_VERSION_13,
) {
  init {
    require(memoryKiB in MIN_MEMORY_KIB..MAX_MEMORY_KIB) { "KDF memory is outside the accepted bounds" }
    require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "KDF iterations are outside the accepted bounds" }
    require(parallelism in MIN_PARALLELISM..MAX_PARALLELISM) { "KDF parallelism is outside the accepted bounds" }
    require(memoryKiB >= 8 * parallelism) { "KDF memory is too small for the selected parallelism" }
    require(outputBytes in MIN_OUTPUT_BYTES..MAX_OUTPUT_BYTES) { "KDF output length is outside the accepted bounds" }
    require(argonVersion == ARGON2_VERSION_13) { "Unsupported Argon2 version" }
  }

  internal fun validateForKeyWrapper() {
    require(this == DEFAULT) { "Unsupported V1 key-wrapper KDF profile" }
  }

  companion object {
    const val ARGON2_VERSION_13 = 0x13
    const val MIN_MEMORY_KIB = 8
    const val MAX_MEMORY_KIB = 256 * 1024
    const val MIN_ITERATIONS = 1
    const val MAX_ITERATIONS = 10
    const val MIN_PARALLELISM = 1
    const val MAX_PARALLELISM = 8
    const val MIN_OUTPUT_BYTES = 16
    const val MAX_OUTPUT_BYTES = 64
    val DEFAULT = KdfParameters(
      memoryKiB = 64 * 1024,
      iterations = 3,
      parallelism = 4,
    )
  }
}

data class KeyWrapEnvelope(
  val kind: CredentialKind,
  val parameters: KdfParameters,
  val salt: CryptoBytes,
  val nonce: CryptoBytes,
  val wrappedKey: CryptoBytes,
)

data class KeyEnvelope(
  val version: Int,
  val passwordWrap: KeyWrapEnvelope,
  val recoveryWrap: KeyWrapEnvelope?,
)

/** Strict bounded binary encoding for the private data-key envelope. */
object KeyEnvelopeCodec {
  private const val MAGIC = 0x414E4B45 // ANKE
  private const val FORMAT_VERSION = 1
  private const val SALT_BYTES = 16
  private const val NONCE_BYTES = 12
  private const val WRAPPED_KEY_BYTES = PrivateCrypto.DATA_KEY_BYTES + PrivateCrypto.GCM_TAG_BYTES
  private const val MAX_ENCODED_BYTES = 256

  fun encode(envelope: KeyEnvelope): ByteArray {
    validateEnvelope(envelope)
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
      data.writeInt(MAGIC)
      data.writeInt(envelope.version)
      data.writeBoolean(envelope.recoveryWrap != null)
      writeWrap(data, envelope.passwordWrap)
      envelope.recoveryWrap?.let { writeWrap(data, it) }
    }
    return output.toByteArray().also { check(it.size <= MAX_ENCODED_BYTES) }
  }

  fun decode(encoded: ByteArray): KeyEnvelope {
    require(encoded.size in MIN_ENCODED_BYTES..MAX_ENCODED_BYTES) { "Malformed key envelope length" }
    try {
      DataInputStream(ByteArrayInputStream(encoded)).use { data ->
        require(data.readInt() == MAGIC) { "Invalid key envelope magic" }
        val version = data.readInt()
        if (version != FORMAT_VERSION) throw UnsupportedPrivateFormatException(version)
        val hasRecovery = data.readBoolean()
        val password = readWrap(data)
        require(password.kind == CredentialKind.PASSWORD) { "Password wrapper is missing" }
        val recovery = if (hasRecovery) readWrap(data) else null
        require(recovery == null || recovery.kind == CredentialKind.RECOVERY) { "Invalid recovery wrapper" }
        require(data.available() == 0) { "Trailing bytes in key envelope" }
        return KeyEnvelope(version, password, recovery).also(::validateEnvelope)
      }
    } catch (unsupported: UnsupportedPrivateFormatException) {
      throw unsupported
    } catch (invalid: IllegalArgumentException) {
      throw invalid
    } catch (failure: Exception) {
      throw IllegalArgumentException("Malformed key envelope", failure)
    }
  }

  private fun validateEnvelope(envelope: KeyEnvelope) {
    if (envelope.version != FORMAT_VERSION) throw UnsupportedPrivateFormatException(envelope.version)
    require(envelope.passwordWrap.kind == CredentialKind.PASSWORD) { "Password wrapper is missing" }
    require(envelope.recoveryWrap == null || envelope.recoveryWrap.kind == CredentialKind.RECOVERY) {
      "Invalid recovery wrapper"
    }
    listOfNotNull(envelope.passwordWrap, envelope.recoveryWrap).forEach { wrap ->
      wrap.parameters.validateForKeyWrapper()
      require(wrap.salt.size == SALT_BYTES) { "Invalid KDF salt length" }
      require(wrap.nonce.size == NONCE_BYTES) { "Invalid GCM nonce length" }
      require(wrap.wrappedKey.size == WRAPPED_KEY_BYTES) { "Invalid wrapped data key length" }
    }
  }

  private fun writeWrap(output: DataOutputStream, wrap: KeyWrapEnvelope) {
    output.writeByte(wrap.kind.wireId)
    output.writeInt(wrap.parameters.memoryKiB)
    output.writeInt(wrap.parameters.iterations)
    output.writeInt(wrap.parameters.parallelism)
    output.writeInt(wrap.parameters.outputBytes)
    output.writeInt(wrap.parameters.argonVersion)
    output.write(wrap.salt.copyBytes())
    output.write(wrap.nonce.copyBytes())
    output.write(wrap.wrappedKey.copyBytes())
  }

  private fun readWrap(input: DataInputStream): KeyWrapEnvelope {
    val kind = CredentialKind.fromWireId(input.readUnsignedByte())
    val parameters = KdfParameters(
      memoryKiB = input.readInt(),
      iterations = input.readInt(),
      parallelism = input.readInt(),
      outputBytes = input.readInt(),
      argonVersion = input.readInt(),
    ).also(KdfParameters::validateForKeyWrapper)
    val salt = ByteArray(SALT_BYTES).also(input::readFully)
    val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
    val wrappedKey = ByteArray(WRAPPED_KEY_BYTES).also(input::readFully)
    return KeyWrapEnvelope(kind, parameters, CryptoBytes.of(salt), CryptoBytes.of(nonce), CryptoBytes.of(wrappedKey))
      .also {
        MemoryWipe.bytes(salt)
        MemoryWipe.bytes(nonce)
        MemoryWipe.bytes(wrappedKey)
      }
  }

  private const val MIN_ENCODED_BYTES = 4 + 4 + 1 + 1 + 5 * 4 + 16 + 12 + WRAPPED_KEY_BYTES
}

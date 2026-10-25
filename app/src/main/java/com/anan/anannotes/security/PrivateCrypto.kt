package com.anan.anannotes.security

import org.bouncycastle.crypto.PBEParametersGenerator
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PrivateRecordContext(
  val recordId: String,
  val blockType: String,
  val formatVersion: Int,
)

data class EncryptedPrivateRecord(
  val version: Int,
  val nonce: CryptoBytes,
  val ciphertext: CryptoBytes,
)

class PrivateAuthenticationException : Exception("Private data authentication failed")

class UnsupportedPrivateFormatException(version: Int) :
  IllegalArgumentException("Unsupported private data format version: $version")

class PrivateCryptoException(cause: Throwable) : IllegalStateException("Private cryptographic operation failed", cause)

internal fun interface KeyDeriver {
  fun derive(credential: CharArray, salt: CryptoBytes, parameters: KdfParameters): ByteArray
}

/** Key wrapping and authenticated record encryption; raw key arrays remain caller-owned. */
class PrivateCrypto internal constructor(
  private val random: SecureRandom,
  private val keyDeriver: KeyDeriver,
) {
  constructor() : this(SecureRandom(), Argon2idKeyDeriver())

  fun createKeyEnvelope(
    dataKey: ByteArray,
    password: CharArray,
    recoveryKey: CharArray?,
    parameters: KdfParameters = KdfParameters.DEFAULT,
  ): KeyEnvelope {
    try {
      require(dataKey.size == DATA_KEY_BYTES) { "Data key must be 256 bits" }
      validateCredential(password)
      recoveryKey?.let(::validateCredential)
      parameters.validateForKeyWrapper()
      val passwordWrap = wrap(dataKey, password, CredentialKind.PASSWORD, parameters)
      val recoveryWrap = recoveryKey?.let { wrap(dataKey, it, CredentialKind.RECOVERY, parameters) }
      return KeyEnvelope(KEY_ENVELOPE_VERSION, passwordWrap, recoveryWrap)
    } finally {
      MemoryWipe.chars(password)
      MemoryWipe.chars(recoveryKey)
    }
  }

  @Throws(PrivateAuthenticationException::class)
  fun unlockDataKey(envelope: KeyEnvelope, credential: CharArray, kind: CredentialKind): ByteArray {
    try {
      if (envelope.version != KEY_ENVELOPE_VERSION) throw UnsupportedPrivateFormatException(envelope.version)
      val wrapper = when (kind) {
        CredentialKind.PASSWORD -> envelope.passwordWrap.takeIf { it.kind == kind }
        CredentialKind.RECOVERY -> envelope.recoveryWrap?.takeIf { it.kind == kind }
      } ?: throw PrivateAuthenticationException()
      validateCredential(credential)
      wrapper.parameters.validateForKeyWrapper()
      requireWrapperShape(wrapper)
      val derivedKey = keyDeriver.derive(credential, wrapper.salt, wrapper.parameters)
      val nonce = wrapper.nonce.copyBytes()
      val ciphertext = wrapper.wrappedKey.copyBytes()
      val aad = wrapAssociatedData(envelope.version, wrapper)
      try {
        val unwrapped = decryptAesGcm(derivedKey, nonce, ciphertext, aad)
        if (unwrapped.size != DATA_KEY_BYTES) {
          MemoryWipe.bytes(unwrapped)
          throw IllegalArgumentException("Invalid unwrapped key length")
        }
        return unwrapped
      } finally {
        MemoryWipe.bytes(derivedKey)
        MemoryWipe.bytes(nonce)
        MemoryWipe.bytes(ciphertext)
        MemoryWipe.bytes(aad)
      }
    } catch (failure: PrivateAuthenticationException) {
      throw failure
    } catch (failure: UnsupportedPrivateFormatException) {
      throw failure
    } catch (_: Exception) {
      throw PrivateAuthenticationException()
    } finally {
      MemoryWipe.chars(credential)
    }
  }

  fun encrypt(dataKey: ByteArray, plaintext: ByteArray, context: PrivateRecordContext): EncryptedPrivateRecord {
    require(dataKey.size == DATA_KEY_BYTES) { "Data key must be 256 bits" }
    validateContext(context)
    val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
    val ciphertext = try {
      encryptAesGcm(dataKey, nonce, plaintext, recordAssociatedData(context))
    } catch (failure: GeneralSecurityException) {
      throw PrivateCryptoException(failure)
    }
    return EncryptedPrivateRecord(RECORD_FORMAT_VERSION, CryptoBytes.of(nonce), CryptoBytes.of(ciphertext))
      .also {
        MemoryWipe.bytes(nonce)
        MemoryWipe.bytes(ciphertext)
      }
  }

  /**
   * Encrypts a known-length stream directly to a caller-owned temporary destination. The caller
   * writes its record header from [writeHeader] and must publish the destination only after this
   * method returns; partial ciphertext is not a valid record.
   */
  internal fun encryptStream(
    dataKey: ByteArray,
    plaintext: InputStream,
    destination: OutputStream,
    expectedPlaintextBytes: Long,
    expectedSha256: ByteArray,
    context: PrivateRecordContext,
    writeHeader: (nonce: ByteArray, ciphertextBytes: Int) -> Unit,
  ) {
    require(dataKey.size == DATA_KEY_BYTES) { "Data key must be 256 bits" }
    validateContext(context)
    require(expectedPlaintextBytes in 1..(Int.MAX_VALUE - GCM_TAG_BYTES).toLong()) {
      "Expected private stream length is invalid"
    }
    require(expectedSha256.size == SHA256_BYTES) { "Expected stream digest must be SHA-256" }

    val expectedDigest = expectedSha256.clone()
    val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
    val associatedData = recordAssociatedData(context)
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    val digest = MessageDigest.getInstance(SHA_256)
    try {
      val cipher = try {
        aesGcmCipher(Cipher.ENCRYPT_MODE, dataKey, nonce, associatedData)
      } catch (failure: GeneralSecurityException) {
        throw PrivateCryptoException(failure)
      }
      writeHeader(nonce, Math.addExact(expectedPlaintextBytes, GCM_TAG_BYTES.toLong()).toInt())

      var consumed = 0L
      while (consumed < expectedPlaintextBytes) {
        val requested = minOf(buffer.size.toLong(), expectedPlaintextBytes - consumed).toInt()
        var count = plaintext.read(buffer, 0, requested)
        if (count < 0) throw IOException("Private attachment ended before its declared length")
        if (count == 0) {
          val next = plaintext.read()
          if (next < 0) throw IOException("Private attachment ended before its declared length")
          buffer[0] = next.toByte()
          count = 1
        }
        consumed += count.toLong()
        digest.update(buffer, 0, count)
        val encrypted = try {
          cipher.update(buffer, 0, count)
        } catch (failure: RuntimeException) {
          throw PrivateCryptoException(failure)
        }
        if (encrypted != null) {
          try {
            destination.write(encrypted)
          } finally {
            MemoryWipe.bytes(encrypted)
          }
        }
      }
      if (plaintext.read() != -1) throw IOException("Private attachment exceeds its declared length")
      val actualDigest = digest.digest()
      try {
        if (!MessageDigest.isEqual(actualDigest, expectedDigest)) {
          throw IOException("Private attachment SHA-256 does not match its declared digest")
        }
      } finally {
        MemoryWipe.bytes(actualDigest)
      }

      val finalBytes = try {
        cipher.doFinal()
      } catch (failure: GeneralSecurityException) {
        throw PrivateCryptoException(failure)
      }
      try {
        destination.write(finalBytes)
      } finally {
        MemoryWipe.bytes(finalBytes)
      }
    } finally {
      MemoryWipe.bytes(expectedDigest)
      MemoryWipe.bytes(nonce)
      MemoryWipe.bytes(associatedData)
      MemoryWipe.bytes(buffer)
    }
  }

  @Throws(PrivateAuthenticationException::class)
  fun decrypt(dataKey: ByteArray, encrypted: EncryptedPrivateRecord, context: PrivateRecordContext): ByteArray {
    require(dataKey.size == DATA_KEY_BYTES) { "Data key must be 256 bits" }
    if (encrypted.version != RECORD_FORMAT_VERSION) throw UnsupportedPrivateFormatException(encrypted.version)
    validateContext(context)
    val nonce = encrypted.nonce.copyBytes()
    val ciphertext = encrypted.ciphertext.copyBytes()
    try {
      require(nonce.size == GCM_NONCE_BYTES && ciphertext.size >= GCM_TAG_BYTES) { "Malformed encrypted record" }
      return decryptAesGcm(dataKey, nonce, ciphertext, recordAssociatedData(context))
    } catch (failure: AEADBadTagException) {
      throw PrivateAuthenticationException()
    } catch (failure: PrivateAuthenticationException) {
      throw failure
    } catch (failure: GeneralSecurityException) {
      throw PrivateCryptoException(failure)
    } finally {
      MemoryWipe.bytes(nonce)
      MemoryWipe.bytes(ciphertext)
    }
  }

  private fun wrap(
    dataKey: ByteArray,
    credential: CharArray,
    kind: CredentialKind,
    parameters: KdfParameters,
  ): KeyWrapEnvelope {
    val salt = ByteArray(KDF_SALT_BYTES).also(random::nextBytes)
    val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
    val saltValue = CryptoBytes.of(salt)
    val wrapperWithoutCiphertext = KeyWrapEnvelope(
      kind = kind,
      parameters = parameters,
      salt = saltValue,
      nonce = CryptoBytes.of(nonce),
      wrappedKey = CryptoBytes.of(ByteArray(DATA_KEY_BYTES + GCM_TAG_BYTES)),
    )
    var derivedKey: ByteArray? = null
    try {
      val derived = keyDeriver.derive(credential, saltValue, parameters).also { derivedKey = it }
      val wrapped = encryptAesGcm(
        key = derived,
        nonce = nonce,
        plaintext = dataKey,
        aad = wrapAssociatedData(KEY_ENVELOPE_VERSION, wrapperWithoutCiphertext),
      )
      return wrapperWithoutCiphertext.copy(wrappedKey = CryptoBytes.of(wrapped)).also { MemoryWipe.bytes(wrapped) }
    } catch (failure: GeneralSecurityException) {
      throw PrivateCryptoException(failure)
    } finally {
      MemoryWipe.bytes(derivedKey)
      MemoryWipe.bytes(salt)
      MemoryWipe.bytes(nonce)
    }
  }

  private fun requireWrapperShape(wrapper: KeyWrapEnvelope) {
    require(wrapper.salt.size == KDF_SALT_BYTES)
    require(wrapper.nonce.size == GCM_NONCE_BYTES)
    require(wrapper.wrappedKey.size == DATA_KEY_BYTES + GCM_TAG_BYTES)
  }

  private fun encryptAesGcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
    aesGcmCipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

  private fun decryptAesGcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
    try {
      aesGcmCipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(ciphertext)
    } catch (failure: AEADBadTagException) {
      throw PrivateAuthenticationException()
    }

  private fun aesGcmCipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
    Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
      init(mode, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(GCM_TAG_BITS, nonce))
      updateAAD(aad)
    }

  private fun wrapAssociatedData(version: Int, wrapper: KeyWrapEnvelope): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out ->
      out.write(WRAP_AAD_DOMAIN)
      out.writeInt(version)
      out.writeByte(wrapper.kind.wireId)
      writeParameters(out, wrapper.parameters)
      out.write(wrapper.salt.copyBytes())
    }
    bytes.toByteArray()
  }

  private fun recordAssociatedData(context: PrivateRecordContext): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { out ->
      out.write(RECORD_AAD_DOMAIN)
      out.writeInt(context.formatVersion)
      writeBoundedUtf8(out, context.recordId)
      writeBoundedUtf8(out, context.blockType)
    }
    bytes.toByteArray()
  }

  private fun writeParameters(out: DataOutputStream, parameters: KdfParameters) {
    out.writeInt(parameters.memoryKiB)
    out.writeInt(parameters.iterations)
    out.writeInt(parameters.parallelism)
    out.writeInt(parameters.outputBytes)
    out.writeInt(parameters.argonVersion)
  }

  private fun writeBoundedUtf8(out: DataOutputStream, value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    require(encoded.isNotEmpty() && encoded.size <= MAX_CONTEXT_FIELD_BYTES) { "Invalid authenticated context field" }
    out.writeInt(encoded.size)
    out.write(encoded)
  }

  private fun validateContext(context: PrivateRecordContext) {
    require(context.formatVersion == RECORD_FORMAT_VERSION) { "Unsupported record context version" }
    require(context.recordId.isNotBlank() && context.blockType.isNotBlank()) { "Record context must be complete" }
    require(context.recordId.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_FIELD_BYTES)
    require(context.blockType.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_FIELD_BYTES)
  }

  private fun validateCredential(credential: CharArray) {
    require(credential.isNotEmpty() && credential.size <= MAX_CREDENTIAL_CHARS) { "Credential length is invalid" }
  }

  companion object {
    const val DATA_KEY_BYTES = 32
    const val GCM_TAG_BYTES = 16
    private const val SHA256_BYTES = 32
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val SHA_256 = "SHA-256"
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
    private const val KDF_SALT_BYTES = 16
    private const val KEY_ENVELOPE_VERSION = 1
    private const val RECORD_FORMAT_VERSION = 1
    private const val MAX_CONTEXT_FIELD_BYTES = 4096
    private const val MAX_CREDENTIAL_CHARS = 4096
    private const val AES_ALGORITHM = "AES"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private val WRAP_AAD_DOMAIN = "AnAnNotes/PrivateKeyWrap/v1".toByteArray(Charsets.US_ASCII)
    private val RECORD_AAD_DOMAIN = "AnAnNotes/PrivateRecord/v1".toByteArray(Charsets.US_ASCII)
  }
}

/** RFC 9106 Argon2id v1.3 implementation using Bouncy Castle's lightweight API. */
internal class Argon2idKeyDeriver : KeyDeriver {
  override fun derive(credential: CharArray, salt: CryptoBytes, parameters: KdfParameters): ByteArray =
    deriveWithAssociatedValues(credential, salt, parameters, secret = null, additional = null)

  internal fun deriveWithAssociatedValues(
    credential: CharArray,
    salt: CryptoBytes,
    parameters: KdfParameters,
    secret: CryptoBytes?,
    additional: CryptoBytes?,
  ): ByteArray {
    val saltBytes = salt.copyBytes()
    val passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(credential)
    val secretBytes = secret?.copyBytes()
    val additionalBytes = additional?.copyBytes()
    val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(parameters.argonVersion)
      .withIterations(parameters.iterations)
      .withMemoryAsKB(parameters.memoryKiB)
      .withParallelism(parameters.parallelism)
      .withSalt(saltBytes)
      .also { argonBuilder ->
        secretBytes?.let(argonBuilder::withSecret)
        additionalBytes?.let(argonBuilder::withAdditional)
      }
    val argonParameters = builder.build()
    val output = ByteArray(parameters.outputBytes)
    try {
      Argon2BytesGenerator().apply { init(argonParameters) }.generateBytes(passwordBytes, output)
      return output
    } catch (failure: Throwable) {
      MemoryWipe.bytes(output)
      throw failure
    } finally {
      MemoryWipe.bytes(passwordBytes)
      MemoryWipe.bytes(saltBytes)
      MemoryWipe.bytes(secretBytes)
      MemoryWipe.bytes(additionalBytes)
      argonParameters.clear()
      builder.clear()
    }
  }
}

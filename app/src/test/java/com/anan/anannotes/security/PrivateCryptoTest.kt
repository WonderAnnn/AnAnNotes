package com.anan.anannotes.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PrivateCryptoTest {
  private val testKdf = KeyDeriver { credential, salt, parameters ->
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt.copyBytes(), "HmacSHA256"))
    val credentialBytes = String(credential).toByteArray(Charsets.UTF_8)
    try {
      mac.doFinal(credentialBytes)
    } finally {
      credentialBytes.fill(0)
    }
  }

  private val crypto = PrivateCrypto(SecureRandom(), testKdf)
  private val fastParams = KdfParameters(memoryKiB = 65_536, iterations = 3, parallelism = 4)

  @Test
  fun passwordAndRecoveryWrappersIndependentlyUnlockSameDataKey() {
    val dataKey = ByteArray(PrivateCrypto.DATA_KEY_BYTES) { (it * 7).toByte() }
    val envelope = crypto.createKeyEnvelope(
      dataKey = dataKey,
      password = "correct horse battery staple".toCharArray(),
      recoveryKey = "ANAN-RECOVERY-KEY-01".toCharArray(),
      parameters = fastParams,
    )
    val decoded = KeyEnvelopeCodec.decode(KeyEnvelopeCodec.encode(envelope))

    assertArrayEquals(
      dataKey,
      crypto.unlockDataKey(decoded, "correct horse battery staple".toCharArray(), CredentialKind.PASSWORD),
    )
    assertArrayEquals(
      dataKey,
      crypto.unlockDataKey(decoded, "ANAN-RECOVERY-KEY-01".toCharArray(), CredentialKind.RECOVERY),
    )
  }

  @Test
  fun wrongPasswordAndRecoveryKeyUseUniformAuthenticationFailure() {
    val envelope = crypto.createKeyEnvelope(
      ByteArray(PrivateCrypto.DATA_KEY_BYTES) { it.toByte() },
      "right-password".toCharArray(),
      "right-recovery".toCharArray(),
      fastParams,
    )

    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.unlockDataKey(envelope, "wrong-password".toCharArray(), CredentialKind.PASSWORD)
    }
    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.unlockDataKey(envelope, "wrong-recovery".toCharArray(), CredentialKind.RECOVERY)
    }
    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.unlockDataKey(envelope, "right-password".toCharArray(), CredentialKind.RECOVERY)
    }
  }

  @Test
  fun wrongFormatVersionTamperedNonceAndWrappedKeyAreRejected() {
    val envelope = crypto.createKeyEnvelope(
      ByteArray(PrivateCrypto.DATA_KEY_BYTES) { 0x5a },
      "password".toCharArray(),
      null,
      fastParams,
    )
    val encoded = KeyEnvelopeCodec.encode(envelope)
    encoded[7] = 2 // The final byte of the fixed-width envelope version.
    assertThrows(UnsupportedPrivateFormatException::class.java) { KeyEnvelopeCodec.decode(encoded) }

    val wrap = envelope.passwordWrap
    val changedNonce = wrap.nonce.copyBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
    val tamperedNonce = envelope.copy(passwordWrap = wrap.copy(nonce = CryptoBytes.of(changedNonce)))
    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.unlockDataKey(tamperedNonce, "password".toCharArray(), CredentialKind.PASSWORD)
    }

    val changedCiphertext = wrap.wrappedKey.copyBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
    val tamperedCiphertext = envelope.copy(passwordWrap = wrap.copy(wrappedKey = CryptoBytes.of(changedCiphertext)))
    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.unlockDataKey(tamperedCiphertext, "password".toCharArray(), CredentialKind.PASSWORD)
    }

    val changedKdf = envelope.copy(passwordWrap = wrap.copy(parameters = fastParams.copy(iterations = 4)))
    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.unlockDataKey(changedKdf, "password".toCharArray(), CredentialKind.PASSWORD)
    }
  }

  @Test
  fun credentialBuffersAreConsumedAndWiped() {
    val password = "wipe-me".toCharArray()
    val recovery = "recovery-wipe-me".toCharArray()
    val envelope = crypto.createKeyEnvelope(
      ByteArray(PrivateCrypto.DATA_KEY_BYTES) { 0x22 },
      password,
      recovery,
      fastParams,
    )
    assertTrue(password.all { it == '\u0000' })
    assertTrue(recovery.all { it == '\u0000' })

    val unlockPassword = "wipe-me".toCharArray()
    crypto.unlockDataKey(envelope, unlockPassword, CredentialKind.PASSWORD)
    assertTrue(unlockPassword.all { it == '\u0000' })
  }

  @Test
  fun eachContentEncryptionUsesANewNonceAndRoundTripsWithBoundContext() {
    val key = ByteArray(PrivateCrypto.DATA_KEY_BYTES) { (it + 1).toByte() }
    val context = PrivateRecordContext(recordId = "note-42/block-7", blockType = "TEXT", formatVersion = 1)
    val plaintext = "私密正文\nsecond line".toByteArray(Charsets.UTF_8)

    val first = crypto.encrypt(key, plaintext, context)
    val second = crypto.encrypt(key, plaintext, context)

    assertNotEquals(first.nonce, second.nonce)
    assertArrayEquals(plaintext, crypto.decrypt(key, first, context))
    assertArrayEquals(plaintext, crypto.decrypt(key, second, context))
    assertThrows(PrivateAuthenticationException::class.java) {
      crypto.decrypt(key, first, context.copy(blockType = "IMAGE"))
    }
  }

  @Test
  fun tamperedCiphertextNeverReturnsPartialPlaintext() {
    val key = ByteArray(PrivateCrypto.DATA_KEY_BYTES) { 0x31 }
    val context = PrivateRecordContext("record-1", "TEXT", 1)
    val encrypted = crypto.encrypt(key, ByteArray(256 * 1024) { (it % 251).toByte() }, context)
    val tamperedBytes = encrypted.ciphertext.copyBytes().also {
      it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte()
    }

    val failure = assertThrows(PrivateAuthenticationException::class.java) {
      crypto.decrypt(key, encrypted.copy(ciphertext = CryptoBytes.of(tamperedBytes)), context)
    }
    assertTrue(failure.message.orEmpty().contains("authentication"))
  }

  @Test
  fun malformedKdfParametersAreRejectedBeforeDerivation() {
    assertThrows(IllegalArgumentException::class.java) {
      KdfParameters(memoryKiB = Int.MAX_VALUE, iterations = 3, parallelism = 4)
    }
    assertThrows(IllegalArgumentException::class.java) {
      KdfParameters(memoryKiB = 65_536, iterations = 0, parallelism = 4)
    }
    assertThrows(IllegalArgumentException::class.java) {
      KdfParameters(memoryKiB = 65_536, iterations = 2, parallelism = 4).validateForKeyWrapper()
    }
  }

  @Test
  fun rfc9106Argon2idVectorMatches() {
    val actual = Argon2idKeyDeriver().deriveWithAssociatedValues(
      credential = CharArray(32) { '\u0001' },
      salt = CryptoBytes.of(ByteArray(16) { 0x02 }),
      parameters = KdfParameters(memoryKiB = 32, iterations = 3, parallelism = 4, outputBytes = 32),
      secret = CryptoBytes.of(ByteArray(8) { 0x03 }),
      additional = CryptoBytes.of(ByteArray(12) { 0x04 }),
    )

    assertEquals(
      "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659",
      actual.joinToString("") { "%02x".format(it) },
    )
  }
}

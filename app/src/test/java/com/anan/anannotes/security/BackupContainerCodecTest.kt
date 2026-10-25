package com.anan.anannotes.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.bouncycastle.crypto.PBEParametersGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BackupContainerCodecTest {
  private val derivations = AtomicInteger()
  private val testKdf = KeyDeriver { credential, salt, parameters ->
    derivations.incrementAndGet()
    require(parameters == KdfParameters.DEFAULT)
    val saltBytes = salt.copyBytes()
    val passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(credential)
    try {
      Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(saltBytes, "HmacSHA256"))
        doFinal(passwordBytes)
      }
    } finally {
      MemoryWipe.bytes(saltBytes)
      MemoryWipe.bytes(passwordBytes)
    }
  }

  private val codec = BackupContainerCodec(SecureRandom(), testKdf)

  @Test
  fun encryptedContainerRoundTripsAcrossChunkBoundaryWithoutPlaintextOnWire() {
    val plaintext = ByteArray(BackupContainerCodec.CHUNK_BYTES + 37) { ((it * 31) % 251).toByte() }
    val marker = "ANAN_PRIVATE_BACKUP_SENTINEL".toByteArray(Charsets.UTF_8)
    marker.copyInto(plaintext, 73)
    val password = "独立备份密码".toCharArray()
    val encoded = encrypt(plaintext, password)

    assertTrue(password.all { it == '\u0000' })
    assertArrayEquals("ANBK".toByteArray(Charsets.US_ASCII), encoded.copyOfRange(0, 4))
    assertFalse("Backup wire bytes must not contain the plaintext marker", encoded.containsBytes(marker))

    val verifyPassword = "独立备份密码".toCharArray()
    val verified = codec.verify(ByteArrayInputStream(encoded), verifyPassword, maxContainerBytes = encoded.size.toLong())
    try {
      assertTrue(verifyPassword.all { it == '\u0000' })
      assertEquals(plaintext.size.toLong(), verified.plaintextBytes)
      assertEquals(2L, verified.chunkCount)

      val decrypted = StagingConsumer()
      codec.decryptVerified(verified, ByteArrayInputStream(encoded), decrypted)
      assertArrayEquals(plaintext, decrypted.published)
      assertTrue(decrypted.callbackBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
    } finally {
      verified.close()
    }
  }

  @Test
  fun emptyBackupUsesAnAuthenticatedFinalRecordAtTheFirstNonceIndex() {
    val fixedCodec = BackupContainerCodec(FixedSecureRandom(0x2a), testKdf)
    val output = ByteArrayOutputStream()
    fixedCodec.encrypt(ByteArrayInputStream(byteArrayOf()), output, "backup-password".toCharArray(), maxPlaintextBytes = 0)
    val encoded = output.toByteArray()
    val header = encoded.copyOfRange(0, 43)
    val finalHeader = encoded.copyOfRange(43, 56)
    assertArrayEquals(ByteArray(4) { 0x2a }, header.copyOfRange(35, 39))
    assertEquals(2, finalHeader[0].toInt() and 0xff)
    assertEquals(0L, ByteBuffer.wrap(finalHeader.copyOfRange(1, 9)).long)
    assertEquals(0, ByteBuffer.wrap(finalHeader.copyOfRange(9, 13)).int)

    val salt = header.copyOfRange(19, 35)
    val prefix = header.copyOfRange(35, 39)
    val passwordBytes = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes("backup-password".toCharArray())
    val expectedKey = Mac.getInstance("HmacSHA256").run {
      init(SecretKeySpec(salt, "HmacSHA256"))
      doFinal(passwordBytes)
    }
    val expectedNonce = ByteBuffer.allocate(12).put(prefix).putLong(0L).array()
    val expectedAad = "AnAnNotes/BackupContainer/v1".toByteArray(Charsets.US_ASCII) + header + finalHeader
    try {
      val finalPlaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(expectedKey, "AES"), GCMParameterSpec(128, expectedNonce))
        updateAAD(expectedAad)
        doFinal(encoded.copyOfRange(56, 72))
      }
      assertArrayEquals(byteArrayOf(), finalPlaintext)
    } finally {
      MemoryWipe.bytes(salt)
      MemoryWipe.bytes(prefix)
      MemoryWipe.bytes(passwordBytes)
      MemoryWipe.bytes(expectedKey)
      MemoryWipe.bytes(expectedNonce)
      MemoryWipe.bytes(expectedAad)
    }

    val verified = fixedCodec.verify(
      ByteArrayInputStream(encoded),
      "backup-password".toCharArray(),
      maxContainerBytes = encoded.size.toLong(),
    )
    try {
      assertEquals(0L, verified.chunkCount)
      assertEquals(0L, verified.plaintextBytes)
      val consumer = StagingConsumer()
      fixedCodec.decryptVerified(verified, ByteArrayInputStream(encoded), consumer)
      assertArrayEquals(byteArrayOf(), consumer.published)
    } finally {
      verified.close()
    }
  }

  @Test
  fun wrongPasswordTamperingTruncationAndTrailingBytesAreRejectedDuringVerification() {
    val encoded = encrypt("restore only after complete verification".toByteArray(), "backup-password".toCharArray())

    val wrongPassword = "incorrect".toCharArray()
    assertThrows(BackupAuthenticationException::class.java) {
      codec.verify(ByteArrayInputStream(encoded), wrongPassword, encoded.size.toLong())
    }
    assertTrue(wrongPassword.all { it == '\u0000' })

    val changedChunk = encoded.clone().also { it[56 + 4] = (it[56 + 4].toInt() xor 0x20).toByte() }
    assertThrows(BackupAuthenticationException::class.java) {
      codec.verify(ByteArrayInputStream(changedChunk), "backup-password".toCharArray(), changedChunk.size.toLong())
    }

    val truncated = encoded.copyOf(encoded.size - 1)
    assertThrows(BackupContainerException::class.java) {
      codec.verify(ByteArrayInputStream(truncated), "backup-password".toCharArray(), encoded.size.toLong())
    }

    val trailing = encoded + byteArrayOf(0x01)
    assertThrows(BackupContainerException::class.java) {
      codec.verify(ByteArrayInputStream(trailing), "backup-password".toCharArray(), trailing.size.toLong())
    }
  }

  @Test
  fun malformedKdfParametersAreRejectedBeforeRunningArgonDerivation() {
    val encoded = encrypt(byteArrayOf(1, 2, 3), "backup-password".toCharArray())
    val derivationsAfterWrite = derivations.get()
    val hostileHeader = encoded.clone().also { ByteBuffer.wrap(it).putInt(6, Int.MAX_VALUE) }

    assertThrows(InvalidBackupContainerException::class.java) {
      codec.verify(ByteArrayInputStream(hostileHeader), "backup-password".toCharArray(), hostileHeader.size.toLong())
    }
    assertEquals("Untrusted KDF parameters must be rejected before deriving a key", derivationsAfterWrite, derivations.get())
  }

  @Test
  fun operationByteLimitRejectsAChunkBeforeReadingItsPayload() {
    val encoded = encrypt(ByteArray(BackupContainerCodec.CHUNK_BYTES + 1) { 0x44 }, "backup-password".toCharArray())
    val source = CountingInputStream(encoded)

    assertThrows(InvalidBackupContainerException::class.java) {
      codec.verify(source, "backup-password".toCharArray(), maxContainerBytes = 72L)
    }

    assertEquals("Only the 43-byte header and 13-byte record header may be consumed", 56, source.bytesRead)
  }

  @Test
  fun fragmentedStreamsAndIntermittentZeroLengthReadsRoundTrip() {
    val plaintext = ByteArray(321) { (it * 17).toByte() }
    val output = ByteArrayOutputStream()
    codec.encrypt(
      FragmentedInputStream(plaintext),
      output,
      "backup-password".toCharArray(),
      maxPlaintextBytes = plaintext.size.toLong(),
    )
    val encoded = output.toByteArray()
    val verified = codec.verify(
      FragmentedInputStream(encoded),
      "backup-password".toCharArray(),
      maxContainerBytes = encoded.size.toLong(),
    )
    try {
      val consumer = StagingConsumer()
      codec.decryptVerified(verified, FragmentedInputStream(encoded), consumer)
      assertArrayEquals(plaintext, consumer.published)
    } finally {
      verified.close()
    }
  }

  @Test
  fun duplicateOrReorderedChunkIndicesAreRejected() {
    val encoded = encrypt(ByteArray(BackupContainerCodec.CHUNK_BYTES + 1) { 0x12 }, "backup-password".toCharArray())
    val duplicateSecondIndex = encoded.clone().also {
      val secondFrameHeaderOffset = 43 + 13 + BackupContainerCodec.CHUNK_BYTES + 16
      ByteBuffer.wrap(it).putLong(secondFrameHeaderOffset + 1, 0L)
    }

    assertThrows(InvalidBackupContainerException::class.java) {
      codec.verify(
        ByteArrayInputStream(duplicateSecondIndex),
        "backup-password".toCharArray(),
        duplicateSecondIndex.size.toLong(),
      )
    }
  }

  @Test
  fun modificationBetweenVerificationAndConsumptionAbortsTheStagingConsumer() {
    val plaintext = ByteArray(BackupContainerCodec.CHUNK_BYTES + 19) { (it % 127).toByte() }
    val encoded = encrypt(plaintext, "backup-password".toCharArray())
    val verified = codec.verify(
      ByteArrayInputStream(encoded),
      "backup-password".toCharArray(),
      maxContainerBytes = encoded.size.toLong(),
    )
    val replacement = encoded.clone().also {
      val secondChunkCiphertextOffset = 43 + 13 + BackupContainerCodec.CHUNK_BYTES + 16 + 13
      it[secondChunkCiphertextOffset] = (it[secondChunkCiphertextOffset].toInt() xor 0x01).toByte()
    }
    val consumer = StagingConsumer()
    try {
      assertThrows(BackupAuthenticationException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(replacement), consumer)
      }
      assertTrue(consumer.aborted)
      assertFalse(consumer.committed)
      assertEquals(null, consumer.published)
      assertTrue(consumer.callbackBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
    } finally {
      verified.close()
    }
  }

  @Test
  fun independentlyAuthenticatedReplacementWithTheSameHeaderIsRejectedByFingerprint() {
    val firstCodec = BackupContainerCodec(FixedSecureRandom(0x31), testKdf)
    val replacementCodec = BackupContainerCodec(FixedSecureRandom(0x31), testKdf)
    val firstPlaintext = "verified payload A".toByteArray(Charsets.UTF_8)
    val replacementPlaintext = "verified payload B".toByteArray(Charsets.UTF_8)
    val first = encrypt(firstCodec, firstPlaintext)
    val replacement = encrypt(replacementCodec, replacementPlaintext)
    assertArrayEquals(first.copyOfRange(0, 43), replacement.copyOfRange(0, 43))
    assertEquals(first.size, replacement.size)
    assertFalse(first.contentEquals(replacement))

    val verified = firstCodec.verify(
      ByteArrayInputStream(first),
      "backup-password".toCharArray(),
      first.size.toLong(),
    )
    val consumer = StagingConsumer()
    try {
      val failure = assertThrows(InvalidBackupContainerException::class.java) {
        firstCodec.decryptVerified(verified, ByteArrayInputStream(replacement), consumer)
      }
      assertEquals("Verified backup changed during restore", failure.message)
      assertTrue(consumer.aborted)
      assertFalse(consumer.committed)
      assertEquals(null, consumer.published)
      assertTrue(consumer.stagedBuffersAtAbort.isNotEmpty())
      assertTrue(consumer.stagedBuffersAtAbort.all { buffer -> buffer.all { it == 0.toByte() } })
      assertTrue(consumer.callbackBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
    } finally {
      verified.close()
    }
  }

  @Test
  fun commitAndAbortFailuresPreserveThePrimaryFailureAndCleanupFailure() {
    val encoded = encrypt("transaction".toByteArray(), "backup-password".toCharArray())
    val verified = codec.verify(
      ByteArrayInputStream(encoded),
      "backup-password".toCharArray(),
      encoded.size.toLong(),
    )
    val consumer = StagingConsumer(failCommit = true, failAbort = true)
    try {
      val failure = assertThrows(IllegalStateException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(encoded), consumer)
      }
      assertEquals("consumer commit failed", failure.message)
      assertEquals("consumer abort failed", failure.suppressed.single().message)
      assertTrue(consumer.aborted)
      assertEquals(null, consumer.published)
      assertTrue(consumer.stagedBuffersAtAbort.isNotEmpty())
      assertTrue(consumer.stagedBuffersAtAbort.all { buffer -> buffer.all { it == 0.toByte() } })
    } finally {
      verified.close()
    }
  }

  @Test
  fun callbackFailureAbortsAndWipesPendingPlaintext() {
    val encoded = encrypt("callback failure".toByteArray(), "backup-password".toCharArray())
    val verified = codec.verify(
      ByteArrayInputStream(encoded),
      "backup-password".toCharArray(),
      encoded.size.toLong(),
    )
    val consumer = StagingConsumer(failOnChunk = true)
    try {
      assertThrows(IllegalStateException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(encoded), consumer)
      }
      assertTrue(consumer.aborted)
      assertFalse(consumer.committed)
      assertEquals(null, consumer.published)
      assertTrue(consumer.stagedBuffers.isEmpty())
      assertTrue(consumer.stagedBuffersAtAbort.isNotEmpty())
      assertTrue(consumer.stagedBuffersAtAbort.all { buffer -> buffer.all { it == 0.toByte() } })
      assertTrue(consumer.callbackBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
    } finally {
      verified.close()
    }
  }

  @Test
  fun authenticatedAnbkChunksAreParsedIncrementallyAndCommittedOnlyAfterBothTerminators() {
    val payload = ByteArrayOutputStream().also { output ->
      val writer = BackupRecordStreamWriter(output, maxStreamBytes = 8L * 1024L * 1024L)
      val asset = ByteArray(BackupContainerCodec.CHUNK_BYTES + 73) { (it * 7).toByte() }
      writer.writeRecord(
        BackupRecordDomain.PRIVATE,
        BackupRecordKind.ASSET,
        schemaVersion = 1,
        payloadBytes = asset.size.toLong(),
        payload = ByteArrayInputStream(asset),
      )
      writer.finish()
    }.toByteArray()
    val encrypted = encrypt(codec, payload, "backup-password".toCharArray())
    val verified = codec.verify(ByteArrayInputStream(encrypted), "backup-password".toCharArray(), encrypted.size.toLong())
    val sink = RecordingBackupRecordSink()
    val consumer = BackupRecordPayloadConsumer(sink, maxStreamBytes = payload.size.toLong())

    try {
      codec.decryptVerified(verified, ByteArrayInputStream(encrypted), consumer)
      assertTrue(sink.committed)
      assertFalse(sink.aborted)
      assertEquals(1L, sink.committedSummary?.recordCount)
      assertEquals(BackupContainerCodec.CHUNK_BYTES + 73, sink.asset.size)
    } finally {
      verified.close()
    }
  }

  @Test
  fun recordPayloadStaysStagedWhenAuthenticatedContainerFinalIsInvalid() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = "private attachment payload".toByteArray()
        writer.writeRecord(BackupRecordDomain.PRIVATE, BackupRecordKind.ASSET, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val encrypted = encrypt(codec, payload, "backup-password".toCharArray())
    val verified = codec.verify(ByteArrayInputStream(encrypted), "backup-password".toCharArray(), encrypted.size.toLong())
    val changedFinal = encrypted.clone().also { it[it.lastIndex] = (it.last().toInt() xor 0x20).toByte() }
    val sink = RecordingBackupRecordSink()
    val consumer = BackupRecordPayloadConsumer(sink, maxStreamBytes = payload.size.toLong())

    try {
      assertThrows(BackupAuthenticationException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(changedFinal), consumer)
      }
      assertFalse(sink.committed)
      assertTrue(sink.aborted)
      assertTrue(sink.stagedAssetBytes.all { it == 0.toByte() })
    } finally {
      verified.close()
    }
  }

  @Test
  fun backupRecordConsumerAllowsCleanupRetryWhenTheFirstAbortFails() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = "private attachment payload".toByteArray()
        writer.writeRecord(BackupRecordDomain.PRIVATE, BackupRecordKind.ASSET, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val encrypted = encrypt(codec, payload, "backup-password".toCharArray())
    val verified = codec.verify(ByteArrayInputStream(encrypted), "backup-password".toCharArray(), encrypted.size.toLong())
    val changedFinal = encrypted.clone().also { it[it.lastIndex] = (it.last().toInt() xor 0x20).toByte() }
    val sink = RecordingBackupRecordSink(failAbortAttempts = 1)
    val consumer = BackupRecordPayloadConsumer(sink, maxStreamBytes = payload.size.toLong())

    try {
      val failure = assertThrows(BackupAuthenticationException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(changedFinal), consumer)
      }
      assertEquals("abort failure must remain visible alongside authentication failure", 1, failure.suppressed.size)
      assertEquals(1, sink.abortCalls)
      assertFalse(sink.aborted)
      assertThrows(IllegalStateException::class.java) { consumer.onChunk(byteArrayOf(0)) }
      assertThrows(IllegalStateException::class.java) { consumer.commit() }

      consumer.abort()

      assertEquals(2, sink.abortCalls)
      assertTrue(sink.aborted)
    } finally {
      verified.close()
    }
  }

  @Test
  fun abortWaitsUntilAnInFlightRecordCallbackHasStoppedWriting() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = byteArrayOf(7, 8, 9)
        writer.writeRecord(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val enteredWrite = CountDownLatch(1)
    val releaseWrite = CountDownLatch(1)
    val abortStarted = CountDownLatch(1)
    val abortReturned = CountDownLatch(1)
    val events = java.util.Collections.synchronizedList(mutableListOf<String>())
    val failure = AtomicReference<Throwable?>()
    val sink = object : BackupRecordRestoreSink {
      override fun onRecordStart(descriptor: BackupRecordDescriptor) = Unit
      override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
        events += "write-start"
        enteredWrite.countDown()
        check(releaseWrite.await(5, TimeUnit.SECONDS)) { "test timed out waiting to release callback" }
        events += "write-finished"
      }
      override fun onRecordEnd(descriptor: BackupRecordDescriptor) = Unit
      override fun commit(summary: BackupRecordStreamSummary) = Unit
      override fun abort() { events += "abort" }
    }
    val consumer = BackupRecordPayloadConsumer(sink, payload.size.toLong())
    val writer = Thread {
      runCatching { consumer.onChunk(payload) }.exceptionOrNull()?.let(failure::set)
    }
    val aborter = Thread {
      abortStarted.countDown()
      runCatching { consumer.abort() }.exceptionOrNull()?.let(failure::set)
      abortReturned.countDown()
    }

    try {
      writer.start()
      assertTrue("record callback did not start", enteredWrite.await(3, TimeUnit.SECONDS))
      aborter.start()
      assertTrue(abortStarted.await(3, TimeUnit.SECONDS))
      val blockedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
      while (aborter.state != Thread.State.BLOCKED && System.nanoTime() < blockedDeadline) Thread.yield()
      assertEquals("abort thread should be waiting for the active chunk monitor", Thread.State.BLOCKED, aborter.state)
      assertFalse("abort must wait for active record writes", abortReturned.await(100, TimeUnit.MILLISECONDS))
    } finally {
      releaseWrite.countDown()
      writer.join(3_000)
      if (aborter.state != Thread.State.NEW) aborter.join(3_000)
    }

    assertFalse("consumer threads did not finish", writer.isAlive || aborter.isAlive)
    assertEquals(null, failure.get())
    assertEquals(listOf("write-start", "write-finished", "abort"), events)
  }

  @Test
  fun consumerRejectsReentrantAbortFromARecordCallback() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = byteArrayOf(3, 2, 1)
        writer.writeRecord(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val events = mutableListOf<String>()
    lateinit var consumer: BackupRecordPayloadConsumer
    val sink = object : BackupRecordRestoreSink {
      override fun onRecordStart(descriptor: BackupRecordDescriptor) = Unit
      override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
        events += "record-data"
        consumer.abort()
      }
      override fun onRecordEnd(descriptor: BackupRecordDescriptor) { events += "record-end" }
      override fun commit(summary: BackupRecordStreamSummary) = Unit
      override fun abort() { events += "abort" }
    }
    consumer = BackupRecordPayloadConsumer(sink, payload.size.toLong())

    assertThrows(IllegalStateException::class.java) { consumer.onChunk(payload) }
    consumer.abort()

    assertEquals(listOf("record-data", "abort"), events)
  }

  @Test
  fun verifiedRestoreAbortsAfterCallbackReentrantlyAttemptsAbort() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = byteArrayOf(6, 5, 4)
        writer.writeRecord(BackupRecordDomain.PRIVATE, BackupRecordKind.NOTE, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val encrypted = encrypt(codec, payload, "backup-password".toCharArray())
    val verified = codec.verify(ByteArrayInputStream(encrypted), "backup-password".toCharArray(), encrypted.size.toLong())
    val events = mutableListOf<String>()
    lateinit var consumer: BackupRecordPayloadConsumer
    val sink = object : BackupRecordRestoreSink {
      override fun onRecordStart(descriptor: BackupRecordDescriptor) = Unit
      override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
        events += "record-data"
        consumer.abort()
      }
      override fun onRecordEnd(descriptor: BackupRecordDescriptor) { events += "record-end" }
      override fun commit(summary: BackupRecordStreamSummary) { events += "commit" }
      override fun abort() { events += "abort" }
    }
    consumer = BackupRecordPayloadConsumer(sink, payload.size.toLong())

    try {
      assertThrows(IllegalStateException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(encrypted), consumer)
      }
      assertEquals(listOf("record-data", "abort"), events)
    } finally {
      verified.close()
    }
  }

  @Test
  fun consumerRejectsReentrantCommitFromARecordCallback() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = byteArrayOf(1, 4, 9)
        writer.writeRecord(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val events = mutableListOf<String>()
    lateinit var consumer: BackupRecordPayloadConsumer
    val sink = object : BackupRecordRestoreSink {
      override fun onRecordStart(descriptor: BackupRecordDescriptor) = Unit
      override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
        events += "record-data"
        consumer.commit()
      }
      override fun onRecordEnd(descriptor: BackupRecordDescriptor) { events += "record-end" }
      override fun commit(summary: BackupRecordStreamSummary) { events += "commit" }
      override fun abort() { events += "abort" }
    }
    consumer = BackupRecordPayloadConsumer(sink, payload.size.toLong())

    assertThrows(IllegalStateException::class.java) { consumer.onChunk(payload) }
    consumer.abort()

    assertEquals(listOf("record-data", "abort"), events)
  }

  @Test
  fun consumerAbortsWhenRecordSinkCommitFailsAndSinkCanRollbackPartialPublication() {
    val payload = ByteArrayOutputStream().also { output ->
      BackupRecordStreamWriter(output, maxStreamBytes = 1024).also { writer ->
        val bytes = "restore payload".toByteArray()
        writer.writeRecord(BackupRecordDomain.ORDINARY, BackupRecordKind.NOTE, 1, bytes.size.toLong(), ByteArrayInputStream(bytes))
        writer.finish()
      }
    }.toByteArray()
    val encrypted = encrypt(codec, payload, "backup-password".toCharArray())
    val verified = codec.verify(ByteArrayInputStream(encrypted), "backup-password".toCharArray(), encrypted.size.toLong())
    val sink = RecordingBackupRecordSink(failCommit = true)
    val consumer = BackupRecordPayloadConsumer(sink, payload.size.toLong())

    try {
      assertThrows(IllegalStateException::class.java) {
        codec.decryptVerified(verified, ByteArrayInputStream(encrypted), consumer)
      }
      assertEquals(1, sink.commitCalls)
      assertEquals(1, sink.abortCalls)
      assertFalse("abort must roll back any incomplete publication", sink.committed)
      assertTrue(sink.aborted)
      assertTrue(sink.stagedAssetBytes.isEmpty())
    } finally {
      verified.close()
    }
  }

  @Test
  fun invalidDerivedKeyLengthIsWipedAlongWithThePassword() {
    val invalidKey = ByteArray(16) { 0x65 }
    val badKdf = KeyDeriver { _, _, _ -> invalidKey }
    val badCodec = BackupContainerCodec(SecureRandom(), badKdf)
    val password = "backup-password".toCharArray()

    assertThrows(IllegalArgumentException::class.java) {
      badCodec.encrypt(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), password, maxPlaintextBytes = 1)
    }

    assertTrue(password.all { it == '\u0000' })
    assertTrue(invalidKey.all { it == 0.toByte() })
  }

  @Test
  fun verifiedCapabilityWipesItsKeyWhenClosedAndRejectsFurtherUse() {
    val derivedKeys = mutableListOf<ByteArray>()
    val observingKdf = KeyDeriver { credential, salt, parameters ->
      testKdf.derive(credential, salt, parameters).also(derivedKeys::add)
    }
    val fixedCodec = BackupContainerCodec(FixedSecureRandom(0x25), observingKdf)
    val encoded = encrypt(fixedCodec, byteArrayOf(3, 4, 5))
    assertTrue(derivedKeys.single().all { it == 0.toByte() })

    val verified = fixedCodec.verify(
      ByteArrayInputStream(encoded),
      "backup-password".toCharArray(),
      encoded.size.toLong(),
    )
    val retainedKey = derivedKeys.last()
    assertFalse(retainedKey.all { it == 0.toByte() })
    verified.close()
    assertTrue(retainedKey.all { it == 0.toByte() })

    val consumer = StagingConsumer()
    assertThrows(IllegalStateException::class.java) {
      fixedCodec.decryptVerified(verified, ByteArrayInputStream(encoded), consumer)
    }
    assertTrue(consumer.aborted)
  }

  @Test
  fun failedEncryptionStillConsumesAndWipesBackupPassword() {
    val password = "backup-password".toCharArray()

    assertThrows(IllegalArgumentException::class.java) {
      codec.encrypt(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), password, maxPlaintextBytes = 1)
    }

    assertTrue(password.all { it == '\u0000' })
  }

  private fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
    return encrypt(codec, plaintext, password)
  }

  private fun encrypt(target: BackupContainerCodec, plaintext: ByteArray, password: CharArray = "backup-password".toCharArray()): ByteArray {
    val output = ByteArrayOutputStream()
    target.encrypt(ByteArrayInputStream(plaintext), output, password, maxPlaintextBytes = plaintext.size.toLong())
    return output.toByteArray()
  }

  private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
    indices.any { start -> start + needle.size <= size && copyOfRange(start, start + needle.size).contentEquals(needle) }

  private class StagingConsumer(
    private val failCommit: Boolean = false,
    private val failAbort: Boolean = false,
    private val failOnChunk: Boolean = false,
  ) : BackupPayloadConsumer {
    val stagedBuffers = mutableListOf<ByteArray>()
    var stagedBuffersAtAbort: List<ByteArray> = emptyList()
      private set
    val callbackBuffers = mutableListOf<ByteArray>()
    var committed = false
      private set
    var aborted = false
      private set
    var published: ByteArray? = null
      private set

    override fun onChunk(plaintext: ByteArray) {
      check(!committed && !aborted)
      callbackBuffers += plaintext
      stagedBuffers += plaintext.clone()
      if (failOnChunk) throw IllegalStateException("consumer chunk failed")
    }

    override fun commit() {
      check(!committed && !aborted)
      if (failCommit) throw IllegalStateException("consumer commit failed")
      val size = stagedBuffers.sumOf(ByteArray::size)
      val result = ByteArray(size)
      var offset = 0
      stagedBuffers.forEach { buffer ->
        buffer.copyInto(result, offset)
        offset += buffer.size
      }
      published = result
      wipeStagedBuffers()
      committed = true
    }

    override fun abort() {
      stagedBuffersAtAbort = stagedBuffers.toList()
      wipeStagedBuffers()
      aborted = true
      if (failAbort) throw IllegalStateException("consumer abort failed")
    }

    private fun wipeStagedBuffers() {
      stagedBuffers.forEach(MemoryWipe::bytes)
      stagedBuffers.clear()
    }
  }

  private class RecordingBackupRecordSink(
    private val failAbortAttempts: Int = 0,
    private val failCommit: Boolean = false,
  ) : BackupRecordRestoreSink {
    private var stagedAsset: ByteArrayOutputStream? = null
    var stagedAssetBytes = byteArrayOf()
      private set
    var committedSummary: BackupRecordStreamSummary? = null
      private set
    var committed = false
      private set
    var aborted = false
      private set
    var abortCalls = 0
      private set
    var commitCalls = 0
      private set

    val asset: ByteArray get() = stagedAssetBytes

    override fun onRecordStart(descriptor: BackupRecordDescriptor) {
      if (descriptor.kind == BackupRecordKind.ASSET) stagedAsset = ByteArrayOutputStream()
    }

    override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
      if (descriptor.kind == BackupRecordKind.ASSET) stagedAsset?.write(bytes, offset, length)
    }

    override fun onRecordEnd(descriptor: BackupRecordDescriptor) = Unit

    override fun commit(summary: BackupRecordStreamSummary) {
      committedSummary = summary
      stagedAssetBytes = stagedAsset?.toByteArray() ?: byteArrayOf()
      committed = true
      commitCalls++
      if (failCommit) throw IllegalStateException("atomic publish failed")
    }

    override fun abort() {
      abortCalls++
      if (abortCalls <= failAbortAttempts) throw IllegalStateException("staging cleanup failed")
      stagedAssetBytes = stagedAsset?.toByteArray()?.also(MemoryWipe::bytes) ?: byteArrayOf()
      committed = false
      committedSummary = null
      stagedAsset = null
      aborted = true
    }
  }

  private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    var bytesRead = 0
      private set

    override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
      super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
  }

  private class FixedSecureRandom(private val value: Byte) : SecureRandom() {
    override fun nextBytes(bytes: ByteArray) {
      bytes.fill(value)
    }
  }

  private class FragmentedInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
    private var bulkReadCount = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      bulkReadCount++
      if (bulkReadCount % 4 == 0) return 0
      return super.read(buffer, offset, minOf(length, 3))
    }
  }
}

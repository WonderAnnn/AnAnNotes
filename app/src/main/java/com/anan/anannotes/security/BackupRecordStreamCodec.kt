package com.anan.anannotes.security

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

enum class BackupRecordDomain(internal val wireId: Int) {
  ORDINARY(1),
  PRIVATE(2),
}

enum class BackupRecordKind(internal val wireId: Int) {
  FOLDER(1),
  NOTE(2),
  BLOCK(3),
  TODO(4),
  DRAFT(5),
  DRAFT_BLOCK(6),
  ASSET(7),
}

data class BackupRecordDescriptor(
  val domain: BackupRecordDomain,
  val kind: BackupRecordKind,
  val schemaVersion: Int,
  val payloadBytes: Long,
)

data class BackupRecordStreamSummary(
  val streamBytes: Long,
  val payloadBytes: Long,
  val recordCount: Long,
  val ordinaryRecordCount: Long,
  val privateRecordCount: Long,
)

class BackupRecordStreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface BackupRecordStreamHandler {
  fun onRecordStart(descriptor: BackupRecordDescriptor)

  /** The buffer belongs to the caller and is only valid for the duration of this callback. */
  fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int)
  fun onRecordEnd(descriptor: BackupRecordDescriptor)
}

/**
 * A staging sink; it must consume bytes synchronously and publish only from commit. Commit must be
 * atomic, or remain fully rollback-capable if it throws; abort must restore the pre-restore visible
 * state and discard only transaction-owned work. For private records, persistent bytes must be
 * encrypted under the target vault key before the callback returns; plaintext must not be retained.
 */
interface BackupRecordRestoreSink : BackupRecordStreamHandler {
  fun commit(summary: BackupRecordStreamSummary)
  fun abort()
}

/**
 * Bounded, length-prefixed record framing for the plaintext carried by ANBK. Payloads remain opaque
 * here: version-specific typed field codecs are responsible for validating each record body. The
 * reader deliberately exposes a bounded stream rather than materializing an attachment in memory.
 */
object BackupRecordStreamCodec {
  const val MAX_STREAM_BYTES = BackupContainerCodec.MAX_PLAINTEXT_BYTES
  const val MAX_RECORDS = 1_000_000L
  const val MAX_ORDINARY_ASSET_BYTES = 250L * 1024L * 1024L
  const val MAX_PRIVATE_ASSET_BYTES = 64L * 1024L * 1024L - 16L
  const val MAX_NOTE_BYTES = 8L * 1024L * 1024L
  const val MAX_BLOCK_BYTES = 8L * 1024L * 1024L
  const val MAX_TODO_BYTES = 4L * 1024L * 1024L
  const val MAX_DRAFT_BYTES = 8L * 1024L * 1024L
  const val MAX_DRAFT_BLOCK_BYTES = 8L * 1024L * 1024L
  const val MAX_FOLDER_BYTES = 64L * 1024L
  const val READ_BUFFER_BYTES = 64 * 1024

  internal const val STREAM_HEADER_BYTES = 5
  internal const val FRAME_HEADER_BYTES = 12
  internal const val SCHEMA_VERSION = 1
  internal const val END_DOMAIN_ID = 0
  internal const val END_KIND_ID = 0

  internal val MAGIC = "ANRD".toByteArray(Charsets.US_ASCII)

  internal fun maxPayloadBytes(domain: BackupRecordDomain, kind: BackupRecordKind): Long = when (kind) {
    BackupRecordKind.FOLDER -> MAX_FOLDER_BYTES
    BackupRecordKind.NOTE -> MAX_NOTE_BYTES
    BackupRecordKind.BLOCK -> MAX_BLOCK_BYTES
    BackupRecordKind.TODO -> MAX_TODO_BYTES
    BackupRecordKind.DRAFT -> MAX_DRAFT_BYTES
    BackupRecordKind.DRAFT_BLOCK -> MAX_DRAFT_BLOCK_BYTES
    BackupRecordKind.ASSET -> if (domain == BackupRecordDomain.PRIVATE) MAX_PRIVATE_ASSET_BYTES else MAX_ORDINARY_ASSET_BYTES
  }

  internal fun validateStreamLimit(maxStreamBytes: Long) {
    if (maxStreamBytes !in (STREAM_HEADER_BYTES + FRAME_HEADER_BYTES).toLong()..MAX_STREAM_BYTES) {
      throw BackupRecordStreamException("Backup record stream size limit is invalid")
    }
  }
}

/** Writes records to a temporary destination; callers publish it only after [finish] succeeds. */
class BackupRecordStreamWriter(
  private val destination: OutputStream,
  private val maxStreamBytes: Long = BackupRecordStreamCodec.MAX_STREAM_BYTES,
) {
  private var streamBytes = 0L
  private var payloadBytes = 0L
  private var records = 0L
  private var ordinaryRecords = 0L
  private var privateRecords = 0L
  private var finished = false
  private var failed = false

  init {
    BackupRecordStreamCodec.validateStreamLimit(maxStreamBytes)
    writeRaw(BackupRecordStreamCodec.MAGIC)
    writeRaw(byteArrayOf(BackupRecordStreamCodec.SCHEMA_VERSION.toByte()))
  }

  fun writeRecord(
    domain: BackupRecordDomain,
    kind: BackupRecordKind,
    schemaVersion: Int,
    payloadBytes: Long,
    payload: InputStream,
  ) {
    checkOpen()
    if (schemaVersion != BackupRecordStreamCodec.SCHEMA_VERSION) {
      throw BackupRecordStreamException("Unsupported backup record schema version")
    }
    if (payloadBytes < 0L || payloadBytes > BackupRecordStreamCodec.maxPayloadBytes(domain, kind)) {
      throw BackupRecordStreamException("Backup record payload length exceeds its type limit")
    }
    if (records >= BackupRecordStreamCodec.MAX_RECORDS) {
      throw BackupRecordStreamException("Backup record count exceeds its limit")
    }
    ensureRoom(payloadBytes)

    val header = encodeFrame(domain.wireId, kind.wireId, schemaVersion, payloadBytes)
    val buffer = ByteArray(BackupRecordStreamCodec.READ_BUFFER_BYTES)
    try {
      writeRaw(header)
      var remaining = payloadBytes
      while (remaining > 0L) {
        val requested = minOf(buffer.size.toLong(), remaining).toInt()
        val count = payload.read(buffer, 0, requested)
        if (count < 0) throw BackupRecordStreamException("Backup record payload ended before its declared length")
        if (count == 0) {
          val one = payload.read()
          if (one < 0) throw BackupRecordStreamException("Backup record payload ended before its declared length")
          buffer[0] = one.toByte()
          destination.write(buffer, 0, 1)
          streamBytes++
          remaining--
          continue
        }
        destination.write(buffer, 0, count)
        streamBytes = checkedAdd(streamBytes, count.toLong())
        remaining -= count.toLong()
      }
      this.payloadBytes = checkedAdd(this.payloadBytes, payloadBytes)
      records++
      if (domain == BackupRecordDomain.ORDINARY) ordinaryRecords++ else privateRecords++
    } catch (failure: Throwable) {
      failed = true
      if (failure is BackupRecordStreamException) throw failure
      if (failure is IOException) throw BackupRecordStreamException("Unable to write backup record", failure)
      throw failure
    } finally {
      MemoryWipe.bytes(buffer)
      MemoryWipe.bytes(header)
    }
  }

  fun finish(): BackupRecordStreamSummary {
    checkOpen()
    if (BackupRecordStreamCodec.FRAME_HEADER_BYTES.toLong() > maxStreamBytes - streamBytes) {
      throw BackupRecordStreamException("Backup record stream exceeds its size limit")
    }
    try {
      writeRaw(encodeFrame(BackupRecordStreamCodec.END_DOMAIN_ID, BackupRecordStreamCodec.END_KIND_ID,
        BackupRecordStreamCodec.SCHEMA_VERSION, 0L))
      destination.flush()
      finished = true
      return BackupRecordStreamSummary(streamBytes, payloadBytes, records, ordinaryRecords, privateRecords)
    } catch (failure: Throwable) {
      failed = true
      if (failure is BackupRecordStreamException) throw failure
      if (failure is IOException) throw BackupRecordStreamException("Unable to finish backup record stream", failure)
      throw failure
    }
  }

  private fun ensureRoom(nextPayloadBytes: Long) {
    val withFrame = checkedAdd(streamBytes, BackupRecordStreamCodec.FRAME_HEADER_BYTES.toLong())
    val withPayload = checkedAdd(withFrame, nextPayloadBytes)
    val includingTerminator = checkedAdd(withPayload, BackupRecordStreamCodec.FRAME_HEADER_BYTES.toLong())
    if (includingTerminator > maxStreamBytes) {
      throw BackupRecordStreamException("Backup record stream exceeds its size limit")
    }
  }

  private fun checkOpen() {
    if (failed) throw BackupRecordStreamException("Backup record stream writer cannot continue after a failure")
    if (finished) throw BackupRecordStreamException("Backup record stream is already finished")
  }

  private fun writeRaw(bytes: ByteArray) {
    if (bytes.size.toLong() > maxStreamBytes - streamBytes) {
      throw BackupRecordStreamException("Backup record stream exceeds its size limit")
    }
    destination.write(bytes)
    streamBytes = checkedAdd(streamBytes, bytes.size.toLong())
  }

  private fun checkedAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
  } catch (failure: ArithmeticException) {
    throw BackupRecordStreamException("Backup record stream length overflow", failure)
  }

  private fun encodeFrame(domain: Int, kind: Int, version: Int, size: Long): ByteArray =
    byteArrayOf(domain.toByte(), kind.toByte(), (version ushr 8).toByte(), version.toByte()) +
      longToBytes(size)

  private fun longToBytes(value: Long): ByteArray = ByteArray(8) { index ->
    (value ushr (56 - index * 8)).toByte()
  }
}

/** Parses the record framing without allocating per-record payloads. Handler must consume each body fully. */
class BackupRecordStreamReader(
  private val maxStreamBytes: Long = BackupRecordStreamCodec.MAX_STREAM_BYTES,
) {
  fun readAll(source: InputStream, onRecord: (BackupRecordDescriptor, InputStream) -> Unit): BackupRecordStreamSummary {
    BackupRecordStreamCodec.validateStreamLimit(maxStreamBytes)
    val input = CountedLimitedInputStream(source, maxStreamBytes)
    val streamHeader = input.readFully(BackupRecordStreamCodec.STREAM_HEADER_BYTES)
    if (!streamHeader.copyOfRange(0, 4).contentEquals(BackupRecordStreamCodec.MAGIC) ||
      (streamHeader[4].toInt() and 0xff) != BackupRecordStreamCodec.SCHEMA_VERSION
    ) {
      throw BackupRecordStreamException("Backup record stream header is invalid or unsupported")
    }

    var count = 0L
    var ordinaryCount = 0L
    var privateCount = 0L
    var totalPayloadBytes = 0L
    while (true) {
      val first = input.readOneOrEof()
        ?: throw BackupRecordStreamException("Backup record stream terminator is missing")
      val frame = ByteArray(BackupRecordStreamCodec.FRAME_HEADER_BYTES)
      frame[0] = first.toByte()
      input.readFully(frame, offset = 1, length = frame.size - 1)

      val domainId = frame[0].toInt() and 0xff
      val kindId = frame[1].toInt() and 0xff
      val schemaVersion = ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
      val payloadLength = readLong(frame, 4)
      if (domainId == BackupRecordStreamCodec.END_DOMAIN_ID && kindId == BackupRecordStreamCodec.END_KIND_ID) {
        if (schemaVersion != BackupRecordStreamCodec.SCHEMA_VERSION || payloadLength != 0L) {
          throw BackupRecordStreamException("Backup record stream terminator is malformed")
        }
        if (input.probeEnd() != null) throw BackupRecordStreamException("Trailing bytes after backup record stream terminator")
        return BackupRecordStreamSummary(input.bytesRead, totalPayloadBytes, count, ordinaryCount, privateCount)
      }

      val domain = BackupRecordDomain.entries.firstOrNull { it.wireId == domainId }
        ?: throw BackupRecordStreamException("Backup record domain is unknown")
      val kind = BackupRecordKind.entries.firstOrNull { it.wireId == kindId }
        ?: throw BackupRecordStreamException("Backup record kind is unknown")
      if (schemaVersion != BackupRecordStreamCodec.SCHEMA_VERSION) {
        throw BackupRecordStreamException("Unsupported backup record schema version")
      }
      if (payloadLength < 0L || payloadLength > BackupRecordStreamCodec.maxPayloadBytes(domain, kind)) {
        throw BackupRecordStreamException("Backup record payload length exceeds its type limit")
      }
      if (count >= BackupRecordStreamCodec.MAX_RECORDS) throw BackupRecordStreamException("Backup record count exceeds its limit")
      val projectedBytes = checkedAdd(checkedAdd(input.bytesRead, payloadLength), BackupRecordStreamCodec.FRAME_HEADER_BYTES.toLong())
      if (projectedBytes > maxStreamBytes) throw BackupRecordStreamException("Backup record stream exceeds its size limit")
      totalPayloadBytes = checkedAdd(totalPayloadBytes, payloadLength)

      val limited = RecordBodyInputStream(input, payloadLength)
      onRecord(BackupRecordDescriptor(domain, kind, schemaVersion, payloadLength), limited)
      if (limited.remainingBytes != 0L) {
        throw BackupRecordStreamException("Backup record handler did not consume the complete payload")
      }
      count++
      if (domain == BackupRecordDomain.ORDINARY) ordinaryCount++ else privateCount++
    }
  }

  private fun readLong(bytes: ByteArray, offset: Int): Long {
    var result = 0L
    for (index in offset until offset + 8) result = (result shl 8) or (bytes[index].toLong() and 0xffL)
    return result
  }

  private fun checkedAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
  } catch (failure: ArithmeticException) {
    throw BackupRecordStreamException("Backup record stream length overflow", failure)
  }

  private class CountedLimitedInputStream(private val source: InputStream, private val maximum: Long) {
    var bytesRead = 0L
      private set

    fun readOneOrEof(): Int? {
      if (bytesRead >= maximum) throw BackupRecordStreamException("Backup record stream exceeds its size limit")
      val value = source.read()
      if (value < 0) return null
      bytesRead++
      return value
    }

    fun probeEnd(): Int? {
      if (bytesRead < maximum) return readOneOrEof()
      val value = source.read()
      if (value >= 0) throw BackupRecordStreamException("Backup record stream exceeds its size limit")
      return null
    }

    fun readFully(length: Int): ByteArray = ByteArray(length).also { readFully(it, 0, length) }

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
      if (length.toLong() > maximum - bytesRead) throw BackupRecordStreamException("Backup record stream exceeds its size limit")
      var cursor = offset
      val end = offset + length
      while (cursor < end) {
        val count = source.read(buffer, cursor, end - cursor)
        if (count < 0) throw BackupRecordStreamException("Backup record stream is truncated", EOFException())
        if (count == 0) {
          val one = source.read()
          if (one < 0) throw BackupRecordStreamException("Backup record stream is truncated", EOFException())
          buffer[cursor++] = one.toByte()
          bytesRead++
        } else {
          cursor += count
          bytesRead = checkedAdd(bytesRead, count.toLong())
        }
      }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (length == 0) return 0
      if (bytesRead >= maximum) throw BackupRecordStreamException("Backup record stream exceeds its size limit")
      val allowed = minOf(length.toLong(), maximum - bytesRead).toInt()
      val count = source.read(buffer, offset, allowed)
      if (count > 0) bytesRead = checkedAdd(bytesRead, count.toLong())
      return count
    }

    fun read(): Int {
      if (bytesRead >= maximum) throw BackupRecordStreamException("Backup record stream exceeds its size limit")
      val value = source.read()
      if (value >= 0) bytesRead++
      return value
    }

    private fun checkedAdd(first: Long, second: Long): Long = try {
      Math.addExact(first, second)
    } catch (failure: ArithmeticException) {
      throw BackupRecordStreamException("Backup record stream length overflow", failure)
    }
  }

  private class RecordBodyInputStream(
    private val source: CountedLimitedInputStream,
    initialRemainingBytes: Long,
  ) : InputStream() {
    var remainingBytes = initialRemainingBytes
      private set

    override fun read(): Int {
      if (remainingBytes == 0L) return -1
      val value = source.read()
      if (value >= 0) remainingBytes--
      return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
      if (offset < 0 || length < 0 || length > buffer.size - offset) throw IndexOutOfBoundsException()
      if (length == 0) return 0
      if (remainingBytes == 0L) return -1
      val requested = minOf(length.toLong(), remainingBytes).toInt()
      val count = source.read(buffer, offset, requested)
      if (count > 0) {
        remainingBytes -= count.toLong()
      } else if (count == 0) {
        val one = read()
        if (one < 0) return -1
        buffer[offset] = one.toByte()
        return 1
      }
      return count
    }
  }
}

/** Incremental parser for ANBK plaintext callbacks; record bytes are never concatenated or retained. */
class BackupRecordStreamDecoder(
  private val handler: BackupRecordStreamHandler,
  private val maxStreamBytes: Long = BackupRecordStreamCodec.MAX_STREAM_BYTES,
) {
  private val streamHeader = ByteArray(BackupRecordStreamCodec.STREAM_HEADER_BYTES)
  private val frameHeader = ByteArray(BackupRecordStreamCodec.FRAME_HEADER_BYTES)
  private var streamHeaderBytes = 0
  private var frameHeaderBytes = 0
  private var streamBytes = 0L
  private var payloadBytes = 0L
  private var recordCount = 0L
  private var ordinaryCount = 0L
  private var privateCount = 0L
  private var descriptor: BackupRecordDescriptor? = null
  private var bodyRemaining = 0L
  private var sawTerminator = false
  private var isFinished = false
  private var failed = false

  init {
    BackupRecordStreamCodec.validateStreamLimit(maxStreamBytes)
  }

  fun accept(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
    checkUsable()
    if (offset < 0 || length < 0 || length > bytes.size - offset) throw IndexOutOfBoundsException()
    if (length.toLong() > maxStreamBytes - streamBytes) fail("Backup record stream exceeds its size limit")
    var cursor = offset
    val end = offset + length
    try {
      while (cursor < end) {
        if (sawTerminator) fail("Trailing bytes after backup record stream terminator")
        when {
          streamHeaderBytes < streamHeader.size -> {
            streamHeader[streamHeaderBytes++] = bytes[cursor++]
            streamBytes++
            if (streamHeaderBytes == streamHeader.size) validateStreamHeader()
          }
          descriptor == null -> {
            frameHeader[frameHeaderBytes++] = bytes[cursor++]
            streamBytes++
            if (frameHeaderBytes == frameHeader.size) parseFrameHeader()
          }
          else -> {
            val current = checkNotNull(descriptor)
            val count = minOf((end - cursor).toLong(), bodyRemaining).toInt()
            if (count <= 0) fail("Backup record decoder entered an invalid body state")
            streamBytes = checkedAdd(streamBytes, count.toLong())
            bodyRemaining -= count.toLong()
            handler.onRecordData(current, bytes, cursor, count)
            cursor += count
            if (bodyRemaining == 0L) {
              handler.onRecordEnd(current)
              if (current.domain == BackupRecordDomain.ORDINARY) ordinaryCount++ else privateCount++
              recordCount++
              descriptor = null
            }
          }
        }
      }
    } catch (failure: Throwable) {
      failed = true
      throw failure
    }
  }

  fun finish(): BackupRecordStreamSummary {
    checkUsable()
    if (streamHeaderBytes != streamHeader.size || frameHeaderBytes != 0 || descriptor != null || bodyRemaining != 0L) {
      fail("Backup record stream is truncated")
    }
    if (!sawTerminator) fail("Backup record stream terminator is missing")
    isFinished = true
    return BackupRecordStreamSummary(streamBytes, payloadBytes, recordCount, ordinaryCount, privateCount)
  }

  private fun validateStreamHeader() {
    if (!streamHeader.copyOfRange(0, 4).contentEquals(BackupRecordStreamCodec.MAGIC) ||
      (streamHeader[4].toInt() and 0xff) != BackupRecordStreamCodec.SCHEMA_VERSION
    ) {
      fail("Backup record stream header is invalid or unsupported")
    }
  }

  private fun parseFrameHeader() {
    val domainId = frameHeader[0].toInt() and 0xff
    val kindId = frameHeader[1].toInt() and 0xff
    val version = ((frameHeader[2].toInt() and 0xff) shl 8) or (frameHeader[3].toInt() and 0xff)
    val length = readLong(frameHeader, 4)
    frameHeaderBytes = 0

    if (domainId == BackupRecordStreamCodec.END_DOMAIN_ID && kindId == BackupRecordStreamCodec.END_KIND_ID) {
      if (version != BackupRecordStreamCodec.SCHEMA_VERSION || length != 0L) fail("Backup record stream terminator is malformed")
      sawTerminator = true
      return
    }
    val recordDomain = BackupRecordDomain.entries.firstOrNull { it.wireId == domainId }
      ?: fail("Backup record domain is unknown")
    val recordKind = BackupRecordKind.entries.firstOrNull { it.wireId == kindId }
      ?: fail("Backup record kind is unknown")
    if (version != BackupRecordStreamCodec.SCHEMA_VERSION) fail("Unsupported backup record schema version")
    if (length < 0L || length > BackupRecordStreamCodec.maxPayloadBytes(recordDomain, recordKind)) {
      fail("Backup record payload length exceeds its type limit")
    }
    if (recordCount >= BackupRecordStreamCodec.MAX_RECORDS) fail("Backup record count exceeds its limit")
    val projectedBytes = checkedAdd(checkedAdd(streamBytes, length), BackupRecordStreamCodec.FRAME_HEADER_BYTES.toLong())
    if (projectedBytes > maxStreamBytes) fail("Backup record stream exceeds its size limit")
    payloadBytes = checkedAdd(payloadBytes, length)
    val parsed = BackupRecordDescriptor(recordDomain, recordKind, version, length)
    descriptor = parsed
    bodyRemaining = length
    handler.onRecordStart(parsed)
    if (length == 0L) {
      handler.onRecordEnd(parsed)
      if (recordDomain == BackupRecordDomain.ORDINARY) ordinaryCount++ else privateCount++
      recordCount++
      descriptor = null
    }
  }

  private fun readLong(bytes: ByteArray, offset: Int): Long {
    var result = 0L
    for (index in offset until offset + 8) result = (result shl 8) or (bytes[index].toLong() and 0xffL)
    return result
  }

  private fun checkedAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
  } catch (failure: ArithmeticException) {
    fail("Backup record stream length overflow")
  }

  private fun checkUsable() {
    if (failed) throw BackupRecordStreamException("Backup record decoder cannot continue after a failure")
    if (isFinished) throw BackupRecordStreamException("Backup record decoder is already finished")
  }

  private fun fail(message: String): Nothing {
    failed = true
    throw BackupRecordStreamException(message)
  }
}

/**
 * Adapts authenticated ANBK chunks to the record decoder. The sink's commit is invoked only after
 * the decoder has seen its own terminator; the outer container invokes this consumer only after its
 * authenticated FINAL record and EOF have also been checked.
 */
class BackupRecordPayloadConsumer(
  private val sink: BackupRecordRestoreSink,
  maxStreamBytes: Long = BackupRecordStreamCodec.MAX_STREAM_BYTES,
) : BackupPayloadConsumer {
  private val decoder = BackupRecordStreamDecoder(sink, maxStreamBytes)
  private var state = State.OPEN

  @Synchronized
  override fun onChunk(plaintext: ByteArray) {
    check(state == State.OPEN) { "Backup record payload consumer is not open" }
    state = State.PROCESSING
    try {
      decoder.accept(plaintext)
      state = State.OPEN
    } catch (failure: Throwable) {
      state = State.INPUT_FAILED
      throw failure
    }
  }

  @Synchronized
  override fun commit() {
    check(state == State.OPEN) { "Backup record payload consumer is not open" }
    state = State.COMMITTING
    try {
      val summary = decoder.finish()
      sink.commit(summary)
      state = State.COMMITTED
    } catch (failure: Throwable) {
      state = State.COMMIT_FAILED
      throw failure
    }
  }

  @Synchronized
  override fun abort() {
    when (state) {
      State.COMMITTED, State.ABORTED -> return
      State.OPEN, State.INPUT_FAILED, State.COMMIT_FAILED, State.ABORT_FAILED -> Unit
      State.PROCESSING, State.COMMITTING, State.ABORTING -> error("Backup record payload consumer is busy")
    }
    state = State.ABORTING
    try {
      sink.abort()
      state = State.ABORTED
    } catch (failure: Throwable) {
      state = State.ABORT_FAILED
      throw failure
    }
  }

  private enum class State {
    OPEN,
    PROCESSING,
    INPUT_FAILED,
    COMMITTING,
    COMMIT_FAILED,
    COMMITTED,
    ABORTING,
    ABORT_FAILED,
    ABORTED,
  }
}

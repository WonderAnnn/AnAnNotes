package com.anan.anannotes.security

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Receives one decoded record at a time and consumes attachment bytes synchronously. */
internal interface TypedBackupRecordStore {
  fun onRecord(domain: BackupRecordDomain, payload: BackupRecordPayload)

  fun onAssetBytes(domain: BackupRecordDomain, asset: BackupRecordPayload.Asset, bytes: ByteArray, offset: Int, length: Int)

  /** Must atomically publish staged state or remain rollback-capable if it throws. */
  fun commit(streamSummary: BackupRecordStreamSummary, recordSetSummary: BackupRecordSetSummary)

  /** Must remove only transaction-owned staging and restore the pre-operation visible state. */
  fun abort()
}

/**
 * Adapts incrementally delivered ANRD frame bytes to the strict payload codec. Assets flow through
 * a bounded pipe to the codec's digesting reader, so a large private or ordinary attachment is
 * never materialized as one byte array by this adapter.
 */
internal class TypedBackupRecordRestoreSink(
  private val store: TypedBackupRecordStore,
  private val validator: BackupRecordSetValidator = BackupRecordSetValidator(),
) : BackupRecordRestoreSink {
  private val decoder = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "anan-backup-record-decoder").apply { isDaemon = true }
  }
  private var active: ActiveRecord? = null
  private var committed = false
  private var aborted = false

  @Synchronized
  override fun onRecordStart(descriptor: BackupRecordDescriptor) {
    check(!committed && !aborted && active == null) { "Typed backup sink is not ready for another record" }
    val input = PipedInputStream(PIPE_BUFFER_BYTES)
    val output = PipedOutputStream(input)
    val future = try {
      decoder.submit {
        try {
          val payload = BackupRecordPayloadCodec.decode(
            domain = descriptor.domain,
            kind = descriptor.kind,
            schemaVersion = descriptor.schemaVersion,
            source = input,
            payloadBytes = descriptor.payloadBytes,
            onAssetBytes = BackupAssetByteSink { asset, bytes, offset, length ->
              store.onAssetBytes(descriptor.domain, asset, bytes, offset, length)
            },
          )
          validator.accept(descriptor.domain, payload)
          store.onRecord(descriptor.domain, payload)
        } finally {
          input.close()
        }
      }
    } catch (failure: Throwable) {
      runCatching { output.close() }
      runCatching { input.close() }
      throw failure
    }
    active = ActiveRecord(descriptor, input, output, future)
  }

  @Synchronized
  override fun onRecordData(descriptor: BackupRecordDescriptor, bytes: ByteArray, offset: Int, length: Int) {
    val current = active ?: throw BackupRecordStreamException("Typed backup sink received data outside a record")
    if (current.descriptor != descriptor) throw BackupRecordStreamException("Typed backup sink record changed unexpectedly")
    try {
      current.output.write(bytes, offset, length)
    } catch (failure: IOException) {
      throw decodeFailure(current, failure)
    }
  }

  @Synchronized
  override fun onRecordEnd(descriptor: BackupRecordDescriptor) {
    val current = active ?: throw BackupRecordStreamException("Typed backup sink ended a missing record")
    if (current.descriptor != descriptor) throw BackupRecordStreamException("Typed backup sink record changed unexpectedly")
    active = null
    try {
      current.output.close()
      current.future.get()
    } catch (failure: Throwable) {
      throw decodeFailure(current, failure)
    } finally {
      runCatching { current.input.close() }
    }
  }

  @Synchronized
  override fun commit(summary: BackupRecordStreamSummary) {
    check(!committed && !aborted && active == null) { "Typed backup sink cannot commit in its current state" }
    val recordSummary = validator.finish()
    val ordinaryRecords = recordSummary.ordinaryFolders + recordSummary.ordinaryNotes +
      recordSummary.ordinaryBlocks + recordSummary.ordinaryTodos + recordSummary.ordinaryDrafts +
      recordSummary.ordinaryDraftBlocks + recordSummary.ordinaryAssets
    val privateRecords = recordSummary.privateNotes + recordSummary.privateBlocks +
      recordSummary.privateTodos + recordSummary.privateDrafts + recordSummary.privateDraftBlocks +
      recordSummary.privateAssets
    if (ordinaryRecords != summary.ordinaryRecordCount || privateRecords != summary.privateRecordCount ||
      ordinaryRecords + privateRecords != summary.recordCount
    ) throw BackupRecordPayloadException("Typed record counts do not match the record stream")
    shutdownDecoder()
    store.commit(summary, recordSummary)
    committed = true
  }

  @Synchronized
  override fun abort() {
    if (committed || aborted) return
    aborted = true
    var cleanupFailure: Throwable? = null
    fun remember(failure: Throwable) {
      val current = cleanupFailure
      if (current == null) cleanupFailure = failure else current.addSuppressed(failure)
    }
    active?.let { current ->
      active = null
      runCatching { current.output.close() }.onFailure(::remember)
      current.future.cancel(true)
      runCatching { current.input.close() }.onFailure(::remember)
    }
    decoder.shutdownNow()
    try {
      if (!decoder.awaitTermination(DECODER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw IOException("Backup record decoder did not stop after cancellation")
      }
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      remember(failure)
    } catch (failure: Throwable) {
      remember(failure)
    }
    validator.close()
    try {
      store.abort()
    } catch (failure: Throwable) {
      remember(failure)
    }
    cleanupFailure?.let { throw it }
  }

  private fun decodeFailure(record: ActiveRecord, failure: Throwable): Throwable {
    record.future.cancel(true)
    val cause = when (failure) {
      is ExecutionException -> failure.cause ?: failure
      else -> failure
    }
    return when (cause) {
      is BackupRecordPayloadException -> cause
      is BackupRecordStreamException -> cause
      is IOException -> BackupRecordPayloadException("Backup record could not be decoded", cause)
      else -> BackupRecordPayloadException("Backup record could not be decoded", cause)
    }
  }

  private fun shutdownDecoder() {
    decoder.shutdown()
    try {
      if (!decoder.awaitTermination(DECODER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        decoder.shutdownNow()
        throw IOException("Backup record decoder did not stop after commit")
      }
    } catch (failure: InterruptedException) {
      decoder.shutdownNow()
      Thread.currentThread().interrupt()
      throw IOException("Interrupted while closing backup record decoder", failure)
    }
  }

  private data class ActiveRecord(
    val descriptor: BackupRecordDescriptor,
    val input: PipedInputStream,
    val output: PipedOutputStream,
    val future: java.util.concurrent.Future<*>,
  )

  private companion object {
    const val PIPE_BUFFER_BYTES = 64 * 1024
    const val DECODER_SHUTDOWN_TIMEOUT_SECONDS = 10L
  }
}

package com.anan.anannotes.feature.backup

import com.anan.anannotes.data.files.SafBackupDocumentStore
import com.anan.anannotes.data.files.SafBackupReceipt
import com.anan.anannotes.data.files.StagedBackupCiphertext
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.security.BackupContainerCodec
import com.anan.anannotes.security.BackupContainerSummary
import com.anan.anannotes.security.BackupPayloadConsumer
import com.anan.anannotes.security.BackupRecordDomain
import com.anan.anannotes.security.BackupRecordPayload
import com.anan.anannotes.security.BackupRecordPayloadWriter
import com.anan.anannotes.security.BackupRecordPayloadConsumer
import com.anan.anannotes.security.BackupRecordSetSummary
import com.anan.anannotes.security.BackupRecordStreamSummary
import com.anan.anannotes.security.CryptoBytes
import com.anan.anannotes.security.TypedBackupRecordStore
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.TypedBackupRecordRestoreSink
import com.anan.anannotes.security.VerifiedBackup
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

internal data class BackupCreationResult(
  val destination: SafBackupReceipt,
  val container: BackupContainerSummary,
  val recordStream: BackupRecordStreamSummary,
)

/** Implementations apply these decisions in their durable transaction and recheck the target revision there. */
internal interface ConflictAwareBackupRestoreStore : TypedBackupRecordStore {
  fun prepareConflictResolution(resolution: ResolvedBackupConflictPlan)
}

/** Preview values are available only after ANBK authentication and a full typed-record pass. */
data class BackupPreview(
  val encryptedFileBytes: Long,
  val authenticatedPayloadBytes: Long,
  val recordSet: BackupRecordSetSummary,
  val conflictSummary: BackupConflictSummary? = null,
)

data class BackupConflictSummary(
  val newObjects: Int,
  val identicalObjects: Int,
  val conflicts: Int,
  val ordinaryConflicts: Int = 0,
  val privateConflicts: Int = 0,
)

/**
 * Owns end-to-end ANBK/ANRD sequencing. Export sources write typed records; inspection stages only
 * ciphertext; restore can consume a preview capability once and commits through a rollback-capable
 * typed store.
 */
internal class BackupCoordinator(
  private val containerCodec: BackupContainerCodec,
  private val documents: SafBackupDocumentStore,
  private val noBackupDirectory: File,
) {
  fun recoverAbandonedInspectionFiles(): Int =
    SafBackupDocumentStore.cleanupOrphanedStagedFiles(noBackupDirectory)

  fun createBackup(
    target: OutputTarget.DocumentTree,
    requestedName: String,
    backupPassword: CharArray,
    writeRecords: (BackupRecordPayloadWriter) -> Unit,
  ): BackupCreationResult {
    var containerSummary: BackupContainerSummary? = null
    var recordSummary: BackupRecordStreamSummary? = null
    try {
      val receipt = documents.writeBackup(target, requestedName) { encryptedOutput ->
        val input = PipedInputStream(PIPE_BUFFER_BYTES)
        val output = PipedOutputStream(input)
        val producer = Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "anan-backup-record-writer").apply { isDaemon = true }
        }
        val future = producer.submit {
          try {
            val writer = BackupRecordPayloadWriter(output)
            writeRecords(writer)
            writer.finish().also { recordSummary = it }
          } finally {
            output.close()
          }
        }
        try {
          containerSummary = containerCodec.encrypt(
            plaintext = input,
            destination = encryptedOutput,
            backupPassword = backupPassword,
            maxPlaintextBytes = BackupContainerCodec.MAX_PLAINTEXT_BYTES,
          )
          try {
            future.get()
          } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
          }
        } catch (failure: Throwable) {
          future.cancel(true)
          runCatching { input.close() }
          throw failure
        } finally {
          runCatching { input.close() }
          runCatching { output.close() }
          producer.shutdownNow()
          try {
            if (!producer.awaitTermination(10, TimeUnit.SECONDS)) {
              throw IOException("Backup record writer did not stop after completion")
            }
          } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while closing backup record writer", failure)
          }
        }
      }
      return BackupCreationResult(
        destination = receipt,
        container = checkNotNull(containerSummary),
        recordStream = checkNotNull(recordSummary),
      )
    } finally {
      MemoryWipe.chars(backupPassword)
    }
  }

  fun inspect(sourceUriString: String, backupPassword: CharArray): InspectedBackup {
    var staged: StagedBackupCiphertext? = null
    var verified: VerifiedBackup? = null
    try {
      staged = documents.stageEncryptedSource(sourceUriString, noBackupDirectory)
      verified = staged.openInputStream().use { input ->
        containerCodec.verify(
          source = input,
          backupPassword = backupPassword,
          maxContainerBytes = BackupContainerCodec.MAX_CONTAINER_BYTES,
        )
      }
      val store = PreviewValidationStore()
      val consumer = BackupRecordPayloadConsumer(TypedBackupRecordRestoreSink(store))
      staged.openInputStream().use { input -> containerCodec.decryptVerified(verified, input, consumer) }
      val summary = checkNotNull(store.recordSetSummary) { "Backup semantic validation did not complete" }
      return InspectedBackup(
        preview = BackupPreview(
          encryptedFileBytes = staged.sizeBytes,
          authenticatedPayloadBytes = verified.plaintextBytes,
          recordSet = summary,
          conflictSummary = null,
        ),
        stagedFile = staged,
        verifiedBackup = verified,
        objectFingerprints = checkNotNull(store.objectFingerprints) { "Backup fingerprints were not finalized" },
      ).also {
        staged = null
        verified = null
      }
    } finally {
      MemoryWipe.chars(backupPassword)
      verified?.close()
      staged?.close()
    }
  }

  /** The passed restore store owns conflict choice application and all cross-file/Room rollback. */
  fun restore(
    inspected: InspectedBackup,
    target: ConflictAwareBackupRestoreStore,
    resolution: ResolvedBackupConflictPlan,
  ): BackupRecordSetSummary {
    inspected.verifyResolution(resolution)
    val capability = inspected.claimForRestore()
    val capture = SummaryCapturingStore(target)
    try {
      target.prepareConflictResolution(resolution)
      val consumer = BackupRecordPayloadConsumer(TypedBackupRecordRestoreSink(capture))
      capability.staged.openInputStream().use { input -> containerCodec.decryptVerified(capability.verified, input, consumer) }
      return checkNotNull(capture.recordSetSummary) { "Backup restore did not commit" }
    } catch (failure: Throwable) {
      runCatching { capture.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
      throw failure
    } finally {
      inspected.close()
    }
  }

  private class PreviewValidationStore : TypedBackupRecordStore {
    private val fingerprintCollector = BackupObjectFingerprintCollector()
    var recordSetSummary: BackupRecordSetSummary? = null
    var objectFingerprints: Map<BackupObjectKey, CryptoBytes>? = null

    override fun onRecord(domain: BackupRecordDomain, payload: BackupRecordPayload) =
      fingerprintCollector.accept(domain, payload)

    override fun onAssetBytes(domain: BackupRecordDomain, asset: BackupRecordPayload.Asset, bytes: ByteArray, offset: Int, length: Int) = Unit

    override fun commit(streamSummary: BackupRecordStreamSummary, recordSetSummary: BackupRecordSetSummary) {
      this.recordSetSummary = recordSetSummary
      objectFingerprints = fingerprintCollector.finish()
    }

    override fun abort() = fingerprintCollector.close()
  }

  private class SummaryCapturingStore(private val delegate: TypedBackupRecordStore) : TypedBackupRecordStore {
    private val terminal = AtomicBoolean(false)
    var recordSetSummary: BackupRecordSetSummary? = null

    override fun onRecord(domain: BackupRecordDomain, payload: BackupRecordPayload) {
      check(!terminal.get()) { "Restore target is already complete" }
      delegate.onRecord(domain, payload)
    }

    override fun onAssetBytes(domain: BackupRecordDomain, asset: BackupRecordPayload.Asset, bytes: ByteArray, offset: Int, length: Int) {
      check(!terminal.get()) { "Restore target is already complete" }
      delegate.onAssetBytes(domain, asset, bytes, offset, length)
    }

    override fun commit(streamSummary: BackupRecordStreamSummary, recordSetSummary: BackupRecordSetSummary) {
      check(!terminal.get()) { "Restore target is already complete" }
      delegate.commit(streamSummary, recordSetSummary)
      this.recordSetSummary = recordSetSummary
      terminal.set(true)
    }

    override fun abort() {
      if (terminal.compareAndSet(false, true)) delegate.abort()
    }
  }

  private companion object {
    const val PIPE_BUFFER_BYTES = 64 * 1024
  }
}

/** In-memory capability. Closing invalidates the backup key and deletes its encrypted staging file. */
internal class InspectedBackup internal constructor(
  preview: BackupPreview,
  private val stagedFile: StagedBackupCiphertext,
  private val verifiedBackup: VerifiedBackup,
  private val objectFingerprints: Map<BackupObjectKey, CryptoBytes>,
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val consumed = AtomicBoolean(false)
  private val backupSessionId = UUID.randomUUID().toString()
  private var conflictPlan: BackupConflictPlan? = null
  private var boundTargetRevision: Long? = null
  var preview: BackupPreview = preview
    private set

  internal fun bindTargetSnapshot(currentFingerprints: Map<BackupObjectKey, CryptoBytes>, targetRevision: Long) {
    check(!closed.get() && !consumed.get()) { "Inspected backup is expired or already used" }
    check(conflictPlan == null) { "Backup is already bound to a restore target" }
    val plan = BackupConflictPlan.compare(
      backupSessionId = backupSessionId,
      targetRevision = targetRevision,
      current = currentFingerprints,
      incoming = objectFingerprints,
    )
    conflictPlan = plan
    boundTargetRevision = targetRevision
    preview = preview.copy(
      conflictSummary = BackupConflictSummary(
        newObjects = plan.newObjects.size,
        identicalObjects = plan.identicalObjects.size,
        conflicts = plan.conflicts.size,
        ordinaryConflicts = plan.conflictKeys.count { it.domain == BackupRecordDomain.ORDINARY },
        privateConflicts = plan.conflictKeys.count { it.domain == BackupRecordDomain.PRIVATE },
      ),
    )
  }

  internal fun resolveConflictChoices(
    currentFingerprints: Map<BackupObjectKey, CryptoBytes>,
    choices: Map<BackupObjectKey, BackupConflictChoice>,
  ): ResolvedBackupConflictPlan {
    check(!closed.get() && !consumed.get()) { "Inspected backup is expired or already used" }
    val plan = checkNotNull(conflictPlan) { "Inspect must bind this backup to a restore target first" }
    val currentRevision = BackupTargetRevision.from(currentFingerprints)
    return plan.resolve(
      currentSessionId = backupSessionId,
      currentTargetRevision = currentRevision,
      currentTargetFingerprints = currentFingerprints,
      choices = choices,
    )
  }

  /** Applies one explicit policy per privacy domain without exposing private record IDs to UI state. */
  internal fun resolveConflictChoicesByDomain(
    currentFingerprints: Map<BackupObjectKey, CryptoBytes>,
    ordinaryChoice: BackupConflictChoice,
    privateChoice: BackupConflictChoice,
  ): ResolvedBackupConflictPlan {
    check(!closed.get() && !consumed.get()) { "Inspected backup is expired or already used" }
    val plan = checkNotNull(conflictPlan) { "Inspect must bind this backup to a restore target first" }
    val currentRevision = BackupTargetRevision.from(currentFingerprints)
    val choices = plan.conflictKeys.associateWith { key ->
      if (key.domain == BackupRecordDomain.ORDINARY) ordinaryChoice else privateChoice
    }
    return plan.resolve(
      currentSessionId = backupSessionId,
      currentTargetRevision = currentRevision,
      currentTargetFingerprints = currentFingerprints,
      choices = choices,
    )
  }

  internal fun verifyResolution(resolution: ResolvedBackupConflictPlan) {
    check(!closed.get() && !consumed.get()) { "Inspected backup is expired or already used" }
    check(resolution.backupSessionId == backupSessionId) { "Conflict choices belong to another backup" }
    check(resolution.targetRevision == boundTargetRevision) { "Conflict choices belong to another target snapshot" }
    check(conflictPlan != null) { "Backup is not bound to a restore target" }
  }

  internal fun claimForRestore(): RestoreCapability {
    check(!closed.get() && consumed.compareAndSet(false, true)) { "Inspected backup is expired or already used" }
    return RestoreCapability(stagedFile, verifiedBackup)
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      verifiedBackup.close()
      stagedFile.close()
    }
  }

  internal data class RestoreCapability(
    val staged: StagedBackupCiphertext,
    val verified: VerifiedBackup,
  )
}

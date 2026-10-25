package com.anan.anannotes.feature.editor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/**
 * Transaction-owned publication for ordinary crash drafts restored from an authenticated backup.
 * Private drafts must never pass through this class; they stay inside the encrypted private vault.
 *
 * Before creating staged draft files, byte-for-byte copies of every draft that may be replaced are
 * fsynced into the transaction directory. Publication is copy+atomic-rename, leaving the staged
 * source and rollback copies intact until Room's durable commit marker decides the outcome.
 */
internal class EditorDraftRestoreTransactionStore(
  private val draftsDirectory: File,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val mutex = Mutex()

  suspend fun stage(transactionId: String, snapshots: List<EditorDraftSnapshot>) = mutex.withLock {
    withContext(ioDispatcher) {
      val transactionDirectory = transactionDirectory(transactionId)
      check(!transactionDirectory.exists()) { "Editor draft restore transaction already exists" }
      val uniqueIds = snapshots.map(EditorDraftSnapshot::draftId)
      require(uniqueIds.distinct().size == uniqueIds.size) { "Backup contains duplicate draft IDs" }
      uniqueIds.forEach(::validateDraftId)

      val stagedDirectory = File(transactionDirectory, STAGED_DIRECTORY)
      val previousDirectory = File(transactionDirectory, PREVIOUS_DIRECTORY)
      if (!stagedDirectory.mkdirs() || !previousDirectory.mkdirs()) {
        transactionDirectory.deleteRecursively()
        throw IOException("无法创建草稿恢复暂存目录")
      }

      try {
        // Snapshot originals first. A crash before all originals are copied cannot have published
        // anything because stage files are deliberately written only after this loop.
        uniqueIds.forEach { draftId ->
          val current = draftFile(draftId)
          if (current.isFile) copyAtomically(current, File(previousDirectory, current.name))
        }
        val stageStore = FileEditorDraftStore(stagedDirectory, ioDispatcher)
        snapshots.forEach { stageStore.writeAtomic(it) }
      } catch (failure: Throwable) {
        // No publication can have happened from stage(); leave the journal-owned directory for
        // startup recovery if cleanup itself fails.
        runCatching { transactionDirectory.deleteRecursively() }
        throw failure
      }
    }
  }

  suspend fun publish(transactionId: String) = mutex.withLock {
    withContext(ioDispatcher) {
      val staged = stagedDraftFiles(transactionId)
      if (staged.isEmpty()) return@withContext
      if (!draftsDirectory.exists() && !draftsDirectory.mkdirs()) throw IOException("无法创建草稿目录")
      staged.forEach { source -> copyAtomically(source, draftFile(source.nameWithoutExtension)) }
    }
  }

  /** Idempotently restores only IDs in this transaction; unrelated drafts are never touched. */
  suspend fun rollback(transactionId: String) = mutex.withLock {
    withContext(ioDispatcher) {
      val transactionDirectory = transactionDirectory(transactionId)
      if (!transactionDirectory.exists()) return@withContext
      val staged = stagedDraftFiles(transactionId)
      val previousDirectory = File(transactionDirectory, PREVIOUS_DIRECTORY)
      staged.forEach { stagedFile ->
        val target = draftFile(stagedFile.nameWithoutExtension)
        val previous = File(previousDirectory, stagedFile.name)
        if (previous.isFile) copyAtomically(previous, target) else Files.deleteIfExists(target.toPath())
      }
      deleteTransactionDirectory(transactionDirectory)
    }
  }

  /** On commit, retain the published versions; on abort, restore the byte-for-byte originals. */
  suspend fun finish(transactionId: String, committed: Boolean) {
    if (committed) {
      mutex.withLock {
        withContext(ioDispatcher) { deleteTransactionDirectoryIfPresent(transactionDirectory(transactionId)) }
      }
    } else {
      rollback(transactionId)
    }
  }

  private fun stagedDraftFiles(transactionId: String): List<File> {
    val stagedDirectory = File(transactionDirectory(transactionId), STAGED_DIRECTORY)
    if (!stagedDirectory.exists()) return emptyList()
    val files = stagedDirectory.listFiles()
      ?: throw IOException("无法读取草稿恢复暂存目录")
    return files.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }.onEach { file ->
      validateDraftId(file.name.removeSuffix(FILE_SUFFIX))
    }.sortedBy(File::getName)
  }

  private fun draftFile(draftId: String): File {
    validateDraftId(draftId)
    return File(draftsDirectory, "$draftId$FILE_SUFFIX")
  }

  private fun transactionDirectory(transactionId: String): File {
    validateTransactionId(transactionId)
    return File(draftsDirectory, "$RESTORE_PREFIX$transactionId")
  }

  private fun deleteTransactionDirectoryIfPresent(directory: File) {
    if (directory.exists()) deleteTransactionDirectory(directory)
  }

  private fun deleteTransactionDirectory(directory: File) {
    check(directory.parentFile?.canonicalFile == draftsDirectory.canonicalFile) {
      "草稿恢复事务目录越界"
    }
    if (directory.exists() && !directory.deleteRecursively()) {
      throw IOException("无法清理草稿恢复事务目录")
    }
  }

  private fun copyAtomically(source: File, target: File) {
    val parent = target.parentFile ?: throw IOException("草稿目标目录无效")
    if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建草稿目录")
    val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.restore-tmp")
    try {
      FileInputStream(source).use { input ->
        FileOutputStream(temporary).use { output ->
          input.copyTo(output, COPY_BUFFER_BYTES)
          output.flush()
          output.fd.sync()
        }
      }
      try {
        Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), target.toPath(), REPLACE_EXISTING)
      }
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  private fun validateDraftId(draftId: String) {
    require(runCatching { UUID.fromString(draftId).toString() == draftId }.getOrDefault(false)) {
      "草稿标识无效"
    }
  }

  private fun validateTransactionId(transactionId: String) {
    require(runCatching { UUID.fromString(transactionId).toString() == transactionId }.getOrDefault(false)) {
      "草稿恢复事务标识无效"
    }
  }

  private companion object {
    const val STAGED_DIRECTORY = "staged"
    const val PREVIOUS_DIRECTORY = "previous"
    const val RESTORE_PREFIX = ".restore-"
    const val FILE_SUFFIX = ".aan-draft"
    const val COPY_BUFFER_BYTES = 64 * 1024
  }
}

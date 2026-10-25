package com.anan.anannotes.data.files

import android.content.ContentResolver
import com.anan.anannotes.feature.export.ExportLayout
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.security.BackupContainerCodec
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SafBackupReceipt(
  val uriString: String,
  val fileName: String,
  val destinationName: String?,
)

/** SAF adapter used only for encrypted backup containers. */
internal class SafBackupDocumentStore(
  private val documents: SafDocumentAccess,
) {
  constructor(contentResolver: ContentResolver) : this(AndroidSafDocumentAccess(contentResolver))

  fun openSource(uriString: String): InputStream =
    documents.openInputStream(uriString)
      ?: throw IOException("系统无法读取所选备份文件")

  /** A newly created SAF document is deleted on every incomplete write path. */
  fun writeBackup(
    target: OutputTarget.DocumentTree,
    requestedName: String,
    content: (OutputStream) -> Unit,
  ): SafBackupReceipt {
    var created: SafDocumentRef? = null
    try {
      val uniqueName = ExportLayout.uniqueFileName(requestedName, documents.listNames(target))
      val document = documents.create(target, uniqueName, BACKUP_MIME_TYPE)
        ?: throw IOException("无法在所选文件夹创建备份文件")
      created = document
      val output = documents.openOutputStream(document)
        ?: throw IOException("系统没有返回可写入的备份文件流")
      output.use(content)
      return SafBackupReceipt(
        uriString = document.uriString,
        fileName = runCatching { documents.displayName(document) }.getOrNull() ?: document.displayName,
        destinationName = document.destinationName,
      )
    } catch (failure: Throwable) {
      created?.let { document ->
        val deleted = runCatching { documents.delete(document) }.getOrDefault(false)
        if (!deleted) failure.addSuppressed(IOException("无法清理本次创建的不完整备份文件"))
      }
      throw failure
    }
  }

  /**
   * Copies only encrypted bytes from the selected provider into a bounded, random no-backup file.
   * The returned handle deletes the file on close; startup cleanup removes only this class's names.
   */
  fun stageEncryptedSource(
    sourceUriString: String,
    noBackupDirectory: File,
    maxBytes: Long = BackupContainerCodec.MAX_CONTAINER_BYTES,
  ): StagedBackupCiphertext {
    require(maxBytes in 1L..BackupContainerCodec.MAX_CONTAINER_BYTES) { "Backup staging limit is invalid" }
    if (!noBackupDirectory.exists() && !noBackupDirectory.mkdirs()) {
      throw IOException("无法创建加密备份暂存目录")
    }
    if (!noBackupDirectory.isDirectory) throw IOException("加密备份暂存位置不可用")
    val token = UUID.randomUUID().toString()
    val temporary = File(noBackupDirectory, "$STAGE_PREFIX$token.tmp")
    val staged = File(noBackupDirectory, "$STAGE_PREFIX$token.stage")
    if (!temporary.createNewFile()) throw IOException("无法创建加密备份暂存文件")
    var copied = 0L
    try {
      openSource(sourceUriString).use { input ->
        FileOutputStream(temporary).use { fileOutput ->
          val buffer = ByteArray(BUFFER_BYTES)
          try {
            BufferedOutputStream(fileOutput, BUFFER_BYTES).use { output ->
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (count.toLong() > maxBytes - copied) throw IOException("所选备份文件超过安全大小上限")
                output.write(buffer, 0, count)
                copied += count.toLong()
              }
              output.flush()
              fileOutput.fd.sync()
            }
          } finally {
            buffer.fill(0)
          }
        }
      }
      moveAtomically(temporary, staged)
      return StagedBackupCiphertext(staged, copied)
    } catch (failure: Throwable) {
      runCatching { Files.deleteIfExists(temporary.toPath()) }
      runCatching { Files.deleteIfExists(staged.toPath()) }
      throw failure
    }
  }

  private fun moveAtomically(source: File, destination: File) {
    try {
      Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source.toPath(), destination.toPath())
    }
  }

  companion object {
    const val BACKUP_MIME_TYPE = "application/octet-stream"
    private const val BUFFER_BYTES = 64 * 1024
    private const val STAGE_PREFIX = ".anan-backup-inspect-"
    private val STAGED_NAME = Regex("^\\.anan-backup-inspect-[0-9a-fA-F-]{36}\\.(tmp|stage)$")

    fun cleanupOrphanedStagedFiles(noBackupDirectory: File): Int {
      val candidates = noBackupDirectory.listFiles().orEmpty().filter { STAGED_NAME.matches(it.name) }
      var removed = 0
      candidates.forEach { file -> if (Files.deleteIfExists(file.toPath())) removed++ }
      return removed
    }
  }
}

internal class StagedBackupCiphertext internal constructor(
  private val file: File,
  val sizeBytes: Long,
) : AutoCloseable {
  fun openInputStream(): InputStream = FileInputStream(file)

  override fun close() {
    Files.deleteIfExists(file.toPath())
  }
}

package com.anan.anannotes.data.files

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class StoredOriginalImage(
  val assetId: String,
  val relativePath: String,
  val byteCount: Long,
)

/** Stores durable originals outside the disposable preview-cache directory. */
class OriginalImageStore(private val filesRoot: File) {
  private val assetDirectory = File(filesRoot, ASSET_DIRECTORY)

  /**
   * Writes a verified ordinary restore attachment below a transaction-owned directory. The
   * returned path is safe to store in Room before the restore commit marker is inserted; startup
   * recovery can then remove the whole group if the database transaction did not commit.
   */
  fun importRestore(
    transactionId: String,
    assetId: String,
    input: InputStream,
    extension: String,
    expectedByteCount: Long,
    expectedSha256: ByteArray,
  ): StoredOriginalImage = input.use { source ->
    validateUuid(transactionId, "恢复事务标识无效")
    validateUuid(assetId, "图片标识无效")
    require(extension.matches(Regex("[a-zA-Z0-9]{1,8}"))) { "图片扩展名无效" }
    require(expectedByteCount in 1..MAX_IMAGE_BYTES) { "恢复图片长度超出安全范围" }
    require(expectedSha256.size == SHA256_BYTES) { "恢复图片摘要长度无效" }

    val group = File(assetDirectory, "$RESTORE_DIRECTORY_PREFIX$transactionId")
    ensureRestoreDirectory(group)
    val normalizedExtension = extension.lowercase()
    val target = File(group, "$assetId.$normalizedExtension")
    val temporary = File(group, ".$assetId.${UUID.randomUUID()}.tmp")
    val expectedDigest = expectedSha256.clone()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    var copied = 0L
    try {
      val file = FileOutputStream(temporary)
      try {
        while (true) {
          val count = source.read(buffer)
          if (count < 0) break
          if (count == 0) {
            val next = source.read()
            if (next < 0) break
            buffer[0] = next.toByte()
            copied += 1L
            if (copied > expectedByteCount || copied > MAX_IMAGE_BYTES) {
              throw IOException("恢复图片长度超过声明值")
            }
            digest.update(buffer, 0, 1)
            file.write(buffer, 0, 1)
          } else {
            copied += count.toLong()
            if (copied > expectedByteCount || copied > MAX_IMAGE_BYTES) {
              throw IOException("恢复图片长度超过声明值")
            }
            digest.update(buffer, 0, count)
            file.write(buffer, 0, count)
          }
        }
        if (copied != expectedByteCount) throw IOException("恢复图片长度与声明值不符")
        val actualDigest = digest.digest()
        try {
          if (!MessageDigest.isEqual(actualDigest, expectedDigest)) throw IOException("恢复图片 SHA-256 校验失败")
        } finally {
          actualDigest.fill(0)
        }
        file.flush()
        file.fd.sync()
      } finally {
        file.close()
      }
      moveAtomically(temporary, target)
      StoredOriginalImage(
        assetId = assetId,
        relativePath = "$ASSET_DIRECTORY/$RESTORE_DIRECTORY_PREFIX$transactionId/$assetId.$normalizedExtension",
        byteCount = copied,
      )
    } finally {
      expectedDigest.fill(0)
      buffer.fill(0)
      if (temporary.exists()) temporary.delete()
    }
  }

  fun import(input: InputStream, extension: String = "img"): StoredOriginalImage {
    require(extension.matches(Regex("[a-zA-Z0-9]{1,8}"))) { "图片扩展名无效" }
    if (!assetDirectory.exists() && !assetDirectory.mkdirs()) throw IOException("无法创建图片目录")

    val assetId = UUID.randomUUID().toString()
    val target = File(assetDirectory, "$assetId.${extension.lowercase()}")
    val temporary = File(assetDirectory, ".$assetId.tmp")
    var copied = 0L
    return try {
      BufferedInputStream(input).use { source ->
        FileOutputStream(temporary).use { outputFile ->
          BufferedOutputStream(outputFile).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val count = source.read(buffer)
              if (count < 0) break
              copied += count
              if (copied > MAX_IMAGE_BYTES) throw IOException("图片超过 250 MB 安全上限")
              output.write(buffer, 0, count)
            }
            output.flush()
            outputFile.fd.sync()
          }
        }
      }
      if (copied == 0L) throw IOException("图片文件为空")
      moveAtomically(temporary, target)
      StoredOriginalImage(assetId, "$ASSET_DIRECTORY/$assetId.${extension.lowercase()}", copied)
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  fun resolve(relativePath: String): File {
    require(relativePath.startsWith("$ASSET_DIRECTORY/")) { "图片路径不在原图目录中" }
    val file = File(filesRoot, relativePath)
    val rootPath = assetDirectory.canonicalFile.toPath()
    val filePath = file.canonicalFile.toPath()
    require(filePath.startsWith(rootPath) && filePath != rootPath) { "图片路径超出原图目录" }
    return file
  }

  /** Deletes only the generated directory owned by the supplied transaction UUID. */
  fun deleteRestoreGroup(transactionId: String): Boolean {
    validateUuid(transactionId, "恢复事务标识无效")
    val group = File(assetDirectory, "$RESTORE_DIRECTORY_PREFIX$transactionId")
    val rootPath = assetDirectory.canonicalFile.toPath()
    val groupPath = group.canonicalFile.toPath()
    require(!Files.isSymbolicLink(group.toPath())) { "恢复目录不能是符号链接" }
    require(groupPath.parent == rootPath && groupPath.fileName.toString() == "$RESTORE_DIRECTORY_PREFIX$transactionId") {
      "恢复目录超出原图目录"
    }
    return if (!group.exists()) true else group.deleteRecursively()
  }

  private fun ensureRestoreDirectory(group: File) {
    if (!assetDirectory.exists() && !assetDirectory.mkdirs()) throw IOException("无法创建图片目录")
    require(!Files.isSymbolicLink(assetDirectory.toPath()) && !Files.isSymbolicLink(group.toPath())) {
      "恢复目录不能是符号链接"
    }
    val rootPath = assetDirectory.canonicalFile.toPath()
    val groupPath = group.canonicalFile.toPath()
    require(groupPath.parent == rootPath && groupPath.fileName.toString().startsWith(RESTORE_DIRECTORY_PREFIX)) {
      "恢复目录超出原图目录"
    }
    if (!group.exists() && !group.mkdirs()) throw IOException("无法创建恢复图片目录")
    require(group.isDirectory && group.canonicalFile.toPath().parent == rootPath) { "恢复目录无效" }
  }

  private fun validateUuid(value: String, message: String) {
    require(runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)) { message }
  }

  fun delete(relativePath: String): Boolean = Files.deleteIfExists(resolve(relativePath).toPath())

  private fun moveAtomically(source: File, destination: File) {
    try {
      Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(source.toPath(), destination.toPath())
    }
  }

  companion object {
    const val ASSET_DIRECTORY = "notes/assets"
    const val MAX_IMAGE_BYTES = 250L * 1024L * 1024L
    private const val RESTORE_DIRECTORY_PREFIX = "restore-"
    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val SHA256_BYTES = 32
  }
}

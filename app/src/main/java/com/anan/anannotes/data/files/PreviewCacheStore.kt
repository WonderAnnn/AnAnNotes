package com.anan.anannotes.data.files

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Rebuildable thumbnails only; clearing this store never touches durable note assets. */
class PreviewCacheStore(private val cacheRoot: File) {
  private val previewDirectory = File(cacheRoot, PREVIEW_DIRECTORY)

  fun write(assetId: String, bytes: ByteArray): String {
    require(assetId.matches(ASSET_ID_PATTERN)) { "图片标识无效" }
    require(bytes.isNotEmpty()) { "预览图片为空" }
    if (!previewDirectory.exists() && !previewDirectory.mkdirs()) throw IOException("无法创建图片预览缓存目录")

    val relativePath = "$PREVIEW_DIRECTORY/$assetId.jpg"
    val destination = resolve(relativePath)
    val temporary = File(previewDirectory, ".$assetId.tmp")
    return try {
      FileOutputStream(temporary).use { outputFile ->
        BufferedOutputStream(outputFile).use { output ->
          output.write(bytes)
          output.flush()
          outputFile.fd.sync()
        }
      }
      try {
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      relativePath
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  fun resolve(relativePath: String): File {
    require(relativePath.startsWith("$PREVIEW_DIRECTORY/")) { "路径不在预览缓存目录中" }
    val file = File(cacheRoot, relativePath)
    val rootPath = previewDirectory.canonicalFile.toPath()
    val filePath = file.canonicalFile.toPath()
    require(filePath.startsWith(rootPath) && filePath != rootPath) { "预览缓存路径越界" }
    return file
  }

  fun clear(): Int {
    if (!previewDirectory.exists()) return 0
    val files = previewDirectory.listFiles() ?: throw IOException("无法读取图片预览缓存目录")
    var removed = 0
    files.forEach { file ->
      if (file.isFile && Files.deleteIfExists(file.toPath())) removed++
    }
    return removed
  }

  companion object {
    const val PREVIEW_DIRECTORY = "notes/previews"
    private val ASSET_ID_PATTERN = Regex("[a-zA-Z0-9_-]{1,128}")
  }
}

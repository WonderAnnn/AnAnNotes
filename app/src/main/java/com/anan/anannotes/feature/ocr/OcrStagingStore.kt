package com.anan.anannotes.feature.ocr

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Durable, app-private source for text-only OCR jobs; removed when the job reaches a terminal state. */
class OcrStagingStore(
  appPrivateDirectory: File,
  private val maximumBytes: Long = MAX_STAGED_IMAGE_BYTES,
) {
  private val directory = File(appPrivateDirectory, DIRECTORY_NAME)

  fun stage(ocrBlockId: String, input: InputStream): File {
    val target = fileFor(ocrBlockId)
    if (!directory.exists() && !directory.mkdirs()) throw IOException("无法创建 OCR 临时图片目录")
    val temporary = File(directory, ".${ocrBlockId}.tmp")
    var copied = 0L
    try {
      BufferedInputStream(input).use { source ->
        FileOutputStream(temporary).use { raw ->
          BufferedOutputStream(raw).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
              val count = source.read(buffer)
              if (count < 0) break
              copied += count
              if (copied > maximumBytes) throw IOException("OCR 图片超过 250 MB 安全上限")
              output.write(buffer, 0, count)
            }
            output.flush()
            raw.fd.sync()
          }
        }
      }
      if (copied == 0L) throw IOException("所选 OCR 图片为空")
      try {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      return target
    } finally {
      if (temporary.exists()) temporary.delete()
    }
  }

  fun fileFor(ocrBlockId: String): File {
    require(isStagingBlockId(ocrBlockId)) { "OCR 作业标识无效" }
    val root = directory.canonicalFile.toPath()
    val file = File(directory, "$ocrBlockId.source").canonicalFile
    require(file.toPath().startsWith(root) && file.parentFile == directory.canonicalFile) { "OCR 临时源路径无效" }
    return file
  }

  fun delete(ocrBlockId: String): Boolean = fileFor(ocrBlockId).delete()

  companion object {
    const val MAX_STAGED_IMAGE_BYTES = 250L * 1024L * 1024L
    private const val DIRECTORY_NAME = "ocr-staging"
    private val BLOCK_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

    /** Pending text-only OCR input must survive process death and Android cache eviction. */
    fun forContext(context: Context): OcrStagingStore = OcrStagingStore(context.noBackupFilesDir)

    /** Reads staging created by earlier app versions before sources moved out of cacheDir. */
    fun legacyCache(context: Context): OcrStagingStore = OcrStagingStore(context.cacheDir)

    fun findStagedSource(context: Context, ocrBlockId: String): File? {
      if (!isStagingBlockId(ocrBlockId)) return null
      return forContext(context).fileFor(ocrBlockId).takeIf(File::isFile)
        ?: legacyCache(context).fileFor(ocrBlockId).takeIf(File::isFile)
    }

    fun deleteStagedSource(context: Context, ocrBlockId: String) {
      if (!isStagingBlockId(ocrBlockId)) return
      forContext(context).delete(ocrBlockId)
      legacyCache(context).delete(ocrBlockId)
    }

    private fun isStagingBlockId(value: String): Boolean = value.matches(BLOCK_ID_PATTERN)
  }
}

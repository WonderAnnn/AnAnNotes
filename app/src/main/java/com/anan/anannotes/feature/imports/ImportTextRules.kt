package com.anan.anannotes.feature.imports

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object ImportTextRules {
  const val MAX_TEXT_BYTES = 5 * 1024 * 1024

  fun pickerMimeTypes(): Array<String> = arrayOf(
    "text/*",
    "image/*",
    "application/markdown",
    "application/x-markdown",
    "application/octet-stream",
  )

  private val supportedExtensions = setOf("txt", "md", "markdown")
  private val supportedMimeTypes = setOf(
    "text/plain",
    "text/markdown",
    "text/x-markdown",
    "application/markdown",
    "application/x-markdown",
  )

  fun isSupportedTextFile(displayName: String?, mimeType: String?): Boolean {
    val name = displayName.orEmpty().trim()
    val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
      .takeIf { name.contains('.') }
      ?.lowercase()

    if (extension != null) {
      return extension in supportedExtensions && (mime.isEmpty() || mime == "application/octet-stream" || mime in supportedMimeTypes)
    }
    return mime in supportedMimeTypes
  }

  @Throws(IOException::class)
  fun readUtf8(displayName: String?, mimeType: String?, input: InputStream): String {
    if (!isSupportedTextFile(displayName, mimeType)) throw IOException("仅支持 TXT 和 Markdown 文件")

    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      total += count
      if (total > MAX_TEXT_BYTES) throw IOException("文本文件超过 5 MB 安全上限")
      output.write(buffer, 0, count)
    }
    if (total == 0) throw IOException("文本文件没有内容")

    val decoded = try {
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(output.toByteArray()))
        .toString()
    } catch (error: Exception) {
      throw IOException("文本文件不是有效的 UTF-8 编码", error)
    }
    val text = decoded.removePrefix("\uFEFF")
    if (text.isEmpty()) throw IOException("文本文件没有内容")
    return text
  }
}

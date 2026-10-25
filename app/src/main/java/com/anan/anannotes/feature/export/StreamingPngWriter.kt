package com.anan.anannotes.feature.export

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.roundToInt

class PngSizeLimitException(message: String) : IOException(message)

/** RGB8 PNG encoder with O(width) buffers, Sub filtering and bounded IDAT chunks.
 * The caller owns the output stream. close() aborts/releases zlib; finish() publishes IEND.
 * Format: https://www.w3.org/TR/png-3/ (pHYs, filtering, IDAT, CRC).
 */
class StreamingPngWriter(
  output: OutputStream,
  private val width: Int,
  private val height: Int,
  dpi: Int? = null,
  private val maxBytes: Long = MAX_FILE_BYTES,
) : Closeable {
  private val destination = DataOutputStream(output)
  private var bytesWritten = 0L
  private var rowsWritten = 0
  private var finished = false
  private var closed = false
  private val filteredRow: ByteArray
  private val compressed = ByteArray(IDAT_SIZE)
  private val deflater: Deflater

  init {
    require(width in 1..32768 && height > 0) { "PNG 尺寸无效" }
    require(dpi == null || dpi in 1..10000) { "PNG DPI 无效" }
    require(maxBytes > 0) { "PNG 文件上限无效" }
    filteredRow = ByteArray(width * 3 + 1)
    // Validate and write headers before allocating native zlib state (which needs close()).
    budget(8)
    destination.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
    chunk("IHDR", payload {
      writeInt(width); writeInt(height)
      writeByte(8); writeByte(2); writeByte(0); writeByte(0); writeByte(0)
    })
    if (dpi != null) {
      val pixelsPerMeter = (dpi / 0.0254).roundToInt()
      chunk("pHYs", payload { writeInt(pixelsPerMeter); writeInt(pixelsPerMeter); writeByte(1) })
    }
    deflater = Deflater(4)
  }

  fun writeRow(pixels: IntArray) {
    check(!closed && !finished && rowsWritten < height) { "PNG 行数超限或编码器已结束" }
    require(pixels.size == width) { "PNG 行宽不匹配" }
    filteredRow[0] = 1 // Sub: compare against the same channel of the previous pixel.
    var leftRed = 0
    var leftGreen = 0
    var leftBlue = 0
    pixels.forEachIndexed { x, argb ->
      val red = (argb ushr 16) and 255
      val green = (argb ushr 8) and 255
      val blue = argb and 255
      val offset = 1 + x * 3
      filteredRow[offset] = (red - leftRed).toByte()
      filteredRow[offset + 1] = (green - leftGreen).toByte()
      filteredRow[offset + 2] = (blue - leftBlue).toByte()
      leftRed = red; leftGreen = green; leftBlue = blue
    }
    deflater.setInput(filteredRow)
    while (!deflater.needsInput()) drain()
    rowsWritten++
  }

  fun finish() {
    check(!closed && !finished) { "PNG 编码器已结束" }
    check(rowsWritten == height) { "PNG 图像未写完整" }
    deflater.finish()
    while (!deflater.finished()) drain()
    chunk("IEND", byteArrayOf())
    destination.flush()
    finished = true
  }

  override fun close() {
    if (!closed) {
      closed = true
      deflater.end()
    }
  }

  private fun drain() {
    val count = deflater.deflate(compressed)
    if (count > 0) chunk("IDAT", compressed, count)
  }

  private fun chunk(type: String, content: ByteArray, length: Int = content.size) {
    budget(length.toLong() + 12)
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    destination.writeInt(length)
    destination.write(typeBytes)
    destination.write(content, 0, length)
    destination.writeInt(CRC32().apply { update(typeBytes); update(content, 0, length) }.value.toInt())
  }

  private fun budget(count: Long) {
    if (count > maxBytes - bytesWritten) {
      throw PngSizeLimitException("PNG 文件超过 128 MiB 安全上限，请改用分页导出。")
    }
    bytesWritten += count
  }

  private fun payload(write: DataOutputStream.() -> Unit): ByteArray =
    ByteArrayOutputStream().apply { DataOutputStream(this).write() }.toByteArray()

  companion object {
    const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    private const val IDAT_SIZE = 32768
  }
}

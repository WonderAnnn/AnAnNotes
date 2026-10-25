package com.anan.anannotes.feature.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPngWriterTest {
  @Test fun writesValidRgbRowsCrcAndSixHundredDpiWithoutClosingCallerStream() {
    var closed = false
    val output = object : ByteArrayOutputStream() { override fun close() { closed = true } }
    StreamingPngWriter(output, width = 2, height = 2, dpi = 600).use { writer ->
      writer.writeRow(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()))
      writer.writeRow(intArrayOf(0xff0000ff.toInt(), 0xffffffff.toInt()))
      writer.finish()
    }
    val chunks = chunks(output.toByteArray())
    assertEquals(listOf("IHDR", "pHYs"), chunks.take(2).map { it.first })
    assertTrue(chunks.drop(2).dropLast(1).all { it.first == "IDAT" })
    DataInputStream(ByteArrayInputStream(chunks.first().second)).use {
      assertEquals(2, it.readInt()); assertEquals(2, it.readInt())
      assertEquals(8, it.readUnsignedByte()); assertEquals(2, it.readUnsignedByte())
    }
    DataInputStream(ByteArrayInputStream(chunks.single { it.first == "pHYs" }.second)).use {
      assertEquals(23622, it.readInt()); assertEquals(23622, it.readInt()); assertEquals(1, it.readUnsignedByte())
    }
    assertArrayEquals(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, -1), rgb(chunks, 2, 2))
    assertFalse("编码器不能关闭调用者的流", closed)
  }

  @Test fun writesManyRowsAcrossBoundedIdatChunksAndNoInventedDpiForLongImage() {
    val output = ByteArrayOutputStream()
    val width = 113
    val height = 257
    val expected = ByteArrayOutputStream()
    StreamingPngWriter(output, width, height).use { writer ->
      repeat(height) { y ->
        val pixels = IntArray(width) { x ->
          val red = (x * 97 + y * 17) and 255
          val green = (x * 33 + y * 113) and 255
          val blue = (x * 191 + y * 37) and 255
          expected.write(red); expected.write(green); expected.write(blue)
          0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        writer.writeRow(pixels)
      }
      writer.finish()
    }
    val chunks = chunks(output.toByteArray())
    assertFalse(chunks.any { it.first == "pHYs" })
    assertTrue(chunks.filter { it.first == "IDAT" }.all { it.second.size <= 32768 })
    assertArrayEquals(expected.toByteArray(), rgb(chunks, width, height))
  }

  @Test fun rejectsMissingExcessAndWrongWidthRows() {
    StreamingPngWriter(ByteArrayOutputStream(), 2, 2).use { writer ->
      assertThrows(IllegalArgumentException::class.java) { writer.writeRow(intArrayOf(0)) }
      writer.writeRow(intArrayOf(0, 0))
      assertThrows(IllegalStateException::class.java) { writer.finish() }
      writer.writeRow(intArrayOf(0, 0))
      assertThrows(IllegalStateException::class.java) { writer.writeRow(intArrayOf(0, 0)) }
      writer.finish()
    }
  }

  @Test fun honorsEncodedFileBudgetAndPropagatesWriteFailure() {
    assertThrows(IOException::class.java) {
      StreamingPngWriter(ByteArrayOutputStream(), 1, 1, maxBytes = 40L).use {
        it.writeRow(intArrayOf(0xff001122.toInt())); it.finish()
      }
    }
    val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("磁盘不可写") } }
    assertEquals("磁盘不可写", assertThrows(IOException::class.java) { StreamingPngWriter(broken, 1, 1) }.message)
  }

  @Test fun validatesDimensionsBeforeWritingAnyHeader() {
    val output = ByteArrayOutputStream()
    assertThrows(IllegalArgumentException::class.java) { StreamingPngWriter(output, 0, 1) }
    assertThrows(IllegalArgumentException::class.java) { StreamingPngWriter(output, 1, -1) }
    assertEquals(0, output.size())
  }

  private fun chunks(bytes: ByteArray): List<Pair<String, ByteArray>> =
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
      val signature = ByteArray(8).also(input::readFully)
      assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), signature)
      val result = mutableListOf<Pair<String, ByteArray>>()
      while (input.available() > 0) {
        val length = input.readInt()
        val typeBytes = ByteArray(4).also(input::readFully)
        val content = ByteArray(length).also(input::readFully)
        val crc = CRC32().apply { update(typeBytes); update(content) }.value
        assertEquals(crc, input.readInt().toLong() and 0xffffffffL)
        result += typeBytes.toString(Charsets.US_ASCII) to content
      }
      assertEquals("IEND", result.last().first)
      result
    }

  private fun rgb(chunks: List<Pair<String, ByteArray>>, width: Int, height: Int): ByteArray {
    val compressed = chunks.filter { it.first == "IDAT" }.fold(ByteArrayOutputStream()) { out, chunk ->
      out.apply { write(chunk.second) }
    }.toByteArray()
    val raw = InflaterInputStream(ByteArrayInputStream(compressed)).readBytes()
    val stride = width * 3
    assertEquals((stride + 1) * height, raw.size)
    val result = ByteArray(stride * height)
    repeat(height) { y ->
      val inputOffset = y * (stride + 1)
      assertEquals(1, raw[inputOffset].toInt())
      repeat(stride) { x ->
        val left = if (x >= 3) result[y * stride + x - 3].toInt() and 255 else 0
        result[y * stride + x] = ((raw[inputOffset + 1 + x].toInt() and 255) + left).toByte()
      }
    }
    return result
  }
}

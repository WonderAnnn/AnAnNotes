package com.anan.anannotes.core.model

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFormattingCodecTest {
  @Test
  fun roundTripsBodyHeadingAndOverlappingInlineStyles() {
    val cases = listOf(
      "正文" to BlockFormatting(),
      "标题" to BlockFormatting(presentation = TextBlockPresentation.HEADING),
      "甲乙丙" to BlockFormatting(
        inlineStyles = listOf(
          InlineStyleRange(0, 3, InlineStyle.BOLD),
          InlineStyleRange(1, 2, InlineStyle.ITALIC),
        ),
      ),
    )

    cases.forEach { (text, formatting) ->
      val encoded = BlockFormattingCodec.encode(text, BlockType.TEXT, formatting)

      assertEquals(BlockFormattingState.Supported(formatting), BlockFormattingCodec.decode(encoded, text, BlockType.TEXT))
    }
  }

  @Test
  fun unknownVersionIsUnsupportedAndPreservesOriginalBytes() {
    val bytes = validBlob(version = 77)

    val decoded = BlockFormattingCodec.decode(bytes, "正文", BlockType.TEXT)

    assertTrue(decoded is BlockFormattingState.Unsupported)
    decoded as BlockFormattingState.Unsupported
    assertEquals(FormattingDecodeError.UNSUPPORTED_VERSION, decoded.error)
    assertEquals(bytes.toList(), decoded.originalBlob.toList())
  }

  @Test
  fun unsupportedStateKeepsAReadOnlyCopyOfItsOriginalBlob() {
    val source = byteArrayOf(1, 2, 3)
    val unsupported = BlockFormattingState.Unsupported(source, FormattingDecodeError.MALFORMED_DATA)
    source[0] = 9
    val exposed = unsupported.originalBlob
    exposed[1] = 9

    assertEquals(listOf<Byte>(1, 2, 3), unsupported.originalBlob.toList())
  }

  @Test
  fun versionOneUsesStableExplicitWireIds() {
    val encoded = BlockFormattingCodec.encode(
      text = "A",
      type = BlockType.TEXT,
      formatting = BlockFormatting(
        presentation = TextBlockPresentation.HEADING,
        inlineStyles = listOf(InlineStyleRange(0, 1, InlineStyle.STRIKETHROUGH)),
      ),
    )
    val expected = byteArrayOf(
      0x41, 0x4e, 0x46, 0x4d, // ANFM
      0x01, // version 1
      0x01, // HEADING
      0x00, 0x00, 0x00, 0x01, // one range
      0x00, 0x00, 0x00, 0x00, // start 0
      0x00, 0x00, 0x00, 0x01, // end 1
      0x03, // STRIKETHROUGH
    )

    assertEquals(expected.toList(), encoded.toList())
  }

  @Test
  fun truncatedAndTrailingDataAreRejectedAsMalformed() {
    val truncated = byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), 'M'.code.toByte())
    val validWithTrailingByte = validBlob() + byteArrayOf(1)

    assertUnsupported(truncated, FormattingDecodeError.MALFORMED_DATA)
    assertUnsupported(validWithTrailingByte, FormattingDecodeError.MALFORMED_DATA)
  }

  @Test
  fun invalidRangeAndStyleIdAreRejected() {
    val invalidRange = validBlob(range = intArrayOf(0, 2, 1))
    val invalidStyle = validBlob(range = intArrayOf(0, 1, 99))

    assertUnsupported(invalidRange, FormattingDecodeError.INVALID_RANGE, text = "甲")
    assertUnsupported(invalidStyle, FormattingDecodeError.MALFORMED_DATA, text = "甲")
  }

  @Test
  fun excessiveRangeCountAndBlobSizeAreRejectedBeforeDecodingRecords() {
    val excessiveCount = validBlob(rangeCount = BlockFormattingRules.MAX_RANGES_PER_BLOCK + 1)
    val excessiveSize = ByteArray(BlockFormattingCodec.MAX_BLOB_BYTES + 1)

    assertUnsupported(excessiveCount, FormattingDecodeError.LIMIT_EXCEEDED)
    assertUnsupported(excessiveSize, FormattingDecodeError.LIMIT_EXCEEDED)
  }

  @Test
  fun headingIsRejectedForQuoteBlock() {
    val encoded = BlockFormattingCodec.encode(
      text = "引用",
      type = BlockType.TEXT,
      formatting = BlockFormatting(presentation = TextBlockPresentation.HEADING),
    )

    assertUnsupported(
      encoded,
      FormattingDecodeError.INVALID_BLOCK_STYLE,
      text = "引用",
      type = BlockType.QUOTE,
    )
  }

  private fun assertUnsupported(
    bytes: ByteArray,
    error: FormattingDecodeError,
    text: String = "正文",
    type: BlockType = BlockType.TEXT,
  ) {
    val decoded = BlockFormattingCodec.decode(bytes, text, type)

    assertTrue("预期格式数据被拒绝", decoded is BlockFormattingState.Unsupported)
    decoded as BlockFormattingState.Unsupported
    assertEquals(error, decoded.error)
    assertEquals(bytes.toList(), decoded.originalBlob.toList())
  }

  private fun validBlob(
    version: Int = 1,
    presentationId: Int = 0,
    rangeCount: Int = if (version == 1) 1 else 0,
    range: IntArray? = intArrayOf(0, 1, 1),
  ): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
      output.write(byteArrayOf('A'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), 'M'.code.toByte()))
      output.writeByte(version)
      output.writeByte(presentationId)
      output.writeInt(rangeCount)
      if (range != null) {
        output.writeInt(range[0])
        output.writeInt(range[1])
        output.writeByte(range[2])
      }
    }
    return bytes.toByteArray()
  }
}

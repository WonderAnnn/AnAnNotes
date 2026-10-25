package com.anan.anannotes.core.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object BlockFormattingCodec {
  const val MAX_BLOB_BYTES = 4 * 1024 * 1024
  private const val VERSION = 1
  private const val FIXED_HEADER_BYTES = 10
  private const val RANGE_RECORD_BYTES = 9
  private val magic = byteArrayOf(0x41, 0x4e, 0x46, 0x4d)

  fun encode(text: String, type: BlockType, formatting: BlockFormatting): ByteArray {
    val validationError = BlockFormattingRules.validate(text, type, formatting)
    require(validationError == null) { "格式数据无效：$validationError" }
    val normalized = formatting.copy(inlineStyles = BlockFormattingRules.normalize(formatting.inlineStyles))
    val encodedSize = FIXED_HEADER_BYTES.toLong() + normalized.inlineStyles.size.toLong() * RANGE_RECORD_BYTES
    require(encodedSize <= MAX_BLOB_BYTES) { "格式数据超出大小限制" }

    val bytes = ByteArrayOutputStream(encodedSize.toInt())
    DataOutputStream(bytes).use { output ->
      output.write(magic)
      output.writeByte(VERSION)
      output.writeByte(presentationId(normalized.presentation))
      output.writeInt(normalized.inlineStyles.size)
      normalized.inlineStyles.forEach { range ->
        output.writeInt(range.startUtf16)
        output.writeInt(range.endUtf16)
        output.writeByte(styleId(range.style))
      }
    }
    return bytes.toByteArray()
  }

  fun decode(blob: ByteArray, text: String, type: BlockType): BlockFormattingState {
    if (blob.size > MAX_BLOB_BYTES) return unsupported(blob, FormattingDecodeError.LIMIT_EXCEEDED)
    if (blob.size < magic.size + 1) return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)

    try {
      DataInputStream(ByteArrayInputStream(blob)).use { input ->
        val actualMagic = ByteArray(magic.size)
        input.readFully(actualMagic)
        if (!actualMagic.contentEquals(magic)) {
          return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)
        }

        val version = input.readUnsignedByte()
        if (version != VERSION) return unsupported(blob, FormattingDecodeError.UNSUPPORTED_VERSION)
        if (input.available() < FIXED_HEADER_BYTES - magic.size - 1) {
          return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)
        }

        val presentation = presentationFromId(input.readUnsignedByte())
          ?: return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)
        val count = input.readInt()
        if (count < 0 || count > BlockFormattingRules.MAX_RANGES_PER_BLOCK) {
          return unsupported(blob, FormattingDecodeError.LIMIT_EXCEEDED)
        }
        if (input.available().toLong() < count.toLong() * RANGE_RECORD_BYTES) {
          return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)
        }

        val ranges = ArrayList<InlineStyleRange>(count)
        repeat(count) {
          val start = input.readInt()
          val end = input.readInt()
          val style = styleFromId(input.readUnsignedByte())
            ?: return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)
          ranges += InlineStyleRange(start, end, style)
        }
        if (input.available() != 0) return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)

        val formatting = BlockFormatting(presentation, ranges)
        val normalized = try {
          BlockFormattingRules.normalize(ranges)
        } catch (_: IllegalArgumentException) {
          return unsupported(blob, FormattingDecodeError.INVALID_RANGE)
        }
        if (normalized != ranges) return unsupported(blob, FormattingDecodeError.INVALID_RANGE)

        val error = BlockFormattingRules.validate(text, type, formatting)
        if (error != null) return unsupported(blob, error)
        return BlockFormattingState.Supported(formatting)
      }
    } catch (_: IOException) {
      return unsupported(blob, FormattingDecodeError.MALFORMED_DATA)
    }
  }

  private fun unsupported(blob: ByteArray, error: FormattingDecodeError) =
    BlockFormattingState.Unsupported(originalBlob = blob, error = error)

  private fun presentationId(presentation: TextBlockPresentation): Int = when (presentation) {
    TextBlockPresentation.BODY -> 0
    TextBlockPresentation.HEADING -> 1
  }

  private fun presentationFromId(id: Int): TextBlockPresentation? = when (id) {
    0 -> TextBlockPresentation.BODY
    1 -> TextBlockPresentation.HEADING
    else -> null
  }

  private fun styleId(style: InlineStyle): Int = when (style) {
    InlineStyle.BOLD -> 1
    InlineStyle.ITALIC -> 2
    InlineStyle.STRIKETHROUGH -> 3
  }

  private fun styleFromId(id: Int): InlineStyle? = when (id) {
    1 -> InlineStyle.BOLD
    2 -> InlineStyle.ITALIC
    3 -> InlineStyle.STRIKETHROUGH
    else -> null
  }
}

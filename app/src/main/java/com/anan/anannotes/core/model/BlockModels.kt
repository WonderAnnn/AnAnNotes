package com.anan.anannotes.core.model

import java.util.UUID

enum class BlockType {
  TEXT,
  IMAGE,
  TODO,
  QUOTE,
  DIVIDER,
  OCR_TEXT,
}

enum class InlineStyle {
  BOLD,
  ITALIC,
  STRIKETHROUGH,
}

enum class TextBlockPresentation {
  BODY,
  HEADING,
}

enum class FormattingDecodeError {
  UNSUPPORTED_VERSION,
  MALFORMED_DATA,
  LIMIT_EXCEEDED,
  INVALID_RANGE,
  INVALID_BLOCK_STYLE,
}

data class InlineStyleRange(
  val startUtf16: Int,
  val endUtf16: Int,
  val style: InlineStyle,
)

data class BlockFormatting(
  val presentation: TextBlockPresentation = TextBlockPresentation.BODY,
  val inlineStyles: List<InlineStyleRange> = emptyList(),
)

sealed interface BlockFormattingState {
  data class Supported(val value: BlockFormatting) : BlockFormattingState

  class Unsupported(
    originalBlob: ByteArray,
    val error: FormattingDecodeError,
  ) : BlockFormattingState {
    private val storedBlob = originalBlob.copyOf()
    val originalBlob: ByteArray get() = storedBlob.copyOf()

    override fun equals(other: Any?): Boolean =
      other is Unsupported && error == other.error && storedBlob.contentEquals(other.storedBlob)

    override fun hashCode(): Int = 31 * storedBlob.contentHashCode() + error.hashCode()

    override fun toString(): String = "Unsupported(error=$error, originalBlobSize=${storedBlob.size})"
  }
}

enum class BlockAlignment {
  START,
  CENTER,
  END,
}

enum class OcrStatus {
  NOT_REQUESTED,
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED,
}

data class NoteBlockDraft(
  val id: String = UUID.randomUUID().toString(),
  val type: BlockType,
  val text: String? = null,
  val assetPath: String? = null,
  val previewPath: String? = null,
  val widthPercent: Float = 100f,
  val alignment: BlockAlignment = BlockAlignment.CENTER,
  val aspectRatio: Float? = null,
  val ocrStatus: OcrStatus? = null,
  val formatting: BlockFormattingState = BlockFormattingState.Supported(BlockFormatting()),
)

data class BlockModel(
  val id: String,
  val noteId: String,
  val position: Int,
  val type: BlockType,
  val text: String? = null,
  val assetPath: String? = null,
  val previewPath: String? = null,
  val widthPercent: Float = 100f,
  val alignment: BlockAlignment = BlockAlignment.CENTER,
  val aspectRatio: Float? = null,
  val ocrStatus: OcrStatus? = null,
  val formatting: BlockFormattingState = BlockFormattingState.Supported(BlockFormatting()),
)

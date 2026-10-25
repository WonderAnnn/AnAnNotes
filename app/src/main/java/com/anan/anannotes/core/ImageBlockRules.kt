package com.anan.anannotes.core

import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.TextSelection
import java.util.UUID
import kotlin.math.roundToInt

data class ImageDisplaySize(val widthPx: Int, val heightPx: Int)

object ImageBlockRules {
  const val MIN_WIDTH_PERCENT = 10f
  const val MAX_WIDTH_PERCENT = 100f
  const val DEFAULT_WIDTH_PERCENT = 100f

  fun normalizeWidthPercent(value: Float): Float =
    if (value.isFinite()) value.coerceIn(MIN_WIDTH_PERCENT, MAX_WIDTH_PERCENT) else DEFAULT_WIDTH_PERCENT

  fun sizeForWidth(availableWidthPx: Int, widthPercent: Float, aspectRatio: Float): ImageDisplaySize {
    require(availableWidthPx >= 0) { "图片可用宽度不能为负数" }
    val safeRatio = aspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    val width = (availableWidthPx * normalizeWidthPercent(widthPercent) / 100f).roundToInt()
    return ImageDisplaySize(widthPx = width, heightPx = (width / safeRatio).roundToInt())
  }

  fun insertAtSelection(
    blocks: List<NoteBlockDraft>,
    textBlockId: String,
    selectionStart: Int,
    selectionEnd: Int,
    image: NoteBlockDraft,
    newTextBlockId: () -> String = { UUID.randomUUID().toString() },
  ): List<NoteBlockDraft> {
    require(image.type == BlockType.IMAGE) { "插入内容必须是图片块" }
    val index = blocks.indexOfFirst { it.id == textBlockId && it.type == BlockType.TEXT }
    require(index >= 0) { "找不到当前文本块" }

    val selected = blocks[index]
    val text = selected.text.orEmpty()
    val safeSelection = BlockFormattingRules.normalizeSelection(
      text,
      TextSelection(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)),
    )
    val start = safeSelection.startUtf16
    val end = safeSelection.endUtf16
    val before = text.substring(0, start)
    val after = text.substring(end)
    val formatting = (selected.formatting as? BlockFormattingState.Supported)?.value
      ?: throw IllegalArgumentException("不支持的格式块必须先明确移除格式后才能插入图片")
    val replacedText = BlockFormattingRules.replaceText(
      oldText = text,
      newText = before + after,
      oldSelection = TextSelection(start, end),
      selection = TextSelection(start, start),
      composing = null,
      formatting = formatting,
    )
    val (beforeFormatting, afterFormatting) = BlockFormattingRules.splitFormattingAt(
      text = replacedText.text,
      formatting = replacedText.formatting,
      offsetUtf16 = start,
    )
    val inserted = image.copy(
      widthPercent = normalizeWidthPercent(image.widthPercent),
      aspectRatio = image.aspectRatio?.takeIf { it.isFinite() && it > 0f },
      formatting = BlockFormattingState.Supported(BlockFormatting()),
    )
    val replacement = buildList {
      if (before.isNotEmpty()) {
        add(selected.copy(
          text = before,
          formatting = BlockFormattingState.Supported(beforeFormatting),
        ))
      }
      add(inserted)
      if (after.isNotEmpty()) {
        add(selected.copy(
          id = if (before.isEmpty()) selected.id else newTextBlockId(),
          text = after,
          formatting = BlockFormattingState.Supported(afterFormatting),
        ))
      }
    }
    return ensureEditableTextTail(blocks.take(index) + replacement + blocks.drop(index + 1), newTextBlockId)
  }

  fun ensureEditableTextTail(
    blocks: List<NoteBlockDraft>,
    newTextBlockId: () -> String = { UUID.randomUUID().toString() },
  ): List<NoteBlockDraft> {
    if (blocks.isNotEmpty() && blocks.last().type != BlockType.IMAGE) return blocks
    return blocks + NoteBlockDraft(id = newTextBlockId(), type = BlockType.TEXT, text = "")
  }

  fun removeImageBlocks(
    blocks: List<NoteBlockDraft>,
    selectedImageBlockIds: Set<String>,
    newTextBlockId: () -> String = { UUID.randomUUID().toString() },
  ): List<NoteBlockDraft> {
    if (selectedImageBlockIds.isEmpty()) return blocks
    val remaining = blocks.filterNot { it.type == BlockType.IMAGE && it.id in selectedImageBlockIds }
    return ensureEditableTextTail(remaining, newTextBlockId)
  }
}

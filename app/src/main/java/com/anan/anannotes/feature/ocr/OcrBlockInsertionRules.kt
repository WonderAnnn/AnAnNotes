package com.anan.anannotes.feature.ocr

import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TextSelection
import java.util.UUID

enum class OcrImportMode {
  IMAGE_ONLY,
  IMAGE_AND_TEXT,
  TEXT_ONLY,
}

data class OcrBlockInsertionResult(
  val blocks: List<NoteBlockDraft>,
  val focusTextBlockId: String,
  val selectionStart: Int,
)

object OcrBlockInsertionRules {
  fun insertAtSelection(
    blocks: List<NoteBlockDraft>,
    textBlockId: String,
    selectionStart: Int,
    selectionEnd: Int,
    pendingOcrBlock: NoteBlockDraft,
    newTextBlockId: () -> String = { UUID.randomUUID().toString() },
  ): OcrBlockInsertionResult {
    require(pendingOcrBlock.type == BlockType.OCR_TEXT && pendingOcrBlock.ocrStatus == OcrStatus.PENDING) {
      "OCR 内容块必须从等待状态开始"
    }
    val index = blocks.indexOfFirst { it.id == textBlockId && it.type == BlockType.TEXT }
    require(index >= 0) { "找不到当前文本块" }
    val original = blocks[index]
    val text = original.text.orEmpty()
    val selection = BlockFormattingRules.normalizeSelection(
      text,
      TextSelection(minOf(selectionStart, selectionEnd), maxOf(selectionStart, selectionEnd)),
    )
    val start = selection.startUtf16
    val end = selection.endUtf16
    val before = text.substring(0, start)
    val after = text.substring(end)
    val formatting = (original.formatting as? BlockFormattingState.Supported)?.value
      ?: throw IllegalArgumentException("不支持的格式块必须先明确移除格式后才能插入识别文字")
    val replaced = BlockFormattingRules.replaceText(
      oldText = text,
      newText = before + after,
      oldSelection = selection,
      selection = TextSelection(start, start),
      composing = null,
      formatting = formatting,
      type = original.type,
    )
    val (beforeFormatting, afterFormatting) = BlockFormattingRules.splitFormattingAt(replaced.text, replaced.formatting, start)
    var trailingTextBlockId: String? = null
    val replacement = buildList {
      if (before.isNotEmpty()) add(original.copy(text = before, formatting = BlockFormattingState.Supported(beforeFormatting)))
      add(pendingOcrBlock.copy(text = null))
      if (after.isNotEmpty()) {
        val afterBlockId = if (before.isEmpty()) original.id else newTextBlockId()
        trailingTextBlockId = afterBlockId
        add(original.copy(
          id = afterBlockId,
          text = after,
          formatting = BlockFormattingState.Supported(afterFormatting),
        ))
      }
    }
    var updated = blocks.take(index) + replacement + blocks.drop(index + 1)
    var addedTailId: String? = null
    if (updated.lastOrNull()?.type !in EDITABLE_TEXT_TYPES) {
      val tail = NoteBlockDraft(id = newTextBlockId(), type = BlockType.TEXT, text = "")
      updated = updated + tail
      addedTailId = tail.id
    }
    val focus = trailingTextBlockId
      ?: blocks.drop(index + 1).firstOrNull { it.type == BlockType.TEXT }?.id
      ?: addedTailId
      ?: updated.lastOrNull { it.type == BlockType.TEXT }?.id
      ?: updated.lastOrNull { it.type in EDITABLE_TEXT_TYPES }?.id
      ?: error("OCR 插入后必须保留一个可编辑文本块")
    return OcrBlockInsertionResult(updated, focus, 0)
  }

  fun appendAfterImage(blocks: List<NoteBlockDraft>, imageBlockId: String, pendingOcrBlock: NoteBlockDraft): List<NoteBlockDraft> {
    require(pendingOcrBlock.type == BlockType.OCR_TEXT && pendingOcrBlock.ocrStatus == OcrStatus.PENDING)
    val imageIndex = blocks.indexOfFirst { it.id == imageBlockId && it.type == BlockType.IMAGE }
    require(imageIndex >= 0) { "找不到图片块" }
    return blocks.toMutableList().apply { add(imageIndex + 1, pendingOcrBlock.copy(text = null)) }
  }

  private val EDITABLE_TEXT_TYPES = setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT)
}

package com.anan.anannotes.core

import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.TextSelection
import java.util.UUID

object TodoBlockRules {
  private val editableTextTypes = setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT)

  fun insertAtSelection(
    blocks: List<NoteBlockDraft>,
    textBlockId: String,
    selection: TextSelection,
    newBlockId: () -> String = { UUID.randomUUID().toString() },
  ): List<NoteBlockDraft> {
    val index = blocks.indexOfFirst { it.id == textBlockId && it.type in editableTextTypes }
    require(index >= 0) { "找不到当前可编辑文字块" }

    val source = blocks[index]
    val text = source.text.orEmpty()
    val range = BlockFormattingRules.normalizeSelection(text, selection)
    val formatting = (source.formatting as? BlockFormattingState.Supported)?.value
      ?: throw IllegalArgumentException("不支持的格式块必须先明确移除格式后才能插入待办")
    val before = text.substring(0, range.startUtf16)
    val after = text.substring(range.endUtf16)
    val beforeFormatting = BlockFormattingRules.splitFormattingAt(text, formatting, range.startUtf16).first
    val afterFormatting = BlockFormattingRules.splitFormattingAt(text, formatting, range.endUtf16).second
    val todo = NoteBlockDraft(
      id = newBlockId(),
      type = BlockType.TODO,
      text = "",
      formatting = BlockFormattingState.Supported(BlockFormatting()),
    )

    val replacement = buildList {
      if (before.isNotEmpty()) {
        add(source.copy(text = before, formatting = BlockFormattingState.Supported(beforeFormatting)))
      }
      add(todo)
      if (after.isNotEmpty()) {
        add(
          source.copy(
            id = if (before.isEmpty()) source.id else newBlockId(),
            text = after,
            formatting = BlockFormattingState.Supported(afterFormatting),
          ),
        )
      } else if (before.isEmpty()) {
        add(source.copy(type = BlockType.TEXT, text = "", formatting = BlockFormattingState.Supported(BlockFormatting())))
      } else {
        add(NoteBlockDraft(id = newBlockId(), type = BlockType.TEXT, text = ""))
      }
    }
    return blocks.take(index) + replacement + blocks.drop(index + 1)
  }

  fun insertAfterTodo(
    blocks: List<NoteBlockDraft>,
    todoBlockId: String,
    newTodoBlockId: String,
  ): List<NoteBlockDraft> {
    val index = blocks.indexOfFirst { it.id == todoBlockId && it.type == BlockType.TODO }
    require(index >= 0) { "找不到当前待办块" }
    require(blocks.none { it.id == newTodoBlockId }) { "新待办块 ID 已存在" }
    val todo = NoteBlockDraft(
      id = newTodoBlockId,
      type = BlockType.TODO,
      text = "",
      formatting = BlockFormattingState.Supported(BlockFormatting()),
    )
    return blocks.take(index + 1) + todo + blocks.drop(index + 1)
  }

  fun completedIdsForPersistedTodoBlocks(
    completedIds: Set<String>,
    persistedBlocks: List<NoteBlockDraft>,
  ): Set<String> {
    val persistedTodoIds = persistedBlocks.asSequence()
      .filter { it.type == BlockType.TODO }
      .map { it.id }
      .toSet()
    return completedIds intersect persistedTodoIds
  }
}

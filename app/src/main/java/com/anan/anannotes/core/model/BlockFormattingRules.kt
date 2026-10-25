package com.anan.anannotes.core.model

data class TextSelection(
  val startUtf16: Int,
  val endUtf16: Int,
) {
  init {
    require(startUtf16 <= endUtf16) { "选区起点不能晚于终点" }
  }
}

data class TextEditResult(
  val text: String,
  val selection: TextSelection,
  val composing: TextSelection?,
  val formatting: BlockFormatting,
)

object BlockFormattingRules {
  const val MAX_RANGES_PER_BLOCK = 100_000

  private val inlineFormatBlockTypes = setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT)

  fun normalize(ranges: List<InlineStyleRange>): List<InlineStyleRange> {
    require(ranges.size <= MAX_RANGES_PER_BLOCK) { "格式区间数量超限" }
    ranges.forEach { range ->
      require(range.startUtf16 >= 0 && range.startUtf16 < range.endUtf16) { "格式区间无效" }
    }

    val merged = buildList {
      InlineStyle.entries.forEach { style ->
        val sorted = ranges.asSequence()
          .filter { it.style == style }
          .sortedWith(compareBy<InlineStyleRange> { it.startUtf16 }.thenBy { it.endUtf16 })
          .toList()
        var start = -1
        var end = -1
        sorted.forEach { range ->
          if (start < 0) {
            start = range.startUtf16
            end = range.endUtf16
          } else if (range.startUtf16 <= end) {
            end = maxOf(end, range.endUtf16)
          } else {
            add(InlineStyleRange(start, end, style))
            start = range.startUtf16
            end = range.endUtf16
          }
        }
        if (start >= 0) add(InlineStyleRange(start, end, style))
      }
    }
    require(merged.size <= MAX_RANGES_PER_BLOCK) { "格式区间数量超限" }
    return merged.sortedWith(
      compareBy<InlineStyleRange> { it.startUtf16 }
        .thenBy { it.endUtf16 }
        .thenBy { it.style.name },
    )
  }

  fun validate(
    text: String,
    type: BlockType,
    state: BlockFormattingState,
  ): FormattingDecodeError? = when (state) {
    is BlockFormattingState.Unsupported -> state.error
    is BlockFormattingState.Supported -> validate(text, type, state.value)
  }

  fun validate(
    text: String,
    type: BlockType,
    formatting: BlockFormatting,
  ): FormattingDecodeError? {
    if (formatting.inlineStyles.size > MAX_RANGES_PER_BLOCK) {
      return FormattingDecodeError.LIMIT_EXCEEDED
    }
    if (formatting.presentation == TextBlockPresentation.HEADING && type != BlockType.TEXT) {
      return FormattingDecodeError.INVALID_BLOCK_STYLE
    }
    if (type !in inlineFormatBlockTypes && formatting.inlineStyles.isNotEmpty()) {
      return FormattingDecodeError.INVALID_BLOCK_STYLE
    }
    if (type !in inlineFormatBlockTypes && formatting.presentation != TextBlockPresentation.BODY) {
      return FormattingDecodeError.INVALID_BLOCK_STYLE
    }

    for (range in formatting.inlineStyles) {
      if (range.startUtf16 < 0 || range.startUtf16 >= range.endUtf16 || range.endUtf16 > text.length) {
        return FormattingDecodeError.INVALID_RANGE
      }
      if (!isSafeBoundary(text, range.startUtf16) || !isSafeBoundary(text, range.endUtf16)) {
        return FormattingDecodeError.INVALID_RANGE
      }
    }
    return null
  }

  fun setInlineStyle(
    text: String,
    formatting: BlockFormatting,
    selection: TextSelection,
    style: InlineStyle,
    enabled: Boolean,
    type: BlockType = BlockType.TEXT,
  ): BlockFormatting {
    requireValid(text, type, formatting)
    val safeSelection = normalizeSelection(text, selection)
    if (safeSelection.startUtf16 == safeSelection.endUtf16) return formatting

    val adjusted = formatting.inlineStyles.flatMap { range ->
      if (range.style != style || enabled || !overlaps(range, safeSelection)) {
        listOf(range)
      } else {
        buildList {
          if (range.startUtf16 < safeSelection.startUtf16) {
            add(range.copy(endUtf16 = safeSelection.startUtf16))
          }
          if (range.endUtf16 > safeSelection.endUtf16) {
            add(range.copy(startUtf16 = safeSelection.endUtf16))
          }
        }
      }
    }
    val updated = if (enabled) {
      adjusted + InlineStyleRange(safeSelection.startUtf16, safeSelection.endUtf16, style)
    } else {
      adjusted
    }
    return formatting.copy(inlineStyles = normalize(updated))
  }

  fun replaceText(
    oldText: String,
    newText: String,
    oldSelection: TextSelection,
    selection: TextSelection,
    composing: TextSelection?,
    formatting: BlockFormatting,
    typingOverrides: Map<InlineStyle, Boolean> = emptyMap(),
    type: BlockType = BlockType.TEXT,
  ): TextEditResult {
    requireValid(oldText, type, formatting)
    val safeOldSelection = normalizeSelection(oldText, oldSelection)
    val safeSelection = normalizeSelection(newText, selection)
    val safeComposing = composing?.let { normalizeSelection(newText, it) }
    if (oldText == newText) {
      return TextEditResult(newText, safeSelection, safeComposing, formatting)
    }

    val changed = changedRegion(oldText, newText, safeOldSelection)
    val removedStart = changed.oldStart
    val removedEnd = changed.oldEnd
    val insertedStart = changed.newStart
    val insertedEnd = changed.newEnd
    val delta = (insertedEnd - insertedStart) - (removedEnd - removedStart)

    val shiftedRanges = formatting.inlineStyles.flatMap { range ->
      when {
        range.endUtf16 <= removedStart -> listOf(range)
        range.startUtf16 >= removedEnd && removedStart != removedEnd ->
          listOf(range.copy(startUtf16 = range.startUtf16 + delta, endUtf16 = range.endUtf16 + delta))
        removedStart == removedEnd && range.startUtf16 >= removedStart ->
          listOf(range.copy(startUtf16 = range.startUtf16 + delta, endUtf16 = range.endUtf16 + delta))
        else -> buildList {
          if (range.startUtf16 < removedStart) {
            add(range.copy(endUtf16 = removedStart))
          }
          if (range.endUtf16 > removedEnd) {
            add(range.copy(startUtf16 = insertedEnd, endUtf16 = range.endUtf16 + delta))
          }
        }
      }
    }

    val insertedRanges = if (insertedStart < insertedEnd) {
      InlineStyle.entries.mapNotNull { style ->
        val sourceIndex = when {
          removedStart > 0 -> removedStart - 1
          removedEnd < oldText.length -> removedEnd
          else -> null
        }
        val inherited = sourceIndex != null && formatting.inlineStyles.any {
          it.style == style && it.startUtf16 <= sourceIndex && sourceIndex < it.endUtf16
        }
        val enabled = typingOverrides[style] ?: inherited
        if (enabled) InlineStyleRange(insertedStart, insertedEnd, style) else null
      }
    } else {
      emptyList()
    }

    val updated = formatting.copy(inlineStyles = normalize(shiftedRanges + insertedRanges))
    requireValid(newText, type, updated)
    return TextEditResult(
      text = newText,
      selection = safeSelection,
      composing = safeComposing,
      formatting = updated,
    )
  }

  fun normalizeSelection(text: String, selection: TextSelection): TextSelection {
    requireSelection(text, selection)
    if (selection.startUtf16 == selection.endUtf16) {
      val offset = if (isSafeBoundary(text, selection.startUtf16)) {
        selection.startUtf16
      } else {
        selection.startUtf16 - 1
      }
      return TextSelection(offset, offset)
    }

    val start = if (isSafeBoundary(text, selection.startUtf16)) selection.startUtf16 else selection.startUtf16 - 1
    val end = if (isSafeBoundary(text, selection.endUtf16)) selection.endUtf16 else selection.endUtf16 + 1
    return TextSelection(start, end)
  }

  fun isStyleActiveAtCaret(
    text: String,
    formatting: BlockFormatting,
    style: InlineStyle,
    offsetUtf16: Int,
    type: BlockType = BlockType.TEXT,
  ): Boolean {
    requireValid(text, type, formatting)
    val caret = normalizeSelection(text, TextSelection(offsetUtf16, offsetUtf16)).startUtf16
    val sourceIndex = when {
      caret > 0 -> caret - 1
      caret < text.length -> caret
      else -> return false
    }
    return formatting.inlineStyles.any {
      it.style == style && it.startUtf16 <= sourceIndex && sourceIndex < it.endUtf16
    }
  }

  fun splitFormattingAt(
    text: String,
    formatting: BlockFormatting,
    offsetUtf16: Int,
  ): Pair<BlockFormatting, BlockFormatting> {
    requireValid(text, BlockType.TEXT, formatting)
    require(offsetUtf16 in 0..text.length && isSafeBoundary(text, offsetUtf16)) {
      "拆分位置不是有效的 UTF-16 字符边界"
    }

    val leftRanges = formatting.inlineStyles.mapNotNull { range ->
      val start = range.startUtf16
      val end = minOf(range.endUtf16, offsetUtf16)
      if (start < end) range.copy(endUtf16 = end) else null
    }
    val rightRanges = formatting.inlineStyles.mapNotNull { range ->
      val start = maxOf(range.startUtf16, offsetUtf16)
      val end = range.endUtf16
      if (start < end) range.copy(startUtf16 = start - offsetUtf16, endUtf16 = end - offsetUtf16) else null
    }
    return formatting.copy(inlineStyles = normalize(leftRanges)) to
      formatting.copy(inlineStyles = normalize(rightRanges))
  }

  private data class ChangedRegion(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
  )

  private fun changedRegion(oldText: String, newText: String, oldSelection: TextSelection): ChangedRegion {
    val oldPrefix = oldText.substring(0, oldSelection.startUtf16)
    val oldSuffix = oldText.substring(oldSelection.endUtf16)
    val selectionAnchored = newText.startsWith(oldPrefix) && newText.endsWith(oldSuffix)
    if (selectionAnchored) {
      val newEnd = newText.length - oldSuffix.length
      if (newEnd >= oldSelection.startUtf16 && isSafeBoundary(newText, oldSelection.startUtf16) &&
        isSafeBoundary(newText, newEnd)
      ) {
        return ChangedRegion(oldSelection.startUtf16, oldSelection.endUtf16, oldSelection.startUtf16, newEnd)
      }
    }

    var prefix = 0
    val commonLength = minOf(oldText.length, newText.length)
    while (prefix < commonLength && oldText[prefix] == newText[prefix]) prefix++
    while (prefix > 0 && (!isSafeBoundary(oldText, prefix) || !isSafeBoundary(newText, prefix))) prefix--

    var oldEnd = oldText.length
    var newEnd = newText.length
    while (oldEnd > prefix && newEnd > prefix && oldText[oldEnd - 1] == newText[newEnd - 1]) {
      oldEnd--
      newEnd--
    }
    while ((!isSafeBoundary(oldText, oldEnd) || !isSafeBoundary(newText, newEnd)) &&
      oldEnd < oldText.length && newEnd < newText.length
    ) {
      oldEnd++
      newEnd++
    }
    require(isSafeBoundary(oldText, oldEnd) && isSafeBoundary(newText, newEnd)) {
      "文本变更切开 UTF-16 代理项对"
    }
    return ChangedRegion(prefix, oldEnd, prefix, newEnd)
  }

  private fun requireValid(text: String, type: BlockType, formatting: BlockFormatting) {
    val error = validate(text, type, formatting)
    require(error == null) { "格式数据无效：$error" }
  }

  private fun requireSelection(text: String, selection: TextSelection) {
    require(selection.startUtf16 >= 0 && selection.endUtf16 <= text.length) { "选区超出正文范围" }
  }

  private fun isSafeBoundary(text: String, offset: Int): Boolean =
    offset == 0 || offset == text.length ||
      !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

  private fun overlaps(range: InlineStyleRange, selection: TextSelection): Boolean =
    range.startUtf16 < selection.endUtf16 && selection.startUtf16 < range.endUtf16
}

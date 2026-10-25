package com.anan.anannotes.feature.export

import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.TextBlockPresentation
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

data class ExportPixelSize(val width: Int, val height: Int)

data class ExportPdfPageSize(val widthPoints: Float, val heightPoints: Float)

/** Pure export layout and text serialization rules shared by file renderers. */
object ExportLayout {
  const val ATTRIBUTION_TEXT = "来自安安简记"
  private const val MILLIMETERS_PER_INCH = 25.4
  private const val A4_WIDTH_MM = 210.0
  private const val A4_HEIGHT_MM = 297.0
  private const val MAX_FILENAME_SEGMENT_LENGTH = 64
  private const val MAX_BASE_FILENAME_UTF8_BYTES = 180
  private const val MAX_LONG_IMAGE_SEGMENT_COUNT = 4_096
  private const val IMAGE_DIRECTORY = "images/"
  private val illegalFileNameCharacters = Regex("[<>:\"/\\\\|?*\\p{Cc}]")
  private val repeatedSeparators = Regex("_+")

  val a4PdfPageSize = ExportPdfPageSize(
    widthPoints = (A4_WIDTH_MM / MILLIMETERS_PER_INCH * 72.0).toFloat(),
    heightPoints = (A4_HEIGHT_MM / MILLIMETERS_PER_INCH * 72.0).toFloat(),
  )

  fun a4PngSize(dpi: Int): ExportPixelSize {
    require(dpi > 0) { "DPI 必须大于 0" }
    return ExportPixelSize(
      width = ceil(A4_WIDTH_MM / MILLIMETERS_PER_INCH * dpi).toInt(),
      height = ceil(A4_HEIGHT_MM / MILLIMETERS_PER_INCH * dpi).toInt(),
    )
  }

  fun pngSize(format: ExportFormat): ExportPixelSize = when (format) {
    ExportFormat.PNG_PAGED_600_DPI -> a4PngSize(dpi = 600)
    ExportFormat.PNG_SOCIAL_SQUARE -> ExportPixelSize(width = 1080, height = 1080)
    ExportFormat.PNG_SOCIAL_PORTRAIT -> ExportPixelSize(width = 1080, height = 1350)
    else -> throw IllegalArgumentException("该格式不是固定尺寸 PNG")
  }

  fun imageSampleSize(width: Int, height: Int, maxDecodeEdge: Int): Int {
    require(width > 0 && height > 0) { "图片尺寸必须大于 0" }
    require(maxDecodeEdge > 0) { "解码尺寸上限必须大于 0" }
    var sample = 1
    while (width.toLong() / sample > maxDecodeEdge || height.toLong() / sample > maxDecodeEdge) {
      sample *= 2
    }
    return sample
  }

  fun splitLongImage(totalHeightPx: Int, maxSegmentHeightPx: Int): List<Int> {
    require(totalHeightPx > 0) { "长图高度必须大于 0" }
    require(maxSegmentHeightPx > 0) { "分段高度必须大于 0" }
    val segmentCount = (totalHeightPx - 1) / maxSegmentHeightPx + 1
    require(segmentCount <= MAX_LONG_IMAGE_SEGMENT_COUNT) { "长图超过安全分段上限，无法导出" }
    val segments = ArrayList<Int>(segmentCount)
    var remaining = totalHeightPx
    while (remaining > 0) {
      val segmentHeight = minOf(remaining, maxSegmentHeightPx)
      segments += segmentHeight
      remaining -= segmentHeight
    }
    return segments
  }

  fun defaultBaseFileName(folderName: String, title: String, date: LocalDate): String {
    var safeFolder = safeFileNameSegment(folderName, fallback = "未分类")
    var safeTitle = safeFileNameSegment(title, fallback = "无标题笔记")
    fun completeName() = "${safeFolder}_${safeTitle}_${date}"
    while (completeName().toByteArray(Charsets.UTF_8).size > MAX_BASE_FILENAME_UTF8_BYTES) {
      if (safeFolder.toByteArray(Charsets.UTF_8).size >= safeTitle.toByteArray(Charsets.UTF_8).size &&
        safeFolder.codePointCount(0, safeFolder.length) > 1
      ) {
        safeFolder = safeFolder.dropLastCodePoint()
      } else if (safeTitle.codePointCount(0, safeTitle.length) > 1) {
        safeTitle = safeTitle.dropLastCodePoint()
      } else {
        break
      }
    }
    return completeName()
  }

  fun uniqueFileName(requestedName: String, existingNames: Set<String>): String {
    val normalizedExisting = existingNames.mapTo(hashSetOf()) { it.lowercase() }
    if (requestedName.lowercase() !in normalizedExisting) return requestedName
    val dot = requestedName.lastIndexOf('.').takeIf { it > 0 }
    val stem = if (dot == null) requestedName else requestedName.substring(0, dot)
    val extension = if (dot == null) "" else requestedName.substring(dot)
    var sequence = 2
    while (true) {
      val candidate = "$stem ($sequence)$extension"
      if (candidate.lowercase() !in normalizedExisting) return candidate
      sequence++
    }
  }

  fun toPlainText(document: NoteDocument): String {
    val blocks = document.blocks.sortedBy(BlockModel::position).map { block ->
      NoteBlockDraft(id = block.id, type = block.type, text = block.text)
    }
    val completedTodoBlockIds = document.todos.asSequence()
      .filter { it.isCompleted }
      .mapTo(hashSetOf()) { it.blockId }
    return toPlainText(document.note.title, blocks, completedTodoBlockIds)
  }

  /** Serializes the live editor draft, including edits that have not yet been saved. */
  fun toPlainText(
    title: String,
    blocks: List<NoteBlockDraft>,
    completedTodoBlockIds: Set<String> = emptySet(),
  ): String {
    val body = blocks.mapNotNull { block ->
      val text = block.text.orEmpty()
      when (block.type) {
        BlockType.TEXT, BlockType.OCR_TEXT -> text.takeIf(String::isNotBlank)
        BlockType.QUOTE -> text.takeIf(String::isNotBlank)?.let { prefixLines(it, "> ") }
        BlockType.TODO -> text.takeIf(String::isNotBlank)?.let {
          val check = if (block.id in completedTodoBlockIds) "x" else " "
          "- [$check] $it"
        }
        BlockType.IMAGE, BlockType.DIVIDER -> null
      }
    }.joinToString("\n\n")

    return buildString {
      append(title.ifBlank { "无标题笔记" })
      if (body.isNotBlank()) append("\n\n").append(body)
    }
  }

  fun toMarkdown(document: NoteDocument): String {
    val todoByBlockId = document.todos.associateBy { it.blockId }
    val orderedBlocks = document.blocks.sortedBy(BlockModel::position)
    val imageNumbers = orderedBlocks.filter { it.type == BlockType.IMAGE }
      .mapIndexed { index, block -> block.id to index + 1 }
      .toMap()
    val body = orderedBlocks.mapNotNull { block ->
      val text = block.text.orEmpty()
      when (block.type) {
        BlockType.TEXT -> text.takeIf(String::isNotBlank)?.let { value ->
          val formatted = applyInlineMarkdown(value, block.formatting)
          val presentation = (block.formatting as? BlockFormattingState.Supported)?.value?.presentation
          if (presentation == TextBlockPresentation.HEADING) "## $formatted" else formatted
        }
        BlockType.OCR_TEXT -> text.takeIf(String::isNotBlank)?.let(::escapeMarkdownText)
        BlockType.QUOTE -> text.takeIf(String::isNotBlank)?.let { prefixLines(escapeMarkdownText(it), "> ") }
        BlockType.TODO -> text.takeIf(String::isNotBlank)?.let {
          val check = if (todoByBlockId[block.id]?.isCompleted == true) "x" else " "
          "- [$check] ${escapeMarkdownText(it).replace("\n", "\n  ")}"
        }
        BlockType.IMAGE -> block.assetPath?.let { assetPath ->
          "![图片](${IMAGE_DIRECTORY}${imageEntryName(imageNumbers.getValue(block.id), assetPath)})"
        }
        BlockType.DIVIDER -> "---"
      }
    }.joinToString("\n\n")

    return buildString {
      append("# ").append(escapeMarkdownText(document.note.title.ifBlank { "无标题笔记" }))
      if (body.isNotBlank()) append("\n\n").append(body)
    }
  }

  /** Writes a Markdown document and its original image bytes into a portable ZIP archive. */
  fun writeMarkdownZip(
    output: OutputStream,
    document: NoteDocument,
    openImage: (BlockModel) -> InputStream?,
  ) {
    val images = document.blocks.sortedBy(BlockModel::position).filter { it.type == BlockType.IMAGE }
    ZipOutputStream(output).use { zip ->
      val markdownName = "${safeFileNameSegment(document.note.title, "无标题笔记")}.md"
      zip.putNextEntry(ZipEntry(markdownName))
      zip.write(toMarkdown(document).toByteArray(Charsets.UTF_8))
      zip.closeEntry()

      if (images.isNotEmpty()) {
        zip.putNextEntry(ZipEntry(IMAGE_DIRECTORY))
        zip.closeEntry()
      }
      images.forEachIndexed { index, block ->
        val assetPath = block.assetPath ?: throw ExportAssetReadException("图片块缺少原图路径")
        val image = openImage(block) ?: throw ExportAssetReadException("无法读取笔记中的原图")
        image.use { input ->
          zip.putNextEntry(ZipEntry(IMAGE_DIRECTORY + imageEntryName(index + 1, assetPath)))
          input.copyTo(zip)
          zip.closeEntry()
        }
      }
      zip.finish()
    }
  }

  private fun safeFileNameSegment(value: String, fallback: String): String {
    val sanitized = value.trim()
      .replace(illegalFileNameCharacters, "_")
      .replace(repeatedSeparators, "_")
      .trim(' ', '.', '_')
      .takeCodePoints(MAX_FILENAME_SEGMENT_LENGTH)
      .trimEnd(' ', '.', '_')
    return sanitized.ifBlank { fallback }
  }

  private fun String.takeCodePoints(maxCodePoints: Int): String {
    val count = minOf(maxCodePoints, codePointCount(0, length))
    return substring(0, offsetByCodePoints(0, count))
  }

  private fun String.dropLastCodePoint(): String {
    val count = codePointCount(0, length)
    return substring(0, offsetByCodePoints(0, count - 1))
  }

  private fun escapeMarkdownText(text: String): String = buildString(text.length) {
    text.forEach { character ->
      when (character) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~' -> {
          append('\\').append(character)
        }
        else -> append(character)
      }
    }
  }

  private fun escapeHtmlTextCharacter(character: Char): String = when (character) {
    '&' -> "&amp;"
    '<' -> "&lt;"
    '>' -> "&gt;"
    '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '|', '~' -> "&#${character.code};"
    else -> character.toString()
  }

  private fun imageEntryName(number: Int, assetPath: String): String {
    val extension = assetPath.substringAfterLast('.', "bin")
      .lowercase()
      .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
      ?: "bin"
    return "image-${number.toString().padStart(3, '0')}.$extension"
  }

  private fun prefixLines(text: String, prefix: String): String =
    text.split('\n').joinToString("\n") { prefix + it }

  private fun applyInlineMarkdown(text: String, formattingState: BlockFormattingState): String {
    val formatting = (formattingState as? BlockFormattingState.Supported)?.value ?: return escapeMarkdownText(text)
    val validationError = BlockFormattingRules.validate(text, BlockType.TEXT, formatting)
    require(validationError == null) { "文本格式范围无效，无法安全导出 Markdown" }
    val ranges = BlockFormattingRules.normalize(formatting.inlineStyles)
    val overlapsAcrossStyles = hasOverlappingStyleRanges(ranges)
    val openMarkers = if (overlapsAcrossStyles) {
      mapOf(
        InlineStyle.BOLD to "<strong>",
        InlineStyle.ITALIC to "<em>",
        InlineStyle.STRIKETHROUGH to "<del>",
      )
    } else {
      mapOf(
        InlineStyle.BOLD to "**",
        InlineStyle.ITALIC to "*",
        InlineStyle.STRIKETHROUGH to "~~",
      )
    }
    val closeMarkers = if (overlapsAcrossStyles) {
      mapOf(
        InlineStyle.BOLD to "</strong>",
        InlineStyle.ITALIC to "</em>",
        InlineStyle.STRIKETHROUGH to "</del>",
      )
    } else {
      openMarkers
    }
    val styleEvents = ranges.flatMap { range ->
      listOf(
        InlineStyleEvent(range.startUtf16, range.style, delta = 1),
        InlineStyleEvent(range.endUtf16, range.style, delta = -1),
      )
    }.sortedWith(compareBy<InlineStyleEvent> { it.offset }.thenBy { it.delta }.thenBy { it.style.ordinal })
    val activeCounts = InlineStyle.entries.associateWith { 0 }.toMutableMap()
    var eventIndex = 0
    var activeStyles = emptyList<InlineStyle>()

    return buildString {
      for (index in text.indices) {
        while (eventIndex < styleEvents.size && styleEvents[eventIndex].offset == index) {
          val event = styleEvents[eventIndex++]
          activeCounts[event.style] = activeCounts.getValue(event.style) + event.delta
        }
        val nextStyles = InlineStyle.entries.filter { activeCounts.getValue(it) > 0 }
        var commonPrefixLength = 0
        while (commonPrefixLength < minOf(activeStyles.size, nextStyles.size) &&
          activeStyles[commonPrefixLength] == nextStyles[commonPrefixLength]
        ) {
          commonPrefixLength++
        }
        for (activeIndex in activeStyles.lastIndex downTo commonPrefixLength) {
          append(closeMarkers.getValue(activeStyles[activeIndex]))
        }
        for (activeIndex in commonPrefixLength..nextStyles.lastIndex) {
          append(openMarkers.getValue(nextStyles[activeIndex]))
        }
        append(
          if (overlapsAcrossStyles) escapeHtmlTextCharacter(text[index]) else escapeMarkdownText(text[index].toString()),
        )
        activeStyles = nextStyles
      }
      for (activeIndex in activeStyles.lastIndex downTo 0) {
        append(closeMarkers.getValue(activeStyles[activeIndex]))
      }
    }
  }

  private fun hasOverlappingStyleRanges(ranges: List<com.anan.anannotes.core.model.InlineStyleRange>): Boolean {
    val styleEvents = ranges.flatMap { range ->
      listOf(
        InlineStyleEvent(range.startUtf16, range.style, delta = 1),
        InlineStyleEvent(range.endUtf16, range.style, delta = -1),
      )
    }.sortedWith(compareBy<InlineStyleEvent> { it.offset }.thenBy { it.delta }.thenBy { it.style.ordinal })
    val activeCounts = InlineStyle.entries.associateWith { 0 }.toMutableMap()
    styleEvents.forEach { event ->
      if (event.delta < 0) {
        activeCounts[event.style] = activeCounts.getValue(event.style) + event.delta
      } else {
        if (activeCounts.any { (style, count) -> style != event.style && count > 0 }) return true
        activeCounts[event.style] = activeCounts.getValue(event.style) + event.delta
      }
    }
    return false
  }

  private data class InlineStyleEvent(val offset: Int, val style: InlineStyle, val delta: Int)
}

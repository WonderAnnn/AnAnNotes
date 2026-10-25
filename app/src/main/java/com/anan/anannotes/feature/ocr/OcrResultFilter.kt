package com.anan.anannotes.feature.ocr

import kotlin.math.ceil

internal data class OcrLineEvidence(
  val text: String,
  val confidence: Float,
  val top: Int,
  val bottom: Int,
)

internal data class OcrTextCandidate(
  val lines: List<OcrLineEvidence>,
  val meanConfidence: Float,
)

/** Filters only highly recognizable phone chrome patterns and tiny, low-confidence edge debris. */
internal object OcrResultFilter {
  fun clean(lines: List<OcrLineEvidence>, imageHeight: Int): String =
    retainedLines(lines, imageHeight)
      .map { it.text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trimEnd() }
      .filter(String::isNotBlank)
      .joinToString("\n")

  internal fun retainedLines(lines: List<OcrLineEvidence>, imageHeight: Int): List<OcrLineEvidence> =
    lines.filterNot { line -> isPhoneStatusOverlay(line, imageHeight) || isTinySymbolNoise(line, imageHeight) }

  private fun isPhoneStatusOverlay(line: OcrLineEvidence, imageHeight: Int): Boolean {
    if (imageHeight <= 0) return false
    val text = line.text.trim()
    if (text.length > MAX_STATUS_LINE_CHARS) return false
    val lineHeight = (line.bottom - line.top).coerceAtLeast(0)
    val edgeBand = (imageHeight * STATUS_EDGE_RATIO).toInt()
    val maxLineHeight = maxOf(MIN_STATUS_LINE_HEIGHT_PX, (imageHeight * STATUS_HEIGHT_RATIO).toInt())
    val isAtEdge = line.top <= edgeBand || line.bottom >= imageHeight - edgeBand
    if (!isAtEdge || lineHeight > maxLineHeight) return false

    val recognizedStatusCategories = listOf(
      TIME_PATTERN.containsMatchIn(text),
      NETWORK_PATTERN.containsMatchIn(text),
      BATTERY_PATTERN.containsMatchIn(text),
    ).count { it }
    return recognizedStatusCategories == STATUS_CATEGORIES_COUNT ||
      (recognizedStatusCategories >= MIN_STATUS_CATEGORIES && line.confidence <= MAX_PARTIAL_STATUS_CONFIDENCE)
  }

  private fun isTinySymbolNoise(line: OcrLineEvidence, imageHeight: Int): Boolean {
    if (imageHeight <= 0 || line.confidence > MAX_SYMBOL_NOISE_CONFIDENCE) return false
    val lineHeight = (line.bottom - line.top).coerceAtLeast(0)
    val maxLineHeight = maxOf(MIN_SYMBOL_HEIGHT_PX, (imageHeight * SYMBOL_HEIGHT_RATIO).toInt())
    val edgeBand = (imageHeight * STATUS_EDGE_RATIO).toInt()
    val isAtEdge = line.top <= edgeBand || line.bottom >= imageHeight - edgeBand
    val containsReadableCharacter = line.text.any(Char::isLetterOrDigit)
    return isAtEdge && lineHeight <= maxLineHeight && !containsReadableCharacter
  }

  private const val MAX_STATUS_LINE_CHARS = 32
  private const val MIN_STATUS_CATEGORIES = 2
  private const val MIN_STATUS_LINE_HEIGHT_PX = 28
  private const val MIN_SYMBOL_HEIGHT_PX = 12
  private const val MAX_SYMBOL_NOISE_CONFIDENCE = 15f
  private const val STATUS_EDGE_RATIO = 0.06f
  private const val STATUS_HEIGHT_RATIO = 0.04f
  private const val SYMBOL_HEIGHT_RATIO = 0.018f
  private val TIME_PATTERN = Regex("(?<!\\d)\\d{1,2}:\\d{2}(?!\\d)")
  private val NETWORK_PATTERN = Regex("(?i)(?:\\b[2-6]G\\b|\\bLTE\\b|\\bVoLTE\\b|\\bWi[ -]?Fi\\b)")
  private val BATTERY_PATTERN = Regex("(?<!\\d)\\d{1,3}\\s?%")
}

/** Selects the enhanced pass only when it improves confidence without truncating the page. */
internal object OcrCandidateSelector {
  fun choose(original: OcrTextCandidate, enhanced: OcrTextCandidate, imageHeight: Int): OcrTextCandidate =
    choose(original, imageHeight, enhanced, imageHeight)

  fun choose(
    original: OcrTextCandidate,
    originalImageHeight: Int,
    enhanced: OcrTextCandidate,
    enhancedImageHeight: Int,
  ): OcrTextCandidate {
    val originalLines = OcrResultFilter.retainedLines(original.lines, originalImageHeight)
    val enhancedLines = OcrResultFilter.retainedLines(enhanced.lines, enhancedImageHeight)
    if (enhancedLines.isEmpty()) return original

    val originalCharacters = originalLines.sumOf { line -> line.text.count { !it.isWhitespace() } }
    val enhancedCharacters = enhancedLines.sumOf { line -> line.text.count { !it.isWhitespace() } }
    if (originalCharacters > 0 && enhancedCharacters < ceil(originalCharacters * MIN_CHARACTER_COVERAGE).toInt()) {
      return original
    }
    if (originalLines.isNotEmpty() && enhancedLines.size < ceil(originalLines.size * MIN_LINE_COVERAGE).toInt()) {
      return original
    }
    if (
      contentSimilarity(originalLines, originalImageHeight, enhancedLines, enhancedImageHeight) < MIN_CONTENT_SIMILARITY
    ) return original

    return if (enhanced.meanConfidence >= original.meanConfidence + MIN_CONFIDENCE_GAIN) enhanced else original
  }

  private fun contentSimilarity(
    originalLines: List<OcrLineEvidence>,
    originalImageHeight: Int,
    enhancedLines: List<OcrLineEvidence>,
    enhancedImageHeight: Int,
  ): Double {
    val enhancedTextByLine = enhancedLines.map { contentCharacters(it.text) }
    val originalTextByLine = originalLines.map { contentCharacters(it.text) }
    val enhancedCharacters = enhancedTextByLine.sumOf(String::length)
    val originalCharacters = originalTextByLine.sumOf(String::length)
    if (originalCharacters == 0) return 1.0
    if (enhancedCharacters == 0) return 0.0

    val usedEnhancedLines = BooleanArray(enhancedLines.size)
    var matchedCharacters = 0
    var consideredOriginalCharacters = 0
    val maxVerticalDistanceRatio = maxOf(
      MIN_LINE_MATCH_DISTANCE_PX.toDouble() / originalImageHeight.coerceAtLeast(1),
      MIN_LINE_MATCH_DISTANCE_PX.toDouble() / enhancedImageHeight.coerceAtLeast(1),
      MAX_LINE_MATCH_DISTANCE_RATIO.toDouble(),
    )
    originalLines.forEachIndexed { originalIndex, originalLine ->
      val originalText = originalTextByLine[originalIndex]
      if (originalText.isEmpty()) return@forEachIndexed
      consideredOriginalCharacters += originalText.length

      val nearestEnhancedIndex = enhancedLines.indices
        .filterNot { usedEnhancedLines[it] }
        .minByOrNull { enhancedIndex ->
          val enhancedLine = enhancedLines[enhancedIndex]
          kotlin.math.abs(
            lineCenter(originalLine).toDouble() / originalImageHeight.coerceAtLeast(1) -
              lineCenter(enhancedLine).toDouble() / enhancedImageHeight.coerceAtLeast(1),
          )
        } ?: return@forEachIndexed
      val enhancedLine = enhancedLines[nearestEnhancedIndex]
      val verticalDistanceRatio = kotlin.math.abs(
        lineCenter(originalLine).toDouble() / originalImageHeight.coerceAtLeast(1) -
          lineCenter(enhancedLine).toDouble() / enhancedImageHeight.coerceAtLeast(1),
      )
      if (verticalDistanceRatio > maxVerticalDistanceRatio) {
        return@forEachIndexed
      }

      usedEnhancedLines[nearestEnhancedIndex] = true
      matchedCharacters += commonCharacterCount(originalText, enhancedTextByLine[nearestEnhancedIndex])
    }

    val originalCoverage = matchedCharacters.toDouble() / consideredOriginalCharacters.coerceAtLeast(1)
    val enhancedCoverage = matchedCharacters.toDouble() / enhancedCharacters
    return minOf(originalCoverage, enhancedCoverage)
  }

  private fun contentCharacters(text: String): String = buildString {
    text.forEach { character ->
      if (character.isLetterOrDigit()) append(character.lowercaseChar())
    }
  }

  private fun lineCenter(line: OcrLineEvidence): Int = line.top + (line.bottom - line.top) / 2

  private fun commonCharacterCount(left: String, right: String): Int {
    if (left.isEmpty() || right.isEmpty()) return 0
    var previous = IntArray(right.length + 1) { it }
    for (leftIndex in left.indices) {
      val current = IntArray(right.length + 1)
      current[0] = leftIndex + 1
      for (rightIndex in right.indices) {
        val substitution = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
        current[rightIndex + 1] = minOf(previous[rightIndex + 1] + 1, current[rightIndex] + 1, substitution)
      }
      previous = current
    }
    return maxOf(left.length, right.length) - previous[right.length]
  }

  private const val MIN_CHARACTER_COVERAGE = 0.78
  private const val MIN_LINE_COVERAGE = 0.65
  private const val MIN_CONFIDENCE_GAIN = 5f
  private const val MIN_CONTENT_SIMILARITY = 0.55
  private const val MIN_LINE_MATCH_DISTANCE_PX = 24
  private const val MAX_LINE_MATCH_DISTANCE_RATIO = 0.08f
}

private const val MAX_PARTIAL_STATUS_CONFIDENCE = 45f
private const val STATUS_CATEGORIES_COUNT = 3

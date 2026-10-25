package com.anan.anannotes.feature.ocr

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Estimates a small in-plane correction for dark text on light paper using row projections. */
internal object OcrDeskewEstimator {
  fun estimate(width: Int, height: Int, grayscale: IntArray): Float {
    require(width > 0 && height > 0 && grayscale.size == width * height)
    if (width < MIN_IMAGE_EDGE || height < MIN_IMAGE_EDGE) return 0f

    val histogram = IntArray(LUMINANCE_LEVELS)
    grayscale.forEach { value -> histogram[value.coerceIn(0, 255)]++ }
    val threshold = otsuThreshold(histogram)
    val foreground = IntArray(grayscale.size)
    var foregroundCount = 0
    grayscale.forEachIndexed { index, value ->
      if (value <= threshold) foreground[foregroundCount++] = index
    }
    if (foregroundCount < MIN_FOREGROUND_PIXELS) return 0f

    val padding = ceil(width * sin(Math.toRadians(MAX_SEARCH_ANGLE))).toInt() + 2
    val baselineScore = projectionScore(width, height, foreground, foregroundCount, padding, 0.0)
    if (baselineScore <= 0.0) return 0f

    var bestAngle = 0.0
    var bestScore = baselineScore
    var angle = -MAX_SEARCH_ANGLE
    while (angle <= MAX_SEARCH_ANGLE + ANGLE_EPSILON) {
      val score = projectionScore(width, height, foreground, foregroundCount, padding, Math.toRadians(angle))
      if (score > bestScore) {
        bestScore = score
        bestAngle = angle
      }
      angle += SEARCH_STEP_DEGREES
    }

    val relativeGain = (bestScore - baselineScore) / baselineScore
    if (kotlin.math.abs(bestAngle) < MIN_CORRECTION_ANGLE || relativeGain < MIN_PROJECTION_GAIN) return 0f
    return bestAngle.toFloat()
  }

  private fun projectionScore(
    width: Int,
    height: Int,
    foreground: IntArray,
    foregroundCount: Int,
    padding: Int,
    appliedRotationRadians: Double,
  ): Double {
    val projections = IntArray(height + padding * 2)
    val sine = sin(appliedRotationRadians)
    val cosine = cos(appliedRotationRadians)
    val centerX = (width - 1) / 2.0
    val centerY = (height - 1) / 2.0 + padding
    var index = 0
    while (index < foregroundCount) {
      val sourceIndex = foreground[index++]
      val sourceX = sourceIndex % width
      val sourceY = sourceIndex / width
      val projectedY = (sine * (sourceX - centerX) + cosine * (sourceY - centerY + padding) + centerY)
        .roundToInt()
      if (projectedY in projections.indices) projections[projectedY]++
    }

    var squaredProjection = 0L
    projections.forEach { count -> squaredProjection += count.toLong() * count }
    return squaredProjection.toDouble() / foregroundCount
  }

  private fun otsuThreshold(histogram: IntArray): Int {
    val total = histogram.sum().toLong()
    var weightedTotal = 0L
    histogram.forEachIndexed { level, count -> weightedTotal += level.toLong() * count }

    var backgroundCount = 0L
    var backgroundWeighted = 0L
    var bestVariance = -1.0
    var bestThreshold = 0
    for (level in histogram.indices) {
      backgroundCount += histogram[level]
      if (backgroundCount == 0L) continue
      val foregroundCount = total - backgroundCount
      if (foregroundCount == 0L) break
      backgroundWeighted += level.toLong() * histogram[level]
      val backgroundMean = backgroundWeighted.toDouble() / backgroundCount
      val foregroundMean = (weightedTotal - backgroundWeighted).toDouble() / foregroundCount
      val meanDifference = backgroundMean - foregroundMean
      val variance = backgroundCount.toDouble() * foregroundCount * meanDifference * meanDifference
      if (variance > bestVariance) {
        bestVariance = variance
        bestThreshold = level
      }
    }
    return bestThreshold
  }

  private const val LUMINANCE_LEVELS = 256
  private const val MIN_IMAGE_EDGE = 64
  private const val MIN_FOREGROUND_PIXELS = 100
  private const val MAX_SEARCH_ANGLE = 7.0
  private const val SEARCH_STEP_DEGREES = 0.5
  private const val MIN_CORRECTION_ANGLE = 0.75
  private const val MIN_PROJECTION_GAIN = 0.015
  private const val ANGLE_EPSILON = 0.0001
}

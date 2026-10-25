package com.anan.anannotes.feature.ocr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class OcrDeskewEstimatorTest {
  @Test
  fun horizontalTextDoesNotRequestCorrection() {
    val image = syntheticText(rotationDegrees = 0.0)

    assertEquals(0f, OcrDeskewEstimator.estimate(image.width, image.height, image.pixels), 0.01f)
  }

  @Test
  fun clockwiseSkewReturnsOppositeCorrectionAngle() {
    val image = syntheticText(rotationDegrees = 4.0)

    assertEquals(-4f, OcrDeskewEstimator.estimate(image.width, image.height, image.pixels), 0.75f)
  }

  @Test
  fun counterClockwiseSkewReturnsClockwiseCorrectionAngle() {
    val image = syntheticText(rotationDegrees = -3.5)

    assertEquals(3.5f, OcrDeskewEstimator.estimate(image.width, image.height, image.pixels), 0.75f)
  }

  @Test
  fun subDegreeRasterNoiseDoesNotTriggerCorrection() {
    val image = syntheticText(rotationDegrees = 0.5)

    assertEquals(0f, OcrDeskewEstimator.estimate(image.width, image.height, image.pixels), 0.01f)
  }

  @Test
  fun blankImageDoesNotInventAnAngle() {
    val width = 320
    val height = 180

    assertEquals(0f, OcrDeskewEstimator.estimate(width, height, IntArray(width * height) { 248 }), 0.01f)
  }

  private fun syntheticText(width: Int = 640, height: Int = 360, rotationDegrees: Double): GrayImage {
    val horizontal = IntArray(width * height) { 255 }
    val lineTops = listOf(70, 130, 190, 250)
    lineTops.forEachIndexed { lineIndex, top ->
      for (glyph in 0 until 55) {
        val left = 55 + glyph * 9
        val glyphTop = top + (glyph * 7 + lineIndex * 3) % 8
        val glyphHeight = 10 + (glyph * 5 + lineIndex) % 9
        for (stroke in 0 until 3) {
          val x = left + stroke * 2
          for (y in glyphTop until glyphTop + glyphHeight) {
            if (x in 0 until width && y in 0 until height) horizontal[y * width + x] = 0
          }
        }
        val crossBarY = glyphTop + glyphHeight / 2
        for (x in left until left + 7) {
          if (x in 0 until width && crossBarY in 0 until height) horizontal[crossBarY * width + x] = 0
        }
      }
    }

    if (rotationDegrees == 0.0) return GrayImage(width, height, horizontal)

    val rotated = IntArray(width * height) { 255 }
    val radians = Math.toRadians(rotationDegrees)
    val cosine = cos(radians)
    val sine = sin(radians)
    val centerX = (width - 1) / 2.0
    val centerY = (height - 1) / 2.0
    horizontal.forEachIndexed { index, value ->
      if (value < 128) {
        val x = index % width
        val y = index / width
        val dx = x - centerX
        val dy = y - centerY
        val rotatedX = (cosine * dx - sine * dy + centerX).roundToInt()
        val rotatedY = (sine * dx + cosine * dy + centerY).roundToInt()
        if (rotatedX in 0 until width && rotatedY in 0 until height) {
          rotated[rotatedY * width + rotatedX] = 0
        }
      }
    }
    return GrayImage(width, height, rotated)
  }

  private data class GrayImage(val width: Int, val height: Int, val pixels: IntArray)
}

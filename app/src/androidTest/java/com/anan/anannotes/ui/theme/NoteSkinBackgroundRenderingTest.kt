package com.anan.anannotes.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import com.anan.anannotes.feature.export.exportTestResources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteSkinBackgroundRenderingTest {
  @Test
  fun everySkinUsesStandaloneArtworkAcrossTallLongImagePages() {
    val width = 360
    val height = 1_920
    val resources = exportTestResources()

    NoteSkinCatalog.all.forEach { skin ->
      val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      try {
        NoteSkinBackgroundPainter.drawPattern(
          Canvas(bitmap), width.toFloat(), height.toFloat(), skin, resources,
        )
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        assertTrue("${skin.name} 应由独立底图绘制", pixels.toSet().size > 32)
        assertEquals("${skin.name} 输出必须覆盖整张长图", 0, pixels.count { it == 0 })
      } finally {
        bitmap.recycle()
      }
    }
  }

  @Test
  fun standaloneArtworkLongImageBandedRenderingHasNoSeams() {
    val width = 360
    val height = 1_920
    val bandHeight = 128
    val resources = exportTestResources()
    NoteSkinCatalog.all.forEach { skin ->
      val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      val banded = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      try {
        NoteSkinBackgroundPainter.drawPattern(Canvas(full), width.toFloat(), height.toFloat(), skin, resources)
        var top = 0
        while (top < height) {
          val count = minOf(bandHeight, height - top)
          val bandTop = maxOf(0, top - 8)
          val bandBottom = minOf(height, top + count + 8)
          val band = Bitmap.createBitmap(width, bandBottom - bandTop, Bitmap.Config.ARGB_8888)
          try {
            val canvas = Canvas(band)
            canvas.translate(0f, -bandTop.toFloat())
            NoteSkinBackgroundPainter.drawPattern(
              canvas = canvas,
              width = width.toFloat(),
              height = height.toFloat(),
              skin = skin,
              resources = resources,
              visibleTop = bandTop.toFloat(),
              visibleBottom = bandBottom.toFloat(),
            )
            val rowPixels = IntArray(width)
            repeat(count) { row ->
              band.getPixels(rowPixels, 0, width, 0, top - bandTop + row, width, 1)
              banded.setPixels(rowPixels, 0, width, 0, top + row, width, 1)
            }
          } finally {
            band.recycle()
          }
          top += count
        }
        val reference = IntArray(width * height)
        val streamed = IntArray(width * height)
        full.getPixels(reference, 0, width, 0, 0, width, height)
        banded.getPixels(streamed, 0, width, 0, 0, width, height)
        val mismatches = reference.indices.filter { reference[it] != streamed[it] }
        val maxChannelDelta = mismatches.maxOfOrNull { index ->
          (0..2).maxOf { channel ->
            val expected = (reference[index] ushr (channel * 8)) and 0xff
            val actual = (streamed[index] ushr (channel * 8)) and 0xff
            kotlin.math.abs(expected - actual)
          }
        } ?: 0
        assertTrue(
          "${skin.id} 分带差异超限; mismatches=${mismatches.size}, maxChannelDelta=$maxChannelDelta, " +
            "first=${mismatches.firstOrNull()?.let { "(${it % width},${it / width}):${reference[it]}→${streamed[it]}" }}",
          maxChannelDelta <= 4,
        )
      } finally {
        full.recycle()
        banded.recycle()
      }
    }
  }
}

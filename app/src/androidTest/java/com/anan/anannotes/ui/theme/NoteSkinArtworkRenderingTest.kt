package com.anan.anannotes.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry

class NoteSkinArtworkRenderingTest {
  @Test
  fun everySkinUsesItsOwnFullResolutionArtworkWithoutScalingAtNativeSize() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val resources = context.resources
    val decodedResources = mutableSetOf<Int>()

    assertEquals("每个目录皮肤都必须有自己的底图", 11, NoteSkinCatalog.all.size)

    NoteSkinCatalog.all.forEach { skin ->
      val resourceName = "skin_${skin.id.replace('-', '_')}"
      val resourceId = resources.getIdentifier(resourceName, "drawable", context.packageName)
      assertNotEquals("${skin.name} 缺少独立底图 $resourceName", 0, resourceId)
      assertTrue("${skin.name} 不能复用另一款皮肤的底图", decodedResources.add(resourceId))

      val source = BitmapFactory.decodeResource(resources, resourceId)
        ?: error("${skin.name} 底图无法解码")
      val rendered = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
      try {
        assertTrue("${skin.name} 宽度不足以清晰呈现", source.width >= 1_024)
        assertTrue("${skin.name} 高度不足以清晰呈现", source.height >= 1_536)
        assertEquals("${skin.name} 底图应保持竖版 2:3 比例", 2f / 3f, source.width.toFloat() / source.height, 0.002f)

        NoteSkinBackgroundPainter.draw(
          canvas = Canvas(rendered),
          width = rendered.width.toFloat(),
          height = rendered.height.toFloat(),
          skin = skin,
          resources = resources,
        )

        // Sample the entire sheet on a fixed grid: no atlas crop, accidental resampling,
        // or wrong-skin resource may silently pass as the expected artwork.
        for (row in 0..24) {
          for (column in 0..16) {
            val x = (source.width - 1) * column / 16
            val y = (source.height - 1) * row / 24
            assertEquals(
              "${skin.id} 原尺寸绘制应保留底图像素 ($x,$y)",
              source.getPixel(x, y),
              rendered.getPixel(x, y),
            )
          }
        }
      } finally {
        source.recycle()
        rendered.recycle()
      }
    }
  }
}

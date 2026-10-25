package com.anan.anannotes.ui.theme

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.anan.anannotes.R
import com.anan.anannotes.core.model.canonicalNoteSkinId
import java.util.WeakHashMap
import kotlin.math.floor

/** Shares the same standalone high-resolution artwork across editor, preview and export rendering. */
object NoteSkinBackgroundPainter {
  private val bitmapCache = WeakHashMap<Resources, LruCache<Int, Bitmap>>()
  private val artworkResources = mapOf(
    "minimal-white" to R.drawable.skin_minimal_white,
    "warm-paper" to R.drawable.skin_warm_paper,
    "mint-green" to R.drawable.skin_mint_green,
    "sakura-petal-paper" to R.drawable.skin_sakura_petal_paper,
    "bamboo-shadow" to R.drawable.skin_bamboo_shadow,
    "misty-tide" to R.drawable.skin_misty_tide,
    "osmanthus-note" to R.drawable.skin_osmanthus_note,
    "moonlit-sky" to R.drawable.skin_moonlit_sky,
    "wisteria-cloud" to R.drawable.skin_wisteria_cloud,
    "camellia-letter" to R.drawable.skin_camellia_letter,
    "southern-ginkgo" to R.drawable.skin_southern_ginkgo,
  )

  fun draw(canvas: Canvas, width: Float, height: Float, skin: NoteSkin, resources: Resources) {
    if (!isDrawable(width, height, 0f, height)) return
    val artwork = artworkFor(skin, resources)
    val source = Rect(0, 0, artwork.width, artwork.height)
    val sourceWidth = source.width().toFloat()
    val sourceHeight = source.height().toFloat()
    val destinationRatio = width / height
    val sourceRatio = sourceWidth / sourceHeight

    // Center-crop instead of stretching the approved paper artwork out of proportion.
    if (destinationRatio > sourceRatio) {
      val visibleHeight = sourceWidth / destinationRatio
      val inset = (sourceHeight - visibleHeight) / 2f
      source.top = (source.top + inset).toInt()
      source.bottom = (source.bottom - inset).toInt()
    } else if (destinationRatio < sourceRatio) {
      val visibleWidth = sourceHeight * destinationRatio
      val inset = (sourceWidth - visibleWidth) / 2f
      source.left = (source.left + inset).toInt()
      source.right = (source.right - inset).toInt()
    }

    val save = canvas.save()
    try {
      canvas.clipRect(0f, 0f, width, height)
      canvas.drawBitmap(
        artwork,
        source,
        RectF(0f, 0f, width, height),
        BITMAP_PAINT,
      )
    } finally {
      canvas.restoreToCount(save)
    }
  }

  /** Long PNGs tile the exact page crop at its original aspect ratio; banded rendering stays seam-free. */
  fun drawPattern(
    canvas: Canvas,
    width: Float,
    height: Float,
    skin: NoteSkin,
    resources: Resources,
    visibleTop: Float = 0f,
    visibleBottom: Float = height,
  ) {
    if (!isDrawable(width, height, visibleTop, visibleBottom)) return
    val artwork = artworkFor(skin, resources)
    val source = Rect(0, 0, artwork.width, artwork.height)
    val tileHeight = width * source.height() / source.width().toFloat()
    if (!tileHeight.isFinite() || tileHeight <= 0f) return

    val save = canvas.save()
    try {
      canvas.clipRect(0f, visibleTop, width, visibleBottom)
      val firstTile = floor(visibleTop / tileHeight).toInt().coerceAtLeast(0)
      val lastTile = floor((visibleBottom - 0.001f) / tileHeight).toInt().coerceAtLeast(firstTile)
      for (tile in firstTile..lastTile) {
        val top = tile * tileHeight
        canvas.drawBitmap(
          artwork,
          source,
          RectF(0f, top, width, top + tileHeight),
          BITMAP_PAINT,
        )
      }
    } finally {
      canvas.restoreToCount(save)
    }
  }

  private fun artworkFor(skin: NoteSkin, resources: Resources): Bitmap {
    val canonicalId = canonicalNoteSkinId(skin.id)
    val resourceId = checkNotNull(artworkResources[canonicalId]) {
      "皮肤 $canonicalId 没有对应的独立高清底图"
    }

    return synchronized(bitmapCache) {
      val cache = bitmapCache.getOrPut(resources) {
        object : LruCache<Int, Bitmap>(BITMAP_CACHE_CAPACITY_BYTES) {
          override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
        }
      }
      cache.get(resourceId) ?: BitmapFactory.decodeResource(
        resources,
        resourceId,
        BitmapFactory.Options().apply { inScaled = false },
      )?.also { cache.put(resourceId, it) } ?: error("无法加载皮肤 $canonicalId 的独立高清底图")
    }
  }

  private fun isDrawable(width: Float, height: Float, visibleTop: Float, visibleBottom: Float): Boolean =
    width.isFinite() && height.isFinite() && visibleTop.isFinite() && visibleBottom.isFinite() &&
      width > 0f && height > 0f && visibleBottom > visibleTop

  private val BITMAP_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
  private const val BITMAP_CACHE_CAPACITY_BYTES = 12 * 1024 * 1024
}

@Composable
fun Modifier.noteSkinBackground(skin: NoteSkin): Modifier {
  val resources = LocalContext.current.resources
  return drawWithContent {
    drawIntoCanvas { canvas ->
      NoteSkinBackgroundPainter.draw(canvas.nativeCanvas, size.width, size.height, skin, resources)
    }
    drawContent()
  }
}

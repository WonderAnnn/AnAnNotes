package com.anan.anannotes.feature.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteModel
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.TodoModel
import com.anan.anannotes.ui.theme.NoteSkinBackgroundPainter
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Evidence generator for the 2026-10-26 skin fidelity review (handover guide P1:
 * skin artwork vs A4 600 DPI export sharpness). Not a pass/fail gate: it renders a fixed
 * note through every real export path for all 11 skins and writes normalized crops,
 * previews and an edge-energy metric so the artifacts can be compared off-device.
 *
 * Everything lands in Android/data/com.anan.anannotes/files/skin-fidelity-20261026/.
 */
@RunWith(AndroidJUnit4::class)
class SkinExportFidelityEvidenceTest {
  private val outputDir: File
    get() = File(
      InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
      "skin-fidelity-20261026",
    ).apply { mkdirs() }

  @Test fun generateSkinExportFidelityMatrix() {
    val resources = exportTestResources()
    val csv = StringBuilder("skin,format,width,height,crop_px,energy512,bytes\n")
    val manifest = StringBuilder()
    manifest.append("skin,format,upscale_vs_artwork\n")
    var written = 0

    for (skin in NoteSkinCatalog.all) {
      val id = skin.id
      val snapshot = fixedNote(id)

      // 1) Source artwork reference: same physical patch through the A4 center-crop geometry.
      val artwork = BitmapFactory.decodeResource(resources, artworkResId(id),
        BitmapFactory.Options().apply { inScaled = false }) ?: error("missing artwork $id")
      val artworkWidth = artwork.width
      try {
        val visibleHeight = artwork.width / (210f / 297f)
        val refCx = (A4_MARGIN_FRACTION / 2f * -1f + 1f) * artwork.width
        val refCy = (artwork.height - visibleHeight) / 2f + V_CENTER * visibleHeight
        val refHalf = (HALF_FRACTION * artwork.width).toInt()
        val ref = crop512(artwork, refCx.toInt(), refCy.toInt(), refHalf)
        savePng(ref, "$id-source-ref").also { written++ }
        csv.append(row(id, "source-ref", artwork.width, artwork.height, refHalf * 2, energy512(ref), 0))
        manifest.append("$id,source-ref,1.00x\n")
        ref.recycle()
      } finally {
        artwork.recycle()
      }

      // 2) Screen preview proxy: the exact painter the editor Compose background calls.
      run {
        val screen = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        NoteSkinBackgroundPainter.draw(canvas, 1080f, 2400f, skin, resources)
        val (cx, cy, half) = cropCenter(1080, 2400, 0.085f)
        val crop = crop512(screen, cx, cy, half)
        savePng(screen, "$id-screen-full"); written++
        savePng(crop, "$id-screen-crop"); written++
        csv.append(row(id, "screen", 1080, 2400, half * 2, energy512(crop), 0))
        manifest.append("$id,screen,%.2fx\n".format(1080f / artworkWidth))
        crop.recycle(); screen.recycle()
      }

      // 3) Social portrait PNG 1080x1350 through the real renderer.
      runBlocking {
        val bytes = renderSocial(snapshot, ExportFormat.PNG_SOCIAL_PORTRAIT)
        val bitmap = decode(bytes, 1)
        val (cx, cy, half) = cropCenter(bitmap.width, bitmap.height, 0.085f)
        val crop = crop512(bitmap, cx, cy, half)
        File(outputDir, "$id-social-portrait.png").writeBytes(bytes)
        savePng(crop, "$id-social-crop"); written += 2
        csv.append(row(id, "social-portrait", bitmap.width, bitmap.height, half * 2, energy512(crop), bytes.size))
        manifest.append("$id,social-portrait,%.2fx\n".format(1080f / artworkWidth))
        crop.recycle(); bitmap.recycle()
      }

      // 4) Long image PNG (1080 wide, tiled at artwork aspect ratio).
      runBlocking {
        val bytes = renderRaster(snapshot, ExportFormat.PNG_LONG_IMAGE)
        val bitmap = decode(bytes, 1)
        val (cx, cy, half) = cropCenter(bitmap.width, bitmap.height, 92f / 1080f)
        val crop = crop512(bitmap, cx, cy, half)
        File(outputDir, "$id-long.png").writeBytes(bytes)
        savePng(crop, "$id-long-crop"); written += 2
        csv.append(row(id, "long-image", bitmap.width, bitmap.height, half * 2, energy512(crop), bytes.size))
        manifest.append("$id,long-image,%.2fx\n".format(1080f / artworkWidth))
        crop.recycle(); bitmap.recycle()
      }

      // 5) A4 PNG 600 DPI through the real renderer; crop via region decoder at full res.
      runBlocking {
        val bytes = renderRaster(snapshot, ExportFormat.PNG_PAGED_600_DPI)
        val (w, h) = 4961 to 7016
        val (cx, cy, half) = cropCenter(w, h, A4_MARGIN_FRACTION)
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size) ?: error("A4 PNG decode failed")
        try {
          val crop = decoder.decodeRegion(
            rect(cx - half, cy - half, cx + half, cy + half),
            BitmapFactory.Options().apply { inSampleSize = 1 },
          ) ?: error("A4 region decode failed")
          val normalized = crop512Direct(crop)
          savePng(normalized, "$id-a4600-crop"); written++
          csv.append(row(id, "a4-png-600dpi", w, h, half * 2, energy512(normalized), bytes.size))
          normalized.recycle()
        } finally {
          decoder.recycle()
        }
        val preview = decode(bytes, 8)
        savePng(preview, "$id-a4600-preview"); written++
        preview.recycle()
        manifest.append("$id,a4-png-600dpi,%.2fx\n".format(4961f / artworkWidth))
      }

      // 6) A4 PDF through the real renderer, then rasterize page 1 at 300 DPI (print check).
      run {
        val pdf = ByteArrayOutputStream()
        AndroidPdfExportRenderer(resources).render(snapshot, { error("无图片") }, pdf)
        val pdfBytes = pdf.toByteArray()
        File(outputDir, "$id-a4.pdf").writeBytes(pdfBytes); written++
        val temp = File(outputDir, "$id-a4.render.pdf")
        temp.writeBytes(pdfBytes)
        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
          PdfRenderer(fd).use { renderer ->
            renderer.openPage(0).use { page ->
              val pw = (595.28f / 72f * 300f).toInt()
              val ph = (841.89f / 72f * 300f).toInt()
              val bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
              bitmap.eraseColor(Color.WHITE)
              page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
              val (cx, cy, half) = cropCenter(pw, ph, A4_MARGIN_FRACTION)
              val crop = crop512(bitmap, cx, cy, half)
              savePng(crop, "$id-pdf300-crop"); written++
              val preview = Bitmap.createScaledBitmap(bitmap, pw / 4, ph / 4, true)
              savePng(preview, "$id-pdf300-preview"); written++
              csv.append(row(id, "pdf-raster-300dpi", pw, ph, half * 2, energy512(crop), pdfBytes.size))
              manifest.append(
                "$id,pdf-raster-300dpi,artwork-ceiling-~124dpi-at-A4,embedded-by-skia\n",
              )
              crop.recycle(); preview.recycle(); bitmap.recycle()
            }
          }
        }
        temp.delete()
      }
    }

    File(outputDir, "fidelity-metrics.csv").writeText(csv.toString())
    File(outputDir, "MANIFEST.txt").writeText(
      buildString {
        append("skin fidelity evidence 2026-10-26\n")
        append("fixed note, right-margin patch ~12.7mm, normalized to 512x512, energy=luma gradient mean\n")
        append("artwork=1024x1536; A4-600dpi upscale=4.85x; A4 physical ceiling of artwork=~124dpi\n")
        append(manifest.toString())
      },
    )
    assertTrue("应写出证据文件", written >= NoteSkinCatalog.all.size * 8)
  }

  private fun fixedNote(skinId: String): ExportSnapshot {
    val heading = BlockModel(
      "heading", "note", 0, BlockType.TEXT, text = "皮肤清晰度基准样张",
      formatting = BlockFormattingState.Supported(BlockFormatting(presentation = TextBlockPresentation.HEADING)),
    )
    val body = (1..8).map { index ->
      BlockModel("body-$index", "note", index, BlockType.TEXT, text = "第 $index 行正文，用于占满页面并保留纸面空白。")
    }
    val quote = BlockModel("quote", "note", 9, BlockType.QUOTE, text = "引用块保留边距条带外的高频纹样。")
    val todoBlock = BlockModel("todo", "note", 10, BlockType.TODO, text = "待办项保持整体布局")
    val divider = BlockModel("divider", "note", 11, BlockType.DIVIDER)
    return ExportSnapshot(
      NoteDocument(
        NoteModel("note", "folder", "皮肤清晰度基准样张", skinId = skinId, createdAtMillis = 1, updatedAtMillis = 2),
        listOf(heading) + body + listOf(quote, todoBlock, divider),
        listOf(TodoModel(todoBlock.id, "note", todoBlock.id, checkNotNull(todoBlock.text), false, 1L)),
      ),
      "测试",
    )
  }

  private suspend fun renderRaster(snapshot: ExportSnapshot, format: ExportFormat): ByteArray {
    var result = byteArrayOf()
    AndroidRasterPngExportRenderer(exportTestResources()).render(
      snapshot, format, { error("无图片") },
    ) { _, content ->
      val output = ByteArrayOutputStream()
      content(output)
      result = output.toByteArray()
      ExportFileReceipt("evidence", "content://evidence")
    }
    return result
  }

  private suspend fun renderSocial(snapshot: ExportSnapshot, format: ExportFormat): ByteArray {
    var result = byteArrayOf()
    AndroidSocialPngExportRenderer(exportTestResources()).render(
      snapshot, format, { error("无图片") },
    ) { _, content ->
      val output = ByteArrayOutputStream()
      content(output)
      result = output.toByteArray()
      ExportFileReceipt("evidence", "content://evidence")
    }
    return result
  }

  /** Center of the right-margin strip; keeps the patch inside pure background at any format. */
  private fun cropCenter(width: Int, height: Int, marginFraction: Float): Triple<Int, Int, Int> {
    val marginPx = (width * marginFraction).toInt()
    val cx = width - marginPx / 2
    val cy = (height * V_CENTER).toInt()
    val half = (HALF_FRACTION * width).toInt().coerceAtMost((marginPx * 0.42f).toInt())
    return Triple(cx, cy, half)
  }

  private fun crop512(bitmap: Bitmap, cx: Int, cy: Int, half: Int): Bitmap {
    val size = half * 2
    val patch = Bitmap.createBitmap(bitmap, (cx - half).coerceIn(0, bitmap.width - size),
      (cy - half).coerceIn(0, bitmap.height - size), size, size)
    val scaled = Bitmap.createScaledBitmap(patch, 512, 512, true)
    if (patch !== scaled) patch.recycle()
    return scaled
  }

  private fun crop512Direct(patch: Bitmap): Bitmap {
    if (patch.width == 512 && patch.height == 512) return patch
    val scaled = Bitmap.createScaledBitmap(patch, 512, 512, true)
    patch.recycle()
    return scaled
  }

  private fun energy512(bitmap: Bitmap): Double {
    val w = bitmap.width; val h = bitmap.height
    val row = IntArray(w); val below = IntArray(w)
    var energy = 0.0; var samples = 0
    bitmap.getPixels(row, 0, w, 0, 0, w, 1)
    for (y in 1 until h) {
      bitmap.getPixels(below, 0, w, 0, y, w, 1)
      for (x in 0 until w) {
        val luma = luma(row[x]); val lumaBelow = luma(below[x])
        if (x > 0) {
          val dx = luma - luma(row[x - 1])
          energy += dx * dx; samples++
        }
        val dy = luma - lumaBelow
        energy += dy * dy; samples++
      }
      System.arraycopy(below, 0, row, 0, w)
    }
    return energy / samples.coerceAtLeast(1)
  }

  private fun luma(pixel: Int): Int =
    (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

  private fun savePng(bitmap: Bitmap, name: String) {
    val output = ByteArrayOutputStream()
    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
    File(outputDir, "$name.png").writeBytes(output.toByteArray())
  }

  private fun decode(bytes: ByteArray, sample: Int): Bitmap =
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
      BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("PNG 不能解码")

  private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
    android.graphics.Rect(left.coerceAtLeast(0), top.coerceAtLeast(0), right, bottom)

  private fun row(skin: String, format: String, w: Int, h: Int, cropPx: Int, energy: Double, bytes: Int): String =
    "$skin,$format,$w,$h,$cropPx,${"%.2f".format(energy)},$bytes\n"

  private fun artworkResId(id: String): Int = when (id) {
    "minimal-white" -> com.anan.anannotes.R.drawable.skin_minimal_white
    "warm-paper" -> com.anan.anannotes.R.drawable.skin_warm_paper
    "mint-green" -> com.anan.anannotes.R.drawable.skin_mint_green
    "sakura-petal-paper" -> com.anan.anannotes.R.drawable.skin_sakura_petal_paper
    "bamboo-shadow" -> com.anan.anannotes.R.drawable.skin_bamboo_shadow
    "misty-tide" -> com.anan.anannotes.R.drawable.skin_misty_tide
    "osmanthus-note" -> com.anan.anannotes.R.drawable.skin_osmanthus_note
    "moonlit-sky" -> com.anan.anannotes.R.drawable.skin_moonlit_sky
    "wisteria-cloud" -> com.anan.anannotes.R.drawable.skin_wisteria_cloud
    "camellia-letter" -> com.anan.anannotes.R.drawable.skin_camellia_letter
    "southern-ginkgo" -> com.anan.anannotes.R.drawable.skin_southern_ginkgo
    else -> error("未知皮肤 $id")
  }

  private companion object {
    const val A4_MARGIN_FRACTION = 42f / 595.28f
    const val V_CENTER = 0.30f
    const val HALF_FRACTION = 0.03023f
  }
}

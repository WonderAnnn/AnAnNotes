package com.anan.anannotes.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regenerates the calibration corpus assets consumed by [OcrSyntheticCorpus].
 *
 * Not part of the regular matrix: it renders the corpus on the CURRENT device and
 * writes PNG/JPEG plus a SHA-256 manifest to Android/data/com.anan.anannotes/files/
 * ocr-corpus-assets/. Copy those files into app/src/androidTest/assets/ocr/corpus/.
 * The calibration reference device is AnAn_API35_Pixel6; rerunning on a different
 * device changes the pixels and therefore the CER baselines.
 *
 * Run with:
 * adb shell am instrument -w -e corpusExport true -e class
 *   com.anan.anannotes.ocr.OcrCorpusAssetExportTest com.anan.anannotes.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OcrCorpusAssetExportTest {
  @Test fun exportCalibrationAssets() {
    assumeTrue(
      "Pass -e corpusExport true to regenerate the calibration assets on this device",
      InstrumentationRegistry.getArguments().getString("corpusExport") == "true",
    )
    val outputDir = File(
      InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
      "ocr-corpus-assets",
    ).apply { mkdirs() }

    val manifest = StringBuilder("file,sha256,bytes\n")
    OcrSyntheticCorpus.sampleSpecs().forEach { spec ->
      val bitmap = OcrSyntheticCorpus.CalibrationRenderer.render(spec)
      val png = ByteArrayOutputStream().also { output ->
        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
      }.toByteArray()
      bitmap.recycle()
      File(outputDir, spec.assetFileName).writeBytes(png)
      manifest.append("${spec.assetFileName},${sha256(png)},${png.size}\n")
    }
    val jpeg = OcrSyntheticCorpus.CalibrationRenderer.tinyCompressedJpeg()
    File(outputDir, "tiny-compressed.jpg").writeBytes(jpeg)
    manifest.append("tiny-compressed.jpg,${sha256(jpeg)},${jpeg.size}\n")

    File(outputDir, "MANIFEST.csv").writeText(manifest.toString())
    assertTrue("至少导出 4 张语料 PNG", outputDir.listFiles { f -> f.extension == "png" }!!.size >= 4)
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

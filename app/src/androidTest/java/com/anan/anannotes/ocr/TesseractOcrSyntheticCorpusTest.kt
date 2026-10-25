package com.anan.anannotes.ocr

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.feature.ocr.OcrProgressStage
import com.anan.anannotes.feature.ocr.TesseractModelInstaller
import com.anan.anannotes.feature.ocr.TesseractOcrEngine
import com.googlecode.tesseract.android.TessBaseAPI
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class TesseractOcrSyntheticCorpusTest {
  @Test
  fun correctsModeratePageSkewAndKeepsMixedContentReadable() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val samples = OcrSyntheticCorpus.create()
    val sample = samples.first { it.id == "small-low-contrast" }
    val rotated = OcrSyntheticCorpus.rotate(sample.bitmap, 6f)
    val bytes = rotated.encodeJpeg(quality = 28)
    val stages = mutableListOf<OcrProgressStage>()
    val dataDirectory = kotlinx.coroutines.runBlocking { TesseractModelInstaller(context).install() }
    val baselineApi = TessBaseAPI()
    var decodedBaseline: Bitmap? = null
    try {
      check(baselineApi.init(dataDirectory.absolutePath, "chi_sim+eng"))
      baselineApi.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
      decodedBaseline = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
      baselineApi.setImage(decodedBaseline)
      baselineApi.meanConfidence()
      val baselineCer = characterErrorRate(sample.expectedText, baselineApi.getUTF8Text().orEmpty())

      val recognized = kotlinx.coroutines.runBlocking {
        TesseractOcrEngine(context).recognize(bytes) { stage -> stages += stage }
      }
      val cer = characterErrorRate(sample.expectedText, recognized)
      val lineCount = recognized.lineSequence().count(String::isNotBlank)

      assertTrue("中度倾斜输入未进入纠偏流程", stages.any { it.name == "DESKEWING_IMAGE" })
      assertTrue("纠偏后 CER=$cer", cer <= sample.maxCharacterErrorRate)
      assertTrue("纠偏候选不应劣于未纠偏 OCR：before=$baselineCer after=$cer", cer <= baselineCer)
      assertTrue("纠偏后文本行数=$lineCount", lineCount in 3..6)
      Log.i(TAG, "sample=skewed-mixed-body baselineCer=$baselineCer correctedCer=$cer lines=$lineCount")
    } finally {
      decodedBaseline?.recycle()
      baselineApi.recycle()
      bytes.fill(0)
      rotated.recycle()
      samples.forEach { it.bitmap.recycle() }
    }
  }

  @Test
  fun defaultEngineMeetsLabeledMixedLayoutQualityBudgets() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val samples = OcrSyntheticCorpus.create()
    try {
      samples.forEach { sample ->
        val bytes = sample.bitmap.encodePng()
        val stages = mutableListOf<OcrProgressStage>()
        val started = SystemClock.elapsedRealtime()
        val recognized = try {
          kotlinx.coroutines.runBlocking {
            TesseractOcrEngine(context).recognize(bytes) { stage -> stages += stage }
          }
        } finally {
          bytes.fill(0)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val cer = characterErrorRate(sample.expectedText, recognized)
        val lineCount = recognized.lineSequence().count(String::isNotBlank)
        val forbiddenHits = sample.forbiddenTokens.filter(recognized::contains)

        Log.i(TAG, "sample=${sample.id} cer=$cer lines=$lineCount elapsedMs=$elapsedMs")
        assertTrue("${sample.id} CER=$cer exceeds ${sample.maxCharacterErrorRate}", cer <= sample.maxCharacterErrorRate)
        assertTrue("${sample.id} line count=$lineCount expected=${sample.expectedLineCount}",
          lineCount in sample.minimumLineCount..(sample.expectedLineCount + 2))
        assertTrue("${sample.id} contains synthetic status-bar noise: $forbiddenHits", forbiddenHits.isEmpty())
        assertTrue(
          "${sample.id} clean orientation should not trigger deskew",
          stages.none { it.name == "DESKEWING_IMAGE" },
        )
      }
    } finally {
      samples.forEach { it.bitmap.recycle() }
    }
  }

  private fun Bitmap.encodePng(): ByteArray = ByteArrayOutputStream().use { output ->
    check(compress(Bitmap.CompressFormat.PNG, 100, output))
    output.toByteArray()
  }

  private fun Bitmap.encodeJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
    check(compress(Bitmap.CompressFormat.JPEG, quality, output))
    output.toByteArray()
  }

  private companion object {
    const val TAG = "AnAnOcrCorpus"
  }
}

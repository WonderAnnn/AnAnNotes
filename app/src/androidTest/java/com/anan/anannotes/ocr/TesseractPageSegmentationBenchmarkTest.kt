package com.anan.anannotes.ocr

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.feature.ocr.OcrLineEvidence
import com.anan.anannotes.feature.ocr.OcrResultFilter
import com.anan.anannotes.feature.ocr.TesseractModelInstaller
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.assertTrue
import org.junit.Test

class TesseractPageSegmentationBenchmarkTest {
  @Test
  fun comparesPageSegmentationModesAcrossIdenticalLabeledLayouts() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val samples = OcrSyntheticCorpus.create()
    val dataDirectory = kotlinx.coroutines.runBlocking { TesseractModelInstaller(context).install() }
    val api = TessBaseAPI()
    try {
      check(api.init(dataDirectory.absolutePath, "chi_sim+eng"))
      val results = SEGMENTATION_MODES.map { (name, mode) ->
        var characterErrors = 0.0
        var linesPreserved = 0
        var elapsedMillis = 0L
        var forbiddenStatusHits = 0
        val perSample = mutableListOf<String>()
        samples.forEach { sample ->
          api.setPageSegMode(mode)
          api.setImage(sample.bitmap)
          val started = SystemClock.elapsedRealtime()
          api.meanConfidence()
          val read = readFilteredText(api, sample.bitmap.height)
          val recognized = read.text
          elapsedMillis += SystemClock.elapsedRealtime() - started
          val sampleCer = characterErrorRate(sample.expectedText, recognized)
          val lineCount = recognized.lineSequence().count(String::isNotBlank)
          characterErrors += sampleCer
          if (lineCount >= sample.minimumLineCount) linesPreserved++
          forbiddenStatusHits += sample.forbiddenTokens.count(recognized::contains)
          perSample += "${sample.id}:cer=$sampleCer,lines=$lineCount,rawLines=${read.rawLineCount}," +
            "keptLines=${read.retainedLineCount},missingBounds=${read.missingBoundsCount}"
        }
        SegmentationResult(
          name,
          characterErrors / samples.size,
          linesPreserved,
          elapsedMillis,
          forbiddenStatusHits,
          perSample,
        )
      }

      assertTrue("每种分割模式都应至少识别一部分语料", results.all { it.meanCharacterErrorRate < 1.0 })
      results.forEach { result -> Log.i(TAG, result.safeSummary()) }
    } finally {
      api.recycle()
      samples.forEach { it.bitmap.recycle() }
    }
  }

  private data class SegmentationResult(
    val mode: String,
    val meanCharacterErrorRate: Double,
    val layoutsWithLinesPreserved: Int,
    val elapsedMillis: Long,
    val forbiddenStatusHits: Int,
    val perSample: List<String>,
  ) {
    fun safeSummary(): String =
      "mode=$mode meanCer=$meanCharacterErrorRate linesPreserved=$layoutsWithLinesPreserved " +
        "elapsedMs=$elapsedMillis statusNoiseHits=$forbiddenStatusHits samples=${perSample.joinToString(";")}"
  }

  private data class FilteredRead(
    val text: String,
    val rawLineCount: Int,
    val retainedLineCount: Int,
    val missingBoundsCount: Int,
  )

  private fun readFilteredText(api: TessBaseAPI, imageHeight: Int): FilteredRead {
    val iterator = api.resultIterator ?: return FilteredRead(api.getUTF8Text().orEmpty()
      .lineSequence()
      .filter(String::isNotBlank)
      .joinToString("\n"), 0, 0, 0)
    val lines = mutableListOf<OcrLineEvidence>()
    var missingBoundsCount = 0
    try {
      val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
      iterator.begin()
      do {
        val text = iterator.getUTF8Text(level).orEmpty()
        val bounds = iterator.getBoundingRect(level)
        if (text.isNotBlank()) {
          if (bounds == null) missingBoundsCount++
          lines += OcrLineEvidence(text, iterator.confidence(level), bounds?.top ?: 0, bounds?.bottom ?: imageHeight)
        }
      } while (iterator.next(level))
    } finally {
      iterator.delete()
    }
    return FilteredRead(
      text = OcrResultFilter.clean(lines, imageHeight),
      rawLineCount = lines.size,
      retainedLineCount = OcrResultFilter.retainedLines(lines, imageHeight).size,
      missingBoundsCount = missingBoundsCount,
    )
  }

  private companion object {
    const val TAG = "AnAnOcrPsmBench"
    val SEGMENTATION_MODES = listOf(
      "AUTO" to TessBaseAPI.PageSegMode.PSM_AUTO,
      "SINGLE_BLOCK" to TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
      "SINGLE_COLUMN" to TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN,
      "SPARSE_TEXT" to TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
    )
  }
}

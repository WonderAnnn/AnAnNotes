package com.anan.anannotes.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TesseractModelCandidateBenchmarkTest {
  @Test
  fun compareFastAndBestOfflineChineseEnglishModelsOnTheSameSyntheticCorpus() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = instrumentation.targetContext
    val testContext = instrumentation.context
    val bitmap = createCorpusBitmap()
    try {
      val fast = recognizeProfile(
        target,
        target.assets,
        "ocr/tessdata",
        "fast",
        bitmap,
      )
      val best = recognizeProfile(
        target,
        testContext.assets,
        "ocr/tessdata_best",
        "best",
        bitmap,
      )

      assertTrue("fast 模型必须至少识别出部分语料", fast.characterCount > 20)
      assertTrue("best 模型必须至少识别出部分语料", best.characterCount > 20)
      assertTrue("fast 模型应保留多个文本行", fast.recognizedLineCount >= 3)
      assertTrue("best 模型应保留多个文本行", best.recognizedLineCount >= 3)
      Log.i(BENCHMARK_TAG, fast.asSafeLogLine())
      Log.i(BENCHMARK_TAG, best.asSafeLogLine())
    } finally {
      bitmap.recycle()
    }
  }

  private fun recognizeProfile(
    target: Context,
    assetContext: android.content.res.AssetManager,
    assetDirectory: String,
    profile: String,
    bitmap: Bitmap,
  ): ProfileResult {
    val root = File(target.cacheDir, "ocr-candidate-$profile").apply { deleteRecursively(); mkdirs() }
    val tessdata = File(root, "tessdata").apply { mkdirs() }
    listOf("chi_sim", "eng").forEach { language ->
      assetContext.open("$assetDirectory/$language.traineddata").use { input ->
        File(tessdata, "$language.traineddata").outputStream().use { output -> input.copyTo(output) }
      }
    }
    val modelBytes = tessdata.listFiles().orEmpty().sumOf(File::length)
    val api = TessBaseAPI()
    try {
      val initStarted = SystemClock.elapsedRealtimeNanos()
      check(api.init(root.absolutePath, "chi_sim+eng")) { "Tesseract model init failed: $profile" }
      val coldInitMillis = (SystemClock.elapsedRealtimeNanos() - initStarted) / 1_000_000L
      api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)

      val runs = ArrayList<Pair<Long, String>>(4)
      repeat(4) {
        api.setImage(bitmap)
        val started = SystemClock.elapsedRealtimeNanos()
        val text = api.getUTF8Text().orEmpty()
        runs += ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L) to text
      }
      val sortedTimes = runs.drop(1).map { it.first }.sorted()
      val medianWarmMillis = sortedTimes[sortedTimes.size / 2]
      val output = runs.last().second
      val memoryInfo = Debug.MemoryInfo()
      Debug.getMemoryInfo(memoryInfo)
      val result = ProfileResult(
        profile = profile,
        coldInitMillis = coldInitMillis,
        medianWarmMillis = medianWarmMillis,
        modelBytes = modelBytes,
        pssKilobytes = memoryInfo.totalPss,
        characterCount = output.count { !it.isWhitespace() },
        recognizedLineCount = output.lineSequence().count(String::isNotBlank),
        characterErrorRate = characterErrorRate(GROUND_TRUTH, output),
      )
      return result
    } finally {
      api.recycle()
      root.deleteRecursively()
    }
  }

  private fun createCorpusBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(1700, 760, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = 68f
      typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    listOf(
      "安安简记 离线识别测试",
      "保留原始换行，文字可编辑。",
      "Meeting Notes 2026 OCR 600 DPI",
      "Order ID: A7B9-2046",
    ).forEachIndexed { index, line -> canvas.drawText(line, 70f, 130f + index * 160f, paint) }
    return bitmap
  }

  private fun characterErrorRate(expected: String, actual: String): Double {
    val left = expected.filterNot(Char::isWhitespace)
    val right = actual.filterNot(Char::isWhitespace)
    if (left.isEmpty()) return 1.0
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
    return previous[right.length].toDouble() / left.length
  }

  private data class ProfileResult(
    val profile: String,
    val coldInitMillis: Long,
    val medianWarmMillis: Long,
    val modelBytes: Long,
    val pssKilobytes: Int,
    val characterCount: Int,
    val recognizedLineCount: Int,
    val characterErrorRate: Double,
  ) {
    fun asSafeLogLine(): String =
      "profile=$profile coldInitMs=$coldInitMillis medianWarmMs=$medianWarmMillis modelBytes=$modelBytes " +
        "pssKB=$pssKilobytes recognizedChars=$characterCount lineCount=$recognizedLineCount cer=$characterErrorRate"
  }

  private companion object {
    const val BENCHMARK_TAG = "AnAnOcrModelBench"
    const val GROUND_TRUTH = "安安简记离线识别测试 保留原始换行文字可编辑 MeetingNotes2026OCR600DPI OrderID:A7B9-2046"
  }
}

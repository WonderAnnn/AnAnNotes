package com.anan.anannotes.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.anan.anannotes.feature.ocr.OcrProgressStage
import com.anan.anannotes.feature.ocr.TesseractModelInstaller
import com.anan.anannotes.feature.ocr.TesseractOcrEngine
import com.anan.anannotes.feature.privatemodule.PrivateOcrRunner
import com.anan.anannotes.feature.privatemodule.PrivateOcrRecognizer
import com.anan.anannotes.feature.privatemodule.PrivateStore
import com.anan.anannotes.security.KeyDeriver
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.PrivateCrypto
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TesseractOcrEngineIntegrationTest {
  @Test
  fun bundledOfflineEngineRecognizesSimplifiedChineseEnglishDigitsAndLineBreaks() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val image = createCorpusBitmap()
    val file = File(context.cacheDir, "ocr-integration-corpus.png")
    val stages = mutableListOf<OcrProgressStage>()
    val started = SystemClock.elapsedRealtime()
    try {
      file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
      val recognized = kotlinx.coroutines.runBlocking {
        TesseractOcrEngine(context).recognize(file) { stage -> stages += stage }
      }
      val memoryBytes = file.readBytes()
      val memoryStages = mutableListOf<OcrProgressStage>()
      val memoryRecognized = try {
        kotlinx.coroutines.runBlocking {
          TesseractOcrEngine(context).recognize(memoryBytes) { stage -> memoryStages += stage }
        }
      } finally {
        memoryBytes.fill(0)
      }
      val elapsed = SystemClock.elapsedRealtime() - started
      val lineCount = recognized.lineSequence().count(String::isNotBlank)
      val characterCount = recognized.count { !it.isWhitespace() }
      val cer = characterErrorRate(GROUND_TRUTH, recognized)
      val installedDirectory = kotlinx.coroutines.runBlocking { TesseractModelInstaller(context).install() }

      assertEquals(listOf(
        OcrProgressStage.PREPARING_MODELS,
        OcrProgressStage.PREPARING_IMAGE,
        OcrProgressStage.RECOGNIZING,
        OcrProgressStage.RESULT_READY,
      ), stages)
      assertTrue("识别行数不足: $lineCount", lineCount >= 4)
      assertTrue("可识别字符数不足: $characterCount", characterCount >= 55)
      assertTrue("合成语料 CER 过高: $cer", cer <= 0.08)
      assertTrue(
        "识别文本不应包含分页控制字符: ${recognized.toCharArray().map { it.code }}",
        recognized.none { it.code < 0x20 && it !in "\n\r\t" },
      )
      assertTrue("内存解码 CER 过高", characterErrorRate(GROUND_TRUTH, memoryRecognized) <= 0.08)
      assertEquals(stages, memoryStages)
      assertTrue(File(installedDirectory, "tessdata/chi_sim.traineddata").isFile)
      assertTrue(File(installedDirectory, "tessdata/eng.traineddata").isFile)
      Log.i(TAG, "elapsedMs=$elapsed lines=$lineCount chars=$characterCount cer=$cer models=chi_sim+eng")
    } finally {
      image.recycle()
      file.delete()
    }
  }

  @Test
  fun lowContrastCompressedMixedScriptStaysReadableAndKeepsLineStructure() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val image = createCorpusBitmap(
      textColor = Color.rgb(202, 199, 193),
      backgroundColor = Color.rgb(248, 247, 242),
      textSize = 68f,
    )
    val encoded = ByteArrayOutputStream().use { output ->
      check(image.compress(Bitmap.CompressFormat.JPEG, 48, output))
      output.toByteArray()
    }
    val stages = mutableListOf<OcrProgressStage>()
    try {
      val recognized = kotlinx.coroutines.runBlocking {
        TesseractOcrEngine(context).recognize(encoded) { stage -> stages += stage }
      }
      val cer = characterErrorRate(GROUND_TRUTH, recognized)
      val lines = recognized.lineSequence().count(String::isNotBlank)

      assertTrue("低对比混排识别 CER 过高: $cer; result=$recognized", cer <= 0.12)
      assertTrue("低对比图应保留四行主体结构，实际 $lines 行: $recognized", lines in 4..7)
      assertTrue("低对比图应尝试增强候选识别", OcrProgressStage.ENHANCING_IMAGE in stages)
    } finally {
      encoded.fill(0)
      image.recycle()
    }
  }

  @Test
  fun smallLowContrastJpegKeepsMixedScriptWithinQualityBudget() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val image = createCorpusBitmap(
      width = 720,
      height = 322,
      textColor = Color.rgb(186, 184, 180),
      backgroundColor = Color.rgb(246, 245, 241),
      textSize = 29f,
    )
    val encoded = ByteArrayOutputStream().use { output ->
      check(image.compress(Bitmap.CompressFormat.JPEG, 28, output))
      output.toByteArray()
    }
    try {
      val recognized = kotlinx.coroutines.runBlocking {
        TesseractOcrEngine(context).recognize(encoded)
      }
      val cer = characterErrorRate(GROUND_TRUTH, recognized)
      val lines = recognized.lineSequence().count(String::isNotBlank)

      assertTrue("低清灰字混排识别 CER 超过 12%: $cer; result=$recognized", cer <= 0.12)
      assertTrue("低清图片应保留至少四行文字，实际 $lines 行: $recognized", lines in 4..7)
    } finally {
      encoded.fill(0)
      image.recycle()
    }
  }

  @Test
  fun verySmallCompressedTextDoesNotLoseMostOfItsContent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    // Calibration asset (rendered + JPEG-compressed once on the reference device):
    // runtime Canvas rasterization and JPEG encoders differ per Android release.
    val encoded = InstrumentationRegistry.getInstrumentation().context.assets
      .open("ocr/corpus/tiny-compressed.jpg").use { it.readBytes() }
    try {
      val recognized = kotlinx.coroutines.runBlocking {
        TesseractOcrEngine(context).recognize(encoded)
      }
      val cer = characterErrorRate(GROUND_TRUTH, recognized)
      val lines = recognized.lineSequence().count(String::isNotBlank)

      assertTrue("超小低清文字被严重误识别或漏识别，CER=$cer; result=$recognized", cer <= 0.12)
      assertTrue("超小低清输入未保留多行结构，实际 $lines 行: $recognized", lines in 4..7)
    } finally {
      encoded.fill(0)
    }
  }

  @Test
  fun screenshotStatusBarNoiseIsExcludedWithoutDroppingShortMixedContent() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val image = Bitmap.createBitmap(1700, 760, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(image).apply { drawColor(Color.WHITE) }
    val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.rgb(80, 80, 80)
      textSize = 29f
      typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      textSize = 68f
      typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    canvas.drawText("5:59    5G    99%", 30f, 40f, statusPaint)
    canvas.drawText("安安简记 OCR 混排回归测试", 70f, 170f, bodyPaint)
    canvas.drawText("有效短码 A7", 70f, 315f, bodyPaint)
    canvas.drawText("Meeting Notes 2026", 70f, 460f, bodyPaint)
    canvas.drawText("Order ID: A7B9-2046", 70f, 605f, bodyPaint)
    val encoded = ByteArrayOutputStream().use { output ->
      check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
      output.toByteArray()
    }
    try {
      val recognized = kotlinx.coroutines.runBlocking { TesseractOcrEngine(context).recognize(encoded) }
      val target = "安安简记OCR混排回归测试 有效短码A7 MeetingNotes2026 OrderID:A7B9-2046"
      val cer = characterErrorRate(target, recognized)

      assertTrue("状态栏时间/网络/电量不应混入正文: $recognized", listOf("5:59", "5G", "99%").none(recognized::contains))
      assertTrue("短编号或混排正文被过滤，CER=$cer; result=$recognized", cer <= 0.12)
    } finally {
      encoded.fill(0)
      image.recycle()
    }
  }

  @Test
  fun exifRotatedJpegIsUprightedBeforeOfflineRecognition() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val upright = createCorpusBitmap()
    val sideways = Bitmap.createBitmap(
      upright,
      0,
      0,
      upright.width,
      upright.height,
      Matrix().apply { postRotate(90f) },
      true,
    )
    upright.recycle()
    val jpeg = ByteArrayOutputStream().use { output ->
      check(sideways.compress(Bitmap.CompressFormat.JPEG, 92, output))
      output.toByteArray()
    }
    sideways.recycle()
    val orientedJpeg = addJpegExifOrientation(jpeg, orientation = 8)
    jpeg.fill(0)
    var decoded: Bitmap? = null
    try {
      decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(orientedJpeg)))
      assertEquals("ImageDecoder 应按 EXIF 将页面恢复为横向", 1700, decoded.width)
      assertEquals("ImageDecoder 应按 EXIF 将页面恢复为横向", 760, decoded.height)
      decoded.recycle()
      decoded = null

      val recognized = kotlinx.coroutines.runBlocking {
        TesseractOcrEngine(context).recognize(orientedJpeg)
      }
      val cer = characterErrorRate(GROUND_TRUTH, recognized)
      val lines = recognized.lineSequence().count(String::isNotBlank)
      assertTrue("EXIF 旋转图片识别 CER 过高: $cer; result=$recognized", cer <= 0.08)
      assertTrue("EXIF 旋转图片应保留多行文本，实际 $lines 行: $recognized", lines in 4..7)
    } finally {
      decoded?.recycle()
      orientedJpeg.fill(0)
    }
  }

  @Test
  fun privateOcrDecryptsOnlyInMemoryAndWipesThePlaintextBuffer() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val privateRoot = File(context.filesDir, "private-ocr-integration-${System.nanoTime()}")
    val store = PrivateStore(privateRoot, testCrypto())
    val bitmap = createCorpusBitmap(
      textColor = Color.rgb(202, 199, 193),
      backgroundColor = Color.rgb(248, 247, 242),
    )
    var encoded: ByteArray? = null
    var decrypted: ByteArray? = null
    var access: PrivateAccess? = null
    val stages = mutableListOf<OcrProgressStage>()
    try {
      encoded = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        output.toByteArray()
      }
      access = kotlinx.coroutines.runBlocking { store.create("integration-password".toCharArray(), null) }
      val reference = kotlinx.coroutines.runBlocking { store.writePrivateAsset(access!!, encoded!!) }
      encoded!!.fill(0)
      val runner = PrivateOcrRunner(store, PrivateOcrRecognizer { bytes, progress ->
        decrypted = bytes
        TesseractOcrEngine(context).recognize(bytes) { stage ->
          stages += stage
          progress.onStage(stage)
        }
      })
      val recognized = kotlinx.coroutines.runBlocking { runner.recognize(access!!, reference) }

      assertTrue("私密 OCR 合成语料 CER 过高", characterErrorRate(GROUND_TRUTH, recognized) <= 0.08)
      assertTrue("私密低对比图应尝试增强候选识别", OcrProgressStage.ENHANCING_IMAGE in stages)
      assertTrue("解密图像缓冲区未擦除", decrypted!!.all { it == 0.toByte() })
      assertTrue("私密 OCR 明文落盘", privateRoot.walkTopDown().filter { it.isFile }.none { file ->
        file.readBytes().toString(Charsets.ISO_8859_1).contains(GROUND_TRUTH)
      })
      store.lock(access!!)
      Log.i(TAG, "privateMemoryOcr=passed recognizedChars=${recognized.count { !it.isWhitespace() }}")
    } finally {
      encoded?.fill(0)
      decrypted?.fill(0)
      access?.let(store::lock)
      bitmap.recycle()
      privateRoot.deleteRecursively()
    }
  }

  private fun createCorpusBitmap(
    width: Int = 1700,
    height: Int = 760,
    textColor: Int = Color.BLACK,
    backgroundColor: Int = Color.WHITE,
    textSize: Float = 68f,
  ): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(backgroundColor)
    val scale = width / 1700f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = textColor
      this.textSize = textSize
      typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }
    listOf(
      "安安简记 离线识别测试",
      "保留原始换行，文字可编辑。",
      "Meeting Notes 2026 OCR 600 DPI",
      "Order ID: A7B9-2046",
    ).forEachIndexed { index, line -> canvas.drawText(line, 70f * scale, (130f + index * 160f) * scale, paint) }
    return bitmap
  }

  private fun addJpegExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
    require(jpeg.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
    require(orientation in 1..8)
    val exifPayload = byteArrayOf(
      0x45, 0x78, 0x69, 0x66, 0x00, 0x00, // Exif\0\0
      0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00, // Little-endian TIFF header
      0x01, 0x00, // One IFD entry
      0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, // Orientation SHORT, count 1
      orientation.toByte(), 0x00, 0x00, 0x00, // Inline orientation value
      0x00, 0x00, 0x00, 0x00, // No next IFD
    )
    val segmentLength = exifPayload.size + 2
    val app1 = byteArrayOf(
      0xFF.toByte(), 0xE1.toByte(),
      (segmentLength shr 8).toByte(), segmentLength.toByte(),
    ) + exifPayload
    return ByteArrayOutputStream(jpeg.size + app1.size).use { output ->
      output.write(jpeg, 0, 2)
      output.write(app1)
      output.write(jpeg, 2, jpeg.size - 2)
      output.toByteArray()
    }
  }

  private fun characterErrorRate(expected: String, actual: String): Double {
    val left = expected.filterNot(Char::isWhitespace)
    val right = actual.filterNot(Char::isWhitespace)
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

  private fun testCrypto(): PrivateCrypto {
    val kdf = KeyDeriver { credential, salt, _ ->
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(salt.copyBytes(), "HmacSHA256"))
      val bytes = String(credential).toByteArray(Charsets.UTF_8)
      try {
        mac.doFinal(bytes)
      } finally {
        bytes.fill(0)
      }
    }
    return PrivateCrypto(SecureRandom(), kdf)
  }

  companion object {
    private const val TAG = "AnAnOcrRuntime"
    private const val GROUND_TRUTH = "安安简记离线识别测试 保留原始换行文字可编辑 MeetingNotes2026OCR600DPI OrderID:A7B9-2046"
  }
}

package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.feature.ocr.OcrProgressListener
import com.anan.anannotes.security.KeyDeriver
import com.anan.anannotes.security.PrivateCrypto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PrivateOcrRunnerTest {
  @Test
  fun decryptedSourceIsMemoryOnlyAndWipedAfterRecognition() = runBlocking {
    val root = Files.createTempDirectory("anan-private-ocr-runner-").toFile()
    try {
      val store = PrivateStore(root, testCrypto())
      val access = store.create("runner-password".toCharArray(), null)
      val phrase = "private-image-plaintext-that-must-not-be-written"
      val input = phrase.toByteArray()
      val reference = store.writePrivateAsset(access, input)
      input.fill(0)
      var decryptedBuffer: ByteArray? = null
      val runner = PrivateOcrRunner(store) { bytes, _ ->
        decryptedBuffer = bytes
        assertEquals(phrase, bytes.toString(Charsets.UTF_8))
        "recognized privately"
      }

      assertEquals("recognized privately", runner.recognize(access, reference))
      assertTrue(decryptedBuffer!!.all { it == 0.toByte() })
      assertTrue(root.walkTopDown().filter { it.isFile }.none { file ->
        file.readBytes().toString(Charsets.ISO_8859_1).contains(phrase)
      })
    } finally {
      root.deleteRecursively()
    }
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
}

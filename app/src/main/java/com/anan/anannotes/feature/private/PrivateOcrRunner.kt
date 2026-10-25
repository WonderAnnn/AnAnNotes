package com.anan.anannotes.feature.privatemodule

import com.anan.anannotes.feature.ocr.OcrProgressListener
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess

fun interface PrivateOcrRecognizer {
  suspend fun recognize(imageBytes: ByteArray, progress: OcrProgressListener): String
}

/** Decrypts an image only in memory, delegates recognition, and wipes the plaintext on every exit path. */
class PrivateOcrRunner(
  private val store: PrivateStore,
  private val recognizer: PrivateOcrRecognizer,
) {
  suspend fun recognize(
    access: PrivateAccess,
    privateAssetReference: String,
    progress: OcrProgressListener = OcrProgressListener {},
  ): String {
    val plaintext = store.readPrivateAsset(access, privateAssetReference)
    try {
      return recognizer.recognize(plaintext, progress)
    } finally {
      MemoryWipe.bytes(plaintext)
    }
  }
}

package com.anan.anannotes.data.files

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class OriginalImageStoreRestoreTest {
  @Test
  fun restoredOriginalIsStreamedIntoItsTransactionOwnedRelativePath() = withTempRoot { root ->
    val store = OriginalImageStore(root)
    val transactionId = "123e4567-e89b-12d3-a456-426614174000"
    val assetId = "123e4567-e89b-12d3-a456-426614174001"
    val bytes = ByteArray(190_000) { (it % 251).toByte() }

    val restored = store.importRestore(
      transactionId = transactionId,
      assetId = assetId,
      input = ByteArrayInputStream(bytes),
      extension = "png",
      expectedByteCount = bytes.size.toLong(),
      expectedSha256 = MessageDigest.getInstance("SHA-256").digest(bytes),
    )

    assertEquals("notes/assets/restore-$transactionId/$assetId.png", restored.relativePath)
    assertArrayEquals(bytes, store.resolve(restored.relativePath).readBytes())
    assertEquals(bytes.size.toLong(), restored.byteCount)
  }

  @Test
  fun wrongDigestOrDeclaredLengthNeverPublishesAReadableRestoredAsset() = withTempRoot { root ->
    val store = OriginalImageStore(root)
    val transactionId = "123e4567-e89b-12d3-a456-426614174000"
    val assetId = "123e4567-e89b-12d3-a456-426614174001"
    val bytes = "ordinary original image".toByteArray()
    val target = File(root, "notes/assets/restore-$transactionId/$assetId.jpg")

    assertThrows(IOException::class.java) {
      store.importRestore(
        transactionId,
        assetId,
        ByteArrayInputStream(bytes),
        "jpg",
        bytes.size.toLong(),
        ByteArray(32) { 0x5a },
      )
    }
    assertFalse(target.exists())
    assertFalse(File(root, "notes/assets/restore-$transactionId").walkTopDown().any { it.extension == "tmp" })

    assertThrows(IOException::class.java) {
      store.importRestore(
        transactionId,
        assetId,
        ByteArrayInputStream(bytes),
        "jpg",
        bytes.size.toLong() + 1,
        MessageDigest.getInstance("SHA-256").digest(bytes),
      )
    }
    assertFalse(target.exists())
  }

  @Test
  fun restoreRejectsInvalidIdsAndExtensionsBeforeCreatingAnyDirectory() = withTempRoot { root ->
    val store = OriginalImageStore(root)
    val bytes = byteArrayOf(1, 2, 3)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)

    assertThrows(IllegalArgumentException::class.java) {
      store.importRestore("../outside", "asset-id", ByteArrayInputStream(bytes), "jpg", 3L, digest)
    }
    assertThrows(IllegalArgumentException::class.java) {
      store.importRestore("123e4567-e89b-12d3-a456-426614174000", "asset-id", ByteArrayInputStream(bytes), "../x", 3L, digest)
    }
    assertFalse(File(root, "notes/assets").exists())
  }

  private fun withTempRoot(block: (File) -> Unit) {
    val root = Files.createTempDirectory("anan-image-restore-").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }
}

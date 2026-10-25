package com.anan.anannotes.feature.ocr

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrStagingStoreTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun textOnlySourceIsAddressedOnlyByStableRandomBlockIdAndCanBeDeleted() {
    val store = OcrStagingStore(temporaryFolder.newFolder("private-store"))
    val id = "123e4567-e89b-12d3-a456-426614174000"
    val source = byteArrayOf(1, 2, 3, 4)

    val staged = store.stage(id, ByteArrayInputStream(source))

    assertArrayEquals(source, staged.readBytes())
    assertTrue(store.delete(id))
    assertFalse(staged.exists())
  }

  @Test
  fun rejectsOversizedAndEmptySourcesWithoutLeavingStagingFiles() {
    val storageRoot = temporaryFolder.newFolder("private-store")
    val store = OcrStagingStore(storageRoot, maximumBytes = 3)
    val id = "123e4567-e89b-12d3-a456-426614174000"

    try {
      store.stage(id, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
      throw AssertionError("oversized content must be rejected")
    } catch (_: IOException) {
      assertFalse(store.fileFor(id).exists())
      assertTrue(File(storageRoot, "ocr-staging").listFiles().orEmpty().isEmpty())
    }
    try {
      store.stage(id, ByteArrayInputStream(byteArrayOf()))
      throw AssertionError("empty content must be rejected")
    } catch (_: IOException) {
      assertFalse(store.fileFor(id).exists())
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsPathsThatAreNotStableUuidBlockIds() {
    OcrStagingStore(temporaryFolder.newFolder("private-store")).fileFor("../../private")
  }
}

package com.anan.anannotes.ocr

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.feature.ocr.OcrStagingStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrStagingStoreRecoveryTest {
  @Test
  fun textOnlySourceSurvivesCacheEvictionAndCanBeReopenedAfterProcessRestart() {
    val appContext = ApplicationProvider.getApplicationContext<Context>()
    val token = UUID.randomUUID().toString()
    val isolatedCache = File(appContext.cacheDir, "ocr-recovery-cache-$token")
    val isolatedNoBackup = File(appContext.noBackupFilesDir, "ocr-recovery-store-$token")
    val isolatedContext = object : ContextWrapper(appContext) {
      override fun getCacheDir(): File = isolatedCache
      override fun getNoBackupFilesDir(): File = isolatedNoBackup
    }
    val blockId = "123e4567-e89b-12d3-a456-426614174000"
    val source = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)
    try {
      val firstProcessStore = OcrStagingStore.forContext(isolatedContext)
      val staged = firstProcessStore.stage(blockId, ByteArrayInputStream(source))
      isolatedCache.deleteRecursively()

      val reopenedStore = OcrStagingStore.forContext(isolatedContext)
      val reopenedSource = reopenedStore.fileFor(blockId)
      assertTrue(
        "暂存源图应位于可跨进程保留的应用私有目录: actual=${reopenedSource.path}, expectedRoot=${isolatedNoBackup.path}",
        reopenedSource.canonicalPath.startsWith(isolatedNoBackup.canonicalPath + File.separator),
      )
      assertArrayEquals(source, reopenedSource.readBytes())
      assertTrue(staged.exists())
    } finally {
      isolatedCache.deleteRecursively()
      isolatedNoBackup.deleteRecursively()
    }
  }

  @Test
  fun pendingSourcesFromEarlierCacheBasedVersionsRemainReadable() {
    val appContext = ApplicationProvider.getApplicationContext<Context>()
    val token = UUID.randomUUID().toString()
    val isolatedCache = File(appContext.cacheDir, "ocr-legacy-cache-$token")
    val isolatedNoBackup = File(appContext.noBackupFilesDir, "ocr-legacy-store-$token")
    val isolatedContext = object : ContextWrapper(appContext) {
      override fun getCacheDir(): File = isolatedCache
      override fun getNoBackupFilesDir(): File = isolatedNoBackup
    }
    val blockId = "123e4567-e89b-12d3-a456-426614174000"
    val source = byteArrayOf(9, 8, 7, 6)
    try {
      val legacyFile = OcrStagingStore.legacyCache(isolatedContext).stage(blockId, ByteArrayInputStream(source))

      val found = OcrStagingStore.findStagedSource(isolatedContext, blockId)

      assertEquals(legacyFile.canonicalFile, found?.canonicalFile)
      assertArrayEquals(source, found?.readBytes())
    } finally {
      isolatedCache.deleteRecursively()
      isolatedNoBackup.deleteRecursively()
    }
  }
}

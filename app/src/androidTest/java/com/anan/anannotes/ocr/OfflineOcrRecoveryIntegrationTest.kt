package com.anan.anannotes.ocr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.os.SystemClock
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.feature.ocr.OfflineOcrWorker
import com.anan.anannotes.feature.ocr.OcrStagingStore
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineOcrRecoveryIntegrationTest {
  @Test
  fun queuedWorkerForDeletedNoteRemovesItsDurableTextOnlySource() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val blockId = UUID.randomUUID().toString()
    val missingNoteId = UUID.randomUUID().toString()
    val workManager = WorkManager.getInstance(context)
    val workName = OfflineOcrWorker.workName(blockId)
    val staged = OcrStagingStore.forContext(context)
      .stage(blockId, ByteArrayInputStream(byteArrayOf(4, 3, 2, 1)))
    val request = OneTimeWorkRequestBuilder<OfflineOcrWorker>()
      .setInputData(
        Data.Builder()
          .putString(OfflineOcrWorker.KEY_NOTE_ID, missingNoteId)
          .putString(OfflineOcrWorker.KEY_OCR_BLOCK_ID, blockId)
          .build(),
      )
      .build()
    try {
      workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).result.get(10, TimeUnit.SECONDS)

      val result = awaitFinishedWork(workManager, request.id)

      assertTrue("不存在的笔记对应 worker 应安全结束", result.state == WorkInfo.State.SUCCEEDED)
      assertTrue("终态 worker 必须清理不可再恢复的暂存原图", !staged.exists())
    } finally {
      try {
        workManager.cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
      } finally {
        OcrStagingStore.deleteStagedSource(context, blockId)
      }
    }
  }

  @Test
  fun startupRecoverySkipsImportedOcrIdsThatHaveNoStagedSource() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = NoteRepository(
      AnAnDatabase.getInstance(context),
      assetStore = OriginalImageStore(context.filesDir),
    )
    // Backup record IDs are path-safe strings, not necessarily UUIDs.
    val blockId = "restored-ocr-block"
    val noteId = checkNotNull(repository.saveDraft(
      NoteDraft(
        title = "导入 OCR 恢复测试",
        titleManuallySet = true,
        blocks = listOf(NoteBlockDraft(id = blockId, type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING)),
      ),
    ))
    val workName = OfflineOcrWorker.workName(blockId)
    try {
      OfflineOcrWorker.recoverPending(context)

      val workInfos = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(workName)
        .get(10, TimeUnit.SECONDS)
      assertTrue("没有暂存源图的导入 OCR 块不得被错误入队", workInfos.isEmpty())
    } finally {
      try {
        WorkManager.getInstance(context).cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
      } finally {
        try {
          OcrStagingStore.deleteStagedSource(context, blockId)
        } finally {
          repository.deletePermanently(noteId)
        }
      }
    }
  }

  @Test
  fun startupRecoveryRequeuesTextOnlyOcrFromDurableStaging() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repository = NoteRepository(
      AnAnDatabase.getInstance(context),
      assetStore = OriginalImageStore(context.filesDir),
    )
    val blockId = UUID.randomUUID().toString()
    val noteId = checkNotNull(repository.saveDraft(
      NoteDraft(
        title = "OCR 恢复测试",
        titleManuallySet = true,
        blocks = listOf(
          NoteBlockDraft(type = BlockType.TEXT, text = "用于验证启动恢复"),
          NoteBlockDraft(id = blockId, type = BlockType.OCR_TEXT, ocrStatus = OcrStatus.PENDING),
        ),
      ),
    ))
    val workName = OfflineOcrWorker.workName(blockId)
    try {
      OcrStagingStore.forContext(context).stage(blockId, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))

      OfflineOcrWorker.recoverPending(context)

      val workInfos = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(workName)
        .get(10, TimeUnit.SECONDS)
      assertTrue(
        "启动恢复必须为纯文字 OCR 安排持久工作",
        workInfos.isNotEmpty(),
      )
    } finally {
      try {
        WorkManager.getInstance(context).cancelUniqueWork(workName).result.get(10, TimeUnit.SECONDS)
      } finally {
        try {
          OcrStagingStore.deleteStagedSource(context, blockId)
        } finally {
          repository.deletePermanently(noteId)
        }
      }
    }
  }

  private fun awaitFinishedWork(workManager: WorkManager, workId: UUID): WorkInfo {
    val deadlineMillis = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(15)
    while (SystemClock.elapsedRealtime() < deadlineMillis) {
      val info = workManager.getWorkInfoById(workId).get(2, TimeUnit.SECONDS)
        ?: throw AssertionError("OCR worker 信息未持久化")
      if (info.state == WorkInfo.State.SUCCEEDED || info.state == WorkInfo.State.FAILED || info.state == WorkInfo.State.CANCELLED) {
        return info
      }
      SystemClock.sleep(50)
    }
    throw AssertionError("OCR worker 在 15 秒内未进入终态")
  }
}

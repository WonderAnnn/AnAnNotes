package com.anan.anannotes.feature.ocr

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.NoteRepository
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException

class OfflineOcrWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
    val ocrBlockId = inputData.getString(KEY_OCR_BLOCK_ID) ?: return Result.failure()
    val repository = NoteRepository(
      AnAnDatabase.getInstance(applicationContext),
      assetStore = OriginalImageStore(applicationContext.filesDir),
    )
    val status = currentStatus(repository, noteId, ocrBlockId)
    if (!OcrJobRules.canRun(status)) {
      if (status == null || status == OcrStatus.SUCCEEDED || status == OcrStatus.CANCELLED) {
        OcrStagingStore.deleteStagedSource(applicationContext, ocrBlockId)
      }
      return Result.success()
    }
    val job = repository.ocrJobSource(noteId, ocrBlockId)
    val stagedSource = OcrStagingStore.findStagedSource(applicationContext, ocrBlockId)
    val image = try {
      stagedSource ?: job?.imageAssetPath?.let { OriginalImageStore(applicationContext.filesDir).resolve(it) }
    } catch (_: IllegalArgumentException) {
      null
    }
    if (image == null || !image.isFile) {
      repository.transitionOcrJob(noteId, ocrBlockId, OcrJobEvent.FAIL)
      OcrStagingStore.deleteStagedSource(applicationContext, ocrBlockId)
      return Result.failure()
    }
    repository.transitionOcrJob(noteId, ocrBlockId, OcrJobEvent.START)
    setStage(OcrProgressStage.PREPARING_MODELS)
    return try {
      if (!image.isFile) throw FileNotFoundException("识别源图片不存在")
      val text = TesseractOcrEngine(applicationContext).recognize(image) { stage -> setStage(stage) }
      val finalStatus = repository.transitionOcrJob(noteId, ocrBlockId, OcrJobEvent.SUCCEED, text)
      if (finalStatus == OcrStatus.SUCCEEDED) {
        setStage(OcrProgressStage.COMPLETE)
        OcrStagingStore.deleteStagedSource(applicationContext, ocrBlockId)
      } else if (finalStatus == null || finalStatus == OcrStatus.CANCELLED) {
        OcrStagingStore.deleteStagedSource(applicationContext, ocrBlockId)
      }
      Result.success()
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      if (runAttemptCount < MAX_RETRIES) {
        Result.retry()
      } else {
        repository.transitionOcrJob(noteId, ocrBlockId, OcrJobEvent.FAIL)
        OcrStagingStore.deleteStagedSource(applicationContext, ocrBlockId)
        Result.failure()
      }
    }
  }

  private suspend fun setStage(stage: OcrProgressStage) {
    setProgress(
      Data.Builder()
        .putInt(KEY_PROGRESS, stage.percent)
        .putString(KEY_STAGE, stage.label)
        .build(),
    )
  }

  private suspend fun currentStatus(repository: NoteRepository, noteId: String, blockId: String): OcrStatus? =
    repository.getDocumentSnapshot(noteId)?.blocks?.firstOrNull { it.id == blockId }?.ocrStatus

  companion object {
    const val KEY_NOTE_ID = "note_id"
    const val KEY_OCR_BLOCK_ID = "ocr_block_id"
    const val KEY_PROGRESS = "progress"
    const val KEY_STAGE = "stage"
    const val MAX_RETRIES = 2
    private const val WORK_PREFIX = "anan-offline-ocr-"

    fun enqueue(context: Context, noteId: String, ocrBlockId: String) {
      val request = OneTimeWorkRequestBuilder<OfflineOcrWorker>()
        .setInputData(
          Data.Builder()
            .putString(KEY_NOTE_ID, noteId)
            .putString(KEY_OCR_BLOCK_ID, ocrBlockId)
            .build(),
        )
        .build()
      WorkManager.getInstance(context.applicationContext)
        .enqueueUniqueWork(workName(ocrBlockId), ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancel(context: Context, noteId: String, ocrBlockId: String) {
      NoteRepository(AnAnDatabase.getInstance(context), assetStore = OriginalImageStore(context.filesDir))
        .transitionOcrJob(noteId, ocrBlockId, OcrJobEvent.CANCEL)
      WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(ocrBlockId))
      OcrStagingStore.deleteStagedSource(context, ocrBlockId)
    }

    suspend fun recoverPending(context: Context) {
      val repository = NoteRepository(
        AnAnDatabase.getInstance(context),
        assetStore = OriginalImageStore(context.filesDir),
      )
      repository.pendingOcrJobs().forEach { job ->
        if (job.imageAssetPath != null || OcrStagingStore.findStagedSource(context, job.ocrBlockId) != null) {
          enqueue(context, job.noteId, job.ocrBlockId)
        }
      }
    }

    suspend fun retry(context: Context, noteId: String, ocrBlockId: String): Boolean {
      val repository = NoteRepository(
        AnAnDatabase.getInstance(context),
        assetStore = OriginalImageStore(context.filesDir),
      )
      val job = repository.ocrJobSource(noteId, ocrBlockId)
      if (job?.imageAssetPath == null && OcrStagingStore.findStagedSource(context, ocrBlockId) == null) return false
      val next = repository.transitionOcrJob(noteId, ocrBlockId, OcrJobEvent.RETRY)
      if (next != OcrStatus.PENDING) return false
      enqueue(context, noteId, ocrBlockId)
      return true
    }

    fun workName(blockId: String) = "$WORK_PREFIX$blockId"
  }
}

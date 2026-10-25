package com.anan.anannotes.feature.privatemodule

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.ExportHistoryRepository
import com.anan.anannotes.feature.export.OutputTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivateExportMediaStoreIntegrationTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  @Test
  fun encryptedPrivateNoteAndImagePublishToGalleryWithoutEnteringOrdinaryHistory() = runBlocking {
    val token = UUID.randomUUID().toString()
    val title = "PrivateExportQA_$token"
    val password = "private-export-test-password"
    val privateRoot = File(context.cacheDir, "private-export-qa-$token")
    val store = PrivateStore(privateRoot)
    val repository = PrivateRepository(store)
    var access: com.anan.anannotes.security.PrivateAccess? = null
    val publishedUris = mutableListOf<Uri>()

    try {
      access = repository.setup(password.toCharArray(), null)
      val imageBytes = createFixturePng()
      val imageReference = store.writePrivateAsset(checkNotNull(access), imageBytes)
      val saved = repository.save(
        checkNotNull(access),
        PrivateEditSnapshot(
          draftId = "private-export-qa-$token",
          noteId = null,
          folderId = "private-uncategorized",
          title = title,
          titleManuallySet = true,
          blocks = listOf(
            NoteBlockDraft(id = "text-$token", type = BlockType.TEXT, text = "encrypted body $token"),
            NoteBlockDraft(
              id = "image-$token",
              type = BlockType.IMAGE,
              assetPath = imageReference,
              widthPercent = 64f,
              aspectRatio = 1f,
            ),
          ),
          skinId = "minimal-white",
          baseContentRevision = null,
          localRevision = 1L,
          savedAtMillis = System.currentTimeMillis(),
        ),
      ) as PrivateSaveResult.Saved
      val request = PrivateExportRequest(saved.noteId, ExportFormat.PNG_SOCIAL_SQUARE, OutputTarget.MediaStorePictures)
      val grant = repository.authorizeExport(checkNotNull(access), password.toCharArray(), request.scope())
      assertNotNull("正确密码应签发一次性私密导出授权", grant)

      val events = PrivateExportServiceFactory.create(context.applicationContext as Application, repository, store)
        .export(checkNotNull(access), checkNotNull(grant), request)
        .toList()

      val completedPages = events.filterIsInstance<PrivateExportEvent.ItemCompleted>()
      assertTrue("PNG 导出至少应发布一页", completedPages.isNotEmpty())
      publishedUris += completedPages.map { Uri.parse(it.location) }
      assertTrue(
        events.any {
          it == PrivateExportEvent.Completed(succeeded = completedPages.size, failed = 0, historyFailures = 0)
        },
      )

      val resolver = context.contentResolver
      val projection = arrayOf(
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.IS_PENDING,
      )
      var renderedPrivatePixelsFound = false
      publishedUris.forEach { uri ->
        resolver.query(uri, projection, null, null, null).use { cursor ->
          assertNotNull("导出文件应可由系统相册 URI 查询", cursor)
          checkNotNull(cursor)
          assertTrue("MediaStore 应包含已发布图片", cursor.moveToFirst())
          assertTrue(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)).contains(token))
          assertEquals("image/png", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)))
          assertEquals("Pictures/AnAn Notes/Exports/", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)))
          assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)))
        }

        val image = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(checkNotNull(it)) }
        try {
          assertEquals(1080, image.width)
          assertEquals(1080, image.height)
          renderedPrivatePixelsFound = renderedPrivatePixelsFound || containsColor(image, FIXTURE_COLOR)
        } finally {
          image.recycle()
        }
      }
      assertTrue("渲染结果应包含从私密库解密的原图像素", renderedPrivatePixelsFound)

      val privateHistory = repository.listPrivateExportHistory(checkNotNull(access))
      val noteHistory = privateHistory.filter { it.noteId == saved.noteId && it.succeeded }
      assertEquals(publishedUris.size, noteHistory.size)
      assertTrue(noteHistory.all { it.fileName.contains(token) })
      val ordinaryHistory = ExportHistoryRepository(AnAnDatabase.getInstance(context)).latest()
      assertFalse("私密导出历史不得写入普通 Room", ordinaryHistory.any { it.fileName.contains(token) })
      assertFalse(
        "私密标题不得进入普通笔记表",
        AnAnDatabase.getInstance(context).noteDao().observeAllActive().first().any { it.title == title },
      )
    } finally {
      publishedUris.forEach { context.contentResolver.delete(it, null, null) }
      access?.let(repository::revoke)
      privateRoot.deleteRecursively()
    }
  }

  private fun createFixturePng(): ByteArray {
    val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(FIXTURE_COLOR) }
    return ByteArrayOutputStream().use { output ->
      check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
      bitmap.recycle()
      output.toByteArray()
    }
  }

  private fun containsColor(bitmap: Bitmap, color: Int): Boolean {
    val row = IntArray(bitmap.width)
    for (y in 0 until bitmap.height) {
      bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
      if (row.any { it == color }) return true
    }
    return false
  }

  private companion object {
    const val FIXTURE_COLOR = 0xff25a7a0.toInt()
  }
}

package com.anan.anannotes.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStorePngExportFileStoreTest {
  @Test
  fun publishesPngInGalleryVisibleExportsFolder() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val resolver = context.contentResolver
    val store = MediaStorePngExportFileStore(resolver)
    val requestedName = "AnAn-test-${System.currentTimeMillis()}.png"
    var createdUri: Uri? = null
    val source = Bitmap.createBitmap(7, 5, Bitmap.Config.ARGB_8888)

    try {
      val receipt = store.writePng(requestedName) { output ->
        assertTrue("PNG 编码应成功", source.compress(Bitmap.CompressFormat.PNG, 100, output))
      }
      createdUri = Uri.parse(receipt.location)

      assertEquals(requestedName, receipt.fileName)
      assertEquals("图片/安安简记/导出", receipt.destinationName)
      val projection = arrayOf(
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.IS_PENDING,
      )
      resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
        arrayOf(requestedName, "Pictures/AnAn Notes/Exports/"),
        null,
      ).use { cursor ->
        assertNotNull("已发布 PNG 应能从 MediaStore 图片集合发现", cursor)
        checkNotNull(cursor)
        assertTrue("MediaStore 中应存在导出项", cursor.moveToFirst())
        assertEquals("image/png", cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)))
        assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)))
      }
      resolver.openInputStream(checkNotNull(createdUri)).use { input ->
        assertNotNull("系统相册应能打开已经发布的 PNG", input)
        val decoded = BitmapFactory.decodeStream(checkNotNull(input))
        assertEquals(7, decoded.width)
        assertEquals(5, decoded.height)
        decoded.recycle()
      }
    } finally {
      source.recycle()
      createdUri?.let { resolver.delete(it, null, null) }
    }
  }
}

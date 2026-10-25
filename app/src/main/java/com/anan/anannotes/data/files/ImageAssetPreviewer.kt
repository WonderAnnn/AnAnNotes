package com.anan.anannotes.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

data class ImagePreview(
  val file: File,
  val relativePath: String,
  val width: Int,
  val height: Int,
)

class ImageAssetPreviewer(filesDirectory: File, cacheDirectory: File) {
  private val originalStore = OriginalImageStore(filesDirectory)
  private val previewStore = PreviewCacheStore(cacheDirectory)

  suspend fun ensure(originalPath: String, previewPath: String? = null): ImagePreview = withContext(Dispatchers.IO) {
    val original = originalStore.resolve(originalPath)
    if (!original.isFile) throw IOException("找不到笔记原图")
    val assetId = original.nameWithoutExtension
    val targetPath = previewPath ?: "${PreviewCacheStore.PREVIEW_DIRECTORY}/$assetId.jpg"
    val target = previewStore.resolve(targetPath)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(original.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片数据无法解码")
    if (!target.isFile) writePreview(original, assetId)
    val resolved = previewStore.resolve(targetPath)
    if (!resolved.isFile) throw IOException("图片预览缓存生成失败")
    ImagePreview(resolved, targetPath, bounds.outWidth, bounds.outHeight)
  }

  private fun writePreview(original: File, assetId: String) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(original.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片数据无法解码")
    val bitmap = BitmapFactory.decodeFile(
      original.absolutePath,
      BitmapFactory.Options().apply {
        inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.RGB_565
      },
    ) ?: throw IOException("无法生成图片预览")
    try {
      val bytes = ByteArrayOutputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_JPEG_QUALITY, output)) throw IOException("预览图编码失败")
        output.toByteArray()
      }
      previewStore.write(assetId, bytes)
    } finally {
      bitmap.recycle()
    }
  }

  private fun calculateSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > MAX_PREVIEW_EDGE_PX && sampleSize <= Int.MAX_VALUE / 2) sampleSize *= 2
    return sampleSize
  }

  companion object {
    const val MAX_PREVIEW_EDGE_PX = 2048
    const val PREVIEW_JPEG_QUALITY = 84
  }
}

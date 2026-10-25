package com.anan.anannotes.data.files

import android.content.ContentResolver
import android.net.Uri
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.NoteBlockDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class ImageAssetImporter(
  private val contentResolver: ContentResolver,
  filesDirectory: File,
  cacheDirectory: File,
) {
  private val originalStore = OriginalImageStore(filesDirectory)
  private val previewer = ImageAssetPreviewer(filesDirectory, cacheDirectory)

  suspend fun import(uri: Uri): NoteBlockDraft = withContext(Dispatchers.IO) {
    val mimeType = contentResolver.getType(uri)?.lowercase() ?: throw IOException("无法读取图片类型")
    val extension = when (mimeType) {
      "image/jpeg", "image/jpg" -> "jpg"
      "image/png" -> "png"
      "image/webp" -> "webp"
      "image/heic", "image/heif" -> "heic"
      "image/gif" -> "gif"
      else -> throw IOException("不支持的图片格式：$mimeType")
    }
    val source = contentResolver.openInputStream(uri) ?: throw IOException("无法打开所选图片")
    val original = originalStore.import(source, extension)
    try {
      val originalFile = originalStore.resolve(original.relativePath)
      if (!originalFile.isFile) throw IOException("图片原图保存失败")
      val preview = previewer.ensure(original.relativePath)
      NoteBlockDraft(
        id = UUID.randomUUID().toString(),
        type = BlockType.IMAGE,
        assetPath = original.relativePath,
        previewPath = preview.relativePath,
        widthPercent = 100f,
        alignment = BlockAlignment.CENTER,
        aspectRatio = preview.width.toFloat() / preview.height.toFloat(),
      )
    } catch (error: Exception) {
      originalStore.delete(original.relativePath)
      throw error
    }
  }
}

package com.anan.anannotes.feature.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.NotePreviewModel
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.files.PreviewCacheStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun NoteCard(
  note: NotePreviewModel,
  selected: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  val shape = RoundedCornerShape(22.dp)
  Card(
    modifier = modifier
      .combinedClickable(onClick = onClick, onLongClick = onLongClick)
      .testTag("note-card-${note.id}"),
    shape = shape,
    colors = CardDefaults.cardColors(
      containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 18.dp),
      verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 9.dp),
    ) {
      Text(
        text = note.title.ifBlank { "无标题笔记" },
        style = MaterialTheme.typography.titleMedium,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = formatNoteTime(note.updatedAtMillis),
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      note.summaryText?.takeIf(String::isNotBlank)?.let { summary ->
        Text(
          text = summary,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = if (compact) 1 else 4,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (note.firstImagePreviewPath != null || note.firstImageAssetPath != null) {
        NoteImageThumbnail(
          previewPath = note.firstImagePreviewPath,
          assetPath = note.firstImageAssetPath,
          aspectRatio = note.firstImageAspectRatio,
          modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        )
      }
    }
  }
}

@Composable
private fun NoteImageThumbnail(
  previewPath: String?,
  assetPath: String?,
  aspectRatio: Float?,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val image by produceState<ImageBitmap?>(initialValue = null, key1 = previewPath, key2 = assetPath) {
    value = withContext(Dispatchers.IO) {
      val previewFile = previewPath?.let { path ->
        runCatching { PreviewCacheStore(context.cacheDir).resolve(path) }.getOrNull()
      }
      val originalFile = assetPath?.let { path ->
        val requestedFile = File(path)
        if (requestedFile.isAbsolute) requestedFile
        else runCatching { OriginalImageStore(context.filesDir).resolve(path) }.getOrNull()
      }
      listOfNotNull(previewFile, originalFile)
        .firstNotNullOfOrNull { file -> if (file.isFile) decodeThumbnail(file) else null }
    }
  }
  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .then(if (aspectRatio != null && aspectRatio > 0f) Modifier.clipToAspectRatio(aspectRatio) else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    if (image != null) {
      Image(
        bitmap = image!!,
        contentDescription = "笔记图片缩略图",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxWidth(),
      )
    } else {
      Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text("图片", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

private fun Modifier.clipToAspectRatio(ratio: Float): Modifier =
  this.then(Modifier.aspectRatio(ratio.coerceIn(0.5f, 2.5f)))

private fun decodeThumbnail(file: File): ImageBitmap? = runCatching {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(file.absolutePath, bounds)
  val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
  val sampleSize = if (largestSide > 512) (largestSide / 512).coerceAtLeast(1) else 1
  val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
  BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()

private fun formatNoteTime(millis: Long): String =
  SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))

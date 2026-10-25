package com.anan.anannotes.feature.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.ImageBlockRules
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.data.files.ImageAssetPreviewer
import com.anan.anannotes.ui.theme.NoteSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageBlock(
  block: NoteBlockDraft,
  filesDirectory: File,
  cacheDirectory: File,
  skin: NoteSkin,
  selected: Boolean,
  selectionMode: Boolean,
  onClick: () -> Unit,
  onToggleSelection: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val previewer = androidx.compose.runtime.remember(filesDirectory, cacheDirectory) {
    ImageAssetPreviewer(filesDirectory, cacheDirectory)
  }
  val previewFileState = produceState<File?>(null, block.assetPath, block.previewPath) {
    value = runCatching {
      val originalPath = block.assetPath ?: return@runCatching null
      previewer.ensure(originalPath, block.previewPath).file
    }.getOrNull()
  }
  val previewFile = previewFileState.value
  val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, previewFile?.absolutePath) {
    value = withContext(Dispatchers.IO) {
      previewFile?.takeIf(File::isFile)?.absolutePath?.let { path -> BitmapFactory.decodeFile(path)?.asImageBitmap() }
    }
  }
  val image = bitmapState.value
  val fraction = ImageBlockRules.normalizeWidthPercent(block.widthPercent) / 100f
  val aspectRatio = block.aspectRatio?.takeIf { it.isFinite() && it > 0f }
    ?: image?.let { it.width.toFloat() / it.height.toFloat() }
    ?: 1f
  val horizontalAlignment = when (block.alignment) {
    BlockAlignment.START -> Alignment.CenterStart
    BlockAlignment.CENTER -> Alignment.Center
    BlockAlignment.END -> Alignment.CenterEnd
  }
  val imageModifier = Modifier
    .fillMaxSize()
    .combinedClickable(
      onClick = { if (selectionMode) onToggleSelection() else onClick() },
      onLongClick = onToggleSelection,
    )
    .semantics { this.selected = selected }
    .testTag("image-block-${block.id}")

  Box(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
    Box(
      modifier = Modifier.fillMaxWidth(fraction).aspectRatio(aspectRatio).align(horizontalAlignment),
    ) {
      if (image == null) {
        Surface(
          modifier = imageModifier,
          color = skin.surface,
          shape = RoundedCornerShape(14.dp),
        ) {
          Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(24.dp)) {
            Text("图片预览暂不可用", color = skin.secondaryText, style = MaterialTheme.typography.bodySmall)
          }
        }
      } else {
        Image(
          bitmap = image,
          contentDescription = "笔记图片，宽度 ${block.widthPercent.toInt()}%",
          contentScale = ContentScale.Fit,
          modifier = imageModifier,
        )
      }
      if (selectionMode) {
        Checkbox(
          checked = selected,
          onCheckedChange = { onToggleSelection() },
          modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).testTag("image-selection-checkbox-${block.id}"),
        )
      }
    }
  }
}

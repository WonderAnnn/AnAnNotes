package com.anan.anannotes.feature.editor

import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anan.anannotes.core.model.canonicalNoteSkinId
import com.anan.anannotes.ui.theme.NoteSkin
import com.anan.anannotes.ui.theme.noteSkinBackground
import com.anan.anannotes.ui.theme.NoteSkinCatalog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkinPickerSheet(
  selectedSkinId: String,
  title: String,
  body: String,
  onSelectSkin: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  val selectedSkin = NoteSkinCatalog.byId(selectedSkinId)
  val canonicalSelectedSkinId = canonicalNoteSkinId(selectedSkinId)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = minOf(LocalWindowInfo.current.containerDpSize.height * 0.72f, 360.dp))
        .verticalScroll(rememberScrollState())
        .padding(bottom = 18.dp)
        .testTag("skin-picker-sheet"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("选择皮肤", style = MaterialTheme.typography.titleLarge)
        TextButton(
          onClick = onDismiss,
          modifier = Modifier.testTag("skin-picker-close"),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
          Text("完成", color = MaterialTheme.colorScheme.onSurface)
        }
      }

      LazyRow(
        modifier = Modifier.testTag("skin-picker-options"),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        items(NoteSkinCatalog.all, key = NoteSkin::id) { skin ->
          SkinOption(
            skin = skin,
            selected = skin.id == canonicalSelectedSkinId,
            onClick = { onSelectSkin(skin.id) },
          )
        }
      }

      Text(
        text = "预览",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp),
      )
      Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 118.dp).testTag("skin-live-preview"),
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().noteSkinBackground(selectedSkin).padding(horizontal = 18.dp, vertical = 14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = title.ifBlank { "标题" },
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
            color = selectedSkin.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = body.ifBlank { "写下此刻…" },
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = selectedSkin.secondaryText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun SkinOption(
  skin: NoteSkin,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  Card(
    modifier = Modifier
      .width(126.dp)
      .height(78.dp)
      // Clip the selectable indication itself, not just the card's contents, so its pressed
      // state follows the rounded tile instead of flashing as a rectangular overlay.
      .clip(shape)
      .border(
        width = if (selected) 2.dp else 1.dp,
        color = if (selected) skin.accent else MaterialTheme.colorScheme.outline,
        shape = shape,
      )
      .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
      .testTag("skin-option-${skin.id}"),
    shape = shape,
    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().noteSkinBackground(skin).padding(horizontal = 12.dp, vertical = 11.dp),
      verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
      Text(
        text = skin.name,
        style = MaterialTheme.typography.labelLarge,
        color = skin.foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Surface(
        modifier = Modifier.fillMaxWidth(0.7f).height(5.dp),
        shape = RoundedCornerShape(50),
        color = skin.accent,
      ) {}
    }
  }
}

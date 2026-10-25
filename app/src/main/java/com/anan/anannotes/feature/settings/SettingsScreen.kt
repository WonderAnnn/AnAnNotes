package com.anan.anannotes.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ThemeMode
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold
import com.anan.anannotes.ui.components.HapticSlider
import com.anan.anannotes.feature.export.ExportDirectoryPreferences

@Composable
fun SettingsScreen(
  settings: DisplaySettings,
  onSettingsChange: (DisplaySettings) -> Unit,
  onClearPreviewCache: () -> Unit,
  cacheMessage: String?,
  onBack: () -> Unit,
  onOpenExportHistory: () -> Unit = {},
  onOpenStorageOverview: () -> Unit = {},
  onOpenBackupRestore: () -> Unit = {},
  onOpenPrivacyNotice: () -> Unit = {},
) {
  val context = LocalContext.current
  var exportDirectoryUri by remember { mutableStateOf(ExportDirectoryPreferences.read(context)) }
  var exportDirectoryMessage by remember { mutableStateOf<String?>(null) }
  val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        .onSuccess {
          ExportDirectoryPreferences.write(context, uri.toString())
          exportDirectoryUri = uri.toString()
          exportDirectoryMessage = "导出位置已更新"
        }
        .onFailure {
          exportDirectoryMessage = "无法保存此文件夹的访问授权，请重新选择。"
        }
    }
  }

  PageScaffold(modifier = Modifier.testTag("settings-screen")) {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
    CompactPageHeader(title = "设置", onBack = onBack, testTag = "settings-page-header")

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
        Text("字体与显示", style = MaterialTheme.typography.titleLarge)
        Text("使用系统字体", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("默认字号", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          Text("${settings.defaultFontSizeSp.toInt()} sp", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HapticSlider(
          value = settings.defaultFontSizeSp,
          onValueChange = { onSettingsChange(settings.copy(defaultFontSizeSp = DisplaySettingsRules.normalizeFontSize(it))) },
          valueRange = DisplaySettingsRules.MIN_FONT_SIZE_SP..DisplaySettingsRules.MAX_FONT_SIZE_SP,
          steps = 0,
          hapticStepSize = 1f,
          modifier = Modifier.testTag("default-font-size-slider"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("行间距", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
          Text("${"%.2f".format(settings.lineSpacingMultiplier)} 倍", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HapticSlider(
          value = settings.lineSpacingMultiplier,
          onValueChange = { onSettingsChange(settings.copy(lineSpacingMultiplier = DisplaySettingsRules.normalizeLineSpacing(it))) },
          valueRange = DisplaySettingsRules.MIN_LINE_SPACING_MULTIPLIER..DisplaySettingsRules.MAX_LINE_SPACING_MULTIPLIER,
          steps = 0,
          hapticStepSize = 0.05f,
          modifier = Modifier.testTag("default-line-spacing-slider"),
        )
        Card(
          modifier = Modifier.fillMaxWidth().testTag("display-settings-preview"),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("字体预览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
              "安安简记，让记录轻松一点。\n这是一段预览文字。",
              fontSize = settings.defaultFontSizeSp.sp,
              lineHeight = (settings.defaultFontSizeSp * settings.lineSpacingMultiplier).sp,
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
      }
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("默认皮肤", style = MaterialTheme.typography.titleLarge)
        Text("仅用于新建笔记；已有笔记保留各自皮肤。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        LazyRow(contentPadding = PaddingValues(end = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(NoteSkinCatalog.all, key = { it.id }) { skin ->
            FilterChip(
              selected = settings.defaultSkinId == skin.id,
              onClick = { onSettingsChange(settings.copy(defaultSkinId = skin.id)) },
              label = { Text(skin.name) },
              modifier = Modifier.testTag("default-skin-${skin.id}"),
            )
          }
        }
      }
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("界面主题", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ThemeMode.entries.forEach { mode ->
            FilterChip(
              selected = settings.themeMode == mode,
              onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
              label = { Text(mode.label) },
              modifier = Modifier.testTag("theme-mode-${mode.name.lowercase()}"),
            )
          }
        }
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth().testTag("settings-export-directory"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("导出保存位置", style = MaterialTheme.typography.titleMedium)
          Text(
            if (exportDirectoryUri == null) "首次导出时选择文件夹" else "已记住上次选择的文件夹",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
          )
          exportDirectoryMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
          }
        }
        TextButton(
          onClick = { exportDirectoryPicker.launch(exportDirectoryUri?.let(Uri::parse)) },
          modifier = Modifier.testTag("choose-export-directory"),
        ) { Text(if (exportDirectoryUri == null) "选择" else "更换") }
      }
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text("数据与存储", modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onOpenExportHistory, modifier = Modifier.fillMaxWidth().testTag("settings-export-history")) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("导出记录", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
              Text("查看文件、打开或分享", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        TextButton(onClick = onOpenStorageOverview, modifier = Modifier.fillMaxWidth().testTag("settings-storage-overview")) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("存储概览", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
              Text("查看占用与清理预览图缓存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        TextButton(onClick = onOpenBackupRestore, modifier = Modifier.fillMaxWidth().testTag("settings-backup-restore")) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text("加密备份与恢复", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
              Text("备份或迁移本机笔记与私密内容", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
        TextButton(onClick = onOpenPrivacyNotice, modifier = Modifier.fillMaxWidth().testTag("settings-privacy-notice")) {
          Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text("隐私说明", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
              Text("了解本地保存、加密与导出边界", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("预览图缓存", style = MaterialTheme.typography.titleMedium)
          cacheMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = onClearPreviewCache, modifier = Modifier.testTag("clear-preview-cache")) { Text("清理") }
      }
    }

    Card(
      modifier = Modifier.fillMaxWidth().testTag("settings-about"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text("关于安安简记", style = MaterialTheme.typography.titleMedium)
        Text("作者：WonderAnn", modifier = Modifier.testTag("settings-author"), style = MaterialTheme.typography.bodyLarge)
      }
    }
  }
}
}

private val ThemeMode.label: String
  get() = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
  }

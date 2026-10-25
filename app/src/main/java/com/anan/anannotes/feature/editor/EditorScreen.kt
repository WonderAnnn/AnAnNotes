package com.anan.anannotes.feature.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.TextSelection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.ui.theme.NoteSkin
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import com.anan.anannotes.ui.theme.noteSkinBackground
import com.anan.anannotes.feature.export.ClipboardTextWriter
import com.anan.anannotes.feature.export.ExportLayout
import com.anan.anannotes.feature.export.ExportFormatSheet
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.feature.ocr.OcrImportMode
import com.anan.anannotes.feature.ocr.OcrImportModeSheet

@Composable
fun EditorScreen(
  noteId: String?,
  folderId: String,
  initialImportText: String? = null,
  initialImportUris: List<String> = emptyList(),
  importRequestId: String? = null,
  displaySettings: DisplaySettings = DisplaySettings(),
  onDisplaySettingsChange: (DisplaySettings) -> Unit = {},
  onStatusBarDarkIconsChange: (Boolean) -> Unit = {},
  isStatusBarOwner: Boolean = true,
  onStorage: () -> Unit = {},
  onBack: () -> Unit,
) {
  val application = LocalContext.current.applicationContext as Application
  val factory = androidx.compose.runtime.remember(
    application,
    noteId,
    folderId,
    importRequestId,
    initialImportText,
    initialImportUris,
    displaySettings.defaultSkinId,
  ) {
    EditorViewModelFactory(application, noteId, folderId, displaySettings, initialImportText, initialImportUris, importRequestId)
  }
  val viewModel: EditorViewModel = viewModel(
    key = "editor-${noteId ?: "new-$folderId-${importRequestId ?: "manual"}"}",
    factory = factory,
  )
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  var showSkinPicker by remember { mutableStateOf(false) }
  var showTypographySheet by remember { mutableStateOf(false) }
  var adjustingImageBlockId by remember { mutableStateOf<String?>(null) }
  var selectedImageBlockIds by remember(noteId) { mutableStateOf(emptySet<String>()) }
  var pendingImageDeletionIds by remember { mutableStateOf<Set<String>?>(null) }
  var showExportFormatSheet by rememberSaveable { mutableStateOf(false) }
  var pendingExportFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
  var pendingExportAttribution by rememberSaveable { mutableStateOf(false) }
  var showOcrImportModeSheet by rememberSaveable { mutableStateOf(false) }
  var pendingOcrImportMode by rememberSaveable { mutableStateOf(OcrImportMode.IMAGE_ONLY) }
  var isChangingExportDirectory by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }
  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri?.let { viewModel.insertImage(it, pendingOcrImportMode) }
    pendingOcrImportMode = OcrImportMode.IMAGE_ONLY
  }
  val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      runCatching { application.contentResolver.takePersistableUriPermission(uri, grantFlags) }
        .onSuccess {
          viewModel.rememberExportDirectory(uri.toString())
          val format = pendingExportFormat
          val includeAttribution = pendingExportAttribution
          pendingExportFormat = null
          pendingExportAttribution = false
          if (format != null) {
            viewModel.exportCurrentNote(format, OutputTarget.DocumentTree(uri.toString()), includeAttribution)
          } else if (isChangingExportDirectory) {
            viewModel.showExportMessage("导出位置已更新。")
          }
        }
        .onFailure {
          pendingExportFormat = null
          pendingExportAttribution = false
          viewModel.showExportMessage("无法保存此文件夹的访问授权，请重新选择。")
        }
    } else {
      pendingExportFormat = null
      pendingExportAttribution = false
    }
    isChangingExportDirectory = false
  }
  val skin = NoteSkinCatalog.byId(state.skinId)
  LaunchedEffect(isStatusBarOwner, state.isLoaded, skin.id, onStatusBarDarkIconsChange) {
    if (isStatusBarOwner && state.isLoaded) {
      onStatusBarDarkIconsChange(skin.background.luminance() > 0.5f)
    }
  }
  val resolvedStyle = DisplaySettingsRules.resolve(displaySettings, state.fontSizeSpOverride, state.lineSpacingMultiplierOverride)
  val activeTextBlock = state.blocks.firstOrNull { it.id == state.activeTextBlockId }
  val inlineToolStates = InlineStyle.entries.associateWith { style -> state.inlineToolState(style) }

  BackHandler(enabled = true) {
    if (state.isExporting) {
      Unit
    } else if (selectedImageBlockIds.isNotEmpty()) {
      selectedImageBlockIds = emptySet()
    } else {
      viewModel.saveAndExit(onBack)
    }
  }

  Box(modifier = Modifier.fillMaxSize().noteSkinBackground(skin)) {
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = Color.Transparent,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      if (state.isLoaded) {
        if (selectedImageBlockIds.isNotEmpty()) {
          ImageSelectionActionBar(
            selectedCount = selectedImageBlockIds.size,
            skin = skin,
            onDelete = { pendingImageDeletionIds = selectedImageBlockIds },
          )
        } else {
          EditorToolbar(
            showMoreToolbar = state.showMoreToolbar,
            onMoreClick = viewModel::toggleMoreToolbar,
            onBoldClick = { viewModel.applyInlineStyle(InlineStyle.BOLD) },
            onHeadingClick = viewModel::togglePresentation,
            onItalicClick = { viewModel.applyInlineStyle(InlineStyle.ITALIC) },
            onStrikethroughClick = { viewModel.applyInlineStyle(InlineStyle.STRIKETHROUGH) },
            onQuoteClick = viewModel::toggleQuote,
            inlineToolStates = inlineToolStates,
            headingToolState = if (
              activeTextBlock?.type == BlockType.TEXT &&
              (activeTextBlock.formatting as? BlockFormattingState.Supported)?.value?.presentation == TextBlockPresentation.HEADING
            ) EditorToolState.ON else EditorToolState.OFF,
            quoteToolState = if (activeTextBlock?.type == BlockType.QUOTE) EditorToolState.ON else EditorToolState.OFF,
            onTextSettingsClick = { showTypographySheet = true },
            onImageClick = { showOcrImportModeSheet = true },
            onInsertTodo = viewModel::insertTodo,
            onCopyClick = {
              val text = ExportLayout.toPlainText(
                title = state.titleForSave,
                blocks = state.blocks,
                completedTodoBlockIds = state.completedTodoBlockIds,
              )
              runCatching { ClipboardTextWriter.copy(application, text, sensitive = false) }
                .onSuccess { viewModel.showExportMessage("已复制全文") }
                .onFailure { viewModel.showExportMessage("复制失败，请稍后重试。") }
            },
            skin = skin,
          )
        }
      }
    },
  ) { contentPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding)
        .consumeWindowInsets(contentPadding)
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .testTag("screen-editor"),
    ) {
      if (selectedImageBlockIds.isNotEmpty()) {
        ImageSelectionHeader(
          selectedCount = selectedImageBlockIds.size,
          onSelectAll = {
            selectedImageBlockIds = state.blocks.filter { it.type == BlockType.IMAGE }.mapTo(linkedSetOf()) { it.id }
          },
          onCancel = { selectedImageBlockIds = emptySet() },
          skin = skin,
        )
      } else {
        EditorTopBar(
          saveStatus = state.saveStatus,
          onBack = { viewModel.saveAndExit(onBack) },
          onRetry = viewModel::retrySave,
          onExport = { showExportFormatSheet = true },
          exportEnabled = state.isLoaded && !state.isExporting,
          onOpenSkinPicker = { showSkinPicker = true },
          skin = skin,
        )
      }

      if (!state.isLoaded) {
        Text("正在打开笔记…", style = MaterialTheme.typography.bodyMedium)
      } else {
        EditorWritingArea(
          title = TextFieldValue(
            text = state.titleFieldValue,
            selection = TextRange(
              state.titleSelectionStart.coerceIn(0, state.titleFieldValue.length),
              state.titleSelectionEnd.coerceIn(0, state.titleFieldValue.length),
            ),
            composition = state.titleComposing?.toTextRange(state.titleFieldValue),
          ),
          blocks = state.blocks,
          activeTextBlockId = state.activeTextBlockId,
          completedTodoBlockIds = state.completedTodoBlockIds,
          ocrProgressByBlock = state.ocrProgressByBlock,
          focusTodoBlockId = state.focusTodoBlockId,
          selectionStart = state.selectionStart,
          selectionEnd = state.selectionEnd,
          composing = state.composing,
          todoSelections = state.todoSelections,
          todoCompositions = state.todoCompositions,
          wordCount = state.blocks.filter { it.type in setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT) }.sumOf { it.text.orEmpty().length },
          onTitleValueChanged = { value ->
            viewModel.onTitleValueChanged(
              title = value.text,
              selection = TextSelection(value.selection.min, value.selection.max),
              composing = value.composition?.let { TextSelection(it.min, it.max) },
            )
          },
          onTextValueChanged = viewModel::onTextValueChanged,
          onTodoValueChanged = { blockId, value ->
            viewModel.onTodoValueChanged(
              blockId = blockId,
              text = value.text,
              selection = TextSelection(value.selection.min, value.selection.max),
              composing = value.composition?.let { TextSelection(it.min, it.max) },
            )
          },
          onTodoCompletionChanged = viewModel::onTodoCompletionChanged,
          onTodoFocusHandled = viewModel::onTodoFocusHandled,
          onTodoFocusChanged = viewModel::onTodoFocusChanged,
          onInsertTodo = viewModel::insertTodo,
          onOcrCancel = viewModel::cancelOcr,
          onOcrRetry = viewModel::retryOcr,
          onResetUnsupportedFormatting = viewModel::resetUnsupportedFormatting,
          filesDirectory = application.filesDir,
          cacheDirectory = application.cacheDir,
          selectedImageBlockIds = selectedImageBlockIds,
          onImageClick = { block ->
            if (selectedImageBlockIds.isEmpty()) adjustingImageBlockId = block.id
            else selectedImageBlockIds = selectedImageBlockIds - block.id
          },
          onToggleImageSelection = { imageId ->
            selectedImageBlockIds = if (imageId in selectedImageBlockIds) {
              selectedImageBlockIds - imageId
            } else {
              selectedImageBlockIds + imageId
            }
          },
          fontSizeSp = resolvedStyle.fontSizeSp,
          lineSpacingMultiplier = resolvedStyle.lineSpacingMultiplier,
          skin = skin,
        )
      }

      if (state.errorMessage != null) {
        Text(
          text = state.errorMessage.orEmpty(),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
    }
  }
  }

  LaunchedEffect(state.exportMessage) {
    val message = state.exportMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.dismissExportMessage()
  }

  val pendingExportFeedback = state.pendingExportFeedback.firstOrNull()
  LaunchedEffect(pendingExportFeedback?.id) {
    pendingExportFeedback?.let { feedback ->
      val result = snackbarHostState.showSnackbar(
        message = feedback.message,
        actionLabel = if (feedback.showStorageAction) "查看存储" else null,
        duration = if (feedback.showStorageAction) SnackbarDuration.Long else SnackbarDuration.Short,
      )
      viewModel.acknowledgeExportFeedback(feedback.id)
      if (result == SnackbarResult.ActionPerformed && feedback.showStorageAction) onStorage()
    }
  }

  if (showExportFormatSheet && state.isLoaded) {
    ExportFormatSheet(
      title = "导出此笔记",
      sheetTestTag = "editor-export-format-sheet",
      listTestTag = "editor-export-format-list",
      exportDirectoryLabel = if (viewModel.savedExportDirectory() == null) "选择保存文件夹" else "更换保存文件夹",
      onSelectDocumentFormat = { format, includeAttribution ->
        showExportFormatSheet = false
        val directory = viewModel.savedExportDirectory()
        if (directory != null) {
          viewModel.exportCurrentNote(format, OutputTarget.DocumentTree(directory), includeAttribution)
        } else {
          pendingExportFormat = format
          pendingExportAttribution = includeAttribution
          exportDirectoryPicker.launch(null)
        }
      },
      onSelectPictureFormat = { format, includeAttribution ->
        showExportFormatSheet = false
        viewModel.exportCurrentNote(format, OutputTarget.MediaStorePictures, includeAttribution)
      },
      onChangeExportDirectory = {
        pendingExportFormat = null
        pendingExportAttribution = false
        isChangingExportDirectory = true
        showExportFormatSheet = false
        exportDirectoryPicker.launch(viewModel.savedExportDirectory()?.let(Uri::parse))
      },
      onDismiss = { showExportFormatSheet = false },
    )
  }

  if (state.isExporting) {
    Dialog(
      onDismissRequest = {},
      properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.testTag("editor-export-progress"),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
          verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
          Text("正在保存并导出…", style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }

  if (showSkinPicker && state.isLoaded) {
    SkinPickerSheet(
      selectedSkinId = state.skinId,
      title = state.titleForDisplay,
      body = state.body,
      onSelectSkin = viewModel::onSkinChanged,
      onDismiss = { showSkinPicker = false },
    )
  }

  if (showOcrImportModeSheet && state.isLoaded) {
    OcrImportModeSheet(
      skin = skin,
      onSelect = { mode ->
        showOcrImportModeSheet = false
        pendingOcrImportMode = mode
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      onDismiss = { showOcrImportModeSheet = false },
    )
  }

  if (showTypographySheet && state.isLoaded) {
    TextSettingsSheet(
      settings = displaySettings,
      fontSizeSpOverride = state.fontSizeSpOverride,
      lineSpacingMultiplierOverride = state.lineSpacingMultiplierOverride,
      onGlobalSettingsChange = onDisplaySettingsChange,
      onNoteOverridesChange = viewModel::onTypographyOverridesChanged,
      onDismiss = { showTypographySheet = false },
    )
  }

  adjustingImageBlockId?.let { imageBlockId ->
    state.blocks.firstOrNull { it.id == imageBlockId }?.let { block ->
      ImageAdjustmentSheet(
        widthPercent = block.widthPercent,
        onWidthPercentChange = { viewModel.onImageWidthChanged(block.id, it) },
        onDeleteClick = {
          adjustingImageBlockId = null
          pendingImageDeletionIds = setOf(block.id)
        },
        onDismiss = { adjustingImageBlockId = null },
      )
    }
  }

  pendingImageDeletionIds?.let { imageIds ->
    AlertDialog(
      modifier = Modifier.testTag("delete-images-confirmation"),
      onDismissRequest = { pendingImageDeletionIds = null },
      title = { Text(if (imageIds.size == 1) "删除这张图片？" else "删除所选图片？") },
      text = { Text("将从这条笔记中移除 ${imageIds.size} 张图片。") },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteImageBlocks(imageIds)
            selectedImageBlockIds = emptySet()
            pendingImageDeletionIds = null
          },
          modifier = Modifier.testTag("confirm-delete-images"),
        ) { Text("删除") }
      },
      dismissButton = {
        TextButton(onClick = { pendingImageDeletionIds = null }, modifier = Modifier.testTag("cancel-delete-images")) {
          Text("取消")
        }
      },
    )
  }

  state.pendingRecovery?.let { draft ->
    val hasConflict = state.recoveryConflictRevision != null
    AlertDialog(
      modifier = Modifier.testTag("draft-recovery-dialog"),
      onDismissRequest = { viewModel.saveAndExit(onBack) },
      title = { Text(if (hasConflict) "笔记版本已变化" else "恢复未保存的编辑？") },
      text = {
        Text(
          if (hasConflict) {
            "检测到较新的笔记版本。将草稿另存为新笔记可避免覆盖现有内容。"
          } else {
            "发现一份未完成的草稿，保存于 ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(draft.savedAtMillis))}。"
          },
        )
      },
      confirmButton = {
        TextButton(
          onClick = if (hasConflict) viewModel::restorePendingDraftAsNewNote else viewModel::restorePendingDraft,
          modifier = Modifier.testTag("draft-recovery-restore"),
        ) {
          Text(if (hasConflict) "另存为新笔记" else "恢复草稿")
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::discardPendingDraft, modifier = Modifier.testTag("draft-recovery-discard")) {
          Text("丢弃草稿")
        }
      },
    )
  }

  if (state.saveConflictRevision != null) {
    AlertDialog(
      modifier = Modifier.testTag("editor-save-conflict-dialog"),
      onDismissRequest = { viewModel.saveAndExit(onBack) },
      title = { Text("笔记已在其他位置更新") },
      text = { Text("当前内容已作为草稿保留。可以另存为新笔记，避免覆盖已保存版本。") },
      confirmButton = {
        TextButton(onClick = viewModel::saveConflictAsNewNote, modifier = Modifier.testTag("editor-conflict-save-copy")) {
          Text("另存为新笔记")
        }
      },
      dismissButton = {
        TextButton(onClick = { viewModel.saveAndExit(onBack) }, modifier = Modifier.testTag("editor-conflict-keep-draft")) {
          Text("保留草稿并返回")
        }
      },
    )
  }
}

@Composable
private fun EditorTopBar(
  saveStatus: EditorSaveStatus,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onExport: () -> Unit,
  exportEnabled: Boolean,
  onOpenSkinPicker: () -> Unit,
  skin: NoteSkin,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    TextButton(
      onClick = onBack,
      contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp),
      modifier = Modifier.testTag("editor-back"),
    ) {
      Text("‹ 返回", style = MaterialTheme.typography.bodyLarge)
    }

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      TextButton(
        onClick = onExport,
        enabled = exportEnabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.testTag("editor-export-button"),
      ) {
        Text("导出", style = MaterialTheme.typography.labelLarge, color = skin.accent)
      }
      TextButton(
        onClick = onOpenSkinPicker,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.testTag("editor-skin-button"),
      ) {
        Text("皮肤", style = MaterialTheme.typography.labelLarge, color = skin.accent)
      }
      VerticalDivider(
        modifier = Modifier.padding(vertical = 9.dp).height(18.dp),
        color = skin.divider,
      )
      TextButton(
        onClick = onRetry,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.testTag("editor-save-status"),
      ) {
        Text(
          text = saveStatusLabel(saveStatus),
          color = if (saveStatus == EditorSaveStatus.FAILED) skin.foreground else skin.secondaryText,
          style = MaterialTheme.typography.labelLarge,
        )
      }
    }
  }
}

private fun saveStatusLabel(status: EditorSaveStatus): String = when (status) {
  EditorSaveStatus.CLEAN -> "未修改"
  EditorSaveStatus.DIRTY -> "待保存"
  EditorSaveStatus.SAVING -> "保存中"
  EditorSaveStatus.SAVED -> "已保存"
  EditorSaveStatus.FAILED -> "保存失败 · 重试"
  EditorSaveStatus.CONFLICT -> "版本冲突"
}

@Composable
private fun ImageSelectionHeader(
  selectedCount: Int,
  onSelectAll: () -> Unit,
  onCancel: () -> Unit,
  skin: NoteSkin,
) {
  Row(
    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("editor-image-selection-header"),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    Text("已选 $selectedCount 张图片", style = MaterialTheme.typography.titleMedium, color = skin.foreground, modifier = Modifier.weight(1f))
    TextButton(onClick = onSelectAll, modifier = Modifier.testTag("editor-select-all-images")) {
      Text("全选", color = skin.foreground)
    }
    TextButton(onClick = onCancel, modifier = Modifier.testTag("editor-cancel-image-selection")) {
      Text("取消", color = skin.foreground)
    }
  }
}

@Composable
private fun ImageSelectionActionBar(
  selectedCount: Int,
  skin: NoteSkin,
  onDelete: () -> Unit,
) {
  Surface(color = skin.surface, shadowElevation = 0.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 4.dp)
        .testTag("editor-image-selection-actions"),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      Text("已选 $selectedCount 张", color = skin.secondaryText, modifier = Modifier.weight(1f))
      TextButton(onClick = onDelete, modifier = Modifier.testTag("editor-delete-selected-images")) {
        Text("删除", color = skin.foreground)
      }
    }
  }
}

@Composable
private fun EditorWritingArea(
  title: TextFieldValue,
  blocks: List<com.anan.anannotes.core.model.NoteBlockDraft>,
  activeTextBlockId: String?,
  completedTodoBlockIds: Set<String>,
  ocrProgressByBlock: Map<String, EditorOcrProgress>,
  focusTodoBlockId: String?,
  selectionStart: Int,
  selectionEnd: Int,
  composing: TextSelection?,
  todoSelections: Map<String, TextSelection>,
  todoCompositions: Map<String, TextSelection>,
  wordCount: Int,
  onTitleValueChanged: (TextFieldValue) -> Unit,
  onTextValueChanged: (String, String, TextSelection, TextSelection?) -> Unit,
  onTodoValueChanged: (String, TextFieldValue) -> Unit,
  onTodoCompletionChanged: (String, Boolean) -> Unit,
  onTodoFocusHandled: (String) -> Unit,
  onTodoFocusChanged: (String, Boolean) -> Unit,
  onInsertTodo: () -> Unit,
  onOcrCancel: (String) -> Unit,
  onOcrRetry: (String) -> Unit,
  onResetUnsupportedFormatting: (String) -> Unit,
  filesDirectory: java.io.File,
  cacheDirectory: java.io.File,
  selectedImageBlockIds: Set<String>,
  onImageClick: (com.anan.anannotes.core.model.NoteBlockDraft) -> Unit,
  onToggleImageSelection: (String) -> Unit,
  fontSizeSp: Float,
  lineSpacingMultiplier: Float,
  skin: NoteSkin,
) {
  val selectionColors = TextSelectionColors(
    handleColor = skin.accent,
    backgroundColor = skin.accent.copy(alpha = 0.18f),
  )

  androidx.compose.runtime.CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
    Column(
      modifier = Modifier.fillMaxSize().testTag("editor-content-area"),
    ) {
      BasicTextField(
        value = title,
        onValueChange = onTitleValueChanged,
        modifier = Modifier.fillMaxWidth().testTag("editor-title"),
        textStyle = MaterialTheme.typography.headlineMedium.copy(
          fontSize = 28.sp,
          lineHeight = 36.sp,
          fontWeight = FontWeight.Medium,
          color = skin.foreground,
        ),
        singleLine = true,
        cursorBrush = SolidColor(skin.accent),
        decorationBox = { innerTextField ->
          Box(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            if (title.text.isBlank()) {
              Text(
                text = "标题",
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp),
                color = skin.secondaryText.copy(alpha = 0.72f),
              )
            }
            innerTextField()
          }
        },
      )

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp).testTag("editor-metadata"),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
      ) {
        Text(
          text = "$wordCount 字",
          style = MaterialTheme.typography.bodySmall,
          color = skin.secondaryText,
        )
      }

      androidx.compose.material3.HorizontalDivider(
        color = skin.divider,
        thickness = 1.dp,
      )

      Column(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        blocks.forEachIndexed { index, block ->
          when (block.type) {
            BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT -> {
              if (block.type == BlockType.OCR_TEXT && block.ocrStatus != com.anan.anannotes.core.model.OcrStatus.SUCCEEDED) {
                OcrBlockStatusCard(
                  block = block,
                  progress = ocrProgressByBlock[block.id],
                  skin = skin,
                  onCancel = { onOcrCancel(block.id) },
                  onRetry = { onOcrRetry(block.id) },
                )
              } else {
              val isImageTailText = block.type == BlockType.TEXT && index == blocks.lastIndex &&
                index > 0 && blocks[index - 1].type == BlockType.IMAGE
              val text = block.text.orEmpty()
              val selection = if (block.id == activeTextBlockId) {
                TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
              } else TextRange(text.length)
              val composition = if (block.id == activeTextBlockId) {
                composing?.let { TextRange(it.startUtf16, it.endUtf16) }
              } else null
              val textFieldValue = TextFieldValue(text, selection, composition)
              val textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = fontSizeSp.sp,
                color = skin.foreground,
                lineHeight = (fontSizeSp * lineSpacingMultiplier).sp,
              )
              val field: @Composable () -> Unit = {
                FormattedTextBlockField(
                  value = textFieldValue,
                  formatting = block.formatting,
                  textStyle = textStyle,
                  cursorBrush = SolidColor(skin.accent),
                  skin = skin,
                  modifier = Modifier.fillMaxWidth().heightIn(min = if (index == 0 || isImageTailText) 150.dp else 48.dp)
                    .testTag(
                      when {
                        index == 0 -> "editor-body"
                        isImageTailText -> "editor-body-after-image"
                        else -> "editor-body-${block.id}"
                      },
                    ),
                  placeholder = if (index == 0) "写下此刻…" else null,
                  onValueChange = { value ->
                    onTextValueChanged(
                      block.id,
                      value.text,
                      TextSelection(value.selection.min, value.selection.max),
                      value.composition?.let { TextSelection(it.min, it.max) },
                    )
                  },
                  onResetUnsupportedFormatting = { onResetUnsupportedFormatting(block.id) },
                )
              }
              if (block.type == BlockType.QUOTE) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  Box(
                    modifier = Modifier.width(3.dp).heightIn(min = 32.dp)
                      .background(skin.divider, RoundedCornerShape(2.dp)),
                  )
                  field()
                }
              } else {
                field()
              }
              }
            }
            com.anan.anannotes.core.model.BlockType.IMAGE -> ImageBlock(
              block = block,
              filesDirectory = filesDirectory,
              cacheDirectory = cacheDirectory,
              skin = skin,
              selected = block.id in selectedImageBlockIds,
              selectionMode = selectedImageBlockIds.isNotEmpty(),
              onClick = { onImageClick(block) },
              onToggleSelection = { onToggleImageSelection(block.id) },
            )
            BlockType.TODO -> EditorTodoBlock(
              blockId = block.id,
              text = block.text.orEmpty(),
              selection = todoSelections[block.id] ?: TextSelection(block.text.orEmpty().length, block.text.orEmpty().length),
              composing = todoCompositions[block.id],
              completed = block.id in completedTodoBlockIds,
              requestFocus = block.id == focusTodoBlockId,
              fontSizeSp = fontSizeSp,
              lineSpacingMultiplier = lineSpacingMultiplier,
              skin = skin,
              onValueChanged = { onTodoValueChanged(block.id, it) },
              onCompletionChanged = { onTodoCompletionChanged(block.id, it) },
              onFocusHandled = { onTodoFocusHandled(block.id) },
              onFocusChanged = { onTodoFocusChanged(block.id, it) },
              onInsertAfter = onInsertTodo,
            )
            else -> Unit
          }
        }
      }
    }
  }
}

@Composable
private fun OcrBlockStatusCard(
  block: com.anan.anannotes.core.model.NoteBlockDraft,
  progress: EditorOcrProgress?,
  skin: NoteSkin,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
) {
  val status = block.ocrStatus
  val percent = progress?.percent?.coerceIn(0, 100) ?: if (status == com.anan.anannotes.core.model.OcrStatus.RUNNING) 10 else 0
  val statusText = when (status) {
    com.anan.anannotes.core.model.OcrStatus.PENDING -> "等待识别"
    com.anan.anannotes.core.model.OcrStatus.RUNNING -> progress?.stage?.ifBlank { null } ?: "正在准备离线识别"
    com.anan.anannotes.core.model.OcrStatus.FAILED -> "识别失败"
    com.anan.anannotes.core.model.OcrStatus.CANCELLED -> "已取消"
    else -> "识别状态未知"
  }
  Surface(
    modifier = Modifier.fillMaxWidth().testTag("ocr-status-${block.id}"),
    shape = RoundedCornerShape(12.dp),
    color = skin.accent.copy(alpha = 0.09f),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
      Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(statusText, color = skin.foreground, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (status == com.anan.anannotes.core.model.OcrStatus.PENDING || status == com.anan.anannotes.core.model.OcrStatus.RUNNING) {
          TextButton(onClick = onCancel, modifier = Modifier.testTag("ocr-cancel-${block.id}")) { Text("取消", color = skin.secondaryText) }
        } else {
          TextButton(onClick = onRetry, modifier = Modifier.testTag("ocr-retry-${block.id}")) { Text("重新识别", color = skin.accent) }
        }
      }
      LinearProgressIndicator(
        progress = { percent / 100f },
        modifier = Modifier.fillMaxWidth().height(3.dp),
        color = skin.accent,
        trackColor = skin.divider,
      )
      Text(
        text = "阶段进度 $percent%${progress?.stage?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
        color = skin.secondaryText,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 5.dp),
      )
    }
  }
}

@Composable
private fun EditorTodoBlock(
  blockId: String,
  text: String,
  selection: TextSelection,
  composing: TextSelection?,
  completed: Boolean,
  requestFocus: Boolean,
  fontSizeSp: Float,
  lineSpacingMultiplier: Float,
  skin: NoteSkin,
  onValueChanged: (TextFieldValue) -> Unit,
  onCompletionChanged: (Boolean) -> Unit,
  onFocusHandled: () -> Unit,
  onFocusChanged: (Boolean) -> Unit,
  onInsertAfter: () -> Unit,
) {
  val focusRequester = androidx.compose.runtime.remember(blockId) { FocusRequester() }
  val bringIntoViewRequester = remember(blockId) { BringIntoViewRequester() }
  LaunchedEffect(requestFocus) {
    if (requestFocus) {
      focusRequester.requestFocus()
      bringIntoViewRequester.bringIntoView()
      onFocusHandled()
    }
  }

  Surface(
    modifier = Modifier.fillMaxWidth()
      .bringIntoViewRequester(bringIntoViewRequester)
      .testTag("editor-todo-block-$blockId"),
    shape = RoundedCornerShape(12.dp),
    color = skin.accent.copy(alpha = 0.08f),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = androidx.compose.ui.Alignment.Top,
    ) {
    Checkbox(
      checked = completed && text.isNotBlank(),
      onCheckedChange = onCompletionChanged,
      enabled = text.isNotBlank(),
      colors = CheckboxDefaults.colors(
        checkedColor = skin.accent,
        uncheckedColor = skin.secondaryText,
        checkmarkColor = skin.background,
        disabledUncheckedColor = skin.secondaryText,
      ),
      modifier = Modifier.testTag("editor-todo-checkbox"),
    )
    BasicTextField(
      value = TextFieldValue(
        text = text,
        selection = TextRange(
          selection.startUtf16.coerceIn(0, text.length),
          selection.endUtf16.coerceIn(0, text.length),
        ),
        composition = composing?.toTextRange(text),
      ),
      onValueChange = onValueChanged,
      modifier = Modifier.weight(1f).heightIn(min = 44.dp).focusRequester(focusRequester)
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .testTag("editor-todo-text"),
      singleLine = true,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
      keyboardActions = KeyboardActions(onNext = { onInsertAfter() }),
      textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSizeSp.sp,
        color = if (completed) skin.secondaryText.copy(alpha = 0.72f) else skin.foreground,
        lineHeight = (fontSizeSp * lineSpacingMultiplier).sp,
        textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None,
      ),
      cursorBrush = SolidColor(skin.accent),
      decorationBox = { innerTextField ->
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
          if (text.isBlank()) {
            Text(
              text = "待办内容",
              style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSizeSp.sp),
              color = skin.secondaryText.copy(alpha = 0.72f),
            )
          }
          innerTextField()
        }
      },
    )
    }
  }
}

@Composable
private fun EditorToolbar(
  showMoreToolbar: Boolean,
  onMoreClick: () -> Unit,
  onBoldClick: () -> Unit,
  onHeadingClick: () -> Unit,
  onItalicClick: () -> Unit,
  onStrikethroughClick: () -> Unit,
  onQuoteClick: () -> Unit,
  inlineToolStates: Map<InlineStyle, EditorToolState>,
  headingToolState: EditorToolState,
  quoteToolState: EditorToolState,
  onTextSettingsClick: () -> Unit,
  onImageClick: () -> Unit,
  onInsertTodo: () -> Unit,
  onCopyClick: () -> Unit,
  skin: NoteSkin,
) {
  Box(modifier = Modifier.fillMaxWidth().imePadding()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
        .background(skin.surface)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 14.dp, vertical = 6.dp)
        .testTag("editor-basic-toolbar"),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      EditorTool("B", "editor-bold", skin, onBoldClick, inlineToolStates[InlineStyle.BOLD] ?: EditorToolState.OFF)
      EditorTool("H", "editor-heading", skin, onHeadingClick, headingToolState)
      EditorTool("待办", "editor-insert-todo", skin, onInsertTodo)
      TextButton(onClick = onTextSettingsClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.testTag("editor-text-settings")) {
        Text("Aa", color = skin.foreground)
      }
      TextButton(onClick = onImageClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.testTag("editor-insert-image")) {
        Text("图片", color = skin.foreground)
      }
      TextButton(
        onClick = onMoreClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Text(if (showMoreToolbar) "收起" else "更多", color = skin.foreground)
      }
      if (showMoreToolbar) {
        EditorTool("斜体", "editor-italic", skin, onItalicClick, inlineToolStates[InlineStyle.ITALIC] ?: EditorToolState.OFF)
        EditorTool("删除线", "editor-strikethrough", skin, onStrikethroughClick, inlineToolStates[InlineStyle.STRIKETHROUGH] ?: EditorToolState.OFF)
        EditorTool("引用", "editor-quote", skin, onQuoteClick, quoteToolState)
        EditorTool("分割线", "editor-insert-divider", skin, {})
        TextButton(
          onClick = onCopyClick,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
          modifier = Modifier.testTag("editor-copy-full-text"),
        ) {
          Text("复制全文", color = skin.foreground)
        }
      }
    }
  }
}

private fun TextSelection.toTextRange(text: String): TextRange = TextRange(
  start = startUtf16.coerceIn(0, text.length),
  end = endUtf16.coerceIn(0, text.length),
)

private enum class EditorToolState { OFF, ON, MIXED }

private fun EditorUiState.inlineToolState(style: InlineStyle): EditorToolState {
  val block = blocks.firstOrNull { it.id == activeTextBlockId }
    ?.takeIf { it.type in setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT) }
    ?: return EditorToolState.OFF
  val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
    ?: return EditorToolState.OFF
  val text = block.text.orEmpty()
  val selection = try {
    BlockFormattingRules.normalizeSelection(text, TextSelection(selectionStart, selectionEnd))
  } catch (_: IllegalArgumentException) {
    return EditorToolState.OFF
  }
  if (selection.startUtf16 == selection.endUtf16) {
    val effectiveState = typingOverrides[style] ?: runCatching {
      BlockFormattingRules.isStyleActiveAtCaret(text, formatting, style, selection.startUtf16, block.type)
    }.getOrDefault(false)
    return if (effectiveState) EditorToolState.ON else EditorToolState.OFF
  }
  val ranges = formatting.inlineStyles.filter { it.style == style }
  return when {
    ranges.any { it.startUtf16 <= selection.startUtf16 && it.endUtf16 >= selection.endUtf16 } -> EditorToolState.ON
    ranges.any { it.startUtf16 < selection.endUtf16 && selection.startUtf16 < it.endUtf16 } -> EditorToolState.MIXED
    else -> EditorToolState.OFF
  }
}

@Composable
private fun EditorTool(
  label: String,
  tag: String,
  skin: NoteSkin,
  onClick: () -> Unit,
  toolState: EditorToolState = EditorToolState.OFF,
) {
  TextButton(
    onClick = onClick,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    modifier = Modifier.testTag(tag).semantics {
      selected = toolState == EditorToolState.ON
      stateDescription = when (toolState) {
        EditorToolState.OFF -> "未启用"
        EditorToolState.ON -> "已启用"
        EditorToolState.MIXED -> "部分应用"
      }
    },
  ) {
    Text(
      label,
      color = when (toolState) {
        EditorToolState.ON -> skin.accent
        EditorToolState.MIXED -> skin.accent.copy(alpha = 0.72f)
        EditorToolState.OFF -> skin.foreground
      },
    )
  }
}

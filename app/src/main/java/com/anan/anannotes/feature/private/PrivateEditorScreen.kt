package com.anan.anannotes.feature.privatemodule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anan.anannotes.ui.theme.AnAnInputFieldShape
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.DEFAULT_NOTE_SKIN_ID
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.TextSelection
import com.anan.anannotes.feature.editor.FormattedTextBlockField
import com.anan.anannotes.feature.editor.SkinPickerSheet
import com.anan.anannotes.feature.export.ExportLayout
import com.anan.anannotes.feature.ocr.OcrImportMode
import com.anan.anannotes.feature.ocr.OcrImportModeSheet
import com.anan.anannotes.ui.theme.NoteSkinCatalog
import com.anan.anannotes.ui.theme.noteSkinBackground
import java.util.UUID

@Composable
fun PrivateEditorScreen(
  note: PrivateNoteDocument?,
  isSaving: Boolean,
  errorMessage: String?,
  onBack: () -> Unit,
  onSave: (title: String, blocks: List<NoteBlockDraft>, todos: List<PrivateTodoDraft>) -> Unit,
  onDraftChange: (title: String, blocks: List<NoteBlockDraft>, todos: List<PrivateTodoDraft>) -> Unit = { _, _, _ -> },
  onSkinChange: (skinId: String) -> Unit = {},
  onExport: () -> Unit = {},
  onCopyText: (String) -> Unit = {},
  pendingRecoveryDraft: PrivateEditSnapshot? = null,
  onRecoveryRestored: () -> Unit = {},
  onRecoveryDiscard: (PrivateEditSnapshot) -> Unit = {},
  privateOcrProgress: Map<String, PrivateOcrProgress> = emptyMap(),
  privateOcrResults: Map<String, NoteBlockDraft> = emptyMap(),
  onImportImage: (
    Uri,
    OcrImportMode,
    List<NoteBlockDraft>,
    String?,
    Int,
    Int,
    (List<NoteBlockDraft>, String?) -> Unit,
  ) -> Unit = { _, _, _, _, _, _, _ -> },
  onCancelOcr: (String) -> Unit = {},
  onRetryOcr: (String) -> Unit = {},
) {
  var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
  var blocks by remember(note?.id) {
    mutableStateOf(note?.blocks ?: listOf(NoteBlockDraft(type = BlockType.TEXT, text = "")))
  }
  var todos by remember(note?.id) {
    mutableStateOf(note?.blocks?.filter { it.type == BlockType.TODO }
      ?.map { PrivateTodoDraft(blockId = it.id, text = it.text.orEmpty()) }.orEmpty())
  }
  var activeTextBlockId by remember(note?.id) {
    mutableStateOf(blocks.firstOrNull { it.type in FORMATTABLE_TYPES }?.id)
  }
  var selectionStart by remember(note?.id) { mutableStateOf(0) }
  var selectionEnd by remember(note?.id) { mutableStateOf(0) }
  var composing by remember(note?.id) { mutableStateOf<TextSelection?>(null) }
  var typingOverrides by remember(note?.id) { mutableStateOf<Map<InlineStyle, Boolean>>(emptyMap()) }
  var showMoreTools by remember(note?.id) { mutableStateOf(false) }
  var showPrivateCopyWarning by remember(note?.id) { mutableStateOf(false) }
  var showSkinPicker by remember(note?.id) { mutableStateOf(false) }
  var showImageImportModeSheet by rememberSaveable(note?.id) { mutableStateOf(false) }
  var selectedImportMode by rememberSaveable(note?.id) { mutableStateOf(OcrImportMode.IMAGE_ONLY) }
  var selectedSkinId by remember(note?.id) {
    mutableStateOf(pendingRecoveryDraft?.skinId ?: note?.skinId ?: DEFAULT_NOTE_SKIN_ID)
  }
  val skin = NoteSkinCatalog.byId(selectedSkinId)
  val activeBlock = blocks.firstOrNull { it.id == activeTextBlockId }
  val completedTodoBlockIds = note?.todos.orEmpty().asSequence()
    .filter { it.completed }
    .mapTo(hashSetOf()) { it.blockId }
  val canExportSavedNote = PrivateEditorRules.canExportSavedNote(note, title, blocks, todos) && !isSaving

  val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      onImportImage(uri, selectedImportMode, blocks, activeTextBlockId, selectionStart, selectionEnd) { updated, focusId ->
        blocks = updated
        activeTextBlockId = focusId
        selectionStart = 0
        selectionEnd = 0
        composing = null
        onDraftChange(title, updated, todos)
      }
    }
    selectedImportMode = OcrImportMode.IMAGE_ONLY
  }

  LaunchedEffect(privateOcrResults, note?.id) {
    privateOcrResults.forEach { (blockId, result) ->
      val local = blocks.firstOrNull { it.id == blockId } ?: return@forEach
      if (local.text != result.text || local.ocrStatus != result.ocrStatus || local.assetPath != result.assetPath) {
        val updated = blocks.map { if (it.id == blockId) result else it }
        blocks = updated
        onDraftChange(title, updated, todos)
      }
    }
  }

  fun updateBlocks(next: List<NoteBlockDraft>) {
    blocks = next
  }

  fun onTextValueChanged(blockId: String, value: TextFieldValue) {
    val index = blocks.indexOfFirst { it.id == blockId }
    val block = blocks.getOrNull(index) ?: return
    if (block.type !in FORMATTABLE_TYPES) return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value ?: return
    val oldText = block.text.orEmpty()
    val wasComposing = composing != null
    val oldSelection = if (activeTextBlockId == blockId) TextSelection(selectionStart, selectionEnd)
    else TextSelection(oldText.length, oldText.length)
    val result = BlockFormattingRules.replaceText(
      oldText = oldText,
      newText = value.text,
      oldSelection = oldSelection,
      selection = TextSelection(value.selection.min, value.selection.max),
      composing = value.composition?.let { TextSelection(it.min, it.max) },
      formatting = formatting,
      typingOverrides = typingOverrides,
      type = block.type,
    )
    if (index >= 0) blocks = blocks.map { current ->
      if (current.id == blockId) current.copy(text = result.text, formatting = BlockFormattingState.Supported(result.formatting))
      else current
    }
    activeTextBlockId = blockId
    selectionStart = result.selection.startUtf16
    selectionEnd = result.selection.endUtf16
    composing = result.composing
    if (result.composing == null &&
      (oldText != result.text || formatting != result.formatting || wasComposing)
    ) onDraftChange(title, blocks, todos)
  }

  fun applyInlineStyle(style: InlineStyle) {
    val block = blocks.firstOrNull { it.id == activeTextBlockId }
      ?.takeIf { it.type in FORMATTABLE_TYPES } ?: return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value ?: return
    val text = block.text.orEmpty()
    val selection = runCatching {
      BlockFormattingRules.normalizeSelection(text, TextSelection(selectionStart, selectionEnd))
    }.getOrElse { return }
    if (selection.startUtf16 == selection.endUtf16) {
      val inherited = BlockFormattingRules.isStyleActiveAtCaret(text, formatting, style, selection.startUtf16, block.type)
      typingOverrides = typingOverrides + (style to !(typingOverrides[style] ?: inherited))
      return
    }
    val enabled = !formatting.inlineStyles.any {
      it.style == style && it.startUtf16 <= selection.startUtf16 && it.endUtf16 >= selection.endUtf16
    }
    val updated = BlockFormattingRules.setInlineStyle(text, formatting, selection, style, enabled, block.type)
    updateBlocks(blocks.map { if (it.id == block.id) it.copy(formatting = BlockFormattingState.Supported(updated)) else it })
    onDraftChange(title, blocks, todos)
  }

  fun toggleHeading() {
    val block = activeBlock?.takeIf { it.type == BlockType.TEXT } ?: return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value ?: return
    val updated = formatting.copy(
      presentation = if (formatting.presentation == TextBlockPresentation.HEADING) TextBlockPresentation.BODY
      else TextBlockPresentation.HEADING,
    )
    updateBlocks(blocks.map { if (it.id == block.id) it.copy(formatting = BlockFormattingState.Supported(updated)) else it })
    onDraftChange(title, blocks, todos)
  }

  fun toggleQuote() {
    val block = activeBlock?.takeIf { it.type == BlockType.TEXT || it.type == BlockType.QUOTE } ?: return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value ?: return
    val nextType = if (block.type == BlockType.TEXT) BlockType.QUOTE else BlockType.TEXT
    updateBlocks(blocks.map {
      if (it.id == block.id) it.copy(type = nextType, formatting = BlockFormattingState.Supported(formatting.copy(presentation = TextBlockPresentation.BODY)))
      else it
    })
    onDraftChange(title, blocks, todos)
  }

  fun resetUnsupportedFormatting(blockId: String) {
    val block = blocks.firstOrNull { it.id == blockId } ?: return
    if (block.formatting !is BlockFormattingState.Unsupported) return
    blocks = blocks.map {
      if (it.id == blockId) it.copy(formatting = BlockFormattingState.Supported(BlockFormatting())) else it
    }
    activeTextBlockId = blockId
    selectionStart = block.text.orEmpty().length
    selectionEnd = selectionStart
    composing = null
    onDraftChange(title, blocks, todos)
  }

  fun inlineToolState(style: InlineStyle): PrivateToolState {
    val block = blocks.firstOrNull { it.id == activeTextBlockId }
      ?.takeIf { it.type in FORMATTABLE_TYPES } ?: return PrivateToolState.OFF
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value ?: return PrivateToolState.OFF
    val text = block.text.orEmpty()
    val selection = runCatching {
      BlockFormattingRules.normalizeSelection(text, TextSelection(selectionStart, selectionEnd))
    }.getOrElse { return PrivateToolState.OFF }
    if (selection.startUtf16 == selection.endUtf16) {
      val active = typingOverrides[style] ?: runCatching {
        BlockFormattingRules.isStyleActiveAtCaret(text, formatting, style, selection.startUtf16, block.type)
      }.getOrDefault(false)
      return if (active) PrivateToolState.ON else PrivateToolState.OFF
    }
    val ranges = formatting.inlineStyles.filter { it.style == style }
    return when {
      ranges.any { it.startUtf16 <= selection.startUtf16 && it.endUtf16 >= selection.endUtf16 } -> PrivateToolState.ON
      ranges.any { it.startUtf16 < selection.endUtf16 && selection.startUtf16 < it.endUtf16 } -> PrivateToolState.MIXED
      else -> PrivateToolState.OFF
    }
  }

  BackHandler(enabled = true, onBack = onBack)

  Column(
    modifier = Modifier.fillMaxSize().imePadding().noteSkinBackground(skin).testTag("private-editor-screen"),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().background(skin.surface).padding(horizontal = 8.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      TextButton(onClick = onBack, modifier = Modifier.testTag("private-editor-back")) { Text("返回") }
      Text(
        text = if (note == null) "新建私密笔记" else "编辑私密笔记",
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.weight(1f).padding(top = 12.dp),
        color = skin.foreground,
      )
      TextButton(
        onClick = { showSkinPicker = true },
        modifier = Modifier.testTag("private-editor-skin-button"),
      ) { Text("皮肤", color = skin.accent) }
      TextButton(
        onClick = { onSave(title, blocks, todos) },
        enabled = !isSaving,
        modifier = Modifier.testTag("private-editor-save"),
      ) { Text(if (isSaving) "保存中" else "保存", color = skin.accent) }
    }

    Column(
      modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
      OutlinedTextField(
        value = title,
        onValueChange = {
          title = it
          onDraftChange(title, blocks, todos)
        },
        modifier = Modifier.fillMaxWidth().testTag("private-title-field"),
        label = { Text("标题") },
        singleLine = true,
        shape = AnAnInputFieldShape,
      )
      Spacer(Modifier.height(14.dp))

      blocks.forEachIndexed { index, block ->
        when (block.type) {
          BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT -> {
            if (block.type == BlockType.OCR_TEXT && block.ocrStatus != OcrStatus.SUCCEEDED) {
              PrivateOcrStatusCard(
                block = block,
                progress = privateOcrProgress[block.id],
                skin = skin,
                onCancel = { onCancelOcr(block.id) },
                onRetry = { onRetryOcr(block.id) },
              )
              Spacer(Modifier.height(8.dp))
              return@forEachIndexed
            }
            val text = block.text.orEmpty()
            val selection = if (block.id == activeTextBlockId) {
              TextRange(selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length))
            } else TextRange(text.length)
            val composition = if (block.id == activeTextBlockId) composing?.let { TextRange(it.startUtf16, it.endUtf16) } else null
            Row(
              modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(skin.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              if (block.type == BlockType.QUOTE) {
                Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(skin.divider))
              }
              FormattedTextBlockField(
                value = TextFieldValue(text, selection, composition),
                formatting = block.formatting,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                  fontSize = 17.sp,
                  lineHeight = 27.sp,
                  color = skin.foreground,
                ),
                cursorBrush = SolidColor(skin.accent),
                skin = skin,
                modifier = Modifier.fillMaxWidth().testTag(
                  if (block.id == blocks.firstOrNull { it.type in FORMATTABLE_TYPES }?.id) "private-body-field"
                  else "private-body-${block.id}",
                ),
                placeholder = if (index == 0) "写下只属于你的内容…" else null,
                onValueChange = { onTextValueChanged(block.id, it) },
                onResetUnsupportedFormatting = { resetUnsupportedFormatting(block.id) },
              )
            }
            Spacer(Modifier.height(10.dp))
          }
          BlockType.IMAGE -> {
            Text(
              text = "已加密图片内容块",
              color = skin.secondaryText,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.fillMaxWidth().background(skin.surface, RoundedCornerShape(12.dp))
                .padding(14.dp).testTag("private-image-block-${block.id}"),
            )
            Spacer(Modifier.height(8.dp))
          }
          BlockType.TODO -> {
            val todo = todos.firstOrNull { it.blockId == block.id } ?: return@forEachIndexed
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
              OutlinedTextField(
                value = todo.text,
                onValueChange = { text ->
                  todos = todos.map { if (it.blockId == todo.blockId) it.copy(text = text) else it }
                  blocks = blocks.map { if (it.id == todo.blockId) it.copy(text = text) else it }
                  onDraftChange(title, blocks, todos)
                },
                modifier = Modifier.weight(1f).testTag("private-todo-field-${todo.blockId}"),
                label = { Text("待办") },
                singleLine = true,
                shape = AnAnInputFieldShape,
              )
              TextButton(
                onClick = {
                  todos = todos.filterNot { it.blockId == todo.blockId }
                  blocks = blocks.filterNot { it.id == todo.blockId }
                  onDraftChange(title, blocks, todos)
                },
                modifier = Modifier.testTag("private-todo-remove-${todo.blockId}"),
              ) { Text("移除") }
            }
            Spacer(Modifier.height(8.dp))
          }
          BlockType.DIVIDER -> HorizontalDivider(color = skin.divider)
        }
      }

      TextButton(
        onClick = {
          val todo = PrivateTodoDraft(text = "")
          todos = todos + todo
          blocks = blocks + NoteBlockDraft(id = todo.blockId, type = BlockType.TODO, text = "")
          onDraftChange(title, blocks, todos)
        },
        modifier = Modifier.testTag("private-todo-add"),
      ) { Text("添加待办", color = skin.accent) }

      errorMessage?.let { message ->
        Text(
          text = message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 14.dp).testTag("private-editor-error"),
        )
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth().background(skin.surface)
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 4.dp)
        .testTag("private-editor-toolbar"),
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      PrivateEditorTool("B", "private-editor-bold", skin, inlineToolState(InlineStyle.BOLD)) { applyInlineStyle(InlineStyle.BOLD) }
      PrivateEditorTool(
        "H",
        "private-editor-heading",
        skin,
        if (activeBlock?.type == BlockType.TEXT &&
          (activeBlock.formatting as? BlockFormattingState.Supported)?.value?.presentation == TextBlockPresentation.HEADING
        ) PrivateToolState.ON else PrivateToolState.OFF,
      ) { toggleHeading() }
      TextButton(onClick = { showImageImportModeSheet = true }, modifier = Modifier.testTag("private-editor-image")) {
        Text("图片", color = skin.foreground)
      }
      TextButton(onClick = { showMoreTools = !showMoreTools }, modifier = Modifier.testTag("private-editor-more")) {
        Text(if (showMoreTools) "收起" else "更多", color = skin.foreground)
      }
      if (showMoreTools) {
        PrivateEditorTool("斜体", "private-editor-italic", skin, inlineToolState(InlineStyle.ITALIC)) { applyInlineStyle(InlineStyle.ITALIC) }
        PrivateEditorTool(
          "删除线",
          "private-editor-strikethrough",
          skin,
          inlineToolState(InlineStyle.STRIKETHROUGH),
        ) { applyInlineStyle(InlineStyle.STRIKETHROUGH) }
        PrivateEditorTool(
          "引用",
          "private-editor-quote",
          skin,
          if (activeBlock?.type == BlockType.QUOTE) PrivateToolState.ON else PrivateToolState.OFF,
        ) { toggleQuote() }
        TextButton(
          onClick = onExport,
          enabled = canExportSavedNote,
          modifier = Modifier.testTag("private-editor-export").semantics {
            stateDescription = if (canExportSavedNote) "可导出已保存笔记" else "请先保存笔记修改后再导出"
          },
        ) { Text("导出", color = if (canExportSavedNote) skin.foreground else skin.secondaryText) }
        TextButton(
          onClick = { showPrivateCopyWarning = true },
          modifier = Modifier.testTag("private-editor-copy-full-text"),
        ) { Text("复制全文", color = skin.foreground) }
      }
    }
    if (showMoreTools && !canExportSavedNote) {
      Text(
        text = if (note == null) "保存笔记后可导出" else "先保存修改，再导出最新内容",
        color = skin.secondaryText,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(skin.surface).padding(horizontal = 16.dp, vertical = 4.dp)
          .testTag("private-editor-export-hint"),
      )
    }
  }

  if (showImageImportModeSheet) {
    OcrImportModeSheet(
      skin = skin,
      onSelect = { mode ->
        showImageImportModeSheet = false
        selectedImportMode = mode
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      onDismiss = { showImageImportModeSheet = false },
    )
  }

  if (showSkinPicker) {
    SkinPickerSheet(
      selectedSkinId = selectedSkinId,
      title = title,
      body = blocks.asSequence()
        .filter { it.type in FORMATTABLE_TYPES }
        .mapNotNull(NoteBlockDraft::text)
        .firstOrNull().orEmpty(),
      onSelectSkin = { skinId ->
        if (selectedSkinId != skinId) {
          selectedSkinId = skinId
          onDraftChange(title, blocks, todos)
          onSkinChange(skinId)
        }
      },
      onDismiss = { showSkinPicker = false },
    )
  }

  if (showPrivateCopyWarning) {
    AlertDialog(
      modifier = Modifier.testTag("private-copy-warning"),
      onDismissRequest = { showPrivateCopyWarning = false },
      title = { Text("复制私密笔记？") },
      text = {
        Text("笔记将复制到系统剪贴板，其他应用可能读取剪贴板内容。仅在确认安全时继续。")
      },
      confirmButton = {
        TextButton(
          onClick = {
            showPrivateCopyWarning = false
            onCopyText(
              ExportLayout.toPlainText(
                title = title,
                blocks = blocks,
                completedTodoBlockIds = completedTodoBlockIds,
              ),
            )
          },
          modifier = Modifier.testTag("private-copy-confirm"),
        ) { Text("继续复制") }
      },
      dismissButton = {
        TextButton(
          onClick = { showPrivateCopyWarning = false },
          modifier = Modifier.testTag("private-copy-cancel"),
        ) { Text("取消") }
      },
    )
  }

  pendingRecoveryDraft?.let { recovery ->
    AlertDialog(
      modifier = Modifier.testTag("private-draft-recovery-dialog"),
      onDismissRequest = {},
      title = { Text("恢复私密草稿？") },
      text = { Text("发现一份与当前笔记版本匹配的加密草稿。") },
      confirmButton = {
        TextButton(
          onClick = {
            title = recovery.title
            blocks = recovery.blocks
            todos = recovery.blocks.filter { it.type == BlockType.TODO }
              .map { PrivateTodoDraft(blockId = it.id, text = it.text.orEmpty()) }
            activeTextBlockId = recovery.blocks.firstOrNull { it.type in FORMATTABLE_TYPES }?.id
            selectionStart = activeTextBlockId?.let { id -> recovery.blocks.firstOrNull { it.id == id }?.text.orEmpty().length } ?: 0
            selectionEnd = selectionStart
            composing = null
            typingOverrides = emptyMap()
            selectedSkinId = recovery.skinId
            onDraftChange(recovery.title, recovery.blocks, todos)
            onSkinChange(recovery.skinId)
            onRecoveryRestored()
          },
          modifier = Modifier.testTag("private-draft-recovery-restore"),
        ) { Text("恢复草稿") }
      },
      dismissButton = {
        TextButton(
          onClick = { onRecoveryDiscard(recovery) },
          modifier = Modifier.testTag("private-draft-recovery-discard"),
        ) { Text("丢弃草稿") }
      },
    )
  }
}

@Composable
private fun PrivateOcrStatusCard(
  block: NoteBlockDraft,
  progress: PrivateOcrProgress?,
  skin: com.anan.anannotes.ui.theme.NoteSkin,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
) {
  val pending = block.ocrStatus == OcrStatus.PENDING || block.ocrStatus == OcrStatus.RUNNING
  val percent = progress?.percent?.coerceIn(0, 100) ?: if (block.ocrStatus == OcrStatus.RUNNING) 10 else 0
  val label = when (block.ocrStatus) {
    OcrStatus.PENDING -> "等待识别"
    OcrStatus.RUNNING -> progress?.stage?.ifBlank { null } ?: "正在准备离线识别"
    OcrStatus.FAILED -> "识别失败"
    OcrStatus.CANCELLED -> "已取消"
    else -> "识别状态未知"
  }
  Row(
    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(skin.accent.copy(alpha = 0.09f))
      .padding(horizontal = 12.dp, vertical = 8.dp).testTag("private-ocr-status-${block.id}"),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, color = skin.foreground, style = MaterialTheme.typography.bodyMedium)
      LinearProgressIndicator(
        progress = { percent / 100f },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
        color = skin.accent,
        trackColor = skin.divider,
      )
      Text(
        "阶段进度 $percent%${progress?.stage?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
        color = skin.secondaryText,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
    TextButton(
      onClick = if (pending) onCancel else onRetry,
      modifier = Modifier.testTag(if (pending) "private-ocr-cancel-${block.id}" else "private-ocr-retry-${block.id}"),
    ) { Text(if (pending) "取消" else "重试", color = skin.accent) }
  }
}

@Composable
private fun PrivateEditorTool(
  label: String,
  tag: String,
  skin: com.anan.anannotes.ui.theme.NoteSkin,
  toolState: PrivateToolState = PrivateToolState.OFF,
  onClick: () -> Unit,
) {
  TextButton(
    onClick = onClick,
    modifier = Modifier.testTag(tag).semantics {
      selected = toolState == PrivateToolState.ON
      stateDescription = when (toolState) {
        PrivateToolState.OFF -> "未启用"
        PrivateToolState.ON -> "已启用"
        PrivateToolState.MIXED -> "部分应用"
      }
    },
  ) {
    Text(
      label,
      color = when (toolState) {
        PrivateToolState.OFF -> skin.foreground
        PrivateToolState.ON -> skin.accent
        PrivateToolState.MIXED -> skin.accent.copy(alpha = 0.72f)
      },
      fontWeight = FontWeight.Medium,
    )
  }
}

private enum class PrivateToolState { OFF, ON, MIXED }

private val FORMATTABLE_TYPES = setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT)

package com.anan.anannotes.feature.editor

import android.app.Application
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.createNoteIfMeaningful
import com.anan.anannotes.core.ImageBlockRules
import com.anan.anannotes.core.TodoBlockRules
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.BlockModel
import com.anan.anannotes.core.model.BlockFormatting
import com.anan.anannotes.core.model.BlockFormattingRules
import com.anan.anannotes.core.model.BlockFormattingState
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.InlineStyle
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.NoteDocument
import com.anan.anannotes.core.model.NoteDraft
import com.anan.anannotes.core.model.TextBlockPresentation
import com.anan.anannotes.core.model.TextSelection
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.files.ImageAssetImporter
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.feature.imports.ImportContentReader
import com.anan.anannotes.feature.imports.ImportTextRules
import com.anan.anannotes.feature.ocr.OcrBlockInsertionRules
import com.anan.anannotes.feature.ocr.OcrImportMode
import com.anan.anannotes.feature.ocr.OcrJobEvent
import com.anan.anannotes.feature.ocr.OcrJobRules
import com.anan.anannotes.feature.ocr.OcrStagingStore
import com.anan.anannotes.feature.ocr.OfflineOcrWorker
import com.anan.anannotes.data.repository.DisplaySettingsRepository
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.data.repository.NoteSaveResult
import com.anan.anannotes.feature.export.ExportDirectoryPreferences
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportFeedback
import com.anan.anannotes.feature.export.ExportRequest
import com.anan.anannotes.feature.export.OrdinaryExportServiceFactory
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.feature.export.ExportFeedbackInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID

class EditorViewModel(
  application: Application,
  noteId: String?,
  folderId: String,
  private val defaultSettings: DisplaySettings = DisplaySettings(),
  private val initialImportText: String? = null,
  private val initialImportUris: List<String> = emptyList(),
  private val initialImportRequestId: String? = null,
) : AndroidViewModel(application) {
  private val database = AnAnDatabase.getInstance(application)
  private val repository = NoteRepository(
    database,
    assetStore = OriginalImageStore(application.filesDir),
  )
  private val exporter = OrdinaryExportServiceFactory.create(application)
  private val imageImporter = ImageAssetImporter(application.contentResolver, application.filesDir, application.cacheDir)
  private val importContentReader = ImportContentReader(application.contentResolver)
  private val initialTextBlock = NoteBlockDraft(type = BlockType.TEXT, text = "")
  private val draftStore: EditorDraftStore = FileEditorDraftStore(File(application.filesDir, DRAFT_DIRECTORY))
  private val _uiState = MutableStateFlow(
    EditorUiState(
      noteId = noteId,
      draftId = UUID.randomUUID().toString(),
      importRequestId = initialImportRequestId,
      folderId = folderId,
      skinId = defaultSettings.defaultSkinId,
      blocks = listOf(initialTextBlock),
      activeTextBlockId = initialTextBlock.id,
      isLoaded = false,
    ),
  )
  private val exportFeedbackInbox = ExportFeedbackInbox()
  val uiState = combine(_uiState, exportFeedbackInbox.next) { state, feedback ->
    state.copy(pendingExportFeedback = listOfNotNull(feedback))
  }.stateIn(viewModelScope, SharingStarted.Eagerly, _uiState.value)
  private val saveMutex = Mutex()
  private val todoCompletionMutex = Mutex()
  private val pendingTodoWrites = MutableStateFlow(0)
  private var saveLoop: Job? = null
  private val ocrMonitorJobs = mutableMapOf<String, Job>()

  init {
    viewModelScope.launch { initialize(noteId, folderId) }
  }

  private suspend fun initialize(noteId: String?, folderId: String) {
    var drafts: List<EditorDraftSnapshot> = emptyList()
    var draftReadError: String? = null
    try {
      drafts = draftStore.listRecoverable()
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      draftReadError = "草稿读取失败，原文件已保留"
    }

    val resolvedNoteId = noteId ?: initialImportRequestId?.let { repository.findImportedNoteId(it) }
    if (resolvedNoteId == null) {
      val persistedDefaultSkin = DisplaySettingsRepository(database.displaySettingsDao()).observe().first().defaultSkinId
      val hasIncomingImport = !initialImportText.isNullOrEmpty() || initialImportUris.isNotEmpty()
      val matchingImportDraft = initialImportRequestId?.let { requestId ->
        drafts.firstOrNull { it.importRequestId == requestId }
      }
      if (matchingImportDraft != null) {
        _uiState.value = _uiState.value.copy(
          importRequestId = initialImportRequestId,
          folderId = matchingImportDraft.folderId,
          skinId = persistedDefaultSkin,
          isLoaded = true,
          pendingRecovery = matchingImportDraft,
          recoveryConflictRevision = matchingImportDraft.noteId?.let { matchingImportDraft.baseContentRevision.coerceAtLeast(1L) },
          errorMessage = draftReadError,
        )
        return
      }
      val pending = drafts.firstOrNull { it.noteId == null && it.folderId == folderId }
        ?.takeUnless { hasIncomingImport }
      val importedTexts = mutableListOf<String>()
      initialImportText?.takeIf(String::isNotEmpty)?.let(importedTexts::add)
      val imageUris = mutableListOf<Uri>()
      var importError = draftReadError
      for (uriString in initialImportUris) {
        val uri = Uri.parse(uriString)
        try {
          val mimeType = getApplication<Application>().contentResolver.getType(uri).orEmpty()
          if (mimeType.startsWith("image/")) {
            imageUris += uri
          } else {
            val text = importContentReader.readText(uri)
            val joinedSize = (importedTexts.joinToString("\n\n") + "\n\n" + text)
              .toByteArray(Charsets.UTF_8)
              .size
            if (joinedSize > ImportTextRules.MAX_TEXT_BYTES) {
              throw IOException("本次导入的文本总量超过 5 MB 安全上限")
            }
            importedTexts += text
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          importError = error.message ?: "文件导入失败"
        }
      }
      val importedBody = importedTexts.joinToString("\n\n")
      val importedTextBlock = initialTextBlock.copy(text = importedBody)
      _uiState.value = _uiState.value.copy(
        importRequestId = initialImportRequestId,
        skinId = persistedDefaultSkin,
        body = importedBody,
        blocks = listOf(importedTextBlock),
        activeTextBlockId = importedTextBlock.id,
        selectionStart = importedBody.length,
        selectionEnd = importedBody.length,
        saveStatus = if (importedBody.isNotBlank()) EditorSaveStatus.DIRTY else EditorSaveStatus.CLEAN,
        revision = if (importedBody.isNotBlank()) 1L else 0L,
        isLoaded = true,
        pendingRecovery = pending,
        errorMessage = importError,
      )
      if (importedBody.isNotBlank()) {
        queueDraftWrite()
        scheduleSave()
      }
      imageUris.forEach { uri ->
        try {
          appendImportedImage(uri)
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "图片导入失败")
        }
      }
      return
    }

    try {
      val document = repository.observeNote(resolvedNoteId).filterNotNull().first()
      val persistedBlocks = document.blocks.map(BlockModel::toDraft)
      val blocks = ImageBlockRules.ensureEditableTextTail(persistedBlocks)
      val addedEditableTail = blocks.size > persistedBlocks.size
      val activeTextBlock = if (addedEditableTail) {
        blocks.last()
      } else {
        blocks.firstOrNull { it.type in TITLE_TEXT_BLOCK_TYPES }
      }
      val pending = drafts.firstOrNull {
        it.noteId == document.note.id ||
          (initialImportRequestId != null && it.importRequestId == initialImportRequestId)
      }
      val alreadyCommittedImportDraft = pending?.takeIf {
        it.noteId == null &&
          initialImportRequestId != null &&
          it.importRequestId == initialImportRequestId &&
          it.matches(document)
      }
      if (alreadyCommittedImportDraft != null) {
        try {
          draftStore.clearIfAtMost(alreadyCommittedImportDraft.draftId, alreadyCommittedImportDraft.localRevision)
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          // The committed note remains authoritative; a later launch can retry stale-draft cleanup.
        }
      }
      val recoverableDraft = pending.takeUnless { it == alreadyCommittedImportDraft }
      _uiState.value = EditorUiState(
        noteId = document.note.id,
        draftId = UUID.randomUUID().toString(),
        importRequestId = initialImportRequestId,
        folderId = document.note.folderId,
        baseContentRevision = document.note.contentRevision,
        body = titleBody(blocks),
        manualTitle = document.note.title,
        titleManuallySet = document.note.titleManuallySet,
        blocks = blocks.ifEmpty { listOf(initialTextBlock) },
        skinId = document.note.skinId,
        fontSizeSpOverride = document.note.fontSizeSpOverride,
        lineSpacingMultiplierOverride = document.note.lineSpacingMultiplierOverride,
        activeTextBlockId = activeTextBlock?.id ?: initialTextBlock.id,
        completedTodoBlockIds = document.todos.filter { it.isCompleted }.mapTo(mutableSetOf()) { it.blockId },
        isLoaded = true,
        pendingRecovery = recoverableDraft,
        recoveryConflictRevision = recoverableDraft?.baseContentRevision?.takeIf { it != document.note.contentRevision },
        errorMessage = draftReadError,
      )
      document.blocks.filter { it.type == BlockType.OCR_TEXT && OcrJobRules.canRun(it.ocrStatus) }
        .forEach { block -> startOcrWork(document.note.id, block.id) }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      _uiState.value = _uiState.value.copy(
        isLoaded = true,
        saveStatus = EditorSaveStatus.FAILED,
        errorMessage = error.message ?: "笔记读取失败",
      )
    }
  }

  fun onBodyChanged(body: String) {
    val current = _uiState.value
    val firstTextIndex = current.blocks.indexOfFirst { it.type == BlockType.TEXT }
    val updatedBlocks = when {
      firstTextIndex < 0 && body.isNotEmpty() -> listOf(NoteBlockDraft(type = BlockType.TEXT, text = body)) + current.blocks
      firstTextIndex < 0 -> current.blocks
      else -> current.blocks.mapIndexedNotNull { index, block ->
        when {
          block.type != BlockType.TEXT -> block
          index == firstTextIndex -> block.copy(text = body)
          else -> null
        }
      }
    }
    _uiState.value = current.copy(
      body = body,
      blocks = updatedBlocks,
      saveStatus = EditorSaveStatus.DIRTY,
      errorMessage = null,
      revision = current.revision + 1,
    )
    queueDraftWrite()
    scheduleSave()
  }

  fun onTextBlockChanged(blockId: String, text: String) {
    val current = _uiState.value
    val existing = current.blocks.any { it.id == blockId && it.type == BlockType.TEXT }
    val updatedBlocks = if (existing) {
      current.blocks.map { block -> if (block.id == blockId) block.copy(text = text) else block }
    } else {
      current.blocks + NoteBlockDraft(id = blockId, type = BlockType.TEXT, text = text)
    }
    updateEditedBlocks(current, updatedBlocks, blockId, text.length, text.length)
  }

  fun onTextValueChanged(
    blockId: String,
    text: String,
    selection: TextSelection,
    composing: TextSelection?,
  ) {
    val current = _uiState.value
    val blockIndex = current.blocks.indexOfFirst { it.id == blockId }
    val block = current.blocks.getOrNull(blockIndex)
      ?: NoteBlockDraft(id = blockId, type = BlockType.TEXT, text = "")
    if (block.type !in FORMATTABLE_BLOCK_TYPES) return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
    if (formatting == null) {
      _uiState.value = current.copy(
        activeTextBlockId = blockId,
        errorMessage = "此文字块的格式版本不兼容，请先移除格式后编辑",
      )
      return
    }

    val oldText = block.text.orEmpty()
    val oldSelection = if (current.activeTextBlockId == blockId) {
      TextSelection(current.selectionStart, current.selectionEnd)
    } else {
      TextSelection(oldText.length, oldText.length)
    }
    val edit = try {
      BlockFormattingRules.replaceText(
        oldText = oldText,
        newText = text,
        oldSelection = oldSelection,
        selection = selection,
        composing = composing,
        formatting = formatting,
        typingOverrides = current.typingOverrides,
        type = block.type,
      )
    } catch (error: IllegalArgumentException) {
      _uiState.value = current.copy(errorMessage = error.message ?: "文字格式无法应用到此内容")
      return
    }

    if (text == oldText) {
      val compositionCommitted = EditorPersistenceRules.shouldPersistInput(
        previousComposing = current.composing,
        composing = composing,
        contentChanged = false,
      )
      _uiState.value = current.copy(
        activeTextBlockId = blockId,
        activeTodoBlockId = null,
        selectionStart = edit.selection.startUtf16,
        selectionEnd = edit.selection.endUtf16,
        composing = edit.composing,
        saveStatus = if (compositionCommitted) EditorSaveStatus.DIRTY else current.saveStatus,
        revision = if (compositionCommitted) current.revision + 1L else current.revision,
      )
      if (compositionCommitted) {
        queueDraftWrite()
        scheduleSave()
      }
      return
    }

    val updatedBlock = block.copy(
      text = edit.text,
      formatting = BlockFormattingState.Supported(edit.formatting),
    )
    val updatedBlocks = if (blockIndex >= 0) {
      current.blocks.map { if (it.id == blockId) updatedBlock else it }
    } else {
      current.blocks + updatedBlock
    }
    updateEditedBlocks(
      current = current,
      blocks = updatedBlocks,
      activeTextBlockId = blockId,
      selectionStart = edit.selection.startUtf16,
      selectionEnd = edit.selection.endUtf16,
      composing = edit.composing,
    )
  }

  fun applyInlineStyle(style: InlineStyle) {
    val current = _uiState.value
    val block = current.blocks.firstOrNull { it.id == current.activeTextBlockId }
      ?.takeIf { it.type in FORMATTABLE_BLOCK_TYPES } ?: return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
    if (formatting == null) {
      _uiState.value = current.copy(errorMessage = "此文字块的格式版本不兼容，请先移除格式后编辑")
      return
    }
    val text = block.text.orEmpty()
    val selection = try {
      BlockFormattingRules.normalizeSelection(text, TextSelection(current.selectionStart, current.selectionEnd))
    } catch (error: IllegalArgumentException) {
      _uiState.value = current.copy(errorMessage = error.message ?: "文字选区无效")
      return
    }

    if (selection.startUtf16 == selection.endUtf16) {
      val inherited = BlockFormattingRules.isStyleActiveAtCaret(
        text = text,
        formatting = formatting,
        style = style,
        offsetUtf16 = selection.startUtf16,
        type = block.type,
      )
      val enabled = !(current.typingOverrides[style] ?: inherited)
      _uiState.value = current.copy(typingOverrides = current.typingOverrides + (style to enabled))
      return
    }

    val fullyStyled = formatting.inlineStyles.any {
      it.style == style && it.startUtf16 <= selection.startUtf16 && it.endUtf16 >= selection.endUtf16
    }
    val updatedFormatting = BlockFormattingRules.setInlineStyle(
      text = text,
      formatting = formatting,
      selection = selection,
      style = style,
      enabled = !fullyStyled,
      type = block.type,
    )
    val updatedBlocks = current.blocks.map {
      if (it.id == block.id) it.copy(formatting = BlockFormattingState.Supported(updatedFormatting)) else it
    }
    updateEditedBlocks(
      current,
      updatedBlocks,
      current.activeTextBlockId,
      selection.startUtf16,
      selection.endUtf16,
      current.composing,
    )
  }

  fun togglePresentation() {
    val current = _uiState.value
    val block = current.blocks.firstOrNull { it.id == current.activeTextBlockId && it.type == BlockType.TEXT } ?: return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
    if (formatting == null) {
      _uiState.value = current.copy(errorMessage = "此文字块的格式版本不兼容，请先移除格式后编辑")
      return
    }
    val updatedFormatting = formatting.copy(
      presentation = if (formatting.presentation == TextBlockPresentation.HEADING) {
        TextBlockPresentation.BODY
      } else {
        TextBlockPresentation.HEADING
      },
    )
    val updatedBlocks = current.blocks.map {
      if (it.id == block.id) it.copy(formatting = BlockFormattingState.Supported(updatedFormatting)) else it
    }
    updateEditedBlocks(current, updatedBlocks, block.id, current.selectionStart, current.selectionEnd, current.composing)
  }

  fun toggleQuote() {
    val current = _uiState.value
    val block = current.blocks.firstOrNull { it.id == current.activeTextBlockId }
      ?.takeIf { it.type == BlockType.TEXT || it.type == BlockType.QUOTE } ?: return
    val formatting = (block.formatting as? BlockFormattingState.Supported)?.value
    if (formatting == null) {
      _uiState.value = current.copy(errorMessage = "此文字块的格式版本不兼容，请先移除格式后编辑")
      return
    }
    val nextType = if (block.type == BlockType.TEXT) BlockType.QUOTE else BlockType.TEXT
    val updatedBlock = block.copy(
      type = nextType,
      formatting = BlockFormattingState.Supported(formatting.copy(presentation = TextBlockPresentation.BODY)),
    )
    val updatedBlocks = current.blocks.map { if (it.id == block.id) updatedBlock else it }
    updateEditedBlocks(current, updatedBlocks, block.id, current.selectionStart, current.selectionEnd, current.composing)
  }

  fun resetUnsupportedFormatting(blockId: String) {
    val current = _uiState.value
    val block = current.blocks.firstOrNull { it.id == blockId } ?: return
    if (block.formatting !is BlockFormattingState.Unsupported) return
    val updatedBlocks = current.blocks.map {
      if (it.id == blockId) it.copy(formatting = BlockFormattingState.Supported(BlockFormatting())) else it
    }
    val cursorAtEnd = block.text.orEmpty().length
    updateEditedBlocks(current, updatedBlocks, blockId, cursorAtEnd, cursorAtEnd)
  }

  fun onTextSelectionChanged(blockId: String, start: Int, end: Int) {
    val current = _uiState.value
    val textBlock = current.blocks.firstOrNull { it.id == blockId && it.type == BlockType.TEXT }
    val updatedBlocks = if (textBlock == null) {
      current.blocks + NoteBlockDraft(id = blockId, type = BlockType.TEXT, text = "")
    } else current.blocks
    val length = updatedBlocks.firstOrNull { it.id == blockId }?.text.orEmpty().length
    _uiState.value = current.copy(
      blocks = updatedBlocks,
      activeTextBlockId = blockId,
      activeTodoBlockId = null,
      selectionStart = start.coerceIn(0, length),
      selectionEnd = end.coerceIn(0, length),
      composing = null,
    )
  }

  fun insertTodo() {
    val current = _uiState.value
    val focusedTodo = current.activeTodoBlockId?.let { focusedId ->
      current.blocks.firstOrNull { it.id == focusedId && it.type == BlockType.TODO }
    }
    if (focusedTodo != null) {
      val todoId = UUID.randomUUID().toString()
      val updatedBlocks = TodoBlockRules.insertAfterTodo(current.blocks, focusedTodo.id, todoId)
      updateEditedBlocks(
        current = current,
        blocks = updatedBlocks,
        activeTextBlockId = current.activeTextBlockId,
        selectionStart = current.selectionStart,
        selectionEnd = current.selectionEnd,
        focusTodoBlockId = todoId,
      )
      return
    }
    val textBlock = current.blocks.firstOrNull { it.id == current.activeTextBlockId && it.type in FORMATTABLE_BLOCK_TYPES }
      ?: current.blocks.firstOrNull { it.type in FORMATTABLE_BLOCK_TYPES }
      ?: initialTextBlock
    val blocks = if (current.blocks.any { it.id == textBlock.id }) current.blocks else current.blocks + textBlock
    val selection = if (textBlock.id == current.activeTextBlockId) {
      TextSelection(current.selectionStart, current.selectionEnd)
    } else {
      val end = textBlock.text.orEmpty().length
      TextSelection(end, end)
    }
    val todoId = UUID.randomUUID().toString()
    var isTodoId = true
    try {
      val updatedBlocks = TodoBlockRules.insertAtSelection(blocks, textBlock.id, selection) {
        if (isTodoId) {
          isTodoId = false
          todoId
        } else UUID.randomUUID().toString()
      }
      updateEditedBlocks(
        current = current,
        blocks = updatedBlocks,
        activeTextBlockId = textBlock.id,
        selectionStart = selection.startUtf16,
        selectionEnd = selection.startUtf16,
        focusTodoBlockId = todoId,
      )
    } catch (error: IllegalArgumentException) {
      _uiState.value = current.copy(errorMessage = error.message ?: "无法在此处插入待办")
    }
  }

  fun onTodoValueChanged(
    blockId: String,
    text: String,
    selection: TextSelection,
    composing: TextSelection?,
  ) {
    val current = _uiState.value
    val block = current.blocks.firstOrNull { it.id == blockId && it.type == BlockType.TODO } ?: return
    val textChanged = block.text.orEmpty() != text
    val previousComposing = current.todoCompositions[blockId]
    val shouldPersist = EditorPersistenceRules.shouldPersistInput(previousComposing, composing, textChanged)
    val updatedBlocks = if (textChanged) {
      current.blocks.map { if (it.id == blockId) it.copy(text = text) else it }
    } else current.blocks
    val updatedCompositions = if (composing == null) current.todoCompositions - blockId
    else current.todoCompositions + (blockId to composing)
    _uiState.value = current.copy(
      body = if (textChanged) titleBody(updatedBlocks) else current.body,
      blocks = updatedBlocks,
      todoSelections = current.todoSelections + (blockId to TextSelection(
        selection.startUtf16.coerceIn(0, text.length),
        selection.endUtf16.coerceIn(0, text.length),
      )),
      todoCompositions = updatedCompositions,
      activeTodoBlockId = blockId,
      saveStatus = if (textChanged || shouldPersist) EditorSaveStatus.DIRTY else current.saveStatus,
      errorMessage = if (textChanged) null else current.errorMessage,
      revision = if (textChanged || shouldPersist) current.revision + 1L else current.revision,
    )
    if (shouldPersist) {
      queueDraftWrite()
      scheduleSave()
    }
  }

  fun onTodoFocusChanged(blockId: String, focused: Boolean) {
    val current = _uiState.value
    val activeTodoBlockId = if (focused) blockId else current.activeTodoBlockId.takeUnless { it == blockId }
    if (activeTodoBlockId != current.activeTodoBlockId) {
      _uiState.value = current.copy(activeTodoBlockId = activeTodoBlockId)
    }
  }

  fun onTodoCompletionChanged(blockId: String, completed: Boolean) {
    val current = _uiState.value
    val block = current.blocks.firstOrNull { it.id == blockId && it.type == BlockType.TODO }
      ?.takeIf { !it.text.isNullOrBlank() } ?: return
    val updatedIds = if (completed) current.completedTodoBlockIds + blockId else current.completedTodoBlockIds - blockId
    _uiState.value = current.copy(completedTodoBlockIds = updatedIds, errorMessage = null)
    pendingTodoWrites.value += 1
    viewModelScope.launch {
      try {
        todoCompletionMutex.withLock {
          try {
            var latest = _uiState.value
            if (latest.blocks.none { it.id == block.id && it.type == BlockType.TODO && !it.text.isNullOrBlank() }) {
              throw IllegalStateException("待办内容已变化，请重试")
            }
            if (latest.noteId == null || latest.saveStatus !in setOf(EditorSaveStatus.CLEAN, EditorSaveStatus.SAVED)) {
              if (!persistCurrent()) throw IllegalStateException("待办尚未保存，稍后再试")
              latest = _uiState.value
            }
            val noteId = latest.noteId ?: throw IllegalStateException("待办尚未保存，稍后再试")
            if (!repository.setTodoCompletedForBlock(noteId, blockId, completed)) {
              throw IllegalStateException("待办尚未同步，请稍后重试")
            }
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            val latest = _uiState.value
            val latestIsRequested = (blockId in latest.completedTodoBlockIds) == completed
            _uiState.value = latest.copy(
              completedTodoBlockIds = if (latestIsRequested) {
                if (completed) latest.completedTodoBlockIds - blockId else latest.completedTodoBlockIds + blockId
              } else latest.completedTodoBlockIds,
              errorMessage = error.message ?: "待办状态保存失败",
            )
          }
        }
      } finally {
        pendingTodoWrites.value = (pendingTodoWrites.value - 1).coerceAtLeast(0)
      }
    }
  }

  fun onTodoFocusHandled(blockId: String) {
    if (_uiState.value.focusTodoBlockId == blockId) {
      _uiState.value = _uiState.value.copy(focusTodoBlockId = null)
    }
  }

  fun insertImage(uri: Uri, mode: OcrImportMode = OcrImportMode.IMAGE_ONLY) {
    viewModelScope.launch {
      var stagedBlockId: String? = null
      try {
        val current = _uiState.value
        val textBlocks = current.blocks.filter { it.type == BlockType.TEXT }
        val selected = textBlocks.firstOrNull { it.id == current.activeTextBlockId } ?: textBlocks.firstOrNull()
          ?: initialTextBlock
        val blocks = if (current.blocks.any { it.id == selected.id }) current.blocks else current.blocks + selected
        val start = if (selected.id == current.activeTextBlockId) current.selectionStart else selected.text.orEmpty().length
        val end = if (selected.id == current.activeTextBlockId) current.selectionEnd else start
        when (mode) {
          OcrImportMode.IMAGE_ONLY, OcrImportMode.IMAGE_AND_TEXT -> {
            val image = imageImporter.import(uri)
            var updatedBlocks = ImageBlockRules.insertAtSelection(blocks, selected.id, start, end, image)
            if (mode == OcrImportMode.IMAGE_AND_TEXT) {
              val pending = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = com.anan.anannotes.core.model.OcrStatus.PENDING)
              updatedBlocks = OcrBlockInsertionRules.appendAfterImage(updatedBlocks, image.id, pending)
            }
            val imageIndex = updatedBlocks.indexOfFirst { it.id == image.id }
            val ocrBlock = updatedBlocks.getOrNull(imageIndex + 1)?.takeIf { it.type == BlockType.OCR_TEXT }
            val textAfterImage = updatedBlocks.drop(imageIndex + 1).firstOrNull { it.type == BlockType.TEXT }
            updateEditedBlocks(current, updatedBlocks, textAfterImage?.id ?: selected.id, 0, 0)
            ocrBlock?.let { scheduleOcrAfterSave(it.id) }
          }
          OcrImportMode.TEXT_ONLY -> {
            val pending = NoteBlockDraft(type = BlockType.OCR_TEXT, ocrStatus = com.anan.anannotes.core.model.OcrStatus.PENDING)
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
              ?: throw IOException("无法打开所选图片")
            stagedBlockId = pending.id
            withContext(Dispatchers.IO) { OcrStagingStore.forContext(getApplication<Application>()).stage(pending.id, input) }
            val inserted = OcrBlockInsertionRules.insertAtSelection(blocks, selected.id, start, end, pending)
            updateEditedBlocks(current, inserted.blocks, inserted.focusTextBlockId, inserted.selectionStart, inserted.selectionStart)
            scheduleOcrAfterSave(pending.id)
          }
        }
      } catch (error: CancellationException) {
        stagedBlockId?.let { OcrStagingStore.deleteStagedSource(getApplication<Application>(), it) }
        throw error
      } catch (error: Exception) {
        stagedBlockId?.let { OcrStagingStore.deleteStagedSource(getApplication<Application>(), it) }
        _uiState.value = _uiState.value.copy(errorMessage = error.message ?: "图片插入失败")
      }
    }
  }

  fun cancelOcr(blockId: String) {
    val noteId = _uiState.value.noteId ?: return
    viewModelScope.launch {
      OfflineOcrWorker.cancel(getApplication(), noteId, blockId)
      syncOcrBlock(noteId, blockId)
    }
  }

  fun retryOcr(blockId: String) {
    val noteId = _uiState.value.noteId ?: return
    viewModelScope.launch {
      try {
        val queued = OfflineOcrWorker.retry(getApplication(), noteId, blockId)
        if (!queued) {
          _uiState.value = _uiState.value.copy(errorMessage = "临时图片已清理，请重新选择图片识别")
          return@launch
        }
        val pending = _uiState.value.blocks.map { block ->
          if (block.id == blockId) block.copy(text = null, ocrStatus = com.anan.anannotes.core.model.OcrStatus.PENDING) else block
        }
        _uiState.value = _uiState.value.copy(blocks = pending, errorMessage = null)
        observeOcrWork(noteId, blockId)
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        _uiState.value = _uiState.value.copy(errorMessage = "无法重新排队，请稍后重试")
      }
    }
  }

  private fun scheduleOcrAfterSave(blockId: String) {
    viewModelScope.launch {
      for (attempt in 0 until OCR_SAVE_ATTEMPTS) {
        if (persistCurrent()) {
          val noteId = _uiState.value.noteId ?: return@launch
          startOcrWork(noteId, blockId)
          return@launch
        }
        if (_uiState.value.saveStatus in setOf(EditorSaveStatus.FAILED, EditorSaveStatus.CONFLICT)) break
        if (attempt < OCR_SAVE_ATTEMPTS - 1) delay(OCR_SAVE_RETRY_DELAY_MILLIS)
      }
      if (_uiState.value.noteId == null || _uiState.value.saveStatus in setOf(EditorSaveStatus.FAILED, EditorSaveStatus.CONFLICT)) {
        _uiState.value = _uiState.value.copy(errorMessage = "图片已加入草稿，但识别任务尚未排队；保存成功后重新打开笔记可继续识别")
      }
    }
  }

  private suspend fun startOcrWork(noteId: String, blockId: String) {
    val block = _uiState.value.blocks.firstOrNull { it.id == blockId }
    if (block?.ocrStatus == com.anan.anannotes.core.model.OcrStatus.SUCCEEDED) return
    OfflineOcrWorker.enqueue(getApplication(), noteId, blockId)
    observeOcrWork(noteId, blockId)
  }

  private fun observeOcrWork(noteId: String, blockId: String) {
    ocrMonitorJobs[blockId]?.cancel()
    ocrMonitorJobs[blockId] = viewModelScope.launch {
      WorkManager.getInstance(getApplication<Application>())
        .getWorkInfosForUniqueWorkFlow(OfflineOcrWorker.workName(blockId))
        .takeWhile { infos ->
          val info = infos.lastOrNull() ?: return@takeWhile true
          if (info.state.isFinished) return@takeWhile false
          val progress = info.progress.getInt(OfflineOcrWorker.KEY_PROGRESS, 0)
          val stage = info.progress.getString(OfflineOcrWorker.KEY_STAGE).orEmpty()
          _uiState.value = _uiState.value.copy(
            ocrProgressByBlock = _uiState.value.ocrProgressByBlock + (blockId to EditorOcrProgress(progress, stage)),
          )
          true
        }
        .collect()
      _uiState.value = _uiState.value.copy(ocrProgressByBlock = _uiState.value.ocrProgressByBlock - blockId)
      syncOcrBlock(noteId, blockId)
    }
  }

  private suspend fun syncOcrBlock(noteId: String, blockId: String) {
    val persisted = repository.getDocumentSnapshot(noteId)?.blocks?.firstOrNull { it.id == blockId } ?: return
    val current = _uiState.value
    val index = current.blocks.indexOfFirst { it.id == blockId }
    if (index < 0) return
    val local = current.blocks[index]
    if (local.ocrStatus !in setOf(
        com.anan.anannotes.core.model.OcrStatus.PENDING,
        com.anan.anannotes.core.model.OcrStatus.RUNNING,
      ) || persisted.ocrStatus !in setOf(
        com.anan.anannotes.core.model.OcrStatus.SUCCEEDED,
        com.anan.anannotes.core.model.OcrStatus.FAILED,
        com.anan.anannotes.core.model.OcrStatus.CANCELLED,
      )
    ) return
    val updated = current.blocks.toMutableList().apply { this[index] = persisted.toDraft() }
    _uiState.value = current.copy(
      blocks = updated,
      body = titleBody(updated),
      ocrProgressByBlock = current.ocrProgressByBlock - blockId,
    )
  }

  private suspend fun appendImportedImage(uri: Uri) {
    val image = imageImporter.import(uri)
    val current = _uiState.value
    val onlyEmptyStarter = current.blocks.size == 1 &&
      current.blocks.single().id == initialTextBlock.id &&
      current.blocks.single().text.isNullOrEmpty()
    val contentBlocks = if (onlyEmptyStarter) emptyList() else current.blocks
    val tail = NoteBlockDraft(type = BlockType.TEXT, text = "")
    val updatedBlocks = contentBlocks + image + tail
    updateEditedBlocks(
      current = current,
      blocks = updatedBlocks,
      activeTextBlockId = tail.id,
      selectionStart = 0,
      selectionEnd = 0,
    )
  }

  fun onImageWidthChanged(blockId: String, widthPercent: Float) {
    val current = _uiState.value
    val updatedBlocks = current.blocks.map { block ->
      if (block.id == blockId && block.type == BlockType.IMAGE) {
        block.copy(widthPercent = ImageBlockRules.normalizeWidthPercent(widthPercent))
      } else block
    }
    if (updatedBlocks == current.blocks) return
    updateEditedBlocks(current, updatedBlocks, current.activeTextBlockId, current.selectionStart, current.selectionEnd)
  }

  fun deleteImageBlocks(imageBlockIds: Set<String>) {
    val current = _uiState.value
    val updatedBlocks = ImageBlockRules.removeImageBlocks(current.blocks, imageBlockIds)
    if (updatedBlocks == current.blocks) return
    val activeTextBlock = updatedBlocks.lastOrNull { it.type == BlockType.TEXT }
    val cursor = activeTextBlock?.text.orEmpty().length
    updateEditedBlocks(
      current = current,
      blocks = updatedBlocks,
      activeTextBlockId = activeTextBlock?.id ?: current.activeTextBlockId,
      selectionStart = cursor,
      selectionEnd = cursor,
    )
  }

  private fun updateEditedBlocks(
    current: EditorUiState,
    blocks: List<NoteBlockDraft>,
    activeTextBlockId: String?,
    selectionStart: Int,
    selectionEnd: Int,
    composing: TextSelection? = null,
    focusTodoBlockId: String? = null,
    activeTodoBlockId: String? = null,
  ) {
    val body = titleBody(blocks)
    val todoBlockIds = blocks.filter { it.type == BlockType.TODO }.mapTo(hashSetOf()) { it.id }
    _uiState.value = current.copy(
      body = body,
      blocks = blocks,
      todoSelections = current.todoSelections.filterKeys { it in todoBlockIds },
      todoCompositions = current.todoCompositions.filterKeys { it in todoBlockIds },
      activeTextBlockId = activeTextBlockId,
      selectionStart = selectionStart,
      selectionEnd = selectionEnd,
      composing = composing,
      focusTodoBlockId = focusTodoBlockId,
      activeTodoBlockId = activeTodoBlockId,
      saveStatus = EditorSaveStatus.DIRTY,
      errorMessage = null,
      revision = current.revision + 1,
    )
    if (EditorPersistenceRules.shouldPersistInput(
        previousComposing = current.composing,
        composing = composing,
        contentChanged = blocks != current.blocks,
      )
    ) {
      queueDraftWrite()
      scheduleSave()
    }
  }

  fun onTitleValueChanged(title: String, selection: TextSelection, composing: TextSelection?) {
    val current = _uiState.value
    val titleChanged = title != current.titleFieldValue
    val shouldPersist = EditorPersistenceRules.shouldPersistInput(current.titleComposing, composing, titleChanged)
    _uiState.value = current.copy(
      manualTitle = if (titleChanged || current.titleManuallySet) title else current.manualTitle,
      titleManuallySet = current.titleManuallySet || titleChanged,
      titleSelectionStart = selection.startUtf16.coerceIn(0, title.length),
      titleSelectionEnd = selection.endUtf16.coerceIn(0, title.length),
      titleComposing = composing,
      saveStatus = if (titleChanged || shouldPersist) EditorSaveStatus.DIRTY else current.saveStatus,
      errorMessage = if (titleChanged) null else current.errorMessage,
      revision = if (titleChanged || shouldPersist) current.revision + 1L else current.revision,
    )
    if (shouldPersist) {
      queueDraftWrite()
      scheduleSave()
    }
  }

  fun onSkinChanged(skinId: String) {
    val current = _uiState.value
    if (current.skinId == skinId) return
    _uiState.value = current.copy(
      skinId = skinId,
      saveStatus = EditorSaveStatus.DIRTY,
      errorMessage = null,
      revision = current.revision + 1,
    )
    queueDraftWrite()
    scheduleSave()
  }

  fun onTypographyOverridesChanged(fontSizeSp: Float?, lineSpacingMultiplier: Float?) {
    val current = _uiState.value
    if (current.fontSizeSpOverride == fontSizeSp && current.lineSpacingMultiplierOverride == lineSpacingMultiplier) return
    _uiState.value = current.copy(
      fontSizeSpOverride = fontSizeSp,
      lineSpacingMultiplierOverride = lineSpacingMultiplier,
      saveStatus = EditorSaveStatus.DIRTY,
      errorMessage = null,
      revision = current.revision + 1,
    )
    queueDraftWrite()
    scheduleSave()
  }

  fun toggleMoreToolbar() {
    _uiState.value = _uiState.value.copy(showMoreToolbar = !_uiState.value.showMoreToolbar)
  }

  fun restorePendingDraft() = restorePendingDraft(asNewNote = false)

  fun restorePendingDraftAsNewNote() = restorePendingDraft(asNewNote = true)

  private fun restorePendingDraft(asNewNote: Boolean) {
    val current = _uiState.value
    val pending = current.pendingRecovery ?: return
    val draftBlocks = pending.draft.blocks.ifEmpty { listOf(initialTextBlock) }
    val restoredBlocks = ImageBlockRules.ensureEditableTextTail(draftBlocks)
    val addedEditableTail = restoredBlocks.size > draftBlocks.size
    _uiState.value = current.copy(
      noteId = if (asNewNote) null else (pending.noteId ?: current.noteId),
      importRequestId = if (asNewNote) null else pending.importRequestId ?: current.importRequestId,
      draftId = pending.draftId,
      folderId = pending.draft.folderId,
      baseContentRevision = if (asNewNote) 0L else current.baseContentRevision,
      body = titleBody(restoredBlocks),
      manualTitle = pending.draft.title,
      titleManuallySet = pending.draft.titleManuallySet,
      blocks = restoredBlocks,
      skinId = pending.draft.skinId,
      fontSizeSpOverride = pending.draft.fontSizeSpOverride,
      lineSpacingMultiplierOverride = pending.draft.lineSpacingMultiplierOverride,
      activeTextBlockId = if (addedEditableTail) restoredBlocks.last().id else restoredBlocks.firstOrNull { it.type == BlockType.TEXT }?.id,
      selectionStart = 0,
      selectionEnd = 0,
      composing = null,
      typingOverrides = emptyMap(),
      completedTodoBlockIds = if (asNewNote) emptySet() else current.completedTodoBlockIds,
      focusTodoBlockId = null,
      activeTodoBlockId = null,
      saveStatus = EditorSaveStatus.DIRTY,
      errorMessage = null,
      revision = maxOf(current.revision, pending.localRevision) + 1L,
      pendingRecovery = null,
      recoveryConflictRevision = null,
    )
    queueDraftWrite()
    scheduleSave()
  }

  fun discardPendingDraft() {
    val pending = _uiState.value.pendingRecovery ?: return
    viewModelScope.launch {
      runCatching { draftStore.clearIfAtMost(pending.draftId, pending.localRevision) }
        .onSuccess {
          _uiState.value = _uiState.value.copy(
            pendingRecovery = null,
            recoveryConflictRevision = null,
            errorMessage = null,
          )
        }
        .onFailure {
          _uiState.value = _uiState.value.copy(errorMessage = "草稿未能删除，已保留在本机")
        }
    }
  }

  fun saveConflictAsNewNote() {
    val current = _uiState.value
    if (current.saveConflictRevision == null) return
    _uiState.value = current.copy(
      noteId = null,
      importRequestId = null,
      baseContentRevision = 0L,
      saveConflictRevision = null,
      completedTodoBlockIds = emptySet(),
      saveStatus = EditorSaveStatus.DIRTY,
      errorMessage = null,
      revision = current.revision + 1L,
    )
    queueDraftWrite()
    scheduleSave()
  }

  fun saveAndExit(onSaved: () -> Unit) {
    viewModelScope.launch {
      var current = _uiState.value
      if (current.pendingRecovery != null || current.saveConflictRevision != null) {
        onSaved()
        return@launch
      }
      if (EditorPersistenceRules.shouldFinalizeCompositionForExplicitSave(current)) {
        current = current.copy(
          composing = null,
          titleComposing = null,
          todoCompositions = emptyMap(),
          saveStatus = EditorSaveStatus.DIRTY,
          errorMessage = null,
          revision = current.revision + 1L,
        )
        _uiState.value = current
      }
      saveLoop?.cancel()
      if (persistCurrent()) onSaved()
    }
  }

  fun retrySave() {
    if (_uiState.value.saveStatus == EditorSaveStatus.FAILED) {
      _uiState.value = _uiState.value.copy(saveStatus = EditorSaveStatus.DIRTY, errorMessage = null)
      scheduleSave()
    }
  }

  fun savedExportDirectory(): String? = ExportDirectoryPreferences.read(getApplication())

  fun rememberExportDirectory(uriString: String) {
    ExportDirectoryPreferences.write(getApplication(), uriString)
  }

  fun showExportMessage(message: String) {
    _uiState.value = _uiState.value.copy(exportMessage = message)
  }

  fun dismissExportMessage() {
    _uiState.value = _uiState.value.copy(exportMessage = null)
  }

  fun acknowledgeExportFeedback(id: Long) {
    exportFeedbackInbox.acknowledge(id)
  }

  fun exportCurrentNote(format: ExportFormat, target: OutputTarget, includeAttribution: Boolean = false) {
    if (_uiState.value.isExporting) return
    when (val decision = EditorExportRules.evaluate(_uiState.value)) {
      is EditorExportDecision.Blocked -> {
        showExportMessage(exportBlockMessage(decision.reason))
        return
      }
      EditorExportDecision.PersistFirst,
      is EditorExportDecision.Ready,
      -> Unit
    }

    _uiState.value = _uiState.value.copy(isExporting = true, exportMessage = null, errorMessage = null)
    viewModelScope.launch {
      try {
        pendingTodoWrites.first { it == 0 }
        val noteId = todoCompletionMutex.withLock { persistLatestRevisionForExport() } ?: return@launch
        exporter.export(ExportRequest(
          noteIds = listOf(noteId),
          format = format,
          target = target,
          includeAttribution = includeAttribution,
        ))
          .flowOn(Dispatchers.IO)
          .collect { event ->
            when (event) {
              is ExportEvent.Completed -> {
                exportFeedbackInbox.post(
                  message = ExportFeedback.format(event),
                  showStorageAction = event.failedItems.any { it.code == ExportErrorCode.INSUFFICIENT_STORAGE },
                )
              }
              ExportEvent.Busy -> showExportMessage("已有导出正在进行，请稍候。")
              is ExportEvent.ItemCompleted, is ExportEvent.ItemFailed, is ExportEvent.Progress -> Unit
            }
          }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        showExportMessage(error.message?.takeIf(String::isNotBlank) ?: "导出失败，请检查保存位置后重试。")
      } finally {
        _uiState.value = _uiState.value.copy(isExporting = false)
      }
    }
  }

  /** Serializes with the debounced autosave and returns only a committed current revision. */
  private suspend fun persistLatestRevisionForExport(): String? {
    repeat(MAX_EXPORT_SAVE_ATTEMPTS) {
      when (val decision = EditorExportRules.evaluate(_uiState.value)) {
        is EditorExportDecision.Ready -> return decision.noteId
        is EditorExportDecision.Blocked -> {
          showExportMessage(exportBlockMessage(decision.reason))
          return null
        }
        EditorExportDecision.PersistFirst -> {
          val revisionBeforeSave = _uiState.value.revision
          if (!persistCurrent()) {
            val latestDecision = EditorExportRules.evaluate(_uiState.value)
            val message = (latestDecision as? EditorExportDecision.Blocked)?.let { exportBlockMessage(it.reason) }
              ?: "笔记未能保存，已取消导出。请重试保存后再导出。"
            showExportMessage(message)
            return null
          }
          val savedState = _uiState.value
          if (savedState.revision == revisionBeforeSave &&
            savedState.saveStatus in setOf(EditorSaveStatus.CLEAN, EditorSaveStatus.SAVED) && savedState.noteId == null
          ) {
            val reason = (EditorExportRules.evaluate(savedState) as? EditorExportDecision.Blocked)?.reason
            showExportMessage(reason?.let(::exportBlockMessage) ?: "笔记未能保存，已取消导出。")
            return null
          }
        }
      }
    }
    showExportMessage("笔记内容仍在变化，已取消导出。请稍后重试。")
    return null
  }

  private fun exportBlockMessage(reason: EditorExportBlockReason): String = when (reason) {
    EditorExportBlockReason.NOT_LOADED -> "笔记仍在加载，请稍后再导出。"
    EditorExportBlockReason.EMPTY -> "笔记还没有内容，暂时无法导出。"
    EditorExportBlockReason.COMPOSING -> "请先完成输入，再进行导出。"
    EditorExportBlockReason.RECOVERY -> "请先处理草稿恢复，再进行导出。"
    EditorExportBlockReason.SAVE_FAILED -> "笔记保存失败，重试成功后才能导出。"
    EditorExportBlockReason.CONFLICT -> "笔记存在版本冲突，请先处理冲突再导出。"
  }

  private fun queueDraftWrite() {
    viewModelScope.launch {
      saveMutex.withLock {
        val snapshot = _uiState.value
        if (!snapshot.isLoaded || snapshot.hasActiveComposition || snapshot.saveStatus != EditorSaveStatus.DIRTY ||
          snapshot.pendingRecovery != null || !shouldWriteDraft(snapshot)
        ) return@withLock
        try {
          draftStore.writeAtomic(snapshot.toDraftSnapshot())
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          _uiState.value = _uiState.value.copy(
            saveStatus = EditorSaveStatus.FAILED,
            errorMessage = error.message ?: "草稿写入失败，内容仍保留在当前页面",
          )
        }
      }
    }
  }

  private fun shouldWriteDraft(state: EditorUiState): Boolean {
    if (state.noteId != null) return true
    val draft = state.toNoteDraft()
    return createNoteIfMeaningful(draft, id = "preview", nowMillis = 0L) != null
  }

  private fun scheduleSave() {
    if (saveLoop?.isActive == true) return
    saveLoop = viewModelScope.launch {
      while (true) {
        val observedRevision = _uiState.value.revision
        delay(AUTO_SAVE_DELAY_MILLIS)
        val current = _uiState.value
        if (current.saveStatus != EditorSaveStatus.DIRTY) return@launch
        if (current.revision != observedRevision) continue
        if (current.hasActiveComposition) continue
        persistCurrent()
        if (_uiState.value.revision == observedRevision) return@launch
      }
    }
  }

  private suspend fun persistCurrent(): Boolean = saveMutex.withLock {
    val snapshot = _uiState.value
    if (!snapshot.isLoaded) return@withLock false
    if (snapshot.hasActiveComposition) return@withLock false
    if (snapshot.saveStatus == EditorSaveStatus.CLEAN || snapshot.saveStatus == EditorSaveStatus.SAVED) return@withLock true
    if (snapshot.pendingRecovery != null || snapshot.saveConflictRevision != null) return@withLock false

    _uiState.value = snapshot.copy(saveStatus = EditorSaveStatus.SAVING, errorMessage = null)
    try {
      if (shouldWriteDraft(snapshot)) draftStore.writeAtomic(snapshot.toDraftSnapshot())
      when (
        val result = repository.saveVersioned(
          draft = snapshot.toNoteDraft(),
          existingNoteId = snapshot.noteId,
          expectedContentRevision = snapshot.baseContentRevision.takeIf { snapshot.noteId != null },
          importRequestId = snapshot.importRequestId,
        )
      ) {
        is NoteSaveResult.Saved -> {
          val latestBeforeCommit = _uiState.value
          val latest = latestBeforeCommit.copy(
            noteId = result.noteId,
            baseContentRevision = result.contentRevision,
            completedTodoBlockIds = TodoBlockRules.completedIdsForPersistedTodoBlocks(
              latestBeforeCommit.completedTodoBlockIds,
              snapshot.blocks,
            ),
          )
          _uiState.value = latest.copy(
            saveStatus = if (latest.revision == snapshot.revision) EditorSaveStatus.SAVED else EditorSaveStatus.DIRTY,
          )
          if (shouldWriteDraft(latest)) {
            val committedSnapshot = latest.toDraftSnapshot()
            draftStore.writeAtomic(committedSnapshot)
            if (latest.revision == snapshot.revision) {
              draftStore.clearIfAtMost(snapshot.draftId, snapshot.revision)
            }
          } else {
            draftStore.clearIfAtMost(snapshot.draftId, snapshot.revision)
          }
          if (latest.revision != snapshot.revision) scheduleSave()
          true
        }
        NoteSaveResult.EmptyNotSaved -> {
          _uiState.value = _uiState.value.copy(saveStatus = EditorSaveStatus.SAVED, errorMessage = null)
          draftStore.clearIfAtMost(snapshot.draftId, snapshot.revision)
          true
        }
        is NoteSaveResult.Conflict -> {
          _uiState.value = _uiState.value.copy(
            saveStatus = EditorSaveStatus.CONFLICT,
            saveConflictRevision = result.currentRevision,
            errorMessage = null,
          )
          false
        }
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      _uiState.value = _uiState.value.copy(
        saveStatus = EditorSaveStatus.FAILED,
        errorMessage = error.message ?: "保存失败，草稿已尽力保留",
      )
      false
    }
  }

  private fun EditorUiState.toDraftSnapshot(): EditorDraftSnapshot = EditorDraftSnapshot(
    draftId = draftId,
    noteId = noteId,
    importRequestId = importRequestId,
    folderId = folderId,
    baseContentRevision = baseContentRevision,
    localRevision = revision,
    savedAtMillis = System.currentTimeMillis(),
    draft = toNoteDraft(),
  )

  private fun EditorUiState.toNoteDraft() = NoteDraft(
    folderId = folderId,
    title = titleForSave,
    titleManuallySet = titleManuallySet,
    blocks = blocks,
    skinId = skinId,
    fontSizeSpOverride = fontSizeSpOverride,
    lineSpacingMultiplierOverride = lineSpacingMultiplierOverride,
  )

  private fun titleBody(blocks: List<NoteBlockDraft>): String =
    blocks.firstOrNull { it.type in TITLE_TEXT_BLOCK_TYPES }?.text.orEmpty()

  companion object {
    const val AUTO_SAVE_DELAY_MILLIS = 400L
    private const val OCR_SAVE_ATTEMPTS = 8
    private const val OCR_SAVE_RETRY_DELAY_MILLIS = 150L
    private const val MAX_EXPORT_SAVE_ATTEMPTS = 8
    private const val DRAFT_DIRECTORY = "drafts"
    private val FORMATTABLE_BLOCK_TYPES = setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT)
    private val TITLE_TEXT_BLOCK_TYPES = setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT)
  }
}

class EditorViewModelFactory(
  private val application: Application,
  private val noteId: String?,
  private val folderId: String,
  private val defaultSettings: DisplaySettings = DisplaySettings(),
  private val initialImportText: String? = null,
  private val initialImportUris: List<String> = emptyList(),
  private val importRequestId: String? = null,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    require(modelClass.isAssignableFrom(EditorViewModel::class.java))
    return EditorViewModel(application, noteId, folderId, defaultSettings, initialImportText, initialImportUris, importRequestId) as T
  }
}

private fun BlockModel.toDraft() = NoteBlockDraft(
  id = id,
  type = type,
  text = text,
  assetPath = assetPath,
  previewPath = previewPath,
  widthPercent = widthPercent,
  alignment = alignment,
  aspectRatio = aspectRatio,
  ocrStatus = ocrStatus,
  formatting = formatting,
)

private fun EditorDraftSnapshot.matches(document: NoteDocument): Boolean =
  draft == NoteDraft(
    folderId = document.note.folderId,
    title = document.note.title,
    titleManuallySet = document.note.titleManuallySet,
    blocks = ImageBlockRules.ensureEditableTextTail(document.blocks.map(BlockModel::toDraft)),
    skinId = document.note.skinId,
    fontSizeSpOverride = document.note.fontSizeSpOverride,
    lineSpacingMultiplierOverride = document.note.lineSpacingMultiplierOverride,
  )


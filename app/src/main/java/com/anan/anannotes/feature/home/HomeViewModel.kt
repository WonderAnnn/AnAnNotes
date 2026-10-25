package com.anan.anannotes.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.NoteSortOrder
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.files.OriginalImageStore
import com.anan.anannotes.data.repository.FolderRepository
import com.anan.anannotes.data.repository.HomeViewPreferenceRepository
import com.anan.anannotes.data.repository.NoteRepository
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportErrorCode
import com.anan.anannotes.feature.export.ExportDirectoryPreferences
import com.anan.anannotes.feature.export.ExportRequest
import com.anan.anannotes.feature.export.ExportFeedback
import com.anan.anannotes.feature.export.ExportFeedbackInbox
import com.anan.anannotes.feature.export.OrdinaryExportServiceFactory
import com.anan.anannotes.feature.export.OutputTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {
  private val database = AnAnDatabase.getInstance(application)
  private val folderRepository = FolderRepository(database)
  private val originalImageStore = OriginalImageStore(application.filesDir)
  private val noteRepository = NoteRepository(database, assetStore = originalImageStore)
  private val preferenceRepository = HomeViewPreferenceRepository(database.homeViewPreferenceDao())
  private val exporter = OrdinaryExportServiceFactory.create(application)
  private val _exportEvents = Channel<ExportEvent>(Channel.BUFFERED)
  val exportEvents = _exportEvents.receiveAsFlow()
  private val exportFeedbackInbox = ExportFeedbackInbox()
  private val selectedFolderId = MutableStateFlow(ALL_NOTES_FOLDER_ID)
  private val sortOrder = selectedFolderId
    .flatMapLatest(preferenceRepository::observeSortOrder)
    .distinctUntilChanged()
  private val selectedNoteIds = MutableStateFlow<Set<String>>(emptySet())
  private val message = MutableStateFlow<String?>(null)
  private val isExporting = MutableStateFlow(false)

  private val visibleNotes = combine(folderRepository.observeFolders(), selectedFolderId, sortOrder) { folders, folderId, order ->
    Triple(folders, folderId, order)
  }.flatMapLatest { (folders, folderId, order) ->
    val notes = if (folderId == ALL_NOTES_FOLDER_ID) noteRepository.observeAllPreviews(order) else noteRepository.observePreviews(folderId, order)
    notes.map { rows -> HomeUiState(folders = folders, selectedFolderId = folderId, sortOrder = order, notes = rows) }
  }

  val uiState = combine(visibleNotes, selectedNoteIds, message, isExporting, exportFeedbackInbox.next) { state, selection, statusMessage, exporting, pendingFeedback ->
    state.copy(
      selectedNoteIds = selection,
      message = statusMessage,
      isExporting = exporting,
      pendingExportFeedback = pendingFeedback,
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

  fun selectFolder(folderId: String) {
    selectedFolderId.value = folderId
    selectedNoteIds.value = emptySet()
  }

  fun setSortOrder(order: NoteSortOrder) {
    val viewId = selectedFolderId.value
    viewModelScope.launch {
      preferenceRepository.setSortOrder(viewId, order)
    }
  }

  fun toggleSelection(noteId: String) {
    selectedNoteIds.value = selectedNoteIds.value.let { selected ->
      if (noteId in selected) selected - noteId else selected + noteId
    }
  }

  fun selectAllVisible() {
    selectedNoteIds.value = uiState.value.notes.mapTo(linkedSetOf()) { it.id }
  }

  fun clearSelection() {
    selectedNoteIds.value = emptySet()
  }

  fun createFolder(name: String) {
    viewModelScope.launch {
      runCatching { folderRepository.createFolder(name) }
        .onSuccess { folder ->
          message.value = null
          selectFolder(folder.id)
        }
        .onFailure { error -> message.value = error.message ?: "无法创建文件夹" }
    }
  }

  fun renameFolder(folderId: String, name: String) {
    viewModelScope.launch {
      runCatching { folderRepository.renameFolder(folderId, name) }
        .onSuccess { message.value = null }
        .onFailure { error -> message.value = error.message ?: "无法重命名文件夹" }
    }
  }

  fun deleteFolder(folderId: String) {
    viewModelScope.launch {
      val deleted = folderRepository.deleteFolder(folderId)
      if (deleted && selectedFolderId.value == folderId) selectFolder(UNCATEGORIZED_FOLDER_ID)
    }
  }

  fun moveSelectionToFolder(folderId: String) {
    val selected = selectedNoteIds.value.toList()
    viewModelScope.launch {
      runCatching { noteRepository.moveNotesToFolder(selected, folderId) }
        .onSuccess { clearSelection(); message.value = null }
        .onFailure { error -> message.value = error.message ?: "无法移动笔记" }
    }
  }

  fun pinSelection() {
    val selected = uiState.value.notes.filter { it.id in selectedNoteIds.value }
    val shouldPin = selected.any { !it.isPinned }
    val ids = selected.map { it.id }
    viewModelScope.launch {
      noteRepository.setPinned(ids, shouldPin)
      clearSelection()
    }
  }

  fun deleteSelection() {
    val ids = selectedNoteIds.value.toList()
    viewModelScope.launch {
      ids.forEach { noteRepository.moveToTrash(it) }
      clearSelection()
    }
  }

  fun savedExportDirectory(): String? = ExportDirectoryPreferences.read(getApplication())

  fun rememberExportDirectory(uriString: String) {
    ExportDirectoryPreferences.write(getApplication(), uriString)
  }

  fun exportSelection(format: ExportFormat, directoryUri: String, includeAttribution: Boolean = false) {
    exportSelection(
      format = format,
      target = OutputTarget.DocumentTree(directoryUri),
      includeAttribution = includeAttribution,
    )
  }

  fun exportSelectionToPictures(format: ExportFormat, includeAttribution: Boolean = false) {
    if (format !in setOf(ExportFormat.PNG_SOCIAL_SQUARE, ExportFormat.PNG_SOCIAL_PORTRAIT,
      ExportFormat.PNG_PAGED_600_DPI, ExportFormat.PNG_LONG_IMAGE)) {
      message.value = "此入口仅用于导出 PNG 图片。"
      return
    }
    exportSelection(format = format, target = OutputTarget.MediaStorePictures, includeAttribution = includeAttribution)
  }

  private fun exportSelection(format: ExportFormat, target: OutputTarget, includeAttribution: Boolean = false) {
    if (isExporting.value) {
      _exportEvents.trySend(ExportEvent.Busy)
      return
    }
    val selected = selectedNoteIds.value.toList()
    if (selected.isEmpty()) return
    val selectedAtStart = selected.toSet()
    isExporting.value = true
    viewModelScope.launch {
      try {
        exporter.export(
          ExportRequest(
            noteIds = selected,
            format = format,
            target = target,
            includeAttribution = includeAttribution,
          ),
        ).flowOn(Dispatchers.IO).collect { event ->
          if (event is ExportEvent.Completed) {
            exportFeedbackInbox.post(ExportFeedback.format(event),
              showStorageAction = event.failedItems.any { it.code == ExportErrorCode.INSUFFICIENT_STORAGE })
          }
          if (event is ExportEvent.Completed && event.failed == 0 && selectedNoteIds.value == selectedAtStart) {
            clearSelection()
          }
          _exportEvents.send(event)
        }
      } finally {
        isExporting.value = false
      }
    }
  }

  fun currentFolderForNewNote(): String =
    selectedFolderId.value.takeUnless { it == ALL_NOTES_FOLDER_ID } ?: UNCATEGORIZED_FOLDER_ID

  fun isUserFolder(folderId: String): Boolean =
    uiState.value.folders.any { it.id == folderId && it.kind == FolderKind.USER }

  fun dismissMessage() {
    message.value = null
  }

  fun showMessage(value: String) {
    message.value = value
  }

  fun acknowledgeExportFeedback(id: Long) {
    exportFeedbackInbox.acknowledge(id)
  }

}

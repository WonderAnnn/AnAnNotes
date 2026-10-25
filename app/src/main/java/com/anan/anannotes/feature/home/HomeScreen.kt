package com.anan.anannotes.feature.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.ExperimentalMaterial3Api
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.feature.export.ExportEvent
import com.anan.anannotes.feature.export.ExportFormatSheet
import com.anan.anannotes.feature.imports.ImportTextRules

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  onNewNote: (folderId: String) -> Unit,
  onImportFile: (folderId: String, uri: Uri) -> Unit,
  onSelectedFolderChanged: (folderId: String) -> Unit,
  onOpenNote: (noteId: String) -> Unit,
  onSearch: () -> Unit,
  onSettings: () -> Unit,
  onPrivate: () -> Unit,
  onTodo: () -> Unit,
  onHome: () -> Unit,
  onStorage: () -> Unit = {},
) {
  val viewModel: HomeViewModel = viewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    uri?.let { onImportFile(viewModel.currentFolderForNewNote(), it) }
  }
  val snackbar = remember { SnackbarHostState() }
  val context = LocalContext.current
  val compactHomeLayout = homeUsesCompactVerticalLayout(LocalWindowInfo.current.containerDpSize.height.value)
  var showSortSheet by remember { mutableStateOf(false) }
  var showMoveSheet by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  var showExportFormatSheet by remember { mutableStateOf(false) }
  var pendingExportFormat by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
  var pendingExportAttribution by rememberSaveable { mutableStateOf(false) }
  var isChangingExportDirectory by rememberSaveable { mutableStateOf(false) }

  val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      runCatching { context.contentResolver.takePersistableUriPermission(uri, grantFlags) }
        .onSuccess {
          viewModel.rememberExportDirectory(uri.toString())
          val format = pendingExportFormat
          val includeAttribution = pendingExportAttribution
          pendingExportFormat = null
          pendingExportAttribution = false
          if (format != null) viewModel.exportSelection(format, uri.toString(), includeAttribution)
          else if (isChangingExportDirectory) viewModel.showMessage("导出位置已更新")
        }
        .onFailure {
          pendingExportFormat = null
          pendingExportAttribution = false
          viewModel.showMessage("无法保存此文件夹的访问授权，请重新选择。")
        }
    } else {
      pendingExportFormat = null
      pendingExportAttribution = false
    }
    isChangingExportDirectory = false
  }

  LaunchedEffect(state.message) {
    state.message?.let {
      snackbar.showSnackbar(it)
      viewModel.dismissMessage()
    }
  }

  LaunchedEffect(viewModel) {
    viewModel.exportEvents.collect { event ->
      when (event) {
        is ExportEvent.ItemCompleted, is ExportEvent.ItemFailed, is ExportEvent.Progress -> Unit
        is ExportEvent.Completed -> Unit
        ExportEvent.Busy -> snackbar.showSnackbar("已有导出正在进行，请稍候。")
      }
    }
  }

  LaunchedEffect(state.pendingExportFeedback?.id) {
    state.pendingExportFeedback?.let { feedback ->
      val result = snackbar.showSnackbar(feedback.message,
        actionLabel = if (feedback.showStorageAction) "查看存储" else null,
        duration = if (feedback.showStorageAction) SnackbarDuration.Long else SnackbarDuration.Short)
      viewModel.acknowledgeExportFeedback(feedback.id)
      if (result == SnackbarResult.ActionPerformed && feedback.showStorageAction) onStorage()
    }
  }

  LaunchedEffect(state.selectedFolderId) {
    onSelectedFolderChanged(viewModel.currentFolderForNewNote())
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(snackbar) },
    bottomBar = {
      if (state.isSelecting) {
        BulkActionBar(
          onMove = { showMoveSheet = true },
          onPin = viewModel::pinSelection,
          pinActionLabel = if (state.notes.filter { it.id in state.selectedNoteIds }.any { !it.isPinned }) "置顶" else "取消置顶",
          onExport = { showExportFormatSheet = true },
          exportEnabled = !state.isExporting,
          isExporting = state.isExporting,
          onDelete = { confirmDelete = true },
        )
      } else {
        MainBottomBar(selectedIndex = 0, onHome = onHome, onTodo = onTodo)
      }
    },
    floatingActionButton = {
      if (!state.isSelecting) {
        FloatingActionButton(
          onClick = { onNewNote(viewModel.currentFolderForNewNote()) },
          modifier = Modifier.testTag("new-note-button"),
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
          ),
        ) {
          Text(text = "+", style = MaterialTheme.typography.headlineMedium)
        }
      }
    },
  ) { contentPadding ->
    Column(
      modifier = Modifier.fillMaxSize().padding(contentPadding)
        .padding(horizontal = if (compactHomeLayout) 12.dp else 20.dp, vertical = if (compactHomeLayout) 4.dp else 12.dp),
      verticalArrangement = Arrangement.spacedBy(if (compactHomeLayout) 4.dp else 12.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth().testTag(if (state.isSelecting) "home-selection-header" else "home-title-header"), verticalAlignment = Alignment.CenterVertically) {
        if (state.isSelecting) {
          Text("已选 ${state.selectedNoteIds.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
          TextButton(onClick = viewModel::selectAllVisible) { Text("全选") }
          TextButton(onClick = viewModel::clearSelection) { Text("取消") }
        } else {
          Text(
            "笔记",
            style = if (compactHomeLayout) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,
            modifier = Modifier.weight(1f).align(Alignment.Top),
          )
          TextButton(
            onClick = { documentPicker.launch(ImportTextRules.pickerMimeTypes()) },
            modifier = Modifier.testTag("home-import-button"),
          ) { Text("导入") }
          if (compactHomeLayout) {
            TextButton(onClick = onPrivate, modifier = Modifier.testTag("home-private-button")) { Text("私密") }
          }
          TextButton(onClick = onSettings, modifier = Modifier.testTag("home-settings-button")) { Text("设置") }
        }
      }
      if (!state.isSelecting && !compactHomeLayout) {
        Card(
          onClick = onPrivate,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("private-entry-button"),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
          Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text("私密笔记", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      OutlinedButton(
        onClick = onSearch,
        modifier = Modifier.fillMaxWidth().height(if (compactHomeLayout) 42.dp else 50.dp).testTag("search-entry"),
      ) {
        Text("⌕  搜索笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      FolderBar(
        folders = state.folders,
        selectedFolderId = state.selectedFolderId,
        onSelectFolder = viewModel::selectFolder,
        onCreateFolder = viewModel::createFolder,
        onRenameFolder = viewModel::renameFolder,
        onDeleteFolder = viewModel::deleteFolder,
        compact = compactHomeLayout,
      )
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          state.selectedFolderName,
          style = if (compactHomeLayout) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = { showSortSheet = true },
          modifier = Modifier.testTag("home-sort-button"),
        ) {
          Text(if (compactHomeLayout) "↕ 排序" else "↕  排序 / 布局", color = MaterialTheme.colorScheme.onSurface)
        }
        if (compactHomeLayout) {
          Spacer(Modifier.width(72.dp))
        }
      }
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (state.notes.isEmpty()) {
          Column(
            modifier = Modifier.align(Alignment.Center).testTag("home-empty-state"),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Text("还没有笔记", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("点击右下角“+”开始记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        } else {
          val fontScale = LocalDensity.current.fontScale
          val columnCount = if (compactHomeLayout) 1 else homeGridColumnCount(maxWidth.value, fontScale)
          LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier.fillMaxSize().testTag(if (columnCount == 1) "note-grid-single" else "note-grid-two"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(state.notes, key = { it.id }) { note ->
              NoteCard(
                note = note,
                selected = note.id in state.selectedNoteIds,
                onClick = {
                  if (state.isSelecting) viewModel.toggleSelection(note.id) else onOpenNote(note.id)
                },
                onLongClick = { viewModel.toggleSelection(note.id) },
                compact = compactHomeLayout,
                modifier = Modifier.animateItem(),
              )
            }
          }
        }
      }
    }
  }

  if (showSortSheet) {
    HomeSortSheet(
      selectedOrder = state.sortOrder,
      onSelectOrder = { order -> viewModel.setSortOrder(order); showSortSheet = false },
      onDismiss = { showSortSheet = false },
    )
  }

  if (showMoveSheet) {
    ModalBottomSheet(onDismissRequest = { showMoveSheet = false }) {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        Text("移动到文件夹", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        (listOfNotNull(state.folders.firstOrNull { it.kind.name == "UNCATEGORIZED" }) + state.folders.filter { it.kind.name == "USER" })
          .forEach { folder ->
            TextButton(
              onClick = { viewModel.moveSelectionToFolder(folder.id); showMoveSheet = false },
              modifier = Modifier.fillMaxWidth().testTag("move-folder-${folder.id}"),
            ) { Text(folder.name) }
          }
      }
    }
  }

  if (showExportFormatSheet) {
    ExportFormatSheet(
      title = "导出 ${state.selectedNoteIds.size} 条笔记",
      sheetTestTag = "home-export-format-sheet",
      listTestTag = "home-export-format-list",
      exportDirectoryLabel = if (viewModel.savedExportDirectory() == null) "选择保存文件夹" else "更换保存文件夹",
      onSelectDocumentFormat = { format, includeAttribution ->
        showExportFormatSheet = false
        val remembered = viewModel.savedExportDirectory()
        if (remembered != null) {
          viewModel.exportSelection(format, remembered, includeAttribution)
        } else {
          pendingExportFormat = format
          pendingExportAttribution = includeAttribution
          exportDirectoryPicker.launch(null)
        }
      },
      onSelectPictureFormat = { format, includeAttribution ->
        showExportFormatSheet = false
        viewModel.exportSelectionToPictures(format, includeAttribution)
      },
      onChangeExportDirectory = {
        val remembered = viewModel.savedExportDirectory()?.let(Uri::parse)
        pendingExportFormat = null
        pendingExportAttribution = false
        isChangingExportDirectory = true
        showExportFormatSheet = false
        exportDirectoryPicker.launch(remembered)
      },
      onDismiss = { showExportFormatSheet = false },
    )
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("移入回收站？") },
      text = { Text("将 ${state.selectedNoteIds.size} 条笔记移入回收站，保留 30 天，可在回收站恢复。") },
      confirmButton = {
        TextButton(onClick = { viewModel.deleteSelection(); confirmDelete = false }) { Text("移入回收站") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
  }
}

@Composable
fun MainBottomBar(selectedIndex: Int, onHome: () -> Unit, onTodo: () -> Unit) {
  NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
    NavigationBarItem(
      selected = selectedIndex == 0,
      onClick = onHome,
      icon = { Text("▤") },
      label = { Text("笔记") },
      modifier = Modifier.testTag("nav-home"),
    )
    NavigationBarItem(
      selected = selectedIndex == 1,
      onClick = onTodo,
      icon = { Text("□") },
      label = { Text("待办") },
      modifier = Modifier.testTag("nav-todo"),
    )
  }
}

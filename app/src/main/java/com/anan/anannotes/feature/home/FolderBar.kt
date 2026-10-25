package com.anan.anannotes.feature.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.FolderKind
import com.anan.anannotes.core.model.FolderModel
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.ui.theme.AnAnInputFieldShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBar(
  folders: List<FolderModel>,
  selectedFolderId: String,
  onSelectFolder: (String) -> Unit,
  onCreateFolder: (String) -> Unit,
  onRenameFolder: (String, String) -> Unit,
  onDeleteFolder: (String) -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  var dialog by remember { mutableStateOf<FolderDialog?>(null) }
  var folderName by remember { mutableStateOf("") }

  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()).testTag("folder-strip"),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FolderChip("全部", selectedFolderId == ALL_NOTES_FOLDER_ID, "folder-all", compact = compact, onClick = { onSelectFolder(ALL_NOTES_FOLDER_ID) })
    FolderChip("未分类", selectedFolderId == UNCATEGORIZED_FOLDER_ID, "folder-uncategorized", compact = compact, onClick = { onSelectFolder(UNCATEGORIZED_FOLDER_ID) })
    folders.filter { it.kind == FolderKind.USER }.forEach { folder ->
      FolderChip(
        label = folder.name,
        selected = folder.id == selectedFolderId,
        testTag = "folder-${folder.id}",
        compact = compact,
        onClick = { onSelectFolder(folder.id) },
        onLongClick = {
          folderName = folder.name
          dialog = FolderDialog.EDIT(folder)
        },
      )
    }
    OutlinedButton(
      onClick = {
        folderName = ""
        dialog = FolderDialog.CREATE
      },
      modifier = Modifier.testTag("create-folder-button"),
      shape = RoundedCornerShape(24.dp),
    ) {
      Text("＋ 文件夹")
    }
  }

  when (val currentDialog = dialog) {
    FolderDialog.CREATE -> CreateFolderSheet(
      folderName = folderName,
      onFolderNameChange = { folderName = it },
      onDismiss = { dialog = null },
      onCreate = { onCreateFolder(folderName.trim()); dialog = null },
    )
    is FolderDialog.EDIT ->
      AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text("文件夹") },
        text = {
          androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
              value = folderName,
              onValueChange = { folderName = it },
              singleLine = true,
              label = { Text("名称") },
              shape = AnAnInputFieldShape,
            )
            Text("删除文件夹不会删除笔记，笔记会移动到“未分类”。", style = MaterialTheme.typography.bodySmall)
          }
        },
        confirmButton = {
          TextButton(onClick = { onRenameFolder(currentDialog.folder.id, folderName); dialog = null }) { Text("保存名称") }
        },
        dismissButton = {
          Row {
            TextButton(onClick = { onDeleteFolder(currentDialog.folder.id); dialog = null }) { Text("删除文件夹") }
            TextButton(onClick = { dialog = null }) { Text("取消") }
          }
        },
      )
    null -> Unit
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateFolderSheet(
  folderName: String,
  onFolderNameChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onCreate: () -> Unit,
) {
  val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f),
  ) {
    LaunchedEffect(Unit) {
      focusRequester.requestFocus()
      keyboardController?.show()
    }
    Column(
      modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
        .padding(horizontal = 24.dp).padding(top = 4.dp, bottom = 20.dp)
        .testTag("create-folder-sheet"),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("新建文件夹", style = MaterialTheme.typography.titleLarge)
      OutlinedTextField(
        value = folderName,
        onValueChange = onFolderNameChange,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("folder-name-input"),
        singleLine = true,
        label = { Text("文件夹名称") },
        shape = AnAnInputFieldShape,
      )
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
          onClick = onDismiss,
          modifier = Modifier.weight(1f).height(50.dp).testTag("create-folder-cancel"),
        ) { Text("取消") }
        Button(
          onClick = onCreate,
          enabled = folderName.isNotBlank(),
          modifier = Modifier.weight(1f).height(50.dp).testTag("create-folder-confirm"),
        ) { Text("创建") }
      }
    }
  }
}

@Composable
private fun FolderChip(
  label: String,
  selected: Boolean,
  testTag: String,
  compact: Boolean = false,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
) {
  val chipShape = RoundedCornerShape(24.dp)

  Surface(
    modifier = Modifier
      .clip(chipShape)
      .semantics { this.selected = selected }
      .combinedClickable(
        role = Role.Tab,
        onClick = onClick,
        onLongClick = onLongClick,
      )
      .testTag(testTag),
    shape = chipShape,
    color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
    shadowElevation = 0.dp,
  ) {
    Text(
      label,
      modifier = Modifier.padding(horizontal = if (compact) 13.dp else 18.dp, vertical = if (compact) 7.dp else 12.dp),
      style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge,
    )
  }
}

private sealed interface FolderDialog {
  data object CREATE : FolderDialog
  data class EDIT(val folder: FolderModel) : FolderDialog
}

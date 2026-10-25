package com.anan.anannotes.feature.backup

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.ui.components.CompactPageHeader
import com.anan.anannotes.ui.components.PageScaffold

@Composable
fun BackupRestoreScreen(
  viewModel: BackupRestoreViewModel = viewModel(),
  onBack: () -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val treeGrantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
  val destinationGrantLease = remember(context) {
    PersistedTreeGrantLease { uriString ->
      context.contentResolver.releasePersistableUriPermission(Uri.parse(uriString), treeGrantFlags)
    }
  }
  DisposableEffect(destinationGrantLease) {
    onDispose { destinationGrantLease.close() }
  }
  var destinationUri by remember { mutableStateOf<String?>(null) }
  var sourceUri by remember { mutableStateOf<String?>(null) }
  var createBackupPassword by remember { mutableStateOf("") }
  var confirmCreateBackupPassword by remember { mutableStateOf("") }
  var restoreBackupPassword by remember { mutableStateOf("") }
  var createPrivatePassword by remember { mutableStateOf("") }
  var restorePrivatePassword by remember { mutableStateOf("") }
  var newPrivatePassword by remember { mutableStateOf("") }
  var confirmNewPrivatePassword by remember { mutableStateOf("") }
  var recoveryKeyAcknowledged by remember { mutableStateOf(false) }
  var ordinaryChoice by remember { mutableStateOf(BackupConflictChoice.KEEP_BOTH) }
  var privateChoice by remember { mutableStateOf(BackupConflictChoice.KEEP_BOTH) }

  val destinationPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) {
      runCatching { context.contentResolver.takePersistableUriPermission(uri, treeGrantFlags) }
        .onSuccess {
          destinationGrantLease.record(uri.toString())
          destinationUri = uri.toString()
        }
    }
  }
  val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      viewModel.discardInspection()
      sourceUri = uri.toString()
      restoreBackupPassword = ""
      restorePrivatePassword = ""
      newPrivatePassword = ""
      confirmNewPrivatePassword = ""
      recoveryKeyAcknowledged = false
    }
  }

  PageScaffold(modifier = Modifier.testTag("backup-restore-screen")) {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      CompactPageHeader(title = "加密备份与恢复", onBack = onBack, testTag = "backup-restore-header")

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("创建备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
          Text(
            "笔记与附件会加密后保存到你选择的位置。请记住备份密码；忘记后无法恢复。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
          )
          TextButton(
            onClick = { destinationPicker.launch(null) },
            modifier = Modifier.fillMaxWidth().testTag("backup-choose-destination"),
          ) { Text(if (destinationUri == null) "选择保存文件夹" else "更换保存文件夹") }
          destinationUri?.let { Text("已选择保存位置", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
          CredentialField("备份密码", createBackupPassword, { createBackupPassword = it }, "backup-password")
          CredentialField("再次输入备份密码", confirmCreateBackupPassword, { confirmCreateBackupPassword = it }, "backup-password-confirmation")
          if (state.privateVaultStatusLoaded && state.privateVaultConfigured) {
            CredentialField("私密库密码", createPrivatePassword, { createPrivatePassword = it }, "backup-private-password")
          }
          Button(
            onClick = {
              val target = destinationUri ?: return@Button
              viewModel.createBackup(
                targetUri = target,
              backupPassword = createBackupPassword.toCharArray(),
                privatePassword = if (state.privateVaultConfigured) createPrivatePassword.toCharArray() else null,
              )
              createBackupPassword = ""
              confirmCreateBackupPassword = ""
              createPrivatePassword = ""
            },
            enabled = state.privateVaultStatusLoaded && !state.isBusy && destinationUri != null && createBackupPassword.isNotEmpty() &&
              createBackupPassword == confirmCreateBackupPassword &&
              (!state.privateVaultConfigured || createPrivatePassword.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().testTag("backup-create-action"),
          ) { Text("创建加密备份") }
          if (state.createdFileName != null) {
            Text(
              text = "已保存：${state.createdFileName}" + (state.createdDestinationName?.let { "\n位置：$it" } ?: ""),
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("backup-created-result"),
            )
          }
        }
      }

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("恢复备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
          TextButton(
            onClick = { sourcePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().testTag("backup-choose-source"),
          ) { Text(if (sourceUri == null) "选择备份文件" else "更换备份文件") }
          sourceUri?.let { Text("已选择备份文件", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
          CredentialField("备份密码", restoreBackupPassword, { restoreBackupPassword = it }, "restore-backup-password")
          if (state.privateVaultStatusLoaded && state.privateVaultConfigured) {
            CredentialField("目标私密库密码", restorePrivatePassword, { restorePrivatePassword = it }, "restore-private-password")
          }
          Button(
            onClick = {
              val source = sourceUri ?: return@Button
              viewModel.inspectBackup(
                sourceUri = source,
                backupPassword = restoreBackupPassword.toCharArray(),
                privatePassword = if (state.privateVaultConfigured) restorePrivatePassword.toCharArray() else null,
              )
              restoreBackupPassword = ""
              restorePrivatePassword = ""
            },
            enabled = state.privateVaultStatusLoaded && !state.isBusy && sourceUri != null && restoreBackupPassword.isNotEmpty() &&
              (!state.privateVaultConfigured || restorePrivatePassword.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().testTag("backup-inspect-action"),
          ) { Text("验证并预览") }
        }
      }

      state.preview?.let { preview ->
        val counts = preview.recordSet
        val includesPrivate = counts.privateNotes + counts.privateBlocks + counts.privateTodos +
          counts.privateDrafts + counts.privateDraftBlocks + counts.privateAssets > 0L
        Card(
          modifier = Modifier.testTag("backup-verified-preview"),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
          Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("备份内容", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("普通笔记：${counts.ordinaryNotes} 条 · 文件夹：${counts.ordinaryFolders} 个 · 待办：${counts.ordinaryTodos} 项")
            Text("普通附件：${counts.ordinaryAssets} 张 · 草稿：${counts.ordinaryDrafts} 份")
            if (includesPrivate) {
              Text("私密内容：${counts.privateNotes} 条笔记 · ${counts.privateTodos} 项待办 · ${counts.privateAssets} 张附件")
              Text("私密草稿：${counts.privateDrafts} 份", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
              Text("此备份不包含私密内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val conflicts = preview.conflictSummary
            if (conflicts != null && conflicts.ordinaryConflicts > 0) {
              ConflictChoices(
                title = "普通笔记冲突：${conflicts.ordinaryConflicts} 项",
                selected = ordinaryChoice,
                onSelect = { ordinaryChoice = it },
                testTag = "ordinary-conflict-choice",
              )
            }
            if (conflicts != null && conflicts.privateConflicts > 0) {
              ConflictChoices(
                title = "私密内容冲突：${conflicts.privateConflicts} 项",
                selected = privateChoice,
                onSelect = { privateChoice = it },
                testTag = "private-conflict-choice",
              )
            }
            if (!state.privateVaultConfigured && includesPrivate) {
              CredentialField("为本机私密库设置密码", newPrivatePassword, { newPrivatePassword = it }, "restore-new-private-password")
              CredentialField("再次输入私密库密码", confirmNewPrivatePassword, { confirmNewPrivatePassword = it }, "restore-confirm-private-password")
              if (state.recoveryKey == null) {
                TextButton(onClick = viewModel::createRecoveryKey, modifier = Modifier.testTag("create-restore-recovery-key")) {
                  Text("生成可选恢复密钥")
                }
              } else {
                val recoveryKey = state.recoveryKey.orEmpty()
                Text("恢复密钥（恢复完成后生效）", style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                  Text(recoveryKey, modifier = Modifier.testTag("restore-recovery-key"), color = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Checkbox(
                    checked = recoveryKeyAcknowledged,
                    onCheckedChange = { recoveryKeyAcknowledged = it },
                    modifier = Modifier.testTag("restore-recovery-key-saved"),
                  )
                  Text("我已复制并妥善保存密钥")
                }
              }
            }
            Button(
              onClick = {
                val targetPassword = if (!state.privateVaultConfigured && includesPrivate) newPrivatePassword.toCharArray() else null
                viewModel.restoreBackup(
                  ordinaryChoice = ordinaryChoice,
                  privateChoice = privateChoice,
                  newPrivatePassword = targetPassword,
                  recoveryKeyConfirmed = recoveryKeyAcknowledged,
                )
                newPrivatePassword = ""
                confirmNewPrivatePassword = ""
              },
              enabled = !state.isBusy &&
                (!(!state.privateVaultConfigured && includesPrivate) ||
                  (newPrivatePassword.isNotEmpty() && newPrivatePassword == confirmNewPrivatePassword &&
                    (state.recoveryKey == null || recoveryKeyAcknowledged))),
              modifier = Modifier.fillMaxWidth().testTag("backup-restore-action"),
            ) { Text("开始恢复") }
          }
        }
      }

      if (state.isBusy) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator()
          Spacer(Modifier.padding(horizontal = 6.dp))
          Text(state.busyLabel ?: "正在处理…")
        }
      }
      state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("backup-restore-error")) }
      state.restoreResult?.let { result ->
        Card(
          modifier = Modifier.fillMaxWidth().testTag("backup-restore-success"),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
          Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("恢复完成", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("普通笔记 ${result.ordinaryNotes} 条 · 私密笔记 ${result.privateNotes} 条")
            Text("图片、文件夹、待办、回收站和草稿已按备份内容处理。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.recoveryKey?.let { key ->
              Text("请立即保存新私密库恢复密钥", style = MaterialTheme.typography.titleSmall)
              SelectionContainer { Text(key, color = MaterialTheme.colorScheme.primary) }
            }
            TextButton(onClick = viewModel::dismissRestoreResult, modifier = Modifier.align(Alignment.End)) { Text("完成") }
          }
        }
      }
      Spacer(Modifier.height(10.dp))
    }
  }
}

@Composable
private fun CredentialField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  testTag: String,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth().testTag(testTag),
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    shape = RoundedCornerShape(12.dp),
  )
}

@Composable
private fun ConflictChoices(
  title: String,
  selected: BackupConflictChoice,
  onSelect: (BackupConflictChoice) -> Unit,
  testTag: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      ConflictChoice.entries.forEach { choice ->
        FilterChip(
          selected = selected == choice.value,
          onClick = { onSelect(choice.value) },
          label = { Text(choice.label) },
          modifier = Modifier.testTag("$testTag-${choice.value.name.lowercase()}"),
        )
      }
    }
  }
}

private enum class ConflictChoice(val value: BackupConflictChoice, val label: String) {
  KEEP_CURRENT(BackupConflictChoice.KEEP_CURRENT, "保留当前"),
  USE_BACKUP(BackupConflictChoice.USE_BACKUP, "使用备份"),
  KEEP_BOTH(BackupConflictChoice.KEEP_BOTH, "两者都保留"),
}

package com.anan.anannotes.feature.privatemodule

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.anan.anannotes.ui.theme.AnAnInputFieldShape
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.selection.SelectionContainer
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.ui.components.PageScaffold
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private val PrivateGraphite = Color(0xFF171B1F)
private val PrivateSurface = Color(0xFF22272C)
private val PrivateSurfaceVariant = Color(0xFF2B3137)
private val PrivateText = Color(0xFFE8EAEC)
private val PrivateMuted = Color(0xFFA8B0B7)
private val PrivateOutline = Color(0xFF454D55)
private val PrivateSlate = Color(0xFFA6B1BB)
private val PrivateAccessBackground = Color(0xFF343A41)
private val PrivateAccessSurface = Color(0xFF3C434B)
private val PrivateAccessSurfaceVariant = Color(0xFF444C55)
private val PrivateAccessText = Color(0xFFF2F4F6)
private val PrivateAccessMuted = Color(0xFFD5DCE1)
private val PrivateAccessOutline = Color(0xFF929DA7)
private val PrivateAccessAccent = Color(0xFFB8C8D6)

private val PrivateColorScheme = darkColorScheme(
  primary = PrivateSlate,
  onPrimary = PrivateGraphite,
  secondary = PrivateMuted,
  onSecondary = PrivateGraphite,
  background = PrivateGraphite,
  onBackground = PrivateText,
  surface = PrivateSurface,
  onSurface = PrivateText,
  surfaceVariant = PrivateSurfaceVariant,
  onSurfaceVariant = PrivateMuted,
  outline = PrivateOutline,
  outlineVariant = PrivateOutline,
  error = PrivateMuted,
  onError = PrivateGraphite,
)

private val PrivateAccessColorScheme = darkColorScheme(
  primary = PrivateAccessAccent,
  onPrimary = PrivateGraphite,
  secondary = PrivateAccessMuted,
  onSecondary = PrivateGraphite,
  background = PrivateAccessBackground,
  onBackground = PrivateAccessText,
  surface = PrivateAccessSurface,
  onSurface = PrivateAccessText,
  surfaceVariant = PrivateAccessSurfaceVariant,
  onSurfaceVariant = PrivateAccessMuted,
  outline = PrivateAccessOutline,
  outlineVariant = PrivateAccessOutline,
  error = PrivateAccessMuted,
  onError = PrivateGraphite,
  errorContainer = PrivateAccessSurfaceVariant,
  onErrorContainer = PrivateAccessText,
)

@Composable
fun PrivateUnlockScreen(
  onBack: () -> Unit,
  viewModelOverride: PrivateModuleViewModel? = null,
  onPrivateExportTreeSelected: ((Uri) -> Unit)? = null,
) {
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }
  val lifecycleOwner = LocalLifecycleOwner.current
  val viewModel: PrivateModuleViewModel = viewModelOverride ?: viewModel(key = "private-module")
  val state by viewModel.state.collectAsStateWithLifecycle()
  val isDebuggable = activity?.applicationInfo?.let {
    it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
  } == true
  var password by remember { mutableStateOf("") }
  var confirmation by remember { mutableStateOf("") }
  var recoveryInput by remember { mutableStateOf("") }
  var useRecoveryKey by remember { mutableStateOf(false) }
  var showPrivateExportFormatSheet by remember { mutableStateOf(false) }
  val pendingPrivateExport = rememberPrivatePendingExportState()
  val privateExportSnackbar = remember { SnackbarHostState() }
  val copyFeedbackScope = rememberCoroutineScope()
  val privateExportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    val request = pendingPrivateExport.value
    val format = request.format
    val noteId = request.noteId
    if (uri != null && format != null && noteId != null) {
      onPrivateExportTreeSelected?.invoke(uri)
      pendingPrivateExport.value = request.copy(target = OutputTarget.DocumentTree(uri.toString()))
    } else {
      pendingPrivateExport.value = PrivatePendingExportState()
    }
  }

  DisposableEffect(activity) {
    val window = activity?.window
    val originalFlags = window?.attributes?.flags ?: 0
    val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
    onDispose {
      if (window != null) {
        window.setFlags(
          restoreWindowFlag(window.attributes.flags, originalFlags, secureFlag),
          secureFlag,
        )
      }
    }
  }

  SideEffect {
    applyPrivateScreenshotPolicy(
      window = activity?.window,
      isDebuggable = isDebuggable,
    )
  }

  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> {
          // Keep app-switcher snapshots protected even in the development build.
          activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
          viewModel.lock()
        }
        Lifecycle.Event.ON_START -> applyPrivateScreenshotPolicy(
          window = activity?.window,
          isDebuggable = isDebuggable,
        )
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.lock()
    }
  }

  LaunchedEffect(Unit) { viewModel.enterModule() }
  LaunchedEffect(state.status) {
    if (state.status != PrivateModuleStatus.UNLOCKED) {
      password = ""
      confirmation = ""
      recoveryInput = ""
      useRecoveryKey = false
    }
  }

  LaunchedEffect(state.status, pendingPrivateExport.value) {
    val request = pendingPrivateExport.value
    val format = request.format
    val noteId = request.noteId
    val target = request.target
    if (state.status == PrivateModuleStatus.UNLOCKED && format != null && noteId != null && target != null) {
      pendingPrivateExport.value = PrivatePendingExportState()
      viewModel.beginPrivateExport(noteId, format, target, request.includeAttribution)
    }
  }

  LaunchedEffect(viewModel, state.status) {
    if (state.status != PrivateModuleStatus.UNLOCKED) return@LaunchedEffect
    val completedFiles = mutableListOf<PrivateExportEvent.ItemCompleted>()
    var failedItem: PrivateExportEvent.ItemFailed? = null
    viewModel.privateExportEvents.collect { event ->
      when (event) {
        is PrivateExportEvent.ItemCompleted -> completedFiles += event
        is PrivateExportEvent.ItemFailed -> failedItem = event
        is PrivateExportEvent.Progress, PrivateExportEvent.AuthorizationRequired -> Unit
        PrivateExportEvent.Busy -> privateExportSnackbar.showSnackbar("已有私密导出正在进行，请稍候。")
        is PrivateExportEvent.Completed -> {
          val failure = failedItem
          val firstFile = completedFiles.firstOrNull()
          val destination = firstFile?.destinationName?.let { "至$it" }.orEmpty()
          val message = when {
            failure != null -> "导出失败 ${failure.code}：${failure.message}（已完成 ${completedFiles.size} 个文件）"
            event.succeeded == 1 && firstFile != null -> "已导出：${firstFile.fileName} $destination"
            event.succeeded > 1 -> "已导出 ${event.succeeded} 个文件 $destination"
            else -> null
          }
          if (message != null) {
            privateExportSnackbar.showSnackbar(
              message,
              duration = if (failure != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
          }
          if (event.historyFailures > 0) {
            privateExportSnackbar.showSnackbar("文件已导出，但私密导出记录暂时无法保存。", duration = SnackbarDuration.Long)
          }
          completedFiles.clear()
          failedItem = null
        }
      }
    }
  }

  BackHandler(enabled = !state.isEditorVisible && state.recoveryKeyToAcknowledge == null) {
    viewModel.lock()
    onBack()
  }

  val isPrivateAccessFlow = state.status != PrivateModuleStatus.UNLOCKED || state.recoveryKeyToAcknowledge != null
  MaterialTheme(
    colorScheme = if (isPrivateAccessFlow) PrivateAccessColorScheme else PrivateColorScheme,
  ) {
    PageScaffold(
      modifier = Modifier.testTag("private-module-root"),
      containerColor = MaterialTheme.colorScheme.background,
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize().testTag("private-safe-content")) {
        when {
          state.status == PrivateModuleStatus.UNKNOWN -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
          }
          state.recoveryKeyToAcknowledge != null -> RecoveryKeyConfirmation(
            recoveryKey = state.recoveryKeyToAcknowledge,
            onAcknowledge = viewModel::acknowledgeRecoveryKey,
          )
          state.status == PrivateModuleStatus.UNLOCKED && state.isEditorVisible -> PrivateEditorScreen(
            note = state.editingNote,
            isSaving = state.isSavingNote,
            errorMessage = state.editorError,
            onBack = viewModel::closeEditor,
            onSave = viewModel::saveNote,
            onDraftChange = viewModel::onPrivateEditorChanged,
            onSkinChange = viewModel::onPrivateEditorSkinChanged,
            onExport = { showPrivateExportFormatSheet = true },
            onCopyText = { text ->
              val message = runCatching {
                PrivateClipboardWriter.copy(context.applicationContext, text)
              }.fold(
                onSuccess = { "已复制全文" },
                onFailure = { "复制失败，请稍后重试。" },
              )
              copyFeedbackScope.launch { privateExportSnackbar.showSnackbar(message) }
            },
            pendingRecoveryDraft = state.pendingEditorRecovery,
            onRecoveryRestored = viewModel::acknowledgeEditorRecovery,
            onRecoveryDiscard = viewModel::discardEditorRecovery,
            privateOcrProgress = state.privateOcrProgress,
            privateOcrResults = state.privateOcrResults,
            onImportImage = viewModel::importPrivateImage,
            onCancelOcr = viewModel::cancelPrivateOcr,
            onRetryOcr = viewModel::retryPrivateOcr,
          )
          state.status == PrivateModuleStatus.UNLOCKED -> PrivateContent(
            state = state,
            onBack = {
              viewModel.lock()
              onBack()
            },
            onLock = viewModel::lock,
            onSelectSection = viewModel::showSection,
            onCreateNote = viewModel::beginNewNote,
            onOpenNote = viewModel::openNote,
            onShowCompletedChange = viewModel::setShowCompletedTodos,
            onTodoCompletedChange = viewModel::setTodoCompleted,
            onShowExportHistory = viewModel::showPrivateExportHistory,
            onHideExportHistory = viewModel::hidePrivateExportHistory,
          )
          else -> PrivateAccessForm(
            firstSetup = state.status == PrivateModuleStatus.SETUP_REQUIRED,
            errorMessage = state.errorMessage,
            retryAfterMillis = state.retryAfterMillis,
            isBusy = state.isBusy,
            recoveryKeyReady = state.recoveryKeyReady,
            useRecoveryKey = useRecoveryKey,
            onUseRecoveryKeyChange = { useRecoveryKey = it; recoveryInput = ""; password = "" },
            password = password,
            onPasswordChange = { password = it },
            confirmation = confirmation,
            onConfirmationChange = { confirmation = it },
            recoveryInput = recoveryInput,
            onRecoveryInputChange = { recoveryInput = it },
            pendingPrivateExportResume = pendingPrivateExport.value.target != null,
            onCancelPendingPrivateExport = {
              pendingPrivateExport.value = PrivatePendingExportState()
            },
            onBack = {
              viewModel.lock()
              onBack()
            },
            onUnlock = {
              viewModel.unlock(if (useRecoveryKey) recoveryInput else password, useRecoveryKey)
              password = ""
              recoveryInput = ""
            },
            onSetup = { createRecovery ->
              viewModel.setup(password, confirmation, createRecovery)
              password = ""
              confirmation = ""
            },
          )
        }
      }
      SnackbarHost(
        hostState = privateExportSnackbar,
        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp),
      )
      }
    }
  }
  if (showPrivateExportFormatSheet && state.status == PrivateModuleStatus.UNLOCKED && state.isEditorVisible) {
    PrivateExportFormatSheet(
      onSelect = { format, includeAttribution ->
        showPrivateExportFormatSheet = false
        val noteId = state.editingNote?.id ?: return@PrivateExportFormatSheet
        if (format.isPrivatePngFormat()) {
          viewModel.beginPrivateExport(noteId, format, OutputTarget.MediaStorePictures, includeAttribution)
        } else {
          pendingPrivateExport.value = PrivatePendingExportState(
            format = format,
            includeAttribution = includeAttribution,
            noteId = noteId,
          )
          privateExportDirectoryPicker.launch(null)
        }
      },
      onDismiss = { showPrivateExportFormatSheet = false },
    )
  }
  if (state.isPrivateExportPasswordPromptVisible && state.status == PrivateModuleStatus.UNLOCKED) {
    PrivateExportPasswordDialog(
      errorMessage = state.privateExportError,
      retryAfterMillis = state.privateExportRetryAfterMillis,
      onSubmit = viewModel::authorizePrivateExport,
      onDismiss = viewModel::cancelPrivateExportPrompt,
    )
  }
  if (state.isPrivateExportWarningVisible && state.status == PrivateModuleStatus.UNLOCKED) {
    PrivateExportPlaintextWarningDialog(
      errorMessage = state.privateExportError,
      isExporting = state.isPrivateExporting,
      onConfirm = viewModel::confirmPrivateExportWarning,
      onDismiss = viewModel::cancelPrivateExportPrompt,
    )
  }
}

@Composable
private fun PrivateAccessForm(
  firstSetup: Boolean,
  errorMessage: String?,
  retryAfterMillis: Long,
  isBusy: Boolean,
  recoveryKeyReady: Boolean,
  useRecoveryKey: Boolean,
  onUseRecoveryKeyChange: (Boolean) -> Unit,
  password: String,
  onPasswordChange: (String) -> Unit,
  confirmation: String,
  onConfirmationChange: (String) -> Unit,
  recoveryInput: String,
  onRecoveryInputChange: (String) -> Unit,
  pendingPrivateExportResume: Boolean,
  onCancelPendingPrivateExport: () -> Unit,
  onBack: () -> Unit,
  onUnlock: () -> Unit,
  onSetup: (Boolean) -> Unit,
) {
  var createRecoveryKey by remember { mutableStateOf(true) }
  var showRecoveryKey by remember { mutableStateOf(false) }
  LaunchedEffect(recoveryKeyReady) { if (recoveryKeyReady) createRecoveryKey = true }
  LaunchedEffect(useRecoveryKey) { showRecoveryKey = false }

  Column(modifier = Modifier.fillMaxSize().imePadding().testTag("private-access-form")) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = onBack, modifier = Modifier.testTag("private-back-button")) { Text("返回") }
      Text("私密笔记", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
    }
    Column(
      modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 34.dp),
    ) {
      Text(
        text = when {
          firstSetup -> "设置访问密码"
          useRecoveryKey -> "使用恢复密钥"
          else -> "输入密码"
        },
        style = MaterialTheme.typography.headlineSmall,
      )
      if (pendingPrivateExportResume && !firstSetup) {
        Card(
          modifier = Modifier.fillMaxWidth().padding(top = 18.dp).testTag("private-export-resume-notice"),
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("保存位置已选择", style = MaterialTheme.typography.titleSmall)
            Text(
              "私密区域已自动锁定。重新解锁后继续导出。",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(
              onClick = onCancelPendingPrivateExport,
              modifier = Modifier.align(Alignment.End).testTag("private-export-resume-cancel"),
            ) { Text("取消本次导出") }
          }
        }
      }
      Spacer(Modifier.height(22.dp))
      if (firstSetup) {
        CredentialField("密码", password, onPasswordChange, password = true, tag = "private-password-field")
        Spacer(Modifier.height(12.dp))
        CredentialField("确认密码", confirmation, onConfirmationChange, password = true, tag = "private-confirm-field")
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("生成恢复密钥", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
          Switch(
            checked = recoveryKeyReady || createRecoveryKey,
            onCheckedChange = { createRecoveryKey = it },
            enabled = !recoveryKeyReady,
            modifier = Modifier.testTag("private-recovery-toggle"),
          )
        }
        if (recoveryKeyReady) {
          Text(
            "恢复密钥已确认，请重新输入密码完成设置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
          )
        } else if (!createRecoveryKey) {
          Text(
            "忘记密码且没有恢复密钥时，私密数据无法恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
          )
        }
      } else {
        if (useRecoveryKey) {
          CredentialField(
            "恢复密钥",
            recoveryInput,
            onRecoveryInputChange,
            password = !showRecoveryKey,
            tag = "private-recovery-field",
            onToggleVisibility = { showRecoveryKey = !showRecoveryKey },
          )
        } else {
          CredentialField("密码", password, onPasswordChange, password = true, tag = "private-password-field")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
          Text("使用恢复密钥", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
          Switch(
            checked = useRecoveryKey,
            onCheckedChange = onUseRecoveryKeyChange,
            modifier = Modifier.testTag("private-use-recovery-toggle"),
          )
        }
      }

      if (retryAfterMillis > 0L && !firstSetup) {
        Text(
          "请稍后重试（${((retryAfterMillis + 999L) / 1_000L)} 秒）",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 12.dp).testTag("private-retry-delay"),
        )
      }
      errorMessage?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 12.dp).testTag("private-auth-error"),
        )
      }
    }
    Button(
      onClick = { if (firstSetup) onSetup(createRecoveryKey) else onUnlock() },
      enabled = !isBusy && (firstSetup || retryAfterMillis <= 0L),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp).height(52.dp).testTag("private-auth-submit"),
      shape = RoundedCornerShape(16.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      ),
    ) {
      if (isBusy) CircularProgressIndicator(
        modifier = Modifier.height(22.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        strokeWidth = 2.dp,
      )
      else Text(if (firstSetup) "创建私密空间" else "解锁")
    }
  }
}

@Composable
private fun CredentialField(
  label: String,
  value: String,
  onValueChange: (String) -> Unit,
  password: Boolean,
  tag: String,
  onToggleVisibility: (() -> Unit)? = null,
) {
  OutlinedTextField(
    value = value,
    onValueChange = onValueChange,
    modifier = Modifier.fillMaxWidth().testTag(tag),
    label = { Text(label) },
    singleLine = true,
    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions = KeyboardOptions(
      keyboardType = if (password) KeyboardType.Password else KeyboardType.Ascii,
      autoCorrectEnabled = false,
    ),
    trailingIcon = onToggleVisibility?.let { toggle ->
      {
        TextButton(onClick = toggle) { Text(if (password) "显示" else "隐藏") }
      }
    },
    shape = AnAnInputFieldShape,
    colors = OutlinedTextFieldDefaults.colors(
      focusedTextColor = PrivateAccessFieldPalette.text,
      unfocusedTextColor = PrivateAccessFieldPalette.text,
      focusedContainerColor = PrivateAccessFieldPalette.container,
      unfocusedContainerColor = PrivateAccessFieldPalette.container,
      focusedLabelColor = PrivateAccessFieldPalette.label,
      unfocusedLabelColor = PrivateAccessFieldPalette.label,
      focusedBorderColor = PrivateAccessFieldPalette.focusedOutline,
      unfocusedBorderColor = PrivateAccessFieldPalette.outline,
      cursorColor = PrivateAccessFieldPalette.cursor,
    ),
  )
}

@Composable
private fun RecoveryKeyConfirmation(
  recoveryKey: String?,
  onAcknowledge: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp, vertical = 20.dp).testTag("private-recovery-confirmation"),
    verticalArrangement = Arrangement.Center,
  ) {
    Text("保存恢复密钥", style = MaterialTheme.typography.headlineSmall)
    Text(
      "保存后需要重新输入密码完成设置；恢复密钥只显示这一次。",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 10.dp),
    )
    Card(
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      shape = RoundedCornerShape(18.dp),
    ) {
      SelectionContainer {
        Text(
          recoveryKey.orEmpty(),
          style = MaterialTheme.typography.titleLarge,
          fontFamily = FontFamily.Monospace,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("private-recovery-key"),
        )
      }
    }
    Button(
      onClick = onAcknowledge,
      modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(52.dp).testTag("private-recovery-acknowledge"),
      shape = RoundedCornerShape(16.dp),
    ) { Text("我已安全保存") }
  }
}

@Composable
private fun PrivateContent(
  state: PrivateModuleUiState,
  onBack: () -> Unit,
  onLock: () -> Unit,
  onSelectSection: (PrivateSection) -> Unit,
  onCreateNote: () -> Unit,
  onOpenNote: (PrivateNoteId) -> Unit,
  onShowCompletedChange: (Boolean) -> Unit,
  onTodoCompletedChange: (PrivateTodoItem, Boolean) -> Unit,
  onShowExportHistory: () -> Unit,
  onHideExportHistory: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxSize().testTag("screen-private")) {
    PrivateModuleHeader(
      onBack = if (state.isPrivateExportHistoryVisible) onHideExportHistory else onBack,
      onLock = onLock,
      onShowExportHistory = onShowExportHistory,
      modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
    )

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      if (state.isPrivateExportHistoryVisible) {
        PrivateExportHistoryScreen(state.privateExportHistory, onHideExportHistory)
      } else when (state.section) {
        PrivateSection.NOTES -> PrivateHomeScreen(
          notes = state.notes,
          onCreateNote = onCreateNote,
          onOpenNote = onOpenNote,
        )
        PrivateSection.TODOS -> PrivateTodoScreen(
          todos = state.todos,
          showCompleted = state.showCompletedTodos,
          onShowCompletedChange = onShowCompletedChange,
          onTodoCompletedChange = onTodoCompletedChange,
          onOpenNote = onOpenNote,
        )
      }
      state.errorMessage?.let { message ->
        Text(
          message,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface).padding(8.dp),
        )
      }
    }
    if (!state.isPrivateExportHistoryVisible) {
      PrivateModuleNavigation(
        section = state.section,
        onSelectSection = onSelectSection,
        modifier = Modifier.testTag("private-navigation"),
      )
    }
  }
}

private fun ExportFormat.isPrivatePngFormat(): Boolean = this in setOf(
  ExportFormat.PNG_PAGED_600_DPI,
  ExportFormat.PNG_LONG_IMAGE,
  ExportFormat.PNG_SOCIAL_SQUARE,
  ExportFormat.PNG_SOCIAL_PORTRAIT,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

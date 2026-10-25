package com.anan.anannotes.feature.privatemodule

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.model.ExportFormat
import com.anan.anannotes.core.model.BlockType
import com.anan.anannotes.core.model.DEFAULT_NOTE_SKIN_ID
import com.anan.anannotes.core.model.NoteBlockDraft
import com.anan.anannotes.core.model.OcrStatus
import com.anan.anannotes.core.ImageBlockRules
import com.anan.anannotes.core.model.BlockAlignment
import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.feature.ocr.OcrBlockInsertionRules
import com.anan.anannotes.feature.ocr.OcrImportMode
import com.anan.anannotes.feature.ocr.OcrJobRules
import com.anan.anannotes.feature.ocr.OcrProgressListener
import com.anan.anannotes.feature.ocr.OcrProgressStage
import com.anan.anannotes.feature.ocr.TesseractOcrEngine
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.PrivateAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

enum class PrivateSection { NOTES, TODOS }

data class PrivateModuleUiState(
  val status: PrivateModuleStatus = PrivateModuleStatus.UNKNOWN,
  val section: PrivateSection = PrivateSection.NOTES,
  val notes: List<PrivateNotePreview> = emptyList(),
  val todos: List<PrivateTodoItem> = emptyList(),
  val showCompletedTodos: Boolean = false,
  val isBusy: Boolean = false,
  val retryAfterMillis: Long = 0L,
  val errorMessage: String? = null,
  val recoveryKeyToAcknowledge: String? = null,
  val recoveryKeyReady: Boolean = false,
  val isEditorVisible: Boolean = false,
  val isCreatingNote: Boolean = false,
  val editingNote: PrivateNoteDocument? = null,
  val isSavingNote: Boolean = false,
  val editorError: String? = null,
  val recoverableEditorDrafts: List<PrivateEditSnapshot> = emptyList(),
  val pendingEditorRecovery: PrivateEditSnapshot? = null,
  val activeEditorDraftId: String? = null,
  val activeEditorLocalRevision: Long = 0L,
  val activeEditorSkinId: String = DEFAULT_NOTE_SKIN_ID,
  val isPrivateExportPasswordPromptVisible: Boolean = false,
  val isPrivateExportWarningVisible: Boolean = false,
  val isPrivateExportHistoryVisible: Boolean = false,
  val isPrivateExporting: Boolean = false,
  val privateExportError: String? = null,
  val privateExportRetryAfterMillis: Long = 0L,
  val privateExportHistory: List<PrivateExportHistoryRecord> = emptyList(),
  val privateOcrProgress: Map<String, PrivateOcrProgress> = emptyMap(),
  val privateOcrResults: Map<String, NoteBlockDraft> = emptyMap(),
)

data class PrivateOcrProgress(val percent: Int, val stage: String)

class PrivateModuleViewModel private constructor(
  application: Application,
  privateStoreOverride: PrivateStore?,
  throttleStore: PrivateAuthThrottleStore,
) : AndroidViewModel(application) {
  constructor(application: Application) : this(
    application,
    null,
    SharedPreferencesPrivateAuthThrottleStore(application),
  )

  internal constructor(
    application: Application,
    privateStoreRoot: File,
    throttleStore: PrivateAuthThrottleStore,
  ) : this(application, PrivateStore(privateStoreRoot), throttleStore)

  private val privateStore = privateStoreOverride ?: PrivateStoreInstances.get(application.filesDir)
  private val repository = PrivateRepository(privateStore)
  private val privateExporter = PrivateExportServiceFactory.create(application, repository, privateStore)
  private val throttle = PrivateAuthThrottle(throttleStore)
  private val privateExportReauthentication = PrivateExportReauthentication(throttle)
  private val random = SecureRandom()
  private val authenticationMutex = Mutex()
  private val mutableState = MutableStateFlow(PrivateModuleUiState())
  val state = mutableState.asStateFlow()
  private var access: PrivateAccess? = null
  private var pendingRecoveryKey: String? = null
  private var sessionEpoch = 0L
  private var authenticationRequest = 0L
  private var privateDraftWriteJob: Job? = null
  private var latestPrivateEditorSnapshot: PrivateEditSnapshot? = null
  private val privateDraftWriteMutex = Mutex()
  private val mutablePrivateExportEvents = MutableSharedFlow<PrivateExportEvent>(extraBufferCapacity = 32)
  val privateExportEvents = mutablePrivateExportEvents.asSharedFlow()
  private var pendingPrivateExportRequest: PrivateExportRequest? = null
  private var pendingPrivateExportGrant: PrivateExportGrant? = null
  private var privateExportJob: Job? = null
  private var privateExportThrottleJob: Job? = null
  private val privateOcrJobs = mutableMapOf<String, Job>()

  fun enterModule() {
    viewModelScope.launch {
      try {
        val status = repository.refreshStatus()
        mutableState.update { it.copy(status = status) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(status = PrivateModuleStatus.LOCKED, errorMessage = GENERIC_ACCESS_ERROR) }
      }
      refreshThrottleWait()
    }
  }

  fun setup(password: String, confirmation: String, createRecoveryKey: Boolean) {
    if (password != confirmation) {
      mutableState.update { it.copy(errorMessage = "两次输入的密码不一致") }
      return
    }
    if (password.isEmpty()) {
      mutableState.update { it.copy(errorMessage = "请输入密码") }
      return
    }
    if (createRecoveryKey && !mutableState.value.recoveryKeyReady) {
      val recoveryKey = generateRecoveryKey()
      pendingRecoveryKey = recoveryKey
      mutableState.update {
        it.copy(recoveryKeyToAcknowledge = recoveryKey, errorMessage = null, isBusy = false)
      }
      return
    }
    val credential = password.toCharArray()
    val requestId = ++authenticationRequest
    val requestEpoch = sessionEpoch
    mutableState.update { it.copy(isBusy = true, errorMessage = null) }
    viewModelScope.launch {
      authenticationMutex.withLock {
        try {
          val recoveryText = if (createRecoveryKey) pendingRecoveryKey else null
          val newAccess = repository.setup(credential, recoveryText?.toCharArray())
          if (!isCurrentAuthentication(requestId, requestEpoch)) {
            repository.revoke(newAccess)
            mutableState.update {
              if (it.status == PrivateModuleStatus.SETUP_REQUIRED) it.copy(status = PrivateModuleStatus.LOCKED) else it
            }
            return@withLock
          }
          access = newAccess
          pendingRecoveryKey = null
          withContext(Dispatchers.IO) { runCatching { throttle.recordSuccess() } }
          loadPrivateContent(newAccess, requestEpoch)
          if (isCurrentSession(newAccess, requestEpoch)) {
            mutableState.update {
              it.copy(
                isBusy = false,
                recoveryKeyToAcknowledge = null,
                recoveryKeyReady = false,
                errorMessage = null,
              )
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          if (isCurrentAuthentication(requestId, requestEpoch)) {
            mutableState.update { it.copy(isBusy = false, errorMessage = "无法完成设置，请检查存储空间后重试") }
          }
        } finally {
          credential.fill('\u0000')
        }
      }
    }
  }

  fun unlock(credentialText: String, useRecoveryKey: Boolean) {
    if (credentialText.isEmpty()) {
      mutableState.update { it.copy(errorMessage = "请输入凭据") }
      return
    }
    val wait = mutableState.value.retryAfterMillis
    if (wait > 0L) {
      mutableState.update { it.copy(errorMessage = "请稍后重试", retryAfterMillis = wait) }
      return
    }
    val credential = credentialText.toCharArray()
    val requestId = ++authenticationRequest
    val requestEpoch = sessionEpoch
    mutableState.update { it.copy(isBusy = true, errorMessage = null) }
    viewModelScope.launch {
      authenticationMutex.withLock {
        try {
          val persistedWait = withContext(Dispatchers.IO) { throttle.remainingMillis(System.currentTimeMillis()) }
          if (persistedWait > 0L) {
            if (isCurrentAuthentication(requestId, requestEpoch)) {
              mutableState.update { it.copy(isBusy = false, errorMessage = "请稍后重试", retryAfterMillis = persistedWait) }
              refreshThrottleWait()
            }
            return@withLock
          }
          val unlocked = if (useRecoveryKey) {
            repository.unlockWithRecoveryKey(credential)
          } else {
            repository.unlockWithPassword(credential)
          }
          if (!isCurrentAuthentication(requestId, requestEpoch)) {
            repository.revoke(unlocked)
            return@withLock
          }
          access = unlocked
          withContext(Dispatchers.IO) { runCatching { throttle.recordSuccess() } }
          loadPrivateContent(unlocked, requestEpoch)
          if (isCurrentSession(unlocked, requestEpoch)) {
            mutableState.update { it.copy(isBusy = false, errorMessage = null, retryAfterMillis = 0L) }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: PrivateAuthenticationException) {
          val retryAfter = withContext(Dispatchers.IO) {
            throttle.recordFailure(System.currentTimeMillis())
            throttle.remainingMillis(System.currentTimeMillis())
          }
          if (isCurrentAuthentication(requestId, requestEpoch)) {
            mutableState.update {
              it.copy(isBusy = false, errorMessage = "凭据无法验证", retryAfterMillis = retryAfter)
            }
            refreshThrottleWait()
          }
        } catch (_: Exception) {
          if (isCurrentAuthentication(requestId, requestEpoch)) {
            mutableState.update { it.copy(isBusy = false, errorMessage = GENERIC_ACCESS_ERROR) }
          }
        } finally {
          credential.fill('\u0000')
        }
      }
    }
  }

  fun acknowledgeRecoveryKey() {
    mutableState.update { it.copy(recoveryKeyToAcknowledge = null, recoveryKeyReady = true) }
  }

  fun lock() {
    val previous = mutableState.value
    sessionEpoch += 1L
    authenticationRequest += 1L
    privateDraftWriteJob?.cancel()
    privateDraftWriteJob = null
    privateOcrJobs.values.forEach(Job::cancel)
    privateOcrJobs.clear()
    privateExportJob?.cancel()
    privateExportJob = null
    privateExportThrottleJob?.cancel()
    privateExportThrottleJob = null
    pendingPrivateExportGrant = null
    pendingPrivateExportRequest = null
    latestPrivateEditorSnapshot = null
    repository.lock()
    access = null
    pendingRecoveryKey = null
    mutableState.value = previous.copy(
      status = if (previous.status == PrivateModuleStatus.SETUP_REQUIRED) {
        PrivateModuleStatus.SETUP_REQUIRED
      } else {
        PrivateModuleStatus.LOCKED
      },
      section = PrivateSection.NOTES,
      notes = emptyList(),
      todos = emptyList(),
      showCompletedTodos = false,
      isBusy = false,
      errorMessage = null,
      recoveryKeyToAcknowledge = null,
      recoveryKeyReady = false,
      isEditorVisible = false,
      isCreatingNote = false,
      editingNote = null,
      isSavingNote = false,
      editorError = null,
      recoverableEditorDrafts = emptyList(),
      pendingEditorRecovery = null,
      activeEditorDraftId = null,
      activeEditorLocalRevision = 0L,
      activeEditorSkinId = DEFAULT_NOTE_SKIN_ID,
      isPrivateExportPasswordPromptVisible = false,
      isPrivateExportWarningVisible = false,
      isPrivateExportHistoryVisible = false,
      isPrivateExporting = false,
      privateExportError = null,
      privateExportRetryAfterMillis = 0L,
      privateExportHistory = emptyList(),
      privateOcrProgress = emptyMap(),
      privateOcrResults = emptyMap(),
    )
  }

  fun beginPrivateExport(
    noteId: PrivateNoteId,
    format: ExportFormat,
    target: OutputTarget,
    includeAttribution: Boolean = false,
  ) {
    val currentAccess = access ?: return
    if (!currentAccess.isActive) return
    val epoch = sessionEpoch
    viewModelScope.launch {
      try {
        if (repository.read(currentAccess, noteId) == null || !isCurrentSession(currentAccess, epoch)) return@launch
        val retryAfter = withContext(Dispatchers.IO) { throttle.remainingMillis(System.currentTimeMillis()) }
        pendingPrivateExportGrant = null
        pendingPrivateExportRequest = PrivateExportRequest(noteId, format, target, includeAttribution)
        mutableState.update {
          it.copy(
            isPrivateExportPasswordPromptVisible = true,
            isPrivateExportWarningVisible = false,
            privateExportError = null,
            privateExportRetryAfterMillis = retryAfter,
          )
        }
        if (retryAfter > 0L) startPrivateExportThrottleCountdown(currentAccess, epoch, retryAfter)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) mutableState.update { it.copy(errorMessage = GENERIC_ACCESS_ERROR) }
      }
    }
  }

  fun authorizePrivateExport(password: CharArray) {
    val currentAccess = access
    val request = pendingPrivateExportRequest
    if (currentAccess == null || request == null || !currentAccess.isActive) {
      password.fill('\u0000')
      cancelPrivateExportPrompt()
      return
    }
    val epoch = sessionEpoch
    mutableState.update { it.copy(privateExportError = null) }
    viewModelScope.launch {
      try {
        val result = authenticationMutex.withLock {
          withContext(Dispatchers.IO) {
            privateExportReauthentication.authorize(currentAccess, password) { candidate ->
              repository.authorizeExport(currentAccess, candidate, request.scope())
            }
          }
        }
        if (!isCurrentSession(currentAccess, epoch) || pendingPrivateExportRequest != request) return@launch
        when (result) {
          is PrivateExportAuthorizationResult.Authorized -> {
            privateExportThrottleJob?.cancel()
            privateExportThrottleJob = null
            pendingPrivateExportGrant = result.grant
            mutableState.update {
              it.copy(
                isPrivateExportPasswordPromptVisible = false,
                isPrivateExportWarningVisible = true,
                privateExportError = null,
                privateExportRetryAfterMillis = 0L,
              )
            }
          }
          is PrivateExportAuthorizationResult.Rejected -> {
            val error = if (result.retryAfterMillis > 0L) "尝试次数过多，请稍后重试" else "密码不正确，请重新输入"
            mutableState.update {
              it.copy(privateExportError = error, privateExportRetryAfterMillis = result.retryAfterMillis)
            }
            if (result.retryAfterMillis > 0L) startPrivateExportThrottleCountdown(currentAccess, epoch, result.retryAfterMillis)
          }
          is PrivateExportAuthorizationResult.Throttled -> {
            mutableState.update {
              it.copy(privateExportError = "尝试次数过多，请稍后重试", privateExportRetryAfterMillis = result.retryAfterMillis)
            }
            startPrivateExportThrottleCountdown(currentAccess, epoch, result.retryAfterMillis)
          }
          PrivateExportAuthorizationResult.Inactive -> cancelPrivateExportPrompt()
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) mutableState.update { it.copy(privateExportError = GENERIC_ACCESS_ERROR) }
      } finally {
        password.fill('\u0000')
      }
    }
  }

  fun confirmPrivateExportWarning() {
    val currentAccess = access ?: return
    val request = pendingPrivateExportRequest ?: return
    val grant = pendingPrivateExportGrant ?: run {
      mutableState.update { it.copy(isPrivateExportWarningVisible = false, isPrivateExportPasswordPromptVisible = true) }
      return
    }
    if (!currentAccess.isActive) {
      cancelPrivateExportPrompt()
      return
    }
    val epoch = sessionEpoch
    pendingPrivateExportGrant = null
    mutableState.update { it.copy(isPrivateExportWarningVisible = false, isPrivateExporting = true, privateExportError = null) }
    privateExportJob?.cancel()
    privateExportJob = viewModelScope.launch {
      try {
        privateExporter.export(currentAccess, grant, request).flowOn(Dispatchers.IO).collect { event ->
          mutablePrivateExportEvents.emit(event)
          if (event == PrivateExportEvent.AuthorizationRequired && isCurrentSession(currentAccess, epoch)) {
            pendingPrivateExportGrant = null
            mutableState.update {
              it.copy(
                isPrivateExportPasswordPromptVisible = true,
                isPrivateExportWarningVisible = false,
                privateExportError = "导出授权已过期，请重新验证密码",
              )
            }
          }
          if (event is PrivateExportEvent.Completed && isCurrentSession(currentAccess, epoch)) {
            val history = repository.listPrivateExportHistory(currentAccess)
            mutableState.update { it.copy(privateExportHistory = history) }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutablePrivateExportEvents.emit(
            PrivateExportEvent.ItemFailed(request.noteId, "AN-EXP-004", "导出失败，请检查保存位置后重试。"),
          )
          mutablePrivateExportEvents.emit(PrivateExportEvent.Completed(succeeded = 0, failed = 1, historyFailures = 0))
        }
      } finally {
        if (isCurrentSession(currentAccess, epoch)) {
          val shouldRetainRequest = mutableState.value.isPrivateExportPasswordPromptVisible ||
            mutableState.value.isPrivateExportWarningVisible
          if (!shouldRetainRequest) pendingPrivateExportRequest = null
          mutableState.update { it.copy(isPrivateExporting = false) }
        }
      }
    }
  }

  fun cancelPrivateExportPrompt() {
    pendingPrivateExportGrant = null
    pendingPrivateExportRequest = null
    mutableState.update {
      it.copy(
        isPrivateExportPasswordPromptVisible = false,
        isPrivateExportWarningVisible = false,
        privateExportError = null,
      )
    }
  }

  private fun startPrivateExportThrottleCountdown(access: PrivateAccess, epoch: Long, initialWaitMillis: Long) {
    privateExportThrottleJob?.cancel()
    privateExportThrottleJob = viewModelScope.launch {
      var remaining = initialWaitMillis
      while (remaining > 0L && isCurrentSession(access, epoch)) {
        delay(1_000L)
        remaining = withContext(Dispatchers.IO) { throttle.remainingMillis(System.currentTimeMillis()) }
        mutableState.update { state ->
          if (isCurrentSession(access, epoch)) {
            state.copy(
              privateExportRetryAfterMillis = remaining,
              privateExportError = if (remaining > 0L) "尝试次数过多，请稍后重试" else null,
            )
          } else state
        }
      }
    }
  }

  fun showPrivateExportHistory() {
    val currentAccess = access ?: return
    if (!currentAccess.isActive) return
    val epoch = sessionEpoch
    viewModelScope.launch {
      try {
        val history = repository.listPrivateExportHistory(currentAccess)
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(privateExportHistory = history, isPrivateExportHistoryVisible = true) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) mutableState.update { it.copy(errorMessage = GENERIC_ACCESS_ERROR) }
      }
    }
  }

  fun hidePrivateExportHistory() {
    mutableState.update { it.copy(isPrivateExportHistoryVisible = false) }
  }

  fun showSection(section: PrivateSection) {
    if (access?.isActive != true) return
    mutableState.update { it.copy(section = section) }
    refreshPrivateContent()
  }

  fun setShowCompletedTodos(show: Boolean) {
    if (access?.isActive != true) return
    mutableState.update { it.copy(showCompletedTodos = show) }
    refreshPrivateContent()
  }

  fun setTodoCompleted(todo: PrivateTodoItem, completed: Boolean) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    viewModelScope.launch {
      try {
        repository.setTodoCompleted(currentAccess, todo.noteId, todo.todoId, completed)
        if (isCurrentSession(currentAccess, epoch)) refreshPrivateContent()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(errorMessage = GENERIC_ACCESS_ERROR) }
        }
      }
    }
  }

  fun beginNewNote() {
    val currentAccess = access ?: return
    if (!currentAccess.isActive) return
    val current = mutableState.value
    val recovery = PrivateEditorRules.findRecoverableDraft(
      current.recoverableEditorDrafts + listOfNotNull(latestPrivateEditorSnapshot),
      null,
      null,
    )
    mutableState.update {
      it.copy(
        isEditorVisible = true,
        isCreatingNote = true,
        editingNote = null,
        editorError = null,
        pendingEditorRecovery = recovery,
        activeEditorDraftId = recovery?.draftId ?: UUID.randomUUID().toString(),
        activeEditorLocalRevision = recovery?.localRevision ?: 0L,
        activeEditorSkinId = recovery?.skinId ?: DEFAULT_NOTE_SKIN_ID,
      )
    }
    recovery?.takeIf { draft -> draft.blocks.any { it.type == BlockType.OCR_TEXT && it.ocrStatus == OcrStatus.PENDING && it.assetPath != null } }
      ?.let { draft ->
        onPrivateEditorChanged(
          draft.title,
          draft.blocks,
          draft.blocks.filter { it.type == BlockType.TODO }.map { PrivateTodoDraft(blockId = it.id, text = it.text.orEmpty()) },
        )
      }
  }

  fun openNote(noteId: PrivateNoteId) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    viewModelScope.launch {
      try {
        privateDraftWriteJob?.join()
        val note = repository.read(currentAccess, noteId)
        if (!isCurrentSession(currentAccess, epoch)) return@launch
        if (note == null) {
          mutableState.update { it.copy(errorMessage = "这条笔记暂时无法打开") }
        } else {
          val recovery = PrivateEditorRules.findRecoverableDraft(
            repository.listRecoverable(currentAccess),
            note.id,
            note.contentRevision,
          )
          mutableState.update {
            it.copy(
              isEditorVisible = true,
              isCreatingNote = false,
              editingNote = note,
              editorError = null,
              pendingEditorRecovery = recovery,
              activeEditorDraftId = recovery?.draftId ?: UUID.randomUUID().toString(),
              activeEditorLocalRevision = recovery?.localRevision ?: 0L,
              activeEditorSkinId = recovery?.skinId ?: note.skinId,
            )
          }
          val draftBlocks = recovery?.blocks ?: note.blocks
          if (draftBlocks.any { it.type == BlockType.OCR_TEXT && it.ocrStatus == OcrStatus.PENDING && it.assetPath != null }) {
            onPrivateEditorChanged(
              recovery?.title ?: note.title,
              draftBlocks,
              draftBlocks.filter { it.type == BlockType.TODO }.map { PrivateTodoDraft(blockId = it.id, text = it.text.orEmpty()) },
            )
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(errorMessage = GENERIC_ACCESS_ERROR) }
        }
      }
    }
  }

  fun closeEditor() {
    mutableState.update {
      it.copy(
        isEditorVisible = false,
        isCreatingNote = false,
        editingNote = null,
        editorError = null,
        pendingEditorRecovery = null,
        activeEditorDraftId = null,
        activeEditorLocalRevision = 0L,
        activeEditorSkinId = DEFAULT_NOTE_SKIN_ID,
      )
    }
  }

  fun acknowledgeEditorRecovery() {
    mutableState.update { it.copy(pendingEditorRecovery = null) }
  }

  fun discardEditorRecovery(draft: PrivateEditSnapshot) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    mutableState.update {
      it.copy(
        pendingEditorRecovery = null,
        activeEditorDraftId = UUID.randomUUID().toString(),
        activeEditorLocalRevision = 0L,
      )
    }
    latestPrivateEditorSnapshot = null
    viewModelScope.launch {
      try {
        repository.clearIfAtMost(currentAccess, draft.draftId, draft.localRevision)
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { state -> state.copy(recoverableEditorDrafts = state.recoverableEditorDrafts.filterNot { it.draftId == draft.draftId }) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(editorError = "无法清除此私密草稿，请解锁后重试") }
        }
      }
    }
  }

  fun onPrivateEditorChanged(title: String, blocks: List<NoteBlockDraft>, todos: List<PrivateTodoDraft>) {
    val currentAccess = access ?: return
    val current = mutableState.value
    if (!current.isEditorVisible || !currentAccess.isActive) return
    val localRevision = current.activeEditorLocalRevision + 1L
    val reconciledBlocks = reconcilePrivateOcrResults(blocks, current.privateOcrResults)
    val snapshot = try {
      createEditorSnapshot(current, title, reconciledBlocks, todos, localRevision)
    } catch (_: IllegalArgumentException) {
      mutableState.update { it.copy(editorError = "私密草稿格式无效，正文仍保留在当前页面") }
      return
    }
    val epoch = sessionEpoch
    latestPrivateEditorSnapshot = snapshot
    mutableState.update {
      it.copy(
        activeEditorDraftId = snapshot.draftId,
        activeEditorLocalRevision = snapshot.localRevision,
        editorError = null,
      )
    }
    schedulePrivateDraftWrite(snapshot, currentAccess, epoch)
    if (isCurrentSession(currentAccess, epoch)) {
      snapshot.blocks.filter { it.type == BlockType.OCR_TEXT && it.ocrStatus == OcrStatus.PENDING }
        .forEach { block -> block.assetPath?.let { startPrivateOcrAfterDraft(currentAccess, epoch, block.id, it) } }
    }
  }

  fun onPrivateEditorSkinChanged(skinId: String) {
    val currentAccess = access ?: return
    val current = mutableState.value
    if (!current.isEditorVisible || !currentAccess.isActive || !DisplaySettingsRules.isApprovedSkinId(skinId)) return
    val source = latestPrivateEditorSnapshot ?: return
    if (current.activeEditorSkinId == skinId && source.skinId == skinId) return
    val snapshot = source.copy(
      skinId = skinId,
      localRevision = current.activeEditorLocalRevision + 1L,
      savedAtMillis = System.currentTimeMillis(),
    )
    val epoch = sessionEpoch
    latestPrivateEditorSnapshot = snapshot
    mutableState.update {
      it.copy(
        activeEditorSkinId = skinId,
        activeEditorLocalRevision = snapshot.localRevision,
        editorError = null,
      )
    }
    schedulePrivateDraftWrite(snapshot, currentAccess, epoch)
  }

  private fun schedulePrivateDraftWrite(
    snapshot: PrivateEditSnapshot,
    currentAccess: PrivateAccess,
    epoch: Long,
  ) {
    privateDraftWriteJob?.cancel()
    privateDraftWriteJob = viewModelScope.launch {
      delay(PRIVATE_DRAFT_DEBOUNCE_MILLIS)
      try {
        privateDraftWriteMutex.withLock {
          if (!isCurrentSession(currentAccess, epoch)) return@withLock
          val latest = mutableState.value
          if (latest.activeEditorDraftId == snapshot.draftId && latest.activeEditorLocalRevision > snapshot.localRevision) {
            return@withLock
          }
          repository.writeAtomic(currentAccess, snapshot)
          if (isCurrentSession(currentAccess, epoch)) {
            mutableState.update { state ->
              val drafts = state.recoverableEditorDrafts.filterNot { it.draftId == snapshot.draftId } + snapshot
              state.copy(recoverableEditorDrafts = drafts)
            }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(editorError = "私密草稿加密保存失败，当前页面内容仍保留") }
        }
      }
    }
  }

  fun importPrivateImage(
    uri: Uri,
    mode: OcrImportMode,
    blocks: List<NoteBlockDraft>,
    activeTextBlockId: String?,
    selectionStart: Int,
    selectionEnd: Int,
    onInserted: (List<NoteBlockDraft>, String?) -> Unit,
  ) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    viewModelScope.launch {
      var privateReference: String? = null
      var sourceBytes: ByteArray? = null
      try {
        val importedBytes = withContext(Dispatchers.IO) { readPrivateImageBytes(uri) }
        sourceBytes = importedBytes
        if (!isCurrentSession(currentAccess, epoch)) return@launch
        privateReference = privateStore.writePrivateAsset(currentAccess, importedBytes)
        val dimensions = BitmapFactory.Options().also { options ->
          options.inJustDecodeBounds = true
          BitmapFactory.decodeByteArray(importedBytes, 0, importedBytes.size, options)
        }
        require(dimensions.outWidth > 0 && dimensions.outHeight > 0) { "所选文件不是可识别的图片" }
        val ratio = dimensions.outWidth.toFloat() / dimensions.outHeight.toFloat()
        val selectedText = blocks.firstOrNull { it.id == activeTextBlockId && it.type == BlockType.TEXT }
          ?: blocks.firstOrNull { it.type == BlockType.TEXT }
          ?: NoteBlockDraft(type = BlockType.TEXT, text = "")
        val baseBlocks = if (blocks.any { it.id == selectedText.id }) blocks else blocks + selectedText
        val start = if (selectedText.id == activeTextBlockId) selectionStart else selectedText.text.orEmpty().length
        val end = if (selectedText.id == activeTextBlockId) selectionEnd else start
        val image = NoteBlockDraft(
          type = BlockType.IMAGE,
          assetPath = privateReference,
          aspectRatio = ratio,
          alignment = BlockAlignment.CENTER,
        )
        var pendingOcr: NoteBlockDraft? = null
        var focusTextBlockId: String? = null
        val updated = when (mode) {
          OcrImportMode.IMAGE_ONLY -> {
            val inserted = ImageBlockRules.insertAtSelection(baseBlocks, selectedText.id, start, end, image)
            val imageIndex = inserted.indexOfFirst { it.id == image.id }
            focusTextBlockId = inserted.drop(imageIndex + 1).firstOrNull { it.type == BlockType.TEXT }?.id
              ?: inserted.lastOrNull { it.type == BlockType.TEXT }?.id
            inserted
          }
          OcrImportMode.IMAGE_AND_TEXT -> {
            val withImage = ImageBlockRules.insertAtSelection(baseBlocks, selectedText.id, start, end, image)
            pendingOcr = NoteBlockDraft(type = BlockType.OCR_TEXT, assetPath = privateReference, ocrStatus = OcrStatus.PENDING)
            val inserted = OcrBlockInsertionRules.appendAfterImage(withImage, image.id, pendingOcr!!)
            val imageIndex = inserted.indexOfFirst { it.id == image.id }
            focusTextBlockId = inserted.drop(imageIndex + 2).firstOrNull { it.type == BlockType.TEXT }?.id
              ?: inserted.lastOrNull { it.type == BlockType.TEXT }?.id
            inserted
          }
          OcrImportMode.TEXT_ONLY -> {
            pendingOcr = NoteBlockDraft(type = BlockType.OCR_TEXT, assetPath = privateReference, ocrStatus = OcrStatus.PENDING)
            val inserted = OcrBlockInsertionRules.insertAtSelection(baseBlocks, selectedText.id, start, end, pendingOcr!!)
            focusTextBlockId = inserted.focusTextBlockId
            inserted.blocks
          }
        }
        if (!isCurrentSession(currentAccess, epoch)) return@launch
        onInserted(updated, focusTextBlockId)
        privateReference = null
        if (pendingOcr != null) {
          // onInserted synchronously triggers the encrypted draft write; the coordinator waits for it below.
          startPrivateOcrAfterDraft(currentAccess, epoch, pendingOcr!!.id, pendingOcr!!.assetPath.orEmpty())
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(editorError = "图片无法读取或超过 64 MB 限制") }
      } finally {
        sourceBytes?.let(MemoryWipe::bytes)
        privateReference?.let { reference ->
          if (currentAccess.isActive) runCatching { privateStore.deletePrivateAssetIfUnreferenced(currentAccess, reference) }
        }
      }
    }
  }

  fun cancelPrivateOcr(blockId: String) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    val job = privateOcrJobs[blockId]
    viewModelScope.launch {
      job?.cancelAndJoin()
      if (!isCurrentSession(currentAccess, epoch)) return@launch
      try {
        privateOcrJobs.remove(blockId)
        val released = repository.updatePrivateOcrBlock(currentAccess, blockId, OcrStatus.CANCELLED)
        val sourceBlock = findPrivateOcrBlock(blockId)
        val result = sourceBlock?.copy(text = null, ocrStatus = OcrStatus.CANCELLED)
        mutableState.update { state ->
          state.copy(
            privateOcrProgress = state.privateOcrProgress - blockId,
            privateOcrResults = result?.let { state.privateOcrResults + (blockId to it) } ?: state.privateOcrResults,
          )
        }
        released?.let { privateStore.deletePrivateAssetIfUnreferenced(currentAccess, it) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        mutableState.update { it.copy(editorError = "无法取消文字识别，请稍后重试") }
      }
    }
  }

  fun retryPrivateOcr(blockId: String) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    viewModelScope.launch {
      try {
        val sourceBlock = findPrivateOcrBlock(blockId)
        val reference = sourceBlock?.assetPath
        if (sourceBlock == null || reference.isNullOrBlank()) {
          mutableState.update { it.copy(editorError = "加密识别图片已不可用，请重新插入图片") }
          return@launch
        }
        repository.updatePrivateOcrBlock(currentAccess, blockId, OcrStatus.PENDING)
        val latest = latestPrivateEditorSnapshot
        if (latest != null) {
          val nextBlocks = latest.blocks.map { if (it.id == blockId) it.copy(text = null, ocrStatus = OcrStatus.PENDING) else it }
          onPrivateEditorChanged(
            latest.title,
            nextBlocks,
            nextBlocks.filter { it.type == BlockType.TODO }.map { PrivateTodoDraft(blockId = it.id, text = it.text.orEmpty()) },
          )
        }
        mutableState.update { state ->
          state.copy(privateOcrResults = state.privateOcrResults + (blockId to sourceBlock.copy(text = null, ocrStatus = OcrStatus.PENDING)))
        }
        startPrivateOcrAfterDraft(currentAccess, epoch, blockId, reference)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) mutableState.update { it.copy(editorError = "无法重新排队，请稍后重试") }
      }
    }
  }

  private fun startPrivateOcrAfterDraft(access: PrivateAccess, epoch: Long, blockId: String, assetReference: String) {
    if (assetReference.isBlank() || privateOcrJobs[blockId]?.isActive == true) return
    privateOcrJobs[blockId] = viewModelScope.launch {
      try {
        privateDraftWriteJob?.join()
        if (!isCurrentSession(access, epoch)) return@launch
        val snapshot = latestPrivateEditorSnapshot
        val savedPending = repository.listRecoverable(access).any { draft ->
          (snapshot == null || draft.draftId == snapshot.draftId) &&
            draft.blocks.any { it.id == blockId && it.ocrStatus == OcrStatus.PENDING && it.assetPath == assetReference }
        }
        if (!savedPending) return@launch
        mutableState.update { state ->
          state.copy(privateOcrProgress = state.privateOcrProgress + (blockId to PrivateOcrProgress(10, "准备离线模型")))
        }
        val runner = PrivateOcrRunner(privateStore) { bytes, progress ->
          TesseractOcrEngine(getApplication()).recognize(bytes, progress)
        }
        val recognized = runner.recognize(access, assetReference, OcrProgressListener { stage ->
          if (isCurrentSession(access, epoch)) {
            mutableState.update { state ->
              state.copy(privateOcrProgress = state.privateOcrProgress + (blockId to PrivateOcrProgress(stage.percent, stage.label)))
            }
          }
        })
        val normalized = OcrJobRules.normalizeText(recognized) ?: throw IOException("识别内容为空")
        val released = repository.updatePrivateOcrBlock(access, blockId, OcrStatus.SUCCEEDED, normalized)
        if (!isCurrentSession(access, epoch)) return@launch
        val updatedNote = mutableState.value.editingNote?.id?.let { repository.read(access, it) }
        val prior = findPrivateOcrBlock(blockId)
        val completed = prior?.copy(text = normalized, assetPath = null, ocrStatus = OcrStatus.SUCCEEDED)
        mutableState.update { state ->
          state.copy(
            editingNote = updatedNote ?: state.editingNote,
            privateOcrProgress = state.privateOcrProgress - blockId,
            privateOcrResults = if (state.isEditorVisible && completed != null) {
              state.privateOcrResults + (blockId to completed)
            } else state.privateOcrResults - blockId,
          )
        }
        released?.let { privateStore.deletePrivateAssetIfUnreferenced(access, it) }
        if (!mutableState.value.isEditorVisible) refreshPrivateContent()
      } catch (cancelled: CancellationException) {
        if (isCurrentSession(access, epoch)) {
          mutableState.update { it.copy(privateOcrProgress = it.privateOcrProgress - blockId) }
        }
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(access, epoch)) {
          runCatching { repository.updatePrivateOcrBlock(access, blockId, OcrStatus.FAILED) }
          val prior = findPrivateOcrBlock(blockId)
          val failed = prior?.copy(text = null, ocrStatus = OcrStatus.FAILED)
          mutableState.update { state ->
            state.copy(
              editorError = "图片文字识别失败，可重试",
              privateOcrProgress = state.privateOcrProgress - blockId,
              privateOcrResults = if (state.isEditorVisible && failed != null) state.privateOcrResults + (blockId to failed) else state.privateOcrResults,
            )
          }
        }
      } finally {
        privateOcrJobs.remove(blockId)
      }
    }
  }

  private fun findPrivateOcrBlock(blockId: String): NoteBlockDraft? =
    latestPrivateEditorSnapshot?.blocks?.firstOrNull { it.id == blockId }
      ?: mutableState.value.editingNote?.blocks?.firstOrNull { it.id == blockId }
      ?: mutableState.value.pendingEditorRecovery?.blocks?.firstOrNull { it.id == blockId }

  private fun reconcilePrivateOcrResults(
    blocks: List<NoteBlockDraft>,
    results: Map<String, NoteBlockDraft>,
  ): List<NoteBlockDraft> = blocks.map { current ->
    results[current.id]?.takeIf { it.type == BlockType.OCR_TEXT && it.ocrStatus != OcrStatus.PENDING } ?: current
  }

  private fun readPrivateImageBytes(uri: Uri): ByteArray {
    val input = getApplication<Application>().contentResolver.openInputStream(uri)
      ?: throw IOException("无法打开图片")
    return input.use { source ->
      val output = object : ByteArrayOutputStream() {
        override fun close() {
          buf.fill(0)
          reset()
        }
      }
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      try {
        var total = 0L
        while (true) {
          val count = source.read(buffer)
          if (count < 0) break
          total += count
          if (total > MAX_PRIVATE_IMAGE_IMPORT_BYTES) throw IOException("图片超过私密导入限制")
          output.write(buffer, 0, count)
        }
        if (total == 0L) throw IOException("图片为空")
        output.toByteArray()
      } finally {
        buffer.fill(0)
        output.close()
      }
    }
  }

  fun saveNote(title: String, blocks: List<NoteBlockDraft>, todos: List<PrivateTodoDraft>) {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    val current = mutableState.value
    if (current.isSavingNote) return
    val reconciledBlocks = reconcilePrivateOcrResults(blocks, current.privateOcrResults)
    val snapshot = try {
      createEditorSnapshot(current, title, reconciledBlocks, todos, current.activeEditorLocalRevision + 1L)
    } catch (_: IllegalArgumentException) {
      mutableState.update { it.copy(editorError = "私密笔记格式无效，正文仍保留在当前页面") }
      return
    }
    privateDraftWriteJob?.cancel()
    privateDraftWriteJob = null
    latestPrivateEditorSnapshot = snapshot
    mutableState.update {
      it.copy(
        isSavingNote = true,
        editorError = null,
        activeEditorDraftId = snapshot.draftId,
        activeEditorLocalRevision = snapshot.localRevision,
      )
    }
    viewModelScope.launch {
      try {
        privateDraftWriteMutex.withLock {
          if (!isCurrentSession(currentAccess, epoch)) return@withLock
          repository.writeAtomic(currentAccess, snapshot)
        }
        if (!isCurrentSession(currentAccess, epoch)) return@launch
        val result = repository.save(currentAccess, snapshot)
        when (result) {
          is PrivateSaveResult.Saved, PrivateSaveResult.EmptyNotSaved -> {
            repository.clearIfAtMost(currentAccess, snapshot.draftId, snapshot.localRevision)
            if (isCurrentSession(currentAccess, epoch)) {
              if (latestPrivateEditorSnapshot?.draftId == snapshot.draftId) latestPrivateEditorSnapshot = null
              refreshPrivateContent()
              mutableState.update {
                it.copy(
                  isSavingNote = false,
                  isEditorVisible = false,
                  isCreatingNote = false,
                  editingNote = null,
                  pendingEditorRecovery = null,
                  activeEditorDraftId = null,
                  activeEditorLocalRevision = 0L,
                  activeEditorSkinId = DEFAULT_NOTE_SKIN_ID,
                  recoverableEditorDrafts = it.recoverableEditorDrafts.filterNot { draft -> draft.draftId == snapshot.draftId },
                )
              }
            }
          }
          is PrivateSaveResult.Conflict -> if (isCurrentSession(currentAccess, epoch)) {
            mutableState.update { it.copy(isSavingNote = false, editorError = "笔记内容已更新，请重新打开后再编辑") }
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(isSavingNote = false, editorError = "保存失败，笔记内容仍保留在当前页面") }
        }
      }
    }
  }

  private fun createEditorSnapshot(
    current: PrivateModuleUiState,
    title: String,
    blocks: List<NoteBlockDraft>,
    todos: List<PrivateTodoDraft>,
    localRevision: Long,
  ): PrivateEditSnapshot {
    val existing = current.editingNote
    val mergedBlocks = PrivateEditorRules.mergeBlocks(existing?.blocks.orEmpty(), blocks, todos)
    val bodyText = mergedBlocks.asSequence()
      .filter { it.type in setOf(BlockType.TEXT, BlockType.QUOTE, BlockType.OCR_TEXT) }
      .mapNotNull(NoteBlockDraft::text)
      .joinToString("\n")
    return PrivateEditSnapshot(
      draftId = current.activeEditorDraftId ?: UUID.randomUUID().toString(),
      noteId = existing?.id,
      folderId = existing?.folderId ?: PRIVATE_UNCATEGORIZED_FOLDER_ID,
      title = PrivateEditorRules.resolveTitle(title, bodyText),
      titleManuallySet = title.isNotBlank(),
      blocks = mergedBlocks,
      skinId = current.activeEditorSkinId,
      fontSizeSpOverride = existing?.fontSizeSpOverride,
      lineSpacingMultiplierOverride = existing?.lineSpacingMultiplierOverride,
      baseContentRevision = existing?.contentRevision,
      localRevision = localRevision,
      savedAtMillis = System.currentTimeMillis(),
    )
  }

  fun refreshThrottleWait() {
    viewModelScope.launch {
      val retry = withContext(Dispatchers.IO) { throttle.remainingMillis(System.currentTimeMillis()) }
      mutableState.update { it.copy(retryAfterMillis = retry) }
      while (retry > 0L && access == null) {
        delay(1_000L)
        val next = withContext(Dispatchers.IO) { throttle.remainingMillis(System.currentTimeMillis()) }
        mutableState.update { it.copy(retryAfterMillis = next) }
        if (next == 0L) break
      }
    }
  }

  private fun refreshPrivateContent() {
    val currentAccess = access ?: return
    val epoch = sessionEpoch
    viewModelScope.launch {
      try {
        loadPrivateContent(currentAccess, epoch)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (isCurrentSession(currentAccess, epoch)) {
          mutableState.update { it.copy(errorMessage = GENERIC_ACCESS_ERROR) }
        }
      }
    }
  }

  private suspend fun loadPrivateContent(currentAccess: PrivateAccess, epoch: Long) {
    val notes = repository.listPreviews(currentAccess)
    val todos = repository.listTodos(currentAccess, mutableState.value.showCompletedTodos)
    val drafts = repository.listRecoverable(currentAccess)
    val history = repository.listPrivateExportHistory(currentAccess)
    if (!isCurrentSession(currentAccess, epoch)) return
    mutableState.update {
      it.copy(
        status = PrivateModuleStatus.UNLOCKED,
        notes = notes,
        todos = todos,
        recoverableEditorDrafts = drafts,
        privateExportHistory = history,
      )
    }
  }

  private fun isCurrentAuthentication(requestId: Long, epoch: Long): Boolean =
    authenticationRequest == requestId && sessionEpoch == epoch

  private fun isCurrentSession(currentAccess: PrivateAccess, epoch: Long): Boolean =
    access === currentAccess && currentAccess.isActive && sessionEpoch == epoch

  private fun generateRecoveryKey(): String {
    val bytes = ByteArray(RECOVERY_KEY_BYTES).also(random::nextBytes)
    return try {
      Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    } finally {
      bytes.fill(0)
    }
  }

  override fun onCleared() {
    privateExportJob?.cancel()
    privateOcrJobs.values.forEach(Job::cancel)
    privateOcrJobs.clear()
    repository.lock()
    access = null
    super.onCleared()
  }

  private companion object {
    const val GENERIC_ACCESS_ERROR = "暂时无法访问私密内容，请稍后重试"
    const val RECOVERY_KEY_BYTES = 24
    const val PRIVATE_UNCATEGORIZED_FOLDER_ID = "private-uncategorized"
    const val PRIVATE_DRAFT_DEBOUNCE_MILLIS = 350L
    const val MAX_PRIVATE_IMAGE_IMPORT_BYTES = 64L * 1024L * 1024L
  }
}

package com.anan.anannotes.feature.backup

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.feature.export.OutputTarget
import com.anan.anannotes.security.BackupRecordSetSummary
import com.anan.anannotes.security.MemoryWipe
import com.anan.anannotes.security.PrivateAccess
import com.anan.anannotes.security.PrivateAuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BackupRestoreUiState(
  val privateVaultStatusLoaded: Boolean = false,
  val privateVaultConfigured: Boolean = false,
  val isBusy: Boolean = false,
  val busyLabel: String? = null,
  val errorMessage: String? = null,
  val createdFileName: String? = null,
  val createdDestinationName: String? = null,
  val preview: BackupPreview? = null,
  val restoreResult: BackupRecordSetSummary? = null,
  val recoveryKey: String? = null,
)

/** Keeps verified backup capabilities and temporary private access out of Navigation arguments. */
class BackupRestoreViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = BackupRepository(application)
  private val random = SecureRandom()
  private val mutableState = MutableStateFlow(BackupRestoreUiState())
  val state = mutableState.asStateFlow()

  private var inspected: InspectedBackup? = null
  private var privateAccess: PrivateAccess? = null

  init {
    viewModelScope.launch {
      runCatching { repository.isPrivateVaultConfigured() }
        .onSuccess { configured ->
          mutableState.update { it.copy(privateVaultConfigured = configured, privateVaultStatusLoaded = true) }
        }
        .onFailure {
          mutableState.update {
            it.copy(privateVaultStatusLoaded = true, errorMessage = "暂时无法读取本地私密库状态，请重试。")
          }
        }
    }
  }

  fun createBackup(
    targetUri: String,
    backupPassword: CharArray,
    privatePassword: CharArray?,
  ) {
    if (mutableState.value.isBusy) {
      MemoryWipe.chars(backupPassword)
      MemoryWipe.chars(privatePassword)
      return
    }
    mutableState.update {
      it.copy(isBusy = true, busyLabel = "正在创建加密备份…", errorMessage = null, createdFileName = null, createdDestinationName = null)
    }
    viewModelScope.launch {
      var access: PrivateAccess? = null
      try {
        if (mutableState.value.privateVaultConfigured) {
          access = repository.unlockPrivateForBackup(privatePassword ?: CharArray(0))
        } else {
          MemoryWipe.chars(privatePassword)
        }
        val name = "AnAnNotes-backup-${LocalDateTime.now().format(FILE_TIME_FORMAT)}.anbk"
        val result = repository.createBackup(
          target = OutputTarget.DocumentTree(targetUri),
          requestedName = name,
          backupPassword = backupPassword,
          privateAccess = access,
        )
        mutableState.update {
          it.copy(createdFileName = result.destination.fileName, createdDestinationName = result.destination.destinationName)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        MemoryWipe.chars(backupPassword)
        MemoryWipe.chars(privatePassword)
        mutableState.update { it.copy(errorMessage = mapFailure(failure)) }
      } finally {
        MemoryWipe.chars(backupPassword)
        MemoryWipe.chars(privatePassword)
        repository.lockPrivateBackupAccess(access)
        mutableState.update { it.copy(isBusy = false, busyLabel = null) }
      }
    }
  }

  fun inspectBackup(sourceUri: String, backupPassword: CharArray, privatePassword: CharArray?) {
    if (mutableState.value.isBusy) {
      MemoryWipe.chars(backupPassword)
      MemoryWipe.chars(privatePassword)
      return
    }
    closeInspection()
    releasePrivateAccess()
    mutableState.update {
      it.copy(
        isBusy = true,
        busyLabel = "正在验证备份…",
        errorMessage = null,
        preview = null,
        restoreResult = null,
        recoveryKey = null,
      )
    }
    viewModelScope.launch {
      try {
        if (mutableState.value.privateVaultConfigured) {
          privateAccess = repository.unlockPrivateForBackup(privatePassword ?: CharArray(0))
        } else {
          MemoryWipe.chars(privatePassword)
        }
        val result = repository.inspectBackup(sourceUri, backupPassword, privateAccess)
        inspected = result
        mutableState.update { it.copy(preview = result.preview) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        MemoryWipe.chars(backupPassword)
        MemoryWipe.chars(privatePassword)
        releasePrivateAccess()
        mutableState.update { it.copy(errorMessage = mapFailure(failure)) }
      } finally {
        MemoryWipe.chars(backupPassword)
        MemoryWipe.chars(privatePassword)
        mutableState.update { it.copy(isBusy = false, busyLabel = null) }
      }
    }
  }

  fun createRecoveryKey() {
    val bytes = ByteArray(24).also(random::nextBytes)
    val key = try {
      Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    } finally {
      bytes.fill(0)
    }
    mutableState.update { it.copy(recoveryKey = key, errorMessage = null) }
  }

  fun restoreBackup(
    ordinaryChoice: BackupConflictChoice,
    privateChoice: BackupConflictChoice,
    newPrivatePassword: CharArray?,
    recoveryKeyConfirmed: Boolean,
  ) {
    val capability = inspected
    if (capability == null || mutableState.value.isBusy) {
      MemoryWipe.chars(newPrivatePassword)
      return
    }
    val recoveryKey = mutableState.value.recoveryKey
    if (recoveryKey != null && !recoveryKeyConfirmed) {
      MemoryWipe.chars(newPrivatePassword)
      mutableState.update { it.copy(errorMessage = "请先复制并确认已保存恢复密钥，避免密钥丢失。") }
      return
    }
    mutableState.update { it.copy(isBusy = true, busyLabel = "正在恢复备份…", errorMessage = null, restoreResult = null) }
    viewModelScope.launch {
      try {
        val result = repository.restoreVerifiedBackup(
          inspected = capability,
          ordinaryChoice = ordinaryChoice,
          privateChoice = privateChoice,
          currentPrivateAccess = privateAccess,
          newPrivatePassword = newPrivatePassword,
          newPrivateRecoveryKey = recoveryKey?.takeIf { !mutableState.value.privateVaultConfigured }?.toCharArray(),
        )
        inspected = null
        mutableState.update { it.copy(preview = null, restoreResult = result) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: Throwable) {
        MemoryWipe.chars(newPrivatePassword)
        inspected = null // The verified inspection capability is single-use, including failure.
        mutableState.update { it.copy(preview = null, errorMessage = mapFailure(failure)) }
      } finally {
        releasePrivateAccess()
        mutableState.update { it.copy(isBusy = false, busyLabel = null, recoveryKey = null) }
      }
    }
  }

  fun dismissRestoreResult() {
    mutableState.update { it.copy(restoreResult = null, recoveryKey = null) }
  }

  fun discardInspection() {
    closeInspection()
    releasePrivateAccess()
    mutableState.update { it.copy(preview = null, recoveryKey = null, restoreResult = null, errorMessage = null) }
  }

  private fun closeInspection() {
    inspected?.close()
    inspected = null
  }

  private fun releasePrivateAccess() {
    repository.lockPrivateBackupAccess(privateAccess)
    privateAccess = null
  }

  private fun mapFailure(failure: Throwable): String = when (failure) {
    is PrivateBackupAuthThrottledException -> "私密库验证暂不可用，请稍后再试。"
    is PrivateAuthenticationException -> "密码不正确，或备份文件已损坏。"
    else -> "操作未完成。请确认密码、文件和存储位置后重试。"
  }

  override fun onCleared() {
    closeInspection()
    releasePrivateAccess()
    super.onCleared()
  }

  private companion object {
    val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}

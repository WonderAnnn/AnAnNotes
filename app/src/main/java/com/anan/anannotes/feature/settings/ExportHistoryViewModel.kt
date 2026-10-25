package com.anan.anannotes.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.model.ExportHistoryModel
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.ExportHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExportHistoryUiState(
  val records: List<ExportHistoryModel> = emptyList(),
  val isLoading: Boolean = true,
  val isClearing: Boolean = false,
  val message: String? = null,
)

class ExportHistoryViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = ExportHistoryRepository(AnAnDatabase.getInstance(application))
  private val fileActions = ExportHistoryFileActions(application)
  private val _state = MutableStateFlow(ExportHistoryUiState())
  val state = _state.asStateFlow()

  init {
    viewModelScope.launch {
      repository.observeLatest()
        .catch { error ->
          if (error is CancellationException) throw error
          _state.update { it.copy(isLoading = false, message = "无法读取导出记录，请刷新重试。") }
        }
        .collect { records -> _state.update { it.copy(records = records, isLoading = false) } }
    }
  }

  fun refresh() {
    if (_state.value.isClearing) return
    viewModelScope.launch {
      _state.update { it.copy(isLoading = true, message = null) }
      try {
        val records = repository.latest()
        _state.update { it.copy(records = records, isLoading = false) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _state.update { it.copy(isLoading = false, message = "无法读取导出记录，请刷新重试。") }
      }
    }
  }

  fun clear() {
    if (_state.value.isClearing || _state.value.isLoading) return
    viewModelScope.launch {
      _state.update { it.copy(isClearing = true, message = null) }
      try {
        repository.clear()
        _state.update { it.copy(records = emptyList(), isClearing = false, message = "已清空导出记录，导出文件已保留。") }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _state.update { it.copy(isClearing = false, message = "清空记录失败，请重试。") }
      }
    }
  }

  fun open(record: ExportHistoryModel) {
    viewModelScope.launch {
      val message = fileActions.open(record)
      _state.update { it.copy(message = message) }
    }
  }

  fun share(record: ExportHistoryModel) {
    viewModelScope.launch {
      val message = fileActions.share(record)
      _state.update { it.copy(message = message) }
    }
  }
}

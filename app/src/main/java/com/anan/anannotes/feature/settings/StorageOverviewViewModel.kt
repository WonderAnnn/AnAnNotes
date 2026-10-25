package com.anan.anannotes.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.data.repository.StorageOverview
import com.anan.anannotes.data.repository.StorageOverviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageOverviewUiState(
  val overview: StorageOverview? = null,
  val isLoading: Boolean = true,
  val isClearing: Boolean = false,
  val message: String? = null,
)

class StorageOverviewViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = StorageOverviewRepository(application)
  private val _state = MutableStateFlow(StorageOverviewUiState())
  val state = _state.asStateFlow()
  private var operation: Job? = null

  init { refresh() }

  fun refresh() {
    if (operation?.isActive == true) return
    operation = viewModelScope.launch {
      _state.update { it.copy(isLoading = true, message = null) }
      try {
        val overview = repository.refresh()
        _state.update { it.copy(overview = overview, isLoading = false) }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _state.update { it.copy(isLoading = false, message = "无法读取存储统计，请刷新重试。") }
      }
    }
  }

  fun clearPreviewCache() {
    if (operation?.isActive == true) return
    operation = viewModelScope.launch {
      _state.update { it.copy(isClearing = true, message = null) }
      var cleaned = false
      try {
        val removed = repository.clearPreviewCache()
        cleaned = true
        val overview = repository.refresh()
        _state.update {
          it.copy(
            overview = overview,
            isClearing = false,
            message = if (removed == 0) "没有可清理的预览图。" else "已清理 $removed 个预览文件。",
          )
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        _state.update {
          it.copy(
            isClearing = false,
            message = if (cleaned) "预览缓存已清理，统计刷新失败，请重试。" else "预览缓存清理失败，请重试。",
          )
        }
      }
    }
  }
}

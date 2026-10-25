package com.anan.anannotes.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anan.anannotes.core.DisplaySettingsRules
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.data.local.AnAnDatabase
import com.anan.anannotes.data.repository.DisplaySettingsRepository
import com.anan.anannotes.data.files.PreviewCacheStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DisplaySettingsViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = DisplaySettingsRepository(AnAnDatabase.getInstance(application).displaySettingsDao())
  private val _settings = MutableStateFlow(DisplaySettings())
  val settings = _settings.asStateFlow()
  private val _cacheMessage = MutableStateFlow<String?>(null)
  val cacheMessage = _cacheMessage.asStateFlow()
  private val settingsWrites = Channel<DisplaySettings>(Channel.CONFLATED)
  private var hasLocalUpdate = false

  init {
    viewModelScope.launch {
      val persisted = repository.observe().first()
      if (!hasLocalUpdate) _settings.value = persisted
    }
    viewModelScope.launch {
      for (requested in settingsWrites) {
        delay(180L)
        val latest = settingsWrites.tryReceive().getOrNull() ?: requested
        repository.save(latest)
      }
    }
  }

  fun update(settings: DisplaySettings) {
    val normalized = DisplaySettingsRules.normalize(settings)
    hasLocalUpdate = true
    _settings.value = normalized
    settingsWrites.trySend(normalized)
  }

  fun clearPreviewCache() {
    viewModelScope.launch {
      runCatching {
        withContext(Dispatchers.IO) { PreviewCacheStore(getApplication<Application>().cacheDir).clear() }
      }.onSuccess { count ->
        _cacheMessage.value = if (count == 0) "没有可清理的预览图" else "已清理 $count 张预览图"
      }.onFailure {
        _cacheMessage.value = it.message ?: "预览缓存清理失败"
      }
    }
  }
}

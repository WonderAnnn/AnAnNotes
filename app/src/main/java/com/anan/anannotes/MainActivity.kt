package com.anan.anannotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anan.anannotes.core.model.ThemeMode
import com.anan.anannotes.feature.backup.BackupRepository
import com.anan.anannotes.feature.imports.ShareImportHandler
import com.anan.anannotes.feature.imports.ShareImportPayload
import com.anan.anannotes.feature.ocr.OfflineOcrWorker
import com.anan.anannotes.feature.settings.DisplaySettingsViewModel
import com.anan.anannotes.ui.navigation.AppNavigation
import com.anan.anannotes.ui.theme.AnAnNotesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  private var incomingShare by mutableStateOf<ShareImportPayload?>(null)
  private var startupState by mutableStateOf<StartupState>(StartupState.Recovering)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) incomingShare = ShareImportHandler.parse(intent)
    enableEdgeToEdge()
    recoverBeforeShowingApp()

    setContent {
      when (startupState) {
        StartupState.Recovering -> AnAnNotesTheme(themeMode = ThemeMode.SYSTEM) {
          StartupRecoveryGate(recovering = true, onRetry = {})
        }
        StartupState.Ready -> {
          val settingsViewModel: DisplaySettingsViewModel = viewModel()
          val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
          val cacheMessage by settingsViewModel.cacheMessage.collectAsStateWithLifecycle()
          AnAnNotesTheme(themeMode = settings.themeMode) {
            Surface(modifier = Modifier.fillMaxSize()) {
              AppNavigation(
                modifier = Modifier.fillMaxSize(),
                displaySettings = settings,
                onDisplaySettingsChange = settingsViewModel::update,
                onClearPreviewCache = settingsViewModel::clearPreviewCache,
                cacheMessage = cacheMessage,
                incomingShare = incomingShare,
                onShareImportConsumed = { incomingShare = null },
                onSystemBarDarkIconsChange = { useDarkIcons ->
                  val controller = WindowCompat.getInsetsController(window, window.decorView)
                  controller.isAppearanceLightStatusBars = useDarkIcons
                  controller.isAppearanceLightNavigationBars = useDarkIcons
                },
                onEditorStatusBarDarkIconsChange = { useDarkIcons ->
                  WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightStatusBars = useDarkIcons
                },
                onEditorNavigationBarDarkIconsChange = { useDarkIcons ->
                  WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightNavigationBars = useDarkIcons
                },
              )
            }
          }
        }
        StartupState.Failed -> AnAnNotesTheme(themeMode = ThemeMode.SYSTEM) {
          StartupRecoveryGate(recovering = false, onRetry = ::recoverBeforeShowingApp)
        }
      }
    }
  }

  /** Completes durable rollback/forward recovery before any note UI or Room-backed VM is created. */
  private fun recoverBeforeShowingApp() {
    startupState = StartupState.Recovering
    lifecycleScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          val repository = BackupRepository(applicationContext)
          repository.recoverPendingRestore()
          repository.recoverAbandonedInspectionFiles()
          OfflineOcrWorker.recoverPending(applicationContext)
        }
      }.onSuccess {
        startupState = StartupState.Ready
      }.onFailure {
        // Recovery failures may be adjacent to private state; keep details out of logs and UI.
        startupState = StartupState.Failed
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    incomingShare = ShareImportHandler.parse(intent)
  }
}

private sealed interface StartupState {
  data object Recovering : StartupState
  data object Ready : StartupState
  data object Failed : StartupState
}

@Composable
private fun StartupRecoveryGate(recovering: Boolean, onRetry: () -> Unit) {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        if (recovering) {
          CircularProgressIndicator()
          Text("正在安全恢复本地数据…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
          Text("本地数据恢复未完成", style = MaterialTheme.typography.titleMedium)
          Text("笔记暂未打开，请重试。", color = MaterialTheme.colorScheme.onSurfaceVariant)
          Button(onClick = onRetry) { Text("重试") }
        }
      }
    }
  }
}

package com.anan.anannotes.ui.navigation

import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.anan.anannotes.core.model.ThemeMode
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.anan.anannotes.core.model.UNCATEGORIZED_FOLDER_ID
import com.anan.anannotes.feature.home.HomeScreen
import com.anan.anannotes.feature.home.MainBottomBar
import com.anan.anannotes.feature.editor.EditorScreen
import com.anan.anannotes.feature.settings.SettingsScreen
import com.anan.anannotes.feature.settings.ExportHistoryScreen
import com.anan.anannotes.feature.settings.StorageOverviewScreen
import com.anan.anannotes.feature.settings.PrivacyNoticeScreen
import com.anan.anannotes.feature.backup.BackupRestoreScreen
import com.anan.anannotes.feature.search.SearchScreen
import com.anan.anannotes.feature.todo.TodoScreen
import com.anan.anannotes.feature.privatemodule.PrivateUnlockScreen
import com.anan.anannotes.core.model.DisplaySettings
import com.anan.anannotes.feature.imports.ShareImportPayload
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

@Serializable
data object TodoRoute : NavKey

@Serializable
data class EditorRoute(
  val noteId: String? = null,
  val folderId: String = UNCATEGORIZED_FOLDER_ID,
  val importText: String? = null,
  val importUris: List<String> = emptyList(),
  val importRequestId: String? = null,
) : NavKey

@Serializable
data object SearchRoute : NavKey

@Serializable
data object SettingsRoute : NavKey

@Serializable
data object ExportHistoryRoute : NavKey

@Serializable
data object StorageOverviewRoute : NavKey

@Serializable
data object BackupRestoreRoute : NavKey

@Serializable
data object PrivacyNoticeRoute : NavKey

@Serializable
data object PrivateRoute : NavKey

@Composable
fun AppNavigation(
  modifier: Modifier = Modifier,
  displaySettings: DisplaySettings = DisplaySettings(),
  onDisplaySettingsChange: (DisplaySettings) -> Unit = {},
  onClearPreviewCache: () -> Unit = {},
  cacheMessage: String? = null,
  incomingShare: ShareImportPayload? = null,
  onShareImportConsumed: () -> Unit = {},
  onSystemBarDarkIconsChange: (Boolean) -> Unit = {},
  onEditorStatusBarDarkIconsChange: (Boolean) -> Unit = {},
  onEditorNavigationBarDarkIconsChange: (Boolean) -> Unit = {},
) {
  val backStack = rememberNavBackStack(HomeRoute)
  val appUsesDarkSurface = when (displaySettings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }
  val isEditorRoute = backStack.lastOrNull() is EditorRoute
  val showDarkSystemBarIcons = backStack.lastOrNull() != PrivateRoute && !appUsesDarkSurface
  if (isEditorRoute) {
    SideEffect { onEditorNavigationBarDarkIconsChange(showDarkSystemBarIcons) }
  } else {
    SideEffect { onSystemBarDarkIconsChange(showDarkSystemBarIcons) }
  }
  var currentFolderForImport by remember { mutableStateOf(UNCATEGORIZED_FOLDER_ID) }
  val returnToHome: () -> Unit = {
    backStack.clear()
    backStack.add(HomeRoute)
    Unit
  }
  val returnToTodo: () -> Unit = {
    backStack.clear()
    backStack.add(TodoRoute)
    Unit
  }
  val popRoute: () -> Unit = {
    if (backStack.size > 1) backStack.removeLastOrNull()
  }

  LaunchedEffect(incomingShare?.requestId) {
    incomingShare?.let { payload ->
      backStack.add(
        EditorRoute(
          folderId = currentFolderForImport,
          importText = payload.text,
          importUris = payload.uris.map(Uri::toString),
          importRequestId = payload.requestId,
        ),
      )
      onShareImportConsumed()
    }
  }

  NavDisplay(
    backStack = backStack,
    onBack = popRoute,
    modifier = modifier,
    transitionSpec = pageForwardTransition(),
    popTransitionSpec = pagePopTransition(),
    predictivePopTransitionSpec = pagePredictivePopTransition(),
    entryProvider =
      entryProvider {
        entry<HomeRoute> {
          HomeScreen(
            onNewNote = { folderId -> backStack.add(EditorRoute(folderId = folderId)) },
            onImportFile = { folderId, uri ->
              backStack.add(
                EditorRoute(
                  folderId = folderId,
                  importUris = listOf(uri.toString()),
                  importRequestId = UUID.randomUUID().toString(),
                ),
              )
            },
            onSelectedFolderChanged = { currentFolderForImport = it },
            onOpenNote = { noteId -> backStack.add(EditorRoute(noteId = noteId)) },
            onSearch = { backStack.add(SearchRoute) },
            onSettings = { backStack.add(SettingsRoute) },
            onPrivate = { backStack.add(PrivateRoute) },
            onStorage = { backStack.add(StorageOverviewRoute) },
            onTodo = returnToTodo,
            onHome = returnToHome,
          )
        }
        entry<TodoRoute> {
          TodoScreen(
            onHome = returnToHome,
            onTodo = returnToTodo,
            onOpenNote = { noteId -> backStack.add(EditorRoute(noteId = noteId)) },
          )
        }
        entry<EditorRoute> { route ->
          EditorScreen(
            noteId = route.noteId,
            folderId = route.folderId,
            initialImportText = route.importText,
            initialImportUris = route.importUris,
            importRequestId = route.importRequestId,
            displaySettings = displaySettings,
            onDisplaySettingsChange = onDisplaySettingsChange,
            onStatusBarDarkIconsChange = onEditorStatusBarDarkIconsChange,
            isStatusBarOwner = backStack.lastOrNull() == route,
            onStorage = { backStack.add(StorageOverviewRoute) },
            onBack = popRoute,
          )
        }
        entry<SearchRoute> {
          SearchScreen(
            onBack = popRoute,
            onOpenNote = { noteId -> backStack.add(EditorRoute(noteId = noteId)) },
          )
        }
        entry<SettingsRoute> {
          SettingsScreen(
            settings = displaySettings,
            onSettingsChange = onDisplaySettingsChange,
            onClearPreviewCache = onClearPreviewCache,
            cacheMessage = cacheMessage,
            onBack = popRoute,
            onOpenExportHistory = { backStack.add(ExportHistoryRoute) },
            onOpenStorageOverview = { backStack.add(StorageOverviewRoute) },
            onOpenBackupRestore = { backStack.add(BackupRestoreRoute) },
            onOpenPrivacyNotice = { backStack.add(PrivacyNoticeRoute) },
          )
        }
        entry<PrivacyNoticeRoute> { PrivacyNoticeScreen(onBack = popRoute) }
        entry<ExportHistoryRoute> { ExportHistoryScreen(onBack = popRoute) }
        entry<StorageOverviewRoute> { StorageOverviewScreen(onBack = popRoute) }
        entry<BackupRestoreRoute> { BackupRestoreScreen(onBack = popRoute) }
        entry<PrivateRoute> {
          PrivateUnlockScreen(
            onBack = {
              backStack.removeLastOrNull()
              Unit
            },
          )
        }
      },
  )
}

@Composable
private fun SimplePage(title: String, testTag: String, onBack: () -> Unit) {
  Column(modifier = Modifier.fillMaxSize().testTag(testTag).padding(horizontal = 20.dp, vertical = 12.dp)) {
    TextButton(onClick = onBack) { Text("返回") }
    Text(text = title, style = MaterialTheme.typography.headlineMedium)
  }
}

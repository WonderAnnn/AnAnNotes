# 任务简报：普通导出记录与存储概览

## 范围

在 `D:/anan/env-check` 增加普通记录/统计仓库、两个 ViewModel 和两个 Compose 页面。主线同时编写 PNG、服务与导航；你不得改 ExportService.kt、HomeViewModel.kt、AppNavigation.kt、SettingsScreen.kt、核心 ExportModels.kt。可改历史 DAO；其余只新增聚焦文件和测试。无 Git 元数据，不创建提交。用 apply_patch 编辑，不派生子代理。

## 精确契约

主线将新增 `feature.export.ExportHistoryRecorder`：

```kotlin
interface ExportHistoryRecorder {
  suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted)
  suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String)
}
```

现有 `core.model.ExportHistoryModel` 已含 id、noteId、format、fileName、destinationUri、createdAtMillis、succeeded、errorCode。成功 ItemCompleted 是 noteId/fileName/location/destinationName?，其中 location 为实际 URI。失败项存必要元数据与错误代码，destinationUri 为空。仓库实现该 recorder，普通数据库已有 export_history 表和 DAO。

- `data.repository.ExportHistoryRepository(database: AnAnDatabase)` 实现 recorder；公开 `observeLatest(): Flow<List<ExportHistoryModel>>`、`suspend fun clear()`、`suspend fun latest(): List<ExportHistoryModel>`。插入+裁剪 Room 事务，最多 100 条，时间戳相同稳定排序。可注入 clock/idFactory 用于测试。
- `feature.settings.ExportHistoryViewModel(application: Application)` 暴露记录状态和 clear/refresh 功能。`ExportHistoryScreen(viewModel: ExportHistoryViewModel, onBack: () -> Unit)` 或可测试 stateless overload；成功文件可以 ACTION_VIEW/ACTION_SEND，content URI 加只读临时授权，先检查存在/可访问；失败/缺失/无应用打开明确反馈。清空记录绝不删除文件。简体中文，中性卡片，CompactPageHeader 返回箭头。
- `data.repository.StorageOverviewRepository` 只统计普通 DB (`context.getDatabasePath("anan-notes.db")` + WAL/SHM)、filesDir/notes/assets、cacheDir/notes/previews。禁止遍历整个 filesDir/cacheDir，不触及 private 路径，不能把其他缓存算入预览。
- 导出统计来自成功记录 URI 去重，使用 ContentResolver 查询文件大小；未知/不可访问不能伪装 0 B，分别计数。明确仅是“记录中可访问的导出文件”，不含从前被裁剪/清空记录的导出，不算进应用内部总量。
- `feature.settings.StorageOverviewViewModel(application: Application)` 和 `StorageOverviewScreen(viewModel: StorageOverviewViewModel, onBack: () -> Unit)`。清理仅 `PreviewCacheStore(context.cacheDir).clear()`，后台 IO 执行，刷新统计、反馈，无全盘权限。
- 页面使用 Modifier.safeDrawingPadding() 保护新页面，不同时 statusBarsPadding/navigationBarsPadding；不要此轮重构其他页面。
- 无联网、全盘相册/文件权限；普通页不显示私密存在性/数量/大小。

## 测试与调度

先写能失败的定向测试，向主线提供 RED 运行命令和文件名，等待主线返回结果再实现。Gradle/ADB 由主线独占，勿自行构建避免竞态。可以在等待时读已有 UI/数据模式。优先有纯 JVM 统计边界测试和 Room Android 100 条/重开/clear 测试、简洁 UI 点击测试。不要依赖用户笔记数据；使用临时目录/内存数据库。

## 交付

完整自审报告写到 `D:/anan/docs/superpowers/plans/2026-09-28-export-history-storage-report.md`，说明改动文件、实际 API、测试覆盖、未验证项/疑虑；不伪造测试运行证据。最终回传 DONE 或 DONE_WITH_CONCERNS + 文件列表与报告路径（简短）。

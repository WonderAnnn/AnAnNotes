# 普通导出记录与存储概览：自审报告

状态：DONE_WITH_CONCERNS。简报范围内的仓库、文件操作、两个 ViewModel、两个 Compose 页面及 28 个定向测试已交付。主线已确认统计 JVM 7/7 GREEN；最终 Android/JVM 回归由主线统一运行，尚未收到其结果。

## 范围与协作

- 按 `2026-09-28-export-history-storage-brief.md` 的精确契约直接编辑共享工作区。
- 未创建 Git 仓库、分支或 worktree，未提交；未派子代理；未运行 Gradle 或 ADB。
- 未编辑 PNG、ExportHistoryRecorder.kt、ExportService.kt、HomeViewModel.kt、AppNavigation.kt、SettingsScreen.kt、核心 ExportModels.kt。
- 主线负责 recorder、服务、导航及所有 RED/GREEN 运行。
- 已完整阅读主线新增的 export-storage 和 edge-to-edge 方针；本轮仅对新增两页应用单层 safeDrawingPadding，不重构其他页面。
- 使用 executing-plans、test-driven-development、verification-before-completion：先交测试，收到主线对应 RED 后实现；验证证据区分主线回传和本任务静态自审。

## 已改文件

以下路径相对 `D:/anan/env-check`：

| 文件 | 变化 |
| --- | --- |
| `app/src/main/java/com/anan/anannotes/data/local/Daos.kt` | 仅修改 ExportHistoryDao：一致的 `createdAtMillis DESC, rowid DESC` 排序，新增 getLatest，裁剪使用相同排序 |
| `app/src/main/java/com/anan/anannotes/data/repository/ExportHistoryRepository.kt` | 新增 recorder 实现，Room 事务内插入及裁剪到 100 条 |
| `app/src/main/java/com/anan/anannotes/data/repository/StorageOverview.kt` | 新增统计结果和已知／未知／不可访问大小类型 |
| `app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt` | 新增限定目录统计、成功 URI 去重统计及 IO 预览缓存清理 |
| `app/src/main/java/com/anan/anannotes/data/repository/ContentResolverExportFileSizeReader.kt` | SIZE 查询加只读描述符访问检查，未知 SIZE 回退 statSize |
| `app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryFileActions.kt` | 检查实际 content URI 后打开／分享；只读临时授权与明确失败反馈 |
| `app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryViewModel.kt` | StateFlow、记录观察、刷新、清空、打开／分享与反馈；含 ExportHistoryUiState |
| `app/src/main/java/com/anan/anannotes/feature/settings/StorageOverviewViewModel.kt` | StateFlow、IO 统计／清理后刷新、加载与错误反馈；含 StorageOverviewUiState |
| `app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryScreen.kt` | 默认 ViewModel 和 stateless 重载；历史卡片、空状态、返回、清空确认、操作按钮 |
| `app/src/main/java/com/anan/anannotes/feature/settings/StorageOverviewScreen.kt` | 默认 ViewModel 和 stateless 重载；内部分类、外部导出覆盖范围、清理与反馈 |
| `app/src/test/java/com/anan/anannotes/data/repository/StorageOverviewRepositoryTest.kt` | 7 个 JVM 测试 |
| `app/src/androidTest/java/com/anan/anannotes/data/repository/ExportHistoryRepositoryTest.kt` | 6 个 Room 测试 |
| `app/src/androidTest/java/com/anan/anannotes/data/repository/ContentResolverExportFileSizeReaderTest.kt` | 6 个 provider 边界测试 |
| `app/src/androidTest/java/com/anan/anannotes/feature/settings/ExportHistoryFileActionsTest.kt` | 6 个文件操作测试，使用 API 29 可用的 Intent extra 读取方式 |
| `app/src/androidTest/java/com/anan/anannotes/ui/ExportHistoryAndStorageScreenTest.kt` | 3 个 stateless Compose 点击测试及根标签检查；已移除错误 import，语义读取在 runOnIdle 外 |

报告文件：`D:/anan/docs/superpowers/plans/2026-09-28-export-history-storage-report.md`。

## 已实现 API

```kotlin
ExportHistoryRepository(
  database: AnAnDatabase,
  nowMillis: () -> Long = System::currentTimeMillis,
  newId: () -> String = { UUID.randomUUID().toString() },
) : ExportHistoryRecorder

fun observeLatest(): Flow<List<ExportHistoryModel>>
suspend fun latest(): List<ExportHistoryModel>
suspend fun clear()
suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted)
suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String)

StorageOverviewRepository(context: Context, historyRepository: ExportHistoryRepository = ...)
suspend fun refresh(): StorageOverview
suspend fun clearPreviewCache(): Int

// 无 Android 方法调用的文件/历史/reader 注入构造，用于 JVM 临时目录测试。
StorageOverviewRepository(
  databaseFile: File,
  filesDir: File,
  cacheDir: File,
  historyProvider: suspend () -> List<ExportHistoryModel>,
  exportFileSizeReader: ExportFileSizeReader,
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)

fun interface ExportFileSizeReader { fun read(uriString: String): ExportFileSize }
ContentResolverExportFileSizeReader(resolver: ContentResolver) : ExportFileSizeReader
```

`StorageOverview` 提供 databaseBytes、assetBytes、previewBytes、internalUnreadableEntries、exportedFiles；internalBytes 仅为前三项之和。

`ExportStorageSummary` 提供 recordedFileCount、knownBytes、knownSizeCount、unknownSizeCount、unavailableCount、accessibleFileCount。未知和不可访问均不能等同于已知 0 B。

页面与 ViewModel 的实际公共接口：

```kotlin
ExportHistoryViewModel(application: Application)
val state: StateFlow<ExportHistoryUiState>
fun refresh()
fun clear()
fun open(record: ExportHistoryModel)
fun share(record: ExportHistoryModel)

StorageOverviewViewModel(application: Application)
val state: StateFlow<StorageOverviewUiState>
fun refresh()
fun clearPreviewCache()

@Composable
fun ExportHistoryScreen(
  viewModel: ExportHistoryViewModel = viewModel(),
  onBack: () -> Unit,
)

@Composable
fun StorageOverviewScreen(
  viewModel: StorageOverviewViewModel = viewModel(),
  onBack: () -> Unit,
)

@Composable
fun ExportHistoryScreen(
  state: ExportHistoryUiState,
  onBack: () -> Unit,
  onClear: () -> Unit,
  onRefresh: () -> Unit,
  onOpen: (ExportHistoryModel) -> Unit,
  onShare: (ExportHistoryModel) -> Unit,
)

@Composable
fun StorageOverviewScreen(
  state: StorageOverviewUiState,
  onBack: () -> Unit,
  onRefresh: () -> Unit,
  onClearPreviewCache: () -> Unit,
)

ExportHistoryFileActions(
  context: Context,
  fileSizeReader: ExportFileSizeReader = ContentResolverExportFileSizeReader(context.contentResolver),
  ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
)
suspend fun open(record: ExportHistoryModel): String?
suspend fun share(record: ExportHistoryModel): String?
```

可直接使用主线的 `ExportHistoryScreen(onBack = ...)` 和 `StorageOverviewScreen(onBack = ...)` 调用；也可显式注入 ViewModel。FileActions 返回 null 表示启动成功，返回简体中文文本表示失败。

根标签分别为 `export-history-screen`、`storage-overview-screen`；标题标签为 `export-history-page-header`、`storage-overview-page-header`。主线拥有的 `settings-export-history`、`settings-storage-overview` 入口未由本任务编辑。

`ExportHistoryUiState` 包含 records、isLoading、isClearing、message；`StorageOverviewUiState` 包含 nullable overview、isLoading、isClearing、message。未加载统计时使用 null，不将未读数据画成 0 B。

## 测试覆盖

- JVM：只统计普通 DB/WAL/SHM、notes/assets、notes/previews；排除其他数据库、私密 fixture、草稿、导入缓存和其他缓存；缺失目录；成功 URI 去重；失败记录排除；外部导出不计入内部合计；访问异常；真实空文件；清理只删除预览文件。
- Room：成功保存实际 URI/文件名；失败仅保存元数据和错误码且 URI 为空；同时间戳 125 条裁剪为最近插入的 100 条；裁剪失败回滚插入；并发上限；清空及裁剪保留导出文件；重开保持顺序及已清空状态。
- Provider：未知或负 SIZE 使用可访问描述符的 statSize；可访问管道的未知大小保留为 Unknown；未知 SIZE 且拒绝访问为 Unavailable；SIZE 已知仍检查拒绝／文件缺失；缺失 SIZE 列也检查访问。
- 文件操作：实际 URI 和 MIME；ACTION_VIEW/ACTION_SEND；send/chooser/ClipData 只读授权；失败记录、不可访问、无应用、file URI 拒绝。
- Compose：成功操作按钮、失败码、清空确认、空状态与反馈、返回和刷新、统计与预览清理回调、普通页不显示私密文案。

测试只使用临时目录、内存或临时 Room 数据库、进程内 ContentProvider、截获启动 Intent 的 ContextWrapper 和 stateless 页面，不依赖用户笔记。

## 实际验证证据

以下均为主线回传，本任务没有启动构建：

1. 历史仓库 RED：2026-09-28 本轮，session 67327，24 秒。主线运行 connectedDebugAndroidTest 指向 RasterRenderer；compileDebugAndroidTestKotlin 因 ExportHistoryRepository/recordSuccess/latest 缺失失败。收到该结果后实现历史仓库。
2. 统计仓库 RED：2026-09-28 本轮，session 77434。主线运行 `:app:testDebugUnitTest --tests '*ExportServiceTest'` 时编译所有 JVM 测试；compileDebugUnitTestKotlin 因 ExportFileSize/ExportFileSizeReader/StorageOverviewRepository 缺失失败。收到该结果后实现统计仓库。
3. UI/FileActions RED：2026-09-28 本轮，session 19720，18 秒，`:app:connectedDebugAndroidTest` 在 compileDebugAndroidTestKotlin 因缺 ExportHistoryFileActions、ExportHistoryScreen/UiState、StorageOverviewScreen/UiState 失败。收到该结果后完成对应实现。
4. 统计 JVM GREEN：主线明确回传 `StorageOverviewRepositoryTest` 7/7 通过；另回传主线 PNG/Service/Feedback 共 29 项定向 GREEN，此处不将这些计为本任务 28 项全通过。
5. 主线编译完整页面时反馈唯一生产错误为 StorageOverviewScreen 缺 CompactPageHeader import；该 import 已补入。测试中无效的 `assertDoesNotExist` 顶层 import 也已去掉，该断言使用交互成员。
6. 最终 Android 仓库/provider/FileActions/页面测试、完整 JVM、Lint、APK 和设备回归：待主线统一运行。当前只确认统计 7/7 GREEN，不宣称 28/28 或最终构建成功。

上述 RED 为新增 API 缺失的编译级 RED，并非已运行断言的失败证据。测试自身 import 错误已单独修正，没有将其计作功能缺失的 RED。

主线运行命令（工作目录 `D:/anan/env-check`）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.anan.anannotes.data.repository.StorageOverviewRepositoryTest"
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.data.repository.ExportHistoryRepositoryTest,com.anan.anannotes.data.repository.ContentResolverExportFileSizeReaderTest,com.anan.anannotes.feature.settings.ExportHistoryFileActionsTest,com.anan.anannotes.ui.ExportHistoryAndStorageScreenTest"
```

## 完整自审结论与待验证项

- 历史仓库只访问普通数据库；清空和裁剪没有任何文件删除逻辑。插入和裁剪均在一个 withTransaction 内；读取和裁剪的排序完全一致。
- 统计只逐个读取普通 DB/sidecar，并进入两棵指定子树；不遍历 filesDir/cacheDir 根目录。子树遍历不跟随符号链接；指定目录的中间符号链接被拒绝。
- 导出统计仅遍历成功历史中的唯一 URI。SIZE 查询的缺失、负值或查询异常均继续执行轻量只读 openFileDescriptor；无法打开为 Unavailable，可打开但仍无大小为 Unknown，合法 0 才为 Known(0)。
- 预览清理在 IO 上调用现有 PreviewCacheStore(cacheDir).clear()。保留该 store 原有的直接子文件清理语义，统计刷新可以仍包含尚存的嵌套预览文件。
- 两个 ViewModel 只构造普通仓库。存储统计及清理不在主线程执行；清理完成后重新读取统计，清理成功但刷新失败时单独提示。取消异常均重新抛出。
- 文件操作先验证成功记录和 content URI，再在 IO 上检查访问；可读但大小未知的文件仍可打开／分享。URI 通过 data 或 EXTRA_STREAM 及 ClipData 传递，send 和 chooser 仅授予临时 READ；没有 WRITE 或 PERSISTABLE 授权。ActivityNotFoundException、SecurityException 和其他启动失败均返回明确反馈。
- 原始 file URI 被拒绝，不在失败反馈中回显内部路径。打开／分享的副作用在 StateFlow.update 之外执行，避免原子更新重试时重复启动应用。
- 两页背景铺满窗口，各只应用一次 safeDrawingPadding，未叠加 statusBarsPadding/navigationBarsPadding；使用 CompactPageHeader 返回箭头、中性圆角卡片和可滚动内容。主线的入口与导航文件未被改动。
- 历史显示实际文件名、格式、时间、成功／错误码和保存位置。MediaStore 位置显示为系统相册导出目录；能从普通 SAF URI 解码的 primary 文档目录显示实际目录，其余显示系统文件提供方，不猜测未知提供方的物理路径。
- 存储页明确仅是“记录中可访问的导出文件”；裁剪／清空后的导出不可追溯，外部导出不计入内部合计。只有 knownSizeCount 大于 0 时显示已知大小合计；未知／不可访问单独计数，避免误导为 0 B。
- 测试自身兼容性自审修正了 API 33 才提供的 Parcelable extra 重载，改用 minSdk 29 可用的泛型旧接口；Compose 语义读取移出 runOnIdle，避免在 UI 线程执行测试同步。
- 未添加权限或联网逻辑，未改数据库结构或迁移。本任务执行了文件读取及限定搜索的静态自审，未运行 Gradle/ADB；静态检查不能替代最终编译和设备测试。
- provider 测试使用 [Android 官方 ContentResolver.wrap(ContentProvider) API](https://developer.android.com/reference/android/content/ContentResolver#wrap(android.content.ContentProvider))，应用 minSdk 已为 29，无需改 Manifest 注册测试 provider。
- 交付后待主线的 Android GREEN 覆盖 Room 裁剪回滚与重开、provider 管道和访问拒绝、只读 Intent 与页面点击；主线已告知导航和设置入口接入。真实接收应用的打开／分享、OEM 权限撤销行为，以及 API 29/35/36、横屏、切口、大字体完整矩阵均未在本任务验收，仍按主方针归入后续或主线验收。

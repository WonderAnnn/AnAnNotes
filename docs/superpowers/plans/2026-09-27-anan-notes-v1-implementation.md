# 安安简记 V1 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 subagent-driven-development（推荐）或 executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在现有 Android 骨架上实现一个 Android 10+、本地优先、隐私隔离、支持混合编辑和多格式导出的安安简记 V1。

**架构：** 使用 Kotlin + Jetpack Compose 构建单 Activity 应用，按“领域模型、数据仓库、业务用例、页面状态、Compose UI”分层。普通笔记和私密笔记使用不同的数据边界与搜索索引；图片原图、预览缓存、导出文件和备份文件由独立文件服务管理。所有耗时任务通过协程在后台执行，并以可观察状态返回 UI。

**技术栈：** Kotlin、Jetpack Compose、Material 3、Room、Kotlin Coroutines/Flow、Kotlinx Serialization、Android Photo Picker、Storage Access Framework、Android Sharesheet、Android `PdfDocument`、MediaStore、Android Keystore 辅助能力；离线 OCR 使用通过体积、许可证和设备效果门槛的内置中英混合模型。

**规格：** `docs/superpowers/specs/2026-09-26-anan-notes-v1-design.md`

**总体技术设计：** `docs/superpowers/specs/2026-09-27-anan-notes-v1-technical-design.md`。实现每项任务时同时核对其中的接口、数据边界和测试契约。

**富文本专项实现计划：** [富文本编辑实现计划](2026-09-27-anan-notes-rich-text-implementation.md)，按专项规格单独执行，不替代此 V1 总体计划中的 OCR、导出和备份任务。

**当前导出与下一步入口（2026-09-29 收尾核对）：** [高清导出、记录和存储实施方针](2026-09-28-anan-notes-export-storage-guideline.md) 是本轮推荐方案的详细契约和最新证据入口；本轮四项普通功能已实现，验证结果见 [回归记录](../verification/2026-09-28-export-storage-verification.md)。后续先按 [全面屏与异形屏方针](2026-09-28-anan-notes-edge-to-edge-guideline.md) 执行 E1，再按 E2–E6 推进。下方各“检查点/增量”保留当时版本的历史测试数字和边界，不应把旧“未实现”表述当作当前功能清单。任务 10 未整体勾选：私密验证导出与独立加密记录、编辑页单条快速导出、真实文件提供器和小米验收仍需完成。

## 全局约束

- 最低支持 Android 10（API 29）；首版应用安装包目标不超过 300 MB。
- Manifest 不声明 `INTERNET`，不申请通讯录、电话、短信、定位、通知或广泛媒体读取权限。
- 图片选择使用 Photo Picker；文件导入、导出和备份使用 Storage Access Framework；QQ、微信只通过用户主动触发的系统分享进入应用。
- 普通搜索、普通待办、普通导出记录和系统最近任务预览不得显示私密模块存在或内容。
- 本地 JVM 单元测试可使用 Gradle `--tests` 过滤；Android connected instrumentation test 不使用 `--tests`，按类过滤时使用 `-Pandroid.testInstrumentationRunnerArguments.class=完整测试类名`，否则运行完整设备测试套件。详见 [Android 官方命令行测试说明](https://developer.android.com/studio/test/command-line)。
- 私密数据使用一个随机数据密钥；密码和恢复密钥分别通过 Argon2id 派生保护密钥，再分别以 AES-256-GCM 封装数据密钥。
- 首页默认一级文件夹、自适应双列卡片、文字优先、第一张图片缩略图；窄屏或大字体时自动转单列。
- 编辑器首行自动作为标题，但首行仍保留在正文；空内容不落库；自动保存和异常草稿恢复必须可测试。
- 图片是独立内容块，保持原始比例；宽度使用百分比；原图和预览缓存分离，清理缓存不能删除原图。
- OCR 原图先插入，识别异步进行；识别期间显示进度和百分比；取消后保留原图，不保留未完成 OCR 文本。
- 导出包含 A4 PDF、600 DPI 分页 PNG、长图 PNG、1080 × 1080、1080 × 1350、TXT、Markdown ZIP。
- A4 600 DPI 图像必须逐页渲染和写入，不能把整本多页文档一次性加载到内存。
- 视觉系统以石墨黑、白色和冷灰为主；低饱和蓝灰只用于小面积进度、选中和状态；不使用大面积蓝色、黄色、红色或无功能意义的宣传副标题。
- 每项实现先写失败测试，再写最小实现；每个任务结束时运行对应单元测试、组合测试或设备验收。

**最近检查点（2026-09-27）：** MuMu Android 12（API 32）上的完整 `:app:connectedDebugAndroidTest` 28/28 通过；JVM 单元测试此前为 59/59 通过。Lint 有 6 条 Warning、无 Error；Debug APK 为 39,326,301 字节（约 37.50 MiB），不是 release 包体积验收结果。构建契约脚本确认 applicationId、minSdk、无 INTERNET 权限和应用名。迁移测试现同时验证旧笔记标题与正文升级后均可搜索；仪器测试含搜索/待办 UI、私密数据仓库隔离及 Argon2id 探针。本次未安装或操作小米真机。MuMu 默认 KDF 样本为 446 ms，PSS 增量约 66.7 MiB；Xiaomi/低内存设备性能、恢复密钥交互与后台锁定专项验收仍待完成。

**最近检查点（2026-09-28）：** 完成一轮界面问题修整与回归：文件夹底部面板可下滑关闭且按钮等宽；待办编辑使用单行输入和 IME Next，新待办不会把后续输入吞进同一块，待办视觉使用低饱和主题底色；`adjustResize` 已移至 Activity，普通编辑工具栏与选图栏均处理 IME inset，新增运行时窗口模式回归断言；笔记卡片优先读取预览缓存并在缺失时回退原图；搜索/设置共用适配状态栏与刘海安全区的紧凑返回栏，搜索框改为圆角紧凑样式，设置滑杆取消突兀刻度。新增/修正仪器回归，避免测试共享数据库互相污染。MuMu Android 12（API 32）完整 `:app:connectedDebugAndroidTest` 最新为 77/77 通过；JVM `:app:testDebugUnitTest` 为 105/105 通过；`:app:lintDebug` 无错误，报告 7 条 Warning、2 条 Hint。Debug APK 为 39,156,643 字节（约 37.34 MiB）；`scripts/verify-build-contract.ps1` 的 applicationId、minSdk、无 INTERNET 权限、应用名四项均通过。MuMu 内置 Sogou 输入法未在自动化/截图中呈现实际键盘画面，因此工具栏与真实键盘间距仍需在软键盘正常显示的设备上视觉验收；本轮未连接或操作小米真机。导出入口与导出服务尚未实现，因此目前不在“更多”菜单中；按任务 10 的既有规格作为下一实现模块。

**最近检查点（2026-09-28，普通导出稳定性加固）：** 首页当前开放 TXT 与 Markdown ZIP；导出完成/部分失败反馈含实际文件夹名、成功文件名与失败代码；SAF 写入回执反馈实际显示名，批次互斥防事件交错，忙碌状态阻止重复触发，目录选择器期间格式状态可在 Activity 重建后恢复。事件通过缓冲 Channel 跨 Activity 重建保留，终态事件自带全批次条目。SAF 文件存储与 Android DocumentsContract 访问分层；若失败清理也失败，会显示不完整文件名并要求手动删除。Markdown 文件 MIME 筛选包含标准及泛型提供器类型，应用仍校验文件扩展名/MIME；Markdown 正文、引用、待办和标题转义字面标记，基础文件名限制为 180 UTF-8 字节。JVM 138/138、MuMu Android 12/API 32 instrumentation 81/81；Lint 0 Error、7 Warning、2 Hint；Debug APK 40,069,521 字节（约 38.22 MiB）；构建契约检查通过。自动化已验证文件存储边界，但真实系统 SAF 目录授权/落盘、文件管理器打开与 Xiaomi 真机仍待验收；系统选择器期间旋转尚未端到端手工验证。PDF／PNG、MediaStore、导出历史和存储概览未实现；任务 10 步骤 2–6 保持未完成。导入 OCR 与 QQ／微信真机分享测试仍待完成。当前工作区没有 Git 元数据，无 SHA 差异或提交。

**最近检查点（2026-09-28，A4 PDF 增量）：** 普通批量导出现在新增 PDF（A4）选项；渲染器用 Android `PdfDocument` 逐页输出、文字保留矢量、读取笔记皮肤和默认/单条字号与行距、绘制标题/正文/引用/待办/图片/OCR 块，并对原图采样解码以限制瞬时内存。代码审查发现首页超长标题未分页，已添加设备回归并修正为逐行分页；标题、皮肤标记与正文现在按顺序续页。完成提示为 ViewModel 中带 ID 的待确认队列，Activity 重建后可重放，连续导出逐条排队显示，迟到确认不会清掉新结果。导入选择器 MIME 覆盖 Markdown、图片与泛型内容提供器类型，解析仍在应用层校验。最终 JVM 全量 145/145；MuMu Android 12/API 32 全量 85/85；Lint 0 Error、7 Warning、2 Hint；Debug APK 40,074,765 字节（约 38.22 MiB）；构建契约检查通过。审查中无 Critical；唯一 Important 标题分页问题已修复并有设备回归。低风险验证缺口为 Snackbar 旋转/重建端到端测试和大图采样边界的设备级断言。尚未在小米真机或真实系统文件管理器验证 SAF 实际落盘与打开。600 DPI 分页 PNG、长图/朋友圈 PNG、MediaStore、导出历史和存储概览仍未实现；导入 OCR 及 QQ／微信真机分享验收仍未完成，任务 9 步骤 4–5 和任务 10 步骤 2–6 继续保持未完成。本工作区没有 Git 元数据。

**最近检查点（2026-09-28，朋友圈 PNG 增量）：** 首页导出面板现提供 1080 × 1080 方图与 1080 × 1350 竖图；社交 PNG 支持主题/字体/行距、富文本块及保持比例的图片块，长内容逐页输出。MediaStore 在 `Pictures/AnAn Notes/Exports/` 暂存后发布，失败时清理未完成项，不新增广泛存储权限。最终 JVM 150/150、MuMu Android 12/API 32 instrumentation 91/91；Lint 0 Error、9 Warning、2 Hint；Debug APK 40,089,345 字节（约 38.23 MiB）；构建契约检查通过。A4 600 DPI PNG、长图、导出记录/存储概览、真实 Xiaomi 与文件管理器手工验收仍待完成；工作区没有 Git 元数据。

**最新检查点（2026-09-28 设备回归；2026-09-29 收尾核对）：** A4 600 DPI PNG（4961×7016、pHYs 23622 px/m）、1080 px 单张长图、普通导出记录和存储概览完成。新增流式逐行编码与 128 行画带，记录最多 100 条并使用实际发布回执；清空历史不删文件、缓存清理不动原图、普通统计不探测私密目录。PNG 跨画带文字/跨页行序、真实 Job 取消链、分隔线描边和屏外 TXT 测试定位的审查问题均已关闭。9 月 28 日完整命令 exit 0、MuMu Android 12/API 32 为 125/125；9 月 29 日 JVM 实际重跑 170/170、构建和四项契约检查通过；Lint 0 Error、13 Warning、2 Hint。Debug APK 41,215,925 字节（39.31 MiB），已于 9 月 28 日保留数据安装到 MuMu；收尾时 MuMu 离线，未安装或验收小米真机。下一轮只先执行 E1，未勾选整个全面屏矩阵或 V1。

## 文件与模块清单

现有 `env-check/` 是已生成的 Android 环境骨架，将直接改造成产品工程，不再保留 `com.anan.envcheck` 的示例命名。

**构建与应用入口：**

- 修改：`env-check/settings.gradle.kts`、`env-check/build.gradle.kts`、`env-check/gradle/libs.versions.toml`、`env-check/app/build.gradle.kts`
- 修改：`env-check/app/src/main/AndroidManifest.xml`、`env-check/app/src/main/res/values/strings.xml`、`env-check/app/src/main/res/values/themes.xml`
- 创建：`env-check/scripts/verify-build-contract.ps1`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/AnAnNotesApp.kt`
- 修改：`env-check/app/src/main/java/com/anan/envcheck/MainActivity.kt`，迁移到 `com.anan.anannotes.MainActivity`

**核心领域与数据：**

- 创建：`env-check/app/src/main/java/com/anan/anannotes/core/model/NoteModels.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/core/model/BlockModels.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/core/model/FolderModels.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/core/model/TodoModels.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/core/model/SearchModels.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/core/model/ExportModels.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/data/local/AnAnDatabase.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/data/local/Entities.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/data/local/Daos.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/data/local/NoteSearchFts.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/data/repository/NoteRepository.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/data/repository/FolderRepository.kt`

**主题、导航与页面：**

- 创建：`env-check/app/src/main/java/com/anan/anannotes/ui/theme/AnAnColors.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/ui/theme/AnAnTheme.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/ui/theme/AnAnTypography.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/ui/navigation/AppNavigation.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/home/HomeScreen.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/home/HomeViewModel.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/editor/EditorScreen.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/editor/EditorViewModel.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/todo/TodoScreen.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/search/SearchScreen.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/settings/SettingsScreen.kt`

**导入、OCR、导出和隐私：**

- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/imports/ShareImportHandler.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/imports/FileImportService.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/ocr/OcrEngine.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/ocr/OcrWorker.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/export/ExportService.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/export/ExportHistoryRepository.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/backup/BackupService.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/feature/private/PrivateStore.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/security/PrivateCrypto.kt`
- 创建：`docs/superpowers/security/2026-09-27-anan-notes-private-crypto.md`

**测试：**

- 创建：`env-check/app/src/test/java/com/anan/anannotes/core/NoteRulesTest.kt`
- 创建：`env-check/app/src/test/java/com/anan/anannotes/core/SearchRulesTest.kt`
- 创建：`env-check/app/src/test/java/com/anan/anannotes/security/PrivateCryptoTest.kt`
- 创建：`env-check/app/src/test/java/com/anan/anannotes/feature/ExportLayoutTest.kt`
- 创建：`env-check/app/src/test/java/com/anan/anannotes/feature/OcrStateTest.kt`
- 创建：`env-check/app/src/androidTest/java/com/anan/anannotes/ui/HomeScreenTest.kt`
- 创建：`env-check/app/src/androidTest/java/com/anan/anannotes/ui/PrivacyIsolationTest.kt`
- 创建：`env-check/app/src/androidTest/java/com/anan/anannotes/ui/EditorScreenTest.kt`
- 创建：`env-check/app/src/androidTest/java/com/anan/anannotes/ui/ExportFlowTest.kt`

---

### 任务 1：把环境骨架改造成安安简记工程

**文件：**

- 修改：`env-check/settings.gradle.kts`，将根工程名改为 `AnAnNotes`。
- 修改：`env-check/app/build.gradle.kts`，把 namespace、applicationId 改为 `com.anan.anannotes`，将 `minSdk` 改为 29，保留已存在的 Compose/Kotlin/Java 工具链配置。
- 修改：`env-check/app/src/main/AndroidManifest.xml`，迁移 Activity 包名，保留主入口，不增加网络权限。
- 修改：`env-check/app/src/main/res/values/strings.xml`，将应用名改为“安安简记”，补充中英文基础字符串。
- 修改：`env-check/app/src/main/res/values/themes.xml`，将主题名改为 `Theme.AnAnNotes`。
- 迁移：`env-check/app/src/main/java/com/anan/envcheck/` 到 `env-check/app/src/main/java/com/anan/anannotes/`，删除示例 `Greeting`、示例仓库和示例导航页面。

- [x] **步骤 1：先写工程契约测试**

创建 `env-check/scripts/verify-build-contract.ps1`，检查已打包的合并 Manifest 中应用包名为 `com.anan.anannotes`、最小系统版本为 29、没有 `INTERNET` 权限，并检查应用名资源为“安安简记”。检查构建产物而不是 Gradle 配置文本。

- [x] **步骤 2：运行基线测试确认旧骨架无法满足契约**

先执行现有 debug 产物的契约检查：

```powershell
Set-Location D:\anan\env-check
.\scripts\verify-build-contract.ps1
```

预期：当前骨架因包名、API 下限和应用名未迁移而失败；记录失败原因后再修改工程。

- [x] **步骤 3：迁移 namespace、applicationId、包路径和资源主题**

将 `com.anan.envcheck` 全部迁移到 `com.anan.anannotes`；将 `minSdk` 设为 29；确保 Manifest 中没有 `uses-permission android:name="android.permission.INTERNET"`。

- [x] **步骤 4：运行基线测试确认工程可编译**

运行：

```powershell
Set-Location D:\anan\env-check
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

预期：退出码为 0，生成 `env-check/app/build/outputs/apk/debug/app-debug.apk`。

再次运行 `\.\scripts\verify-build-contract.ps1`，预期通过。

- [x] **步骤 5：在 MUMU 或小米手机安装启动**

使用 Android Studio 或以下命令安装：

```powershell
adb devices -l
adb -s <设备序列号> install -r D:\anan\env-check\app\build\outputs\apk\debug\app-debug.apk
adb -s <设备序列号> shell am start -n com.anan.anannotes/.MainActivity
```

同时连接多个设备时必须通过 `-s` 指定目标；无线调试的设备序列号/端口可能变化，应以当次 `adb devices -l` 输出为准。

预期：应用可启动，系统设置中显示“安安简记”，没有权限弹窗和网络权限。

### 任务 2：建立主题、语言和导航骨架

**文件：**

- 创建：`env-check/app/src/main/java/com/anan/anannotes/ui/theme/AnAnColors.kt`、`AnAnTheme.kt`、`AnAnTypography.kt`
- 创建：`env-check/app/src/main/java/com/anan/anannotes/ui/navigation/AppNavigation.kt`
- 修改：`env-check/app/src/main/java/com/anan/anannotes/MainActivity.kt`
- 修改：`env-check/app/src/main/res/values/strings.xml`
- 创建：`env-check/app/src/main/res/values-en/strings.xml`
- 测试：`env-check/app/src/androidTest/java/com/anan/anannotes/ui/AppNavigationTest.kt`

- [x] **步骤 1：编写导航行为测试并验证红灯**

新增加号快速新建 UI 测试。它在 MUMU 上按预期失败：现有占位页没有 `new-note-button`，确认了导航行为尚未实现。测试先完成行为测试；主题颜色将在实现后通过构建和 MUMU 视觉检查确认。

- [x] **步骤 2：实现固定视觉基线**

实现石墨、白、冷灰和低饱和蓝灰色板；关闭未确认的动态大面积取色；为系统深色模式提供对应深色表面和文字层级。Typography 使用系统字体和首版默认字号；全局可调字号、行距接入在任务 6 完成。

- [x] **步骤 3：实现导航状态**

定义 `HomeRoute`、`TodoRoute`、`EditorRoute(noteId)`、`SearchRoute`、`SettingsRoute`、`PrivateRoute` 和辅助页面路由。底部只显示“笔记”和“待办”，私密模块不作为普通底部导航项。

- [x] **步骤 4：运行 Compose 测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

预期：主题和导航测试通过；在没有连接设备时使用 MUMU 模拟器完成 connected test。

实际结果：`connectedDebugAndroidTest` 在 MUMU（Android 12/API 32）通过；首次同时连接小米时，系统拒绝了测试 APK 的自动安装请求。之后只用 MUMU 完成本轮验收。首页截图复核确认浅色系统组件使用石墨/冷灰，没有系统动态蓝紫色。

### 任务 3：实现本地数据模型和普通笔记仓库

**文件：**

- 创建：`core/model/NoteModels.kt`、`BlockModels.kt`、`FolderModels.kt`、`TodoModels.kt`、`ExportModels.kt`
- 创建：`data/local/Entities.kt`、`Daos.kt`、`AnAnDatabase.kt`
- 创建：`data/repository/NoteRepository.kt`、`FolderRepository.kt`、`EntityMappers.kt`
- 测试：`core/NoteRulesTest.kt`、`androidTest/data/NoteRepositoryTest.kt`

- [x] **步骤 1：编写领域规则测试**

测试以下行为：空白笔记不插入；正文有内容时自动生成独立 note ID；每条普通笔记只有一个 folderId；删除用户文件夹会把其中笔记移动到“未分类”；笔记删除进入 30 天回收站而不是直接消失；置顶排序优先于时间排序。
先运行测试确认模型与规则缺失（编译红灯），再实现 `NoteRules` 并通过全部规则测试。

- [x] **步骤 2：定义实体和字段**

`FolderEntity` 保存 id、名称、系统文件夹类型、创建时间和排序位置；`NoteEntity` 保存 id、folderId、标题、创建/更新时间、皮肤、字号覆盖、行间距覆盖、置顶和回收站时间；`BlockEntity` 保存 noteId、顺序、类型、文本或图片引用、图片宽度百分比、对齐方式和 OCR 状态；`TodoEntity` 保存所属 noteId、blockId、文字、完成状态和修改时间；`ExportHistoryEntity` 保存导出元数据但不保存正文或图片副本。

- [x] **步骤 3：实现 Room 数据库和仓库事务**

接入 Room 2.8.5 与 KSP 2.3.12。为文件夹删除、笔记移动、笔记软删除、恢复、永久删除和排序建立事务方法。普通库不包含私密字段或私密正文表；后续私密模块将使用独立加密存储。关闭 Android 自动云备份，保留用户主动控制的手动备份方向。

- [x] **步骤 4：运行规则测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.anan.anannotes.core.NoteRulesTest'
```

结果：`NoteRulesTest` 单元测试通过；后续全量回归在 MUMU Android 12/API 32 上共 8 项 connected tests 通过，覆盖 Room 初始化、正文/图片比例与待办持久化、完成状态保留、文件夹删除迁移、回收站以及普通待办隔离。Room 已生成并检查版本 1 schema。

### 任务 4：首页、文件夹、卡片和批量操作

**文件：**

- 创建：`feature/home/HomeScreen.kt`、`HomeViewModel.kt`、`FolderBar.kt`、`NoteCard.kt`、`HomeSortSheet.kt`、`BulkActionBar.kt`
- 文件夹新增、重命名与删除入口位于首页横向文件夹栏；独立管理页暂不单独建立。
- 创建：`androidTest/java/com/anan/anannotes/ui/HomeScreenTest.kt`

- [x] **步骤 1：编写首页交互测试**

测试首页显示横向文件夹栏、卡片标题/时间/摘要和第一张图片缩略图；测试空状态文案与加号、窄屏或大字体切换为单列、点击卡片打开笔记、长按进入选择模式。已在 MUMU 增加图片缩略图真实绘制、文件夹管理及批量交互 UI 回归；320dp 内容宽度和大字体规则由 JVM 测试覆盖。设备级窄屏/大字体/深色模式组合验收保留在步骤 5。

- [x] **步骤 2：实现首页状态模型**

`HomeUiState` 包含当前文件夹、卡片列表、排序类型、选择集合和提示状态。默认排序为置顶后最近编辑，可切换创建时间；排序入口位于文件夹标题右侧并打开底部面板。列数根据实际可用宽度和字体缩放在 UI 层计算；搜索状态将在搜索任务接入。2026-09-27 已新增 `HomeViewPreferenceEntity`、DAO 和 `HomeViewPreferenceRepository`，按文件夹及“全部”视图分别持久记住排序；Room 1→2 迁移保留旧笔记，MuMu 首页回归与迁移测试通过。

- [x] **步骤 3：实现自适应卡片布局**

使用 `BoxWithConstraints` 和字体缩放计算两列/单列；卡片显示标题、编辑时间、摘要和首图路径，文字优先；图片以采样解码绘制，缺失时保留文字。缩略图缓存的生成/重建将在图片存储任务实现。

- [ ] **步骤 4：实现一级文件夹和批量操作**

支持建立、重命名、删除用户文件夹；删除文件夹时事务性移动到“未分类”。长按后提供全选、移动到文件夹、置顶/取消置顶、导出和删除；批量删除进入回收站并显示数量确认。V1 不额外引入未确认的“归档”操作。
目前已实现文件夹新增、重命名、删除迁移；批量选择、全选/取消、移动、置顶/取消置顶和回收站确认。多选数量及全选/取消已移至顶部，底部仅保留批量操作；置顶按钮会按当前选中项显示“置顶”或“取消置顶”。回收站二次确认现在明确显示笔记数量及 30 天保留说明。批量导出入口仍待导出任务接入，因此本步骤保持未完成。

- [ ] **步骤 5：运行 UI 测试和预览检查**

运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.HomeScreenTest"
```

在 320dp 窄屏、普通字体、大字体和深色模式下检查关键操作不被截断。
2026-09-27：MuMu Android 12/API 32 首页仪器测试 8/8 通过，全量 JVM 单元测试通过；覆盖首页空状态和加号、卡片文字/时间/首图、点击进入编辑、文件夹新增/重命名/删除迁移、多选全选/取消、批量移动/置顶/取消置顶/回收站数量确认及排序。已手工打开 Debug APK 检查浅色和深色首页，颜色层级正常；MuMu 截图仍为 1600×900 横向画布，强制旋转也未切换为竖屏，因此手机竖屏/窄屏、大字体下的设备视觉、纵横屏组合及 Xiaomi 11 Pro 手工验收仍待完成。本轮没有操作小米真机，MuMu 的旋转和夜间模式设置已恢复。

### 任务 5：实现编辑器、自动保存和草稿恢复

**文件：**

- 创建：`feature/editor/EditorScreen.kt`、`EditorViewModel.kt`、`EditorUiState.kt`
- 待创建：`EditorToolbar.kt`（完整格式工具尚未接入）
- 已创建：`feature/editor/EditorDraftStore.kt`，实现普通笔记版本化原子草稿存储
- 创建：`androidTest/java/com/anan/anannotes/ui/EditorScreenTest.kt`

- [x] **步骤 1：编写编辑器规则测试**

测试第一行自动成为标题但仍保留在正文；空第一行使用“无标题笔记”；手动标题不会被正文变化覆盖；空内容不落库；编辑状态变化可以恢复草稿；恢复草稿后可以选择恢复或丢弃。
已有首行标题、手动标题覆盖、空标题占位规则单测；另有草稿格式往返、旧修订不得清理新草稿、重复写入替换和未知版本保留测试，以及 MUMU 恢复/丢弃交互测试。

- [x] **步骤 2：实现编辑器状态和自动保存**

编辑器维护标题、正文块、当前皮肤、字号、行间距、选中文件夹和保存状态。每次编辑递增本地修订号，原子写入独立草稿快照；正式保存通过仓库事务递增笔记内容版本，只清理已提交修订号的草稿。退出页面前等待当前保存任务完成或保持未保存状态；重启时让用户选择恢复或丢弃较新的草稿。
已实现：每次正文、标题或皮肤编辑递增本地修订号并异步写入普通草稿；正式保存前再确保快照落盘，Room 事务递增 `contentRevision`，仅清除不超过已提交修订的快照。正常返回等待保存；重开时可恢复或丢弃。版本变化时提示另存为新笔记或保留草稿，避免静默覆盖。字号和行距覆盖值已随笔记读取、草稿恢复和自动保存保留；设置入口与实际排版应用属于任务 6。

- [ ] **步骤 3：实现工具栏和标题逻辑**

键盘上方显示基础工具栏，“更多”展开完整工具栏；首行标题识别不删除正文文本；标题手动修改使用独立字段；加号直接进入当前文件夹的编辑页。
当前基础工具栏结构、“更多”展开、首行保留和手动标题已实现；编辑页已改为无框专注布局，皮肤按钮打开五种内置皮肤的横向选择与实时预览，并按单条笔记自动保存。格式、待办和图片按钮仍禁用，后续分别由混合编辑器/图片任务接入。

- [ ] **步骤 4：运行单元和设备测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*Editor*Test'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.EditorScreenTest"
```

在 MUMU 和小米手机上执行旋转/后台/强制停止后的草稿恢复检查。
2026-09-27：MuMu Android 12/API 32 完整仪器套件 28/28 通过，覆盖编辑器保存/草稿恢复及首页空状态回归；之前遗留的测试数据清理失败已由按笔记 ID 清理及整套复跑确认。强制停止/后台/旋转手工恢复、Xiaomi 11 Pro 验收仍未完成；本轮没有向小米安装或启动应用。

### 任务 6：皮肤、字体、图片块和预览缓存

**文件：**

- 创建：`core/model/DisplaySettings.kt`、`feature/editor/SkinPickerSheet.kt`、`ImageBlock.kt`、`ImageAdjustmentSheet.kt`
- 创建：`data/files/OriginalImageStore.kt`、`PreviewCacheStore.kt`
- 修改：`ui/theme/AnAnTheme.kt`，让皮肤作用于当前编辑器和导出排版
- 测试：`core/DisplaySettingsTest.kt`、`feature/ImageBlockTest.kt`

- [x] **步骤 1：编写图片和显示规则测试**

测试五种内置皮肤 ID；全局默认字体和单条笔记覆盖互不覆盖；图片默认居中、比例保持；宽度百分比边界为 10%–100%；清理预览缓存后原图和 OCR 文字仍存在。
已验证五种内置皮肤、全局默认与单条覆盖隔离、字号/行距边界、图片宽度与比例、插入顺序、缓存清理不触及原图与 OCR 文本，以及原图路径越界拒绝。

- [x] **步骤 2：实现全局设置和单条覆盖**

设置页保存默认字号、行间距、浅色/深色跟随系统和默认皮肤；笔记级设置只写入该笔记覆盖字段；已有覆盖的笔记不被全局设置强行改写。
设置页已持久化全局字号、行距、主题模式和默认皮肤；编辑器可在“全局默认 / 仅本条笔记”间切换，笔记覆盖值独立保存，新增笔记读取持久化默认皮肤。

- [x] **步骤 3：实现图片独立块**

通过 Android Photo Picker 插入独立图片块；原图进入应用私有目录，预览写入可清理缓存，图片按 10%–100% 调宽并保持原始比例、默认居中；图片插入当前光标处并保留前后文字顺序。回收站保留原图，永久删除仅在最后一个块引用消失后清理。未申请广泛媒体权限。

- [x] **步骤 4：运行图片测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ImageBlockTest' --tests '*DisplaySettingsTest'
```

2026-09-27 全量验证通过：26 项 JVM 单元测试、Android 测试源码编译、`:app:lintDebug`、Debug APK 与 instrumentation APK 构建均成功。图片宽度/比例、插入顺序及原图/缓存生命周期规则有单元测试；横图、竖图、超大图、不可读 URI 的设备端导入验收仍待模拟器在线后执行。小米真机未被安装或操作。

### 任务 7：全文搜索和普通待办页

**文件：**

- 创建：`data/local/NoteSearchFts.kt`、`data/repository/SearchRepository.kt`
- 创建：`core/SearchRules.kt`、`data/repository/TodoRepository.kt`
- 创建：`feature/search/SearchScreen.kt`、`SearchViewModel.kt`、`SearchHighlight.kt`
- 创建：`feature/todo/TodoScreen.kt`、`TodoViewModel.kt`
- 测试：`core/SearchRulesTest.kt`、`feature/search/SearchHighlightTest.kt`、`androidTest/java/com/anan/anannotes/data/SearchAndTodoRepositoryTest.kt`、`androidTest/java/com/anan/anannotes/ui/SearchAndTodoTest.kt`

- [x] **步骤 1：编写搜索与待办测试**

测试输入停止约 300ms 后搜索标题、正文、引用、待办和 OCR 文本；命中内容以粗体并使用小面积主题强调色显示；普通索引不包含私密内容；普通待办只显示未完成项，已完成项在开关打开后显示删除线和淡化文字。
已新增搜索规则/300ms debounce/命中范围与粗体强调色 JVM 测试、Room 搜索与待办仓库仪器测试、搜索高亮 UI 与待办跳转 UI 测试。规则测试先因对应能力缺失而红灯，规则和高亮测试现已通过。普通索引专属普通 Room 与 NoteRepository；私密库尚未实现，将在任务 8 做端到端隔离验收。

- [x] **步骤 2：实现普通全文索引**

为标题、正文块、引用、待办文本和成功 OCR 文本建立普通 FTS 索引；笔记更新、删除、恢复和 OCR 成功时同步索引；查询结果通过 noteId 回到原笔记。
Room 数据库由版本 4 升至 5；FTS4 索引与普通笔记保存、移入回收站、恢复、永久删除在同一 Room 事务维护，升级迁移会为既有活动笔记回填索引。FTS 命中结合子串回退以支持中文部分匹配；搜索结果用 noteId 回到活动笔记并关联当前文件夹名。

- [x] **步骤 3：实现搜索栏和高亮**

输入即查询，使用 300ms debounce；结果显示标题、文件夹、命中片段和粗体匹配；无结果页面只显示当前查询相关文案，不插入装饰性副标题。
首页搜索入口现已接到搜索页；输入即搜索、300ms debounce、标题/正文片段的粗体低饱和主题色高亮、文件夹与日期显示、清除查询和无结果状态已实现。

- [x] **步骤 4：实现待办底部页**

显示所有普通笔记未完成待办，按最近修改时间排序；每项显示待办文字、所属笔记标题和文件夹；点击跳转到原笔记；完成后从默认列表消失但保留在原笔记。
待办页默认仅显示未完成项，开关打开后显示全部；完成项使用勾选、删除线和淡化文本；列表按待办更新时间倒序，并提供原笔记/文件夹信息、勾选更新和跳转编辑器入口。

- [x] **步骤 5：运行搜索/待办测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SearchRulesTest'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.SearchAndTodoTest"
```
2026-09-27：MuMu Android 12/API 32 完整仪器测试 28/28 通过（包含 Room 搜索/待办仓库与 UI 用例），全量 JVM 测试此前 59/59 通过。仪器测试发现并修复 `MIGRATION_4_5` 的 FTS4 `notindexed` 标识符引用与 Room schema 不一致；迁移现覆盖旧正文回填到 FTS 并可搜索。搜索高亮子项测试改用未合并 Compose 语义树。未向小米真机安装或运行测试。

### 任务 8：私密模块和加密数据边界

**文件：**

- 创建：`security/PrivateCrypto.kt`、`security/KeyEnvelope.kt`、`security/MemoryWipe.kt`
- 创建：`feature/private/PrivateStore.kt`、`PrivateRepository.kt`、`PrivateUnlockScreen.kt`、`PrivateHomeScreen.kt`、`PrivateTodoScreen.kt`
- 创建：`docs/superpowers/security/2026-09-27-anan-notes-private-crypto.md`
- 测试：`security/PrivateCryptoTest.kt`、`androidTest/java/com/anan/anannotes/ui/PrivacyIsolationTest.kt`

- [x] **步骤 1：先写密码学格式与失败测试**

测试随机数据密钥可以被密码封装和恢复密钥封装分别解开；错误密码、错误恢复密钥、篡改 nonce、篡改密文/参数和错误版本都必须失败；每次加密使用新 nonce；解密失败不得返回部分明文。另验证 AAD 上下文绑定、凭证缓冲区清理及 RFC 9106 Argon2id 测试向量。2026-09-27：上述本地 JVM 测试通过。

- [ ] **步骤 2：提出并审查 V1 密钥格式**

草案见 `docs/superpowers/security/2026-09-27-anan-notes-private-crypto.md`：32 字节随机数据密钥；密码和恢复密钥各有独立 16 字节 salt；Argon2id v1.3 默认候选 memory 64 MiB、iterations 3、parallelism 4、输出 32 字节；AES-GCM 使用 12 字节 nonce 和 128 位认证标签。每个封装保存版本、参数、salt、nonce 和 wrapped key；内容 AAD 绑定 record ID、block type 和格式版本。2026-09-27 MuMu Android 12/API 32 单次派生 446 ms，进程 PSS 基线 140,774 KiB、峰值 209,090 KiB（增量约 66.7 MiB）；这是一次模拟器进程级测量，参数仍待 Xiaomi 11 Pro、低内存设备、独立安全评审与迁移设计定稿。

私密加密基础和文件仓库已实现；快照/附件加密存于独立 `filesDir/private/`，密码和可选恢复密钥分别包装同一 DEK。`PrivateRepository` 已提供解锁会话、私密搜索、笔记修订冲突、待办、草稿、回收站与私密图片密文读写基础。只读代码审查发现 KDF 参数资源耗尽与图片引用/删除竞态；现已将 V1 KDF 限定为唯一已实现配置，并把引用校验、快照提交、附件删除纳入同一存储锁。本轮继续实现了本地认证节流、会话代次与认证串行化、首页私密入口、石墨冷灰解锁/设置页、恢复密钥确认流程、私密笔记/待办和基础文本编辑；增加了旧会话撤销与待办/编辑规则测试。之后续审发现按列表位置匹配待办可能把完成状态串给另一项；现已改为用稳定 block ID 保持待办身份，并加入前插、重排和仓库保存集成测试。恢复密钥输入默认遮挡，可显式切换显示，且关闭自动纠错。复审发现私密页退出会无条件清除窗口防截图标志；现已按进入前状态恢复该标志，并以 JVM 回归测试验证恢复时保留其他窗口标志。2026-09-27 MuMu Android 12/API 32 完整仪器测试 28/28 通过，迁移正文索引补测及 Argon2id 探针纳入套件；此前 JVM 单元测试 59/59 通过。Lint 有 6 条 Warning、无 Error；Debug APK 为 39,326,301 字节（约 37.50 MiB）。恢复密钥 UI 显示/隐藏与后台锁定流程、Xiaomi/低内存 KDF 测量、独立密码学审查仍待完成；任务 8 尚未完成。

- [ ] **步骤 3：实现私密数据仓库**

私密笔记、附件、OCR 文本、搜索索引、待办、草稿、回收站和导出记录写入私密存储边界；普通仓库只保留普通数据。私密草稿使用独立持久化和修订号恢复，不能落到普通草稿目录；私密回收站只能解锁后访问，按 30 天规则清理。离开私密页或应用进入后台时清空会话数据密钥引用；认证失败使用不区分“无数据/密码错误”的统一提示，并对连续失败次数做本地节流。

- [ ] **步骤 4：实现私密页面流程**

首页使用独立入口进入私密模块；解锁后显示私密笔记和私密待办；锁定后不显示笔记标题、数量、最近编辑时间、导出记录或搜索结果。私密页使用文字主导的石墨/冷灰风格，不使用大锁、钥匙插画、黄色或红色警示。

2026-09-27 进度：已接入首页独立入口、首次设置/密码与恢复密钥解锁、私密笔记和待办页及基础文本/待办编辑；私密页面启用 `FLAG_SECURE`，在 `ON_STOP`、离开路由及组件释放时撤销会话并清空页面状态。已加入异步认证会话代次校验与串行化，避免应用切后台后旧认证结果重新打开私密会话。剩余设备级页面与生命周期验收见步骤 5。

- [x] **步骤 5：运行安全测试与隔离测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*PrivateCryptoTest'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.PrivacyIsolationTest"
```

验收：未解锁时普通搜索、普通待办、普通草稿、普通导出记录和最近任务预览都无法发现私密内容；私密草稿可在重新解锁后恢复，私密回收站可恢复且不泄露；密码和恢复密钥均能独立解锁同一数据密钥。

2026-09-27：MuMu Android 12/API 32 上 `:app:testDebugUnitTest`（包含 `PrivateCryptoTest`）和完整 `:app:connectedDebugAndroidTest` 通过；最新仪器套件 28/28，其中 `PrivacyIsolationTest` 验证私密正文留在私密仓库且未进入普通搜索/文件明文，KDF 探针测得 446 ms、PSS 增量约 66.7 MiB。恢复密钥遮挡交互、后台锁定/生命周期专项用例、Xiaomi/低内存设备性能仍未验收。

### 任务 9：导入、QQ/微信分享和离线 OCR

**文件：**

- 修改：`env-check/app/src/main/AndroidManifest.xml`，增加 `ACTION_SEND`、`ACTION_SEND_MULTIPLE` 的 `text/plain`、`text/markdown`、`image/*` 和文件类型接收配置，不增加网络权限。
- 创建：`feature/imports/ShareImportHandler.kt`、`FileImportService.kt`、`ImportChoiceSheet.kt`
- 创建：`feature/ocr/OcrEngine.kt`、`OcrModelLoader.kt`、`OcrWorker.kt`、`OcrUiState.kt`
- 创建：`docs/superpowers/decisions/2026-09-27-anan-notes-ocr-selection.md`
- 测试：`feature/OcrStateTest.kt`、`androidTest/java/com/anan/anannotes/ui/ImportFlowTest.kt`

- [ ] **步骤 1：先写 OCR 状态机测试**

覆盖 `Idle`、`LoadingModel`、`Recognizing(progressPercent)`、`Succeeded(text)`、`Cancelled`、`Failed(code)` 状态；测试取消保留原图、失败可重试、成功生成独立可编辑 OCR 块；短文字默认展开，长文字默认折叠。

- [ ] **步骤 2：完成 OCR 技术选型验证**

建立 `OcrEngine` 接口和两个离线候选适配层，使用同一组中英混合、数字、换行、低清图片测试集比较识别效果、冷启动时间、内存峰值、APK 增量和许可证。只有满足中英混合可用、无联网依赖、可随安装包分发、总包体积小于 300 MB 的候选才可写入 `OcrModelLoader`；将实测结果和许可证摘要写入决策文档。

- [ ] **步骤 3：实现分享和文件导入**

接收 QQ、微信或其他应用通过系统分享的文字、图片和文件；TXT/Markdown/图片通过 SAF 导入；导入后创建新笔记并默认进入当前选中文件夹。图片导入弹出“仅插入图片 / 插入图片并识别文字 / 只识别文字”。

- [ ] **步骤 4：实现异步 OCR**

原图先进入编辑器并立即落库；后台任务加载内置模型，更新横向进度条和百分比；成功后生成可编辑 OCR 块并更新搜索索引；取消或失败时不生成未完成文本，原图仍可导出和删除。

- [ ] **步骤 5：运行 OCR 和分享测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OcrStateTest'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.ImportFlowTest"
```

分别从文件管理器、QQ 和微信分享文字、图片和 TXT，验证应用不需要登录、不读取聊天列表、不访问网络。

#### 导入阶段 9A 记录（2026-09-28）

- 已实现 `ACTION_SEND`／`ACTION_SEND_MULTIPLE` 接收文字和图片；主界面通过 SAF `OpenDocument` 提供 TXT、Markdown、图片单文件导入入口。分享文字或文本文件创建新笔记；图片保存为独立块，复制原图并生成预览缓存；目标文件夹取当前选中项，“全部”视图回落到“未分类”。
- 新增 UTF-8 TXT/Markdown 校验与读取：保留原始换行、移除 UTF-8 BOM、拒绝空文件/不支持格式/超过 5 MB 的文本；不增加网络或存储权限。分享过滤器不接收 PDF 等不支持格式。
- MuMu Android 12／API 32 上新增分享路由、分享文本（包括仅含 ClipData 的来源）、Markdown URI、图片原图/宽高比、文件夹入口等测试；完整 `connectedDebugAndroidTest` 为 64/64 通过，JVM 测试为 104/104 通过，Lint 无 Error，Debug 构建成功；构建契约确认无 `INTERNET` 权限。合并 Manifest 仅包含 AndroidX 自动生成的签名级 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，没有媒体或广泛存储权限。
- 本阶段有意不显示尚不可用的 OCR 模式选项；OCR 状态机、模型许可证/效果/体积验证、图片“仅插图/插图并识别/仅识别”选项和 QQ／微信真机手工分享验收仍未完成，因此任务 9 步骤 1、2、3、4、5 继续保持未完成。
- 导入恢复加固记录（2026-09-28）：系统分享／文件导入的随机 `importRequestId` 现在随编辑状态和草稿格式 v4 持久化；Room 7 的 `notes.importRequestId` 使用唯一索引，保存笔记与块时同事务提交。恢复先查已保存笔记，再查同请求草稿，防止进程重建后重复消费路由载荷；重复请求不会覆盖首份笔记。若 Room 已提交而草稿尚未回写 `noteId`，完整内容相同才自动清理旧草稿，内容不同继续走恢复/冲突选择。保留草稿 v1–v3 兼容性，并新增草稿往返、旧格式读取、仓库幂等、恢复分支、提交后崩溃窗口和 v5→v7 迁移测试。MuMu Android 12/API 32 全量设备测试 70/70 通过，JVM 全量测试 105/105 通过；`lintDebug` 0 Error、7 Warning；`assembleDebug` 成功，Debug APK 39,498,997 字节。代码审查未发现 Critical/Important/Minor 问题。OCR 和 QQ／微信真机验收仍未完成。

### 任务 10：导出、导出记录和数据与存储

**文件：**

- 创建：`feature/export/ExportService.kt`、`ExportLayout.kt`、`AndroidPdfExportRenderer.kt`、`ExportHistoryRepository.kt`、`ExportFeedback.kt`
- 创建：`feature/settings/StorageScreen.kt`、`ExportHistoryScreen.kt`
- 创建：`data/files/ExportFileStore.kt`
- 测试：`feature/ExportLayoutTest.kt`、`androidTest/java/com/anan/anannotes/ui/ExportFlowTest.kt`

- [x] **步骤 1：编写导出契约测试**

测试 A4 PDF 页面规格、分页 PNG 的 600 DPI 布局、1080 × 1080 和 1080 × 1350 的精确像素尺寸、长图拼接、TXT 文本、Markdown ZIP 内的 `md` 文件和 `images` 目录；测试默认文件名的文件夹/标题/日期组合和非法字符替换。

- [ ] **步骤 2：实现普通内容渲染器**

渲染器读取当前笔记皮肤、字体、行间距、图片块和 OCR 文本；PDF 优先绘制矢量文字；A4 600 DPI PNG 为 4961 × 7016，采用带状画布与流式编码，不创建整页 bitmap、不把多页合入一张长图。单张长图内部带状处理，结果为一份 PNG；详见 [2026-09-28 高清导出及存储方针](2026-09-28-anan-notes-export-storage-guideline.md)。

- [ ] **步骤 3：实现文件落盘规则**

PDF/TXT/Markdown 通过 SAF 选择目录；PNG 通过 MediaStore 写入 `Pictures/AnAn Notes/Exports`，使系统相册发现；Markdown 使用 ZIP 输出；首次选择后记住位置，设置页可更换。

- [ ] **步骤 4：实现导出反馈和历史**

成功反馈短暂显示文件名和保存位置；失败显示 `AN-EXP-001` 到 `AN-EXP-004` 中的稳定代码、原因和可执行建议，不使用红色。历史记录最多保留普通 100 条和私密 100 条；清除只删除元数据，不删除已导出的文件；普通页面绝不显示私密导出记录。

- [ ] **步骤 5：实现存储概览和缓存清理**

显示正文、原图、预览缓存和导出文件占用；清理只删除预览缓存；空间不足返回 `AN-EXP-002`，不修改原笔记并提供查看存储入口。

- [ ] **步骤 6：运行导出测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ExportLayoutTest'
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.ExportFlowTest"
```

在设备文件管理器和相册中确认 PDF、PNG、TXT、Markdown ZIP 都可发现和打开。

#### 导出布局核心记录（2026-09-28）

- 新增 `feature/export/ExportLayout.kt`：集中定义 A4 PDF 物理页尺寸、A4 600 DPI PNG（4961 × 7016）、1080 × 1080／1080 × 1350 社交图尺寸、长图分段、默认文件名清理、TXT 纯文本和待办状态序列化、Markdown 格式与原图相对路径，以及 Markdown ZIP 原图打包。
- 新增 14 项 `ExportLayoutTest`，先确认实现缺失时编译失败，再验证尺寸、分段边界、文件名、文本内容、Markdown 格式与 ZIP 原图字节。全量 JVM 测试 119/119 通过；`lintDebug` 0 Error、7 Warning、2 Hint；`assembleDebug` 成功，Debug APK 39,275,097 字节（约 37.46 MiB）；构建契约检查通过。只读代码复核中提出的交叉样式、ZIP 输入流关闭、超大分段数、Unicode 文件名截断和归档内容断言问题均已处理，复核未发现 Critical／Important／Minor 遗留问题。
- 这只是导出核心规则，不是可供用户操作的导出功能：编辑页入口、SAF／MediaStore 文件落盘、PDF／PNG 实际渲染、导出记录与存储页均未接入，文件管理器／相册验收尚未做。因此任务 10 步骤 2–6 仍未完成，当前版本仍不能实际导出文件。
- 当前工作区没有 Git 元数据，无法生成 SHA 差异或提交；改动保留在现有项目目录。

#### 普通导出第一阶段记录（2026-09-28）

- 新增 `feature/export/ExportService.kt`、`data/files/SafExportFileStore.kt`、`feature/export/ExportDirectoryPreferences.kt`：首页批量操作提供 TXT 与 Markdown ZIP；TXT 按 UTF-8 写入，Markdown ZIP 包含 Markdown 文件及原图原始字节。每条笔记单独创建文件；名称含文件夹、标题和日期，目标文件夹内同名时追加序号，不覆盖旧文件。某条笔记失败会产生对应错误事件并继续其他条目；SAF 写入中途失败时尽力删除部分文件。
- `OpenDocumentTree` 选择的目录以可持久化 URI 权限记忆；首页导出格式面板和设置页都能更换位置。普通快照仅经普通 `NoteRepository` 读取；笔记、块与待办通过同一 Room 事务读取，私密仓库未接入普通导出入口。
- `ExportServiceTest` 验证实际序列化字节、Markdown ZIP 图片字节、文件名、重复 ID 去重及失败后继续；MuMu `ExportFlowTest` 验证多选导出格式入口和设置页更换目录控件。全量 JVM 123/123、MuMu Android 12/API 32 connected 81/81、Lint 0 Error／9 Warning／2 Hint、Debug APK 39,618,985 字节；构建契约检查通过。

#### A4 PDF 渲染增量（2026-09-28）

- 新增 `AndroidPdfExportRenderer` 并接入普通导出服务和首页格式面板，现可导出 A4 PDF、UTF-8 TXT、Markdown ZIP；PDF 用 `PdfDocument` 逐页生成，正文文字为矢量绘制，应用全局显示默认值与单条笔记覆盖值均参与排版，当前皮肤用于页面底色、文字和强调元素，原图以有界采样解码。导出终态 Snackbar 由带 ID 的待确认队列驱动，重建后可重放，多条结果不会互相覆盖。
- `AndroidPdfExportRendererTest` 在 MuMu 上通过系统 `PdfRenderer` 验证 A4 页面尺寸、深色皮肤底色、正文绘制、多页正文/标题分页及原图嵌入。新增服务路由/MIME/文件名测试、图片采样尺寸单测及导出提示队列测试。代码审查发现的长标题裁切已用失败测试复现并修复；完整 JVM 145/145、MuMu 85/85；Lint 0 Error、7 Warning、2 Hint；Debug APK 40,074,765 字节；构建契约检查通过。尚缺 Compose 层 Snackbar 重建/顺序端到端测试和大图采样设备上限断言。
- 当前尚未提供任何 PNG 实际渲染或 MediaStore 写入，不能将 600 DPI、长图或朋友圈 PNG 视为可用；SAF 真实目录授权、第三方文件提供器实际写入、文件管理器打开及 Xiaomi 真机验收仍待做。导出记录和存储概览 UI/数据也未实现。故任务 10 步骤 2–6 仍保持未完成；本次仅完成 PDF 子项与其回归，不提前勾选整个渲染步骤。
- 导入侧选择器已扩展 MIME 类型并保持内容校验；内置 OCR、三种图片导入模式和 QQ／微信实体设备分享尚未实现/验收，任务 9 步骤 4–5 仍未完成。
- 本阶段只开放 TXT 与 Markdown ZIP。PDF 与 PNG 渲染、MediaStore、导出历史/存储页、真实 SAF 文件夹选取和文件管理器打开验收仍待后续实现/验证；因此步骤 2–6 未勾选，不能称为完整导出功能。导入现有系统分享/SAF 基础流程，OCR 与 QQ／微信真机分享验证仍待完成。
- 当前工作区没有 Git 元数据，无法生成 SHA 差异或提交；改动保留在现有项目目录。

#### 普通导出稳定性加固记录（2026-09-28）

- 修正 SAF 重名时完成提示仍显示旧文件名；文件存储现在返回实际显示名和 URI。
- 为导出加单批次互斥和忙碌状态；成功后仅在选择集未变化时清理该批次选择。目录选择器返回所需的导出格式和更换目录状态现在可在 Activity 配置重建后恢复。
- 把 SAF 文件创建/写入/失败清理与 Android `DocumentsContract` 访问拆开测试；验证重名不覆盖、部分文档只清理本次创建项、失败批次保留之前成功文件。源图片读取和 Markdown 内容校验错误不再映射为“目录不可用”。
- 补充 Markdown 字面标记转义与 UTF-8 文件名长度约束。
- 完成/部分成功提示通过 `ExportFeedback` 显示目标文件夹、成功文件名和失败代码/原因；终态事件含完整批次条目，并通过 ViewModel `Channel` 缓冲，避免 Activity 配置重建短暂无订阅时漏掉完成通知。SAF 删除失败会报告残留文件名并提示手动删除；Markdown 导入 MIME 过滤器现包含标准及泛型提供器类型。
- 回归：JVM 138/138，MuMu Android 12／API 32 81/81，`lintDebug` 0 errors／7 warnings／2 hints，Debug APK 40,069,521 字节，`verify-build-contract.ps1` 通过。
- 自动化使用可控文档存储端口验证 SAF 文件存储逻辑，但尚未通过系统文件夹选择器对真实 DocumentsProvider 落盘；文档提供器兼容性、文件管理器打开和 Xiaomi 真机仍是待验收项。Activity 重建恢复逻辑还没有在系统选择器端到端手工验收。PDF／PNG、MediaStore、导出历史、存储概览尚未实现；导入 OCR 与 QQ／微信真机测试仍待完成。本记录不勾选任务 10 的步骤 2–6。

#### 朋友圈 PNG 导出增量（2026-09-28）

- 首页批量导出格式面板新增“PNG（朋友圈方图）1080 × 1080”和“PNG（朋友圈竖图）1080 × 1350”。格式面板采用受屏幕高度约束的可滚动列表，低屏幕高度时 TXT 与 Markdown ZIP 等原有格式仍可滚动访问。
- 新增社交 PNG 渲染器：遵循当前笔记皮肤、字体与行距覆盖，绘制标题、正文、引用、待办和图片块；图片按原始宽高比与百分比宽度居中/对齐，不拉伸。长正文按顺序拆为多张相同尺寸 PNG，并显示页序；图像逐页编码和回收，避免整篇内容驻留为大型位图。
- 新增 MediaStore PNG 文件存储：写入 `Pictures/AnAn Notes/Exports/`，先以待发布状态写完再对系统相册公开；失败时清理不完整条目，清理失败则向反馈报告残留文件名。使用 Android 10+ 的 MediaStore 相对路径，不新增广泛存储权限。方图和竖图都经普通笔记导出服务路由，私密笔记未接入。
- 测试覆盖服务路由/逐页结果、文件名和目标目录、MediaStore 待发布/发布/失败清理；MuMu 检查相册索引、PNG 可解码与精确尺寸，并覆盖首页真实操作的方图 1080 × 1080、竖图 1080 × 1350 端到端流程。最终 JVM 150/150、MuMu Android 12/API 32 connected 91/91；`lintDebug` 0 Error、9 Warning、2 Hint；Debug APK 40,089,345 字节（约 38.23 MiB）；`verify-build-contract.ps1` 的 applicationId、minSdk、无 INTERNET 权限和应用名均通过。
- 本增量只交付朋友圈方图/竖图；A4 600 DPI PNG、单张长图 PNG、导出历史和存储概览仍未实现。真实 Xiaomi 11 Pro、系统相册/文件管理器手工检查及第三方文件提供器验收仍待做；本次没有安装到小米真机。任务 10 步骤 2–6 仍未整体完成，不能据此宣称全部导出功能已交付。
- 当前工作区没有 Git 元数据，无法生成 SHA 差异或提交；改动保留在现有项目目录。

#### 高清 PNG、普通记录与存储概览增量（2026-09-28；收尾 2026-09-29）

- [x] A4 600 DPI 分页 PNG：4961×7016 px、真实 pHYs、128 行画带、原图等比采样、正文与格式按序分页。
- [x] 单张长图 PNG：1080 px 宽、完整测量后单文件输出；高度上限 32768 px、单文件 128 MiB，超限明确拒绝，不截断或静默拆图。
- [x] 普通导出记录：实际成功回执/失败代码、最近 100 条事务裁剪、持久化、打开/分享反馈、清空只删元数据；记录写入失败不误报文件导出失败。
- [x] 普通存储概览：指定普通数据库/原图/预览目录、成功 URI 去重、已知/未知/不可访问区分、清理仅限预览缓存；不把外部副本加入内部合计，不访问私密目录。
- [x] 导出格式面板、设置、导航和不足空间反馈接线；记录/存储页沿用简约返回箭头与单层安全区，未代替全局适配。
- [x] PNG 定向修复与复审、历史/存储接线审查；全量结果与 APK 校验值写入 [验证记录](../verification/2026-09-28-export-storage-verification.md)。9 月 28 日 Android 125/125，9 月 29 日 JVM 170/170；Lint 0 Error、13 Warning、2 Hint；Debug APK 39.31 MiB；权限契约通过。
- [ ] 普通编辑页单条快速导出：等待自动保存当前修订后取快照，再复用格式面板和普通记录。
- [ ] 私密验证导出与独立加密历史；真实第三方提供器/接收应用及小米相册/文件管理器验收。
- [ ] E1–E6 全面屏/异形屏/输入法与大字体设备矩阵、低内存峰值/发布包验收。

以上小项是本次已批准增量的跟踪，不将任务 10 步骤 2–6 整体勾选。下一项按照 [全面屏与异形屏方针第 9 节](2026-09-28-anan-notes-edge-to-edge-guideline.md#9-下一次开发的执行包先完成-e1) 先做 E1，保留既有数据与权限边界。

### 任务 11：加密备份、恢复冲突和回收站

**文件：**

- 创建：`feature/backup/BackupService.kt`、`BackupFormat.kt`、`RestorePreview.kt`、`ConflictResolver.kt`
- 创建：`feature/recycle/RecycleBinScreen.kt`、`RecycleBinViewModel.kt`
- 创建：`feature/settings/PrivacySettingsScreen.kt`
- 测试：`feature/BackupMergeTest.kt`、`feature/RecycleBinTest.kt`

- [ ] **步骤 1：编写备份和回收站测试**

测试备份密码错误不产生部分恢复；默认合并不删除当前数据；相同稳定 ID 去重；双修改显示冲突并允许保留当前、使用备份或保留两个版本；普通和私密回收站 30 天规则分别成立。

- [ ] **步骤 2：实现加密备份格式**

备份使用独立备份密码派生密钥，文件头保存版本、参数和校验信息；大型附件分块加密；私密内容以加密形式进入备份，恢复到私密区前需要相应凭证，不得解密后写入普通区。

- [ ] **步骤 3：实现合并恢复**

恢复前解析摘要并显示新增笔记、图片、文件夹和待办数量；以稳定唯一 ID 去重；冲突逐条交给用户选择；应用恢复采用临时目录和事务，校验完整后再替换/合并。

- [ ] **步骤 4：实现回收站**

普通笔记删除进入回收站 30 天；支持恢复、立即永久删除和到期清理；恢复保留文件夹、皮肤、图片、OCR 和待办；私密回收站只在解锁后显示。

- [ ] **步骤 5：运行测试**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*BackupMergeTest' --tests '*RecycleBinTest'
```

### 任务 12：完善设置、首次启动隐私说明和双语文案

**文件：**

- 修改：`feature/settings/SettingsScreen.kt`
- 创建：`feature/onboarding/PrivacyIntroScreen.kt`
- 修改：`res/values/strings.xml`、`res/values-en/strings.xml`
- 创建：`androidTest/java/com/anan/anannotes/ui/SettingsAndPrivacyIntroTest.kt`

- [ ] **步骤 1：编写设置页测试**

测试设置首页只显示“外观”“导入与导出”“隐私与安全”“数据与存储”四个功能区；私密设置只显示“私密笔记”和“备份与恢复”两个主要区块；不出现应用锁、生物识别承诺、宣传副标题或未确认的统计页。

- [ ] **步骤 2：实现设置页和首次说明**

首次启动显示一页式隐私说明：默认本地保存、不强制登录、不上传、权限由用户主动触发；用户确认后进入首页。设置页接入字体、行间距、默认皮肤、导入导出、导出记录、备份恢复、私密模块和存储概览。

- [ ] **步骤 3：补齐中英文资源**

将所有用户可见文本放入资源文件，包含错误代码说明、导出警告“导出文件将不再受安安简记私密模块保护”、OCR 状态、导入选择、回收站和无内容状态；检查中英文切换不会改变私密隔离逻辑。

- [ ] **步骤 4：运行双语与无障碍检查**

运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.SettingsAndPrivacyIntroTest"
```

在系统大字体、深色模式和 TalkBack 可访问性树中检查按钮有明确文本，重要操作不依赖颜色单独表达。

### 任务 13：整体性能、权限、包体积和真机验收

**文件：**

- 修改：`env-check/app/build.gradle.kts`、`env-check/app/proguard-rules.pro`、`env-check/app/src/main/AndroidManifest.xml`
- 创建：`docs/superpowers/verification/2026-09-27-anan-notes-v1-device-verification.md`
- 创建：`env-check/scripts/verify-v1.ps1`

- [ ] **步骤 1：编写发布验证脚本**

脚本执行 debug/release 构建、单元测试、连接设备测试、APK 体积统计、Manifest 权限检查和关键资源存在性检查；任何失败退出码都非 0。

- [ ] **步骤 2：运行完整自动化验证**

运行：

```powershell
Set-Location D:\anan\env-check
.\scripts\verify-v1.ps1
```

预期：所有测试通过；合并 Manifest 不含 `INTERNET`、通讯录、电话、短信、定位或广泛媒体权限；APK 小于 300 MB。

- [ ] **步骤 3：在 MUMU 和小米手机执行手工验收矩阵**

记录以下设备组合：Android 10/较小屏幕、Android 13+大字体、深色模式、无网络、低剩余空间、横图/竖图、大附件、QQ 分享、微信分享、后台恢复、强制停止后草稿恢复、错误密码、恢复密钥恢复、A4 600 DPI 多页导出。

- [ ] **步骤 4：完成性能与内存检查**

记录首页卡片滚动、全文搜索 debounce、OCR 首次加载、图片插入、A4 分页 PNG 和备份恢复的耗时与峰值内存；多页 A4 导出过程中不出现一次性整本 bitmap，失败后临时文件被清理。

- [ ] **步骤 5：生成 V1 验收记录**

在 `docs/superpowers/verification/2026-09-27-anan-notes-v1-device-verification.md` 记录每项命令、设备、结果、APK 体积、权限清单和未通过项。只有所有 V1 验收标准通过，才进入发布构建和代码审查。

## 实现顺序与检查点

1. 先完成任务 1–3，得到可安装、可保存本地普通笔记的最小闭环。
2. 完成任务 4–7，得到首页、编辑、图片、搜索和待办的日常使用闭环。
3. 完成任务 8，先独立审查私密数据边界，再让私密页面接入普通导航。
4. 完成任务 9–11，加入 OCR、导入、导出、备份和回收站，并保持每项功能离线可用。
5. 完成任务 12–13，进行双语、权限、包体积、性能和两类设备验收。

每个检查点都必须保留测试输出；任何安全、权限、包体积或数据丢失问题未解决时，不进入下一检查点。

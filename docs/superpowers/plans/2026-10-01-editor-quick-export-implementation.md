# 普通编辑页单条快速导出实现计划

> **面向 AI 代理的工作者：** 在当前会话中使用 `executing-plans` 内联执行，按任务逐项验证。每个行为先写失败测试，再实现最小改动。

**目标：** 用户可从普通笔记编辑页直接导出当前这一条笔记；导出内容必须包含点击导出时的最新已完成输入，且空白笔记不得被保存或导出。

**架构：** 提取普通导出格式底部面板供首页与编辑页复用；普通导出服务由共享工厂构建。编辑页在所选格式执行前提交最新修订，遇到输入法组合、保存失败、冲突或空笔记时中止，不读取旧快照；导出过程暂时冻结编辑，继续复用现有 SAF、MediaStore、成功/失败记录与反馈。

**技术栈：** Kotlin、Jetpack Compose、ViewModel、Room、Coroutines/Flow、Android Photo/Document APIs。

**规格：** [高清导出及存储实施方针](2026-09-28-anan-notes-export-storage-guideline.md) 第 7 节 P1“普通编辑页单条快速导出”；接口和数据边界遵守 [V1 总体技术设计](../specs/2026-09-27-anan-notes-v1-technical-design.md) 与 [富文本编辑设计](../specs/2026-09-27-anan-notes-rich-text-design.md)。

## 全局约束

- 最低 Android 版本保持 API 29；不新增网络权限、账号或广泛媒体权限。
- 普通编辑页只能调用普通仓库、普通导出服务和普通导出历史；不得触及私密仓库、私密密钥或私密记录。
- PNG 使用现有 MediaStore 目的位置；PDF、TXT、Markdown ZIP 使用现有 SAF 目的位置与已记住的文件夹。
- 保持所有 7 种现有格式、文件命名、皮肤/字体/图片/待办排版、错误码及部分成功语义不变。
- 新建空白笔记仍遵守“无有效内容就不保存”；测试只清理本测试创建的笔记、记录和文件。
- 工作目录没有 Git 元数据；本计划不初始化仓库、不创建虚构提交。

## 文件与职责

- 创建 `app/src/main/java/com/anan/anannotes/feature/export/ExportFormatSheet.kt`：共享格式面板，只负责显示选项并将所选格式/更换位置操作回调给宿主。
- 创建 `app/src/main/java/com/anan/anannotes/feature/export/OrdinaryExportServiceFactory.kt`：从 Application 构建现有普通导出依赖，避免首页与编辑页各自复制组装代码。
- 修改 `feature/home/HomeScreen.kt`、`feature/home/HomeViewModel.kt`：接入共享面板和服务工厂，保持首页多选行为与既有 testTag。
- 修改 `feature/editor/EditorUiState.kt`、`feature/editor/EditorViewModel.kt`：表达导出准备/执行状态，提交最新修订后仅导出当前 noteId，并转发现有反馈事件。
- 修改 `feature/editor/EditorScreen.kt`：顶栏添加简洁可见的“导出”操作；接入格式面板、SAF 文件夹选择、MediaStore PNG、反馈显示和导出期间的编辑冻结。
- 修改 `app/src/androidTest/java/com/anan/anannotes/ui/EditorScreenTest.kt`；必要时创建 `EditorExportFlowTest.kt`：验证入口、最新内容、空白笔记保护及导出记录。
- 修改或创建 `app/src/test/java/com/anan/anannotes/feature/editor/EditorExportRulesTest.kt`：验证输入法组合/冲突/恢复/失败/空草稿的导出门控规则。

---

### 任务 1：先锁定编辑页导出入口与门控行为

**文件：**
- 修改：`app/src/androidTest/java/com/anan/anannotes/ui/EditorScreenTest.kt`
- 创建：`app/src/test/java/com/anan/anannotes/feature/editor/EditorExportRulesTest.kt`
- 创建：`app/src/main/java/com/anan/anannotes/feature/editor/EditorExportRules.kt`

- [x] **步骤 1：增加入口失败测试**

在 `quickCreateOpensTitleBodyAndBasicToolbar` 的断言后增加：

```kotlin
composeRule.onNodeWithTag("editor-export-button").assertIsDisplayed()
```

- [x] **步骤 2：运行测试确认因入口缺失而失败**

运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.EditorScreenTest#quickCreateOpensTitleBodyAndBasicToolbar" --console=plain
```

预期：`quickCreateOpensTitleBodyAndBasicToolbar` 因找不到 `editor-export-button` 失败；测试启动、数据库初始化必须正常。

- [x] **步骤 3：为可导出状态编写纯规则测试**

定义 `EditorExportBlockReason`（`NOT_LOADED`、`EMPTY`、`COMPOSING`、`RECOVERY`、`SAVE_FAILED`、`CONFLICT`）和 `EditorExportDecision`：`PersistFirst`、`Ready(noteId)`、`Blocked(reason)`。规则矩阵：正文为空且 `noteId == null` 时拒绝；有意义内容但还没保存或状态为 `DIRTY`/`SAVING` 时返回 `PersistFirst`；存在已保存 noteId 且 `CLEAN`/`SAVED` 时返回 `Ready(noteId)`；`composing != null`、`FAILED`、`CONFLICT`、`pendingRecovery != null` 或未加载时返回明确 `Blocked` 原因。规则只接收编辑状态，不依赖 Android 或 Compose。

- [x] **步骤 4：运行规则测试确认缺少实现**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*EditorExportRulesTest' --console=plain
```

预期：因 `EditorExportRules` 尚不存在而编译失败。

### 任务 2：抽出共享格式面板并保持首页导出

**文件：**
- 创建：`app/src/main/java/com/anan/anannotes/feature/export/ExportFormatSheet.kt`
- 修改：`app/src/main/java/com/anan/anannotes/feature/home/HomeScreen.kt`
- 修改：`app/src/androidTest/java/com/anan/anannotes/ui/ExportFlowTest.kt`

- [x] **步骤 1：给首页共享面板行为补充断言**

保留现有全部格式、横屏末项可滚动到达及 SAF 目录操作测试；将新共享组件需要的标题、面板 testTag 和回调行为纳入测试断言。

- [x] **步骤 2：运行 `ExportFlowTest` 观察回归基线**

运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.ExportFlowTest" --console=plain
```

预期：当前首页导出路径全部通过，保存此结果作为提取前对照。

- [x] **步骤 3：提取面板并让首页使用共享组件**

保留既有格式标签与顺序：PDF、A4 600 DPI PNG、长图 PNG、朋友圈方图、朋友圈竖图、TXT、Markdown ZIP；组件只触发宿主回调，不持有 ViewModel、URI 写入器或数据库。

- [x] **步骤 4：重跑 `ExportFlowTest`**

预期：所有原首页导出入口、目的位置、横屏滚动与媒体库回执用例通过。

### 任务 3：实现编辑修订屏障与单条导出用例

**文件：**
- 创建：`app/src/main/java/com/anan/anannotes/feature/export/OrdinaryExportServiceFactory.kt`
- 修改：`app/src/main/java/com/anan/anannotes/feature/home/HomeViewModel.kt`
- 修改：`app/src/main/java/com/anan/anannotes/feature/editor/EditorUiState.kt`
- 修改：`app/src/main/java/com/anan/anannotes/feature/editor/EditorViewModel.kt`
- 修改：`app/src/main/java/com/anan/anannotes/feature/editor/EditorExportRules.kt`
- 测试：`app/src/test/java/com/anan/anannotes/feature/editor/EditorExportRulesTest.kt`

- [x] **步骤 1：先写并运行修订屏障规则测试**

单元测试验证 `EditorExportRules.evaluate` 对未加载、空白、未保存内容、`DIRTY`、`SAVING`、`CLEAN`、`SAVED`、组合输入、恢复冲突、保存失败和版本冲突逐一返回预期 decision。通过真实 Room/Compose 仪器流程再验证提交完成前不产生导出历史、提交后仅导出单条 noteId。

- [x] **步骤 2：运行定向 JVM 测试确认缺少行为**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*EditorExportRulesTest' --console=plain
```

预期：新增规则测试因 `EditorExportRules.evaluate` 尚不存在而失败，不得把失败归因于环境或测试装配。

- [x] **步骤 3：构建共享普通导出服务并实现提交屏障**

共用工厂复用现有 `RoomExportSnapshotProvider`、SAF/MediaStore、PDF/PNG renderer 与 `ExportHistoryRepository`。导出前通过同一 `saveMutex` 与防抖自动保存串行化，再读取并提交当前完整修订；不取消可能已经进入写入阶段的自动保存任务，避免中断保存。若提交期间修订变化则再次检查，直到得到对应当前状态的已保存 `noteId`。组合输入、恢复冲突、保存失败或无 noteId 时返回可见的非破坏性错误，不启动导出。导出期间以不可取消的模态进度层阻止编辑和返回；完成/失败后解除冻结。

- [x] **步骤 4：运行规则与相关保存测试确认通过**

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*EditorExportRulesTest' --tests '*EditorPersistenceRulesTest' --console=plain
```

预期：所有状态门控及既有自动保存/空内容规则测试通过。

### 任务 4：接入编辑页格式选择、目的位置与反馈

**文件：**
- 修改：`app/src/main/java/com/anan/anannotes/feature/editor/EditorScreen.kt`
- 修改：`app/src/androidTest/java/com/anan/anannotes/ui/EditorScreenTest.kt`
- 创建或修改：`app/src/androidTest/java/com/anan/anannotes/ui/EditorExportFlowTest.kt`

- [x] **步骤 1：添加两条端到端失败测试**

其一：新建笔记后快速输入带唯一标记的正文，立即选择社交方图导出，验证 Room 中的最终正文与唯一成功导出记录均对应同一笔记。其二：空白新笔记选择任一格式后，验证没有新笔记、文件或导出历史，并显示简体中文提示。

- [x] **步骤 2：运行编辑器定向仪器测试确认失败**

运行：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.EditorExportFlowTest" --console=plain
```

预期：新用例因编辑页没有导出入口/处理流程失败；测试创建的数据由其唯一 ID 清理。

- [x] **步骤 3：接入顶栏操作、文件夹选择器和反馈**

顶栏显示紧凑“导出”操作及可访问语义；PDF/TXT/Markdown ZIP 使用已记住目录，未设置时打开 `OpenDocumentTree`，返回后记住 URI 并只导出当前笔记；所有 PNG 直接使用 MediaStore。复用共享格式面板、现有导出反馈和导出历史。屏幕重建期间保留待处理格式；用户取消选择器不导出。

- [x] **步骤 4：重跑新旧导出与编辑器仪器测试**

运行完整 connected 测试套件（确保 Gradle 只收到一个 runner class 参数）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --console=plain
```

预期：编辑页单条导出、首页多选导出、自动保存、图片/待办和既有编辑交互全部通过。

### 任务 5：全量回归和文档核对

**文件：**
- 修改：`docs/superpowers/plans/2026-09-28-anan-notes-export-storage-guideline.md`
- 修改：`docs/superpowers/specs/2026-09-27-anan-notes-v1-technical-design.md`
- 更新：`docs/superpowers/verification/2026-10-01-editor-quick-export-verification.md`

- [x] **步骤 1：运行全量构建、JVM、MuMu 仪器和 Lint**

运行：

```powershell
.\gradlew.bat --rerun-tasks :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug --console=plain
```

- [x] **步骤 2：运行构建契约并安装启动 Debug APK**

运行：

```powershell
.\scripts\verify-build-contract.ps1
adb -s 127.0.0.1:16384 install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s 127.0.0.1:16384 shell am start -W -n com.anan.anannotes/.MainActivity
```

- [x] **步骤 3：记录实际测试计数、跳过项、Lint 分类、APK 字节数和设备边界**

验证报告只写本轮命令输出和 XML 的实测结果；真机或其他 API 未连接时明确记为未验收。首页历史、私密数据隔离和离线/无网络边界的结论不得外推到本轮未测试内容。只读代码审查后补充验证了已存空笔记拦截、待办勾选写入与导出快照串行化，以及标题/待办输入法组合状态；最终报告记录了追加回归后的 182 项 JVM、158 项仪器测试与可分发 APK。

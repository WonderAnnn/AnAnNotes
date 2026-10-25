# 安安简记：全面屏与异形屏适配详细方针

日期：2026-09-28；最近执行更新：2026-10-01。状态：E1–E5 已在 MuMu/API 32 完成列明场景；E6 的 MuMu/API 32 下 100%/150%/200% 系统字号及三键导航全量仪器回归均已记录。API 29/35/36、真实小米 11 Pro 与真实 OEM/异形屏矩阵仍未验收。阶段性证据见 [E6 字号回归报告](../verification/2026-09-30-e6-font-matrix-verification.md)，不能据此宣布全设备适配完成。

适用工程：`D:/anan/env-check`。与 [高清导出及存储方针](2026-09-28-anan-notes-export-storage-guideline.md) 衔接；先交付该轮功能，再逐项执行本文。每项均需失败用例、实现、回归证据，不能仅凭一张竖屏截图宣布兼容。

## 1. 目标与不做的事

目标是保持现有轻量、中性、低饱和视觉：背景可以延伸到屏幕边缘，文字、返回箭头、搜索框、工具栏和按钮不会被摄像头、刘海、导航手势或键盘遮住。手机竖屏为主，但横屏、窄分屏和大字体必须能操作。不会通过固定顶端空白、机型名单、禁止横屏或取消现代 SDK 来掩盖问题。

工程当前 `minSdk=29`、`targetSdk=36`、`compileSdk=36`；MainActivity 已调用 `enableEdgeToEdge()`，Manifest 已设 `adjustResize`。Android 16 上针对 API 36 的应用不能通过旧开关退出 edge-to-edge，必须正确处理安全区。[Android 16 行为变更](https://developer.android.com/about/versions/16/behavior-changes-16)

## 2. 已发现的布局风险

| 区域/文件 | 当前做法 | 下一步重点 |
| --- | --- | --- |
| MainActivity / AppNavigation | Activity 开启 edge-to-edge，各页自行处理 Insets | 不在导航根节点再统一叠加所有 padding；建立页面级归属 |
| HomeScreen / TodoScreen | Scaffold + 底部导航 | 核对系统 inset 与底部栏高度是否各只消费一次，横屏左右安全区 |
| EditorScreen | Scaffold 的 contentPadding，EditorToolbar 使用 imePadding | 工具栏贴合键盘；内容 padding 要消费，避免重复 IME 和导航留白 |
| FolderBar | ModalBottomSheet + imePadding/navigationBarsPadding | 底部可下滑取消，等宽按钮；横屏/大字号时仍可滚动输入 |
| SearchScreen / SettingsScreen | statusBarsPadding + navigationBarsPadding | 改为页面级 safeDrawing 策略，补横向切口/自由窗体安全区 |
| CompactPageHeader | 52 dp 固定高度，字号可能放大 | 返回箭头 48 dp 触摸区；大字号不裁切、不顶到状态栏 |
| 私密解锁/编辑页 | 独立深色风格、FLAG_SECURE | 安全区与系统栏图标对比度，退出恢复原窗口外观，保持安全标志 |
| 导出记录/存储概览 | 本轮新增页面按 safeDrawingPadding | 下一阶段并入统一容器，不叠加第二层安全区 |

上表是代码审计得到的风险，不代表每一行都已经复现故障。先通过定向测试定位，再修改对应责任点。

## 3. 安全区归属规则

1. Activity 只负责窗口的 edge-to-edge、`adjustResize` 与系统栏外观，不负责把全应用内容整体缩成一个安全矩形。
2. 页面背景填满窗口。可交互区域依据系统提供的 `safeDrawing` / `displayCutout`；不手算刘海或摄像头位置。横屏时也要保护左右边缘。[Compose 异形屏指引](https://developer.android.com/develop/ui/compose/system/cutouts)
3. 简单二级页在一个外层消费 `safeDrawing`，内部只加正常视觉间距，不再叠加状态栏、导航栏或切口 padding。
4. 使用 Scaffold 的页面明确指定 `contentWindowInsets`。正文使用 `padding(innerPadding).consumeWindowInsets(innerPadding)`，避免子组件再次消费已经加入的间距。Insets padding 修饰符会消费已应用的安全区；不能再用原始像素自行加一遍。[Insets 设置与消费](https://developer.android.com/develop/ui/compose/system/insets-ui)
5. 页顶负责顶部及水平安全区；底部工具栏/导航栏负责底部。`ime` 与 `navigationBars` 用最大覆盖（union）而非相加，键盘可见时不再留一条导航栏高度的无意义空隙。
6. 抽屉遵守 Material3 的窗口 Insets 契约；手动 padding 前先确认父组件已消费的部分。一次修改要同时检查展开、收起、键盘弹出、下滑取消及横屏高度不足。
7. 手势安全区只保护确实可能与系统手势冲突的交互，不把全页面额外加厚。图片长按/百分比输入不要侵占返回手势区域。
8. 全局设置字体和单条字体覆盖不影响系统 inset 计算；文字尺寸改变时调整组件自然高度，不扩大假状态栏。

## 4. 建议的接口与文件组织

新增 `ui/components/PageScaffold.kt` 与 `ui/components/PageInsets.kt`，集中记录策略，避免再复制“状态栏 + 导航栏 + 键盘”的组合到每个页面。

建议接口（实施时以编译和测试结果微调）：

```kotlin
enum class PageInsetPolicy { SECONDARY, MAIN_WITH_BOTTOM_NAV, EDITOR_WITH_IME }

@Composable
fun PageScaffold(
  policy: PageInsetPolicy,
  containerColor: Color,
  topBar: @Composable () -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
)
```

- 不强制所有页面采用相同标题大小或同一滚动结构；容器只管理背景与 inset 归属。
- `SECONDARY`：二级页自然留白；大字体标题允许增高，返回箭头始终可见。
- `MAIN_WITH_BOTTOM_NAV`：背景铺满，固定底部栏保持“笔记/待办”同位置；正文只处理由 Scaffold 返回的 padding。
- `EDITOR_WITH_IME`：编辑工具栏拥有 IME/导航底部安全区，正文依据工具栏占用高度缩放/滚动；光标行必须能滚入可见区域。
- `WindowAppearanceController`（可放 `ui/theme`）：根据当前页面背景亮度决定状态栏/导航栏图标明暗，遵守 route 生命周期；退出深色皮肤/私密页恢复，不能丢失 FLAG_SECURE。
- 不在导出 renderer 修改窗口外观，也不把系统栏绘制进导出图片。

这些是下一步规划接口，不应在总体设计的“已实现接口”表中冒充现有代码。

## 5. 逐步执行清单

### E1：安全区与系统栏基线

- 新增仪器测试/调试测试场景：无切口、顶端切口、左侧切口、底部导航、IME；保存关键组件 bounds 和系统 Insets，断言可点击区域没有交集。
- 审计 MainActivity、NavDisplay、Scaffold、BottomSheet 的每一层 padding 归属。
- 实现公共容器最小策略；第一批只迁移搜索、设置、导出记录、存储概览。
- 验收：返回箭头在安全区下方；顶部不多出两份状态栏；横屏左右安全区不被忽略；滚动后的最后一项可点击。

### E2：首页与待办页

- 迁移首页、待办和批量操作栏，保留卡片缩略图、置顶、排序和搜索功能。
- 双列/单列以实际可用宽度和系统字号判断，不按“小米”或“模拟器”设备名判断。
- 切换页的底部文字和图标 bounds 稳定；切换动画中的页面背景必须不透明，避免旧页残影。
- 验收：横屏切口下不裁卡片；320 dp 窄窗可变单列；200% 字体不挤压底部点击区；手势/三键导航均能触发加号。

### E3：编辑页与输入法

- 迁移 EditorToolbar、图片多选删除栏与正文的 inset 归属，统一消费 innerPadding。
- 对键盘显示/隐藏、候选栏高度变化、输入法切换、待办回车、图片后续正文逐项回归。
- 使用 Compose 原生 inset 动画，不硬编码动画终点或缓存第一次键盘高度。
- 验收：工具栏视觉底边贴合键盘顶边（除已明确设计的 0–8 dp 常规间隔）；没有原截图中的巨大空白；焦点/光标不被工具栏盖住；输入法退出后没有遗留 IME 高度。

执行状态：E3 的 IME/待办子阶段与单条笔记皮肤—状态栏图标联动已在 MuMu Android 12/API 32 完成，详见 [E3 编辑页与输入法验证报告](../verification/2026-09-29-e3-editor-ime-verification.md)。编辑时状态栏图标按笔记背景亮度切换，导航栏图标继续遵循全局主题；退出编辑恢复全局系统栏外观，私密页规则不变。仍未实测候选栏高度变化、切换不同输入法与小米 11 Pro OEM 键盘。

### E4：底部面板

- 新建文件夹、字体、皮肤、图片百分比、排序、导出面板依次迁移。
- 新建文件夹保留底部输入、等宽“取消/创建”、下滑关闭。内容高度不足时滚动，按钮不可跑到键盘后面。
- 保持 12 dp 圆润输入框、轻量滑杆和中性色；不引入突兀黄/红/亮蓝主题。
- 验收：竖屏/横屏/大字号可完成操作；关闭不触发创建；输入内容不因键盘动画丢失；百分比仍可输入。

执行补充（2026-09-30）：图片百分比面板在 MuMu/API 32 的横屏、输入法显示、200% 系统字号组合下验证了滚动到底部并完成保存。修复为 82% 窗口高度上限 + 纵向滚动；其它底部面板的 200% 字号验收仍留在 E6 矩阵，不从该单项结果外推。

### E5：私密页与导航动画

- 深色私密页的系统栏采用可读图标；顶/底安全区规则与普通页一致，但数据仍隔离。
- 返回、应用后台、截图保护、错误口令提示和恢复流程回归，不改变密码学方案。
- 验收：退出恢复主题和图标外观；FLAG_SECURE 的既有边界不被破坏；普通导出记录/存储页不能看到私密元数据。

### E6：设备矩阵与收尾

- 运行以下矩阵并为每格标注“已运行/未运行/不适用”，保留截图、组件 bounds、机型/API/导航方式/字号/方向。
- 全量 JVM、仪器、Lint、权限契约、APK 构建通过后才更新“完成”状态。
- 自动化无法替代真实 OEM 输入法与相册/文件管理器行为，保留小米 11 Pro 手工验收步骤。

## 6. 测试矩阵

| 组别 | 环境 | 必测场景 | 判定 |
| --- | --- | --- | --- |
| 基础最低版本 | Android 10 / API 29 | 手势及三键、搜索/设置、文件夹输入 | 返回/底部控件不遮挡 |
| 当前自动化 | MuMu Android 12 / API 32 | PNG、记录、统计、编辑/IME、横竖屏 | 功能无回退，作为模拟器证据 |
| 强制全面屏 | Android 15 / API 35 | 顶端切口、左侧切口、横屏、导航 | 不依赖退出 edge-to-edge |
| 工程目标 | Android 16 / API 36 | 与上组相同，预测返回、分屏 | 无旧窗口开关依赖 |
| 实体设备 | 小米 11 Pro，实际系统版本记入报告 | 原生/搜狗或实际使用的输入法、长待办、图片后正文 | 一手输入流畅，工具栏贴键盘 |
| 窄窗口 | 320 dp / 360 dp 可用宽度 | 卡片、工具栏、导出格式面板 | 单列/滚动可操作 |
| 大字体 | 100% / 150% / 200% | 标题、按钮、待办、设置、错误提示 | 不截文字、不失点击区 |
| 横屏/分屏 | 左右切口与底部导航组合 | 编辑、皮肤面板、文件夹、长列表 | 左右和上下均适配 |
| 动画状态 | 键盘展开/收起和导航返回的中间帧 | 编辑页、底部抽屉、笔记↔待办 | 不残影、不瞬时双重 padding |
| 深浅外观 | 系统浅/深，笔记浅/深，私密页 | 状态栏/导航栏图标 | 图标可读，退出正确恢复 |

如需新增 SDK 模拟器/镜像，先统计磁盘空间与安装状态；不在本轮擅自下载安装大型镜像。切口模拟、分辨率/字体/导航方式变更都必须记录原值并在测试结束恢复，不能留下影响用户其他应用的设备配置。

当前 Lint 仍有目标 SDK 和依赖升级提示；E4.6 已将导出面板高度上限改为 `LocalWindowInfo.current.containerDpSize.height`，移除 `Configuration.screenHeightDp` 窗口尺寸告警；E4.7 为 440 dp 以下首页增加紧凑布局。窗口容器尺寸只作为比例上限，底部面板仍负责 Insets；紧凑显示测试不等同于分屏验收。SDK/依赖升级另作兼容性检查和验证，不在已验收增量中悄悄升级，也不把 API 36 称作所有时点的最新 Android。

## 7. 手工小米验收操作

1. 打开首页：检查状态栏下方标题、搜索框、第一张图缩略图、底部两页对齐。
2. 新建文件夹并弹键盘：输入、取消、下滑关闭、再创建；两按钮等宽。
3. 新建浅色和深色笔记：输入长待办、回车新增独立项、插图、图下正文，切换输入法/候选栏、收键盘。
4. 打开字号/皮肤/图片百分比面板，横屏再完成操作。
5. 导出 A4 PNG 和单张长图，系统相册/文件管理器能找到；从记录打开/分享，再去存储概览刷新。
6. 进入/退出私密模块，检查系统栏图标、普通历史隔离和截图保护；不使用真实敏感笔记作为自动化测试样本。
7. 记录系统版本、APK 版本/校验值、失败截图及复现步骤；失败格不能以“应该支持”覆盖。

## 8. 完成定义

- 没有硬编码状态栏/刘海/摄像头高度，安全区责任表与代码一致。
- 功能自动化通过，至少 API 29、35、36 的目标场景有运行证据；未具备设备的格明确未验收。
- 当前用户报告的顶部过高、键盘巨大间隙、下方遮挡和切换残影有可重复测试与修复证据。
- 新一轮 APK 保持低于 300 MB，无新增高权限或联网权限。
- 本文及 V1 实施计划同步状态，避免计划/代码/报告互相矛盾。

## 9. E1 执行包记录

本节记录 E1 执行包。E1.1–E1.5 的实现和自动化检查已完成，E1.6 的证据与边界见 [E1 验证报告](../verification/2026-09-29-e1-edge-to-edge-verification.md)。本轮仍不扩张到 OCR、备份、私密导出或整个设置重排。

| 顺序 | 文件/测试 | 具体动作 | 检查点 |
| --- | --- | --- | --- |
| E1.1 | 新增 `androidTest/.../ui/PageInsetsTest.kt` | 构造可控的顶部/左侧切口、底部导航 Insets；测量返回箭头、标题、最后一项的 bounds；加入零 Insets 对照 | 原实现至少在定向遮挡/重复间距用例失败，失败不是找不到测试节点 |
| E1.2 | 新增 `ui/components/PageInsets.kt`、`PageScaffold.kt` | 先实现 SECONDARY 最小策略：背景满屏、内容单层 safeDrawing；对 Scaffold 明确消费 innerPadding；不写固定状态栏高度 | 同一 Insets 注入一次与父层已消费时都不产生两倍留白 |
| E1.3 | 修改 SearchScreen、SettingsScreen、ExportHistoryScreen、StorageOverviewScreen | 按页面逐个迁移，不批量替换所有 padding；保持既有 testTag、按钮行为和数据 VM | 原搜索/字体/导出记录/存储测试不回退；屏外最后按钮可滚动并点击 |
| E1.4 | 修改 CompactPageHeader；新增/扩展 `ui/SecondaryPageLayoutTest.kt` | 自然高度支持 100%/150%/200% 字号，返回仅简约箭头，触摸区至少 48 dp；窄宽标题允许收缩/换行 | 不截标题、返回不贴状态栏；横屏左右切口下仍在安全区 |
| E1.5 | MainActivity、AppNavigation、主题外观控制及测试 | 以当前页面背景控制系统栏图标；进入/退出深色页面成对恢复；不重置 FLAG_SECURE | 浅/深页面可读；私密页截图保护的既有测试仍通过 |
| E1.6 | 验证报告 | 运行定向→全量→Lint/构建/权限；记录截图、bounds、设备、API、字号、方向、导航方式及未测格 | E1 有证据再进入 E2，不能把所有 E1–E6 一次勾选 |

执行状态（更新至 2026-10-01）：E1.1 可控 Insets/零 Insets 测试通过；E1.2 单层 `safeDrawing` 页面容器已实现；E1.3 四个二级页已迁移；E1.4 大字体/窄宽/48 dp 返回触控区用例通过；E1.5 全局浅深主题及私密页系统栏恢复用例通过；E1.6 全量验证记录已生成。E3 的 IME/待办与笔记皮肤—状态栏图标联动已在 MuMu Android 12/API 32 验证。E4 的文件夹、字体、皮肤、图片百分比、排序和导出面板已通过各自列明的 MuMu/API 32 场景；部分短窗口/大字体/分屏矩阵及导出列表高度告警仍待复核。E6 已补齐 MuMu/API 32 的 100%/150%/200% 系统字号和模拟三键导航全量仪器测试；其余 API/实机矩阵见 E6 报告。真实小米 11 Pro、API 29/35/36、竖屏居中/水滴切口、真实 OEM 导航 Insets、候选栏高度变化、输入法切换仍未验收；不得据此宣称全设备全面屏适配完成。

仪器测试可以用可控 Insets 验证布局责任，但必须另保留真实系统窗口测试：注入值测试不能证明 OEM 摄像头、输入法或预测返回兼容。配置大字号/横屏/切口前保存原值；使用 `finally` 恢复，不向应用普通数据库写入真实敏感内容。

建议验证命令（从 `D:/anan/env-check` 执行；新测试类需创建后才能运行）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.PageInsetsTest,com.anan.anannotes.ui.SecondaryPageLayoutTest,com.anan.anannotes.ui.AppNavigationTest,com.anan.anannotes.ui.ExportFlowTest"
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
.\scripts\verify-build-contract.ps1
```

E1 后按 E2→E3→E4→E5→E6 顺序推进。E2 执行证据见 [E2 首页与待办页验证报告](../verification/2026-09-29-e2-main-screens-insets-verification.md)，E3 的 IME/待办与状态栏皮肤联动验证见 [E3 验证报告](../verification/2026-09-29-e3-editor-ime-verification.md)，E4 当前面板与短屏首页证据见 [E4 底部面板验证报告](../verification/2026-09-29-e4-bottom-sheet-verification.md)。下一步复核其余面板在大字号/短窗口下的可达性；真实设备及多 API 矩阵仍按 E6 收尾要求标记未验收。

## 10. E2 首页与待办页执行记录

状态：已完成当前 MuMu Android 12/API 32 的 E2 自动化验证；全设备矩阵仍未完成。

| 顺序 | 文件/测试 | 结果 |
| --- | --- | --- |
| E2.1 | `HomeScreenTest.bulkActionButtonsStayClearOfLandscapeDisplayCutout` | TDD 红灯复现：左侧打孔 inset 为 136 px，批量栏“移动”按钮从 18 px 开始，进入切口安全区；修复后同一打孔模拟下通过 |
| E2.2 | `BulkActionBar.kt` | 交互内容只增加横向 `safeDrawing` Insets；底部仍由 `navigationBarsPadding()` 处理，Surface 背景保持铺满 |
| E2.3 | `SystemWindowInsetsTest.homeAndTodoStayInsideRealInsetsAndKeepBottomNavigationGeometry` | 在真实 Activity 窗口 Insets 下检查两页标题、底部导航安全边界，并确认切换后两个导航项 bounds 不变，通过 |
| E2.4 | 窄屏/字体和既有页面回归 | 既有 `HomeLayoutRulesTest`、标题位置、图片缩略图、排序、批量操作及导航动画测试纳入全量回归 |
| E2.5 | 全量验证 | 170 个 JVM 单元测试通过；仪器 XML 报告 134 项、0 失败、1 项跳过（默认设备未启用打孔）；打孔覆盖层启用时，同一横屏切口用例单独通过；Lint 任务通过（项目现有 13 条 warning、2 条 hint）；Debug APK 构建与构建契约通过 |

MuMu 状态已恢复：打孔 overlay 全部关闭、系统自动旋转仍为开启、user rotation 为 0。Debug APK 为 40,375,949 字节。此处的验收状态反映 E2 执行记录生成时的进度；E6 后续于 2026-10-01 补充了 MuMu/API 32 三键导航测试，但真实小米 11 Pro、API 29/35/36 与 OEM 导航实现仍待验收。

## 11. E3 编辑页与输入法执行记录

状态：IME/待办与笔记皮肤—状态栏图标联动已完成 MuMu Android 12/API 32 自动化验证；设备矩阵未完成。

| 顺序 | 文件/测试 | 结果 |
| --- | --- | --- |
| E3.1 | `EditorScreenTest.editorToolbarStaysWithinEightDpOfVisibleKeyboardTop` | 打开 MuMu 当前 Sogou 输入法后读取真实窗口 IME inset 与 Compose bounds；统一使用 window 坐标像素，工具栏到键盘边界在 8 dp 内，关闭键盘后工具栏回到初始位置；正文末端到工具栏测得 12 dp 紧凑留白 |
| E3.2 | `EditorScreenTest.imageMultiSelectActionBarAlsoTracksVisibleKeyboardTop` 与 `ImageSelectionActionBar` | TDD 红灯：原竖向 padding 8 dp 时，操作栏到键盘实测间距 12.0 dp；收至 4 dp 后同一断言通过。新增 IME 隐藏后的操作栏回位断言；按钮点按/删除回归纳入整组测试 |
| E3.3 | `EditorScreenTest.pressingEnterInTodoCreatesANewIndependentTodoBlock`、`repeatedTodoImeActionsCreateSeparateCheckboxesWithoutAbsorbingBodyText`、`pressingPhysicalEnterInTodoCreatesANewIndependentBlock` | IME action、连续新增三项和 Enter key event 均保留各自独立待办/复选框；新增焦点断言确认焦点移至新待办；随后正文保存为独立文本块；数据库与页面行为通过 |
| E3.4 | `EditorScreenTest.noteWithTrailingImageProvidesEditableSpaceBelowIt` | 图片后仍可滚动并编辑正文；通过 |
| E3.5 | `EditorScreen.kt` | 正文应用 Scaffold `contentPadding` 后显式 `consumeWindowInsets(contentPadding)`；工具栏及图片多选栏继续用 Compose `imePadding()`，不缓存键盘高度 |
| E3.6 | `AppNavigation.kt`、`EditorScreen.kt`、`MainActivity.kt` | 编辑路由接管状态栏图标，按笔记背景亮度设置；Android 导航栏继续显式遵循全局主题（包括 Activity 重建恢复）；退出编辑恢复全局系统栏，私密页规则不变 |
| E3.7 | `SystemBarAppearanceTest.editorSkinControlsStatusBarIconsAndRestoresGlobalThemeOnExit`、`editorActivityRecreationRestoresGlobalNavigationIconsAndKeepsNoteStatusIcons` | 状态栏联动先红灯复现；另强制应用主题与系统主题相反并重建 Activity，旧实现的导航栏图标回到系统默认而非应用主题，修复后两场景均通过 |
| E3.8 | 全量验证 | 170 个 JVM 测试通过；仪器 XML 报告 140 项、0 失败、1 项跳过；Lint 0 errors、13 warnings、2 hints；Debug APK 构建与权限契约通过 |

详见 [E3 编辑页与输入法验证报告](../verification/2026-09-29-e3-editor-ime-verification.md)。本记录不代表不同候选栏高度/输入法切换或小米 OEM 输入法已验收。

## 12. E4 底部面板执行记录

状态：文件夹、字体、皮肤、图片百分比、排序、导出面板及 400/480 dp 横屏首页可达性的已列 MuMu Android 12/API 32 场景均通过；真实分屏、大字号和 OEM/API 矩阵仍待后续验收。

| 顺序 | 文件/测试 | 结果 |
| --- | --- | --- |
| E4.1 | `HomeScreenTest.createFolderKeepsInputAndActionsVisibleAboveKeyboard` | 打开新建文件夹面板，等待真实 IME inset，输入名称并检查输入保留；取消/创建等宽且创建按钮位于键盘顶边上方（容差 8 dp），创建后目录可查询；通过。既有等宽和下滑关闭用例一并全量回归 |
| E4.2 | `TextSettingsSheet.kt`、`EditorScreenTest.typographySheetKeepsActionsAccessibleInLandscapeWindow` | 原实现横屏时找不到滚动语义父级；添加纵向滚动后发现默认半展开状态仍把“恢复全局默认”放在可视窗口外。改为完整展开、使用当前 Compose 窗口高度的 82% 作为内容上限并允许内部滚动；复位操作在横屏可见区域内，通过 |
| E4.3 | `SkinPickerSheet.kt`、`EditorScreenTest.skinPickerShowsPreviewAndControlsInLandscapeWithKeyboard` | 横屏且输入法显示时打开皮肤面板，确认皮肤选项、预览与“完成”均可见/可操作；既有皮肤选择、预览和保存回归通过。本次未发现需要改动生产布局的问题 |
| E4.4 | `ImageAdjustmentSheet.kt`、`EditorScreenTest.imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard` | 横屏和输入法显示时输入 73%，确认输入框及“完成”可操作，关闭面板后确认数据库中对应图片块保存为 73%；通过。测试按图片块类型取值，避免把编辑器的独立正文块误判为图片持久化故障 |
| E4.5 | `HomeScreenTest.sortSheetShowsBothChoicesAndLayoutHintInLandscapeWindow` | 横屏排序面板同时显示“最近编辑时间”“创建时间”和卡片布局说明；已有按文件夹保存排序用例通过 |
| E4.6 | `HomeScreen.kt`、`ExportFlowTest.exportFormatListKeepsLastOptionReachableInLandscapeWindow` | 导出列表高度上限改用 `LocalWindowInfo.current.containerDpSize.height`。临时 720×1280 显示尺寸下横屏显示高度为 480 dp，末项 Markdown ZIP 可滚动到达；尺寸恢复 900×1600。初次 600×900/约 400 dp 复现首页笔记卡不可见，尚未进入多选 |
| E4.7 | `HomeScreen.kt`、`HomeLayoutRules.kt`、`FolderBar.kt`、`NoteCard.kt`、`ExportFlowTest` | 窗口高度低于 440 dp 启用紧凑首页：将“私密”入口移入标题栏、压缩垂直间距和文件夹 chip、缩短搜索框、改用单列紧凑卡片；常规高度保留独立私密卡。400 dp 回归验证卡片可见→长按多选→打开导出面板→到达 Markdown ZIP；480 dp 回归验证常规私密卡及卡片仍可见，均通过 |
| E4.8 | 全量验证与构建 | JVM 172 项通过；仪器回归 147 项、0 失败、1 项跳过；Lint 0 errors、12 warnings、2 hints；Debug APK 41,253,333 字节；签名、包名及构建契约通过。交付包：`D:/anan/env-check/dist/AnAnNotes-2026-09-29-debug.apk` |

完整执行边界与测试环境见 [E4 底部面板验证报告](../verification/2026-09-29-e4-bottom-sheet-verification.md)。MuMu/API 32 的 400 dp 极短横屏和 480 dp 横屏尺寸已分别验证；这些尺寸覆盖不等同于真实分屏窗口验收。200% 系统字体、API 29/35/36、真实小米 OEM 和真实打孔/水滴屏仍未验收。后续复核其他面板的大字体与短窗口可达性，再按 E6 完成真实设备与 API 矩阵。

## 13. E5 私密页、触觉滑杆与大字号面板执行记录

状态：本轮针对私密模块实际系统 Insets、行距/字号触觉反馈、滑杆手柄尺寸及图片大小面板的大字号可达性完成实现和 MuMu Android 12/API 32 回归。系统截图保护保持开启。详细证据见 [E5 验证报告](../verification/2026-09-30-e5-private-insets-verification.md)。

| 顺序 | 文件/测试 | 结果 |
| --- | --- | --- |
| E5.1 | `PrivateUnlockScreen.kt`、`PageScaffold.kt`、`SystemWindowInsetsTest.privateAccessFormStaysInsideRealSystemInsetsAndKeepsScreenshotProtection` | 私密模块统一由满屏背景和单层 safeDrawing 内容容器承载；返回控件避开真实顶部/左侧 Insets，主操作避开底部 Insets；继续断言 `FLAG_SECURE`，通过 |
| E5.2 | `HapticSlider.kt`、`SettingsScreen.kt`、`TextSettingsSheet.kt`、`FontSizeHapticFeedbackTest` | 字号变化按 1 sp、行距按 0.05 倍触发 `SegmentFrequentTick`；偏好页两条滑杆及单条笔记覆盖面板均接入；控件手柄为 8 × 24 dp；两项触觉反馈仪器用例通过 |
| E5.3 | `ImageAdjustmentSheet.kt`、`EditorScreenTest.imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard` | 200% 系统字号、横屏与输入法同时出现时，图片大小面板限制为窗口高度 82% 并支持面板内部滚动；滚动到完成、保存 73% 并从数据层读回，通过 |
| E5.4 | 完整验证 | 172 项 JVM 测试、149 项 Android 仪器测试均为 0 失败/错误/跳过；Lint 0 errors、12 warnings、2 hints；构建契约通过；Debug APK 41,253,333 字节 |

E5 验证仅覆盖 MuMu Android 12/API 32，不代表真实小米 11 Pro、Android API 29/35/36 或所有刘海/水滴/居中挖孔布局均已验收。系统截图仍因私密页 `FLAG_SECURE` 被有意禁止；“全面屏适配”指内容避让安全区，不表示允许系统截取私密内容。E6 仍需真实设备/API 矩阵及手势/三键导航检查。

### E6 阶段性补充（2026-09-30）

MuMu/API 32 的 149 项 Android 仪器测试已分别在系统字号 100% 与 200% 全量运行并通过；200% 检查中修正了首页/待办标题顶部对齐，并让排序、多选、跨皮肤用例正确滚动到懒加载卡片。另有 172 项 JVM 测试、Lint、APK 构建和构建契约检查通过。完整设备参数、报告计数、APK 哈希与未验收格见 [E6 字号回归报告](../verification/2026-09-30-e6-font-matrix-verification.md)。其余设备/API/导航格保持未验收状态。

2026-10-01 补跑 MuMu/API 32 系统字号 150% 全量 Android 仪器测试，154/154 通过且无跳过；另在系统字号 100% 下切换至三键导航，154/154 通过。两次测试后分别通过 `try/finally` 恢复字号/导航，并复核字号为 100%、导航为手势模式。另复跑 JVM 174 项、Lint（0 errors / 12 warnings / 2 hints）、Debug APK 构建与构建契约，均通过。三键专项只证明 MuMu/API 32 的模拟 overlay 行为。报告按各次实际测试数分别记录；API 29/35/36、真实小米 11 Pro 和异形屏/OEM 仍待验收。本机 Android SDK 未安装 Emulator 程序或系统镜像，未擅自下载。

### E7 私密首页视觉与调试截屏（2026-10-01）

私密笔记列表保持安静单列卡片：卡片与石墨背景以低对比细描边区分，去除默认投影并增加一致的左右留白。私密页顶栏改为无文字返回箭头和锁定图标；底部“笔记 / 待办”导航补上自绘、可读屏的线性图标，无须引入扩展图标依赖。E7 取代 E5 对截图策略的描述，但不改变 E5 的 Insets 验收结论：Debug 构建只允许截图已解锁内容，密码/恢复密钥页面和 Release 全程防截屏，进入后台立即启用保护并锁定。此例外仅用于使用虚构数据的 Debug 诊断。2026-10-01 补充的 `PrivateLifecycleSecurityTest` 已在 MuMu/API 32 覆盖实际 Activity 的 `ON_STOP` 防截屏恢复、退出页面恢复原 secure 位及保留其他窗口标志；故障注入确认测试可捕获缺少后台保护的回归。详情见 [私密首页与 Debug 截图验证报告](../verification/2026-10-01-e7-private-home-and-debug-capture.md) 和 [私密模块安全设计](../security/2026-09-27-anan-notes-private-crypto.md)。

### 后续依赖和实现前置条件

- **普通编辑页快速导出**：先稳定编辑器和 IME；提供显眼但低饱和的导出按钮；等待当前修订保存成功再取快照。测试“最后一次输入尚在 debounce/IME composition”“空白笔记”“图片/待办状态刚修改”以及保存冲突；不能直接用旧数据库快照导出。
- **私密导出**：先具备独立密码验证授权和加密历史设计；导出前提示明文不再受私密模块保护。普通历史/存储页必须持续通过隔离测试，不能为方便复用把私密 ID/URI 送入普通服务。
- **离线 OCR 和备份**：按原 V1 文档独立推进；OCR 百分比只能基于真实可计量阶段，不能伪造模型推理进度。模型、包体和性能再评估，不因本轮 PNG 完成就预先写成已支持。

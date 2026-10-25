# 安安简记 E1：安全区与系统栏验证记录

日期：2026-09-29。状态：E1 最小交付通过；设备矩阵尚未完成。

工程：`D:/anan/env-check`。实施方针：[全面屏与异形屏适配详细方针](../plans/2026-09-28-anan-notes-edge-to-edge-guideline.md)。本轮限定于搜索、设置、导出记录、存储概览四个二级页面，以及它们的页面安全区、返回栏和系统栏外观；未开始 E2–E6。

## 实现内容

- 新增 `PageInsets` 与 `PageScaffold`：背景铺满窗口，页面内容在一个边界消费 `safeDrawing`，避免重复叠加系统栏/切口间距。
- 搜索、设置、导出记录、存储概览迁移到统一二级页容器，保留原路由、测试标签、操作与数据状态。
- 返回栏支持自然增高、长标题换行，并提供至少 48 dp 的返回触控区。
- 状态栏和导航栏图标随全局浅/深主题调整；私密页强制浅色图标并在退出后恢复用户选择的主题；原有 `FLAG_SECURE` 行为保留。单条笔记皮肤与系统栏图标联动尚未覆盖，留待 E3。
- 新增合成 Insets、真实窗口 Insets、字号布局和系统栏状态回归测试。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 170 项通过；0 失败、0 错误、0 跳过 |
| `:app:connectedDebugAndroidTest` | MuMu 上 131 项通过；0 失败、0 跳过 |
| 切口窗口专项复测 | MuMu 模拟左侧打孔，`SystemWindowInsetsTest` 1 项通过并完成同步截图 |
| `:app:lintDebug` | 0 错误；13 条 Warning、2 条 Hint，类型见下文 |
| `:app:assembleDebug` | 成功 |
| `scripts/verify-build-contract.ps1` | Application ID、最低 SDK、无 INTERNET 权限、应用名称均 PASS |

全量命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug --console=plain
.\scripts\verify-build-contract.ps1
```

Lint 未清理项：`OldTargetApi` 1、`NewerVersionAvailable` 3、`ConfigurationScreenWidthHeight` 1、`ObsoleteSdkInt` 1、`UseKtx` 7；Hint 为 `AutoboxingStateCreation` 2。它们不导致本次构建失败，后续 SDK/窗口尺寸工作仍需单独处理。Gradle/JBR 另输出 restricted native-access 提示，未导致失败。

## MuMu 窗口证据

- 设备：MuMu，`NTH-AN00` / `Nottingham`，Android 12（API 32）；物理分辨率 900×1600、density 240。此次应用测试窗口/root 为 1600×900（横向），因此不把它记为竖屏验证。
- 系统 `secure.navigation_mode` 读取值为 `0`，测试期间未修改；只记录原始值，不据此推断小米等 OEM 的手势导航表现。
- 未模拟切口时：系统报告 `safe Insets(left=0, top=36, right=0, bottom=0)`；设置页标题 bounds 为 `Rect(30, 48, 1570, 126)`。
- 临时启用 Android 打孔切口 overlay 时：系统报告 `safe Insets(left=136, top=36, right=0, bottom=0)`、`displayCutout Insets(left=136, top=0, right=0, bottom=0)`；设置页标题 bounds 为 `Rect(166, 48, 1570, 126)`。搜索页也通过同一真实窗口安全区断言。坐标为测试窗口像素。
- 设置页末尾缓存操作的 bounds 断言通过；但该 MuMu 场景真实 bottom inset 为 0，底部 inset 的非零边界只由可控 Insets 测试覆盖，不能视为真实导航栏验收。
- 截图：[MuMu 左侧打孔下的设置页](assets/mumu-hole-settings-top.png)。截图采集已等待设备端完成后再继续 UI 操作，画面可见返回栏避开左侧安全区。
- 临时 overlay 在 `finally` 中关闭。复核结果：打孔 overlay 为关闭状态；`accelerometer_rotation=1`、`user_rotation=0` 保持原值；没有遗留的 E1 截图在模拟器 Download 目录。

## APK

Debug APK：`D:/anan/env-check/app/build/outputs/apk/debug/app-debug.apk`，41,215,925 bytes（约 39.3 MiB，低于 300 MB）。SHA-256：

```text
19C473CC7C6EE46CEAC7158BF810EECD3E4A33676EE97A5AFFEA26BD7171F664
```

## 验收边界与下一步

本轮没有在小米 11 Pro 实机上验证，也没有覆盖 API 29/35/36、竖屏居中/水滴切口、瀑布屏、折叠/分屏、真实底部导航安全区、真实输入法/IME、返回手势中间帧或旋转切换。测试没有向普通或私密笔记数据库写入用户内容。MuMu 的切口 overlay 仅证明 Android 提供的左侧 `displayCutout` Insets 能被安全消费，不代表具体厂商硬件布局已验收。

按方针顺序，E2 首页与待办页尚未开始；随后依次执行 E3 编辑页/键盘、E4 面板窗口高度、E5 旋转/分屏与返回动画、E6 设备与无障碍回归。开始下一项前继续保持本报告中的未测项可见，不把 E1 结果扩大解释为全设备兼容。

# E4 底部面板验证记录（更新至第三批）

日期：2026-09-29  
工程：`D:/anan/env-check`  
范围：新建文件夹键盘适配、字体与显示面板横屏可达性，以及皮肤、图片百分比、排序和导出面板的横屏/输入法回归；后续补充 480 dp 导出面板和 400 dp 极短横屏首页多选验证。本报告记录 MuMu/API 32 覆盖结果；真实分屏、额外系统字体和 OEM 设备仍未验收。

## 变更与复现

### 新建文件夹

保留已确认的底部面板方案：输入框 12 dp 圆角、“取消/创建”等宽、下滑关闭。新增仪器测试 `HomeScreenTest.createFolderKeepsInputAndActionsVisibleAboveKeyboard`，在 MuMu 打开真实输入法后输入文件夹名称，检查文本仍在输入框内、两个按钮等宽、按钮底边不落到键盘后面，并完成创建后查询目录。

此前的实现已经满足按钮对称和下滑关闭；本次将键盘可见时的几何边界及输入—创建完整链路固定成回归测试，没有重复调整视觉。

### 字体与显示面板

新增 `EditorScreenTest.typographySheetKeepsActionsAccessibleInLandscapeWindow`，切换到横屏，打开已有字号/行间距覆盖的笔记，并进入“仅本条笔记”设置，检查“恢复全局默认”可见、可点击且位于面板范围内。测试结束在 `finally` 中恢复原请求方向。

TDD 复现与定位：原面板在横屏下缺少可滚动的父布局；加入滚动后，测试仍发现底部操作不在可视窗口内。Compose 语义日志显示面板默认处于半展开状态，内容节点伸出窗口，而滚动轴最大值为 0。最终将面板切换为完整展开，并对内容区设当前窗口高度的 82% 上限、添加纵向滚动。横屏下恢复操作进入可视面板区域，测试通过。

实现使用 `LocalWindowInfo.current.containerDpSize.height` 为内容高度提供窗口相关约束。官方 API 文档说明，窗口容器尺寸可用于整体布局判断，但在 Compose 层未填满窗口或安全区域收缩时，容器尺寸可能大于实际可用内容区；因此此值只作为比例上限，Material 底部面板仍负责自身窗口 Insets，不能据此宣称分屏、切口或所有 OEM 均已验收。[WindowInfo API](https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/WindowInfo)

### 皮肤选择面板

新增 `EditorScreenTest.skinPickerShowsPreviewAndControlsInLandscapeWithKeyboard`：横屏下打开编辑页输入法，再打开皮肤面板，确认皮肤选项、实时预览和“完成”操作均可见且能关闭面板。已有测试继续覆盖选择皮肤、预览更新与重开笔记后的持久化。本场景未复现布局故障，因此没有为追求代码变更而调整现有布局。

### 图片百分比面板

新增 `EditorScreenTest.imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard`：横屏打开图片设置，输入 73%，在真实 IME 可见时确认输入框与“完成”操作可用，关闭面板并读取数据库确认对应图片块保存为 73%。首次断言错误地假设笔记只有一个内容块；编辑器数据实际允许图片之外还有独立正文块。将测试改为按 `BlockType.IMAGE` 查找后通过，生产功能无需修改。

2026-09-30 复测 200% 系统字号后发现此前覆盖遗漏：图片面板正文变高时，底部操作没有滚动父容器，“完成”节点未完成布局；加入 `performScrollTo()` 后原实现明确以“没有 Scroll SemanticsAction”失败。修复采用与字体面板一致的模式：底部面板完全展开，内容高度上限为 Compose 窗口高度的 82%，内容区纵向滚动。MuMu Android 12 / API 32、横屏、系统字号 200%、数字输入法显示时，滚动到“完成”并点击、读取数据库确认 73% 均通过。测试脚本在 `finally` 中将系统字号恢复为原值（1.0）。该结果仅覆盖图片百分比面板，不代表其它面板均已在 200% 字号验收。

### 排序面板

新增 `HomeScreenTest.sortSheetShowsBothChoicesAndLayoutHintInLandscapeWindow`，在横屏状态验证“最近编辑时间”“创建时间”和卡片布局说明可见。原有测试仍覆盖按文件夹分别保存排序状态；本场景未发现需调整生产布局的问题。

### 导出格式面板

`HomeScreen.kt` 的列表高度上限改为 `LocalWindowInfo.current.containerDpSize.height * 0.68f`，与字体设置面板使用实际 Compose 窗口容器尺寸的做法一致；移除了对 `Configuration.screenHeightDp` 的直接依赖。横屏可达性测试在长按后等待“已选”标题出现，再打开导出面板，滚动并确认最后的 Markdown ZIP 选项可见。

紧凑显示验证：通过 MuMu 临时设置 720×1280 像素（240 dpi），再旋转应用至横屏，显示高度约 480 dp（系统栏后应用内容区更小）；长按选择、打开导出面板、滚动至末项均通过。随后以 600×900 像素复现约 400 dp 横屏：原首页卡片完全不可见，长按无法进入多选。修复后，窗口高度低于 440 dp 时启用紧凑首页：减少垂直间距、缩小搜索与文件夹 chip、卡片改为单列紧凑摘要，并将“私密”入口放入标题栏；常规高度仍保留原独立“私密笔记”卡片。新增 400 dp 回归检查紧凑私密入口、笔记卡可见、长按进入多选、打开导出面板并滚动至 Markdown ZIP；另加 480 dp 回归确认常规私密卡片和笔记卡可见。两尺寸及 400 dp 最终导出路径均通过。临时尺寸由 PowerShell `finally` 恢复；最终确认 MuMu 为 900×1600、240 dpi。尺寸覆盖测试不等同于真实分屏窗口；直接对全屏 Activity 调用 `Window.setLayout` 不能改变 decor 高度，故未保留该无效夹具。

## 验证结果

运行环境：MuMu 5.30.1，Android 12 / API 32，设备标识 `NTH-AN00`，测试分辨率 900 × 1600、240 dpi。测试期间横屏切换由用例执行并在 `finally` 中还原。

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
.\scripts\verify-build-contract.ps1
```

- JVM 单元测试：172 项通过，0 失败。
- MuMu 仪器测试：147 项，0 失败、0 错误、1 项跳过。跳过项为 MuMu 默认未开启打孔覆盖层时的横屏切口专项。
- Lint：0 errors、12 warnings、2 hints；首页导出面板的 `Configuration.screenHeightDp` 警告已消失，其余告警不在本批范围内。
- Debug APK：41,253,333 字节，低于 300 MB 包体目标；APK 签名和包名验证通过。
- 构建契约：Application ID、最低 SDK 29、无 `INTERNET` 权限、应用名“安安简记”均通过。
- 今日交付包：`D:/anan/env-check/dist/AnAnNotes-2026-09-29-debug.apk`；与已验证构建输出 SHA-256 一致：`320018DE1F96140B126E2EB3C70FE3FB91D8BF5840F0F6B17263502D10F1D491`。签名方案为 Android debug key 的 APK v2；包名 `com.anan.anannotes`，versionName `1.0`，minSdk 29、targetSdk 36。

本报告覆盖文件夹、字体、皮肤、图片百分比、排序、导出面板，以及 MuMu/API 32 上 400/480 dp 横屏首页入口的所列自动化场景。400 dp 极短横屏已可从首页进入多选并到达 Markdown 导出选项；图片百分比面板另在横屏、键盘显示和 200% 系统字号下通过。真实分屏窗口仍未验收；其它面板在 200% 字号、API 29/35/36、真实小米 11 Pro、OEM 输入法、真实打孔/水滴屏也仍未验收。后续补充其他底部面板的大字号/短窗口覆盖，并按 E6 计划完成真实设备与 API 矩阵。

# E5 私密页、触觉滑杆与大字号面板验证报告

日期：2026-09-30  
工程：`D:/anan/env-check`  
范围：私密模块真实系统 Insets、字号/行距触觉反馈与滑杆手柄尺寸、200% 系统字号下图片设置面板的滚动可达性。

## 实施结果

### 私密模块全面屏安全区

排查前的 MuMu 实测中，系统安全 Insets 为 `left=72 px, top=36 px`，私密页返回控件 bounds 的顶部只有 `6 px`，因此明确复现了控件进入顶部状态栏/显示切口区域的问题。

`PrivateUnlockScreen` 现在以共享 `PageScaffold` 绘制全屏私密背景，并让私密模块的各状态共用单层 `safeDrawing` 内容区域。页面背景仍延伸到屏幕边缘，交互内容避开系统状态栏、显示切口及导航栏安全区域。`SystemWindowInsetsTest.privateAccessFormStaysInsideRealSystemInsetsAndKeepsScreenshotProtection` 读取设备根窗口 Insets，验证私密页返回控件的顶部和左侧边界、底部主按钮边界。

私密页面继续设置 Android `FLAG_SECURE`。因此系统截图会被 Android 有意阻止；本次解决的是页面内容被异形屏/系统栏遮挡的问题，并没有开放私密内容截图。仪器测试另外断言该安全标志仍处于启用状态。

### 字号与行距触觉反馈

新建共用 `HapticSlider`，字号和行间距滑杆在跨越离散调整步长时调用 `SegmentFrequentTick`。字号以 1 sp 为触觉步进，行距以 0.05 倍为步进；应用范围包括设置页全局默认值和单条笔记的排版覆盖。滑杆竖向手柄缩小为 `8 × 24 dp`。

`FontSizeHapticFeedbackTest` 的两项仪器测试分别检查设置页字号/行距滑动的反馈次数，以及行距跨越 0.05 步长时确实触发一次反馈。编辑器排版面板的既有保存用例也纳入全量测试。

### 200% 字号下图片设置面板

横屏且系统字号设为 200% 时，图片大小面板的底部操作原本没有可滚动父级，测试无法滚动到“完成”。现已将面板完全展开，内容高度限制在当前 Compose 窗口高度的 82%，并为内容增加内部纵向滚动。

MuMu Android 12/API 32、横屏、系统字号 200%、数字输入法可见时，`EditorScreenTest.imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard` 将百分比设为 73%，滚动到“完成”并点击，再从数据库确认图片块宽度为 73%。用例结束后系统字号恢复为 1.0。

## 验证环境与结果

- MuMu Android 12 / API 32，设备标识 `NTH-AN00`；自动化实际窗口日志为 `1600 × 900`，即横向窗口。真实小米设备尚未连接参与本轮验证。
- JVM 单元测试：172 项，0 失败、0 错误、0 跳过。
- Android 仪器测试：149 项，0 失败、0 错误、0 跳过。
- Lint：0 errors、12 warnings、2 hints。
- `scripts/verify-build-contract.ps1`：Application ID、min SDK 29、无 `INTERNET` 权限、应用名称均通过。
- Debug APK：41,253,333 字节；SHA-256：`AC4CE800F132F70DC685130853D7F14A532404BBF01EC66D352B027F242A704F`。
- APK：[`app-debug.apk`](../../../env-check/app/build/outputs/apk/debug/app-debug.apk)。

Gradle 总任务成功，XML 仪器测试报告为 149 项且无失败/错误/跳过。Gradle 控制台末尾曾附带一段 Activity teardown 的 `Activity client record must not be null` 诊断，但设备 Logcat 未发现对应的 `FATAL EXCEPTION`，测试 XML 也没有错误记录；本报告以任务退出状态和 XML 汇总为准，后续全量回归继续观察该诊断是否复现。

## 验收边界

本轮完成 MuMu Android 12/API 32 的自动化回归，不宣称完成所有全面屏适配。真实小米 11 Pro、Android API 29/35/36、居中挖孔/水滴屏 OEM 安全区、三键导航和手势导航组合仍待 E6 验收。200% 系统字号的内部滚动只针对图片大小面板验证，不能外推到其它底部面板。私密页 `FLAG_SECURE` 仍会阻止系统截图，这是有意保留的隐私策略。

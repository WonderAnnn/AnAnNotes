# E7 私密首页与 Debug 截图验证报告

日期：2026-10-01  
工程：`D:/anan/env-check`  
模拟器：MuMu，NTH-AN00，Android 12 / API 32，900 × 1600 px，240 dpi

## 本轮实现

- 私密笔记保持用户选择的安静单列卡片：卡片有低对比细描边、零投影和统一左右留白，不显示未加密或不存在的正文摘要。
- 私密页顶栏使用简洁返回箭头和锁定图标；底部“笔记 / 待办”均有自绘线性图标和无障碍描述，不添加扩展图标依赖。
- 截图策略只在应用 `FLAG_DEBUGGABLE` 时放开已解锁的私密内容页。口令页、恢复密钥确认页和恢复密钥显示状态仍启用 `FLAG_SECURE`；Release 全程启用 `FLAG_SECURE`。
- `ON_STOP` 回调先同步恢复 `FLAG_SECURE`，再锁定私密会话。退出页面时按进入前的 secure 位恢复，同时保留其他窗口标志。
- Debug 截图可能被 Android 保存到系统相册；仅使用虚构测试数据诊断，Debug APK 不得携带真实私密内容或作为正式包分发。

## 验证结果

最终代码变更后执行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug
```

- JVM：174 项，0 失败、0 错误、0 跳过。
- MuMu Android 12 / API 32：153 项 Android 仪器测试，0 失败、0 跳过。
- 定向私密首页、私密导航及系统 Insets 测试：6 项通过；另有真实窗口 `FLAG_SECURE` 开/关与其他窗口位保留测试。
- `lintDebug`：任务成功；现存 12 项 Warning、2 项 Hint，本轮没有新增错误级问题。

第一次全量运行曾在第 89 项附近遭遇 MuMu `Activity client record must not be null` 进程崩溃，错误落在既有横屏皮肤面板用例。该用例单独复跑通过，`EditorScreenTest` 类 31/31、Insets 类 4/4 通过；随后最终全量复测 153/153 通过，未改动生产逻辑来掩盖该偶发模拟器事务异常。

Release 边界执行：

```powershell
.\gradlew.bat :app:assembleRelease
```

构建成功。产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`（30,001,460 bytes，SHA-256 `C757F6EBF0F501B1C6DC71C33E5C633085607B06F1F1AB1761AC743396CC22FB`）；Release packaged manifest 中不存在 `android:debuggable="true"`。安全策略单元测试覆盖 Debug 解锁可截屏、Debug 未解锁/恢复密钥显示仍保护、Release 解锁仍保护。

此前代码审查未发现 Critical / Important 问题；Minor 提醒 `ON_STOP` 与退出页面的端到端生命周期组合仍缺少专用仪器测试。该测试缺口已由下方补验关闭。

## E7 生命周期组合补验（2026-10-01）

新增 `PrivateLifecycleSecurityTest.backgroundReenablesScreenshotProtectionAndLeavingPrivateModuleRestoresWindowFlags`，通过真实 `MainActivity` / MuMu 窗口执行以下流程：打开私密入口、在测试中清除 `FLAG_SECURE` 并加入 `FLAG_KEEP_SCREEN_ON`、将 Activity 从 `RESUMED` 移至 `CREATED` 触发 `ON_STOP`、恢复前台并退出私密页。断言后台后重新启用 `FLAG_SECURE`；离开模块后恢复进入前的 secure 位，同时保留其他窗口标志。测试未设置密码、创建私密内容或保存截图。

回归测试有效性检查：临时移除 `ON_STOP` 中的 `addFlags(FLAG_SECURE)` 后，该用例按预期在“进入后台必须重新启用 `FLAG_SECURE`”断言失败；恢复生产代码后定向复跑通过。

补验后的完整命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleRelease
.\scripts\verify-build-contract.ps1
```

- JVM：174 项；0 失败、0 错误、0 跳过。
- MuMu Android 12 / API 32：154 项仪器测试；0 失败、0 错误、0 跳过。
- Lint：0 errors、12 warnings、2 hints；均为项目现存提示。
- Release APK：30,001,460 bytes；SHA-256 `C757F6EBF0F501B1C6DC71C33E5C633085607B06F1F1AB1761AC743396CC22FB`；用 `aapt dump badging` 检查未出现 `application-debuggable`。
- 构建契约：Application ID、最低 SDK、无 INTERNET 权限、应用名均通过。

此生命周期用例验证真实 Activity 的安全窗口标志设置与恢复；当前从未解锁的口令页进入，因此它不单独断言已解锁 ViewModel 内容状态的变化。`ON_STOP` 仍由生产回调调用 `viewModel.lock()`；私密内容数据隔离和锁定行为另由现有私密存储/会话代码测试覆盖。

## 本轮未覆盖

- 未在小米 11 Pro 真机验证 OEM 截屏、任务切换预览和锁屏行为。
- 未以真实私密数据测试；请勿用正式数据验证 Debug 截图。
- Release 包未安装到设备；本轮 Release 验证为构建和 manifest 检查。
- 不同 Android API、异形屏、横屏及分屏矩阵仍按总适配计划待验。

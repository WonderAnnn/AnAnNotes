# E2：首页与待办页 Insets 验证报告

日期：2026-09-29  
工程：`D:/anan/env-check`  
设备：MuMu，Android 12 / API 32，测试设备标识 `NTH-AN00 - 12`

## 结果

修正首页多选状态下的底部批量操作栏：横屏时将交互内容从显示切口的安全区向内收，背景仍延伸到窗口边缘；底部导航栏 Insets 继续单独处理。首页和待办页的实际窗口布局及切换后导航项位置也加入了集成回归。

## TDD 证据

新增 `HomeScreenTest.bulkActionButtonsStayClearOfLandscapeDisplayCutout`，通过 MuMu 打孔覆盖层模拟横屏左侧摄像头切口。

- 修复前失败：切口左侧 inset 为 `136 px`，批量栏“移动”按钮 left 为 `18 px`，落入切口范围。
- 修复后同一场景通过：`BulkActionBar` 的交互 Row 增加横向 `WindowInsets.safeDrawing` padding；没有把整条 Surface 背景缩进安全矩形。
- 默认 MuMu 未启用打孔时，该测试按条件跳过；切口模拟已在隔离的定向运行中启用并通过。

新增 `SystemWindowInsetsTest.homeAndTodoStayInsideRealInsetsAndKeepBottomNavigationGeometry`，读取真实 Activity 窗口 Insets，检查首页/待办页标题的顶部与左右边界、底部导航与导航栏安全区的关系，并验证切换后两个导航项的 bounds 不移动。MuMu/API 32 上通过。

## 验证结果

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
.\scripts\verify-build-contract.ps1
```

- JVM 单元测试：170 项通过，0 失败。
- MuMu 仪器测试：XML 报告 134 项，0 失败，1 项跳过（默认无打孔配置）；打孔场景对应的专项测试另行启用 overlay 后通过。
- Lint：任务成功；工程当前报告 13 条 warning、2 条 hint，未涉及本次修改的 `BulkActionBar.kt` 或新增测试文件。
- Debug APK：`40,375,949` 字节。
- 构建契约：Application ID、最低 SDK 29、无 INTERNET 权限、应用名“安安简记”均通过。
- MuMu 恢复检查：打孔 overlays 全部关闭；自动旋转保持开启；user rotation 为 0。

## 尚未验收

这份报告仅覆盖 MuMu Android 12/API 32 与模拟打孔切口。真实小米 11 Pro、Android API 29/35/36、三键与手势导航对照、横屏真实 OEM 切口、大字号下底部点击目标仍未覆盖；不能据此宣称全部异形屏或设备矩阵完成。

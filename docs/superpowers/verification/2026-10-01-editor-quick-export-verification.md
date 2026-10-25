# 普通编辑页单条快速导出验证

日期：2026-10-01  
工程：`D:/anan/env-check`  
实现计划：[普通编辑页单条快速导出实现计划](../plans/2026-10-01-editor-quick-export-implementation.md)  
产品方针：[高清导出及存储实施方针](../plans/2026-09-28-anan-notes-export-storage-guideline.md) 第 7 节

## 本轮实现

- 普通笔记编辑页顶栏增加可见的“导出”入口，使用与首页相同的格式面板和七种现有格式。
- 新增普通导出服务工厂，首页与编辑页共享 Room 快照读取、PDF/PNG 渲染、SAF、MediaStore 和普通导出历史依赖。
- 编辑页导出前检查空白新稿、输入法 composition、恢复状态、保存失败及版本冲突；通过现有保存互斥锁串行等待最新修订持久化，再将单一普通 `noteId` 交给导出服务。
- 标题、正文和待办输入均跟踪输入法组合区间；组合输入尚未完成时不导出，显式保存/退出会统一提交组合状态。
- 待办勾选的异步写入与导出快照串行化；导出等待尚未完成的勾选写入，避免文件内容和“待办”页状态短暂不一致。
- 对已存在但已清空的笔记同样禁止空内容导出；测试确认不会新增导出历史。
- 导出期间显示不可取消的进度对话框，阻止触摸编辑与返回；完成和失败继续使用现有文件位置反馈、错误代码和导出记录。
- 普通导出只访问普通 Room、普通原图及普通导出历史；未接入私密数据、密钥或私密历史。没有新增 Android 权限。

## 测试与构建

完整强制命令：

```powershell
.\gradlew.bat --rerun-tasks :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug --console=plain
```

| 检查 | 实测结果 |
| --- | --- |
| JVM 单元测试 | 182 项；失败 0、错误 0、跳过 0 |
| MuMu Android 仪器测试 | 158 项；运行 157、失败 0、错误 0、跳过 1 |
| Lint | 0 Error、12 Warning、2 Hint |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk`，39,877,539 bytes（约 38.03 MiB） |
| 可分发安装包 | `dist/AnAnNotes-2026-10-01-debug.apk`；39,877,539 bytes，与构建产物 SHA-256 一致 |
| 构建契约 | Application ID、最低 API 29、无 INTERNET 权限、应用名四项均 PASS |
| MuMu 安装/启动 | ADB 安装成功；`am start -W` 返回 `Status: ok`、`LaunchState: COLD`；前台为 `com.anan.anannotes/.MainActivity`，进程正常运行 |

唯一跳过项为横屏刘海避让测试：连接的 MuMu `NTH-AN00` 不具备显示切口，不能在该设备上验证此场景。仪器测试 XML：`app/build/outputs/androidTest-results/connected/debug/TEST-NTH-AN00 - 12-_app-.xml`（158 项，失败/错误 0，跳过 1）。JVM XML 位于 `app/build/test-results/testDebugUnitTest/`（182 项，失败/错误/跳过均 0）。

定向验证：

- `EditorExportRulesTest` 和 `EditorPersistenceRulesTest` 通过；涵盖空白的已存笔记、标题/正文/待办组合输入、恢复、失败、冲突及显式保存。
- `EditorExportFlowTest` 定向运行 4/4 通过：最新正文成功导出、空白新稿无副作用、空白已存笔记不生成记录、待办勾选写入先于导出快照完成。
- `EditorScreenTest#quickCreateOpensTitleBodyAndBasicToolbar`、`ExportFlowTest` 均包含在全量 MuMu 回归中并通过；首页导出路径未回归。
- TDD 初始阶段的新编辑页用例曾按预期因 `editor-export-button` 尚不存在而失败；接入入口后相同用例通过。

## 设备和验收边界

- 本轮完成的是普通编辑页单条导出，不代表 V1 项目整体完成。
- 当前 ADB 只连接 MuMu Android 12/API 32；小米 11 Pro 未连接，未进行真机验收。
- 没有测试第三方 SAF 文件提供器、重启后的 URI 授权、异形屏设备矩阵、私密导出、加密历史或 OCR；不得据本轮结果推断这些功能已验收。
- 工程目录没有 Git 元数据；本轮没有初始化 Git、移动分支或创建提交。

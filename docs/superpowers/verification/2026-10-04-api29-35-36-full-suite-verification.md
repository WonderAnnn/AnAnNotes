# 2026-10-04 API 29/35/36 全量回归记录

## 结果摘要

| API | 仪器测试计划数 | 通过 | 跳过 | 失败/错误 | 说明 |
|---|---:|---:|---:|---:|---|
| 29 | 199 | 193 | 6 | 0 | 4 个分片：50、50、50、49 项；比 API 35/36 多一个需活动切口的专项用例跳过 |
| 35 | 199 | 194 | 5 | 0 | 5 个分阶段恢复/SAF fixture 按设计跳过 |
| 36 | 199 | 194 | 5 | 0 | 5 个分阶段恢复/SAF fixture 按设计跳过 |

API 29 的额外切口用例随后通过临时启用系统 `tall` cutout overlay 单独复验：1/1 通过。复验后已禁用该 overlay 并确认恢复到原来的无切口配置。

此前 API 36 观察到的 6 个 UI 自动化失败在本次三 API 全量回归中均未重现；下列方法在 API 29、35、36 的全量结果中均为 PASS：

- `EditorScreenTest#editorToolbarStaysWithinEightDpOfVisibleKeyboardTop`
- `EditorScreenTest#imageMultiSelectActionBarAlsoTracksVisibleKeyboardTop`
- `EditorScreenTest#skinPickerShowsPreviewAndControlsInLandscapeWithKeyboard`
- `EditorScreenTest#imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard`
- `HomeScreenTest#createFolderKeepsInputAndActionsVisibleAboveKeyboard`
- `PrivateExportDocumentsUiRoundTripTest#choosingSafFolderLocksPrivateVaultThenResumesOnlyAfterUnlockAndExportReauthentication`

这说明当前回归未发现这些问题，但没有定位此前失败的根因；不能据此声称代码修复了它们。

## 跳过项说明

API 35/36 的 5 个跳过项，以及 API 29 的其中 5 项，均为必须通过独立进程参数运行的阶段夹具：

- `BackupRestoreColdStartRecoveryTest`：seed 与 verify 两阶段。
- `PrivateSafBackupRestoreDocumentsUiTest`：独立私密 SAF 阶段用例。
- `PrivateSafRoundTripFixtureTest`：seed 与 verify 两阶段。

这些 fixture 并非本轮新做的端到端覆盖；其此前单独执行的证据记载在[阶段实施计划](../plans/2026-10-01-private-export-ocr-backup-device-implementation.md)。API 29 第 6 个跳过项是设备默认未模拟切口时的 cutout 专用断言，该断言已通过上述 `tall` overlay 定向运行。

## 构建与静态验证

在 AVD 关闭后执行新鲜 JVM 测试、Lint、Debug APK 与 AndroidTest APK 构建：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
.\scripts\verify-build-contract.ps1
```

- JVM 单元测试：330 项通过，0 失败、错误或跳过。
- Lint：0 errors、17 warnings、2 hints。
- `assembleDebug`、`assembleDebugAndroidTest`：成功。
- Debug APK：74,721,145 bytes；AndroidTest APK：29,071,650 bytes。
- 构建契约：Application ID、`minSdk`、无 `INTERNET` 权限、应用名均通过。
- 编译存在既有 Kotlin warnings，未阻断构建。

## 执行边界与后续

- API 29/35/36 AVD 均以只读、无快照加载/保存模式冷启动；完整仪器测试与构建完成后已关机，ADB 设备列表为空。
- API 36 测试期间临时更改的 `show_ime_with_hard_keyboard` 已恢复；API 29 的 cutout overlay 已恢复为禁用。API 29/35 原有键盘设置未改动。
- 本轮未修改应用或测试源代码；Gradle 构建产物及报告按测试运行更新。
- API 29/35/36 的全部异形屏 × 窄屏 × 字号 × 导航 × 主题交叉矩阵尚未完成。当前 cutout 单测与既有组合测试不能替代完整交叉矩阵。
- Xiaomi 11 Pro 真机、其他 DocumentsProvider、QQ/微信真实分享及第三方文件管理器行为仍待验收。

## 证据来源

仪器测试按各 API 的 Gradle connected test JUnit XML 汇总；API 29 汇总四个 class-filter shard。构建契约来自 `scripts/verify-build-contract.ps1` 输出。完整阶段状态见[API/异形屏/真机验收计划](../plans/2026-10-01-private-export-ocr-backup-device-implementation.md)。

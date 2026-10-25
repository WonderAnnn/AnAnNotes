# 2026-10-26 API 29/35/36 全量矩阵刷新记录

执行日期：2026-10-26（对比基线：[2026-10-04 全量回归](./2026-10-04-api29-35-36-full-suite-verification.md)；对应交接指南第 8 节第 4 步）

三个 AVD 均以 `-no-snapshot-load -no-snapshot-save -no-boot-anim` 冷启动、逐个执行、完成后关机；结束时 ADB 设备列表为空。全套件经 `:app:connectedDebugAndroidTest` 执行；阶段 fixture 经 `adb install` + `am instrument` 直跑（注意：Gradle connected 任务结束会自动卸载应用并删除应用外部目录，证据需先拉取；本轮教训：连续多设备运行时 `androidTest-results/connected/debug` 会被后一轮覆盖，各轮日志已保存至 `D:\anan\outputs\AnAnNotes-baseline-20261025-logs\api{29,35,36}-full-suite.log`）。

## 结果摘要

| API | 套件计数 | 通过 | 跳过 | 失败 | 备注 |
|---|---:|---:|---:|---:|---|
| 29 | 231 | 225 | 6 | 6 | 5 个阶段 fixture + 1 个 cutout 按设计跳过；6 失败全部定性见下 |
| 35 | 233 | 228 | 5 | **0** | 5 个阶段 fixture 按设计跳过；全绿（14m09s） |
| 36 | 233 | 231 | 5 | 2 | 2 个编辑器 UI 时序用例，见下（16m33s） |

套件总量从 2026-10-04 的 199 项增长到 233 项（10-04 之后新增 OCR 语料等测试，含本轮新增的 `SkinExportFidelityEvidenceTest`）。API 29 计数比 35/36 少 2：该轮原始 XML 已被后继运行覆盖无法逐项比对，仅存控制台日志；结合当轮我方证据测试曾在 API 29 以 `NoSuchMethodError` 崩溃一次，怀疑与 instrumentation 进程重启的计数方式有关，留待下次矩阵刷新核对。

## 失败项定性（全部经隔离复测）

### API 29（6 项）

| 用例 | 定性 | 证据 |
|---|---|---|
| `SkinExportFidelityEvidenceTest#generateSkinExportFidelityMatrix` | **我方测试 bug，已修复** | `BitmapRegionDecoder.newInstance` 在 API 29 系统镜像上抛 `NoSuchMethodError`（byte[] 与 InputStream 重载均如此）；改为 `inSampleSize=2` 整图解码后裁剪，修复后在 API 29 复跑 OK（1/1） |
| `EditorScreenTest#headingToolChangesBlockPresentationWithoutChangingNoteTitle` | 时序敏感，非缺陷 | 隔离复测通过 |
| `EditorScreenTest#imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard` | 时序敏感，非缺陷 | 隔离复测通过；该用例在历史记录（2026-10-04/08）中即属偶发失败名单 |
| `TesseractOcrEngineIntegrationTest#verySmallCompressedTextDoesNotLoseMostOfItsContent` | **真实发现：OCR 质量预算在 API 29 不成立** | 两次执行 CER 完全一致（0.1429），确定性失败；该测试为 10-04 后新增，此前未在 API 29 上运行过 |
| `TesseractOcrSyntheticCorpusTest#defaultEngineMeetsLabeledMixedLayoutQualityBudgets` | **真实发现：同上** | `sparse-status-noise` CER=0.1892 > 0.12，两次一致；疑因 Android 10 的文字光栅化与调参基准（API 35 级）不同 |
| `PrivateExportDocumentsUiRoundTripTest#choosingSafFolderLocks...` | **测试兼容性缺口，非应用缺陷** | 确定性失败：测试要求 DocumentsUI 提供位置抽屉，Android 10 旧版 DocumentsUI 无该控件；应用 SAF 能力已由本轮 API 29 fixture 链 5/5 证明 |

### API 36（2 项）

| 用例 | 定性 | 证据 |
|---|---|---|
| `EditorScreenTest#editorCopiesTheCurrentTitleAndBodyDirectlyToSystemClipboard` | 时序敏感（剪贴板），非缺陷 | 隔离复测通过 |
| `EditorScreenTest#imageWidthPanelKeepsPercentEntryAndActionsAccessibleInLandscapeWithKeyboard` | 时序敏感、约五成复现率，非缺陷 | 全量败 → 隔离败 → 再隔离过（未改任何设置）；与交接指南 P2「自动化稳定性」一致 |

## 阶段 fixture（am instrument 直跑）

| Fixture | API 29 | API 35 | API 36 |
|---|---|---|---|
| 冷启动恢复 seed → force-stop → verify | 1/1 + 1/1 | 1/1 + 1/1 | 1/1 + 1/1 |
| SAF 链：RT seed → DocumentsUI seed（真实备份）→ pm clear → DocumentsUI verify（恢复+新私密密码）→ RT verify（8×8 像素校验） | **5/5** | RT seed ✔ 后 UI seed 两次确定性失败，链中止 | 未执行（预期与 35 同类问题） |
| cutout 专项（`tall` overlay） | 1/1，overlay 已恢复禁用 | 不适用（35/36 有切口，套件内执行） | 不适用 |

API 35 UI seed 失败定性：**测试自动化不适配新版 DocumentsUI 流程**（选目录后停留于 DocumentsUI 的 SAVE HERE 状态、10 秒未返回应用，截图确认）。历史记录显示该 fixture 此前只在 MuMu/API 32 与 API 29 执行过，API 35/36 从未单独跑过。应用侧备份/恢复功能本身由 API 29 全链 5/5 与 API 35 套件内其余 SAF/备份用例（全部通过）覆盖。后续需为不同 DocumentsUI 版本适配测试（API 29 无抽屉、API 35 有 SAVE HERE 确认流）。

## 与 2026-10-04 基线对比

- API 35：维持全绿；套件扩大 34 项后仍 0 失败。
- API 36：从 0 失败变为 2 失败，均为历史名单内的时序敏感用例（隔离可过或不稳定复现），未见新增确定性缺陷；不能据此声称"全绿"，维持 P2 稳定性问题定性。
- API 29：新增两项**确定性**发现——OCR 质量预算（2 项）与 DocumentsUI 测试适配（1 项）；另有 2 项时序敏感与我方测试 bug 1 项（已修）。
- 本轮修复：`SkinExportFidelityEvidenceTest` 兼容 minSdk 29；新增 `scripts/parse-instrumentation-xml.ps1` 用于解析 Gradle 仪器测试 XML。

## 待办（新增）

1. OCR 质量预算的 API 29 失败需要决策：按 API 分级预算阈值，或调查 Android 10 文字光栅化差异后调整合成语料（真实素材基准见 [OCR 回归记录](./2026-10-05-ocr-quality-regression.md)，同样未覆盖 API 29）。
2. `PrivateExportDocumentsUiRoundTripTest`（API 29）与 `PrivateSafBackupRestoreDocumentsUiTest`（API 35/36）的 DocumentsUI 版本适配。
3. 下次矩阵刷新时核对 API 29 与 35/36 的 2 项计数差，并保留各轮 XML（避免结果目录覆盖）。

## 边界

- 本轮全部在模拟器完成；小米真机、QQ/微信真实分享、第三方 DocumentsProvider 验收仍待执行（交接指南 P1）。
- 未修改生产代码；测试代码仅修复证据测试自身的 API 29 兼容性。

# PNG 审查修复报告（I1 / I2 / M1）

日期：2026-09-28；收尾说明更新：2026-09-29。修复已完成，最终状态与绿色证据见本文末；I3 由主线处理，不在本次写集中。[定向复审](2026-09-28-export-png-re-review.md) 已确认 I1/I2/I3/M1 均关闭。下方保留红/绿调查过程，不应把早期执行中快照当作当前结论。

## 早期写集快照（最终状态见文末）

- `env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt`：仅先将 `renderPage` 开放为 `internal`，供小尺寸真实编码对照；尚未应用功能修复。
- `env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt`：已落盘，中文、粗体、引用、完成待办删除线的独立整页 Canvas 像素对照；180 条来源行跨页连续区间；128 行边缘分隔线真实像素断言。
- `env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt`：已落盘，在第二页真实 Writer 输出完整 IDAT（包含 CRC）后取消实际 Job，使用真实 renderer / Writer / MediaStore store / Service / Room 历史仓库；仅替换目的文档访问层。
- 未改历史、存储、导航、主页、Service、Writer 或 I3 测试；不创建仓库、不派子代理。

## 定向验证记录

工作目录：`D:/anan/env-check`。命令进程设置 `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`、`ANDROID_SERIAL=emulator-5554`；未清空用户数据或修改设备配置。

首次基线 / RED（session `12257`，已结束，exit 1，Gradle 2m）：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.feature.export.AndroidRasterPngRenderingIntegrityTest,com.anan.anannotes.feature.export.RasterPngCancellationChainTest' --console=plain
```

XML：`env-check/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 12-_app-.xml`；timestamp `2026-09-28T15:07:19`，4 tests / 3 failures / 0 errors / 0 skipped，7.568s。Android 12，全部新测试正常编译并实际执行。

- M1 **真实功能 RED**：`dividerCenteredJustBelowBandEdgeKeepsItsUpperStrokePixels`，像素 `(128,126)` 应为 `-2499103`，实际为背景 `-526087`。分隔线中心在像素 `128.5`，8 倍缩放后上半描边应进入前一带，当前中心坐标筛选漏绘。
- I1：180 条来源行的连续区间及逐页/逐行像素对照基线通过。格式文字整图对照首个差异位于完成复选框圆角边缘 `(91,383)`，`expected=-4932154 / actual=-4406068`，尚在区分栅格化裁剪与文字功能错误。
- I2：真实 Job 已取消；首完整页保留，第二 pending 未发布并删除，输出均关闭。后续解压进度断言失败，尚在调查首次 IDAT 是否只有 zlib 头；不将其直接归为生产取消 bug。

编译/测试缺陷与实际功能失败分别记录；不将缺 import 视为功能 RED。

第二轮（session `60526`，同首次命令，exit 1，Gradle 40s）：XML timestamp `2026-09-28T15:10:39`，4 tests / 1 failure / 0 errors / 0 skipped，5.308s。

- M1 改为 `centerY` + 上下半描边 ink bounds，另保留一设备像素 AA 筛选余量；M1 真实像素对照通过。
- I2 修正测试触发条件：首个完整 IDAT 可能只含 2 字节 zlib 头，没有可解压像素；仅在第二页含压缩数据的完整 IDAT（length > 2，CRC 已验证）后取消实际 Job。完整取消、部分输出解压、成功事件/真实 Room 历史保留、下一批次非 Busy 均通过。该项首轮进度断言是测试条件缺陷，不是生产取消失败；没有修改 CancellationContext / Service / store / Writer。
- I1 精确文字与引用区域均通过；整图有 4 个差异像素，rows `[383,384,402,403]`，最大 RGB 通道差 8，位于完成 checkbox 圆角。保留零容差断言，开始验证物理 Canvas 边缘裁剪的 AA 影响：仍用 128 行 bitmap，每带编码 124 行，上下各 2 行绘制 halo；command 以扩展绘制范围筛选，读取核心 row `offset + 2`。格式夹具同步到新核心边界 `124/248/372/496`，不回避跨带文字。

## 当前交接状态（最终，取代上文执行中状态）

主线要求立即交接，已停止源文件改写与新增实验；不再启动 Gradle / ADB。最后已运行命令 session `33220` 正常结束，exit 0，`BUILD SUCCESSFUL in 40s`；当前无运行中命令。

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.feature.export.AndroidRasterPngExportRendererTest,com.anan.anannotes.feature.export.AndroidRasterPngRenderingIntegrityTest,com.anan.anannotes.feature.export.RasterPngCancellationChainTest' --console=plain
```

最新 XML：`D:/anan/env-check/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 12-_app-.xml`，timestamp `2026-09-28T15:24:03`，**10 tests / 0 failures / 0 errors / 0 skipped**，11.785s；原 renderer 6 项、新完整性 3 项、真实取消链 1 项全部 GREEN。

当前写集仅四个源码/测试文件及本报告：

- `AndroidRasterPngExportRenderer.kt`：`internal renderPage` 测试可见性；Divider 使用 `centerY` 绘制，筛选包含真实 ink bounds 与一设备像素 AA 余量。
- `RasterPngLayout.kt`：Divider 上下 bounds 为 `centerY ± strokeWidth/2`。分页 `first = end` 已恢复。
- 新 `AndroidRasterPngRenderingIntegrityTest.kt`：独立整页 Canvas 对照；中文/粗体/引用/完成待办删除线区域零容差，180 条来源行逐页、逐行像素和连续来源区间校验；M1 描边零容差。仅独立 checkbox-only 参考中明确的 AA 混合色边缘允许最多 6 个差异像素，RGB 通道差最多 9（浅色实测 8，深色实测 9）；背景、实色框体与全部文字仍精确匹配，不做全图模糊比较。
- 新 `RasterPngCancellationChainTest.kt`：第二页真实含压缩数据 IDAT（payload+CRC 已到目的存储）后实际 `Job.cancel`，真实 renderer / Writer / MediaStore store / Service / Room 历史链 GREEN；第一完整页/成功事件/历史保留，第二 pending 删除且不发布，无 failure，下一批次非 Busy。仅替换目的文档访问层；没有改 CancellationContext 或链上其他生产文件。

无效 halo 已完全撤回：生产 bitmap 和核心均恢复 128 行，原夹具跨 `128/256/384/512`。halo 轮仍 4 项中 1 项 AA 失败；独立 checkbox 小样的 drawLine、局部坐标、Path 三种方式均仅复现相同 4 个 AA 差异，其整页结果互相完全一致。因此这是平台分段平移栅格化精度的**测试比较约束修正**，不是虚构的生产内容 bug 修复。

敏感性证据：session `65996`，XML timestamp `2026-09-28T15:21:31`，3 tests / 2 failures / 0 errors / 0 skipped。临时丢一带触发 `(49,128)` 起 2,638 个正文像素差异；临时丢一行触发第 2 页来源区间 `expected 34 / actual 35`。两处突变均已撤回，最终 GREEN 对应恢复后的源码。此前 session `10563` 为 10 tests / 1 failure（仅深色 checkbox AA 差 9 超过当时阈值 8），修正上述局部阈值后得到本轮 10/10 GREEN。

实际生产 bug 为 M1，已由首轮真实像素 RED → bounds 修复 → GREEN 证明。I1 为覆盖补齐与 AA 比较约束修正；I2 首轮 2 字节 zlib 头触发的进度断言属于测试条件错误，真实取消链最终通过。未编辑 I3 / 历史 / 存储 / 导航 / 主页 / Service / Writer；无整页 A4 bitmap、无清空用户数据或设备配置修改。后续小修与全量验证交由主线。

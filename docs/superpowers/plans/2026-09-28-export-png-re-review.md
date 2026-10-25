# PNG 定向复审：I1 / I2 / I3 / M1

日期：2026-09-28；代码 / XML 核对快照 23:31（Asia/Shanghai）。依据[原 PNG 报告](D:/anan/docs/superpowers/plans/2026-09-28-export-png-review-report.md)及[最新修复交接的“最终”状态](D:/anan/docs/superpowers/plans/2026-09-28-export-png-fix-report.md)，不采用交接上半部分已被取代的执行中状态。

## 结论

**I1、I2、I3、M1 均 ADDRESSED。** 原报告的定向阻塞项已关闭；本次修复范围规格符合，质量通过定向复审。未发现修复引入的具体新问题：Critical 0 / Important 0 / Minor 0。此结论不是整个模块或项目的重新验收。

## 当前文件 manifest

无 Git 元数据，按当前文件及明确修复边界核对，不伪造 diff / SHA。

| 文件 | 本轮边界 | 字节数 |
| --- | --- | ---: |
| [AndroidRasterPngExportRenderer.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt:46) | internal renderPage、Divider 筛选 / 绘制、128 行画带及 halo 撤回 | 7736 |
| [RasterPngLayout.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/RasterPngLayout.kt:43) | Divider centerY / stroke bounds；分页突变恢复 | 10303 |
| [AndroidRasterPngRenderingIntegrityTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt:49) | 新增 3 个完整性用例与独立参考 / 比较器 | 16557 |
| [RasterPngCancellationChainTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt:42) | 新增真实取消链用例及目的文档夹具 | 12842 |
| [HomeScreen.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/home/HomeScreen.kt:295) | 仅格式 LazyColumn 的新增 testTag | 18491 |
| [ExportFlowTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/ui/ExportFlowTest.kt:52) | 仅原 I3 格式入口用例的屏外节点定位 | 8974 |

## 逐项核实

| 原项 | 状态 | 具体代码与验证 |
| --- | --- | --- |
| I1：跨带文字 / 分页完整性覆盖不足 | **ADDRESSED** | [完整性测试:49](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt:49) 将中文粗体、引用、完成 / 未完成待办放到 128/256/384/512 边界附近；[独立整页参考:169](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt:169) 自行构造样式和 Canvas 绘制，不调用 renderer helper。文字 / 删除线 / 引用线区域严格逐像素比较，并验证去掉粗体 / 删除线会改变参考。[分页测试:83](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt:83) 对 180 个来源行校验逐页 / 逐行像素、连续字符区间、无漏行 / 重复及最终完整序列。最新 XML 中两项均通过。 |
| I2：没有真实 Job → renderer / Writer / store / history 取消链 | **ADDRESSED** | [取消测试:43](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt:43) 使用实际 renderer、Writer、MediaStore store、Service 与内存 Room 历史仓库；目的文档访问层替代，不注入取消异常。[实际 Job.cancel:70](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt:70) 由第二页完整且含数据的 IDAT 触发；[触发条件:168](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt:168) 排除仅 2 字节 zlib 头，[CRC 检查:212](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt:212) 确保 payload + CRC 已到目的流。[断言:84](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/RasterPngCancellationChainTest.kt:84) 验证真取消、第一完整页保留、第二 pending 删除且不发布、流关闭、第二页可解压但未完成 / 无 IEND、真实成功回执和历史保留、无假失败；下一批次成功且非 Busy。最新 XML 通过。 |
| I3：屏外 TXT 节点定位导致入口验收失败 | **ADDRESSED** | [HomeScreen:297](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/home/HomeScreen.kt:297) 给实际 LazyColumn 添加 `home-export-format-list`；[ExportFlowTest:64](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/ui/ExportFlowTest.kt:64) 从列表调用 `performScrollToNode(hasTestTag(tag))`，随后断言目标显示，不再先定位尚未组合的子节点。PDF 与全部 PNG / TXT / Markdown ZIP 断言仍在；最新 XML 中原失败用例已通过。 |
| M1：Divider 上半描边被画带筛选遗漏 | **ADDRESSED** | [Layout:43](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/RasterPngLayout.kt:43) 明确 centerY，top / bottom 为中心 ± 半描边；[renderer:74](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt:74) 以 ink bounds 加 `1 / scale` 逻辑单位（一设备像素）的 AA fringe 筛选，[renderer:95](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt:95) 仍在中心绘制同宽描边。[回归:132](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt:132) 用 8 倍缩放、128.5 px 中心验证前带像素 `(128,126)` 及整条线零容差一致。最新 XML 通过。 |

### AA 精度及突变证据

[比较器:242](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngRenderingIntegrityTest.kt:242) 不是全图放宽：每种皮肤最多 6 个差异像素、最大 RGB 单通道差 9；差异必须属于独立 checkbox-only 参考的混合色 AA 边缘，且完整参考该点等于 checkbox 参考。背景、实色框体、正文 / 删除线与引用线仍精确比较。交接记录实测只有 4 个圆角边缘差异，浅色最大 8、深色最大 9；没有把平台边缘舍入当正文丢失，也未发现该局部精度边界违反保留正文 / 格式的计划契约。

交接记录提供 session 65996 的突变失败：丢一带出现从 `(49,128)` 起的 2,638 个正文差异像素；漏一行触发第二页来源区间 `expected 34 / actual 35`。M1 首轮记录 `(128,126)` 应有描边但实际为背景的 RED。上述 RED / 突变细节来自交接，不是本复审执行；当前 [分页推进:123](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/RasterPngLayout.kt:123) 已恢复 `first = end`，[画带循环:64](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt:64) 连续编码全部 count 行并推进 start，无跳带 / halo 残留。bitmap 与 BAND_HEIGHT 均保持 128 行。

## 读取到的运行证据与限制

交接记录 session 33220 为定向 10/10 GREEN。本复审独立读取[当前 Android XML](<D:/anan/env-check/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 12-_app-.xml>)，timestamp 原文 `2026-09-28T15:30:51`：原 renderer 6 项、完整性 3 项、真实取消链 1 项及原 I3 用例，所读取的 11 个相关回执均无 failure / error / skipped。

主线随后提供最终验收摘要：session 89710 的 `testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug` 完整命令 exit 0，BUILD SUCCESSFUL（3m20s）；JVM 170/170、Android 125/125，均 0 failure / error / skip。最终全量数字由主线独立读取提供，本复审未重跑或重新汇总；本轮相关 XML 与该摘要一致。

未运行 Gradle / ADB 或测试，未改源码 / 测试，未派代理。没有重新通读已审 Writer / Service，也未重复历史 / 存储、旧模块或 Insets 审查。取消测试证明真实生产链的取消 / 清理控制流，但目的文档层为夹具，不冒充真实系统 provider 或实体设备验证。唯一写入文件为本报告。

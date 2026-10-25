# 高清 PNG 与导出服务任务范围审查

审查日期：2026-09-28（Asia/Shanghai）；下列运行证据读取截止 22:45:09。依据先完整读取的 [范围清单](D:/anan/docs/superpowers/plans/2026-09-28-export-png-review-scope.md)，对照 [实施方针](D:/anan/docs/superpowers/plans/2026-09-28-anan-notes-export-storage-guideline.md) 第 1–3 节、步骤 1–3 及对应 JVM/Android 导出测试边界。主线持续写入和验证，结论对应本报告描述的文件与证据快照。

## 结论

- **规格：主要生产实现符合已核对的静态契约与新增设备断言，完整验收尚未符合。** 真正 A4 像素与 pHYs、流式 RGB 编码、内存约束、单长图预检、MediaStore 路由和非致命历史错误隔离均已接入；跨带文字完整性与实际取消链的验收仍不足，格式入口测试仍失败。
- **质量：暂不通过。** Critical 0、Important 3、Minor 1。I1/I2 为具体测试缺口，不代表已经复现正文丢失或取消残留；I3 为已有 XML 证实的入口验收失败；Minor 为可从代码推导的描边裁剪问题。应补齐 Important 并复验后再验收。

## 实际审查范围与边界

没有 Git 元数据；本次依据清单审阅当前文件，不声称审阅过提交、diff 或 BASE/HEAD SHA，也不推断清单外的改动归属。

生产源码根目录：`D:/anan/env-check/app/src/main/java/com/anan/anannotes/`。测试根目录分别为同工程 `src/test/java/com/anan/anannotes/` 与 `src/androidTest/java/com/anan/anannotes/`。

| 清单类别 | 已审阅文件/区域 |
| --- | --- |
| 新增生产文件 | `feature/export/StreamingPngWriter.kt`、`RasterPngLayout.kt`、`AndroidRasterPngExportRenderer.kt`、`ExportHistoryRecorder.kt` |
| 修改生产文件 | `feature/export/ExportService.kt`、`ExportFeedback.kt`；`feature/home/HomeViewModel.kt` 的导出依赖与收集链、`HomeScreen.kt` 的格式面板与导出事件接线 |
| 新增测试 | JVM `feature/export/StreamingPngWriterTest.kt`；Android `feature/export/AndroidRasterPngExportRendererTest.kt` |
| 修改测试 | JVM `feature/export/ExportServiceTest.kt`、`ExportFeedbackTest.kt`；Android `ui/ExportFlowTest.kt` |
| 必要依赖核对 | `ExportLayout` 的尺寸、采样和命名；既有 PDF/社交 renderer 的接口、排版及资源相关片段；SAF/MediaStore FileStore 的写入、发布、异常与清理契约及有关测试片段 |

未审查历史/存储页面、历史仓库实现、导航整理、全面屏适配或其他产品模块。Storage 测试 XML 仅用于核对范围包给出的已有数量，不作为本次仓库审查结论。

审查期间主线继续写入文件；已重新读取 `ExportServiceTest.kt`（最后写入 22:36:04，现 15 项测试）及 `AndroidRasterPngExportRendererTest.kt`（22:39:13，现 6 项测试），并复核 22:42:44 更新后的 HomeViewModel/HomeScreen 导出区域、22:43:33 更新后的 ExportFlowTest。本报告引用这些版本的行号，不将主线新增代码或测试算成本审查的修改。

## 主要契约核对

| 契约 | 静态代码/已有证据结论 |
| --- | --- |
| 真正 4961 × 7016、23622 px/m | `ExportLayout.kt:40` 向上取整；`RasterPngLayout.kt:74`、`:97` 使用实际像素；`StreamingPngWriter.kt:42`、`:47` 写 IHDR 与 pHYs。新 Android XML 已含尺寸与 pHYs 用例成功回执。 |
| RGB、CRC、zlib、严格行数、调用者流所有权 | Writer 使用 RGB8/Sub、32 KiB 有界 IDAT、逐块 CRC；finish 验证完整行数后结束 zlib、写 IEND，close 仅释放 zlib。已有 5 项 JVM XML 对应 RGB 解压、CRC、行边界、输出上限及流所有权。 |
| 带状内存、图片解码有界 | `AndroidRasterPngExportRenderer.kt:48` 仅分配宽 × 128 ARGB 画带；保留一张采样原图，`:114`–`:118` 限制边长和估算像素数至 8192 / 900 万，正常异常路径回收资源。未创建整页或整长图位图。 |
| 排版、跨带内容 | A4 使用 PDF 相近字号/页边距，按 StaticLayout 行分页；图片依原图 header 比例、百分比和对齐布局，超高 A4 图片等比缩小。画带通过坐标变换与裁剪重绘文本/图片。已有跨带图片连续像素断言；跨带文字完整性断言不足（I1）。 |
| 单长图、超限预检、文件上限 | 固定宽 1080、布局阶段检查高度 32768 并报 AN-EXP-004；完整布局在首次 writePage 之前完成。长图计划仅一页，服务拒绝第二回执；无切片调用。Writer 上限 128 MiB，超限异常传到 FileStore 清理当前未发布项。长图不写虚构 pHYs。 |
| 后台计算与 Flow 上下文 | 预检在 Default；writePage 回到原 Flow 上下文。HomeViewModel `:211` 以 flowOn(IO) 执行服务；实际 MediaStore/SAF content 回调也在 IO，绘制编码不占 UI 主线程。 |
| 路由、命名与部分成功 | 四种 PNG 走 MediaStore，其他格式继续走 SAF；A4 三位页号、长图单文件名称正确。每次成功回执立即进入成功列表，后续失败不删除先前成功文件。 |
| 历史错误隔离 | `ExportService.kt:168` 捕获普通历史异常并计入 historyFailures；成功项使用 FileStore 的真实名称/URI。取消继续传播且不记录假失败；普通历史异常不把已发布文件误报写入失败，Feedback 保留成功摘要并追加记录警告。真实取消链仍缺验收（I2）。 |
| 普通/私密边界 | 当前接线使用普通数据库与 OriginalImageStore；新增 renderer/历史接口未引入私密入口或额外权限。本次不对范围外私密模块和历史仓库内部作验收。 |

## 已有测试证据与限制

本审查没有运行 Gradle、ADB、构建、测试或设备命令。下表是读取已有 XML 的核对结果，不是本审查的新执行结果。

XML 目录：`D:/anan/env-check/app/build/test-results/testDebugUnitTest/`；文件名均为 `TEST-<下列完整类名>.xml`。

| XML 类名 | tests | failures / errors / skipped | XML timestamp（UTC） |
| --- | ---: | --- | --- |
| `com.anan.anannotes.feature.export.StreamingPngWriterTest` | 5 | 0 / 0 / 0 | 2026-09-28T14:43:04.487Z |
| `com.anan.anannotes.feature.export.ExportServiceTest` | 15 | 0 / 0 / 0 | 2026-09-28T14:43:04.359Z |
| `com.anan.anannotes.feature.export.ExportFeedbackTest` | 3 | 0 / 0 / 0 | 2026-09-28T14:43:04.298Z |
| `com.anan.anannotes.data.repository.StorageOverviewRepositoryTest` | 7 | 0 / 0 / 0 | 2026-09-28T14:43:03.479Z |
| 合计 | 30 | 0 / 0 / 0 | 主线新运行记录 |

起初读取的范围包数字 29 与 14:28 的 XML 一致。收尾时主线新 XML 已更新为上述 30 项，包含 `cancellationDoesNotRecordFalseFailureAndReleasesTheBatchLock` 成功回执。该成功证明测试自身断言，未消除 I2 指出的实际取消链覆盖缺口。

收尾读取的 [Android XML](<D:/anan/env-check/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 12-_app-.xml>) timestamp 为 `2026-09-28T14:44:32`，共 37 项、1 failure、0 errors、0 skipped。范围内 `AndroidRasterPngExportRendererTest` 为 6/6；`ExportFlowTest` 为 5/6，其中 A4 发布/真实尺寸/成功历史、长图单文件与既有方图/竖图用例均有成功回执。唯一失败是 `selectedNotesExposeTextAndMarkdownZipExportChoices` 在 `ExportFlowTest.kt:63` 找不到 `export-format-txt`（见 I3）。这不是本轮设备 GREEN；不将模拟器回执称为实体小米验证。

此前 13:37 的旧 91 项 XML 未覆盖新增 raster/A4/长图用例，已被主线新运行替换；上文采用新结果。范围包所述历史页面测试类阻塞不再作为当前状态结论，本审查仍未执行构建或设备命令，也未扩大到页面工作。

## 问题清单

### Critical

无。本范围内未发现已证实的 Critical 缺陷。

### Important

1. **I1：跨带文字和分页完整性的渲染断言不足。** File: [AndroidRasterPngExportRendererTest.kt:71](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRendererTest.kt:71)，相关 `:77`、`:89`、`:152`。

   分页与长图测试主要检查尺寸、页序和“任意非背景像素”。一页只剩部分正文也能通过；第一页只绘出标题仍能满足该像素条件。新增 `:33` 的格式测试验证布局命令与 span，未验证它们穿过画带后实际保留的字形、引用线、复选框和完成删除线。图片连续性测试不能替代文字边界测试。方针明确要求跨带内容/正文完整保留，这些断言无法拦住跨 128 行边界的文字缺行、重复或截断。

   建议：补可定位正文区域的跨带渲染对照或逐行完整性断言，覆盖跨带中文/格式文字与跨页行序；让删除一带正文或漏一行的实现确实使测试失败。保留现有布局与图片测试。

2. **I2：取消验收未经过实际 renderer → Writer → MediaStore 清理链。** File: [ExportServiceTest.kt:101](D:/anan/env-check/app/src/test/java/com/anan/anannotes/feature/export/ExportServiceTest.kt:101)，相关 `:106`；[AndroidRasterPngExportRendererTest.kt:133](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRendererTest.kt:133)。

   新 Service 用例在假 FileStore 调用 content 前直接抛 CancellationException，正确验证了假失败不记录和锁释放，但没有取消运行中的 Job、写出部分 IDAT、创建待发布项或验证删除。Renderer 测试直接写 ByteArrayOutputStream，也没有取消场景。既有 FileStore 的 IOException 清理测试无法证明新 renderer 捕获的上下文检查会在真实取消时穿透到相同清理路径，更未覆盖前一页已发布、后一页取消的组合。

   建议：由主线补实际 Job 取消的组合测试，在后一页画带写入中触发取消，断言当前 pending 项未发布且被删除、先前完整页面/真实回执保留、没有失败历史项、后续批次可运行；记录对应运行证据。本审查不实施或运行该测试。

3. **I3：格式入口设备验收实际失败。** File: [ExportFlowTest.kt:63](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/ui/ExportFlowTest.kt:63)，相关 [HomeScreen.kt:295](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/home/HomeScreen.kt:295)。

   新 Android XML 明确记录 `Action performScrollTo() failed`，原因是找不到唯一的 `TestTag = 'export-format-txt'` 节点。当前测试先找子节点再滚动；格式面板采用 LazyColumn，尚未组合的屏外项不能保证已在语义树中。结合代码，这是定位方式的合理原因推断，不能仅据该异常认定产品已删除 TXT 按钮。无论原因，所要求的全部格式入口验收当前没有通过。

   建议：使测试通过 LazyColumn 的滚动语义找到尚未组合的目标，再验证其显示和操作；保留 TXT/Markdown ZIP 入口断言并由主线复验，不能仅删除失败断言。

### Minor

1. **M1：分隔线的画带筛选范围遗漏向上延伸的描边。** File: [AndroidRasterPngExportRenderer.kt:74](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt:74)，相关 [RasterPngLayout.kt:43](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/RasterPngLayout.kt:43) 与 renderer `:92`–`:93`。

   Divider 的 top 是线的中心坐标，绘制却使用 1 个逻辑单位的居中描边。若中心位于画带末端之后、距离不足半个线宽，筛选因 `command.top > bottom` 跳过前一带；实际本应进入前一带的上半描边被画带裁掉。A4 缩放后半个逻辑单位约为 4.17 px，可能出现线厚不一致。这是静态条件推导，未声称设备复现。

   建议：筛选使用包含半个线宽及抗锯齿余量的绘制边界，分别保留分隔线中心与实际 bounds；添加贴近 128 行边界的像素断言。该问题不破坏 PNG 合法性、文件数量或已发布文件。

## 本次写入

仅创建本报告。未修改生产/测试代码或其他文档，未派子代理，未运行 Gradle/ADB，未创建 Git 元数据，未重复主线历史页面与文档整理工作。

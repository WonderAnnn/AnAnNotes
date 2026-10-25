# 只读审查包：高清 PNG 与导出服务

无 Git 元数据，无法提供真实 BASE/HEAD 或 git diff。以下列出本轮新增/修改的具体范围，请读当前代码进行任务范围审查，不能声称审阅了不存在的提交。

需求：本轮 `2026-09-28-anan-notes-export-storage-guideline.md` 的第 1–3 节、步骤 1–3、JVM/Android 导出测试边界。

新增：

- `D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/StreamingPngWriter.kt`
- `D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/RasterPngLayout.kt`
- `D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRenderer.kt`
- `D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/export/ExportHistoryRecorder.kt`
- `D:/anan/env-check/app/src/test/java/com/anan/anannotes/feature/export/StreamingPngWriterTest.kt`
- `D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/export/AndroidRasterPngExportRendererTest.kt`

修改：ExportService.kt（新增 A4/长图路由和按回执历史记录、historyFailures），ExportFeedback.kt（非致命记录警告），HomeViewModel.kt（接 renderer/普通记录、flowOn IO），HomeScreen.kt（新增格式按钮），ExportServiceTest.kt（新增 5 测试），ExportFeedbackTest.kt（新增警告测试），ExportFlowTest.kt（新增 A4/长图端到端和入口断言）。全部位于上述工程 src 同名目录。

既有且不改：AndroidPdfExportRenderer、AndroidSocialPngExportRenderer、ExportLayout、SAF 与 MediaStore FileStore。必要时读它们确认接口/资源/取消契约，不扩大审查到其他产品模块。

已运行证据：JVM Writer 5/5、Service 14/14、Feedback 3/3、Storage 7/7 定向通过（总计 29，需以报告 XML 最终核对）；Android 编译阶段已验证新 raster 主代码可编译，但设备运行因正在实现的历史页面测试类缺失尚未完成，不能认为设备 GREEN。

审查重点：真 4961×7016 与 pHYs、RGB/CRC/zlib 完整性、带状内存和图片解码有界、跨带文字/图片、单长图不得静默切片、超限预检、取消清理与已发布部分成功、Flow 上下文/后台计算、历史写入错误不得把已经发布的文件误报失败。输出规格与质量两个结论、Critical/Important/Minor 具体 file:line 与原因。

审查不运行 Gradle/ADB、不修改生产/测试文件、不派子代理。报告只写 `D:/anan/docs/superpowers/plans/2026-09-28-export-png-review-report.md`，回传简短结论。存储/历史页面及全面屏完整适配不是此次任务审查范围。

# 高清导出、记录与存储概览：验证记录

日期：2026-09-28；收尾核对：2026-09-29。范围为普通笔记；状态：本轮增量已完成回归，未宣称完整 V1 完成。

工程：`D:/anan/env-check`。依据 [本轮方针](../plans/2026-09-28-anan-notes-export-storage-guideline.md)。后续按 [全面屏与异形屏方针](../plans/2026-09-28-anan-notes-edge-to-edge-guideline.md) 的 E1 执行包开始，不把下一阶段接口当作现有实现。

## 1. 可操作入口

- 首页长按笔记卡片进入选择状态 → 导出 → A4 分页 PNG、单张长图、朋友圈方图/竖图、PDF、TXT 或 Markdown ZIP。
- 四种 PNG 写入 `Pictures/AnAn Notes/Exports/`，完成后通过 MediaStore 发布；其他格式使用系统目录选择器并记住授权位置。
- 设置 → 数据与存储 → 导出记录：显示最近 100 条普通记录，可打开/分享成功文件；清空仅删记录。
- 设置 → 数据与存储 → 存储概览：普通数据库/原图/预览缓存与外部导出分开统计；清理仅针对预览缓存。
- 空间不足提示中的“查看存储”跳到存储概览；普通记录写入失败不会把已经导出的文件误报为失败。

## 2. 设备与命令

本轮自动化设备为 MuMu `emulator-5554`、Android 12/API 32（ADB model `NTH_AN00`、device/product `Nottingham`）。当时物理分辨率 900×1600、density 240，现场窗口为横屏 1600×900；这不是完整横竖屏/异形屏矩阵验收。没有把模拟器测试记为小米 11 Pro 实机验收。工作区没有 Git 元数据，未生成提交或伪造 Git SHA 差异。

9 月 28 日最终命令从工程目录运行；仅在当前命令进程设置 Java 和设备环境变量：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug --console=plain
.\scripts\verify-build-contract.ps1
```

测试结果从 `app/build/test-results/testDebugUnitTest/TEST-*.xml`、`app/build/outputs/androidTest-results/connected/debug/TEST-*.xml` 读取；Lint 从 `app/build/reports/lint-results-debug.xml` 读取；构建退出码和脚本输出共同作为证据，不以文件存在替代构建成功。

9 月 29 日收尾时 `adb devices -l` 没有已连接设备，因此没有重新运行 connected 测试或安装到手机。当前源码不变，运行以下本机检查，并单独强制重跑 JVM 测试，区分 Gradle 的 UP-TO-DATE 与实际执行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
.\scripts\verify-build-contract.ps1
.\gradlew.bat :app:testDebugUnitTest --rerun --console=plain
```

## 3. 本轮最终结果

| 检查 | 结果 | 时间/证据 |
| --- | --- | --- |
| 全量 Gradle | exit 0；BUILD SUCCESSFUL，3 分 20 秒 | 9 月 28 日主线完整运行，session 89710 |
| JVM | 170 tests、0 failure、0 error、0 skipped；28 suites | 9 月 29 日 `--rerun` 实际执行，exit 0，8 秒；重新汇总 XML |
| Android instrumentation | 125 tests、0 failure、0 error、0 skipped | [MuMu XML](<D:/anan/env-check/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 12-_app-.xml>)；timestamp `2026-09-28T15:30:51` UTC |
| Lint | 0 Error、13 Warning、2 Hint | [Lint XML](D:/anan/env-check/app/build/reports/lint-results-debug.xml) |
| 本机再次构建 | exit 0；BUILD SUCCESSFUL，21 秒 | 9 月 29 日：53 tasks，1 executed、52 up-to-date；不声称重新编译全部源码 |
| 构建契约 | applicationId、minSdk、无 INTERNET、应用名均 PASS | 9 月 28 日及 29 日脚本检查 |
| Debug APK | 41,215,925 字节，39.31 MiB；小于 300,000,000 字节 | 文件写入时间 `2026-09-28T23:22:20.7466952+08:00`；9 月 29 日哈希复核一致 |
| 安装/启动 | MuMu `install -r` 返回 Success；MainActivity 启动命令成功 | 9 月 28 日，未清空数据；没有本轮小米安装/验收证据 |

安装包：[app-debug.apk](D:/anan/env-check/app/build/outputs/apk/debug/app-debug.apk)。SHA-256：

```text
7EA118D49C6AD34D2FE8018B2A23DC073615662FE1CA2AEA77E8EBFDB9D44677
```

合并 Manifest 只有 AndroidX 自动生成的签名级 `com.anan.anannotes.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；没有 INTERNET、媒体全盘读取或广泛存储权限。保留 `allowBackup=false`。契约脚本只验证其既定四项，其他权限结论来自合并 Manifest 核对，不能扩大脚本能力。

Lint 警告分布：OldTargetApi 1、NewerVersionAvailable 3、ConfigurationScreenWidthHeight 1、ObsoleteSdkInt 1、UseKtx 7；Hint 为 AutoboxingStateCreation 2。目标/依赖升级需要单独兼容验证；窗口高度提示归入 E4 的实际窗口尺寸评估；其余低风险清理不在已验收增量中临时改动。构建还输出 JBR native-access 和 SDK XML 工具版本提示，未导致失败，列入工具链维护，不假装已消除。

## 4. 定向测试与缺陷处理

- PNG 编码验证真实 IHDR/pHYs、CRC/zlib 解压字节、完整行数、输出预算和调用者流所有权。
- Android 图像验证真正 A4 4961×7016、长图单文件及超限前拒绝、原图比例和宽度百分比、深浅皮肤。
- 历史仓库验证事务裁剪、同时间戳排序、重新打开持久化、清空/裁剪不删除文件；外部统计区分已知、未知和不可访问。
- 文件操作验证只读授权、MIME、缺失/无权/无接收应用反馈；页面验证入口、刷新、清空确认与返回。
- 首轮 37 项 Android 定向测试有 1 项失败：LazyColumn 尚未组合的 TXT 屏外项无法通过子节点 `performScrollTo()` 定位。主线改为通过带标签的列表 `performScrollToNode(hasTestTag(...))`，产品按钮并未缺失；该用例在最终 125 项全量运行中已通过。
- 新增跨画带中文/粗体/引用/完成待办的独立整页参考、180 个来源行的连续区间与逐行像素验证、分隔线真实描边回归，以及实际 Job → renderer → Writer → store → Room 历史的取消链测试。分隔线上半描边遗漏已修复；取消测试排除只有 zlib 头的首个 IDAT，不将测试触发条件问题误报为生产 bug。
- 正文、删除线、引用、实色区域严格逐像素比较；只有独立定位的 checkbox 圆角抗锯齿边缘允许每种皮肤最多 6 个差异像素、RGB 单通道差不超过 9，不把全文放宽。临时丢画带/漏行突变均能使测试失败，突变代码与无效 halo 试验已撤回。
- [修复报告](../plans/2026-09-28-export-png-fix-report.md) 记录 RED、GREEN 和突变证据；[PNG 定向复审](../plans/2026-09-28-export-png-re-review.md) 确认 I1/I2/I3/M1 均 ADDRESSED、没有具体新增问题；[历史/存储/导航审查](../plans/2026-09-28-export-storage-integration-review.md) 通过。复审范围有限，不是整个项目重新验收。

测试只清理各自创建的唯一测试笔记/图片/历史项，或使用临时数据库/目录和可控 provider；不得清空用户数据库，也不得删除原图或已有导出文件。

## 5. 保留边界

- 只交付普通导出/历史/统计，不包含私密密码验证导出或独立加密历史。
- 普通编辑页直接导出尚未接入；当前需回首页长按。下一步需先等待当前修订保存成功，不能导出旧快照。
- 长图上限为 1080×32768 px、单文件 128 MiB；超限明确失败并建议 A4 分页，不自动切片或截断。A4 预检最多 512 页、10000 块、100 万 UTF-16 文本长度。
- 真实 DocumentsProvider 的系统授权/改名/撤销权限、第三方接收应用实际打开/分享和小米系统相册/文件管理器仍需手工验收。
- API 29/35/36、异形屏/横屏/大字体/真实软键盘完整矩阵未验收；本轮新增两页安全区处理不等于 E1–E6 已完成。
- OCR、加密备份、合并恢复、回收站、完整双语资源、发布签名及性能/内存峰值测量仍按 V1 未完成清单推进。Debug APK 的体积不替代发布版验收。

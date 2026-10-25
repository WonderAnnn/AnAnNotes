# Android 开发环境阶段验证

日期：2026-10-01  
工程：`D:/anan/env-check`  
Android SDK：`F:/AndroidDev/Sdk`

## 已验证可用

- Android CLI：`1.0.16406183`
- Android Emulator：`37.1.11.0`
- SDK 平台：API 29（revision 5）、API 35（revision 2）、API 36（revision 2）；对应 `android.jar` 均存在。API 36 平台可用于当前 Debug 构建。
- Build Tools：`36.0.0`、`37.0.0`
- ADB 设备：MuMu `NTH-AN00`，Android 12 / API 32，序列号 `127.0.0.1:16384`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`，39,812,003 bytes。

## 测试结果

| 检查 | 结果 |
| --- | --- |
| `assembleDebug` | 成功 |
| `testDebugUnitTest --rerun-tasks` | 174 项，0 失败、0 错误、0 跳过 |
| `connectedDebugAndroidTest`（MuMu API 32） | 154 项，0 失败、0 错误、1 跳过 |
| `lintDebug` | 成功，0 错误；现有报告含 12 条警告、2 条提示 |
| `scripts/verify-build-contract.ps1` | Application ID、最低 SDK、无 INTERNET 权限、应用名均通过 |
| MuMu 安装与启动冒烟测试 | 安装成功；`MainActivity` 冷启动成功并保持前台，无 `AndroidRuntime` 崩溃记录 |

唯一跳过项为 `bulkActionButtonsStayClearOfLandscapeDisplayCutout`。该设备没有异形屏，无法验证刘海避让场景；不是测试失败。仪器测试 XML：`app/build/outputs/androidTest-results/connected/debug/TEST-NTH-AN00 - 12-_app-.xml`。

全量仪器测试结束后，测试 APK 仍在 MuMu 上，但正式应用 APK 未保留安装，因此紧接着用 `monkey` 检查时报告没有启动 Activity。已核对 Debug APK 内确有 `com.anan.anannotes.MainActivity` 启动入口；将该 Debug APK 重新安装后，`am start -W` 返回 `Status: ok`、`LaunchState: COLD`，前台 Activity 为 `com.anan.anannotes/.MainActivity`。这确认是测试后的设备安装状态，不是应用缺少启动入口。

## 尚未安装的组件

API 29、35、36 的 Google APIs x86_64 **系统镜像尚未安装**，因此本轮没有创建这些 API 的 AVD。对应 SDK 平台已安装，MuMu API 32 已完成设备回归测试。

API 29 镜像包下载长时间无进展（本地 `.part` 文件为 0 bytes），已安全中断。测试过的镜像路径未提供对应新版本：华为镜像的 API 29/35/36 目标地址返回 404；USTC、TUNA、腾讯云及阿里云候选路径对 API 35 返回 404。发现的 API 36 第三方临时文件大小为 1,924,689,206 bytes，而 Google 官方包为 1,895,447,397 bytes，因此未采用；没有安装任何未经校验的第三方包。

官方 SDK 清单中的目标 SHA-1（供后续下载逐包核对）：

| 镜像 | 官方 SHA-1 |
| --- | --- |
| API 29 `google_apis/x86_64` | `50c2953ab13f312ed797a961577ee74a911d087d` |
| API 35 `google_apis/x86_64` | `0103e6dab21290c4b9d16550a3ce99476f884eef` |
| API 36 `google_apis/x86_64` | `c6bf44bdcd885bb902b4ba752d111a073ad7a817` |

## 下一步

现有 MuMu 环境可继续承担 UI 与功能回归；需要 API 29/35/36 兼容矩阵时，需在 Google 下载链路恢复到可用速度后继续安装系统镜像，并在每个镜像完整下载后先核对上述 SHA-1，再交由 SDK 安装器处理。小米 11 Pro 本轮未出现在 `adb devices` 中，真机验证待设备重新连接后进行。

## 2026-10-03 AVD 与回归矩阵更新

API 29、35、36 的 Google APIs x86_64 镜像现已安装，AVD 已建立并可重复启动：

| AVD | API | ADB 端口 | 分辨率 / 密度 |
| --- | ---: | ---: | --- |
| `AnAn_API29_Pixel6` | 29 | 5560 | 1080×2400 / 420 dpi（约 411×914 dp） |
| `AnAn_API35_Pixel6` | 35 | 5562 | 1080×2400 / 420 dpi（约 411×914 dp） |
| `AnAn_API36_Pixel6` | 36 | 5564 | 1080×2400 / 420 dpi（约 411×914 dp） |

为稳定长时间 UI 回归，最终 API 36 全量使用冷启动、无窗口 SwiftShader 模式运行。结果：API 29 与 API 35 各自通过 5 项编辑页/输入法重点测试；API 36 的完整仪器测试 XML 为 192 项、188 通过、4 跳过、0 失败/错误。4 项跳过均为需要分阶段启动的冷启动恢复与私密 SAF fixture（seed/verify），不是测试失败；这些 fixture 需按专用两阶段流程另行执行。API 36 覆盖结果不等价于 API 29/35 全量兼容验收，也不代替真实异形屏/小米设备验收。

同阶段 `:app:testDebugUnitTest --rerun-tasks` 为 330/330 通过；`:app:lintDebug` 为 0 errors / 17 warnings / 2 hints；Debug APK 构建成功，大小 76,341,443 bytes。构建契约脚本确认 application ID、minSdk 29、无 `INTERNET` 权限及应用名均符合预期。Xiaomi 11 Pro 尚未做真机验收；异形屏、导航模式、字体缩放以及完整 API 29/35/36 功能矩阵仍是后续工作。

## 2026-10-04 API 29 / API 35 AVD 回归更新

本轮在 `AnAn_API29_Pixel6` 与 `AnAn_API35_Pixel6` 上执行。标准显示配置均为 1080×2400、420 dpi、字体缩放 1.0；API 29 使用三键导航，API 35 使用手势导航。未清除 AVD 用户数据。窄横屏首页排序/布局入口回归已在两台 AVD 上执行。

| API | 本轮覆盖 | 结果 |
| --- | --- | --- |
| 29 | `HomeScreenTest`（16 项）、`EditorScreenTest`（31 项，分两片）、`EditorExportFlowTest`（4 项） | 51 项计划用例中 50 通过、1 项因未启用显示切口而按条件跳过、0 失败。键盘贴边、横屏皮肤/图片面板、待办回车、多待办独立块、首页长按/批量操作和快速导出均通过。 |
| 35 | 完整仪器测试（4 片） | 192 项计划用例中 188 通过、4 项按设计跳过、0 失败/错误。跳过项为需独立阶段参数的冷启动恢复及私密 SAF seed/verify 夹具。 |
| 35 | 首页 16 项、编辑页 31 项、编辑页导出 4 项、私密富文本 7 项定向复核 | 全部通过；其中首页通过设备支持的横屏切口避让测试。 |

API 35 曾有一次未分片的 192 项长批次在私密富文本页面附近触发 Android 系统重启，仪器报告 `INSTRUMENTATION_ABORTED: System has crashed`，该批次不作为通过结果。冷启动 AVD 后，私密富文本类 7/7 通过；随后完整 4 片运行达到上表的 188 通过、4 跳过、0 失败。API 29 的编辑器整类运行也曾观察到长批次 Insets/Compose idle 超时；定点复跑通过，并把测试中的编辑器就绪、待办字段出现、Room 卡片进入懒加载列表改为显式等待。之后 API 29 编辑器类分两片 31/31 通过。

因此，API 35 当前完整仪器矩阵通过（排除 4 个有意跳过夹具）；API 29 当前已验证上述 51 项核心 UI 流程，但完整套件尝试被 AVD 运行时堆限制异常中断；API 36 新鲜全量复跑结果见下节。其他切口位置与尺寸、字体缩放/导航组合及 Xiaomi 11 Pro 真机验收仍未关闭。设备测试结果不能替代小米相册/文件管理器、第三方 SAF Provider、QQ/微信分享及 OCR 真实图库验收。

## 2026-10-04 API 29 快照诊断、全量验收与 API 36 复验

API 36 `AnAn_API36_Pixel6` 在 1080×2400、420 dpi 配置下完成本轮变更后的全量仪器回归，分 4 片执行：49、42、47、54 项；合计 192 项计划用例，188 通过、4 项阶段 fixture 按设计跳过、0 失败/错误。跳过的是需要独立进程阶段参数的冷启动恢复 seed 与私密 SAF seed/verify 用例。API 36 上 Argon2id 设备性能用例单项通过，默认 64 MiB / 3 轮配置耗时 1.563 秒；诊断日志记录 Runtime 最大堆 192 MiB，系统非 low-RAM。

API 29 的异常已通过原 AVD 与干净基线的只读 A/B 进一步定位。原 AVD 的 `config.ini`、硬件参数、API 29 系统镜像指纹、RAM/CPU/分辨率/密度与干净基线一致；两边 qcow 镜像一致性检查均无错误。差异是原 AVD 保留 `read-snapshot.txt=default_boot` 和对应快速启动快照，而干净基线没有选择该快照。原 AVD 在 `-read-only -no-snapshot-load -no-snapshot-save` 下冷启动，同一 Argon2 探针报告 `Runtime.maxMemory=536,870,912`、memoryClass/largeMemoryClass=512 MiB、lowRam=false、可用内存约 894 MiB；默认 Argon2id v1（65,536 KiB、3 轮、并行度 4）875 ms、峰值 PSS 201,402 KiB，1/1 通过。相反，在 `-read-only -snapshot default_boot -no-snapshot-save` 显式加载原快照后，启动日志确认 `Successfully loaded snapshot 'default_boot'`，同一探针报告 Runtime 最大堆 16,777,216 bytes 并因 OOM 崩溃；当时可用内存约 937 MiB、`deviceLowMemory=false`。由此确认异常由原 AVD 的快速启动快照状态触发，不是 API 29 镜像普遍限制，也不是设备内存不足。原 AVD 数据/配置始终只读，未清除或修复该快照；真实 API 29 手机仍需单独验收。

使用 `-read-only -no-snapshot-load -no-snapshot-save` 冷启动原 API 29 AVD 后，完整 instrumentation 分 4 片执行，runner 每片计划数为 49、42、47、54（合计 192）。4 个需要独立阶段参数的恢复/私密 SAF fixture 和 1 个因 AVD 不支持显示切口的用例按设计跳过；总结果 187 通过、5 跳过、0 失败/错误。原 AVD 用户数据镜像的长度和修改时间前后未变。

另行在同一 API 29 冷启动只读实例上执行恢复 journal 冷启动 fixture：`seedUncommittedRestoreForProcessRestart` 1/1 通过；仅 force-stop 被测应用后，`coldStartRollsBackPersistedRestoreBeforeShowingMainActivity` 1/1 通过。该结果补充验证真实进程停止后的启动恢复路径；私密 SAF 的 seed/verify fixture 仍未运行，完整套件中的阶段性跳过计数不变。

为获取堆上限证据，`Argon2idDevicePerformanceTest` 的 AndroidTest 日志输出 Runtime/ActivityManager 内存字段；本轮没有修改生产 KDF 参数或私密功能行为。API 35 同一 KDF 用例 1/1 通过，Runtime 最大堆为 192 MiB，耗时 1.440 秒。随后新鲜重跑 `:app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin` 成功：JVM 单测 330/330 通过；Lint 0 errors / 17 warnings / 2 hints；Debug 构建与 AndroidTest Kotlin 编译成功；Debug APK 74,721,145 bytes。构建契约脚本的 application ID、minSdk 29、无 INTERNET 权限及应用名检查均通过。API 36 一次快照启动时 ADB 保持 offline；保留 userdata 并以 `-no-snapshot-load -no-snapshot-save` 冷启动后，ADB 恢复为 device、`sys.boot_completed=1`，应用 `MainActivity` 位于前台。

当前状态：API 29 完整 instrumentation 为 187 通过 / 5 跳过 / 0 失败，API 35 与 API 36 均为 188 通过 / 4 个阶段性跳过 / 0 失败；API 29 恢复 journal seed/verify 已另行各 1/1 通过，私密 SAF seed/verify 仍待运行。原 API 29 AVD 的旧 `default_boot` 快照仍保留且已证实会令堆上限退化；使用该 AVD 时须禁用快照加载或后续经确认再刷新快照。API 29 的异形屏用例被跳过，不代表切口适配已验收。Xiaomi 11 Pro、真实异形屏、字体缩放/导航/主题组合、第三方 Provider 与系统相册/文件管理器行为仍待验收。

## 2026-10-04 API 29 私密 SAF seed/verify 与 API 36 显示矩阵

### API 29 私密 SAF 全流程

在 `AnAn_API29_Pixel6` 上以 `-read-only -no-snapshot-load -no-snapshot-save` 启动；只在这次临时运行层安装 APK、创建私密测试库并清除/恢复应用数据，未改写原 AVD 基础用户数据。seed fixture 在库未配置时建立仅供测试的私密库和 `C5_Private_SAF` 笔记，再写入加密 8×8 ARGB 图片。

| 阶段 | 真实操作 | 结果 |
| --- | --- | --- |
| Seed | 通过设置页启动真实 DocumentsUI，选择 Downloads SAF 目录；生产备份流程写入 `.anbk` | `PrivateSafBackupRestoreDocumentsUiTest` 1/1 通过；确认 ANBK 容器头有效、测试笔记标题未以明文出现在备份字节中 |
| 新设备模拟 | 仅在只读运行层执行 `pm clear com.anan.anannotes`，模拟目标设备尚无应用/私密库 | 应用私有目录消失；Downloads 中备份仍可见 |
| Restore | 再经 DocumentsUI 选择 `.anbk`，验证并预览，设置不同的新私密库密码后恢复 | `PrivateSafBackupRestoreDocumentsUiTest` verify 1/1 通过 |
| 独立 Verify | 使用新密码打开私密库，解密图片并检查尺寸、像素及图片块 | `PrivateSafRoundTripFixtureTest` verify 1/1 通过：笔记、图片块、8×8 ARGB 与 `0xff25a7a0` 像素均保持 |

两阶段生成的测试 `.anbk` 文件和测试目录已逐项删除；随后关闭只读 API 29 实例。原 AVD `config.ini` 与基础 `userdata-qemu.img` 的长度/修改时间保持原值。该验收证明 API 29 AVD 上的私密 SAF UI seed/restore/new-key verify 闭环，不等价于小米真机、其他 DocumentsProvider 或跨 Android 版本行为验收。

### API 36 异形屏与显示组合

测试均运行在 `AnAn_API36_Pixel6`（物理 1080×2400、420 dpi）上，通过 Android cutout overlay 模拟形状；宽度对应 320/360/400 dp。Insets 用例检查首页/待办导航、设置、搜索、私密入口和系统栏安全边界；另运行编辑皮肤下系统栏图标恢复测试。中心挖孔最终修正后完整 8/8 通过。

| 模拟屏形状 | 宽度 | 字号缩放 | 导航 / 系统主题 | 测试结果 |
| --- | ---: | ---: | --- | ---: |
| 高刘海 | 360 dp | 100% | 手势 / 浅色 | 5/5 |
| 中心挖孔 | 360 dp | 150% | 三键 / 深色 | 8/8 |
| 双开孔 | 320 dp | 100% | 手势 / 浅色 | 7/7 |
| 圆角切口 | 400 dp | 200% | 三键 / 深色 | 7/7 |
| 瀑布边缘 | 360 dp | 150% | 手势 / 浅色 | 5/5 |
| Pixel 4a 左上角 `@left` 挖孔 | 320 dp | 100% | 三键 / 深色 | 5/5 |
| `emu01` 非对称 V 形切口 | 400 dp | 200% | 手势 / 浅色 | 5/5 |

合计 42 项定向显示/系统栏检查全部通过。修复的是 `SystemBarAppearanceTest` 的测试定位：需先在 `LazyRow` 中滚动至未组合的夜墨皮肤，再点击；这不是生产界面缺陷。另一次未限定测试类的 API 29 `am instrument` 探索性调用曾触发 seed 旧前置条件失败，并出现待办回车用例语义节点未找到；seed 夹具现可自建测试私密库，待办用例单独复跑 API 29 为 1/1 通过。该未限定调用不是全量套件结果，不纳入通过统计。

### API 29/35 异形屏与紧凑高度补充

使用只读、禁用快照的 AVD 临时运行层；API 29 与 API 35 的基础 `config.ini` / `userdata-qemu.img` 修改时间和长度均保持不变。两个设备都以 840×2400 override、420 dpi 测试（逻辑宽度 320 dp），字号缩放 200%。

| API | 模拟切口 | 导航 / 系统主题 | `SystemWindowInsetsTest` + `SystemBarAppearanceTest` | 首页极短横屏回归 |
| --- | --- | --- | ---: | ---: |
| 29 | 高刘海 | 三键 / 浅色（该 AVD 夜间模式锁定为浅色） | 7/7 | 排序面板 1/1 |
| 35 | Pixel 4a 左上角 `@left` 挖孔 | 手势 / 深色 | 7/7 | 高字号长按、排序面板 2/2 |

新回归复现了一个真实紧凑高度问题：320 dp 横屏、200% 字号时，排序面板默认半展开会把“创建时间”选项留在屏幕外；同设备默认字号可通过。`HomeSortSheet` 现对可用高度 ≤480 dp 的窗口跳过半展开态，仍保留内容高度上限和滚动；API 29 与 API 35 的横屏回归各 1/1 通过。`HomeScreenTest` 的文本定位也改为等待首页卡片进入语义树并容忍标题/摘要相同导致的多匹配；API 35 高字号长按复测 1/1 通过。API 29 的夜间模式命令无效且 `dumpsys uimode` 显示锁定为浅色，因此不把 API 29 结果计作深色模式覆盖。

最终本地验证：`:app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 成功，JVM 单测 330/330、0 失败/错误/跳过；Lint 0 errors / 17 warnings / 2 hints；Debug APK 74,721,145 bytes。随后重新编译 AndroidTest 并在 API 29/35 运行上述新增用例，AndroidTest APK 为 29,632,703 bytes。`scripts/verify-build-contract.ps1` 的 Application ID、minSdk、无 INTERNET 权限、应用名 4 项全部通过。API 36 显示恢复为 1080×2400 / 420 dpi、字体 100%、手势导航、浅色系统、默认 Pixel 6 overlay；API 29/35 均以只读模式关闭，AVD 基础文件时间未变化。尚待：更多 API 29/35 切口、字号、导航与横竖屏交叉组合，Xiaomi 11 Pro 真机，以及第三方 SAF Provider / 系统相册和文件管理器验收。

## 2026-10-04 OCR 持久恢复边界回归

审查纯文字 OCR 源图从 `cacheDir` 迁至 `noBackupFilesDir` 后的启动恢复路径时，发现备份 codec 接受不含路径分隔符的稳定字符串 ID，而 OCR 暂存文件路径只接受 UUID。`recoverPending()` 对没有图片块的待处理 OCR 块查询暂存源时会触发 `IllegalArgumentException`，并被 `MainActivity` 的启动恢复门捕获为恢复失败。新增 Room + WorkManager 设备测试后先在 API 36 复现该失败，再保持严格 UUID 文件名约束，让无效暂存 ID 的查询返回 `null`、清理变为空操作。

另新增真实 WorkManager 用例：笔记已不存在时 worker 结束并删除持久暂存图片，避免新目录产生永久孤儿文件。最终 OCR instrumentation 包在冷启动、`-read-only -no-snapshot-load -no-snapshot-save` 的 API 29、API 35、API 36 AVD 上各执行 8/8，0 失败/跳过；包括 OCR 候选基准、普通/私密引擎、旧 cache 暂存兼容、非 UUID 备份 ID 启动恢复和已删除笔记的源图清理。设备顺序运行，AVD 基础数据不写回。

新鲜本地回归：`:app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain` 退出码 0；JUnit XML 汇总 330 项、0 失败/错误/跳过；Lint 0 errors / 17 warnings / 2 hints；Debug APK 74,721,145 bytes；`verify-build-contract.ps1` 的 Application ID、minSDK、无 INTERNET 权限、应用名 4 项均通过。当前 AndroidTest XML（API 35 最后一次运行）为 8/8；API 29 与 API 36 的 Gradle runner 输出同样明确报告 8/8。

全量 UI 状态需单独说明：此前 API 36 全量 `connectedDebugAndroidTest` 运行计划 197 项，5 项阶段 fixture 跳过、6 项失败；失败为 1 项 DocumentsUI 测试未看到 Download 列表，以及 5 项 IME Insets/layout 等待超时。之后图形显示实例中将 `show_ime_with_hard_keyboard` 临时设为 1 并单独复测，Gboard 已启用但仍未显示 IME（`mInputShown=false`），单项依旧超时。该全量套件未在本轮 OCR 代码改动后重跑；所以本轮结论仅为 OCR 定向包和 JVM/构建检查通过，不宣称 API 36 全量 UI 通过。模拟器临时设置已恢复为原值 0，API 29/35/36 只读实例均已关闭。下一步应单独排查该 DocumentsUI/IME 自动化失败，并继续 API 29/35 更广显示组合、Xiaomi 11 Pro 真机及第三方 Provider 验收。

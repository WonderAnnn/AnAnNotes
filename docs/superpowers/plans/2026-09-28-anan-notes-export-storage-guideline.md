# 安安简记：高清导出、导出记录与存储概览实施方针

日期：2026-09-28；收尾核对：2026-09-29。状态：本轮四项普通笔记功能已实现，并完成 MuMu 回归；不代表完整 V1 或全面屏设备矩阵已验收。

本文件补充 [V1 实施计划](2026-09-27-anan-notes-v1-implementation.md) 的任务 10，不替代产品设计、总体设计及独立安全设计。按“先补失败测试 → 实现 → 定向测试 → 全量回归 → 更新证据”推进；不重复已经验收的 TXT、Markdown ZIP、PDF 和朋友圈 PNG。

## 1. 本轮范围与边界

本轮实现 A4 600 DPI 分页 PNG、单文件长图、普通导出记录、普通存储概览，并整理下一阶段全面屏与异形屏适配计划。

- 不增加联网、相册全盘读取或存储全盘访问权限。
- 普通导出仍只读取普通数据库、普通原图目录，不读取私密模块。
- 私密导出必须先有独立认证授权、独立加密记录和安全出口；不能把普通记录仓库复用于私密记录。本轮不得通过普通记录或统计泄露私密文件名、数量、大小、存在性。
- 私密导出接入及完整屏幕适配属于下一阶段，不将接口预留写成已实现。
- 不删除原图、正文、用户导出文件；“清理”仅清理可重建的预览图缓存。
- 工作区没有 Git 元数据：直接在用户指定项目继续，不初始化仓库，不伪造提交或分支验收记录。

## 2. 对用户可见的结果

导出菜单按现有中性、低饱和风格提供：PDF、PNG（A4 分页，600 DPI）、PNG（长图）、PNG（朋友圈方图）、PNG（朋友圈竖图）、TXT、Markdown ZIP。PNG 保存到 `Pictures/AnAn Notes/Exports/`，无需用户每次授权文件夹；其余沿用系统文件夹选择器。

设置页新增“导出记录”和“存储概览”入口。记录页显示格式、实际文件名、时间、位置或错误代码，可打开/分享成功文件，缺失或无访问权时明确提示。清空记录不删除导出文件。

导出结束仍使用短暂提示说明文件和位置，失败说明原因与错误代码；取消不算失败，不发布半成品。批量中已成功的文件保留，后续失败不回滚早先成功项。

## 3. 高清 PNG 的技术契约

### 3.1 A4 分页

- 输出尺寸固定 `4961 × 7016 px`，来自 A4 `210 × 297 mm` 按 600 DPI 向上取整。
- 写入 PNG `pHYs`：横纵均 `23622 px/m`、单位为米，约 600 DPI。像素数和物理分辨率均需验证，不能只改 DPI 标记。
- 文字直接用 Android 字形与布局渲染，不放大手机截图；采用 PDF 相近的页边距和物理字号，继承当前皮肤、字号、行间距及块内格式。
- 图片读取原图而非预览缓存，按实际比例布局并有界采样。600 DPI 保证页面的输出像素密度，不会凭空补出低分辨率照片原本没有的细节。
- 按行分页；待办完成态、引用、图片宽度百分比/对齐和原始比例保留。超高图片等比适应单页，不拉伸。
- 带状画布逐行编码，单条画带高度不超过 128 px；不创建整页约 133 MiB 的 ARGB 位图。任何已发布页面都是完整合法 PNG。
- 文件名：`文件夹_标题_日期_A4_001.png`；多页编号递增，重名交给现有文件存储器处理。

### 3.2 单张长图

- 固定宽度 `1080 px`，高度由完整正文、图片及留白计算；一次笔记只产生一个 PNG。
- 上限 `32768 px`，预先测量，超限报 `AN-EXP-004`，建议使用 A4 分页；不静默切片、不截断内容、不偷偷降分辨率。
- 长图与分页共用流式编码，文件写入上限 `128 MiB`；超过即失败并移除当前未发布文件。
- 不给社交长图虚构 A4 印刷 DPI。文件尺寸可读不等于所有社交平台支持超长图；朋友圈仍推荐 1080×1350 的专用选项。

### 3.3 模块与接口

`StreamingPngWriter` 负责 PNG 签名、IHDR、可选 pHYs、有限大小 IDAT、CRC、zlib、IEND；以逐行 RGB 输入工作，检查行数/宽度、写入失败与上限，保留调用者 OutputStream 的所有权。

`AndroidRasterPngExportRenderer` 负责布局和画带，复用现有 `SocialPngExportRenderer.render(snapshot, format, openImage, writePage)` 的回调契约；只处理 A4 与长图，现有社交 renderer 不变。排版与图片边界预检在创建目的文件之前完成，CPU 渲染/编码不在 UI 主线程执行。

`OrdinaryExportService` 路由四种 PNG 到 MediaStore；在每个写入成功回执后记录成功项，在每个笔记失败后记录失败项，既有 Completed/ItemCompleted/ItemFailed 契约保持兼容。

新增历史接口 `ExportHistoryRecorder`：

```kotlin
interface ExportHistoryRecorder {
  suspend fun recordSuccess(format: ExportFormat, item: ExportEvent.ItemCompleted)
  suspend fun recordFailure(format: ExportFormat, item: ExportEvent.ItemFailed, fileName: String)
}
```

历史持久化失败不得让已成功发布文件被误报为文件写入失败；日志不可反过来破坏导出结果。调用方可提示记录未保存，但不得丢失真实导出回执。记录使用实际回执 URI 和文件名，而非预估重名。

## 4. 历史与存储的技术契约

`ExportHistoryRepository` 只接普通数据库。插入与裁剪使用 Room 事务，最近最多 100 条；相同时间戳排序稳定。记录失败项时只保存必要元数据和错误代码，不持久化笔记正文。清空只调用历史 DAO。

`StorageOverviewRepository` 只统计明确的普通路径：普通 Room 数据库及 WAL/SHM、`filesDir/notes/assets`、`cacheDir/notes/previews`；不枚举整个 filesDir/cacheDir，不访问 private 目录。

导出文件统计仅查询记录中成功回执、去重后的 URI：可访问且大小已知的相加；未知大小、文件缺失或权限已撤销分别计数，不伪装成 0 B，也不申请额外权限。页上明确这是“记录中可访问的导出文件”，不是磁盘上全部历史导出文件；这些文件是外部副本，不算应用私有空间。清空记录后该项无法再追溯。

独立 `ExportHistoryViewModel`/`StorageOverviewViewModel` 提供 StateFlow；界面处理加载、空状态、错误和刷新，沿用圆角卡片、12 dp 输入风格、简体中文、单箭头返回。导航与设置入口统一由现有 AppNavigation 接入。

## 5. 实施顺序、分工与验收

| 步骤 | 产物 | 验收门槛 |
| --- | --- | --- |
| 1 | PNG 逐行编码器和 JVM 测试 | CRC 正确、可解压 RGB、严格行数、DPI 元数据、输出上限/失败 |
| 2 | A4/长图 Android 带状 renderer | 真正尺寸、长图一个回执、中文/格式/图片/深浅皮肤、超限预检 |
| 3 | 导出服务与格式面板接线 | 不走 SAF 的 PNG、批量部分成功、取消清理、后台渲染 |
| 4 | 历史/统计仓库与页面 | Room 重启持久化、100 条上限、清空不删文件、普通/私密隔离、未知大小 |
| 5 | 导航接线和回归 | 设置入口、打开/分享失败提示、界面安全区、已有功能不回退 |
| 6 | 方针/技术设计/实施计划证据同步 | 测试数字来自本轮运行，不把计划勾成未完成的功能 |

主线负责步骤 1–3、5–6。可独立委派步骤 4，写入范围限定新仓库/页面和历史 DAO，禁止同时修改 ExportService、HomeViewModel、AppNavigation 或 SettingsScreen；Gradle 验证由主线统一调度，避免两个构建同时争用目录。

## 6. 回归与安全测试矩阵

- JVM：PNG chunk/CRC、无透明背景 RGB、输出行边界、内容超限、成功/失败记录回调、取消不记假失败、部分成功、URI 去重与未知大小、缓存和原图路径隔离。
- Android：600 DPI 真实 header/pHYs、带状跨界内容、图片比例、长图单文件、Room 记录重建与裁剪、统计不探测私密路径、页面入口及清空行为。
- 全量：`testDebugUnitTest`、`connectedDebugAndroidTest`、`lintDebug`、`assembleDebug` 与权限/最低版本/安装包契约脚本。
- 设备：当前可连接的 MuMu 用于自动化，实体小米 11 Pro 仅在实际连接且运行测试后计入验收；不把模拟器证据冒充真机证据。
- APK 目标仍少于 300 MB；测试中不得读取用户私密正文，不清空用户数据库。

## 7. 下一阶段

| 优先级 | 下一项 | 具体工作 | 结束条件 |
| --- | --- | --- | --- |
| P0 | 完整屏幕适配 E1–E6 | 按独立方针统一 Insets、系统栏外观、编辑器 IME、抽屉和设备矩阵 | 每项有行为测试；未具备设备的格明确未验收 |
| P1（2026-10-01 已实现） | 普通编辑页单条快速导出 | 共用 `ExportFormatSheet` 与普通服务工厂；编辑页提供直接可见的导出操作，等待当前自动保存修订提交后才生成快照；空白新笔记不落盘；沿用普通导出历史、SAF 与 MediaStore | 已验证最新输入对应已提交笔记并生成成功记录；空白笔记不创建记录或文件；不要求先回首页长按。详见 [编辑页单条导出验证报告](../verification/2026-10-01-editor-quick-export-verification.md) |
| P1 | 私密验证导出及私密记录 | 独立 `PrivateExportAuthorizer`/一次性授权，重新验证密码、明文导出警示、独立加密历史/URI；不把私密 ID 送普通服务 | 未认证/过期授权失败；锁定后私密记录不可见；普通统计不能推知私密数据 |
| P1 | 真实目的位置验收 | 小米的相册/文件管理器；系统 SAF 与实际使用的第三方提供器；重启后权限、改名/删除文件、卸载后保留文件 | 实测反馈位置与实际文件一致，失败不留待发布残片 |
| P2 | 现有文档后续任务 | 内置离线 OCR、加密备份/恢复、回收站及发布打包，按 V1 对应任务的未完成清单推进 | 不把本轮 PNG/存储完成等同于完整 V1 已交付 |

完整屏幕适配按独立的 [全面屏与异形屏实施方针](2026-09-28-anan-notes-edge-to-edge-guideline.md) 执行，先统一 Insets 归属再调整页面，不硬编码刘海/状态栏高度。密码学仍以独立安全文档为准，下一项实施前先写定向失败测试。普通笔记现支持首页批量和编辑页单条导出；私密验证导出仍未实现。详细运行记录见 [本轮验证报告](../verification/2026-09-28-export-storage-verification.md) 和 [编辑页单条导出验证报告](../verification/2026-10-01-editor-quick-export-verification.md)。

## 8. 本轮证据

2026-09-28 最终完整命令 `testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug` 退出码为 0，`BUILD SUCCESSFUL`（3 分 20 秒）；2026-09-29 收尾重新运行本机构建、Lint 与契约检查，并强制重跑 JVM 测试。收尾时 ADB 无已连接设备，没有把昨晚的仪器结果冒充今天重新执行。

| 项目 | 实际结果 | 证据边界 |
| --- | --- | --- |
| JVM | 170/170，失败/错误/跳过均为 0 | 28 个 XML suite；9 月 29 日 `testDebugUnitTest --rerun` 实际执行通过 |
| Android | 125/125，失败/错误/跳过均为 0 | MuMu Android 12/API 32；9 月 28 日 XML 时间戳 `2026-09-28T15:30:51` UTC |
| Lint | 0 Error、13 Warning、2 Hint | 未把版本提示或 KTX 建议写成“零警告”；分类见验证报告 |
| 构建契约 | applicationId、minSdk、无 INTERNET、应用名四项 PASS | 检查 APK/合并 Manifest，而非只读配置文本 |
| Debug APK | 41,215,925 字节，39.31 MiB | 小于 300 MB；不替代带 OCR 模型的 Release 包验收 |
| 安装 | 9 月 28 日 MuMu `install -r` 返回 Success，启动命令成功 | 保留用户数据；未安装到小米真机 |
| 定向审查 | PNG I1/I2/I3/M1 均关闭；历史/存储接线审查通过 | [PNG 复审](2026-09-28-export-png-re-review.md)、[历史与存储审查](2026-09-28-export-storage-integration-review.md)；不代表整个 V1 重新验收 |

安装包：[app-debug.apk](D:/anan/env-check/app/build/outputs/apk/debug/app-debug.apk)。SHA-256：

```text
7EA118D49C6AD34D2FE8018B2A23DC073615662FE1CA2AEA77E8EBFDB9D44677
```

交付核对：A4 实际像素及 pHYs、长图单文件、跨画带正文与格式、分页无漏行/重复、真实 Job 取消链、成功回执记录、100 条裁剪、普通/私密路径隔离、未知大小显示、缓存清理边界和页面入口均有对应测试。真实系统文件提供器、第三方接收应用、小米相册、低内存峰值与 E1–E6 矩阵继续保留为未验收项，不因本轮自动化通过而勾选。

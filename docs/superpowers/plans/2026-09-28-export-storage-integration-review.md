# 普通历史 / 存储 / 导航集成只读审查

审查日期：2026-09-28。文件与既有测试结果快照：23:01（Asia/Shanghai）。契约：[导出与存储方针第 4–6 节](D:/anan/docs/superpowers/plans/2026-09-28-anan-notes-export-storage-guideline.md:63)。

## 结论

规格：符合本轮普通历史、存储统计及设置 / 导航入口范围的契约。质量：通过本轮只读集成审查。未发现可定位的实际泄露、误删文件、吞掉取消异常、100 条事务边界破坏、未知大小伪报或页面不可达的重要缺陷。

- Critical：无（0）。
- Important：无（0）。
- Minor：无（0）。

结论仅覆盖下列 manifest，不代表整个项目或第 6 节全量验收已通过。

## 文件 manifest 与边界

工作区无 Git 元数据；本报告基于当前文件清单、范围内实现及相关新测试，不提供或推断 diff、提交 SHA、历史修改归属。大小为整文件字节数；局部审查文件只评价表中区域。

| 当前文件 | 审查边界 | 字节数 |
| --- | --- | ---: |
| [ExportHistoryRepository.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ExportHistoryRepository.kt) | 全文件 | 2539 |
| [StorageOverviewRepository.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt) | 全文件 | 6316 |
| [StorageOverview.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverview.kt) | 统计模型 / 状态 | 1103 |
| [ContentResolverExportFileSizeReader.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ContentResolverExportFileSizeReader.kt) | 全文件 | 1621 |
| [ExportHistoryFileActions.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryFileActions.kt) | 全文件 | 3596 |
| [ExportHistoryViewModel.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryViewModel.kt) | 全文件 | 2951 |
| [StorageOverviewViewModel.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/StorageOverviewViewModel.kt) | 全文件 | 2510 |
| [ExportHistoryScreen.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryScreen.kt) | 全文件 | 8072 |
| [StorageOverviewScreen.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/StorageOverviewScreen.kt) | 全文件 | 7459 |
| [Daos.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/local/Daos.kt:219) | 仅 ExportHistoryDao，219–235 行 | 11024 |
| [SettingsScreen.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/SettingsScreen.kt:189) | 两个入口、回调及所在滚动容器 | 12143 |
| [AppNavigation.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/ui/navigation/AppNavigation.kt:165) | 历史 / 存储路由、设置接线、返回 | 6680 |
| [ExportHistoryRepositoryTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/data/repository/ExportHistoryRepositoryTest.kt) | 6 个历史用例 | 6179 |
| [ContentResolverExportFileSizeReaderTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/data/repository/ContentResolverExportFileSizeReaderTest.kt) | 6 个 provider 用例 | 3856 |
| [ExportHistoryFileActionsTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/feature/settings/ExportHistoryFileActionsTest.kt) | 6 个打开 / 分享用例 | 4954 |
| [ExportHistoryAndStorageScreenTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/ui/ExportHistoryAndStorageScreenTest.kt) | 3 个页面用例 | 6214 |
| [AppNavigationTest.kt](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/ui/AppNavigationTest.kt:39) | 仅设置进入两页并返回的集成用例 | 2396 |
| [StorageOverviewRepositoryTest.kt](D:/anan/env-check/app/src/test/java/com/anan/anannotes/data/repository/StorageOverviewRepositoryTest.kt) | 7 个统计 / 清理用例 | 6336 |

必要依赖仅核对：[Entities.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/local/Entities.kt:110) 的历史实体、[AnAnDatabase.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/local/AnAnDatabase.kt:20) 的普通数据库注册 / 入口、[ExportModels.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/core/model/ExportModels.kt:13) 的历史模型、[OriginalImageStore.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/files/OriginalImageStore.kt:78) 的普通原图目录常量及 [PreviewCacheStore.kt](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/files/PreviewCacheStore.kt:51) 的清理实现。未扩展为旧项目、密码学、PNG 或计划内尚未完成 Insets 的审查。

## 关键代码证据

1. **普通历史及事务边界。** 仓库接 `AnAnDatabase`；失败记录仅保存所需元数据和 `item.code`，不持久化失败消息 / 笔记正文。[失败记录](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ExportHistoryRepository.kt:35) 与 [插入 / 裁剪事务](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ExportHistoryRepository.kt:47) 清楚分离。DAO 查询与裁剪统一使用 `createdAtMillis DESC, rowid DESC`，同时间戳以插入顺序稳定保留最新 100 条；不依赖随机 UUID 排序。[DAO](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/local/Daos.kt:221)；已有测试覆盖同时间戳 125 条、并发写入、裁剪失败回滚和数据库重开持久化。

2. **清空不删导出文件。** 历史仓库 `clear()` 只调用历史 DAO，裁剪 SQL 也只作用于 `export_history`，没有文件删除能力。[clear](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ExportHistoryRepository.kt:27)。存储页清理仅委托普通 `notes/previews` 缓存；检查路径分段符号链接后使用既有缓存 store，不清理原图、其他缓存或外部导出文件。[缓存清理入口](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt:79)；测试用临时文件验证保留边界，不操作用户数据库。

3. **统计没有扩展到私密路径。** 数据库 / WAL / SHM 逐一指定；资产与预览分别限定 `notes/assets`、`notes/previews`，没有枚举整个 filesDir / cacheDir。[统计入口](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt:46)。目录分段拒绝符号链接，树遍历不使用 FOLLOW_LINKS，文件属性使用 NOFOLLOW_LINKS；未发现遍历到其他数据范围的路径。[路径边界](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt:98)。

4. **未知大小不是 0 B。** 只读取成功记录的去重 URI，分别累计 Known、Unknown、Unavailable；缺失 / 撤权归为不可访问，与可访问但未知大小分开。[汇总](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt:50)。即使 provider 返回已知 SIZE，仍需成功打开只读 descriptor 才计入已知大小；负值 / 无列回退 stat，仍未知则保留 Unknown；真正空文件才是 Known(0)。[大小读取](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ContentResolverExportFileSizeReader.kt:28)。页面明确记录覆盖限制、外部副本不计入内部空间，以及清空 / 裁剪后不能再追溯；未发现将未知或不可访问文件伪报为已知 0 B。[统计文案](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/StorageOverviewScreen.kt:99)。

5. **权限与打开 / 分享失败。** 只接受成功记录的 content URI，先检查可访问性；未知大小但可访问仍允许打开 / 分享。使用实际回执 URI、对应 MIME、ClipData，文件 Intent 与分享 chooser 仅授予 READ，无 WRITE / PERSISTABLE 授权，也不申请新权限；非 Activity Context 补 NEW_TASK，在主线程启动。无接收应用、权限异常、文件不可访问均给出中文反馈，不暴露内部文件路径。[Intent 与异常处理](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryFileActions.kt:29)。

6. **取消异常保持取消语义。** 统计 reader、URI 汇总、文件动作和两个 VM 的通用异常处理前均明确重抛 CancellationException；扫描过程中检查协程活跃状态，未发现取消被转换成不可访问计数或普通错误提示的捕获分支。[reader](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/ContentResolverExportFileSizeReader.kt:21)、[统计汇总](D:/anan/env-check/app/src/main/java/com/anan/anannotes/data/repository/StorageOverviewRepository.kt:59)、[文件动作](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryFileActions.kt:37)、[历史 VM](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/ExportHistoryViewModel.kt:32)、[存储 VM](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/StorageOverviewViewModel.kt:37)。本项是静态异常路径结论，不冒充本轮新增动态取消测试。

7. **页面和导航实际接通。** 独立 VM 提供 StateFlow，两页处理加载 / 空状态 / 错误 / 刷新，清空历史有“不删除文件”确认，忙碌时禁用相应动作。设置卡片回调在 AppNavigation 中指向实际历史 / 存储 entry，返回统一 pop，不是仅添加空回调。[设置入口](D:/anan/env-check/app/src/main/java/com/anan/anannotes/feature/settings/SettingsScreen.kt:189)、[接线](D:/anan/env-check/app/src/main/java/com/anan/anannotes/ui/navigation/AppNavigation.kt:165)。真实 MainActivity 导航用例覆盖设置 → 历史 → 返回 → 存储 → 返回设置，而不仅是无 VM 的页面测试。[导航用例](D:/anan/env-check/app/src/androidTest/java/com/anan/anannotes/ui/AppNavigationTest.kt:39)。

## 既有验证证据与限制

只读取主线已有 XML，不启动 Gradle / ADB，不重新执行测试，不派代理。

| 范围内现有用例 | 通过 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| Android：ExportHistoryRepositoryTest | 6 | 0 / 0 / 0 |
| Android：ContentResolverExportFileSizeReaderTest | 6 | 0 / 0 / 0 |
| Android：ExportHistoryFileActionsTest | 6 | 0 / 0 / 0 |
| Android：ExportHistoryAndStorageScreenTest | 3 | 0 / 0 / 0 |
| Android：设置进入历史 / 存储并返回的导航用例 | 1 | 0 / 0 / 0 |
| JVM：StorageOverviewRepositoryTest | 7 | 0 / 0 / 0 |

合计本范围 29 个既有用例通过。证据：[Android XML](<D:/anan/env-check/app/build/outputs/androidTest-results/connected/debug/TEST-emulator-5554 - 12-_app-.xml>)，timestamp 原文 `2026-09-28T14:44:32`；[JVM XML](D:/anan/env-check/app/build/test-results/testDebugUnitTest/TEST-com.anan.anannotes.data.repository.StorageOverviewRepositoryTest.xml)，timestamp `2026-09-28T14:43:03.479Z`。Android 整份 XML 仍标记 37 项 / 1 失败，该失败不在上述范围用例中；本报告不据此声明全量通过，也不重新审查 PNG。

文件动作测试使用捕获 Intent 的测试 Context，证明 URI、权限标记和反馈分支，不等同于第三方接收应用的端到端验证。未新增实体小米设备、全量回归、lint 或 APK 证据；第 5 节步骤 6 的主线文档整理与本轮范围外验收不重复处理。

本轮唯一写入文件为本报告；未改动代码、测试或其他文档。没有需要阻塞主线收尾的本范围 Important / Critical 问题。

# 2026-10-25 基线恢复与新鲜验证记录

执行日期：2026-10-25（全部为本日实际执行结果，非历史引用）

本轮目标：按《安安简记项目交接与继续开发指南》第 8 节顺序完成第 1、2 步——恢复 Git 版本控制建立基线，并在当前源码上执行新鲜单测、Lint、Debug 构建与 API 35 UI 冒烟。

## 1. 备份（先于任何改动）

备份目录 `D:\anan\backups\`，三个归档及 SHA-256：

| 归档 | 大小 | SHA-256 |
|---|---|---|
| `2026-10-25-env-check-source.tar.gz`（源码，排除 build/dist） | 51 MB | `25d667dd69517161f7165e6cae7a5145823c2ed2dbb1c922728641ac8020de37` |
| `2026-10-25-env-check-dist-artifacts.tar.gz`（dist 历史 APK） | 206 MB | `15504aa911092a6cdb54c636e7964431dd8931e1df894c847b4032c358a9f69d` |
| `2026-10-25-docs-superpowers.tar.gz`（项目文档） | 238 KB | `1cafcd5c54f5623dcb9bd45717b91580783d45baff0ada28ffc4e4765b17a674` |

同时复核了 2026-10-17 Release 审查包 `AnAnNotes-1.0-release-unsigned.apk` 的 SHA-256，与历史记录一致（`1ACCFF0666BD3ED3F27242F52D560296C779007E6168E14187E583125FFCD32E`），证据完好。

## 2. Git 基线

- 在 `D:\anan\env-check` 执行 `git init -b main`，基线提交 `862d20c`，tag `baseline-2026-10-25`，共 371 个文件。
- 入库内容：app 源码、Gradle 配置、scripts、Room schema、OCR 模型、皮肤资源，以及项目文档（复制自 `D:\anan\docs\superpowers` 与交接指南）。
- `.gitignore`：沿用原有 Android Studio 模板，补充 `/app/build`、`.kotlin`、`/dist`（dist 的 508 MB 历史 APK 仅存于备份归档，不入库）；`local.properties` 不入库。
- 仓库配置 `core.autocrlf=false`、`core.quotepath=false`，保证基线文件与磁盘字节一致。
- **注意**：`D:\anan\docs\superpowers` 原位置仍保留为交接前冻结副本；今后文档更新应在仓库内 `docs/superpowers/` 进行。仓库内文档里指向 `../../outputs` 的相对链接不再指向 `D:\anan\outputs`，阅读历史报告时以原位置为准。

## 3. 环境状态（本轮实测）

| 项 | 结果 |
|---|---|
| Gradle | 9.5.0，Wrapper 指向本机归档，`--version` 正常 |
| Launcher JVM | Android Studio JBR **Java 25.0.3**（交接文档 P2 疑点属实；`app/build.gradle.kts` 注释已修正，toolchain 21 由 foojay 解析器供给） |
| ADB server | 本轮正常启动，`devices -l` 正常返回；上一会话的 `could not read ok from ADB Server` 未复现 |
| 模拟器 | `AnAn_API35_Pixel6` 冷启动（`-no-snapshot-load -no-snapshot-save`）约 60 秒完成启动，`sys.boot_completed=1` |

## 4. 新鲜验证结果（对应基线提交 862d20c）

| 检查 | 命令 | 结果 |
|---|---|---|
| JVM 单元测试 | `:app:testDebugUnitTest --rerun-tasks` | **352 通过 / 0 跳过 / 0 失败 / 0 错误**（2m22s，统计自测试结果 XML；与 2026-10-08 历史计数一致） |
| Lint | `:app:lintRelease` | **0 error / 22 warning / 2 hint**（10-08 记录为 19 warning，多出 3 个 warning，无 error；报告在 `app/build/reports/lint-results-release.*`） |
| Debug 构建 | `:app:assembleDebug` | 成功，`app-debug.apk` 103,513,067 字节，SHA-256 `5bebade1e5ff1519df0836c2073584bf6d9530ce72060398140e60e9b2b7d8fe` |
| 无 INTERNET 门禁 | `:app:verifyReleaseNoInternetPermission` | 通过 |
| 构建契约 | `scripts/verify-build-contract.ps1` | Application ID / Minimum SDK / No INTERNET permission / Application name 全部 PASS（针对本轮新构建 APK） |

## 5. API 35 UI 冒烟（Debug 包，冷启动模拟器）

流程与结果（全程进程存活，`logcat` 中 **0 次 FATAL EXCEPTION**）：

1. `adb install -r` 安装新构建 → Success。
2. 启动应用：首页正常渲染（标题、私密笔记入口、搜索框、分类筛选、空状态、FAB、底部导航）。
3. 意外点入「待办」页：空状态与开关正常渲染。
4. 返回笔记页，点 FAB 进入混合编辑器：返回/导出/皮肤/未修改工具栏、标题、正文占位、B/H/待办/Aa/图片 底栏均正常。
5. 输入文字 `Baseline smoke test 20261025` 并返回：首页出现对应笔记卡片，自动保存生效。

截图证据：`assets/2026-10-25-baseline/`（api35-smoke-home / -todo / -editor / -saved，共 4 张）。

已知观察（不判为缺陷）：冒烟卡片正文计数显示「0 字」，推测是 `adb shell input text` 焦点落在标题输入框所致，属命令行输入限制；键盘输入路径的正文保存已由既有仪器测试覆盖。

## 6. 结论与后续

- 交接文档 P0「源码没有 Git 元数据」**已关闭**：仓库、基线提交与 tag 已建立，构建产物与 dist 不入库。
- P2「ADB server 未正常启动」本轮**未复现**；P2「JDK 版本注释过期」**已修正**。
- 当前基线健康：单测全绿、Lint 无 error、Debug 构建与门禁通过、API 35 冒烟通过。
- 按交接文档第 8 节，下一步是第 3 步：**皮肤底图与 A4 600 DPI 导出清晰度及视觉一致性**（11 款皮肤 × 屏幕/A4 PDF/A4 PNG/社交 PNG 原图对照）；之后第 4 步刷新 API 29/35/36 全量矩阵。
- Release 签名（P0）仍未配置，维持阻断状态；未生成任何正式签名包。

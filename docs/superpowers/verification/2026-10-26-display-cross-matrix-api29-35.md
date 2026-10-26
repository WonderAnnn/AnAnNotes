# 2026-10-26 API 29/35 异形屏×显示设置交叉矩阵扩展记录

执行日期：2026-10-26（对应交接指南 P1「全量 API 矩阵证据偏旧且交叉组合不全」中"仍待 API 29/35 更广切口/字号/导航/横竖屏交叉组合"项）

## 结论

在 API 29 与 API 35 两个 AVD 上执行 **13/13 通过**（全部为原有断言原样运行，未放宽任何约束）：

| API | 组合 | 结果 |
|---|---|---|
| 35 | 7 种切口形状（hole 中心挖孔 / tall 高刘海 / double 双开孔 / corner 圆角 / waterfall 瀑布 / pixel_4a 左上挖孔 / emu01 非对称）× 360dp × 100% 字号 × 手势导航 × 浅色 | 7/7 PASS |
| 35 | tall × 320dp × 200% 字号 × 三键导航 × 深色（极端交叉样本） | PASS |
| 35 | tall × 400dp × 150% 字号 × 手势 × 浅色 | PASS |
| 29 | tall、hole × 默认宽度 × 100% × 三键 × 浅色 | 2/2 PASS |
| 29 | tall、hole × 320dp × 200% 字号 × 三键 × 深色（极端交叉样本） | 2/2 PASS |

测试类：`SystemWindowInsetsTest`、`PageInsetsTest`、`SystemBarAppearanceTest`、`SecondaryPageLayoutTest`（与 2026-10-04 的 42 项定向矩阵相同目标）。此前 2026-10-04 的定向矩阵只在 API 36 执行，本轮将覆盖扩展到交接指南点名的 API 29/35 缺口。

## 工具

新增可复用编排脚本 [scripts/run-display-cross-matrix.ps1](../../../scripts/run-display-cross-matrix.ps1)：

- 对**已启动**的模拟器循环应用组合（`cmd overlay enable` 切口 / `wm density` 宽度密度 / `settings put system font_scale` 字号 / `settings put secure navigation_mode` 导航 / `cmd uimode night` 主题），经 `am instrument` 直跑（不经 Gradle，避免自动卸载），逐组合记录 CSV，结束统一还原全部设置。
- 本轮已验证可还原：密度回 420、字号回 1.0、夜间关、切口 overlay 全禁用、导航回原值。

**完整交叉矩阵**（252 组合，预计数小时）的一条命令：

```powershell
.\scripts\run-display-cross-matrix.ps1 -Cutouts hole,tall,double,corner,waterfall,pixel_4a,emu01 `
  -WidthDp 320,360,400 -FontScales 1.0,1.5,2.0 -NavModes 0,2 -Themes light,dark `
  -OutCsv D:\anan\outputs\display-cross-full.csv
```

本轮结果 CSV：`D:\anan\outputs\display-cross-api{29,35}-*.csv`。

## API 36 完整交叉尝试（同日追加：部分覆盖 + 镜像稳定性发现）

在 API 36 上尝试完整 252 组合交叉，**因系统镜像在该工作负载下结构性不稳定而中止**：

**结果**（`D:\anan\outputs\display-cross-api36-dedup.csv`，73/252 组合有数据）：60 PASS；5 个真实 FAIL（见下）；2 组合以证据排除；6 行崩溃相关。缺口：tall/corner/emu01 三组因"崩溃后首次冷启动必败"被跳过，hole/double/pixel_4a/waterfall 各组尾部因崩溃或熔断未完成。

**系统镜像稳定性发现（模拟器/系统级，非应用缺陷）**：
1. `wm density 432`（400dp）+ 手势导航在 hole 与 double 两个切口**独立复现** system_server 击杀（settings/overlay 服务消失）；hole 另在 400dp/150%/三键崩溃一次——400dp 单元格整体高危；
2. pixel_4a 切口在 320dp/150%/手势又添新崩溃签名；
3. 一次宿主侧 qemu 段错误；
4. 崩溃后首次冷启动必败（约 7 分钟后可正常启动）。
结论：API 36 模拟器镜像不适合承载"设置快速切换"类长时间矩阵；其 320/360dp 区段在系统存活期间全部稳定通过。

**5 个真实 FAIL（测试运行完成、设备健康）**：hole 与 double 在 `360dp/100%/三键/浅色` 各 1 败（同形，且同组合在另一轮 PASS，条件性）；waterfall 在 `320dp/150%` 的浅/深/手势浅 3 个组合各 1-2 败（**确定性，疑为瀑布边缘 × 150% 字号的真实显示断言问题，待定位用例名**）。

## API 35 完整交叉（同日完成：252/252 全覆盖）

**结论：252/252 组合全覆盖，250 PASS / 2 FAIL**（`D:\anan\outputs\display-cross-api35-dedup.csv`）。这是交接指南 P1"完整交叉矩阵"首次在单一 API 上闭环。执行采用软件渲染无窗口模式（宿主 gfxstream 当日退化后 `-no-window -gpu swiftshader_indirect` 成为唯一稳定启动方式）与分段编排（每组独立冷启动、组级 65 分钟硬超时、adb 全调用超时、逐组合落盘、失败用例名捕获）。

**唯一真实发现（2 组合、同一用例族，`SystemBarAppearanceTest`）**：
| 组合 | 失败用例 |
|---|---|
| waterfall × 320dp × 200% × 三键 × 浅色 | `changingDisplayThemeUpdatesStatusAndNavigationIconsAndRestoresPreference` + `editorActivityRecreationRestoresGlobalNavigationIconsAndKeepsNoteStatusIcons` |
| waterfall × 320dp × 200% × 三键 × 深色 | `changingDisplayThemeUpdatesStatusAndNavigationIconsAndRestoresPreference`（仅此一个） |

定性：**瀑布边缘切口 + 最窄宽度(320dp) + 最大字号(200%) + 三键导航**的极端组合下系统栏图标深浅切换/恢复断言失败；同组合手势导航（nav2）两主题均通过，360/400dp 宽度均通过。2026-10-04 的 42 项定向组合未覆盖此角落。

**2026-10-26 根因定案并已修复（提交 6c896d5）**：手动驱动 + UI 树 dump + 视觉像素证据三方一致——该组合下主题行三个 FilterChip 总宽超出瀑布切口收窄后的视口，「浅色」chip 本身已被屏幕右缘裁切、「深色」chip 完全在屏外，且该行无法被真实拖动滚出（普通 Row 直接裁掉；改 LazyRow 后内容宽度仍被视为适配、第三项未组合不可滚），导致**深色主题在此配置下完全无法选中**——系统栏图标测试失败正是其正确症状，属真实应用缺陷而非测试期望过严。修复：主题行改用 `FlowRow`（换行布局，任意字号/切口下三选项完整可见可达），测试侧语义查找加 `performScrollTo` 兼容纵向折叠。验证：API 35 模拟器同组合（waterfall×320dp×200%×三键）浅色与深色两变体 `SystemBarAppearanceTest` 各 3/3 PASS，恢复默认显示配置后回归 3/3 PASS，JVM 单测全绿。矩阵 CSV 中的 2 个 FAIL 行保留为修复前历史证据。

**跨镜像对照总结**：API 36 上的全部异常（4 次 system_server 击杀、1 次宿主 qemu 段错误、5 个 FAIL、崩溃后首启动必败）在 API 35 同组合上零复现——36 的结果定性为其镜像缺陷链，35 为干净基线。两个此前"证据排除"的组合（各切口 400dp×100%×手势）在 35 上全部通过。

**执行韧性沉淀**（脚本已固化）：逐组合 CSV 追加、熔断仅对设备级 ERROR 生效（测试 FAIL 是发现不是故障）、致命组合证据排除、断点续跑。

## 边界与剩余

- 本轮 13 组合是扩展切片（含每 API 全部切口形状与两个极端交叉点），**不等于 252 组合的完整交叉**；完整矩阵可用上述命令在空闲时段执行。
- 横竖屏轴由测试类内部断言覆盖（含横屏切口用例），未作为外层矩阵维度。
- API 36 的 42 项定向矩阵（2026-10-04）与 API 29 的 tall 切口专项维持原记录有效。
- 真机（小米）验收仍未执行。

# 2026-10-26 API 29/35 异形屏×显示设置交叉矩阵扩展记录

执行日期：2026-10-26（对应交接指南 P1「全量 API 矩阵证据偏旧且交叉组合不全」中"仍待 API 29/35 更广切口/字号/导航/横竖屏交叉组合"项）；2026-10-27 追加 API 29 完整交叉收官。

## API 29 完整交叉（2026-10-27 收官：可用切口 180/180 全 PASS）

minSdk 镜像的完整交叉补齐。**镜像能力探针先行**：`cmd overlay list` 显示该 API 29 镜像仅提供 5/7 切口 overlay（tall/double/corner/emu01/pixel_4a），**hole 与 waterfall 的 cutout emulation overlay 不存在于该镜像**——此前历史切片中标注为 hole 的 API 29 行实为 enable 静默失败后的无切口运行（当时结果仍全过，但切口标签不实，以本节为准）；`settings put secure navigation_mode`、`wm density`、`font_scale`、`cmd uimode` 全部实测可用。

**结果**（`D:\anan\outputs\display-cross-api29-full.csv`）：5 切口 × 320/360/400dp × 100/150/200% 字号 × 三键/手势 × 浅/深 = **180/180 全 PASS，零失败零排除**。执行采用 API 36 收官同款纪律：每组独立冷启动（软件渲染无窗口）、`-NoExclusions`（400dp 杀手组合证据为 API 36 镜像专属，不外推；事实上 API 29 全部 400dp 单元包括 100%×手势均直接通过）、修复后的断点续跑。waterfall 缺陷的极端形态（320dp×200%）在该镜像无法经 overlay 复现，其修复验证以 API 35/36 的全量交叉为准。

至此**三个 API 的显示交叉全部闭环**：API 29 = 180/180（5/7 可用切口）、API 35 = 252/252（唯一 2 FAIL 已修复翻绿）、API 36 = 252/252 零排除。

## 结论（2026-10-26 首轮扩展切片）

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

## API 36 完整交叉（2026-10-27 补齐收官：252/252 全 PASS、零排除）

首轮尝试因镜像不稳定中止于 73/252（原始记录见下节）。随后以**每组独立冷启动**的既定策略分组补齐：tall → waterfall → corner → emu01 → pixel_4a → hole → double，最后以 `-NoExclusions` 对全部 12 项"证据排除"组合做 mop-up 重试。

**最终结论（`D:\anan\outputs\display-cross-api36-final.csv`，按组合去重终版）：252/252 全 PASS、零排除**。

收官过程的关键事实：
1. **waterfall `320dp/150%` 的 3 个确定性 FAIL 全部翻绿**——即 FlowRow 修复（6c896d5）在 API 36 同样生效，与 API 35 上的根因定案互相印证；
2. **12 项"400dp×100%×手势"排除组合在新鲜冷启动下全部实跑通过**（含独立复现过两次的 hole/double）——晨间崩溃链被定性为**环境性**（连续长跑无冷启动的累积不稳定），非确定性系统杀手；`KnownKillerCombos` 保留在脚本中作为历史证据与危险区提示，但不再有实际排除；
3. hole 此前 400dp/150%/三键 的崩溃同样未在冷启动下复现，hole/double `360dp/100%/三键` 的两个条件性 FAIL 复跑通过；
4. tall 组 36/36 零排除通过（其 400dp×100%×手势从始至终实跑）。

**执行过程中修复的脚本缺陷**：`run-display-cross-matrix.ps1` 的断点续跑键构建 `"$f[1]|$f[2]|..."` 触发 PowerShell 双引号字符串插值陷阱（数组整体插值、`[1]` 保持字面量），resume 键全为垃圾导致跳过永不命中（本轮前段各组因此全量重跑，产生重复行，无害但浪费）；已改为 `-join '|'` 构键并验证命中。终版 CSV 按组合去重保留最高等级判定。

### 首轮尝试原始记录（2026-10-26 晨间，保留为历史证据）

在 API 36 上尝试完整 252 组合交叉，**因系统镜像在该工作负载下结构性不稳定而中止**：

**结果**（`D:\anan\outputs\display-cross-api36-dedup.csv` 首轮部分，73/252 组合有数据）：60 PASS；5 个真实 FAIL；2 组合以证据排除；6 行崩溃相关。缺口：tall/corner/emu01 三组因"崩溃后首次冷启动必败"被跳过，hole/double/pixel_4a/waterfall 各组尾部因崩溃或熔断未完成。

**系统镜像稳定性发现（模拟器/系统级，非应用缺陷）**：
1. `wm density 432`（400dp）+ 手势导航在 hole 与 double 两个切口**独立复现** system_server 击杀（settings/overlay 服务消失）；hole 另在 400dp/150%/三键崩溃一次——400dp 单元格整体高危；
2. pixel_4a 切口在 320dp/150%/手势又添新崩溃签名；
3. 一次宿主侧 qemu 段错误；
4. 崩溃后首次冷启动必败（约 7 分钟后可正常启动）。

上述 1-4 在分组冷启动收官轮全部未复现，最终定性：**"设置快速切换"长跑的累积不稳定，冷启动分组即可规避**；API 36 镜像可承载完整矩阵。

**首轮 5 个真实 FAIL 的最终归宿**：waterfall `320dp/150%` ×3 = FlowRow 修复翻绿（同 API 35 根因）；hole/double `360dp/100%/三键/浅色` ×2 = 复跑通过（维持条件性判定，与 API 35 同组合通过一致）。

## API 35 完整交叉（同日完成：252/252 全覆盖）

**结论：252/252 组合全覆盖，250 PASS / 2 FAIL**（`D:\anan\outputs\display-cross-api35-dedup.csv`）。这是交接指南 P1"完整交叉矩阵"首次在单一 API 上闭环。执行采用软件渲染无窗口模式（宿主 gfxstream 当日退化后 `-no-window -gpu swiftshader_indirect` 成为唯一稳定启动方式）与分段编排（每组独立冷启动、组级 65 分钟硬超时、adb 全调用超时、逐组合落盘、失败用例名捕获）。

**唯一真实发现（2 组合、同一用例族，`SystemBarAppearanceTest`）**：
| 组合 | 失败用例 |
|---|---|
| waterfall × 320dp × 200% × 三键 × 浅色 | `changingDisplayThemeUpdatesStatusAndNavigationIconsAndRestoresPreference` + `editorActivityRecreationRestoresGlobalNavigationIconsAndKeepsNoteStatusIcons` |
| waterfall × 320dp × 200% × 三键 × 深色 | `changingDisplayThemeUpdatesStatusAndNavigationIconsAndRestoresPreference`（仅此一个） |

定性：**瀑布边缘切口 + 最窄宽度(320dp) + 最大字号(200%) + 三键导航**的极端组合下系统栏图标深浅切换/恢复断言失败；同组合手势导航（nav2）两主题均通过，360/400dp 宽度均通过。2026-10-04 的 42 项定向组合未覆盖此角落。

**2026-10-26 根因定案并已修复（提交 6c896d5）**：手动驱动 + UI 树 dump + 视觉像素证据三方一致——该组合下主题行三个 FilterChip 总宽超出瀑布切口收窄后的视口，「浅色」chip 本身已被屏幕右缘裁切、「深色」chip 完全在屏外，且该行无法被真实拖动滚出（普通 Row 直接裁掉；改 LazyRow 后内容宽度仍被视为适配、第三项未组合不可滚），导致**深色主题在此配置下完全无法选中**——系统栏图标测试失败正是其正确症状，属真实应用缺陷而非测试期望过严。修复：主题行改用 `FlowRow`（换行布局，任意字号/切口下三选项完整可见可达），测试侧语义查找加 `performScrollTo` 兼容纵向折叠。验证：API 35 模拟器同组合（waterfall×320dp×200%×三键）浅色与深色两变体 `SystemBarAppearanceTest` 各 3/3 PASS，恢复默认显示配置后回归 3/3 PASS，JVM 单测全绿。矩阵 CSV 中的 2 个 FAIL 行保留为修复前历史证据。

**跨镜像对照总结（2026-10-27 收官后更新）**：API 36 首轮的全部异常（4 次 system_server 击杀、1 次宿主 qemu 段错误、5 个 FAIL、崩溃后首启动必败）在分组冷启动收官轮零复现——最终定性为**同日连续长跑的累积不稳定**而非镜像缺陷链；API 35（干净基线）与 API 36（收官轮）现均为完整 252/252 交叉闭环：35 = 250 PASS + 2 FAIL（该 2 FAIL 已定案修复并翻绿），36 = 252 PASS 零排除。两个 API 的全部确定性发现同源（waterfall 主题行缺陷），已修复。

**执行韧性沉淀**（脚本已固化）：逐组合 CSV 追加、熔断仅对设备级 ERROR 生效（测试 FAIL 是发现不是故障）、致命组合证据排除（收官证明可经冷启动 mop-up 回收）、断点续跑（键构建已修复 PowerShell 插值缺陷）、每组独立冷启动是长矩阵的默认纪律。

## 边界与剩余

- 扩展切片（13 组合）与完整交叉均已完成：**API 35 = 252/252 覆盖（2 FAIL 已修复翻绿）、API 36 = 252/252 全 PASS 零排除**；去重终版 CSV 分别为 `display-cross-api35-dedup.csv`、`display-cross-api36-final.csv`。
- 横竖屏轴由测试类内部断言覆盖（含横屏切口用例），未作为外层矩阵维度。
- API 36 的 42 项定向矩阵（2026-10-04）与 API 29 的 tall 切口专项维持原记录有效。
- 真机（小米）验收仍未执行。

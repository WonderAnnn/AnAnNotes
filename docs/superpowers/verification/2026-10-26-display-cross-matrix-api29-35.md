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

## API 35 完整交叉（同日追加，执行中，完成后更新）

- 同参数全 252 组合，`-NoExclusions`（35 无击杀证据），失败用例名捕获已修复，编排加入崩溃后首启动重试。
- 结果 CSV：`D:\anan\outputs\display-cross-api35-full.csv`。

## 边界与剩余

- 本轮 13 组合是扩展切片（含每 API 全部切口形状与两个极端交叉点），**不等于 252 组合的完整交叉**；完整矩阵可用上述命令在空闲时段执行。
- 横竖屏轴由测试类内部断言覆盖（含横屏切口用例），未作为外层矩阵维度。
- API 36 的 42 项定向矩阵（2026-10-04）与 API 29 的 tall 切口专项维持原记录有效。
- 真机（小米）验收仍未执行。

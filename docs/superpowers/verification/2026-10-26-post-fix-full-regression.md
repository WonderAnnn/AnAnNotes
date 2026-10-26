# 2026-10-26 waterfall 修复后全量新鲜回归

## 背景

waterfall 主题行缺陷修复（提交 `6c896d5`：主题行 Row→`FlowRow` 换行 + 测试 `performScrollTo`）当日仅定向复验了 `SystemBarAppearanceTest`（失败组合 light/dark 各 3/3、默认配置 3/3）。设置页被大量仪器用例触达，故对修复提交做一次全量新鲜回归，确认无横向回归。

## 执行方式

两台 AVD 各自冷启动（`-no-snapshot-load -no-snapshot-save -no-boot-anim -no-window -gpu swiftshader_indirect`，宿主 gfxstream 退化后软件渲染无窗口为唯一稳定方式），`adb install` 后 `am instrument -w` 直跑全量（不经 Gradle，避免自动卸载与结果覆盖），控制台日志完整留存：

| API | 结果 | 耗时 | 证据 |
|---|---|---:|---|
| 35（AnAn_API35_Pixel6） | **OK（234 tests），0 失败 0 错误** | 668s | `D:\anan\outputs\api35-postfix-fullsuite.log` |
| 29（AnAn_API29_Pixel6） | **OK（232 tests），0 失败 0 错误** | 498s | `D:\anan\outputs\api29-postfix-fullsuite.log` |

两 API 总数差 2 为 API 条件用例（新 API 专属类），与历史行为一致；控制台报告不逐行列印假设跳过项，"OK (N)" 即 0 失败 0 错误。JVM 单测（`:app:testDebugUnitTest`）同期全绿。跑毕两模拟器均已关机，ADB 设备列表为空。

## Lint 现状（对照 2026-10-04 基线 17 warnings / 2 hints）

`:app:lintDebug`：**0 errors、24 warnings、2 hints**，全部为风格/版本提示类，无一涉及正确性：

- `UseKtx` ×10（KTX 扩展函数风格建议，遍布既有代码）
- `ObsoleteSdkInt` ×3（minSdk 29 下的防御性分支）
- `NewerVersionAvailable` ×3（kotlin 插件 2.3.20→2.4.21、coroutines-test 1.10.2→1.11.0）
- `IconXmlAndPng` ×2（启动图标 XML/位图双表示，历史状态）
- 其余：Compose lint 风格项（`ModifierParameter`、`LocalContextResourcesRead`、`MutableCollectionMutableState`、`AutoboxingStateCreation`）、`OldTargetApi`/`GradleDependency`（compileSdk/targetSdk 36→37 可用提示）、`UsableSpace`（debug-only 测试 provider）

较基线净增 7 项源于新代码（OCR/私密/导出/证据用例）与 Compose lint 规则随 BOM 2026.03.01 的增密，非缺陷。按"冻结约束、收尾期不动依赖"的纪律，本轮不处理，留待发布后统一升级。

## 结论

修复 `6c896d5` 在 API 29/35 全量仪器套件上零回归；至此交接指南机器侧全部工作（基线→矩阵刷新→确定性缺陷清零→时序收口→交叉矩阵→缺陷修复与全量回归）闭环。剩余均为用户决策项：皮肤重制策略（A/B/C）、小米真机验收、Release 签名密钥（P0）。

# E6 阶段性验证：大字号与当前设备回归

日期：2026-09-30；补验：2026-10-01（150% 系统字号、三键导航）  
范围：E6 字号回归和当前可用设备上的完整自动化验证；不代表整个 E6 设备矩阵已经完成。

## 本轮实现与测试调整

- `HomeScreen.kt` 与 `TodoScreen.kt` 的首页/待办标题统一贴齐各自标题行的顶部。200% 系统字号下两行容器自然高度不同，原先居中对齐造成标题文字垂直起点相差 4 px；新增/既有位置断言通过。
- 首页排序回归先将懒加载网格滚回第 0 项，再比较同时可见的卡片；视口只能显示首项时，断言预期首项位于网格起点，不再等待屏外卡片同时进入语义树。
- 批量操作回归只等待首张目标卡片进入视口；“全选”仍断言选择总数，随后对目标笔记执行移动、置顶、删除。
- 跨皮肤复选框回归按笔记 ID 将懒加载卡片滚动到视口后再打开，避免将“屏外尚未组合”误报为皮肤渲染问题。

以上除标题顶部对齐外，均是测试适配懒加载视口，不改变生产数据或用户操作语义。

## 运行环境与结果

| 项目 | 已运行证据 |
| --- | --- |
| 自动化设备 | MuMu；ADB `127.0.0.1:16384`；系统识别 `NTH_AN00` / `Nottingham` |
| Android | 12 / API 32 |
| 显示 | 900 × 1600 px，240 dpi；测试开始前竖屏，包含测试内部显式横屏场景 |
| 系统字号 | 100% 默认值全量回归；200% 全量回归；结束后读取并确认恢复到 100% |
| 导航设置 | 手势模式全量回归；另在三键模式完成 154 项全量仪器测试，结束后恢复手势模式并复核 |
| Android 仪器测试 @ 200% | 149 项；0 失败、0 错误、0 跳过 |
| Android 仪器测试 @ 100% | 149 项；0 失败、0 错误、0 跳过 |
| JVM 单元测试 | 174 项；2026-10-01 复跑，0 失败、0 错误、0 跳过 |
| Android 仪器测试 @ 三键导航 | 154 项；系统字号 100%；0 失败、0 错误、0 跳过 |
| Lint | 0 errors、12 warnings、2 hints；warning/hint 为现有 target SDK /依赖提示、资源目录和 KTX 建议，本轮未扩大范围升级依赖 |
| 构建契约 | PASS：Application ID、最低 SDK、无 `INTERNET` 权限、应用名称 |
| Debug APK | `env-check/app/build/outputs/apk/debug/app-debug.apk`；40,767,785 字节；SHA-256 `0C1B10BB92431CD4716C2BFF40D87454E818FE6A55E6128E1AFAD226152A49D6` |

执行命令：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
.\scripts\verify-build-contract.ps1
```

字号或导航模式变更前读取原系统值，命令以 `try/finally` 恢复并复核。设备仪器结果在 `env-check/app/build/outputs/androidTest-results/connected/debug/TEST-NTH-AN00 - 12-_app-.xml`；该文件当前记录 2026-10-01 的三键导航补验结果（154 项），150% 的 154 项结果保留在当次命令输出及本报告记录中。Lint 报告在 `env-check/app/build/reports/lint-results-debug.txt`。自动化使用合成测试数据；私密页仍保持 `FLAG_SECURE`，没有尝试截取私密内容。本轮没有保存新的整机视觉截图。

## E6 矩阵状态

| 矩阵格 | 状态 | 说明 |
| --- | --- | --- |
| MuMu / API 32 / 系统字号 100% | 已运行 | 全量仪器 149/149；JVM 174/174（2026-10-01 复跑） |
| MuMu / API 32 / 系统字号 200% | 已运行 | 全量仪器 149/149；包含首页排序/批量操作、跨皮肤待办、二级页、编辑器与输入法场景 |
| MuMu / API 32 / 系统字号 150% | 已运行（2026-10-01） | 该次 154 项全量仪器测试；0 失败、0 错误、0 跳过；测试后恢复并复核为 1.0 |
| MuMu / API 32 / 三键导航 | 已运行（2026-10-01） | 系统字号 100%；154/154；切换前模式 `2`，测试期间模式 `0` 且三键 overlay 生效；之后恢复并复核为手势模式 `2` |
| Android 10 / API 29 | 未运行 | 当前没有该 API 的运行设备或系统镜像 |
| Android 15 / API 35 | 未运行 | 当前没有该 API 的运行设备 |
| Android 16 / API 36 | 未运行 | 仅使用 compile/target SDK 36 不等同于 API 36 运行验收 |
| 小米 11 Pro 真机 / MIUI 或 HyperOS | 未运行 | 本轮没有连接真机；MuMu 结果不替代 OEM 验收 |
| 居中挖孔、水滴屏及真机切口 | 未运行 | 本轮没有这些真实屏幕形态；既有模拟切口证据按早期 E1/E2 报告记录，不扩展为本轮结果 |
| 真实分屏窗口、第三方输入法/候选栏变化 | 未运行 | 横屏用例不等同于自由窗体/真实分屏；自动化输入法不等同于小米实机输入法 |

## 150% 字号补验（2026-10-01）

- 设备：MuMu NTH-AN00，Android 12 / API 32，900 × 1600 px，240 dpi；导航模式 `2`（手势）。
- 测试前系统字号为 1.0；设置为 1.5 后运行完整 Android 仪器测试。测试过程通过 `try/finally` 恢复原值，结束后重新读取为 1.0。
- Android 仪器测试：154 项；0 失败、0 错误、0 跳过。此次测试套件比 2026-09-30 的 149 项多出后续加入的测试，因此 150% 行按本次实际 154 项记录，不把不同日期的套件数量混为一谈。
- 未发现需要修改布局或生产代码的字号回归。该结果覆盖 MuMu/API 32 的手势导航场景；三键模式另见下节，均不能替代真实设备及其他 API 验收。

## 三键导航补验（2026-10-01）

- 设备：MuMu NTH-AN00，Android 12 / API 32，900 × 1600 px，240 dpi；系统字号 1.0。
- 切换前读取 `secure.navigation_mode=2` 且手势 overlay 启用；测试期间启用三键 overlay，读取 `secure.navigation_mode=0`，并确认手势 overlay 已关闭。
- Android 仪器测试：154 项；0 失败、0 错误、0 跳过。完整执行 `:app:connectedDebugAndroidTest`。
- `finally` 中重新启用手势 overlay；结束后复核 `secure.navigation_mode=2`、手势 overlay 启用、三键 overlay 关闭；字号仍为 1.0。
- 未发现三键导航相关回归。此结果仅覆盖 MuMu/API 32 的模拟三键模式，不能替代 Android 10、真实小米导航栏或 OEM 手势实现验收；本轮未采集整机视觉截图。

## 2026-10-01 回归收尾

- 运行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`：BUILD SUCCESSFUL；JVM 174 项，0 失败、0 错误、0 跳过；Lint 0 errors、12 warnings、2 hints。
- 运行 `scripts/verify-build-contract.ps1`：Application ID、最低 SDK、无 `INTERNET` 权限、应用名称全部 PASS。
- Debug APK：`env-check/app/build/outputs/apk/debug/app-debug.apk`，40,767,785 字节，SHA-256 `0C1B10BB92431CD4716C2BFF40D87454E818FE6A55E6128E1AFAD226152A49D6`。
- 本轮只做测试和文档更新，未修改业务源码；Gradle 报告构建任务为 `UP-TO-DATE`，因此该 APK 是当前验证到的构建产物，不表示本轮产生了功能变更版安装包。

截至本次补验，MuMu/API 32 的系统字号 100%、150%、200% 均有全量仪器测试记录；100% 与 200% 为 2026-09-30 的 149 项记录，150% 为 2026-10-01 的 154 项记录。三键导航亦于 2026-10-01 以字号 100% 完成 154 项全量测试；JVM 174 项、Lint、构建契约及 APK 构建也已复跑通过。Android API 29/35/36、小米 11 Pro 真机、居中/水滴切口及真实分屏仍未验收，待相应设备实际可用后再逐项补测。本机 SDK 当前没有 Emulator 程序与 system-images；未下载新镜像。

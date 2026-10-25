# 2026-10-26 皮肤底图与 A4 600 DPI 导出清晰度验证记录

执行日期：2026-10-26（对应交接指南 P1「皮肤底图像素密度可能不足以支撑 A4 600 DPI」）

## 结论摘要

1. **渲染管线没有缺陷**。A4 600 DPI PNG 的皮肤背景是双线性放大（像素级亮度剖面证实：边缘为 ~25px 平滑渐变坡，无最近邻色块）；PDF 由 Skia 以接近原生分辨率嵌入底图（单页 1.9–2.9 MB 佐证），正文文字在 PDF 中是矢量、在 600 DPI PNG 中按 600 DPI 栅格化，均不受底图分辨率影响。
2. **真正的限制是底图原生分辨率**：1024×1536 铺满 A4（210mm）的物理上限约 **124 DPI**。A4 600 DPI PNG 将其放大 4.85 倍（每源像素≈4.85 输出像素），PDF 打印时同样受 124 DPI 封顶。屏幕预览/社交 PNG/长图（1080 宽级）≈1:1 无损。
3. **视觉损伤按皮肤类型分层**（见下表）：平滑纸纹/渐变型无碍；高频细节型（星点、花瓣、花蕊）在 A4 600 下有明显柔化，月下星河最严重（小星点融为光斑）。

## 证据方法

- 新增仪器测试 `SkinExportFidelityEvidenceTest`（androidTest）：固定样张笔记（标题+分级标题+8 行正文+引用+待办+分隔线），对 11 款皮肤分别走**真实导出管线**：屏幕预览绘制器（1080×2400）、社交竖图渲染器、长图渲染器、A4 600 DPI PNG 渲染器、A4 PDF 渲染器（另以 PdfRenderer 按 300 DPI 栅格化）。
- 每种格式在页面右边距条带（纯底图区）取同一物理尺度（约 12.7mm 见方）裁剪，归一化到 512×512 后计算亮度梯度能量，并保存裁剪图与整页预览。
- 完整证据（134 个文件，含各格式整页输出与全部裁剪）：`D:\anan\outputs\AnAnNotes-skin-fidelity-20261026\`；仓库内存档：`assets/2026-10-26-skin-fidelity/`（4 张联络表 + CSV + 关键对照图）。
- 测试经 `am instrument` 直接执行（注意：`connectedDebugAndroidTest` 结束会自动卸载应用并删除应用外部目录中的产物，证据需在卸载前拉取）。

## 每款皮肤的 A4 600 DPI 判定

| 皮肤 | 底纹类型 | A4 600 视觉损伤 | 判定 |
|---|---|---|---|
| 极简白 minimal-white | 平滑纸纹 | 无 | 保持现状 |
| 米白纸张 warm-paper | 纸张纤维 | 无 | 保持现状 |
| 薄荷清新 mint-green | 平滑渐变 | 无 | 保持现状 |
| 雾蓝潮汐 misty-tide | 水彩渐变 | 无（渐变本就柔和） | 保持现状 |
| 月下星河 moonlit-sky | 星点/星河 | **重**：小星点融化成光斑 | 优先重制 |
| 纸笺散花 sakura-petal-paper | 花瓣+纸纹 | **重**：花瓣边缘涂抹感 | 优先重制 |
| 桂香笺 osmanthus-note | 细碎花蕊 | 中：花蕊细节丢失 | 建议重制 |
| 竹影清风 bamboo-shadow | 竹影剪影 | 中：叶片边缘柔化（剪影本就软边缘，可辩解） | 建议重制 |
| 茶花信笺 camellia-letter | 花朵+叶 | 轻：花瓣边缘轻微柔化 | 可选 |
| 南国银杏 southern-ginkgo | 银杏叶 | 轻：叶脉轻微模糊 | 可选 |
| 云栖紫藤 wisteria-cloud | 花瓣+云雾渐变 | 轻：花瓣边缘轻微柔化 | 可选 |

PDF（300 DPI 栅格化对照）：各皮肤均与源图接近（能量比 0.75–0.9），星点/花瓣在 PDF 中同样受 124 DPI 封顶但视觉好于 600 PNG 的 4.85 倍上采样。

## 度量数据要点（energy512，512 归一化裁剪的亮度梯度均值）

跨格式绝对值不可直接比（裁剪原始像素数不同导致归一化平滑程度不同，且不同格式中心裁剪几何使采样区域略有差异），趋势结论以视觉对照为准。原始数据见 `fidelity-metrics.csv`。

| 皮肤 | 源图 | A4 600 | PDF 300 |
|---|---|---|---|
| moonlit-sky | 2.00 | 1.75 | 1.68 |
| sakura-petal-paper | 3.81 | 1.90 | 3.37 |
| bamboo-shadow | 5.67 | 8.39* | 5.05 |
| minimal-white | 0.33 | 0.32 | 0.30 |

*竹影 A4 数值偏高源于采样带落在了叶片主体（内容差异），非伪影；已用亮度游程分析排除最近邻块状伪影（全图最锐边缘为平滑渐变、游程 1–3px）。

## 策略选项（待决策）

| 方案 | 内容 | 预期效果与代价 |
|---|---|---|
| A. 维持现状 | 接受 A4 导出中底图 124 DPI 柔化，发布文案如实说明 | 零成本；笔记文字质量不受影响（PDF 矢量/PNG 600 DPI） |
| B. 重点重制（推荐） | 月下星河、纸笺散花重制为 2048×3072（248 DPI）；桂香笺、竹影清风视情况跟进；格式 PNG→WebP | 7 款平滑/轻损皮肤不动；APK 增量约 +2–8 MB（WebP 有损 q90）或 +8–15 MB（无损）；需从原始创作流程重新生成（插值放大不增加细节） |
| C. 全量 2048 重制 | 11 款全部 2048×3072 WebP | 全部达到装饰背景优良打印标准（248 DPI）；APK 增量约 +8–15 MB（q90）/ +20–35 MB（无损） |

技术注记：`drawable-nodpi` 下 WebP 由 `BitmapFactory.decodeResource` 透明解码，`NoteSkinBackgroundPainter` 无需改动；换格式只需替换资源文件。WebP 体积需实测验证（本记录中的估算基于常见压缩比，实现时应以实测为准）。

## 复现

```powershell
# 模拟器就绪后（am instrument 方式，避免 Gradle 卸载导致证据丢失）
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.anan.anannotes.feature.export.SkinExportFidelityEvidenceTest com.anan.anannotes.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.anan.anannotes/files/skin-fidelity-20261026/. <本地目录>
```

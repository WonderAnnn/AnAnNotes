# 安安简记 V1 离线 OCR 选型与数据边界

日期：2026-10-01  
状态：V1 基线已选；设备验收仍进行中  
关联实施计划：[私密导出、离线 OCR、加密备份与设备验收](../plans/2026-10-01-private-export-ocr-backup-device-implementation.md)

## 决策

V1 使用 Tesseract 5.5.1（通过 Tesseract4Android 4.9.0）和 `tessdata_fast` 的简体中文 `chi_sim`、英文 `eng` 模型。引擎与模型随 APK 安装；运行识别不需要联网服务。这里是当前产品基线，不代表所有手机、字体、拍摄质量或语言混排场景都已达到最终识别质量。

模型和引擎分别采用 Apache-2.0 许可；随发行包保留对应许可和第三方声明。上游资料：[Tesseract4Android 4.9.0](https://github.com/adaptech-cz/Tesseract4Android/releases/tag/4.9.0)、[tessdata_fast 许可](https://github.com/tesseract-ocr/tessdata_fast/blob/main/LICENSE)、[Tesseract 许可](https://github.com/tesseract-ocr/tesseract/blob/main/LICENSE)、[官方语言数据说明](https://github.com/tesseract-ocr/tessdoc/blob/main/Data-Files.md)。发布前仍须核对实际 APK 中的 notices 与依赖清单。

## 候选比较

在同一 MuMu Android 12 / API 32 设备、同一合成中文短文图像上做了 `tessdata_fast` 与 `tessdata_best` 对照。两者均得到 4 行、约 65 个非空白字符，单样例 CER 均为 0.031746；该样本太小，不能据此推断普遍准确率。

| 指标 | `tessdata_fast` | `tessdata_best` |
|---|---:|---:|
| 两种语言模型总量（简中 + 英文） | 6,582,244 B | 28,478,024 B |
| 冷启动初始化 | 177 ms | 233 ms |
| 热识别中位耗时 | 250 ms | 1,350 ms |
| 进程 PSS 样本 | 226,203 KB | 238,994 KB |
| 本样例 CER | 0.031746 | 0.031746 |

因此 V1 选择 `fast`：在当前唯一语料上准确率没有可见损失，热识别约快 5.4 倍、双语言模型总量约小 4.3 倍。当前正式 app assets 中两份文件大小分别为 `chi_sim` 2,469,156 B、`eng` 4,113,088 B，合计 6,582,244 B；`best` 保留为仅 instrumentation 测试使用的候选，不打入正式 app assets。上述时间与 PSS 仅用于相同设备、同次实验内的相对比较，不能作为目标手机性能承诺。

## 随包模型完整性

APK 源树中的模型基线 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `chi_sim.traineddata` | `A5FCB6F0DB1E1D6D8522F39DB4E848F05984669172E584E8D76B6B3141E1F730` |
| `eng.traineddata` | `7D4322BD2A7749724879683FC3912CB542F19906C83BCC1A52132556427170B2` |

任何模型升级都要重新核对上游来源、许可、SHA-256、APK 增量、冷/热耗时、PSS，并复跑同一语料和加入的真实授权语料；不可只替换权重文件。

## 导入与隐私边界

- 三种用户选择保持独立：仅插入图片、插入图片并识别、只识别文字。普通 OCR 图片先持久化，之后创建可恢复的离线作业；识别成功后插入独立 `OCR_TEXT` 块，保留归一化后的原始换行，文本可编辑并参与普通搜索。
- 进度显示任务阶段（准备模型、识别、保存结果等），不是 Tesseract 内部逐 token 推理百分比。取消或失败不删除用户原图；失败可重试，不提交半截文本。
- 私密 OCR 不使用 WorkManager、普通暂存目录、普通预览缓存或普通仓库。它只在当前私密授权态读取加密附件，在内存中识别，完成后写回加密私密快照；暂存解密字节在 `finally` 清零。锁定时取消任务并清除内存态进度/结果。
- 所有分享、系统图片选择和文档读取均由用户主动发起；不增加 `INTERNET` 权限。需在最终合并 Manifest 和实际发布 APK 上复核。

## 已执行验证与限制

- 普通 OCR 作业规则、OCR 块插入、持久暂存、仓库集成和搜索测试通过；纯文字 OCR 源图使用 `noBackupFilesDir`，以便进程终止后继续恢复，历史 cache 暂存仍可读取。
- 2026-10-04 API 29/35/36 AVD 的 OCR instrumentation 包各 8/8 通过。新增覆盖非 UUID 备份块 ID 不阻断启动恢复、WorkManager 为已删除笔记退出时清理持久化源图，以及跨进程重排队；暂存文件名仍严格限制 UUID，非 UUID 仅在查找/删除边界安全忽略。
- MuMu 离线引擎 instrumentation 2 项通过，覆盖文件路径与内存字节路径，以及私密加密附件到识别结果再加密持久化的闭环；私密原始语料未在应用文件中发现明文。
- 导入模式 bottom sheet instrumentation 1 项通过；私密编辑器 UI instrumentation 7 项通过。
- MuMu `NTH-AN00` / Android 12 / API 32 的既有仓库与离线引擎测试，以及 API 29/35/36 AVD 的 8 项 OCR 恢复/引擎定向测试已执行。小米 11 Pro、QQ/微信实际分享、旋转/低清/长图/多字体等授权样本仍未完成；本次基准不是正式 OCR 准确率验收，也不代表 API 29/35/36 全量 UI 套件通过。

验证报告和每次回归的精确命令、设备标识应另记在 `docs/superpowers/verification/`，避免把候选决策文档误当作全矩阵通过证明。

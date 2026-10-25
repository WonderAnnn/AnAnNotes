# 私密导出、离线 OCR、加密备份与设备验收实施计划

日期：2026-10-01  
状态：执行中；本计划落实已批准的 V1 产品与技术设计，不替代总体设计或独立安全设计。

## 范围与硬约束

- 私密导出、OCR、备份恢复必须继续使用相互隔离的存储、授权、事件、记录和 UI 路径。不得把私密 ID、正文、图片、OCR 文本、文件名、数量或 URI 写入普通 Room、FTS、缓存、草稿、日志、普通导出服务/历史或存储概览。
- 私密导出每次重新输入私密模块密码；授权只可使用一次并短期有效。恢复密钥不是日常导出凭据。用户确认后输出普通文件，并明确提示“导出文件将不再受安安简记私密模块保护”。私密历史最多保留 100 项并仅在解锁态可见。
- OCR 必须完全离线、模型随应用安装、不增加 `INTERNET` 或其他非必要权限。图片先持久化；识别异步；状态进度表示实际阶段/可观测工作，不伪造推理百分比。私密 OCR 只能在私密加密边界内解密/清理。
- 备份由 SAF 选择位置，独立密码保护；恢复先完整验证并展示预览，默认合并、稳定 ID 去重、显式冲突决策；任何失败都不能部分覆盖当前数据。私密有效载荷不能以明文落入普通缓存、暂存目录或普通数据仓库，必须恢复到私密存储并由目标私密数据密钥重新加密。
- 全包必须小于 300 MB；不可用仅 Debug 包体代替包含 OCR 模型的 Release 包验收。
- 自动化和模拟器结果按实际设备/API 记录。MuMu 不等于小米真机；无挖孔模拟器不等于异形屏实机。缺少设备时如实标为待验收。

## 工作区基线

- 工程：`D:\anan\env-check`；文档：`D:\anan\docs\superpowers`。
- 当前普通 TXT/Markdown/PDF/PNG 导出、普通导出记录和普通存储概览已完成；编辑页单条普通导出也已验证。普通 `OrdinaryExporter` 不得接收私密笔记。
- 私密加密快照、私密原图、私密笔记/待办/草稿/搜索、私密独立导出/加密历史、普通与私密 OCR 路径均已有实现。本轮审计发现私密导出密码再验证未共享解锁退避，现已修复并新增单测/界面测试；私密 MediaStore/SAF 已在 MuMu/API 32 验收，API 29 另完成私密 SAF seed/verify 与新密钥恢复。其他 DocumentsProvider、小米真机相册/文件管理器仍待验收。
- OCR 选型决定和模型哈希记录见[离线 OCR 决策](../specs/2026-10-01-anan-notes-offline-ocr-decision.md)。MuMu Android 12/API 32 已通过部分仓库、离线引擎和 Compose 验收；QQ/微信真实分享、旋转/低清/长图等授权语料与更广矩阵仍待执行。
- 基线设备为 MuMu Android 12/API 32；API 29/35/36 的 AVD 镜像已安装并建立可重复 AVD。2026-10-04 再次完成三 API 仪器全量回归：API 29 四分片合计 199 项（193 通过、6 跳过、0 失败/错误），API 35 与 API 36 各 199 项（194 通过、5 跳过、0 失败/错误）。API 29 多出的 cutout 用例在临时启用 `tall` 模拟切口后定向 1/1 通过，并已恢复原设置。此前报告的 6 个 API 36 UI 失败均在本轮三 API 全量中通过，但未定位旧失败根因。API 29 的 16 MiB Runtime 堆问题已通过只读快照 A/B 定位：显式加载原 `default_boot` 会复现 16 MiB/OOM；禁用快照冷启动则为 512 MiB，Argon2 探针通过。API 29/35 已新增高刘海/左上挖孔、320 dp、200% 字号、导航/主题及短横屏排序面板定向组合；D 阶段全形状/全组合矩阵仍待完成。详见[Android 环境验收记录](../verification/2026-10-01-android-environment-validation.md)及[本轮三 API 回归报告](../verification/2026-10-04-api29-35-36-full-suite-verification.md)。小米 11 Pro 当前未连接。F 盘此前约有 214 GiB 可用。
- 当前工程目录没有 Git 元数据，本轮沿用用户先前要求的当前会话内联执行，不初始化仓库、不覆盖或重置任何用户文件。

## 顺序与交付检查点

### A. 私密导出（本计划第一实现闭环）

1. 先写授权、边界、序列化、失败清理、历史裁剪和会话撤销测试。
2. 增加独立私密密码再认证，不替换/延长当前解锁会话，不把密码放进 grant；grant 绑定当前 `PrivateAccess`，单次消费、短时失效、锁定即失效。
3. 建立只接收 `PrivateNoteId`/私密快照的 `PrivateExporter`；不得依赖/调用 `OrdinaryExporter`。输出目标可以复用不知晓笔记内容边界的底层 SAF/MediaStore 文件写入器；渲染、事件、授权和历史仍留在私密模块。
4. 私密导出支持文档已列出的 PDF、A4 600 DPI PNG、长图/社交 PNG、TXT、Markdown；私密图片只在受控回调内解密供渲染，缓冲区及时清理。输出文件为明文，必须在最终发布前由用户确认。
5. 私密导出历史作为加密私密数据持久化，最多 100 项；解锁时可查看，锁定态、普通设置/历史/统计与日志均不可读到该历史。
6. 加入私密编辑器导出入口、单笔记格式选择、密码二次验证、明文提示和反馈；自动化覆盖正确/错误密码、取消、会话锁定/过期、媒体写失败和跨域不可达。
7. 定向 JVM/设备测试、Lint、构建、APK 安装冷启动后记录私密验收证据。

### B. OCR 候选评估与离线接入

1. 用同一固定语料对至少两个可离线随包分发的候选比较：简体中文、英文、数字、保留换行、低清/旋转/长图；记录模型/运行库许可、模型体积、ABI、冷启动、持续识别耗时、PSS/峰值内存、Release APK 增量。
2. 先确认引擎与权重许可和无运行时联网/统计行为，再选型；将证据写入 OCR 决策文档。若无候选在可接受体积、许可、离线和真实识别质量上达标，停止打包并报告阻塞，不以联网 SDK 替代。
3. TDD 实现 OCR 状态机、稳定作业 ID、持久状态、取消/重试幂等和百分比阶段；图片先保存后作业入队。成功插入保持原换行的独立可编辑 OCR_TEXT 块，之后才参与普通搜索；失败/取消保留原图且不留部分文本。
4. 普通和私密作业分别使用各自的仓库/密钥生命周期。锁定时暂停私密识别；不得把解密私密图像写入普通 WorkManager 输入、文件缓存或系统可备份位置。验证重启后恢复/取消策略。
5. UI 提供三种导入模式：“仅插入图片 / 插入图片并识别文字 / 只识别文字”；展示真实作业状态、动态进度条/百分比、取消与重试。图片原件与 OCR 块保持独立。
6. 覆盖分享/SAF/QQ/微信路径的 instrumentation；QQ/微信实体端验收只有真实设备操作后方可标记通过。

### C. 加密备份与合并恢复

1. 安全设计增量已写入[私密密码学与备份容器设计](../security/2026-09-27-anan-notes-private-crypto.md#7-加密备份容器与跨设备恢复2026-10-01-v1-设计增量)：版本化 `ANBK` 容器、Argon2id + AES-256-GCM 分块认证、抗篡改/截断/重排、独立备份密码和新私密库 DEK 语义、长度边界、双遍验证、私密直加密暂存与崩溃 journal。已明确 FINAL 使用下一个未使用 nonce 索引（空备份为 0）、4096 块/16 GiB 明文硬上限、逐操作最大容器长度、验证能力绑定头部和完整密文指纹；类型化记录层还须定义自己的数量/长度上限。
2. 先写失败测试：错误/篡改/截断/尾随数据、错误密码、重复 ID、当前/备份双向修改、私密内容路径、空间不足、取消/进程中断，且失败后源数据库、加密快照、附件引用及旧文件逐字节保持不变。
3. 全量范围为普通/私密笔记、图片、一级文件夹、待办、回收站和未完成草稿；不含导出历史/目标 URI、诊断日志、可重建缓存或设备权限。若目标尚未配置私密库，用户设置新私密密码并生成全新 DEK；已有目标私密库则先解锁并使用目标 DEK。普通和私密有效载荷有可审查的域标记与独立解密路径；绝不通过普通明文 staging 解包私密记录。
4. `inspect` 只产生短期、进程内、完整性验证的 `VerifiedBackup` 能力；展示新增笔记/图片/文件夹/待办数量与冲突，但不得在未认证状态揭示私密内容/数量。恢复前完成暂存、文件哈希/认证验证、冲突选择与目标空间检查，再通过数据库事务和文件提交协议原子应用；启动修复能回收未提交暂存。
5. 默认合并、稳定 ID 去重、冲突支持“保留当前 / 使用备份 / 保留两个版本”；恢复重试幂等。独立备份密码/SAF 流程不改变私密库登录凭据和恢复密钥。
6. 仅实现用户当前要求的备份/恢复；回收站的其他界面与任务仍按主计划单独执行，不趁机扩大范围。

### C 阶段续作：字段 schema、SAF 与恢复协调（2026-10-02）

实现依据为[安全设计第 7.1 节的类型化载荷 schema v1](../security/2026-09-27-anan-notes-private-crypto.md#71-容器与密码学)。本阶段按以下可独立验收的闭环顺序推进；不能把 ANBK 认证成功等同于语义数据有效，也不能在普通/私密两边都准备完成前发布恢复结果。

1. **C2b 类型化记录 codec**：在 `security/BackupRecordPayloadCodec.kt` 定义 folder/note/block/todo/draft/draft-block/asset 的不可变传输模型；实现精确字段顺序、显式 enum wire ID、严格 UTF-8、nullable/boolean 标志、有限浮点数、每域字段上限及精确 EOF 检查。路径字段不进入传输模型；Block/DraftBlock 只保存备份内 assetRef。附件字段采用元数据前缀 + 原始字节流，编码/解码沿用现有分块回调。
2. **C2c 全流关系校验**：在 `security/BackupRecordSetValidator.kt` 以 domain 隔离地登记 stable ID 和有界引用表；`finish()` 必须证明唯一 ID、父对象存在、块序连续且唯一、待办与 TODO block 文本/引用一致、资产引用和 SHA-256 匹配、草稿声明块数准确，并拒绝 orphan/悬空数据。校验完成之前只写私有 transaction staging，不返回预览。
3. **C3 SAF 容器 I/O 与 inspect**：在 `feature/backup/` 新增可替换的 SAF source/target adapter、应用私有随机密文暂存和 `BackupCoordinator`。创建端将 records writer 与 ANBK encrypt 以有界内存管道连接，成功后才完成 SAF 文档；取消/错误删除仅本次创建的文档。恢复端先把 SAF URI 复制到 no-backup 密文临时文件，再按安全设计两遍认证；第二遍完整解码并运行 C2c 后才生成进程内 `VerifiedBackup` 与预览，进程重启后能力失效。
4. **C4 冲突和目标变更保护**：预览按 stable ID 比对文件夹及完整 note aggregate（note、blocks、todos、引用附件 SHA-256），冲突选择为保留当前/使用备份/保留两个。冲突决策必须与本次 VerifiedBackup 及 inspect 时目标 revision 绑定；目标发生变化、遗漏/重复/伪造决策时拒绝应用并要求重新 inspect。保留两个版本时复制 note 及子块/todo，重映射全部子 ID 与 assetRef；folder 同名副本使用确定性的“（恢复副本 N）”名称并重映射仅备份侧引用。
5. **C5 普通/私密双域 prepare 与恢复 journal**：普通原图先在 transaction-owned 文件暂存区按流式长度/digest 校验，预览图可丢弃并在恢复后重建；私密资产和快照只在目标 DEK 下流式加密到私密 staging，不能先写入普通 staging。无私密库时设置新密码并新建 DEK；已有库时先解锁并复用目标 DEK。journal 只含随机事务 ID 与阶段，不含 domain 数量、ID 或路径；失败清除本事务创建物并保持原数据。启动时必须先完成回滚/幂等前滚修复，再允许普通和私密页面读取恢复涉及的数据。
6. **C6 设置入口与仪器验收**：在设置的数据与存储分组加入加密备份/恢复入口；创建通过 SAF 选目录并重复确认独立备份密码，恢复选择 `.anbk` 后只在认证完成时显示新增数量/冲突。目标私密库的创建/解锁仍使用私密模块独立凭据 UI；不把私密标题、数量、URI 或冲突细节写普通历史、Room、日志或 Bundle。覆盖创建/认证/预览/冲突三种选择/恢复/取消/低空间/损坏/恢复重启/私密新旧 DEK 的单测与 MuMu 仪器测试。

本轮按照用户先前选定的当前会话内联方式执行；工作区没有 Git 元数据，不初始化仓库、不重置或覆盖已有用户文件。每个 C2–C6 闭环均先写失败测试，再实现并验证；尚未接入代码的闭环继续保持未完成。

### D. API/异形屏/真机验收

1. 重查 SDK 根目录、可用磁盘、AVD 工具与镜像包清单；安装 Android API 29、35、36 的必要 x86_64 模拟器镜像/工具。若使用第三方镜像，逐个下载包按此前批准的 Android 官方 SHA-1 清单核对，校验失败即删除该临时包并停止使用。
2. 为 API 29、35、36 至少建可重复 AVD 配置并运行关键首页/编辑/私密/选择器/导出/OCR/备份测试；保存实际型号、API、屏幕 dp、cutout 模拟覆盖、导航模式、字号与命令结果。
3. 覆盖刘海/左上挖孔/居中挖孔/水滴安全区、320/360/400 dp 窄宽与极短横屏、手势/三键导航、100/150/200% 字体、深浅主题、输入法弹出/隐藏/切换、权限/URI 撤销、低空间与长任务生命周期。
4. MuMu 和 AVD 完成后重新连接小米 11 Pro，记录 MIUI/HyperOS、Android API、分辨率/显示缩放/导航/键盘，执行真实系统 SAF、相册、QQ/微信、截屏许可规则、Argon2 性能/OCR/备份恢复验证。当前小米未连接时不得将本节标为全部通过。

## 验证命令基线

每一闭环至少执行：

```powershell
Set-Location D:\anan\env-check
\.\gradlew.bat :app:testDebugUnitTest
\.\gradlew.bat :app:lintDebug
\.\gradlew.bat :app:assembleDebug
\.\gradlew.bat :app:connectedDebugAndroidTest
.\scripts\verify-build-contract.ps1
```

并在可用设备执行对应 instrumentation、安装保数据/冷启动，以及文件管理器/系统相册可见性检查。发布候选另执行 Release 构建、合并 Manifest 检查和总 APK 包体检查。最终报告区分“代码/单元测试通过”“MuMu/API 32 自动化通过”“API 29/35/36 AVD 通过”和“小米真机人工验收通过”。

## 当前进度

- C1（ANBK 流式密码容器）已实现：编码/完整验证/事务消费接口已实现，验证与消费两遍都逐块执行 AES-GCM，消费阶段检查头部及整容器 SHA-256 后才提交接收器，异常时调用 abort。已通过定向 JVM 测试并补强独立安全复审发现的断言缺口。此项本身不等于完整备份。
- C2a（ANRD 有界记录帧与增量解码）已实现：普通/私密 domain、7 类记录、分层 payload 上限、1,000,000 帧/16 GiB 总上限；附件流式传递，ANBK 解密块直接进入 decoder，不写明文总暂存。两层终止和认证检查成功后才提交接收器；异常调用 abort。
- C2b/C2c（2026-10-02）已实现并有测试：类型化字段 codec 覆盖 folder/note/block/todo/draft/draft-block/asset，包含显式 wire enum、严格 UTF-8、有限数值/字段长度和 EOF 检查；全流 validator 检查 domain-scoped ID 唯一性、外键、块序、待办与 TODO 块一致、附件摘要/引用和草稿块数。`BackupRecordSnapshotMapper` 已映射 Room 普通记录、普通崩溃草稿及已解锁私密快照；只导入原图，预览缓存与导出历史排除在外，私密附件识别 MIME 取文件签名。
- C3/C4 服务层（2026-10-02）已实现：SAF 创建/读取 `.anbk`，输入以密文复制进随机 no-backup 暂存；认证和第二遍类型化校验完成后才产生预览能力。记录集生成文件夹及整条笔记聚合指纹（含块、待办和附件摘要）；比较新增/相同/冲突，冲突选择绑定本次 inspect 会话与目标内容快照。恢复协调器在一次性消费能力后要求目标先准备事务，再流式解码；准备或消费失败会 abort 并清除本次暂存。恢复目标实现须在自己的持久事务内再次核对 revision。
- C5/C6 当前状态（2026-10-02）：双域 typed restore target 已接入 Room、普通草稿/原图事务、私密 vault/附件事务和持久 journal；设置页已提供 SAF 创建/选择入口、备份验证预览、按普通/私密域分别选择冲突策略、目标私密库解锁或为新设备设置新密码/可选恢复密钥。启动恢复门、入口导航、临时私密访问清理及 SAF 持久目录授权的替换/离页释放均已接通。MuMu 上已通过系统 DocumentsUI 实际完成 SAF 选择目录→生成 `.anbk`→重新选择文件→密码认证和内容预览→恢复；空备份与包含 1 条普通笔记的备份均完成往返，后者预览显示 1 条笔记且恢复完成反馈一致。剩余验收为取消/低空间/损坏文件/进程中断恢复注入、私密域数据的完整 SAF UI 往返，以及小米 11 Pro 真机验证；因此 C5/C6 操作主路径已在模拟器接通并冒烟验证，整体验收仍未完成。
- 2026-10-02 C5 子步骤：`PrivateStore.writePrivateAssetFromStream` 已实现，将私密附件按 64 KiB 上限流式做 AES-GCM 加密，边读边验长度和 SHA-256，仅在验证成功后原子发布；可写入 UUID 绑定的事务目录，私密引用也绑定事务上下文，回滚 API 只删除经 UUID 校验的指定事务目录。针对解密往返、块大小、磁盘无明文、摘要不匹配不发布、事务目录清理及既有图片读取/删除流程的定向 JVM 测试通过。此能力还没有连入 restore target、双库提交 journal 或新库 DEK 配置流程。
- 2026-10-02 C5 续作：新增固定长度、带 CRC32 的内容无关恢复 journal（仅随机事务 UUID 与 phase）；Room v8 增加 `backup_restore_commits`，使提交标记与普通数据能够处于同一个事务。新增恢复协调器：Room 标记存在时前向确认并清理旧私密快照副本；无标记时回滚普通原图组、私密 vault 快照/新密钥及私密图片组；若 journal 声称已提交而 Room 无标记则失败关闭。`MainActivity` 现在先显示启动恢复门，再创建任何 Room-backed 页面/VM；恢复失败会阻止打开笔记并提供重试，不向日志记录恢复异常细节。
- 同日较早阶段记录：普通原图已补流式事务目录导入，私密 vault 暂存 API 支持旧库回滚与无库时生成新 DEK。当时尚未接入真实 typed restore target 和恢复 UI；该阶段状态已由下方 C5/C6 接通记录更新。
- 同日较早阶段记录：普通原图已补流式事务目录导入，私密 vault 暂存 API 支持旧库回滚与无库时生成新 DEK。当时尚未接入真实 typed restore target 和恢复 UI；该阶段状态已由后续 C5/C6 接通记录更新。
- 2026-10-02 本轮新鲜阶段回归：强制重跑 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --rerun-tasks` 全部成功；JUnit XML 汇总 307 项、0 失败、0 错误、0 跳过。Lint 为 0 errors / 15 warnings / 2 hints；Android instrumentation 源码编译成功。构建契约脚本通过 application ID、minSdk 29、无 INTERNET 权限及应用名；Debug APK 74,376,961 bytes。当前 `adb devices -l` 列表为空，本轮未运行 connected instrumentation、未安装冷启动；MuMu与小米 11 Pro 的设备验收仍待执行。
- 2026-10-02 阶段回归：`:app:testDebugUnitTest` 共 304 项，0 失败/错误/跳过；`:app:lintDebug`、`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin` 与 `scripts/verify-build-contract.ps1` 通过（application ID、minSdk 29、无 INTERNET 权限、应用名）。Debug APK 74,838,543 bytes。当前 ADB 未发现在线设备，因此本轮未运行 connected instrumentation、未安装冷启动；MuMu 与小米 11 Pro 的设备验收仍待执行。
- 2026-10-02 本轮阶段验收：全量 JVM 单测 290 项，0 失败/错误/跳过；MuMu/NTH-AN00 Android 12/API 32 仪器测试 174 项、0 失败、1 项因设备无异形屏能力跳过；`:app:lintDebug`、`:app:assembleDebug` 及构建契约检查通过（application ID、minSdk 29、无 INTERNET 权限、应用名）。MuMu 显示尺寸 900×1600、密度 240 dpi。Debug APK 75,391,187 bytes。API 29/35/36 AVD、Xiaomi 11 Pro 真机及 SAF 文件管理器可见性仍待验收。
- 2026-10-01 本轮最终阶段验收：JVM 单测 246 项、0 失败/错误/跳过；MuMu Android 12/API 32 instrumentation 174 项、0 失败、1 项因无异形屏能力跳过；Lint 0 errors / 15 warnings / 2 hints；Debug 与 unsigned Release 构建通过，Release APK 63,657,850 bytes，Debug APK 75,372,143 bytes；构建契约检查通过（application ID、minSdk29、无 INTERNET、应用名）。Debug APK 在 MuMu 安装并打开至 MainActivity。MuMu 不是小米真机；API 29/35/36 AVD、Xiaomi 11 Pro、SAF/MediaStore 真机可见性仍未验收。
- 2026-10-01 回归：全量 JVM 单测 226/226 通过；MuMu Android 12/API 32 仪器测试 174 项、0 失败、1 项因模拟器无异形屏而跳过；Lint 0 errors / 15 warnings / 2 hints；Debug 和 unsigned Release 构建、构建契约通过。Debug APK 75,372,143 bytes，Release APK 63,625,082 bytes；Release 包小于 300 MB。MuMu 不是小米真机，API 29/35/36 AVD 及 Xiaomi 11 Pro 仍未验收。
- 2026-10-02 C5/C6 接通：新增 `AppBackupRestoreStore`，把已验证的类型化记录落到 Room、普通草稿、普通原图和私密 vault；私密图片仅流式加密入本次事务目录，目标私密库缺失时以新密码及新 DEK 准备封套，已有库则经认证后沿用目标 DEK。普通草稿发布也加入同一恢复 journal 的提交/回滚和启动前向清理；Room 行、搜索 FTS 与提交标记同事务提交。恢复前会再次比对目标指纹；按域冲突选择不向 UI 暴露私密记录 ID。普通/私密附件、回收站、待办及未完成草稿按类型化 schema 恢复。
- 2026-10-02 C6 UI：设置 →「加密备份与恢复」接通 SAF 目录/文件选择器、独立备份密码及确认、私密库密码退避验证、认证后记录摘要、分域冲突策略、未配置私密库时新密码/可选恢复密钥及完成状态。恢复密钥须复制并确认后才允许提交；ViewModel 清理时会释放 inspect 能力和临时私密访问密钥；更换保存目录时释放旧 SAF 持久授权，页面退出时释放当前授权。错误文案不显示标题、私密 URI 或内部异常详情。
- 2026-10-02 C5/C6 回归：`:app:testDebugUnitTest` 318 项全部通过；MuMu NTH-AN00 / Android 12(API 32) instrumentation 共 180 项、0 失败、0 错误、1 项因设备不支持异形屏跳过（179 项执行通过）；`:app:lintDebug` 0 errors / 16 warnings / 2 hints；Debug 与 unsigned Release 均构建成功，Android instrumentation 源码编译成功。Debug APK 75,501,199 bytes，Release APK 64,116,602 bytes；构建契约检查在 Windows PowerShell 5.1 与 PowerShell 7 均通过 application ID、minSdk 29、无 INTERNET 权限及应用名检查。新增 SAF 授权替换/关闭释放单测、入口导航、“目标已有同名文件夹时恢复草稿仍映射到正确文件夹”设备回归均通过。MuMu 上另用最新 APK 选择 SAF 目录并退出页面，`dumpsys activity permissions` 确认该 URI 的 `persisted=0x0`；页面运行期间仍由 Activity 持有的临时授权不等于持久授权。另在 MuMu Android 12 系统 DocumentsUI 中实测空备份及 1 条普通笔记的 SAF 创建、文件重选、认证预览、恢复往返；备份文件分别为 180 B 与 449 B。取消/低空间/损坏/进程中断注入、私密数据 SAF UI 往返和 Xiaomi 11 Pro 真机验收仍未执行。
- 2026-10-03 C5/C6 故障与私密域阶段验收：损坏/篡改/截断/尾随数据/错误密码的既有 ANBK 测试及私密 staging 清理测试通过；新增 ENOSPC staging 回滚测试，确认普通/私密事务目录及 journal 清理、旧文件逐字节保留；新增 journal 持久化后重建 recovery 对象的中断回滚测试。MuMu NTH-AN00 / Android 12(API 32) 上以两阶段 instrumentation + 主机侧 `am force-stop` 真实杀进程，再冷启动 `MainActivity`，确认未提交恢复 journal 和普通图片事务暂存被清理。通过 DocumentsUI 实际备份含 1 条私密笔记和 1 张私密加密图片的库；随后仅清除测试 application ID 的应用数据（SAF 文件保留），重新选择备份、输入独立备份密码并认证预览（私密笔记 1、附件 1），为新库设置新密码后恢复；独立 instrumentation 解锁新库并解密/解码 8×8 PNG，验证尺寸和像素值完整。单测 `:app:testDebugUnitTest` 321 项、0 失败/错误/跳过；MuMu `:app:connectedDebugAndroidTest` 184 项、179 通过、5 跳过、0 失败/错误；需阶段参数的冷启动和私密 SAF 种子/验证测试另用直接 instrumentation 命令执行并通过。整套设备回归中曾发现备份入口 UI 测试未滚动到首屏外的 SAF 文件选择按钮，补上 `performScrollTo()` 后单测和完整 instrumentation 复跑通过。低存储通过受控 ENOSPC 异常路径模拟，未物理填满模拟器存储。SAF 备份、MuMu 共享存储中的临时截图及测试私密数据已清理；工作区 `build/` 下仍保留 4 张 UI 验证截图。Xiaomi 11 Pro 真机及 API 29/35/36 AVD 仍待验收。
- 2026-10-03 A 私密导出阶段验收：新增 `PrivateExportMediaStoreIntegrationTest`，使用临时隔离的加密私密库创建含私密原图的笔记，经密码重新认证和生产导出工厂实际渲染/写入 MuMu MediaStore；逐页验证方形 PNG 为 1080×1080、`image/png`、Pictures 导出目录、`IS_PENDING=0`、解密原图像素，以及私密历史成功且标题/历史未进入普通 Room。真实方图按内容生成两页，测试按分页语义逐页核验；测试产生的 QA 文件已全部删除。新增 SAF 与 MediaStore 的 ENOSPC 单测，确认稳定“空间不足”错误和未完成文档清理。全量 JVM 单测 323 项、0 失败/错误；MuMu NTH-AN00 / Android 12(API 32) instrumentation 185 项、180 通过、5 跳过、0 失败/错误；Lint 0 errors / 16 warnings / 2 hints，Debug APK 75,501,199 bytes；`assembleDebug`、AndroidTest Kotlin 编译、构建契约检查（application ID、minSdk 29、无 INTERNET 权限、应用名）通过。低存储仍为受控 ENOSPC 注入，SAF 私密导出的真实 DocumentsUI 往返及 API 29/35/36、小米真机验收待完成。
- 2026-10-03 A 私密 SAF 设备集成补充：新增 `PrivateExportSafIntegrationTest`，通过 MuMu Android 12/API 32 的真实 ContentResolver / `DocumentsContract` 调用生产 `PrivateExportServiceFactory` 与 `SafExportFileStore`。Markdown ZIP 往返验证正文、加密原图解密后的原始 PNG 字节、`application/zip` Provider 文件及重复导出不覆盖；写入流故障验证只删除本次半成品，记录失败仅进入私密加密历史，普通 Room/普通导出历史无私密标题。定向 instrumentation 2/2 通过。此用例直接调用测试 DocumentsProvider，**未**覆盖系统 DocumentsUI 中用户手动浏览/选择目录的 ActivityResult 往返，故 A 项仍保持未完成，不能据此标记真机 SAF UI 验收通过。
- 2026-10-03 本轮回归：`:app:testDebugUnitTest --rerun-tasks` 328 项、0 失败/错误/跳过；`:app:lintDebug`、`:app:assembleDebug`、全量 `:app:connectedDebugAndroidTest` 均成功；MuMu NTH-AN00 / Android 12(API 32) instrumentation 190 项、0 失败/错误、5 跳过（4 项需独立进程阶段参数、1 项模拟器不支持异形屏）；Lint 0 errors / 17 warnings；Debug APK 75,501,199 bytes；`scripts/verify-build-contract.ps1` 的 application ID、minSdk 29、无 INTERNET 权限、应用名均通过。此项测试通过不替代待完成的真实 DocumentsUI 手动选目录往返、API 29/35/36 AVD 或 Xiaomi 11 Pro 验收。
- 2026-10-03 A 私密导出 DocumentsUI 往返补验：新增 `PrivateExportDocumentsUiRoundTripTest`，通过实际 Android 系统 `DocumentsUI` 浏览 `Download`、新建唯一测试子文件夹并授权。MuMu Android 12/API 32 返回应用后触发 `ON_STOP` 自动锁定；页面显示继续导出提示；测试重新解锁、再次输入独立导出密码并确认明文提示，然后通过所选 SAF tree 查询 ZIP 文档并解压校验 Markdown 正文。首次尝试直接授权 `Download` 根目录时，系统明确拒绝并提示为保护隐私需选其他文件夹；测试据此改为用户可操作的专用子目录，符合系统限制。成功测试用 SAF 删除本次生成文件夹；先前失败尝试留下的唯一临时 ZIP/文件夹也已清理。全量验证命令 `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain` 成功：JVM 328/328，0 失败/错误；MuMu instrumentation XML 191 项、186 执行通过、5 跳过、0 失败/错误（4 个需独立进程阶段参数，1 个设备不支持异形屏）；Lint 0 errors / 17 warnings / 2 hints；Debug APK 75,869,379 bytes；构建契约脚本的 application ID、minSdk 29、无 INTERNET 权限及应用名检查通过。该测试直接从 DocumentsUI tree 验证 SAF 文档内容，不据此推断 MediaStore 索引或小米文件管理器行为。MuMu 非小米真机；API 29/35/36 AVD、第三方 Provider、重启后的授权策略及 Xiaomi 11 Pro 真机可见性仍待验收。
- 2026-10-03 A 私密 DocumentsUI 专项稳定性复跑：一次复跑观察到 SAF Provider 查询列表在相邻两次查询间短暂不一致。测试现保留轮询条件中找到的同一文档快照供后续 ZIP 读取，并让失败信息报告实际 ZIP 条目；最终态重新编译 AndroidTest 并定向复跑 `PrivateExportDocumentsUiRoundTripTest`，1/1 通过。该次仅调整测试读取/诊断，不改生产导出行为；测试生成目录由 SAF finally 清理。
- 2026-10-03 阶段回归复验：全量命令 `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain` 成功；随后 `:app:testDebugUnitTest --rerun-tasks` 新鲜执行 328 项，0 失败/错误。MuMu NTH-AN00 / Android 12(API 32) 的全量 instrumentation 计划 191 项，5 项跳过、0 失败/错误；跳过项包括需要显式阶段参数的冷启动/私密 SAF 夹具及设备不支持异形屏的用例。冷启动夹具已另行按 seed → 仅 force-stop 测试应用 → verify 顺序执行，两阶段各自通过；验证确认启动 MainActivity 前清理未提交 journal 与图片暂存。最终 `:app:assembleDebug` 成功，Debug APK 75,869,467 bytes；Lint 0 errors / 17 warnings / 2 hints；`scripts/verify-build-contract.ps1` 的 application ID、minSdk 29、无 INTERNET 权限、应用名检查全部通过。测试设备仍只有 MuMu（900×1600、240 dpi），Xiaomi 11 Pro 与 API 29/35/36 AVD 未连接/未验收；因此本轮结果不代表真机或多 API 通过。
- 2026-10-03 E3 与 API AVD 回归：API 29、API 35 AVD 各运行 5 项编辑页重点测试（IME 工具栏贴边/复位、图片多选操作栏、待办插入/回车/连续新增），均通过；API 36 完整编辑页类 31/31 通过。新插入并获焦的待办块接入 `BringIntoViewRequester`，在软键盘出现时将输入框滚入可视区域；测试等待首页 Room 列表收敛，并分别等待 IME 显示后的 Compose 贴顶和隐藏后的原位恢复，避免抢读旧布局。私密编辑工具栏仍按用户选择保持单行横向滚动，引用工具测试先实际滑动工具栏再点击。
- 同日 API 36 全量验收：`:app:connectedDebugAndroidTest` 的 JUnit XML 为 192 项，188 通过、4 跳过、0 失败/错误；4 项是需分阶段执行的冷启动恢复与私密 SAF 种子/验证 fixture。API 29/35 本轮仅执行上述 5 项 E3 定向矩阵，不代表完整 API 矩阵；异形屏、字体缩放、导航模式、完整导出/OCR/备份跨 API 仍需补验。
- 同日新鲜静态/构建回归：`:app:testDebugUnitTest --rerun-tasks` 330/330 通过；`:app:lintDebug` 为 0 errors / 17 warnings / 2 hints；`:app:assembleDebug` 与 AndroidTest Kotlin 编译成功。Debug APK 为 76,341,443 bytes；`scripts/verify-build-contract.ps1` 验证 application ID、minSdk 29、无 INTERNET 权限和应用名均通过。
- 2026-10-04 API 29 快照根因与完整矩阵：原 `AnAn_API29_Pixel6` 的 AVD 配置、系统镜像指纹及硬件参数与干净基线一致，qcow 一致性检查无错误；但原 AVD 持有并选择了 `default_boot` 快照。只读对照中，`-no-snapshot-load` 冷启动下 `Runtime.maxMemory=512 MiB`，Argon2 v1 默认档（64 MiB / 3 轮 / 并行度 4）875 ms、峰值 PSS 201,402 KiB、1/1 通过；显式加载 `default_boot` 后 Runtime 最大堆为 16 MiB，原样复现 OOM，系统仍有约 937 MiB 可用且 `lowMemory=false`。原 AVD 用户数据与配置未写回。以冷启动只读模式将 API 29 仪器套件分 4 片运行：49、42、47、54 项；总计 192 项，187 通过、5 跳过、0 失败/错误。跳过为 4 个需独立阶段参数的恢复/私密 SAF fixture 和 1 个设备无异形屏能力的切口测试。随后新鲜重跑 JVM 330/330、Lint 0 errors / 17 warnings / 2 hints、Debug 构建及 AndroidTest 编译；构建契约 4 项通过，Debug APK 74,721,145 bytes。API 36 已恢复并冷启动到 MainActivity。API 29/35/36 的其他切口、字体缩放/导航组合与真机矩阵仍待完成。
- 2026-10-04 API 29 journal 分阶段复验及 API 36 设备恢复：API 29 在原数据只读、禁用快照模式下单独运行 `seedUncommittedRestoreForProcessRestart` 与 `coldStartRollsBackPersistedRestoreBeforeShowingMainActivity`，两阶段分别 1/1 通过（中间仅 force-stop 被测应用）；此前私密 SAF seed/verify 尚未执行，已由下方 2026-10-04 实测记录完成。API 36 快照启动时曾出现 ADB offline；不清除 userdata，使用 `-no-snapshot-load -no-snapshot-save` 冷启动后 `sys.boot_completed=1`、APK 可查询且 `MainActivity` 前台。
- 2026-10-04 API 29 私密备份 SAF 与 API 36 显示矩阵：API 29 AVD 用 `-read-only -no-snapshot-load -no-snapshot-save` 完成真实 DocumentsUI seed → 创建 ANBK → 仅清除临时运行层的应用数据 → DocumentsUI 选取 → 设置新私密密码恢复 → 独立解密校验 8×8 ARGB 图片像素。seed、restore UI、verify fixture 各 1/1 通过；所有测试备份文件/目录已逐项清理，关闭只读实例后原 AVD 基础数据不变。API 36 在中心挖孔/高刘海/双开孔/圆角切口/瀑布边缘/Pixel 4a 左上角挖孔/非对称切口，320/360/400 dp，100/150/200% 字号，手势/三键及浅/深系统主题组合下执行 42 项定向 Insets 与系统栏检查，42/42 通过。`SystemBarAppearanceTest` 改为先滚动 LazyRow 再点未组合皮肤；这是测试定位修正，不是生产 UI 变更。最终 `:app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain` 成功：JVM 330/330，Lint 0 errors / 17 warnings / 2 hints，Debug APK 74,721,145 bytes；构建契约 4/4 通过。待验收：Xiaomi 11 Pro 真机、第三方 SAF Provider、API 29/35 完整显示组合矩阵。
- 2026-10-04 API 29/35 异形屏与短高度补充：只读冷启动、不写回 AVD 基础数据。API 29 高刘海/320 dp/200% 字号/三键浅色下 `SystemWindowInsetsTest` + `SystemBarAppearanceTest` 7/7，短横屏排序面板 1/1；该 AVD 夜间模式锁定为浅色。API 35 Pixel 4a 左上角 `@left` 挖孔/320 dp/200% 字号/手势深色下上述系统栏与 Insets 测试 7/7，首页高字号长按和短横屏排序面板 2/2。测试复现半展开面板在紧凑高度隐藏选项；`HomeSortSheet` 已改为高度 ≤480 dp 时跳过半展开态，API 29/35 横屏回归均通过。首页测试文本查询改为等待 Room 列表并使用多节点匹配，以适配标题/摘要同文。当前组合不等于 API 29/35 全切口/主题/导航交叉矩阵。
- 2026-10-04 OCR 持久恢复边界：纯文字 OCR 源图改存 `noBackupFilesDir` 后，发现恢复查询可能遇到合法但非 UUID 的备份块 ID，而暂存文件名只允许 UUID，导致启动恢复门异常。新增真实 Room/WorkManager 回归后先确认失败，再让暂存查找/删除对非 UUID ID 安全返回空/无操作，不放宽 UUID 文件路径校验；同时清理由于笔记已删除、作业成功或取消而变为终态的暂存源。OCR instrumentation 包在 API 29、35、36 各 8/8 通过，涵盖纯文字作业跨进程恢复、旧 cache 暂存兼容、导入非 UUID ID 不阻断启动及孤儿暂存清理。新鲜 JVM 330/330，Lint 0 errors / 17 warnings / 2 hints，Debug APK 74,721,145 bytes；构建契约 4/4 通过。此前 API 36 全量连接测试曾观察到 6 个 UI 自动化失败；其后 API 29/35/36 全量回归均通过相应 6 个方法，但旧失败根因未定位，详见[三 API 回归报告](../verification/2026-10-04-api29-35-36-full-suite-verification.md)。QQ/微信实际分享和授权图像语料准确率仍待真机验收。
- [ ] A 私密导出（私密 MediaStore 图片、私密 SAF 真实 DocumentsUI 往返已在 MuMu/API 32 验收；API 29 已通过私密 SAF seed/verify 与新密钥恢复。ENOSPC/部分文件清理由受控单测覆盖；第三方 Provider 与小米真机文件管理器/相册行为仍待验收）
- [ ] B OCR 候选决策与 OCR 闭环（选型及普通/私密代码路径已具备；普通纯文字 OCR 的 `noBackupFilesDir` 暂存、跨进程恢复及终态清理已在 API 29/35/36 各 8/8 定向验证。扩大图像语料、失败重试/取消体验、QQ/微信实际分享和完整 API UI 矩阵仍待完成）
- [ ] C 加密备份/合并恢复（typed restore target、双域事务、Room/journal/启动恢复、设置与 SAF 操作入口、认证预览及分域冲突 UI 已接通；MuMu 已完成损坏输入、模拟 ENOSPC 回滚、真实进程中断冷启动恢复，以及包含私密笔记和加密图片的 SAF 新库恢复往返。API 29 只读 AVD 上另完成私密 SAF seed/verify 与新私密密钥恢复：真实 DocumentsUI 往返和 8×8 图片像素校验通过；API 35/36 完整套件已覆盖。Xiaomi 11 Pro 真机验收仍待执行）
- [ ] D API 29/35/36 AVD 与异形屏矩阵（最新完整仪器套件：API 29 为 193 通过/6 跳过，API 35/36 各 194 通过/5 跳过，均 0 失败/错误；API 29 临时模拟 `tall` 切口后专用断言 1/1。三 API 中此前报告的 6 个 UI 失败方法均通过。API 36 另有 7 种形状、320/360/400 dp、100/150/200% 字号、两种导航/主题定向 42/42；API 29 高刘海与 API 35 Pixel 4a 左上角挖孔的 320 dp/200% 字号组合 Insets/系统栏各 7/7，短横屏排序面板 API 29/35 各 1/1；API 35 高字号长按另 1/1。OCR 恢复包 API 29/35/36 各 8/8。仍待 API 29/35 更广切口/字号/导航/横竖屏交叉组合、小米真机验收）
- [ ] D 小米 11 Pro 真机验收

### 2026-10-04 三 API 全量回归更新（覆盖前文较早测试计数）

API 29/35/36 仪器套件最新结果分别为 193/199、194/199、194/199 通过；API 29/35/36 分别跳过 6、5、5 项，均 0 失败/错误。跳过项为需独立进程参数的恢复/SAF fixture，另 API 29 默认无切口配置跳过一个切口断言；临时启用 `tall` cutout overlay 后该断言 1/1 通过，随后恢复原设置。此前 API 36 报告的 6 个 UI 自动化失败在三 API 全量复跑中均通过，但根因未定位，也未据此推断产品代码修复。

新鲜 JVM 单测 330/330；Lint 0 errors / 17 warnings / 2 hints；Debug 与 AndroidTest APK 均构建成功，分别 74,721,145 与 29,071,650 bytes；构建契约 4/4 通过。AVD 设置已复原并关机。本轮未修改应用或测试源码。详细结果见[三 API 全量验证报告](../verification/2026-10-04-api29-35-36-full-suite-verification.md)。

此前 API 29/35/36 较早的全量计数和“API 36 UI 失败项待复验”是历史记录，以本更新与验证报告为当前状态。更广异形屏/显示交叉矩阵及小米真机验收仍未完成。


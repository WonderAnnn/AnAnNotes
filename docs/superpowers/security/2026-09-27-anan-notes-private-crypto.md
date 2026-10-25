# 安安简记私密模块安全设计（V1 实施草案）

状态：实现中的格式草案；不是独立安全审计，也不是发布安全认证。MuMu 已取得一组初步 Argon2id 设备数据；小米真机、低内存设备及私密 UI 生命周期验收仍未完成，V1 参数尚未冻结。

## 1. 安全目标与边界

私密模块用于保护应用私有目录中的笔记和附件静态数据。应用不要求登录，不增加网络访问；Android Manifest 当前 `allowBackup="false"`。密码和可选恢复密钥分别封装同一个随机数据密钥，用户不必为每条笔记重复输入密码。

当前边界：

- `key-envelope.bin` 保存版本、KDF 参数、随机 salt、随机 nonce 和加密后的数据密钥，不保存笔记内容或明文密码。
- `vault.snapshot` 是单独加密的快照，包含笔记标题、正文块、文件夹 ID、皮肤、待办、草稿和回收站时间等元数据。
- 原始私密附件单独加密后保存在 `filesDir/private/assets/`；文件名只使用随机 UUID。当前不写私密预览缓存，后续预览必须在内存中生成，不能复用普通缓存目录。
- 普通 Room 数据库、普通 FTS 搜索表和普通草稿目录不保存私密条目。私密搜索只在解锁后读取私密快照并在内存中检索。
- 快照文件及附件采用同目录临时文件、`fsync` 和原子替换提交；临时内容也是密文。

不承诺抵御已取得应用进程执行权、已解锁设备上的恶意键盘/辅助功能、Root/取证级内存采集、用户主动复制/截图，或用户授权导出的普通 PDF/PNG/TXT/Markdown。Debug 构建为便于诊断，明确允许在私密模块已解锁时截取私密内容；Debug APK 只能使用虚构/测试数据，不得用于真实敏感笔记或作为正式包分发。Debug 中的锁定/凭据/恢复密钥页面仍启用 `FLAG_SECURE`；Release 构建所有私密页面均启用 `FLAG_SECURE`。应用进入后台时立即重新启用截图保护并锁定会话。系统截屏可能保存到系统相册，应用不复制或留存截图。闪存文件系统上的删除不是可证明的物理擦除。文件数量、密文大小和最近写入时间仍可能从应用私有目录侧面推断。

## 2. 密钥层次与候选参数

```text
用户密码 ──Argon2id──> 派生密钥 ──AES-256-GCM──┐
                                              ├── 同一个随机 256-bit 数据密钥（DEK）
可选恢复密钥 ─Argon2id─> 派生密钥 ─AES-256-GCM─┘
                                                     ├─ 加密私密快照
                                                     └─ 加密私密附件
```

实现候选固定使用 Argon2id v1.3（RFC 9106 的版本 19）、16-byte 随机 salt、64 MiB memory、3 次迭代、4 lanes，输出 32 bytes。RFC 9106 将 Argon2id `t=3, m=64 MiB` 列为受内存限制环境的推荐配置；这里采用其 `p=4` lanes 建议。每种凭据独立生成 salt 与包装 nonce。用户密码按精确 Unicode 字符序列经 UTF-8 编码，不做 Unicode 归一化。

64 MiB / 3 / 4 是当前默认候选，不是最终性能验收结论。Argon2 参数会写入每个 key wrapper；V1 解码器只接受这一组精确参数配置，先比对再派生，避免篡改 envelope 请求更高内存或迭代次数导致解锁卡顿/OOM。若未来调参，应升级封装版本并显式列出允许的旧配置；不能让文件任意指定昂贵参数，也不能静默降级。正式冻结前需在 Xiaomi 11 Pro、MUMU 和低内存配置上记录解锁时长、峰值内存和失败/OOM 行为。

2026-09-27 MuMu（Android 12/API 32）初测：单次默认配置派生 446 ms；`Debug.MemoryInfo.totalPss` 从 140,774 KiB 基线升至 209,090 KiB 峰值，增量约 66.7 MiB。该 PSS 是整个测试进程的比例集大小，不是 Argon2 独占内存；测量为 5 ms 轮询的一次样本，只作模拟器阶段参考，不作为 Xiaomi 11 Pro 或低内存设备的验收结论。测量由 `Argon2idDevicePerformanceTest` 生成。

密钥封装采用两个可独立解开的 wrapper：密码 wrapper 必需，恢复 wrapper 可选。解锁失败对调用方统一表现为认证失败；缺失恢复 wrapper、错误凭据、错误 salt/nonce/ciphertext 不应反馈“有无某条笔记”等信息。恢复密钥只用于解锁/恢复，不是普通私密导出的授权凭据。

## 3. 加密和认证格式

- 数据密钥：由 `SecureRandom` 生成的 32-byte DEK。
- AES：Android JCA `AES/GCM/NoPadding`，256-bit key、96-bit 随机 nonce、128-bit authentication tag。
- 每次封装或内容加密均重新生成 nonce。快照更新会以新 nonce 重加密，不覆盖复用旧 nonce 的密文。
- GCM AAD 使用域分离常量和长度前缀编码；密钥 wrapper 绑定 envelope version、凭据类型、Argon2 参数及 salt；内容记录绑定 record ID、block type 和记录格式版本。
- 解密仅在 GCM tag 验证成功后返回完整明文；认证失败不暴露 `doFinal` 的中间输出。上下文不匹配也视为认证失败。
- V1 key envelope 为有界二进制格式（`ANKE` magic、版本、恢复 wrapper 标记、固定长度字段）；解析器要求完整消费输入、拒绝尾随数据和未知版本，编码长度不超过 256 bytes。所有由文件输入的 KDF 参数先做上限验证，再开始派生，避免超大内存/迭代参数造成资源耗尽。
- V1 密文记录外层有 `ANPR` magic、记录版本、nonce 和长度；解密后的快照有内部 `ANVS` magic/版本。外层只揭示容器类型、密文长度和文件更新行为，不包含标题、正文或文件夹名称。

AES-GCM 格式依据 NIST SP 800-38D；该文档正在修订，项目保持完整 128-bit tag 和 96-bit nonce，不采用截短 tag。

## 4. 密码、内存和会话处理

- UI 应把密码输入限制在单独凭据表单，提交后立即清空可编辑状态；当前 crypto/store API 会消费并清零传入的 `CharArray`。
- Argon2 输入 UTF-8 临时字节、salt 副本、派生密钥和序列化明文缓冲区在使用后尽力清零；Bouncy Castle Argon2 实现会在计算退出时清零其工作块。JVM、JCA、Compose/输入法和系统可能产生其他副本，因此这属于尽力清理而不是内存擦除保证。
- `PrivateAccess` 不提供数据密钥 getter；只允许在内部同步回调中使用，并可撤销。撤销后会清零持有的密钥数组，之后的访问失败。
- 已接入离开私密页面、`ON_STOP`、组件释放时撤销会话与清空私密页面状态；私密窗口标志按构建和状态控制：Release 全程 `FLAG_SECURE`；Debug 仅已解锁内容允许系统截屏，凭据/恢复密钥显示期间保持保护；`ON_STOP` 先同步重新加锁和启用保护；退出时只恢复原始 secure 位，不覆盖其他窗口标志。Debug 截屏可能把敏感内容保存到系统相册，必须只用虚构数据诊断。最近任务预览仍需专项仪器及手工验收，不能只据代码接线宣称生命周期已完整验收。
- 私密解锁和私密导出再认证共用同一持久化本地失败退避。连续 5 次失败先冷却 30 秒，后续失败按指数增加并封顶 5 分钟；成功验证清除失败计数。此节流用于减慢本机误输/猜测，不是抵御 Root、篡改应用数据或离线攻击的安全边界。密码变更、恢复密钥重新生成/轮换和密钥格式升级尚未实现。

## 5. 失败、恢复和数据隔离规则

- 不提供服务器端找回。用户忘记密码且未保存恢复密钥时，私密数据不可恢复；界面必须在设置前明确提示。
- 普通自动备份关闭；私密内容不进入普通数据库、普通导出历史、普通草稿、普通 FTS、普通缓存或普通 WorkManager 输入。私密导出已有独立 `PrivateExportService`/私密数据源和最多 100 条的加密私密历史；每次重新输入私密密码，验证后生成绑定当前会话/笔记/格式/目标的一次性短期授权，并显示“导出文件将不再受安安简记私密模块保护”。导出目标为用户明确选择的普通 SAF 文件或系统相册文件。不能因此宣称实际文件管理器/相册或异常中断清理已在 Xiaomi 上验收。
- 加密快照采用整体原子替换，单次保存失败保留旧快照。高频草稿写入频率、快照体积和 crash consistency 仍需在编辑器接入时验证。
- 私密图片已通过系统 Photo Picker 导入并以随机引用写入加密附件；私密 OCR 走内存字节识别，不使用普通 WorkManager 或普通缓存，解密缓冲区在 `finally` 中清零，OCR 文本写回加密快照。内存采样预览、图片旋转元数据一致性和 Xiaomi/低内存设备生命周期仍需补验。私密附件引用校验、快照提交与未引用附件删除共用存储互斥边界，避免删除与新建引用并发产生悬空资源。
- 扩展 schema 时必须递增快照版本，并提供显式迁移；未知版本应拒绝读取且保留原文件，不能静默覆盖。

## 6. 已验证与待验证

当前本地测试覆盖：密码/恢复 wrapper 分别恢复同一 DEK、错凭据统一失败、nonce/ciphertext/KDF 参数篡改失败、unknown envelope version 拒绝、新 nonce、AAD 上下文绑定、GCM tag 篡改不返回明文、RFC 9106 Argon2id 向量、磁盘快照/图片不包含测试标题正文、恢复密钥解锁、访问撤销、私密搜索/回收站和空白新笔记不落库。新增覆盖私密导出一过授权、私密历史隔离/上限、私密图片 OCR 结果写回加密快照及解密输入清零、导出再认证退避。ANBK 容器编码/验证/事务消费有 14 项定向测试。2026-10-01 全量 JVM 单测 226/226 通过；MuMu Android 12/API 32 仪器测试 174 项、0 失败、1 项因模拟器无异形屏而跳过。此结果不代表 API 29–36 或 Xiaomi 真机已验证。

尚未验收：普通待办/导航/最近任务预览的端到端隐私隔离、Android JCA 在 API 29–36 的设备行为、Xiaomi 11 Pro 与低内存设备上的 Argon2 性能/峰值内存/OOM 行为、私密 UI 生命周期与系统 SAF/相册实机验收、编辑器异常恢复、真实多样 OCR 语料、加密备份/恢复、压力/模糊测试、外部密码学审查。自动化测试只证明覆盖场景的实现行为，不是独立安全认证。

## 7. 加密备份容器与跨设备恢复（2026-10-01 V1 设计增量）

状态：产品语义已由用户确认，格式方案作为实现基线；ANBK 流式编码/完整验证与事务消费 codec 已有代码，14 项定向 JVM 测试和一次独立安全复审已完成，但类型化载荷、SAF 工作流、跨仓库恢复事务、崩溃修复、设备/模糊测试仍未完成，格式不能视为冻结。用户确认：恢复至尚未配置私密库的设备时设置新私密密码并使用新生成的数据密钥重新加密。独立备份密码只保护备份文件，不替换私密库密码或恢复密钥，也不把源设备 DEK 包进备份。

### 7.1 容器与密码学

- 文件 magic 为 `ANBK`，格式版本显式编码。头部只包含版本、KDF 标识/受限参数、随机 16-byte salt、随机 32-bit nonce 前缀及固定 4 MiB 分块大小；不放笔记数、日期、标题、文件夹名或明文摘要。
- 独立备份密码使用 Argon2id v1.3 派生 32-byte 一次性备份密钥；V1 候选参数为 64 MiB / 3 次迭代 / 4 lanes。派生密钥不落盘，完成、失败或取消后尽力清零。密码 UTF-8 字节有界、保留原字符序列、不做 Unicode 归一化。解析头部时先校验版本和所有 KDF 参数上限，再启动 Argon2。
- 加密载荷按最多 4 MiB plaintext chunk 分块独立使用 AES-256-GCM，tag 固定 128-bit。nonce 为 `randomPrefix32 || monotonicallyIncreasingIndex64`；数据块从索引 0 连续递增，FINAL 使用下一个未使用索引（空备份的 FINAL 索引为 0），任何跳号/回绕均拒绝。每个 chunk 的 AAD 包含精确的原始头部字节、记录类型、chunk index、明文长度和结束标记。一个备份密钥只服务于一个随机 salt/前缀生成的单一容器。
- V1 硬上限为 4096 个数据块（最多 16 GiB 明文）及其固定头/标签开销。读取 API 还必须要求调用方传入本次操作的 `maxContainerBytes`，该值从已暂存文件长度和可用空间核算得出，且不能超过 V1 硬上限；累计长度采用溢出检查后再分配/暂存。后续类型化记录解析器仍必须为记录数、单记录字段、附件总量和引用数量定义独立上限，不能把“分块有界”误当成“记录集合有界”。
- 最后必须写入独立、带认证的 FINAL 记录；解析端要求 chunk 顺序连续、每块 tag 正确、恰有一个 FINAL 且其后立即 EOF。拒绝截断、重排、重复、拼接、未知标志、超界长度及尾随字节。载荷采用有类型、长度前缀的二进制记录流，不直接解压不可信 ZIP 路径；每种 schema 版本有明确记录上限和字段上限。
- 普通记录、私密记录、图片附件和待恢复草稿都处于同一已认证加密载荷内。图片按原始字节流分块；备份全程不要求把整份附件或整个备份放入内存。备份文件本身是密文，但其文件名/保存目录由用户决定，界面应提示由用户选择可信位置。

**记录流实现增量（2026-10-01，格式未冻结）：** ANBK 明文载荷外层已实现 `ANRD` magic + 1-byte stream version；每个记录帧为 domain `u8`、kind `u8`、schema version `u16`、payload length `u64`（大端，必须非负）及精确长度 payload。domain 1/2 分别为普通/私密；kind 1–7 依次为文件夹、笔记、内容块、待办、草稿头、草稿块、附件。`0/0/version 1/length 0` 是唯一流终止帧，之后必须立即 EOF。当前只接受 schema v1，未知 domain/kind/version、负长度、截断、未完全消费的记录、尾随数据均拒绝。

实现提供增量 decoder，可直接接收 ANBK 解密回调的分块，不拼接整份明文或附件；记录 body 以短生命周期 byte slice 同步回调。恢复 sink 必须在回调返回前完成该片段的受保护写入；私密数据须直接在目标私密 DEK 下加密。consumer 串行化分块写入、commit 与 abort；清理失败可重试，清理成功前禁止继续写入或提交。接收器仅在 ANRD terminator、ANBK authenticated FINAL、EOF 和完整密文指纹全部验证后提交。当前帧上限：文件夹 64 KiB、笔记 8 MiB、内容块 8 MiB、待办 4 MiB、草稿头 8 MiB、草稿块 8 MiB、普通附件 250 MiB、私密附件 64 MiB−16 bytes；总流不超过 16 GiB、总帧数不超过 1,000,000。以上是有界传输层，不是完整语义 schema：字段编码/字段长度、对象关系及附件摘要校验、重复 ID 与引用校验仍待实现，不能据此宣称备份/恢复已完成。

**类型化载荷 schema v1（2026-10-02 实现约定）：** 记录体不再是不透明字节。多字节整数与 IEEE-754 binary32 均为大端；字符串为 `u32 byteLength + strict UTF-8`，不做 Unicode 归一化；nullable 字段先写 `u8` presence（仅 0/1）；布尔仅接受 `u8 0/1`；浮点数拒绝 NaN/Infinity；每个非附件记录必须精确消费记录体且不得有尾随字节。长度前缀的高位若令其超过实现可分配的有符号上限，立即拒绝。所有 ID 限于 1–128 个 UTF-8 字节，只作不透明引用，不作为文件路径；空值、控制字符和 `/`、`\\` 均拒绝。未知 enum 数值、无效 UTF-8、错误 nullable 标记和未知 schema 一律拒绝。

记录字段顺序固定如下，帧的 domain 决定普通/私密 ID 命名空间；不同 domain 不得互相引用：

| kind | schema v1 字段顺序 |
| --- | --- |
| `FOLDER` | `id:string, name:string, kind:u8, createdAtMillis:i64, sortPosition:i32`。kind：`USER=1`、`UNCATEGORIZED=2`。 |
| `NOTE` | `id:string, folderId:string, title:string, titleManuallySet:bool, createdAtMillis:i64, updatedAtMillis:i64, skinId:string, fontSizeSpOverride:nullable-f32, lineSpacingMultiplierOverride:nullable-f32, isPinned:bool, deletedAtMillis:nullable-i64, contentRevision:i64, importRequestId:nullable-string`。私密笔记没有导入请求 ID，编码为 null。 |
| `BLOCK` | `id:string, noteId:string, position:i32, type:u8, text:nullable-string, assetRef:nullable-string, widthPercent:f32, alignment:u8, aspectRatio:nullable-f32, ocrStatus:nullable-u8, formattingBlob:nullable-bytes`。Block type：`TEXT=1, IMAGE=2, TODO=3, QUOTE=4, DIVIDER=5, OCR_TEXT=6`；alignment：`START=1, CENTER=2, END=3`；OCR：`NOT_REQUESTED=1, PENDING=2, RUNNING=3, SUCCEEDED=4, FAILED=5, CANCELLED=6`。导出时将 `RUNNING` 规范为 `PENDING`，恢复后可重新排队。 |
| `TODO` | `id:string, noteId:string, blockId:string, text:string, isCompleted:bool, updatedAtMillis:i64`。 |
| `DRAFT` | `draftId:string, noteId:nullable-string, folderId:string, baseContentRevision:nullable-i64, localRevision:i64, savedAtMillis:i64, importRequestId:nullable-string, title:string, titleManuallySet:bool, skinId:string, fontSizeSpOverride:nullable-f32, lineSpacingMultiplierOverride:nullable-f32, blockCount:u32`。 |
| `DRAFT_BLOCK` | `draftId:string, position:i32, blockId:string, type:u8, text:nullable-string, assetRef:nullable-string, widthPercent:f32, alignment:u8, aspectRatio:nullable-f32, ocrStatus:nullable-u8, formattingBlob:nullable-bytes`。 |
| `ASSET` | `assetId:string, mimeType:string, byteCount:i64, sha256:32 bytes, rawBytes[byteCount]`。asset 元数据在流式记录体前缀，后续原图按 reader 回调分段传输和哈希；`BlockEntity.assetPath`、`previewPath` 及任何本机绝对/相对路径都不得编码。一个笔记块以 `assetRef` 指向同 domain 的 `assetId`。 |

类型校验使用本项目数据边界：文件夹名称最多 4 KiB；标题最多 1 MiB；普通/私密文本与字符串字段最多分别 7 MiB / 4 MiB，且完整记录仍受前述帧上限约束；skin ID 最多 128 bytes；格式 blob 最多 4 MiB；MIME 仅接受当前导入器支持的图片类型；附件长度必须为正且不超过所属 domain 的附件上限。笔记时间戳非负、内容修订号至少为 1；草稿修订号/时间非负；block 和 draft-block position 非负且每个父对象内唯一；字号仅接受 14–32 sp、行距 1–2、图片宽度 1–100%、宽高比大于 0 且有限。语义校验器在完成整流后另行拒绝重复 ID、悬空或跨域 folder/note/block/asset 引用、重复位置、TODO 与其 TODO block 不一致、声明块数与实际块数不符、未被引用附件；父记录可以晚于子记录出现，但在完整终止帧之前不得生成预览或提交。附件 digest 必须对原始字节逐段 SHA-256 匹配。

### 7.2 备份内容和迁移语义

- V1 纳入：普通与私密笔记、内容块及顺序/格式/皮肤/宽度、原始图片、一级文件夹、待办完成状态、回收站条目，以及未完成的可恢复草稿；OCR 文本随内容块保留，PENDING 状态随原图恢复后可重新排队。
- 不纳入：普通/私密导出历史和目标 URI、诊断日志、临时预览图缓存、系统权限 URI grant、设备显示/字体偏好和账号数据。缓存可重建；不把旧设备的 SAF 授权伪装成可在新设备使用。
- 恢复默认合并并按稳定 ID 去重。重复 ID 且载荷相同时作为已存在内容；内容不同则显示冲突并允许保留当前、使用备份或保留两个版本。预览不得在备份密码通过完整性验证前显示任何数量或私密信息。
- 若目标设备还未配置私密库，用户在恢复流程中设置新的私密密码，生成全新的随机 DEK；来自备份的私密有效载荷只在受控内存/流中出现，随后在私密暂存目录中用新 DEK 重新加密，绝不恢复源设备 DEK。目标已有私密库时，用户先解锁该库，合并后的私密数据使用目标现有 DEK 重加密。

### 7.3 检查、暂存和提交

1. SAF 选择器返回源 URI 后，先把原始加密容器复制到随机命名的应用私有 `noBackupFilesDir` 临时容器；该临时文件只含密文，大小受磁盘配额/可用空间检查约束并受 V1 硬上限约束。取消、锁定、异常退出后由启动清理回收未引用容器。
2. 第一遍以用户输入的独立备份密码验证全部 chunk、FINAL 和 EOF；失败时不解析/展示条目、不写任何恢复数据。通过后第二遍读取同一临时密文；进程内 `VerifiedBackup` 绑定已认证头部与完整容器 SHA-256，消费遍仍逐块验证 GCM、结尾和指纹，只有完全一致后事务接收器才能 commit，任何失败都调用 abort 清理暂存。第二遍的暂存文件必须由应用私有目录独占且在事务期间不可替换；不能仅凭第一次校验结果信任再次读取的内容。
3. 恢复先做全量验证、长度/数量/ID/引用/空间检查，并把普通数据和图片暂存到独立 restore 目录；私密内容逐记录直接重加密到目标 DEK 的私密暂存库，不能先解密到普通文件再搬运。
4. 恢复协调器写入仅含随机事务 ID、阶段和无敏感计数的原子 journal。所有文件与两类数据仓库都准备完成后再进入 commit；失败且尚未 commit 时删除新建暂存物，不修改旧数据库、旧私密库和旧附件。崩溃发生在 commit 窗口时，下一次启动按 journal 选择完整回滚或幂等前滚，不能让半恢复状态直接对用户可见。
5. 清理要求按引用关系执行：未提交事务只删自己创建的随机文件；成功后再移除不再引用的旧文件。SAF 源备份文件不会被删除或改写。

### 7.4 安全审查要点与测试门槛

- KDF 参数拒绝测试必须在派生前完成；口令、派生密钥、chunk plaintext 和私密附件临时数组所有退出路径均清零。错误认证不得回传部分明文。
- 模糊测试覆盖损坏头部、未知版本/flags、所有长度边界、chunk tag、缺块/乱序/重复、错误 FINAL/尾随数据、重复 ID、恶意引用、超大条目和取消/空间不足。
- 故障注入覆盖复制、认证、普通暂存、私密重加密、数据库提交、目录换入、journal 更新、进程终止和启动修复；在失败语义允许的每个点比较源库、目标库、附件引用及旧文件哈希。
- 设计与实现须复核普通/私密日志、崩溃报告、截图、export history、Room/FTS、WorkManager 输入和 Manifest 均不暴露私密字段。完成实现后仍需独立安全审查；本节不是形式化验证或认证。

## 8. 依据

- [RFC 9106: Argon2 Memory-Hard Function](https://www.rfc-editor.org/rfc/rfc9106.html)
- [NIST SP 800-38D: GCM and GMAC](https://csrc.nist.gov/pubs/sp/800/38/d/final)
- [Bouncy Castle Java releases and API distribution](https://www.bouncycastle.org/download/bouncy-castle-java/)
- [Bouncy Castle Argon2 implementation source](https://github.com/bcgit/bc-java/blob/main/core/src/main/java/org/bouncycastle/crypto/generators/Argon2BytesGenerator.java)

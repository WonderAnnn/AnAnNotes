# 安安简记富文本编辑实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 subagent-driven-development 或 executing-plans 逐任务实现。步骤用复选框跟踪；每个任务先测后写。当前工作区没有 Git 元数据，不能创建 worktree 或执行 commit；实现时逐任务保留验证输出，待用户将工程放入 Git 后再提交。

**目标：** 在普通和私密笔记中实现可保存、可恢复、可搜索且不破坏旧数据的轻量富文本编辑。

**架构：** 在共享领域模型中保存纯文本、内联格式半开区间和块级标题属性；Room、普通草稿和私密快照分别使用兼容升级。Compose 编辑器以单一文本/选择状态处理格式、输入法和自动保存；普通与私密 UI 共用文字块编辑组件，但继续使用各自仓库与持久化边界。

**技术栈：** Kotlin、Jetpack Compose、Room、Coroutines/Flow、现有二进制草稿/私密快照 codec；不增加第三方运行时依赖。

**规格：** [富文本编辑设计](../specs/2026-09-27-anan-notes-rich-text-design.md)。同时遵守 [V1 产品设计](../specs/2026-09-26-anan-notes-v1-design.md) 和 [V1 总体技术设计](../specs/2026-09-27-anan-notes-v1-technical-design.md)。

## 全局约束

- 最低支持 Android 10（API 29）；不新增网络权限、遥测或云端格式化服务。
- 纯文本与格式数据分开保存；区间使用 Kotlin String/Compose 一致的 UTF-16 半开区间 [startUtf16, endUtf16)。
- 仅 BOLD、ITALIC、STRIKETHROUGH 可重叠；单级标题仅用于 TEXT 块；QUOTE 是独立块且不能同时为标题。
- 每块最多 100,000 个格式区间，支持的格式 blob 最大 4 MiB；普通草稿和私密快照最大 64 MiB。
- 普通 Room 从 v5 迁移到 v6；普通 ANAN 草稿 codec 从 v2 升到 v3；私密 ANVS 快照从 v1 升到 v2；外层 ANPR 私密记录格式不变。
- 旧数据库、草稿和私密快照必须保留正文；旧格式默认 BODY/空区间。未知格式 blob 必须原样保留，不得静默清空或重写。
- 搜索只索引纯文本；本计划不实现 PDF、PNG、TXT、Markdown 导出，只保持已有导出输入为无格式正文，并为后续格式转换保留数据契约。
- 格式变化和正文在同一编辑状态/保存事务中提交；格式保存失败不能报告成功，也不能清除仍可恢复的草稿。
- 使用 MuMu Android 12/API 32 作为当前主要设备验证目标；不安装或操作 Xiaomi 11 Pro，除非用户另行要求。
- 每个任务结束运行其列明的测试；不执行 Git commit（当前目录不是 Git 仓库）。

---

## 文件与职责

**领域模型、范围算法与序列化**

- 修改：env-check/app/src/main/java/com/anan/anannotes/core/model/BlockModels.kt — 增加格式枚举、区间、块展示属性和支持/不支持状态。
- 创建：env-check/app/src/main/java/com/anan/anannotes/core/model/BlockFormattingRules.kt — 校验、规范化、区间应用/移除、编辑变换和块拆分规则。
- 创建：env-check/app/src/main/java/com/anan/anannotes/core/model/BlockFormattingCodec.kt — ANFM v1 有界编码器/解码器。
- 修改：env-check/app/src/main/java/com/anan/anannotes/core/ImageBlockRules.kt — 插入图片拆分文字块时裁剪、重基并保留格式区间。
- 创建：env-check/app/src/test/java/com/anan/anannotes/core/model/BlockFormattingRulesTest.kt、BlockFormattingCodecTest.kt；修改 env-check/app/src/test/java/com/anan/anannotes/feature/ImageBlockTest.kt。

**普通数据库与普通恢复草稿**

- 修改：env-check/app/src/main/java/com/anan/anannotes/data/local/Entities.kt、AnAnDatabase.kt — nullable BLOB 和 Room v5→v6 migration。
- 修改：env-check/app/src/main/java/com/anan/anannotes/data/repository/EntityMappers.kt、NoteRepository.kt — 格式 blob 与块模型互转，事务保存正文与格式。
- 修改：env-check/app/src/main/java/com/anan/anannotes/feature/editor/EditorDraftStore.kt — ANAN v3，读取 v1/v2 并保存 v3。
- 修改：env-check/app/src/test/java/com/anan/anannotes/feature/editor/EditorDraftStoreTest.kt、env-check/app/src/androidTest/java/com/anan/anannotes/data/NoteRepositoryTest.kt。
- 创建：env-check/app/src/androidTest/java/com/anan/anannotes/data/BlockFormattingMigrationTest.kt — 从已导出的 Room v5 schema 验证迁移。

**编辑器 UI 与私密边界**

- 创建：env-check/app/src/main/java/com/anan/anannotes/feature/editor/FormattedTextBlockField.kt — 普通/私密编辑器共用的富文本输入视图。
- 修改：env-check/app/src/main/java/com/anan/anannotes/feature/editor/EditorUiState.kt、EditorViewModel.kt、EditorScreen.kt — 原子文本/选择/格式状态、普通自动保存、工具栏与块操作。
- 修改：env-check/app/src/main/java/com/anan/anannotes/feature/private/PrivateStore.kt、PrivateEditorRules.kt、PrivateModuleViewModel.kt、PrivateEditorScreen.kt — ANVS v2、私密块格式、加密草稿恢复和编辑 UI。
- 修改：env-check/app/src/test/java/com/anan/anannotes/feature/private/PrivateStoreTest.kt、PrivateEditorRulesTest.kt；创建 env-check/app/src/androidTest/java/com/anan/anannotes/ui/PrivateRichTextEditorTest.kt。
- 修改：env-check/app/src/androidTest/java/com/anan/anannotes/ui/EditorScreenTest.kt — 编辑器手势、自动保存和恢复用例。

测试继续使用现有 Gradle 工具链与依赖，不为富文本增加库或 Manifest 权限。Room schema 导出位置是 env-check/app/schemas/com.anan.anannotes.data.local.AnAnDatabase/；新 schema 文件由 Room/KSP 构建生成并纳入迁移验证。

## 任务 1：领域模型、格式区间算法与 ANFM codec

**文件：** 上述领域模型、规则、codec 及其 JVM 测试；同时修改 core/ImageBlockRules.kt 和 feature/ImageBlockTest.kt。

- [x] **步骤 1：先写范围规则失败测试**

在 BlockFormattingRulesTest.kt 写测试，覆盖格式应用/移除、同样式合并、不同样式重叠、混合选区统一应用和非法边界拒绝。至少固定以下断言：

        val formatted = BlockFormattingRules.setInlineStyle(
          text = "甲乙丙",
          formatting = BlockFormatting(),
          selection = TextSelection(1, 2),
          style = InlineStyle.BOLD,
          enabled = true,
        )
        assertEquals(listOf(InlineStyleRange(1, 2, InlineStyle.BOLD)), formatted.inlineStyles)

- [x] **步骤 2：运行测试确认缺少实现时失败**

运行：

        Set-Location D:\anan\env-check
        .\gradlew.bat :app:testDebugUnitTest --tests '*BlockFormattingRulesTest'

预期：测试因目标模型/规则尚不存在而编译失败，不得用跳过测试代替。

- [x] **步骤 3：增加格式模型和基础范围规则**

在 BlockModels.kt 增加 InlineStyle、TextBlockPresentation、FormattingDecodeError、InlineStyleRange、BlockFormatting、BlockFormattingState。在 BlockFormattingRules.kt 实现 normalize(ranges) 和 validate(text, type, state)。规范化合并同样式的重叠/相邻范围并排序；不同样式允许重叠。范围越界、样式与块类型不兼容或切开代理项对时返回明确错误，不静默截断。

- [x] **步骤 4：写文本编辑、Unicode 和图片拆块失败测试**

在 BlockFormattingRulesTest.kt 测试选择应用/移除与编辑替换后的偏移调整；扩展 ImageBlockTest.kt：文本“甲乙丙丁”在 UTF-16 偏移 2 插入图片后，左侧保留 [0,2)、右侧格式区间重基为 [0,2)，边界插入不生成空文字块。另测“A😀B”的合法 emoji 区间 [1,3)，中间偏移 2 不可作为格式边界。

- [x] **步骤 5：运行新规则测试确认失败**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*BlockFormattingRulesTest' --tests '*ImageBlockTest'

预期：新编辑变换/拆块用例失败，既有图片宽度和比例用例继续通过。

- [x] **步骤 6：实现选择应用、文本变换和图片拆块**

实现 setInlineStyle(text, formatting, selection, style, enabled)、replaceText(...) 和 splitFormattingAt(...)。replaceText 输入/输出使用 TextSelection 与 TextEditResult，同时携带 composition；依据旧/新文字和选区更新区间，插入文字服从显式 typingOverrides 或插入点继承规则。

        data class TextSelection(val startUtf16: Int, val endUtf16: Int)
        data class TextEditResult(
          val text: String,
          val selection: TextSelection,
          val composing: TextSelection?,
          val formatting: BlockFormatting,
        )

纯领域算法不得依赖 Compose/Android；跨代理项的 UI 选区向外扩到字符边界，折叠光标吸附到安全边界。

- [x] **步骤 7：写 ANFM codec 的兼容和损坏输入测试**

在 BlockFormattingCodecTest.kt 覆盖 BODY 空区间、HEADING、内联范围、不同样式重叠的 v1 往返；固定枚举 ID；验证未知版本返回 Unsupported 并保留原字节；截断、尾随字节、非法范围、非法枚举和超限输入不得解码为 Supported。NoteRepositoryTest 后续验证默认 BODY/空格式在数据库中存成 NULL。

- [x] **步骤 8：运行 codec 测试确认失败**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*BlockFormattingCodecTest'

预期：codec 测试因实现缺失而失败。

- [x] **步骤 9：实现有界 ANFM v1 codec**

在 BlockFormattingCodec.kt 实现显式版本、固定 ID 和长度边界的编码/解码；默认 BODY/空区间由 mapper 表示为 NULL，非空格式写 ANFM v1。原始未知格式字节只由 Unsupported 持有并透传，不能重新编码。

- [x] **步骤 10：运行任务 1 全套测试**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*BlockFormattingRulesTest' --tests '*BlockFormattingCodecTest' --tests '*ImageBlockTest'

预期：范围、Unicode、codec 和图片拆分测试全部通过；不改动既有图片宽度/比例规则。

## 任务 2：普通 Room、mapper 与保存事务

**文件：** Entities.kt、AnAnDatabase.kt、EntityMappers.kt、NoteRepository.kt、NoteRepositoryTest.kt、BlockFormattingMigrationTest.kt。

- [x] **步骤 1：先写 mapper 和仓库往返失败测试**

扩展 NoteRepositoryTest.kt：保存 TEXT 块“格式搜索正文”，带标题展示和 BOLD [0,2)；重新读取后正文、块 ID、标题属性和区间保持相同。断言 FTS 搜索“格式搜索正文”命中，而正文/FTS 中不出现 Markdown 标记。新建 BlockFormattingMigrationTest.kt：用 schema 5 fixture 写入既有 note/block/FTS 数据，准备迁移后检查默认 formattingBlob 为 null、旧搜索结果仍在。

- [x] **步骤 2：运行普通仓库测试确认失败**

运行：

        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.data.NoteRepositoryTest"
        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.data.BlockFormattingMigrationTest"

预期：格式往返用例因字段缺失失败；迁移用例因 MIGRATION_5_6 未注册而失败。

- [x] **步骤 3：增加 Room v6 nullable BLOB 与显式 migration**

给 BlockEntity 加 formattingBlob: ByteArray? = null，把 AnAnDatabase 版本设为 6，创建并注册 MIGRATION_5_6：

        val MIGRATION_5_6 = object : Migration(5, 6) {
          override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE note_blocks ADD COLUMN formattingBlob BLOB")
          }
        }

Room schema 中旧块的 BLOB 为 NULL；迁移不得重建表、改写正文/ID 或重建 FTS。将 MIGRATION_5_6 添加到生产 Room builder 与迁移测试 builder；更新 KSP 导出的 6.json。

- [x] **步骤 4：接入 mapper 与普通保存**

BlockEntity.toModel() 将 null 转 Supported(BlockFormatting())，将有效 blob 解码为 Supported，损坏/未知 blob 转成含原始副本的 Unsupported。NoteRepository.saveVersioned() 对 Supported 编码后写入 formattingBlob，对 Unsupported 原样复制旧 blob；非法新格式拒绝保存。正文、块、格式和 contentRevision 继续在现有 Room transaction 内提交。

- [x] **步骤 5：验证从 schema v5 的迁移**

在 BlockFormattingMigrationTest.kt 用 MigrationTestHelper.createDatabase(TEST_DATABASE, 5) 建立 v5 库，写入一个有正文的活动笔记和一个可检索 FTS 条目，关闭后用生产 migration 链打开。断言 Room v6 可读取原标题、正文、块 ID、formattingBlob == null，且搜索仍命中；另保存一条新格式笔记后读回格式区间。NoteRepositoryTest 额外断言默认 BODY/空范围存为 NULL、格式写失败时版本不递增。

运行：

        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.data.BlockFormattingMigrationTest"
        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.data.NoteRepositoryTest"

预期：Room migration、搜索不变和普通保存往返全部通过。

## 任务 3：普通 ANAN 草稿 v3 与旧草稿兼容

**文件：** feature/editor/EditorDraftStore.kt、feature/editor/EditorDraftStoreTest.kt。

- [x] **步骤 1：先写格式草稿往返与 v2 fixture 测试**

扩展 EditorDraftStoreTest.kt：新建含 BOLD/ITALIC 重叠范围和 HEADING 块的草稿，写入再读取后精确相等；添加按现有 v2 字段顺序手写的 ANAN v2 fixture，确认正文/块 ID 读取后为默认无格式；添加未知 ANFM blob fixture，确认它不被清空。

- [x] **步骤 2：运行草稿测试确认新格式用例失败**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*EditorDraftStoreTest'

预期：新 v3 往返用例失败，既有 v1 fixture 仍通过。

- [x] **步骤 3：升级 DraftCodec 到 v3**

新写文件使用版本 3；每个 block 在旧字段之后写入长度有界的 nullable formatting blob。v1/v2 解码不读取新字段并赋 Supported(BlockFormatting())；v3 有效 blob 解码为 Supported，未知/损坏的格式 blob保持 Unsupported 原字节。保持 ANAN 文件魔数、原子替换、64 MiB 总文件上限和 UUID 文件名不变。

v3 读取分支采用显式版本判断，不能让 v1/v2 误读 v3 新字段：

        val formattingState = when (version) {
          1, 2 -> BlockFormattingState.Supported(BlockFormatting())
          3 -> BlockFormattingCodec.decodeState(input.readNullableBytes())
          else -> throw IOException("Unsupported draft version")
        }

- [x] **步骤 4：运行草稿全套测试**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*EditorDraftStoreTest'

预期：v1/v2 兼容、v3 格式往返、原子替换、修订号保护和损坏草稿保留用例均通过。

## 任务 4：普通编辑器原子状态、工具栏与内容块行为

**文件：** EditorUiState.kt、EditorViewModel.kt、EditorScreen.kt、新建 FormattedTextBlockField.kt、EditorScreenTest.kt、ImageBlockTest.kt。

- [x] **步骤 1：先写编辑规则和 UI 失败测试**

扩展 Compose instrumentation 测试：选中文字后点击 B 显示加粗；对全加粗选区再点 B 移除；无选区点 B 后连续输入为加粗，再点 B 后新字恢复普通；点击 H 改变当前 TEXT 块展示属性但不改首页笔记标题；点击“引用”在 TEXT/QUOTE 间转换且保留文本和 ID。测试图片插入后左右格式区间仍正确；未知格式块只读、其他块可编辑，明确确认移除后才能编辑该块。

- [x] **步骤 2：运行编辑器测试确认失败**

运行：

        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.EditorScreenTest"

预期：新测试因工具栏按钮目前无动作或正文状态未携带格式而失败。

- [x] **步骤 3：让 ViewModel 原子接收文本与选择**

将当前 onTextChanged / onTextSelectionChanged 两次更新合并为 onTextValueChanged(blockId, text, selectionStart, selectionEnd, composingStart, composingEnd)。单次状态变更更新正文、选区和格式，按 BlockFormattingRules 调整编辑区间，然后调用现有 queueDraftWrite() 与 scheduleSave()。在 EditorUiState 保存当前编辑会话的输入覆盖 Map<InlineStyle, Boolean>；未设置的样式继承光标邻近字符，不将临时覆盖写入数据库或草稿。

UI 回调和 ViewModel 使用同一份文本/选择更新，不再先发文字回调再发选择回调：

        fun onTextValueChanged(
          blockId: String,
          text: String,
          selection: TextSelection,
          composing: TextSelection?,
        )

- [x] **步骤 4：把编辑规则接到普通 ViewModel**

EditorViewModel.onTextValueChanged 从当前块快照构造旧文本、旧 selection/composition 和格式，调用 BlockFormattingRules.replaceText，一次性替换 NoteBlockDraft，再调现有 queueDraftWrite() 与 scheduleSave()。新增 applyInlineStyle(style)、togglePresentation() 和 toggleQuote()；无选区样式覆盖只保留在 EditorUiState，不写入 NoteDraft。Unsupported 块不允许文本/格式变更，只允许显式 resetUnsupportedFormatting(blockId) 清除原 blob。

        fun applyInlineStyle(style: InlineStyle)
        fun togglePresentation()
        fun toggleQuote()
        fun resetUnsupportedFormatting(blockId: String)

- [x] **步骤 5：实现共用富文本文字块组件**

在 FormattedTextBlockField.kt 把纯文本与 Supported 区间合成为 AnnotatedString，映射 BOLD/ITALIC/STRIKETHROUGH 到 Compose SpanStyle，保留 TextFieldValue.selection/composition。组件不得接触仓库、私密访问令牌或文件系统；Unsupported 状态展示当前主题下的短错误提示和“移除格式并编辑”按钮，正文可选中复制但不可改写。

组件以调用方传入的注解文本与回调为边界，不访问仓库或私密密钥：

        @Composable
        fun FormattedTextBlockField(
          value: TextFieldValue,
          formatting: BlockFormattingState,
          onValueChange: (TextFieldValue) -> Unit,
          onResetUnsupportedFormatting: () -> Unit,
          modifier: Modifier = Modifier,
        )

- [x] **步骤 6：接通标题、引用和编辑工具栏**

EditorScreen 对 TEXT、QUOTE、OCR_TEXT 渲染共用组件；H 修改 TEXT 的 presentation；QUOTE 操作在 TEXT/QUOTE 间转换并保留块 ID、文本与内联格式。把现有空操作 B/H 接到 ViewModel；More 菜单接斜体、删除线和引用；其他待办/图片/分割线操作维持现状。颜色只取当前皮肤 token。

- [x] **步骤 7：运行规则、草稿和编辑器测试**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*BlockFormattingRulesTest' --tests '*ImageBlockTest' --tests '*EditorDraftStoreTest'
        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.EditorScreenTest"

预期：有/无选区行为、持续输入、格式叠加、IME composition、标题/引用转换、未知格式恢复、图片拆分和已有自动保存/草稿恢复测试通过；格式操作不创建空笔记。

## 任务 5：私密 ANVS v2、富文本编辑和加密草稿恢复

**文件：** feature/private/PrivateStore.kt、PrivateEditorRules.kt、PrivateModuleViewModel.kt、PrivateEditorScreen.kt、PrivateStoreTest.kt、PrivateEditorRulesTest.kt、新建 PrivateRichTextEditorTest.kt。

- [x] **步骤 1：先写私密快照与合并规则失败测试**

在 PrivateStoreTest.kt 添加含私密正文和格式范围的 snapshot round-trip，断言关闭/重新解锁后格式精确恢复，私密目录任何文件都不含正文明文。构造 ANVS v1 fixture，确认旧快照解码后各块无格式；验证未知 ANFM blob 通过加密快照写入/读取仍保持原始字节。扩展 PrivateEditorRulesTest.kt，确认编辑文本/待办时不丢失未编辑块的 ID、图片引用和格式区间。

- [x] **步骤 2：运行私密存储测试确认失败**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*PrivateStoreTest' --tests '*PrivateEditorRulesTest'

预期：新格式或 v1 兼容用例因 ANVS codec 仍是 v1 而失败。

- [x] **步骤 3：升级私密快照 codec**

将内层 PrivateVaultSnapshotCodec 版本升到 2，并将 readBlocks(input, version) 改为按快照版本读取：v1 不消费格式字段并赋默认格式；v2 在每块旧字段之后读写有界的 nullable opaque formatting blob。外层 ANPR 不变。格式解码为 Unsupported 时保留原 blob；仅成功写入私密加密 snapshot 后，才清除已确认移除的元数据。为直接验证 v1 fixture，将 codec 可见性限制在 internal，不公开为应用 API。

块读取必须把 ANVS 版本传入，不允许依赖当前版本猜测字段布局：

        private fun readBlocks(input: DataInputStream, snapshotVersion: Int): List<NoteBlockDraft> {
          // v1: 读取既有块字段并赋默认格式
          // v2: 再读取 nullable formatting blob，并保留不支持的原始 bytes
        }

- [x] **步骤 4：让私密块合并规则保留富文本和块身份**

更新 PrivateEditorRules.mergeBlocks 和 PrivateModuleViewModel.saveNote，使保存接收有序 NoteBlockDraft 列表；按 block ID 合并待办，不重建/丢弃未编辑图片、引用、OCR 块或格式范围。先运行 PrivateEditorRulesTest 确认保留块 ID/格式用例通过。

私密保存接口从正文字符串改为有序块列表，待办状态仍由原有块 ID 绑定：

        fun saveNote(
          title: String,
          blocks: List<NoteBlockDraft>,
          todos: List<PrivateTodoDraft>,
        )

- [x] **步骤 5：在私密编辑页面复用富文本组件**

PrivateEditorScreen 将现有单字符串 body 编辑改为按 NoteBlockDraft 显示/编辑 TEXT、QUOTE、OCR_TEXT；使用 FormattedTextBlockField 与 B/I/S/H/引用规则。普通仓库、普通草稿和普通 FTS 不参与私密编辑。私密输入字段与工具栏不得存入普通导航状态。

- [x] **步骤 6：接入现有加密草稿接口**

私密编辑变更使用稳定 draft ID 和递增 localRevision，通过 PrivateRepository.writeAtomic 加密保存 PrivateEditSnapshot；重新解锁后仅在匹配 noteId/revision 的私密编辑会话中提示恢复/丢弃。清理使用 PrivateRepository.clearIfAtMost(access, draftId, revision) 的修订号保护；锁定、切后台或访问令牌失效时取消待写任务、清空 UI 明文状态，不向普通 filesDir/drafts/ 写入任何数据。

- [x] **步骤 7：运行私密 codec、规则、草稿和 UI 测试**

运行：

        .\gradlew.bat :app:testDebugUnitTest --tests '*PrivateStoreTest' --tests '*PrivateEditorRulesTest' --tests '*PrivateRepositoryTest'
        .\gradlew.bat :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.anan.anannotes.ui.PrivateRichTextEditorTest"

预期：ANVS v1/v2、私密格式草稿恢复、私密正文密文隔离、锁定清屏和格式操作测试通过；任何私密输入均不进入 Room、普通草稿、FTS 或日志。

## 任务 6：全链路兼容、回归和 MuMu 验收

**文件：** 仅在前述测试暴露问题时修改对应实现/测试；更新总体技术设计的实现基线，不改独立密码学参数文档。

- [x] **步骤 1：逐条核对规格覆盖**

按富文本设计第 1–8 节核对：范围/非目标、选区与粘贴/IME、B/I/S 叠加、H/QUOTE、Unicode、图片拆块、Room/ANAN/ANVS 兼容、搜索纯文本、私密隔离、错误恢复和皮肤颜色。发现未覆盖项必须先补测试和计划任务，不跳过验收。

- [x] **步骤 2：运行完整 JVM、lint 与 debug 构建**

运行：

        Set-Location D:\anan\env-check
        .\gradlew.bat :app:testDebugUnitTest
        .\gradlew.bat :app:lintDebug
        .\gradlew.bat :app:assembleDebug

预期：命令退出码均为 0；记录实际测试总数与 lint warning，不把既往 59/59 结果当作本轮证据。

- [x] **步骤 3：在 MuMu 运行完整 Android instrumentation suite**

运行：

        .\gradlew.bat :app:connectedDebugAndroidTest

验收普通数据库 v5→v6 migration、旧正文搜索、编辑器选区/IME、草稿恢复、私密加密 snapshot v1→v2 和锁定隔离。设备输出若未出现 connected device，先恢复 MuMu 的 ADB 连接再重试；不要转而操作小米手机。

- [x] **步骤 4：检查最终改动和文档状态**

逐项确认只改富文本所需源文件、测试、Room schema 和技术设计进度；确认没有新 Manifest 权限、依赖或导出实现。记录本轮命令和结果；当前 workspace 无 .git，不生成 commit。

### 任务 6 验收记录（2026-09-27）

- 规格覆盖审计补充了 IME composition 持久化策略、显式返回保存时结束 composition、粘贴/撤销/重做区间回归，以及私密格式工具栏的 OFF/ON/MIXED 状态和皮肤强调色反馈测试。
- 在 `D:\anan\env-check` 执行 `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`：成功；JVM 93 项、0 失败、0 跳过；Lint 0 errors、6 warnings、2 hints；Debug APK 40,109,437 字节。
- `$env:ANDROID_ADB_SERVER_PORT='5038'; $env:ANDROID_SERIAL='emulator-5554'; .\gradlew.bat connectedDebugAndroidTest`：MuMu Android 12／API 32，43 项、0 失败、0 跳过。
- 应用源 Manifest 没有 `uses-permission` 声明；富文本实现没有增加 Gradle 依赖或导出功能。工作区没有 `.git`，未执行 commit。
- MuMu 自动化测试通过不等于小米真机上的视觉与输入法验收；最终发布版大小也尚未验证。

## 顺序与完成定义

按任务 1 → 2 → 3 → 4 → 5 → 6 顺序执行。每个任务的失败测试必须先复现，再实现最小改动并重跑该测试。普通编辑与持久化通过后再接入私密范围，最终才运行全量设备测试。只有所有任务列明的验证退出码为 0、普通与私密旧数据兼容、格式区间不跨越 Unicode 代理项边界，且私密数据隔离用例通过，才可将本专项标记完成。

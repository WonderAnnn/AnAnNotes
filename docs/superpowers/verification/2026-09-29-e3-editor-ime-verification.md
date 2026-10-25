# E3：编辑页与输入法验证报告

日期：2026-09-29  
工程：`D:/anan/env-check`  
设备：MuMu，Android 12 / API 32，测试设备标识 `NTH-AN00 - 12`  
输入法：MuMu 当前配置的搜狗输入法

## 本轮范围

围绕编辑页的工具栏与输入法间距、键盘收起恢复、待办 Enter 行为、图片多选删除栏、图片后的正文编辑，以及笔记皮肤与状态栏图标联动进行验证。用户已批准将状态栏联动并入 E3。未将“候选栏高度变化、切换不同输入法、真实小米 11 Pro”记为已验收。

## 根因与改动

- 普通编辑工具栏的 IME 边界在 MuMu/API 32 上未复现此前描述的大空隙：实际打开搜狗输入法并测量窗口 IME inset，工具栏到键盘顶边在 8 dp 内；键盘收起后 bounds 回到打开前位置。正文末端到工具栏另有 12 dp 紧凑留白，不是键盘与工具栏间的间隙。
- 图片多选删除栏原有 8 dp 竖向 padding。新增窗口 Insets 回归先失败，测得操作内容到底部键盘边界为 12.0 dp；将竖向 padding 调为 4 dp 后，同一测试通过并纳入点按/删除既有回归。
- 正文容器应用 Scaffold `contentPadding` 后显式调用 `consumeWindowInsets(contentPadding)`，明确 Insets 只由该层负责消费。工具栏继续采用 Compose 原生 `imePadding()`，未缓存首次键盘高度或写死动画终点。
- 待办字段的软键盘 IME action 与 Enter key event 都能插入下一个独立待办块。连续创建三项后每项有独立复选框，后续输入仍落入正文文本块；原正文和图片后续正文用例均通过。
- Compose 原始 `SemanticsNode` 窗口 bounds 按像素处理，直接与 `decorView` 的 IME inset 比较；容差按 density 换算，避免把原始像素再次乘 density。图片多选栏还检查键盘收起后恢复到无键盘基线的位置。
- 待办字段的软键盘 IME action 与 Enter key event 都能插入下一个独立待办块。连续创建三项后每项有独立复选框，后续输入仍落入正文文本块；测试同时断言焦点转移到新建待办。
- 编辑路由接管状态栏图标外观，按当前笔记背景亮度选择深/浅状态栏图标；编辑期间 Android 导航栏图标仍由全局主题控制。Activity 重建时也显式重设全局导航栏图标，避免回到 `enableEdgeToEdge()` 的系统默认外观。离开编辑路由后导航层重新应用全局系统栏外观；私密页仍强制浅色系统栏图标，退出后恢复全局主题。

## TDD 与自动化证据

新增/扩展仪器测试：

- `editorToolbarStaysWithinEightDpOfVisibleKeyboardTop`：以 window bounds 像素和 decorView IME inset 测量工具栏边界，收起后检查位置恢复；正文—工具栏留白限于紧凑范围。
- `imageMultiSelectActionBarAlsoTracksVisibleKeyboardTop`：覆盖多选栏在 IME 可见时的边界和收起后的回位。修复前实测 12.0 dp，修复后通过 8 dp 验收。
- `repeatedTodoImeActionsCreateSeparateCheckboxesWithoutAbsorbingBodyText`：连续创建三项并向末尾正文块继续输入，检查数据库块类型、文字和复选框数量。
- `pressingPhysicalEnterInTodoCreatesANewIndependentBlock`：用 Compose Enter key event 验证待办块分裂与独立保存。
- `SystemBarAppearanceTest.editorSkinControlsStatusBarIconsAndRestoresGlobalThemeOnExit`：浅色全局主题下切换“夜墨黑/极简白”皮肤，检查状态栏图标随皮肤变化、导航栏图标不变；退出编辑恢复全局状态，并复核私密页规则。
- `SystemBarAppearanceTest.editorActivityRecreationRestoresGlobalNavigationIconsAndKeepsNoteStatusIcons`：令应用主题与系统主题相反，重建 Activity 后检查导航栏继续遵循应用主题、状态栏继续遵循笔记皮肤。

既有 `pressingEnterInTodoCreatesANewIndependentTodoBlock` 和 `noteWithTrailingImageProvidesEditableSpaceBelowIt` 一并回归。

## 验证结果

运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug
.\scripts\verify-build-contract.ps1
```

- JVM：170 项通过，0 失败。
- MuMu 仪器测试：140 项，0 失败，1 项跳过；跳过项为 MuMu 默认未启用打孔覆盖层时的切口专项测试。
- 编辑页定向仪器测试：28/28 通过；系统栏主题与 Activity 重建测试：3/3 通过。
- Lint：0 errors、13 warnings、2 hints；warning/hint 为当前工程既有报告项。
- Debug APK：40,767,785 字节。
- 构建契约：Application ID、最低 SDK 29、无 INTERNET 权限、应用名“安安简记”均通过。

状态栏联动定向测试先在旧实现下按预期失败（夜墨黑下仍显示浅色主题对应的深色图标）；Activity 重建用例也先复现导航栏回到系统默认图标。修复后 `SystemBarAppearanceTest` 3/3 通过。审阅还指出原 IME bounds 单位混用，现已统一用 window 坐标像素，并加上待办焦点与图片操作栏收起回位断言。全量命令于 2026-09-29 再次完成：`:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug`，构建成功；Lint 为 0 errors、13 warnings、2 hints，构建契约脚本全部通过。

## 尚未验收

没有连接或操作真实小米 11 Pro；也没有自动改变搜狗候选栏高度或切换其他输入法。IME action 和 Enter key event 使用仪器测试触发，不能替代手指点击 OEM 输入法按键。Android API 29/35/36、手势/三键导航、大字号与横屏输入法矩阵仍待后续设备验证。

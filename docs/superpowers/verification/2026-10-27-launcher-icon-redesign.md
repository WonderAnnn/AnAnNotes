# 2026-10-27 启动器图标改版验证

## 变更（提交 51860d0）

按用户选定方案（内嵌预览主方案，整体放大 20%）替换启动器图标：**深灰钢笔倾斜 45°、笔尖接触略小的灰色文档**（文档 41×51dp 于 108dp 画布、含折角/两条横线/墨迹曲线），背景色沿用 `#F1F2F0`。

- `drawable/ic_launcher_foreground.xml`：新前景矢量（外层 group 以 (54,54) 为轴缩放 1.2，笔为嵌套 rotate 45° group）。
- `drawable/ic_launcher_monochrome.xml`：同轮廓单色版（主题图标用）。
- 5 组密度 legacy 位图：`scripts/regenerate-legacy-icons.ps1`（System.Drawing 按同几何栅格化，本机无 cwebp 故以 PNG 替换 WebP；方形 18% 圆角遮罩 + 圆形遮罩两版，遮罩外透明）。
- 背景 `ic_launcher_background.xml` 未改动。

## 验证链

1. **位图像素结构校验**（xxxhdpi 192px）：四角遮罩外 A=0（方/圆均过）；文档区 `#C9CFD2`、笔杆 `#454F54`、底色 `#F1F2F0` 精确命中；笔尖轴区采样为深色抗锯齿过渡值，几何存在。
2. **构建**：`:app:assembleDebug` BUILD SUCCESSFUL。
3. **构建契约**：Application ID / minSdk / 无 INTERNET / 应用名 4/4 PASS。
4. **装机实测**（API 35 模拟器冷启动、`adb install`、`am start` 启动正常）：应用抽屉截图经视觉分析确认——灰色圆角文档 + 深灰钢笔右上向左下倾斜笔尖触纸 + 折角 + 两横线 + 墨迹曲线全部在位，清晰无畸变无遮罩裁切异常（证据 `D:\anan\outputs\icon-test-drawer.png`、`icon-test-home.png`）。

## 边界

- 已知取舍（用户确认接受）：文档四角略超严格自适应安全圈（r=33），常见方形/超椭圆启动器显示完整，极紧圆形遮罩可能轻微裁角；笔身全部在安全区内。
- 单色（主题）图标未在模拟器上截图验证（启动器主题图标开关不在本轮范围），轮廓与前景同源，风险低。
- 本轮为纯资源变更，未重跑全量仪器套件（最近一次全量在 abe51f0，应用代码此后无改动）；启动烟雾 + 上述链路覆盖。

## 发布候选回归（同日追加）

带新图标的发布候选验证：

- `:app:assembleRelease`（含 lintVital 与无 INTERNET 门禁）BUILD SUCCESSFUL；未签名包 86,434,204 B，SHA-256 `54024c8a1c039d18ed8c46969e2a13930ca98cbaa4c5384c3b5919d665823c29`（仅作审查用，非分发物）。
- API 35 全量仪器套件（`am instrument` 直跑）：**首次 234 中 1 败**——`EditorScreenTest#camelliaAndGinkgoSkinsCanBeSelectedAndPersisted` 因列表出现两张同名"新皮肤选择保存验证"卡片（一张为本轮、一张为 2026-10-26 直跑残留）。**定性：跨运行残留数据，非代码缺陷**——直跑不经 Gradle 自动卸载，App 数据跨轮保留，而该用例以固定标题断言唯一。**`pm clear com.anan.anannotes` 后复跑：OK（234 tests）全绿**（`D:\anan\outputs\api35-iconbuild-fullsuite-clea r.log`）。
- **纪律沉淀：同一 AVD 上直跑全量套件前先 `pm clear com.anan.anannotes`**，否则固定标题用例会被上一轮残留击中。

## 再生

修改图标几何后运行 `powershell -File scripts/regenerate-legacy-icons.ps1` 重出各密度 PNG；前景/单色矢量直接编辑 `drawable/ic_launcher_*.xml`。

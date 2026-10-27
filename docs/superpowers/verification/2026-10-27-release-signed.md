# 2026-10-27 Release 1.1 正式签名验证

交接指南 P0「Release 尚未配置长期签名」**本日闭环**。

## 密钥（用户掌控）

- 正式密钥由用户在本机生成：`D:\keys\AnAnNotes-release.jks`，RSA 4096、有效期 10000 天、alias `anannotes`、DN `CN=AnAnNotes, O=AnAn`。
- 口令只存在于用户记忆与仓库根 gitignored 的 `keystore.properties`（构建期由 Gradle 读取）；密钥与口令**未提交仓库、未出现在任何对话内容**。生成过程两次踩坑（中文 keytool 不认"是"致 DN 循环 → 改 `-dname` 参数；`D:\keys` 目录不存在致写入失败 → 先 mkdir），已向用户提供可复制的完整命令。
- 签名配置即 `2026-10-26-release-signing-pipeline-ready.md` 演练过的可选式脚手架，无代码改动。

## 构建与签名验证

- versionCode 1→**2**、versionName 1.0→**1.1**（发布动作的一部分）。
- `:app:assembleRelease :app:bundleRelease` BUILD SUCCESSFUL（含 lintVital 与无 INTERNET 门禁）。
- APK `apksigner verify`：**VERIFIED**（v2）；签名者证书 DN 与用户密钥一致，证书 SHA-256 `013881549da5afddd9b6a4c18f4003ff9f2a56b9626f7f334ac4530e21a25e3a`。
- AAB `jarsigner -verify`：**jar 已验证**（自签证书 PKIX 提示为预期）。
- 产物（复制存档于 `D:\anan\outputs\AnAnNotes-Release-Signed-20261027\`，含 README/权限/依赖/SHA256SUMS）：
  - `AnAnNotes-1.1-release-signed.apk` 86,446,492 B，SHA-256 `f775a739…31854ad`
  - `AnAnNotes-1.1-release-signed.aab` 50,058,520 B，SHA-256 `2de46e94…667f01a`
- 权限实测（aapt2）：WAKE_LOCK / ACCESS_NETWORK_STATE / RECEIVE_BOOT_COMPLETED / FOREGROUND_SERVICE + 自定义 not-exported receiver 权限，**无 INTERNET**。

## 装机验证（API 35 模拟器，AnAn_API35_Pixel6 冷启动）

1. 首次尝试覆盖安装被拒：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`（设备上是 debug 签名旧包）——**签名机制正确行为**，非缺陷。
2. 卸载后全新安装：**Success**；`am start` 启动至安安简记首页，截图经视觉确认正常渲染（标题栏/设置/新建按钮/笔记卡片，无崩溃黑屏白屏，`D:\anan\outputs\release-launch2.png`）。
3. 同签名覆盖安装 `install -r`：**Success**，设备侧 `versionCode=2` 确认，且**旧笔记数据在覆盖后保留**（升级不丢数据的附加证据）。

## 边界

- 指南第 8 节的发布前顺序中，**小米真机验收仍未执行**（等设备连入；执行清单见状态补充文档第五节）。皮肤策略 A/B/C 决策仍待用户（选 A 则以已知限制记录）。
- 本次模拟器上并存过一台非项目 AVD（"Nottingham"），未触碰；后续 adb 多设备时命令需 `-s` 指定。
- debug→release 的覆盖安装不可能（签名不同），真机上首次安装正式包前需卸载 debug 版——已在本记录留痕。

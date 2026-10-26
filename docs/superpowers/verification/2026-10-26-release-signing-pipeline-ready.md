# 2026-10-26 Release 签名管线就绪（脚手架 + 演练验证）

## 背景

交接指南 P0「Release 尚未配置长期签名」的完成标准要求仓库外用户密钥、递增 versionCode、重建并验证 APK/AAB 签名。真实密钥必须由用户生成保管（不进仓库、不发聊天），但机器侧可以先完成：签名配置脚手架 + 一次性测试密钥全管线演练 + 无配置回退验证。本轮即此准备工作。

## 改动

- `app/build.gradle.kts`：新增**可选** release 签名配置——仓库根存在 `keystore.properties` 时读取 `storeFile/storePassword/keyAlias/keyPassword` 并应用到 release 构建型；不存在时行为与之前完全一致（产出 `app-release-unsigned.apk`）。
- `.gitignore`：新增 `/keystore.properties`、`*.jks`、`*.keystore`，杜绝密钥材料误提交。
- `keystore.properties.example`：模板 + 密钥生成命令示例（强调密钥放仓库外）。

## 演练证据（一次性测试密钥，仓库外 `D:\anan\outputs\test-signing\`，已删除）

1. `keytool -genkeypair`（RSA 2048、30 天、自签）生成测试密钥 → 写 `keystore.properties`（该文件全程未出现在 `git status`，gitignore 生效）。
2. `:app:assembleRelease :app:bundleRelease` → **BUILD SUCCESSFUL**，产出 `app-release.apk`（86,460,262 B）与 `app-release.aab`（50,075,522 B），命名即证明已签名。
3. `apksigner verify --print-certs`：**Verifies**，v2 方案签名（minSdk 29，v2 为最低必需；v3 仅密钥轮换场景需要），签名者证书 DN 与测试密钥一致，SHA-256 `7427d2a8…811`。
4. `jarsigner -verify`（AAB）：**jar 已验证**；告警均为一次性测试证书预期项（自签/短期/无时间戳）。
5. 删除 `keystore.properties` 与测试密钥 → 重建 `:app:assembleRelease` → 回退产出 `app-release-unsigned.apk`，确认无配置时零行为变化。
6. `scripts/verify-build-contract.ps1`：Application ID / minSdk / 无 INTERNET / 应用名全部 PASS。

**踩坑记录**：现代 keytool 默认 PKCS12 库会**忽略与 storepass 不同的 -keypass**（密钥实际用 store 口令保护），首次演练即因此报 `KeytoolException: Given final block not properly padded`；properties 里两口令一致后通过。模板注释已写明。

## 用户正式签名操作步骤（剩余唯一的 P0 人工环节）

1. 仓库外生成正式密钥（口令自定，建议 store/key 一致）：
   `keytool -genkeypair -v -keystore <仓库外路径>/AnAnNotes-release.jks -alias anannotes -keyalg RSA -keysize 4096 -validity 10000`
   妥善备份密钥与口令（丢失即无法给既有用户发升级包）。
2. 复制 `keystore.properties.example` 为仓库根 `keystore.properties`，填入密钥绝对路径与口令（该文件被 gitignore）。
3. 递增 `versionCode`（当前 1 → 2）后 `.\gradlew.bat :app:assembleRelease :app:bundleRelease`。
4. 重出证据链：`apksigner verify --print-certs`、权限清单（无 INTERNET 门禁已内建于构建）、依赖清单、断网复验、SHA-256——不能沿用 2026-10-17 审查包的任何旧报告。

## 边界

- versionCode 保持 1 未动：递增绑定在正式签名发布动作上，由用户执行步骤 3 时完成。
- 测试密钥已彻底删除，仓库内无任何密钥材料；本演练不构成正式签名。
- Release APK/AAB 的安装与升级验证须用**正式**密钥包执行（测试包签名不同，无法覆盖安装验证升级路径）。

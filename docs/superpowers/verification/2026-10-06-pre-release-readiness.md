# 2026-10-06 发布前验收记录

## 结论

**暂不具备正式公开发布条件。** Release 变体可以编译，测试和静态检查通过；但 Release APK 与 Android App Bundle（AAB）均未使用发布密钥签名，当前不能作为正式分发包或上传至 Google Play。本轮没有上传应用、修改 Play Console 或生成/保存长期发布密钥。

## 本轮验证

| 检查项 | 结果 |
|---|---|
| Release 构建 | `:app:assembleRelease`、`:app:bundleRelease` 成功 |
| JVM 单元测试 | 强制重跑 350 项，0 失败、0 错误、0 跳过 |
| Release Lint | 0 errors、22 warnings、2 hints |
| 构建契约脚本 | `scripts/verify-build-contract.ps1` 通过：Application ID、`minSdk`、应用名及无 `INTERNET` 权限 |
| 合并 Release Manifest | 未发现网络、广泛存储/媒体、联系人、电话、录音或位置权限；仅含 WorkManager 等依赖声明的 `WAKE_LOCK`、`ACCESS_NETWORK_STATE`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE` 及 AndroidX 动态接收器权限；`allowBackup=false` |
| 私密页截图策略 | Release 非 debuggable 时启用 `FLAG_SECURE`；Debug 构建保留截图能力 |
| 包体积 | APK 64,116,138 bytes；AAB 27,742,548 bytes，均低于项目 300 MB 上限 |
| Google Play 目标 API | 当前 `targetSdk=36`；符合自 2026-08-31 起新应用和更新要求的 API 36 下限 ([官方要求](https://developer.android.com/google/play/requirements/target-sdk)) |

本轮完整 Release 构建命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :app:bundleRelease --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
```

## 构建产物

| 产物 | 路径 | SHA-256 | 状态 |
|---|---|---|---|
| Release APK | `env-check/app/build/outputs/apk/release/app-release-unsigned.apk` | `30E0BCD4B1BB74804BD5C62754EFB6142F35AF1320F5605863160B9F1D2677C9` | 未签名；`apksigner verify` 明确拒绝 |
| Release AAB | `env-check/app/build/outputs/bundle/release/app-release.aab` | `15AFBF00425772414359FEDE13B717CD8A7BE950B316638A6A4CAAB4DA3B6D09` | 未签名；包内没有 JAR 签名条目 |
| 现有 Debug APK | `env-check/dist/AnAnNotes-2026-10-06-author-attribution-final-debug.apk` | `6FF2269E19EE1C8523E1E47BEE8C5A299892D0B5DD53EBDFEA6F8D5C3341294D` | 签名有效，但签名证书为 `Android Debug`，只供调试/侧载测试，不能当作正式版 |

当前 Gradle 配置没有 Release `signingConfig`，项目目录中也没有发布 keystore。Debug APK 的版本为 `1.0 (versionCode 1)`、`minSdk 29`、`targetSdk 36`。尚未确认该包名是否已在商店登记；若已有商店版本，上传前必须按商店记录递增 `versionCode`。

## 尚未关闭的发布项

1. **确定分发渠道并配置签名。** 若选择 Google Play，新应用应使用上传密钥签名 AAB，并在 Play App Signing 中配置；签名材料应保存在用户控制的安全位置，不通过聊天传递。Android 官方说明：[Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756)、[上传 AAB](https://developer.android.com/studio/publish/upload-bundle)。
2. **完成商店资料。** 尚未核验公开隐私政策 URL、Data safety 表单、内容分级、商店图标/截图、描述、开发者资料及 Play Console 应用设置。Google Play 要求发布应用填写 Data safety；即使应用声明不收集数据，也需提交该表单并提供隐私政策链接 ([官方说明](https://support.google.com/googleplay/android-developer/answer/10787469))。
3. **检查账号专属测试要求。** 若使用 2023-11-13 后创建的个人 Play 开发者账号，公开生产发布前需要至少 12 名测试者连续加入封闭测试 14 天，并申请生产权限 ([官方要求](https://support.google.com/googleplay/android-developer/answer/14151465))。
4. **补足最终设备验收。** 最新完整 API 29/35/36 全量回归证据见 [2026-10-04 报告](./2026-10-04-api29-35-36-full-suite-verification.md)；五款皮肤的专项导出验证见 [2026-10-05 报告](./2026-10-05-five-patterned-skins-verification.md)。本轮 ADB 因运行环境缺少 `HOME` 且无法建立 `\.android` 配置目录而不能连接设备，所以未重新运行连接测试。小米 11 Pro、QQ/微信真实分享、其他厂商 DocumentsProvider 的最终验收仍未完成。
5. **确认发布来源。** 当前工程目录不含 Git 元数据，无法把本次包体绑定到提交号，也无法完成分支状态审计；正式发布前应在受版本控制的干净工作区复建并留存产物校验值。

Lint 的 22 条 warning 和 2 条 hint 未阻断构建；包括 API 37 SDK 可用后的旧目标版本提示、建议更新项及 KTX/Compose 代码风格提示。API 36 目前满足 Google Play 的 API 目标要求；API 37 的兼容行为仍应在对应 AVD 上单独验收后再决定是否升级。

## 下一步

正式签名包需要用户决定分发渠道，并提供/配置自己的现有上传密钥，或明确授权生成一把由用户自行备份保管的新密钥。拿到签名配置后，再生成并验证签名 APK/AAB、做 Release 安装冒烟测试，并完成目标商店的资料与轨道验收。此报告不代表已向任何商店提交或发布。

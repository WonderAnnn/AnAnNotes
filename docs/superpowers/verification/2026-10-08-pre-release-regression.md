# 2026-10-08 发布前回归与测试包记录

## 本轮改动

- A4 600 DPI PNG 仍按 `4961 × 7016` 像素输出，并写入 600 DPI 的 PNG `pHYs` 元数据。修正原图解码上限：从 9 MP 提高到 16 MP，避免常见 12 MP 手机照片在绘制前被强制降采样一半；导出仍采用 128 行分带渲染，并对 OOM 返回可恢复错误。
- 皮肤选项卡增加圆角裁剪，使按压反馈沿圆角矩形边界显示；皮肤卡片选中态 elevation 设为 0 dp。
- 首页与私密首页新建按钮、首页批量操作栏阴影设为 0 dp，减少不必要的悬浮阴影。
- 剪贴板代码将 `ClipData` 构造提取为可测试方法，确认私密内容在交给 Android 前设置 `EXTRA_IS_SENSITIVE`。

## 验证结果

| 项目 | 结果 |
|---|---|
| JVM 单元测试 | 352 项，0 失败、0 错误、0 跳过（`--rerun-tasks`） |
| Release Lint | 0 errors、19 warnings、2 hints |
| Release/Debug 构建 | Debug APK、AndroidTest APK、Release APK、AAB 全部构建成功 |
| API 35 AVD 全量 Android 回归 | 234 项测试事件，5 项跳过、2 项失败；失败项针对系统剪贴板元数据读回和横屏图片面板等待笔记加载 |
| API 35 定向复测 | 上述 2 个失败项连同剪贴板边界测试共 4 项重测通过；之后又重测 A4 细节、皮肤保存、长按多选等 4 项，通过 |
| API 35 APK 冒烟 | 最终 Debug APK 安装成功，启动后 `MainActivity` 位于前台 |
| 构建契约 | Application ID、`minSdk=29`、应用名、无 `INTERNET` 权限均通过 |
| 小米 15 实机 | 未连接到 ADB，本轮未在实机上安装或操作；需要用户手动验收 |

全量 Android 测试的两个失败随后都在定向复测中通过，但因单次完整回归耗时约 24 分钟，本报告记录为“定向复测通过、完整套件未在修改测试断言后再次全量重跑”，不将其记作最终全绿。API 35 UI 测试曾观察到 SystemUI 将剪贴板 extras 替换为 `com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY`；因此敏感标记现在直接在交给系统前的 `ClipData` 上断言。小米 15 上是否抑制私密剪贴板预览，仍需实机确认。

本轮 Release 门槛命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:bundleRelease --offline --no-daemon --console=plain
```

## 构建产物

| 产物 | 体积 | SHA-256 | 状态 |
|---|---:|---|---|
| [Debug 测试 APK](../../../env-check/dist/AnAnNotes-2026-10-08-pre-release-test-debug.apk) | 75,433,487 bytes | `649E1D2F9D0550556A174D73CBAE14880018BF827213110EB6C36A87A260E7F9` | Android Debug 证书签名（v2），只供侧载验收，不是正式发布签名 |
| Release APK | 64,132,522 bytes | `B446D3DFEAD93ED01851812FD816B66F976CAF4DD3C0BAC9A87C664D9685ADA0` | 未签名 |
| Release AAB | 27,744,670 bytes | `07890F63442B0A3CD369D5A02982D8FDF3F960D35A399D46BE2277297FA47A98` | 无签名条目 |

测试 APK 元数据：`com.anan.anannotes`、版本 `1.0 (versionCode 1)`、`minSdk 29`、`targetSdk 36`。文件可直接安装供小米 15 人工测试；由于是 Android Debug 签名，它不能作为商店发布包。

## 尚未完成的正式发布门槛

1. 在小米 15 真机确认 600 DPI PNG 的文字/照片清晰度与相册/文件管理器识别、皮肤按压反馈圆角和整体阴影观感。
2. 完成 Android 全量回归的最终全绿重跑；继续记录 API 29/36 本轮关键变更的定向复测。
3. 使用用户控制的发布密钥签名 Release APK/AAB；不得用 Debug APK 冒充正式版。
4. 递增 `versionCode`，完成目标商店资料、隐私政策、Data safety 与发布轨道验收。

因此当前交付物是**小米 15 手动验收用 Debug APK**，不是已正式发布的版本。

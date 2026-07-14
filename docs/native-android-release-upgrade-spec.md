# Android 原生覆盖升级规格

## 背景

旧 Tauri Android 正式包的 `applicationId` 是 `com.vibedrop.mobile`，当前自动生成的 Tauri Android `versionCode` 是 `1004`、`versionName` 是 `0.1.4`。原生 Android 工程为了未来覆盖升级也使用同一个 release `applicationId`，但初始 `versionCode` 仍是 `1`。如果直接拿原生 release 包安装到现有手机，Android 包管理器会认为这是降级安装并拒绝，除非用户先卸载旧版；卸载会破坏“保留旧数据并迁移”的目标。

## 目标

1. 原生 Android release 包 `applicationId` 继续保持 `com.vibedrop.mobile`。
2. 原生 Android release `versionCode` 必须大于旧 Tauri 正式包 `1004`。
3. debug 预览包继续使用 `.nativepreview` 后缀，能和旧 Tauri 正式包并排安装。
4. debug 预览包名称上继续能看出是预览版本，release 名称上不再写 `preview`。

## 方案

- `defaultConfig.versionCode = 1100`
- `defaultConfig.versionName = "0.2.0-native"`
- `debug.applicationIdSuffix = ".nativepreview"`
- `debug.versionNameSuffix = "-preview"`

`versionCode` 选择 `1100` 的原因是留出清晰的版本段：旧 Tauri 0.1.x 使用 100x，原生 0.2.x 从 1100 起步。后续正式发版时只需要继续递增，比如 `1101`、`1102`。

## 验收

1. `assembleDebug` 通过，包名为 `com.vibedrop.mobile.nativepreview`。
2. `assembleRelease` 通过，包名为 `com.vibedrop.mobile`。
3. release `versionCode` 大于 1004，理论上能覆盖安装旧 Tauri 正式包并保留私有目录用于迁移。

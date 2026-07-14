# Android 原生正式覆盖部署规格

## 背景

原生 Android debug 预览包使用 `com.vibedrop.mobile.nativepreview`，适合和当前 Tauri 正式包并排测试；但最终替换 Tauri 必须安装 release 包 `com.vibedrop.mobile`。只有包名、签名和 `versionCode` 都满足 Android 包管理器规则，系统才会保留旧应用私有目录，让原生启动时读取旧 `files/history.json` 并迁移到 Room。

## 目标

1. 提供一个明确的原生 release 构建、签名、可选安装脚本。
2. 默认只构建并签名，不自动覆盖手机上的当前正式包，避免误操作。
3. 用户显式传 `--install` 时才执行 `adb install -r` 覆盖安装并重启。
4. 安装前校验原生 release `applicationId` 是 `com.vibedrop.mobile`，`versionCode` 高于旧 Tauri `1004`。
5. 复用当前项目已有 keystore 变量：`VIBEDROP_KEYSTORE_PATH` 和 `VIBEDROP_KEYSTORE_PASS`。

## 技术方案

新增 `scripts/deploy-native-android-release.sh`：

- 使用 `mobile/src-tauri/gen/android/gradlew -p native/android :app:assembleRelease` 构建。
- 找到 `native/android/app/build/outputs/apk/release/app-release-unsigned.apk`。
- 用 Android SDK build-tools 里的 `apksigner` 签名到 `VibeDrop-native-release-signed.apk`。
- 读取 `output-metadata.json` 校验：
  - `applicationId == com.vibedrop.mobile`
  - `versionCode > 1004`
- 默认输出 signed APK 路径后退出。
- `--install` 时解析 ADB 设备，执行 `adb install -r`，然后启动 `com.vibedrop.mobile/com.vibedrop.mobile.nativeapp.MainActivity`。

## 为什么默认不安装

这个脚本用于最终覆盖 Tauri 正式包，风险高于 debug 预览包。默认不安装可以让我们在开发中持续验证“包能构建、能签名、版本元数据正确”，但不会不小心替换用户当前正在用的正式包。真正要实机迁移时再显式加 `--install`。

## 验收

1. `bash -n scripts/deploy-native-android-release.sh` 通过。
2. `./scripts/deploy-native-android-release.sh --skip-install` 能构建并签名 release APK。
3. 输出 metadata 显示 `com.vibedrop.mobile` 和 `versionCode 1100`。
4. 不传 `--install` 不会调用 `adb install`。

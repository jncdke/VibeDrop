# Android debuggable 常开规格

## 背景

VibeDrop 是当前用户自用的 Android + macOS 局域网工具。排查断连、SQLite 历史、WebView localStorage、媒体缓存和原生日志时，需要读取 Android App 私有目录；但 release 包默认 `debuggable=false`，`adb shell run-as com.vibedrop.mobile ...` 会返回 `package not debuggable`。

## 目标

1. 所有 Android 构建都允许调试，尤其是当前稳定 Tauri release 构建。
2. 保持现有 `applicationId=com.vibedrop.mobile`、签名和版本号逻辑不变，避免丢失手机私有数据。
3. 继续使用现有 `./scripts/deploy-android.sh` 安装流程，不新增 debug/internal 版本分支。
4. 验证安装后 `run-as com.vibedrop.mobile` 可用。

## 实现

在 `mobile/src-tauri/gen/android/app/build.gradle.kts` 的 `release` buildType 里设置：

```kotlin
isDebuggable = true
isJniDebuggable = true
```

Gradle 会在最终合并 Manifest 中生成 `android:debuggable="true"`。不直接手写 Manifest，是为了让构建系统成为唯一来源，避免后续 buildType 覆盖。

## 影响

开启后，只要手机授权了 ADB 调试，连接这台电脑就可以用 `run-as` 读取 App 私有数据、SQLite、缓存和 WebView 存储。这个项目按自用工具处理，因此接受这个安全取舍。

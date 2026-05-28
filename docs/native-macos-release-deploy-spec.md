# 原生 macOS 正式部署规格

## 背景

当前原生 macOS 端已经能通过 `scripts/deploy-native-macos.sh` 生成真实 `.app` 预览包，默认名称是 `VibeDrop Native.app`，bundle id 是 `com.vibedrop.nativepreview`，安装到 `~/Applications`。这个设计适合和当前 Tauri 正式版并排验证，不会覆盖 `/Applications/VibeDrop.app`。

但 Tauri 正式版还有一个关键系统集成：`VibeDropShare.appex` Share Extension，以及 `发送到 VibeDrop.workflow` Finder 右键服务。Finder 或系统分享菜单把文件交给它们后，会写入 `~/.vibedrop/finder-share-requests` 队列，主 App 再读取这个队列并发送给手机。原生 AppModel 已兼容读取这个队列，所以正式替换时必须把同一个 Share Extension 嵌入原生 `.app`，并在安装时刷新 Finder 服务入口，否则系统分享入口会退化。

## 目标

1. 保留现有原生预览脚本默认行为，继续生成可并排运行的 `VibeDrop Native.app`。
2. 给预览脚本增加 `--with-share-extension`，需要时构建、嵌入、签名并注册 `VibeDropShare.appex`。
3. 新增正式部署包装脚本，默认生成 `VibeDrop.app`、bundle id `com.vibedrop.desktop`、目标目录 `/Applications`，并自动带上 Share Extension。
4. 正式部署脚本默认只构建不安装，必须显式传 `--install` 才覆盖 `/Applications/VibeDrop.app`，避免误替换当前可用版本。
5. 签名链路兼容稳定签名证书；正式替换时优先复用 `~/.vibedrop/signing/vibedrop-codesign.keychain-db` 中的本地证书，缺失时默认失败，除非显式允许 ad-hoc fallback。

## 非目标

1. 本阶段不删除 Tauri 工程，也不删除旧 `scripts/deploy-desktop.sh`。
2. 本阶段不改 Share Extension 的业务代码；它继续使用当前 `desktop/share-extension` 工程和同一队列目录。
3. 本阶段不自动执行正式覆盖安装；脚本能力准备好后由人工确认时机。

## 技术方案

`scripts/deploy-native-macos.sh` 继续负责底层构建：SwiftPM 编译 `VibeDropMacApp`，手动组装 `.app` bundle，写入 Info.plist、资源和图标。当传入 `--with-share-extension` 时，它会调用 `scripts/generate-share-extension-project.rb` 确保 Xcode 工程存在，用 `xcodebuild` 构建 `VibeDropShare.appex`，复制到 `Contents/PlugIns/VibeDropShare.appex`，先带 entitlements 签名扩展，再签名主 app。安装后用 `lsregister` 刷新主 app、用 `pluginkit -a` 注册扩展，并运行 `scripts/install-finder-send-workflow.py` 安装 Finder 右键服务。

`scripts/deploy-native-macos-release.sh` 是正式替换入口。它包装底层脚本并固定正式参数：`--app-name VibeDrop`、`--bundle-id com.vibedrop.desktop`、`--install-dir /Applications`、`--with-share-extension`。不带 `--install` 时会追加 `--skip-install`，只产出 bundle 用于验包；带 `--install` 时会先停止可能占用 bundle 的旧进程，然后覆盖安装并打开。

签名分两层处理：底层脚本只执行签名动作，支持 `--sign-identity`、`SIGN_IDENTITY` 和 `KEYCHAIN_PATH`；正式包装脚本负责解析稳定证书，逻辑与旧 Tauri 部署脚本一致，优先使用 `CODESIGN_IDENTITY`，其次在 keychain 里按 `CERT_NAME_PATTERN` 查找。如果找不到稳定证书且 `ALLOW_ADHOC_FALLBACK` 不是 `1`，正式部署会失败。原因是 macOS 辅助功能授权绑定签名身份，频繁 ad-hoc 签名会让用户反复授权。

## 验收标准

1. `bash -n scripts/deploy-native-macos.sh scripts/deploy-native-macos-release.sh` 通过。
2. `./scripts/deploy-native-macos.sh --skip-install --no-sign` 能生成预览 `.app`。
3. `./scripts/deploy-native-macos-release.sh --skip-install --no-sign` 能生成正式名称和正式 bundle id 的 `.app`，并包含 `Contents/PlugIns/VibeDropShare.appex`。
4. `swift test` 在 `native/macos` 通过。
5. README 清楚说明预览部署、正式验包、正式安装三个入口。

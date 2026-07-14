# VibeDrop native macOS

这是 macOS 原生重构入口。当前阶段已经从 Swift Package core 推进到真实 App 闭环：`VibeDropNativeCore` 覆盖 v1 协议 action、设备身份和历史结构；`VibeDropMacStorage` 用 GRDB/SQLite 导入旧 Tauri 的 `~/.vibedrop/history.jsonl`，原生新历史以 SQLite 为主，同时追加兼容 JSONL 供现有 Home Vault 同步脚本读取；`VibeDropMacServer` 使用 SwiftNIO 提供 HTTP/WebSocket/UDP 服务，覆盖 `/discover`、`/pair/request`、`/pair/status/{id}`、`/ws`、`/share-extension/paths` 和 UDP `discover_probe`；`VibeDropMacApp` 提供 SwiftUI/AppKit 窗口、状态栏菜单、配对、拖拽/Finder 分享发送、历史、热力图、诊断、登录项和正式 bundle 部署路径。

## 当前构建

```sh
cd native/macos
swift test
```

启动当前原生 macOS 服务预览：

```sh
cd native/macos
VOICEDROP_PIN=1234 VIBEDROP_PORT=9001 swift run VibeDropMacServerPreview
```

启动当前原生 macOS App 壳：

```sh
cd native/macos
swift run VibeDropMacApp
```

构建并安装真实 `.app` 预览包：

```sh
./scripts/deploy-native-macos.sh
```

默认会生成 `VibeDrop Native.app`，bundle id 为 `com.vibedrop.nativepreview`，安装到 `~/Applications` 并打开。它可以和当前 Tauri 正式包并排存在，用于验证状态栏、辅助功能授权、登录项等真实 macOS 行为；如果只想产出 bundle 不安装，可运行 `./scripts/deploy-native-macos.sh --skip-install`。如果要在预览包里同时验证 Finder/系统分享菜单入口，可运行 `./scripts/deploy-native-macos.sh --with-share-extension`，脚本会构建并嵌入当前 `desktop/share-extension` 里的 `VibeDropShare.appex`。

生成原生正式包但不覆盖安装：

```sh
./scripts/deploy-native-macos-release.sh
```

这个入口会生成正式 `VibeDrop.app`，bundle id 为 `com.vibedrop.desktop`，并默认嵌入 Share Extension；不传 `--install` 时只产出 bundle，方便验包。确认要用原生端替换当前 `/Applications/VibeDrop.app` 时再运行：

```sh
./scripts/deploy-native-macos-release.sh --install
```

正式部署会优先复用 `~/.vibedrop/signing/vibedrop-codesign.keychain-db` 里的稳定签名证书，缺失时默认失败；如果只是临时验证，可显式设置 `ALLOW_ADHOC_FALLBACK=1` 允许 ad-hoc 签名。这里要谨慎，因为 macOS 辅助功能权限和签名身份绑定，签名身份频繁变化会导致系统要求重新授权。

正式安装时还会刷新 Launch Services、注册 `VibeDropShare.appex`，并安装 `发送到 VibeDrop.workflow` Finder 右键服务；这两个入口都会写入 `~/.vibedrop/finder-share-requests`，由原生 App 读取并发送给当前选中的 Android 设备。

这个预览服务已经是真实网络监听。当前预览已接入文本/回车输入模拟、图片剪贴板、文件收件箱、桌面到 Android 分片发送、文件夹/多文件 ZIP 打包发送、手机保存回执、SQLite 历史写入、兼容 JSONL 追加、SwiftUI/AppKit 基础窗口、macOS 登录项开机启动开关、Finder/Share Extension 共享队列兼容，以及 App 内诊断日志/诊断 JSON 导出；首次运行时 macOS 会弹辅助功能权限，授权后 Android 发来的 `type`、`type_enter`、`enter` 才能真正写到当前焦点应用。图片会保存到 `~/.vibedrop/received-images` 并写入系统剪贴板，文件会保存到 `~/Downloads/VibeDrop 收件箱`，分片接收的临时文件在 `~/.vibedrop/incoming-downloads`。历史页读取原生 SQLite 全量记录，支持导出完整 JSON 到 `~/Downloads`，并能对保留本地路径的单项或多项记录直接“系统打开/打开全部”。诊断事件写在 `~/Library/Application Support/VibeDrop/diagnostics/events.jsonl`，导出文件写到 `~/Downloads`，只记录服务状态、配对、连接数量和文件发送数量，不记录正文、剪贴板内容和文件路径。登录项开关使用 `SMAppService.mainApp`，真正注册需要打包签名后的 `.app` bundle，`swift run` 开发态可能显示为不支持。

## 模块现状

1. `VibeDropNativeCore`：协议、设备身份、历史模型、状态机。
2. `VibeDropMacStorage`：GRDB 历史库、JSONL 迁移、查询和兼容追加。
3. `VibeDropMacServer`：SwiftNIO HTTP/WebSocket/UDP discovery、pair、Share Extension 本机入口和消息效果分流。
4. `VibeDropMacRuntime`：CGEvent 输入模拟、剪贴板、收件箱、分片接收、分片发送、SQLite 历史写入。
5. `VibeDropMacApp`：SwiftUI/AppKit app shell，覆盖服务状态、连接信息、配对、连接设备、拖拽/选择/Finder 分享发送、状态栏、诊断、登录项、最近历史、历史筛选、热力图、媒体预览和系统打开。

# VibeDrop native macOS

这是 macOS 原生重构入口。当前阶段先建立 Swift Package 级别的 core 模型，覆盖 v1 协议 action、设备身份和历史记录结构，并加入 `VibeDropMacStorage` 数据层，用 GRDB/SQLite 导入旧 Tauri 的 `~/.vibedrop/history.jsonl`。`VibeDropMacServer` 已开始接入 SwiftNIO，提供原生 HTTP/WebSocket/UDP 预览服务，覆盖 `/discover`、`/pair/request`、`/pair/status/{id}`、`/ws` 的 v1 认证、ping/pong、文本消息分流，以及 UDP `discover_probe` 应答。

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

这个预览服务已经是真实网络监听。当前预览已接入文本/回车输入模拟、图片剪贴板、文件收件箱、桌面到 Android 分片发送、文件夹/多文件 ZIP 打包发送、手机保存回执、SQLite 历史写入、SwiftUI/AppKit 基础窗口、macOS 登录项开机启动开关，以及 App 内诊断日志/诊断 JSON 导出；首次运行时 macOS 会弹辅助功能权限，授权后 Android 发来的 `type`、`type_enter`、`enter` 才能真正写到当前焦点应用。图片会保存到 `~/.vibedrop/received-images` 并写入系统剪贴板，文件会保存到 `~/Downloads/VibeDrop 收件箱`，分片接收的临时文件在 `~/.vibedrop/incoming-downloads`。诊断事件写在 `~/Library/Application Support/VibeDrop/diagnostics/events.jsonl`，导出文件写到 `~/Downloads`，只记录服务状态、配对、连接数量和文件发送数量，不记录正文、剪贴板内容和文件路径。登录项开关使用 `SMAppService.mainApp`，真正注册需要打包签名后的 `.app` bundle，`swift run` 开发态可能显示为不支持。

## 后续模块

1. `VibeDropNativeCore`：协议、设备身份、历史模型、状态机。
2. `VibeDropMacStorage`：GRDB 历史库和 JSONL 迁移，后续承接历史查询、热力图聚合和 Home Vault 导出。
3. `VibeDropMacServer`：discover/pair/WebSocket v1 路由和消息效果分流，后续接 SwiftNIO HTTP/WebSocket 与 UDP discovery。
4. `VibeDropMacRuntime`：把 server effect 接到 CGEvent 输入模拟和 SQLite 历史写入。
5. `VibeDropMacApp`：SwiftUI/AppKit app shell，当前已覆盖服务状态、连接信息、配对、连接设备、拖拽普通文件/文件夹/多文件发送和最近历史。

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

这个预览服务已经是真实网络监听，但 Accessibility 输入模拟、剪贴板广播、文件落盘和窗口 UI 还在后续模块中接入。
当前预览已接入文本/回车输入模拟和 SQLite 历史写入；首次运行时 macOS 会弹辅助功能权限，授权后 Android 发来的 `type`、`type_enter`、`enter` 才能真正写到当前焦点应用。

## 后续模块

1. `VibeDropNativeCore`：协议、设备身份、历史模型、状态机。
2. `VibeDropMacStorage`：GRDB 历史库和 JSONL 迁移，后续承接历史查询、热力图聚合和 Home Vault 导出。
3. `VibeDropMacServer`：discover/pair/WebSocket v1 路由和消息效果分流，后续接 SwiftNIO HTTP/WebSocket 与 UDP discovery。
4. `VibeDropMacRuntime`：把 server effect 接到 CGEvent 输入模拟和 SQLite 历史写入。
5. `VibeDropMacApp`：SwiftUI/AppKit app shell。

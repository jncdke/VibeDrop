# VibeDrop native macOS

这是 macOS 原生重构入口。当前阶段先建立 Swift Package 级别的 core 模型，覆盖 v1 协议 action、设备身份和历史记录结构，并加入 `VibeDropMacStorage` 数据层，用 GRDB/SQLite 导入旧 Tauri 的 `~/.vibedrop/history.jsonl`。下一阶段再接入 SwiftUI/AppKit 窗口、NSStatusItem、Accessibility、NSPasteboard 和 SwiftNIO WebSocket server。

## 当前构建

```sh
cd native/macos
swift test
```

## 后续模块

1. `VibeDropNativeCore`：协议、设备身份、历史模型、状态机。
2. `VibeDropMacStorage`：GRDB 历史库和 JSONL 迁移，后续承接历史查询、热力图聚合和 Home Vault 导出。
3. `VibeDropMacApp`：SwiftUI/AppKit app shell。
4. `VibeDropMacServer`：SwiftNIO HTTP/WebSocket 与 UDP discovery。

# VibeDrop native rewrite workspace

这个目录承载原生重构实现。旧 Tauri 代码在 `desktop/` 和 `mobile/` 中继续保留，直到原生端通过协议兼容、数据迁移、UI 复刻和稳定性测试。

## 当前阶段

- `android/`：Kotlin + Jetpack Compose 原生 Android 客户端骨架，目标是先连接现有 Mac 端。
- `macos/`：Swift 原生 macOS package，已接入 SwiftNIO 服务、Runtime 输入/媒体/文件处理、SQLite 历史和首版 SwiftUI/AppKit App 壳。

## 实施原则

1. 先兼容 v1 协议，再扩展 v2。
2. 先导入旧历史，再写新 UI。
3. 先 Android 连接旧 Mac，再 macOS 替换旧 Mac。
4. 原生端达到功能 parity 前，不删除旧 Tauri 实现。

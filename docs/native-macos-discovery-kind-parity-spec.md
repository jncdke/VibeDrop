# macOS 原生 Discovery Kind 兼容规格

## 背景

旧 Tauri 桌面端、移动端 Rust parser、Android 原生 parser 和 `docs/protocol-v1-fixtures/messages/discover-response.json` 都使用 `{"kind":"desktop"}` 表示桌面发现响应。但 macOS 原生 `DiscoverResponse` 当前输出 `kind:"vibedrop_desktop"`，这会造成新 Android 或旧 Tauri Android 扫描原生 Mac 时丢弃响应，表现为“服务开着但扫描不到”。

## 目标

1. macOS 原生 HTTP `/discover` 和 UDP discovery 都恢复输出 v1 兼容的 `kind:"desktop"`。
2. macOS Swift 测试对照协议 fixture，防止后续再次漂移。
3. Android 原生 discovery parser 保持兼容 `desktop`，并额外接受早期原生预览版的 `vibedrop_desktop`，降低过渡期互通风险。

## 非目标

1. 不改变 discovery URL、UDP probe、端口、server id 或配对流程。
2. 不引入协议 v2 discovery；当前仍以 v1 兼容为首要目标。

## 验收

1. `swift test` 通过，且 discover 测试断言 `kind == "desktop"`。
2. Android debug/release 构建继续通过。
3. `git diff --check` 通过。

# v1 Legacy file_download Fixture 规格

## 背景

当前原生 Android 到 Mac 的文件发送已经改为 `incoming_file_start/chunk/complete` 分片协议，这是更稳的主路径。但旧 Tauri v1 协议里还存在 `file_download`：单条 WebSocket 消息携带 `file_base64`，macOS 原生服务仍保留这个 legacy action 用于旧端兼容。共享 protocol fixtures 缺少 `file_download` 样本，Android 协议常量也没有列出它，容易让后续重构误以为这个 action 不属于 v1。

## 目标

1. 在 `docs/protocol-v1-fixtures/messages` 增加 `file-download.json`。
2. macOS `VibeDropMessageTests` 解码该 fixture，并校验文件名、MIME 和 base64 字段。
3. Android `VibeDropActions` 增加 `FileDownload` 常量，协议 fixture 测试识别该 action。
4. 不改变当前原生 Android 的主发送路径，仍优先使用分片传输。

## 非目标

1. 不重新启用 Android 原生端的 legacy 整文件发送。
2. 不改变 macOS legacy `file_download` 运行时处理逻辑。
3. 不扩大 v1 协议字段。

## 验收

1. Android `:app:testDebugUnitTest` 通过。
2. macOS `swift test` 通过。
3. `git diff --check` 通过。

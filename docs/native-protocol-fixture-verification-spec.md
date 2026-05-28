# 原生协议 Fixture 自动验证规格

## 背景

原生 Android 和原生 macOS 已经覆盖旧 Tauri 主闭环，但协议兼容不能只靠人工点测。当前仓库已有 `docs/protocol-v1-fixtures` 作为 v1 JSON 字段形状基准，如果原生端后续重构时改错 `device_id`、`type_enter`、`incoming_file_*`、历史导出字段名，旧端或 Home Vault 可能不会立刻在编译期报错。

## 目标

1. Android 原生端增加本地 unit test，直接读取协议 fixture，验证发送端生成 JSON 与现有 v1 字段兼容。
2. 验证 `parseAction` 能识别所有带 `action` 的 v1 消息，并且对非 WebSocket action fixture 返回空。
3. 验证 Android 历史 JSON 导出包含 Home Vault 和旧脚本需要的 sender/receiver camelCase 与 snake_case 字段。
4. 让 `:app:testDebugUnitTest` 成为原生 Android parity 的自动验证入口之一。

## 非目标

1. 本轮不做真 WebSocket 端到端集成测试；局域网、权限、系统剪贴板仍需要真机验收。
2. 本轮不改协议 v2，不引入 envelope、checksum 或能力协商。
3. 本轮不依赖 Android instrumentation；这些测试应在 JVM 本地跑，速度快，适合每次提交前执行。

## 设计

Android test source set 增加三类验证：

1. `AuthPayload` 对照 `auth-primary.json` 和 `auth-clipboard-sync.json`，确保主连接和后台剪贴板连接都继续使用 snake_case v1 字段。
2. `parseAction` 扫描消息 fixture，覆盖 `auth`、`ping`、`pong`、`clipboard`、`type`、`type_enter`、`enter`、`image_clipboard`、`incoming_history_session_start`、`incoming_file_*`。
3. `HistoryArchiveJson` 直接构造一条带完整身份元数据和缩略图的历史，断言导出 JSON 同时包含 `senderBaseDeviceId/sender_base_device_id`、`receiverHost/receiver_host`、`targetHost`、`thumbnailDataUrl/thumbnail_data_url`。

## 验收

1. `:app:testDebugUnitTest` 通过。
2. Android debug/release 构建继续通过。
3. `git diff --check` 通过。

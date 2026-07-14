# VibeDrop protocol v1 fixtures

这些 fixture 是原生重构的兼容基准，不包含真实用户历史或真实剪贴板内容。它们用脱敏设备名、脱敏文本和短 base64 占位符表达当前 v1 协议的字段形状。新 Android、新 macOS 和旧 Tauri 端互通时，至少要继续识别这些消息。

## 目录

- `messages/`：WebSocket 与 HTTP/UDP discovery 的单条协议消息。
- `history/`：历史记录导入、Home Vault 解析和 SQLite 迁移时需要兼容的样本。

## 兼容原则

1. v1 action 名称保持不变。
2. 未识别字段必须忽略，不能让旧端因为新字段崩溃。
3. 必填字段缺失时要给出明确错误，而不是静默写入坏历史。
4. 设备身份字段要同时保留原始值和规范化展示值。
5. 文件传输必须使用 start/chunk/complete/saved/error 的完成确认语义。

# Home Vault 显式设备角色字段解析规格

## 背景

Android 和 macOS 原生端现在已经在历史导出里保存更完整的 sender/receiver 身份，例如 `senderDeviceId`、`senderBaseDeviceId`、`receiverDeviceId`、`receiverHost`、`targetBaseDeviceId`。但 Home Vault 同步脚本的 `derive_device_roles()` 仍主要按旧 Tauri 字段推断：`clientId/deviceId` 表示手机，`targetDeviceName/targetServerId` 表示 Mac。这个策略对旧历史可用，但在多台手机、多台接收 Mac、后台剪贴板连接和原生新字段同时存在时，会丢掉更准确的身份信息。

## 目标

1. Home Vault 优先使用显式 `sender*` / `receiver*` 字段推导发送端和接收端。
2. 同时识别 camelCase、snake_case、`source*`、`target*` 兼容字段。
3. 旧 Tauri 历史仍走原有 `client/target/direction` 兜底逻辑。
4. 设备 canonical 化把 `base_device_id` 也纳入 alias labels，让同一物理设备的主连接、剪贴板连接和旧 id 更容易归并。

## 设计

新增内部 helper：

1. `device_has_identity(device)`：判断一个设备 dict 是否至少有 id/name/host/server_id/base_device_id。
2. `explicit_device_roles(entry, source)`：从新原生字段提取 sender/receiver；两端至少有一端明确时优先返回，缺失的一侧继续用旧 `client/target/direction` 逻辑补齐。
3. `canonicalize_device()` labels 加入 `base_device_id`。

字段优先级：

1. sender：`senderDeviceId/sourceDeviceId/sender_device_id/source_device_id`，name 使用 `senderName/sourceDeviceName/...`，base 使用 `senderBaseDeviceId/sourceBaseDeviceId/...`。
2. receiver：`receiverDeviceId/receiver_device_id` 明确标记接收端；存在这些字段时，`targetDeviceId/targetServerId/targetHost/targetDeviceName/targetBaseDeviceId` 也作为 receiver 兼容别名读取。
3. 如果只有一侧有显式 sender/receiver，另一侧从旧推断结果补齐，避免旧字段和新字段混合的历史丢失 sender 或 receiver。
4. 如果没有显式 sender/receiver，保持旧逻辑：按 `direction == desktop_to_mobile` 决定 client/target 谁是 sender。

## 验收

1. Python unit test 覆盖新 Android 原生导出、半新半旧混合记录、旧 Tauri mobile_to_desktop、desktop_to_mobile 四种形状。
2. `python3 -m unittest tests.test_home_vault_device_roles` 通过。
3. `git diff --check` 通过。

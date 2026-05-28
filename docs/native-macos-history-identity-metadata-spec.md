# macOS Native History Identity Metadata Persistence Spec

## 背景

macOS 原生运行层写入历史时已经构造了完整 `DeviceIdentity`：`deviceId`、`baseDeviceId`、`displayName`、`role`、`host`、`ip`、`port`。但 `MacHistoryDatabase` 旧表结构只保存 `deviceId/displayName`，读取历史时会丢掉 base id、角色和网络身份。结果是历史页 alias merge 只能依赖当前在线客户端；手机离线后，旧历史里同一台 Android 的主连接、剪贴板连接、旧 client id 或显示名可能再次拆开。

## 目标

1. macOS SQLite 历史库保存 sender/receiver 的完整设备身份元数据。
2. 新写入记录在 `fetchRecent/fetchAll/fetchEntry` 后仍保留 `baseDeviceId/role/host/ip/port`。
3. 已有数据库通过 GRDB migration 无损新增 nullable columns，不删除、不重写旧历史。
4. 历史 UI 的 canonical merge 自动吃到这些字段，离线时也能更稳定合并同一设备。
5. 兼容旧 Home Vault JSONL 追加格式；新增字段只能作为向后兼容的额外信息，不能破坏现有解析。

## 非目标

1. 不尝试为所有旧记录反向推断 base id。旧数据缺什么就保留缺失；展示层仍可用显示名和机器名做弱归并。
2. 不改 `HistoryEntry` public model。现有 `DeviceIdentity` 已具备需要的字段。
3. 不修改 Android Room schema；本轮只补 macOS 数据层截断问题。

## 设计

新增 SQLite columns：

- sender：`sender_base_device_id`、`sender_role`、`sender_host`、`sender_ip`、`sender_port`
- receiver：`receiver_base_device_id`、`receiver_role`、`receiver_host`、`receiver_ip`、`receiver_port`

迁移策略：

1. 保持现有 `create native history tables` migration 语义。
2. 新增 `add device identity metadata` migration，通过 `ALTER TABLE history_entries ADD COLUMN ...` 扩展旧库。
3. 新建库也会先创建旧字段，再跑扩展迁移，避免 migration 名称已记录的旧库和新库分叉。

读写策略：

1. `insert(_:)` 写入完整身份字段。
2. `mapHistoryEntry` 读取完整字段并恢复 `DeviceIdentity`。
3. legacy JSONL 追加继续保留现有字段，同时可追加 base id/role/host/ip/port 等额外字段，旧解析器会忽略未知字段。

## 验收

1. 插入带完整 sender/receiver identity 的记录后，`fetchAll()` 返回的字段与插入值一致。
2. 现有 legacy JSONL 追加测试仍通过。
3. `swift test` 全部通过。

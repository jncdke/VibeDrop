# Android Native History Identity Metadata Persistence Spec

## 背景

Android 原生端写历史时已经能拿到更完整的设备身份：本机 `deviceId/baseDeviceId/deviceName`，目标 Mac 的 `stableId/displayName/host/ip/port`，以及连接角色 `primary`、`desktop`。但 Room `history_entries` 当前只保存 sender/receiver 的 id 和 name，导出给 Home Vault 或在历史页离线归并时会丢掉 host、base id、role 等关键证据。

## 目标

1. Android Room 历史主表保存 sender/receiver 的完整身份元数据。
2. 新记录写入时保留 `baseDeviceId/role/host/ip/port`。
3. 导出 JSON 和 Home Vault payload 带上这些额外字段，同时保留旧字段名，保持协议兼容。
4. 历史页 alias merge 把新字段纳入匹配，不只依赖当前保存设备列表。
5. 旧数据库通过 Room v3 migration 无损升级，不 destructive migration。

## 非目标

1. 不回填所有旧历史缺失字段；旧记录能从 id/name 归并的继续归并。
2. 不改变 v1 WebSocket 协议 action。
3. 不改变 Android 文件 item 表结构。

## 设计

Room `history_entries` 新增 nullable columns：

- sender：`senderBaseDeviceId`、`senderRole`、`senderHost`、`senderIp`、`senderPort`
- receiver：`receiverBaseDeviceId`、`receiverRole`、`receiverHost`、`receiverIp`、`receiverPort`

写入策略：

1. Android 发往 Mac：sender 为当前手机，role=`primary`；receiver 为目标 Mac，role=`desktop`，保存 host/ip/port。
2. Mac 发往 Android：sender 为 Mac，role=`desktop`；receiver 为当前手机，role=`primary`。
3. 导入旧 JSON 时解析 camelCase 和 snake_case 字段；缺失 sender 时继续回退到当前手机。

导出策略：

1. 保留旧字段：`senderDeviceId`、`senderName`、`receiverDeviceId`、`receiverName`、`targetName` 等。
2. 增加新字段：camelCase 和 snake_case 各一套，方便 Home Vault、旧脚本和未来原生端都能吃。
3. `targetDeviceName/targetHost` 优先输出 receiver host，再退回 receiver name。

## 验收

1. Android debug/release 构建通过。
2. `rg Room.databaseBuilder` 确认所有数据库入口继续共享 migrations。
3. 新历史导出 JSON 包含 base id、role、host、ip、port 字段。

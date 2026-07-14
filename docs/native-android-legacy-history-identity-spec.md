# Android 原生旧历史身份迁移规格

## 背景

旧 Tauri Android 的 `history.json` 主要记录手机发往 Mac 的历史。早期记录通常只有 `target`、`targetName`、`targetAlias`、`targetDeviceName`、`targetServerId` 等接收端字段，不一定有 `senderDeviceId` 和 `senderName`。原生导入器如果把缺失发送端统一写成“导入历史”，历史页和 Home Vault 就无法把这些记录归到当前手机名下。

## 目标

1. 旧记录缺少发送端字段时，默认发送端使用当前 Android 原生身份 `identity.deviceId/deviceName`。
2. 接收端显示名优先使用旧 UI 里更像“用户可读名称”的字段：`targetAlias`、`targetName`，再退到 `targetDeviceName`/host。
3. 接收端 stable id 优先使用 `targetServerId`、`targetId`、`serverId`，再退到 `target` 或可读名称。
4. 已有新格式字段 `senderName`、`receiverName`、`senderDeviceId`、`receiverDeviceId` 不被覆盖。

## 字段优先级

发送端：

- id：`senderDeviceId` -> `sourceDeviceId` -> 当前手机 `identity.deviceId`
- name：`senderName` -> `sourceDeviceName` -> 当前手机 `identity.deviceName`

接收端：

- name：`receiverName` -> `targetAlias` -> `targetName` -> `targetDeviceName` -> `target`
- id：`receiverDeviceId` -> `targetServerId` -> `targetId` -> `serverId` -> `target` -> 接收端 name

## 验收

1. 旧 Tauri `history.json` 导入后，缺少 sender 字段的记录在原生历史筛选里归到当前手机。
2. 接收端筛选优先显示“一加/mini/MacBook”这类可读名，而不是优先显示 `.local` host。
3. 新格式导入不回退、不覆盖已有 sender/receiver 字段。
4. Android debug 构建通过。

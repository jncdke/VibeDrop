# Android Native History Identity Merge Spec

## 背景

Tauri 版历史和 Home Vault viewer 都已经支持把同一台物理设备的不同历史标签归并，例如 `MacBook`、`overlorddeMacBook-Air-4.local`、server id，以及旧数据里的 `未知发送端`。Android 原生历史页目前的发送端/接收端筛选 key 只取原始 id 或显示名的小写值，所以旧记录、手动设备、Bonjour 设备和导入数据可能拆成多个候选，和 Tauri 行为不等价。

## 目标

1. Android 原生历史页的发送端、接收端筛选候选必须按归并后的物理设备统计数量，并继续按数量降序排列。
2. 当前 Android 设备的别名要归并：`identity.deviceId`、`identity.baseDeviceId`、`identity.deviceName`、空发送端，以及移动端旧记录里的 `未知发送端` 都应视为当前手机。
3. 已保存 Mac 设备的别名要归并：`stableId`、`id`、`displayName`、`host`、`ip` 以及 `.local` 去后缀后的机器名都应作为同一组候选。
4. 历史页筛选必须使用同一套 key，避免候选显示已合并但点击后匹配不到旧记录。
5. 不在这一轮改写历史数据库原始字段。原始 sender/receiver 字段保留，方便以后追溯来源；本轮只在 UI 筛选层做 canonical view。

## 非目标

1. 不新增 Room schema 版本。
2. 不硬编码单一用户设备以外的业务规则；允许保留通用的“未知发送端归当前手机”规则，因为 Android 本机历史中旧 Tauri 发送记录天然代表当前手机。
3. 不改变 Home Vault receiver。Home Vault 已经有服务端 canonical 化，本轮只补 Android 原生历史页。

## 设计

新增历史身份归并辅助结构：

- `HistoryIdentityContext`：持有当前 Android 身份和已保存桌面设备。
- `HistoryEndpointIdentity`：描述某条历史端点的 `id/name/direction`。
- `canonicalHistoryEndpointKey(...)`：把一个端点映射到稳定筛选 key。
- `canonicalHistoryEndpointLabel(...)`：决定候选展示名，优先展示用户可读名称。

关键规则：

1. 发送端如果是空、`未知发送端`，并且方向不是 `desktop_to_mobile`，归并到当前 Android。
2. 任一端点如果命中当前 Android 的 id/name/base id，归并到当前 Android。
3. 任一端点如果命中某台保存 Mac 的 `stableId/id/displayName/host/ip`，或机器名规范化后互相包含，归并到该 Mac 的 `stableId`。
4. 未命中已知设备时回退到原始 id/name 的规范化值，保证未知设备仍能独立筛选。

## 验收

1. 历史页发送端下拉中不再把 `未知发送端` 和当前手机拆成两个候选。
2. 历史页接收端下拉中不再把同一台 Mac 的 `MacBook`、hostname、server id 拆成多个候选。
3. 候选后的数量来自归并后的记录数。
4. 点击任意归并候选后，列表显示数量和候选数量一致。
5. Android debug/release 均可构建通过。

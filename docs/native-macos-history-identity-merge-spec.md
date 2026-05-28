# macOS Native History Identity Merge Spec

## 背景

Android 原生历史页已经补齐发送端/接收端 alias 归并。macOS 原生历史窗口仍按 `HistoryEntry.sender/receiver.deviceId` 或显示名直接分组，旧 JSONL 里的 `未知发送端`、Mac hostname/server id、Android 主连接/剪贴板连接可能被拆成多个候选。为了让 Android、macOS、Home Vault 三个查看入口一致，macOS 历史页也需要同一类 canonical key。

## 目标

1. macOS 历史页的发送端、接收端筛选按物理设备归并后统计数量，并继续按数量降序排列。
2. 当前 Mac 的 `serverId`、hostname、IP、历史里更短的 `MacBook` 这类机器名要归并成当前 Mac。
3. 当前连接的 Android 设备要用 `deviceId/baseDeviceId/deviceName` 归并；剪贴板同步连接和主连接如果 base id 一致，应算同一台手机。
4. 旧 Mac 历史中的 `未知发送端` 只在当前只有一台 Android 物理设备在线时归到该设备，避免多手机场景误归并。
5. 候选统计和点击筛选必须使用同一套 canonical key。

## 非目标

1. 不改 SQLite schema，不回写旧历史原始 sender/receiver 字段。
2. 不修改 Home Vault 同步脚本。
3. 不在 macOS 端硬编码个人设备别名；已确认的个人别名仍由 Home Vault 服务端脚本处理。

## 设计

新增 macOS 历史 UI 层的身份上下文：

- `MacHistoryIdentityContext`：持有当前 `MacServerConfiguration` 和在线 `MacConnectedClientSnapshot`。
- `ResolvedMacHistoryEndpoint`：保存 canonical key 和展示 label。
- `resolveMacHistoryEndpoint(...)`：把历史端点映射到当前 Mac、在线 Android 设备或原始 fallback。

归并规则：

1. 端点命中当前 Mac 的 `serverId/hostname/ip`，或机器名规范化后与 hostname 包含关系成立，归并到 `mac:<serverId>`。
2. 端点命中在线 Android 的 `deviceId/baseDeviceId/deviceName`，归并到 `android:<baseDeviceId>`；label 优先用非剪贴板连接的 `deviceName`。
3. `未知发送端` 在 `mobile_to_desktop` 或空方向里，如果当前只有一个 Android base id 在线，归并到这台 Android。
4. 未命中时回退到原始 id/name 的小写 key，保证未知设备仍可筛选。

## 验收

1. macOS 历史发送端候选不把同一台在线 Android 的主连接和剪贴板连接拆成两个。
2. macOS 历史接收端候选不把当前 Mac 的 hostname、server id、短机器名拆开。
3. 单手机在线时，旧 `未知发送端` 记录可归到这台手机；多手机在线时保留未知，避免误判。
4. 点击归并后的候选，列表数量与候选括号数量一致。
5. `swift test` 通过。

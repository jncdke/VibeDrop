# Home Vault 查看器设备筛选 Key 规格

## 背景

Home Vault 查看器目前用显示名作为发送端/接收端筛选值，例如 `一加 Ace 5`。这对单手机够用，但多手机或多台同型号设备会被错误合并：两台设备只要显示名一样，候选列表、数量和筛选结果就无法区分。原生重构后 Android/macOS 已经尽量保存稳定 `senderId/receiverId`，查看器也应该优先使用稳定 id 做筛选 key。

## 目标

1. 发送端和接收端筛选的内部 value 使用设备 id 优先，而不是显示名优先。
2. 显示文案继续用易读设备名；当多个设备同名但 id 不同时，在候选文案后追加短 id，避免视觉上混成一个。
3. 保持已 canonical 化的同一物理设备继续合并，因为 canonical 后 id 已经相同。
4. 选项继续显示数量，并继续按数量降序排列。

## 设计

查看器前端将设备处理拆成三步：

1. `historyDeviceIdentity(entry, role)`：从 entry 读取 `{id, name, host}`。
2. `deviceFilterKey(identity)`：`id` 存在时返回 `id:<id>`，否则用 `name:<name>`，最后才是 `unknown`。
3. `deviceFilterLabel(identity, duplicateNames)`：默认显示 `name || id || 未知设备`；如果同一显示名对应多个 id，则追加短 id。

筛选匹配也使用同一个 `deviceFilterKey()`，所以候选列表和实际过滤逻辑一致。

## 验收

1. `scripts/sync-home-vault.py` 语法检查通过。
2. Home Vault 设备角色单测继续通过。
3. `git diff --check` 通过。

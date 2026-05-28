# Android 原生设备排序规格

## 背景

旧 Tauri Android 端在“附近电脑/已保存设备”区域支持调整设备顺序，发送页会按用户习惯展示常用 Mac。原生 Android 当前 `devices` 表只按 `lastSeenAt/updatedAt` 排序，编辑、配对或发现刷新后可能让设备顺序漂移；多 Mac 场景下这会影响肌肉记忆和发送效率。

## 目标

1. 已保存 Mac 顺序必须持久化到 Room，而不是依赖更新时间。
2. 发送页、设置页、后台剪贴板同步读取设备时都使用同一顺序。
3. 设置页连接诊断卡提供“上移/下移”入口，能直接调整设备顺序。
4. 新增设备默认追加到列表末尾。
5. 编辑、重新配对、观察到同一设备时保留已有 `sortOrder`。
6. 迁移不能丢失旧 Room 数据；已有设备自动获得稳定初始顺序。

## 数据设计

`DeviceEntity` 新增：

- `sortOrder: Int`

Room schema 从 1 升到 2：

- `ALTER TABLE devices ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0`

首次迁移后旧设备可能都有 `0`，查询层用 `sortOrder ASC, COALESCE(lastSeenAt, updatedAt) DESC` 作为兼容排序；之后任何显式上移/下移都会重新编号为 `0, 10, 20...`。

## UI 设计

- 设置页每个已保存 Mac 卡片增加“上移/下移”按钮。
- 第一项禁用“上移”，最后一项禁用“下移”。
- 排序操作只改设备顺序，不删除历史，不改 host/PIN，不重建历史。

## 验证

1. Android debug 构建通过。
2. 旧 schema 能迁移到 version 2。
3. 新增/编辑/配对不破坏已有顺序。
4. 上移/下移后发送页卡片顺序随 Room Flow 自动更新。

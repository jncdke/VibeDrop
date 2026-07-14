# Android Native Room Migration Consistency Spec

## 背景

Android 原生端当前有两个入口会打开同一个 `vibedrop-native.db`：

1. 主 App 的 `AppContainer`。
2. 后台剪贴板同步 `ClipboardSyncService`。

`AppContainer` 注册了 `MIGRATION_1_2`，但 `ClipboardSyncService` 直接 `Room.databaseBuilder(...).build()`，没有注册 migration。常规前台启动时主 App 先创建数据库，这个问题不一定立刻暴露；但如果服务独立启动、系统恢复服务、或未来继续增加 Room 版本，服务入口可能因为缺 migration 而打不开数据库，直接影响后台剪贴板同步稳定性。

## 目标

1. 所有打开 `VibeDropDatabase` 的入口必须使用同一套 Room migrations。
2. migration 定义从 `AppContainer` 私有 companion object 移到共享位置。
3. 当前行为不改数据库 schema，只统一 migration 注册方式。
4. 未来新增 version 3/4 时，只需在一个地方追加 migration，主 App 和 Service 自动共享。

## 非目标

1. 本轮不新增 Room schema 字段。
2. 不改变剪贴板同步业务逻辑、通知、连接策略。
3. 不使用 destructive migration，必须继续保留已有用户数据。

## 设计

新增 `VibeDropMigrations`：

- 暴露 `MIGRATION_1_2`。
- 暴露 `ALL` 数组，供 `RoomDatabase.Builder.addMigrations(*VibeDropMigrations.ALL)` 使用。

接入点：

- `AppContainer` 使用共享 migrations。
- `ClipboardSyncService` 使用共享 migrations。

## 验收

1. `AppContainer` 不再持有私有 migration 定义。
2. `ClipboardSyncService` 打开数据库时注册同一套 migrations。
3. Android debug/release 构建通过。

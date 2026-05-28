# Android 原生旧历史包装格式导入规格

## 背景

覆盖安装原生 Android 版本时，App 会从旧 Tauri 私有目录里的 `history.json` 做一次性迁移。真正负责写入 Room 的 `HistoryRepository.importArchive()` 已经支持顶层数组和 `{ "history": [...] }` 两种格式，但迁移入口 `LegacyHistoryImporter` 在调用它之前先强制执行 `JSONArray(rawText)`。如果旧文件来自原生导出、Home Vault 回灌或手工备份，顶层可能是对象包装，迁移会在入口提前失败，导致历史没有导入。

## 目标

1. 一次性迁移入口接受顶层数组：`[{...}]`。
2. 一次性迁移入口接受包装对象：`{ "history": [{...}] }`。
3. 为人工修复和未来工具保留宽容入口：`entries`、`items`、`data` 只要是数组也可作为历史列表读取。
4. 迁移后继续从同一批历史项恢复接收端设备候选，不改变现有设备保存逻辑。
5. 格式错误时返回明确错误，不标记迁移完成，避免用户修复文件后无法再次迁移。

## 非目标

1. 本轮不改变 Room 历史表结构。
2. 本轮不新增 UI 入口，也不改变导入历史按钮的行为。
3. 本轮不做跨设备去重策略调整，仍由 `HistoryRepository.importArchive()` 按 entry id 去重。

## 设计

新增一个仓库层纯解析函数 `extractHistoryArchiveEntries(rawText)`，只负责从 JSON 文本中提取历史数组。`HistoryRepository.importArchive()` 和 `LegacyHistoryImporter.importIfNeeded()` 都使用这个函数，避免迁移入口和核心导入器各自维护一套不一致的判断。`LegacyHistoryImporter` 保留轻量包装函数，方便迁移测试表达覆盖安装语义。

优先级：

1. 文本 trim 后以 `[` 开头：按顶层数组解析。
2. 否则按对象解析，并依次查找 `history`、`entries`、`items`、`data`。
3. 找不到数组时抛出可读异常。

## 验收

1. 顶层数组旧历史可以解析。
2. `{ "history": [...] }` 原生导出可以解析。
3. `{ "entries": [...] }` 兼容包装可以解析。
4. 无历史数组的对象会失败并保留可重试迁移状态。
5. Android JVM 单测、debug/release 构建和 `git diff --check` 通过。

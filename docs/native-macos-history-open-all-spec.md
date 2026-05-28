# macOS 原生历史批量打开规格

## 背景

旧 Tauri macOS 历史媒体预览支持“打开全部原文件”。原生 macOS 历史页已经能展示媒体缩略图，也能在单项预览里用系统默认 App 打开单个文件，但批量传输记录还缺少一键打开全部原文件的入口。

## 目标

1. 历史行中只要存在可打开的本地路径，就显示打开按钮。
2. 单个可打开项目显示“系统打开”，多个项目显示“打开全部”。
3. 点击后使用 `NSWorkspace.shared.open` 逐个打开可访问的文件路径。
4. 不显示不存在路径的项目，避免按钮点了无反应。
5. 不改变历史数据库结构和导出格式。

## 实现方案

- 复用现有 `historyItemOpenPath(_:)` 解析 `localPath` 与 `savedPath`。
- 新增 `openHistoryItems(_:)`，过滤出存在的文件，再交给 `NSWorkspace` 打开。
- `HistoryRow` 增加 `onOpenItems` 回调，内部计算 `openableItems`，在行右侧渲染按钮。

## 验证

1. `cd native/macos && swift test` 通过。
2. 没有本地路径的历史行不出现打开按钮。
3. 单文件历史行显示“系统打开”。
4. 多文件历史行显示“打开全部”。

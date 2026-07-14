# Android 原生历史状态中文化规格

## 背景

旧移动端历史筛选和历史卡片都使用中文状态文案，例如“成功”“失败”“部分完成”。原生 Android 已经在筛选器和搜索索引里实现了中文状态映射，但历史卡片底部 chip 仍直接显示 Room 里的原始状态值，如 `success`、`failed`、`partial`。

## 目标

1. 历史卡片主状态 chip 使用 `historyStatusLabel()`，和筛选器、搜索索引保持同一套中文映射。
2. 不改变数据库字段，不改导入导出格式；这只是展示层文案修正。
3. item 级预览继续使用 `itemStatusLabel()`，因为文件接收 item 的“已保存/接收中”比 entry 的“成功/进行中”更符合语境。

## 验收标准

1. `success` 展示为“成功”。
2. `failed` 展示为“失败”。
3. `partial` 展示为“部分完成”。
4. Android debug 和 release 构建通过。

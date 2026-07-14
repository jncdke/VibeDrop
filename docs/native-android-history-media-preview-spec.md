# Android 原生历史媒体预览规格

## 背景

旧 Tauri Android 历史页点击媒体缩略图时会先进入 VibeDrop 内部预览层，用户可以在同一条历史记录的多张图片/视频之间快速查看，再决定是否交给系统相册、浏览器或默认应用打开。原生 Android 当前已经显示缩略图，也已经有图片/视频打开策略，但点击缩略图会直接触发系统 `ACTION_VIEW`，缺少内部预览这一步。

## 目标

1. 历史卡片里的媒体缩略图点击后先打开原生 Compose 预览弹窗。
2. 预览弹窗展示当前历史记录内的全部 item，支持点击缩略图切换当前 item。
3. 大预览区优先显示 `thumbnailDataUrl`；没有缩略图时显示类型、文件名和状态，不再提示原图缺失。
4. “系统打开”按钮继续复用已有 `openHistoryMediaItem()` 与设置页的图片/视频打开策略。
5. 失败项仍可预览元数据，但不提供系统打开动作，避免跳到不可用 URI。

## 非目标

1. 不在数据库里保存 Android 原图。
2. 不新增全屏播放器或视频解码器。
3. 不改变 Home Vault 同步 payload。
4. 不改变历史记录 schema。

## 技术方案

`HistoryScreen` 增加短生命周期 Compose 状态：

- `previewRecord: HistoryEntryWithItems?`
- `previewIndex: Int`

`HistoryCard` 不再直接调用系统打开，而是把被点击的 `HistoryItemEntity` 回传给 `HistoryScreen`。`HistoryScreen` 找到该 item 在当前记录 sorted items 中的 index，然后展示 `HistoryMediaPreviewDialog`。

弹窗内部：

- 顶部显示当前 item 文件名。
- 中间是稳定高度的大预览区，使用已有 `decodeThumbnailDataUrl()`。
- 下方横向缩略图列表复用小 preview 视觉，但点击只切换当前 item。
- 底部按钮：关闭、系统打开。

这样实现的关键点是把“VibeDrop 内预览”和“系统打开”分层：前者只消费 Room 里已有的轻量字段，性能和权限风险小；后者继续走 Android 系统 Intent，符合用户在设置页里选择的策略。

## 验收

1. 有缩略图的图片历史点击后先出现预览弹窗。
2. 多 item 历史可以在弹窗里切换当前 item。
3. 点击“系统打开”按图片/视频策略打开当前 item。
4. 没有缩略图的文件/失败项不会显示 `no-path` 或原图缺失提示，只显示元数据。
5. `:app:assembleDebug` 通过。

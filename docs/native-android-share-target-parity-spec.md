# Android 原生系统分享目标选择规格

## 背景

旧 Tauri Android 端接收系统分享后，会在发送页顶部显示共享内容，同时允许用户直接点击任意 Mac 设备卡片上的“传图到剪贴板”或“传到收件箱”。原生 Android 当前已经能接收系统分享 URI，并提供一个分享卡片发送到首个可用 Mac，但多 Mac 场景下目标选择不够直接。

## 目标

1. 系统分享进入单张图片后，点击任意已连接 Mac 卡片的“传图到剪贴板”，应把该图片发送到这台 Mac 的剪贴板。
2. 系统分享进入一个或多个 URI 后，点击任意已连接 Mac 卡片的“传到收件箱”，应把这些 URI 发送到这台 Mac 收件箱。
3. 批量 URI 继续使用分片协议和 `history_session_id/history_item_index/history_item_count`，让 Mac 历史保持聚合。
4. 发送成功后清除当前系统分享 payload；全部失败时保留，方便换一台 Mac 重试。
5. 普通手动选择图片/文件的行为保持不变：没有 pending 分享内容时仍打开系统选择器。

## 实现方案

- 在 `SendScreen` 内抽出两个复用入口：
  - `sendImageUriToClipboardForDevice(deviceId, uri, sharedPayloadId?)`
  - `sendUrisToInboxForDevice(deviceId, uris, sharedPayloadId?)`
- `imagePicker` 和 `filePicker` 继续复用这两个入口。
- 设备卡片 `onPickImage` 先检查 `sharedPayload`：
  - 无分享内容：打开图片选择器。
  - 多项分享：提示批量内容应走收件箱。
  - 单项但不是图片：提示用收件箱。
  - 单张图片：直接发送到当前设备剪贴板。
- 设备卡片 `onPickFile` 先检查 `sharedPayload.uris`：
  - 有分享 URI：直接发送到当前设备收件箱。
  - 无分享 URI：打开文件选择器。

## 非目标

1. 不改变系统分享文本的现有草稿填入策略。
2. 不上传原图到 Home Vault。
3. 不改变文件分片大小、背压策略和历史格式。

## 验证

1. Android debug 构建通过。
2. 无分享内容时图片/文件按钮仍打开系统选择器。
3. 有分享单图时图片按钮不再打开选择器，而是发送 pending 图片。
4. 有分享文件/多文件时收件箱按钮按当前设备发送并记录历史。

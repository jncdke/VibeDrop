# 发送输入草稿保留规格

## 背景

发送页设备卡片会随着连接状态、自动发现、认证身份补齐、设备排序和配对更新而重新渲染。旧实现中 `renderSendCards()` 会直接清空 `#send-cards` 并重建 textarea，因此用户在连接中或网络抖动时输入的文字可能随 DOM 重建丢失。

## 目标

连接状态变化和设备信息同步不应清除用户正在输入的文字。只有明确发送成功，或用户自己删除输入框内容时，草稿才应消失。

## 行为规格

1. 用户在某台设备卡片输入文字后，即使该设备从已连接变为未连接、连接中、错误，文字也保留。
2. 自动发现更新 IP、端口、hostname、serverId 导致发送卡片重建时，文字保留。
3. 认证成功后补齐桌面身份信息导致发送卡片重建时，文字保留。
4. 发送成功后保持原行为：清空该设备输入框，并同步清空对应草稿。
5. 发送失败时不清空草稿，方便用户重试。
6. 草稿只保存在当前前端进程内，不写入 localStorage，避免重启 App 后残留敏感文本。
7. 如果重渲染发生时用户正在输入，重建后恢复原输入框焦点和光标位置，尽量不打断输入法编辑。

## 技术方案

新增 `sendDrafts` 内存表，以设备 ID 为 key。`renderSendCards()` 在清空容器前调用 `captureSendDraftsFromDom()` 保存现有 textarea 值；创建新 textarea 后用 `getSendDraft(dev.id)` 还原；textarea 的 `input` 事件实时更新草稿。发送成功清空输入框时调用 `setSendDraft(deviceId, '')`。同时记录重渲染前的活动 textarea、`selectionStart` 和 `selectionEnd`，渲染后用 `requestAnimationFrame()` 恢复焦点和选区。

# Android 原生混合系统分享 Payload 规格

## 背景

Android 系统分享并不总是“纯文本”或“纯文件”。很多 App 分享图片、视频、网页或文档时，会同时带 `Intent.EXTRA_STREAM` 和 `Intent.EXTRA_TEXT`。当前原生发送页收到 `sharedPayload.text` 后会自动填入第一台 Mac 的草稿并立即 `onConsumeSharedPayload()`，如果 payload 同时包含 URI，媒体分享卡片会被提前清掉，导致用户原本要传的图片/文件丢失。

## 目标

1. 纯文本分享：保持当前效率，自动填入第一台 Mac 草稿并消费 payload。
2. 文字 + URI 混合分享：文字填入草稿，但不消费 payload，继续显示媒体/文件分享卡片。
3. 纯 URI 分享：保持当前行为，显示系统分享卡片，用户选择发到 Mac 剪贴板或收件箱。

## 非目标

1. 本轮不把分享文字和文件打包成一个联合传输协议。
2. 本轮不改变系统分享入口 manifest。

## 验收

1. Android debug/release 构建通过。
2. `git diff --check` 通过。

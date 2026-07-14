# 原生 macOS Share Extension 入口等价规格

## 背景

旧 Tauri macOS 服务提供 `POST /share-extension/paths` 给 `VibeDropShare.appex` 调用。这个入口不是普通局域网 API，而是 Mac 本机 Share Extension 把 Finder 选中文件交给主 App 的桥。旧实现有两个关键保护：只允许 loopback 调用，并且在没有支持接收文件的手机在线时立即返回错误。

当前原生 SwiftNIO 版已经实现了同一路由，但只把路径写入 `~/.vibedrop/finder-share-requests` 队列，没有检查请求来源，也不会在没有可接收手机时立刻失败。这会造成两个问题：一是局域网内其他设备也可能向这个接口塞路径；二是 Finder 分享扩展会显示“已交给 VibeDrop”，但实际主 App 因没有目标手机而暂时不会处理，反馈不如旧版直接。

## 目标

1. `/share-extension/paths` 只接受 `127.0.0.1`、`::1` 等本机 loopback 连接。
2. 请求路径必须非空，并且只保留当前文件系统里确实存在的路径。
3. 当前没有 `can_receive_files=true` 的 WebSocket 手机连接时，返回 `409 Conflict` 和中文错误。
4. 有在线接收端时才写入 Finder share queue，由现有 `MacNativeAppModel.processPendingFinderShareRequests()` 继续按当前选中手机发送。
5. 保持 Share Extension 前端不变，因为它已经能读取非 2xx 响应里的 `error` 字段并展示给用户。

## 技术方案

在 `MacConnectedClientRegistry` 增加 `hasFileReceiver()`，用同一把锁读取当前连接快照，判断是否存在 `peer.canReceiveFiles`。`VibeDropHTTPHandler` 构造时接收 registry；处理 `/share-extension/paths` 时先检查 `context.channel.remoteAddress?.ipAddress` 是否 loopback，再解析 JSON、过滤存在路径，最后检查 `hasFileReceiver()`。通过后仍写入 `.vibedrop/finder-share-requests`，不改变 AppModel 的发送路径。

这里不直接在 HTTP handler 里发文件，是为了保留现有队列解耦：Share Extension 是短生命周期进程，只负责提交请求；主 App 负责选择目标手机、打包目录、多文件归档、进度 UI、历史写入和错误清理。

## 验收标准

1. `swift test` 通过。
2. 新增测试覆盖：没有手机在线时，本机 `POST /share-extension/paths` 返回 `409`，错误信息为“当前没有支持接收文件的手机在线设备”。
3. 现有 `/discover`、配对、WebSocket auth、剪贴板广播测试不回退。

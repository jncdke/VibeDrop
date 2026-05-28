# 大文件稳定传输规格

## 1. 问题定义

当前“手机 -> Mac 收件箱目录”的文件发送链路不适合大文件，尤其是视频。

现状调查结果：

- 前端有显式硬限制：`mobile/src/app.js` 中 `MAX_FILE_TRANSFER_BYTES = 32 * 1024 * 1024`
- 发送实现是整文件 `FileReader.readAsDataURL()`，再把完整 base64 放进单条 WebSocket JSON 消息
- Android 分享入口 `NativeShare.readPendingSharedContentBase64()` 会直接 `File.readBytes()` 再整体 base64 编码
- 桌面端 `file_download` 处理会一次性接收完整 `file_base64`，再整体解码保存

这会带来四类问题：

1. **内存峰值高**
   手机端会同时持有原始文件、base64 字符串、JSON 字符串等多个副本。
2. **base64 膨胀**
   base64 大约会额外增加 33% 体积，32MB 原文件会变成约 42MB 文本负载。
3. **单消息过大**
   WebSocket 单帧 / 单消息过大时更容易超时、断连、阻塞 UI。
4. **失败不可恢复**
   任何一个阶段失败，整次传输都要从头开始，没有稳定的大文件传输语义。

## 2. 目标

本次目标聚焦在：

1. 手机端“传文件到下载”支持明显大于 32MB 的文件
2. 大视频、大文件能够稳定发送到 Mac 的 VibeDrop 收件箱
3. 去掉前端硬编码的 32MB 上限
4. 不再依赖整文件 base64 一次性发送
5. 保持现有本地局域网直连架构，不引入云中转

本次不扩展到：

- 图片进剪贴板这条链路
- 断点续传到任意百分比继续
- 多文件并发上传

## 3. 设计原则

### 3.1 统一双向协议

仓库里已经存在一套“桌面 -> 手机”的分片传输协议：

- `incoming_file_start`
- `incoming_file_chunk`
- `incoming_file_complete`
- `incoming_file_saved`
- `incoming_file_error`

本次不重新发明协议，而是把这套协议扩展为**双向通用**。

原因：

- 现有手机接收原生命令已经能按块落盘
- 桌面端也已经有分片发送与最终确认逻辑
- 继续复用能减少协议分叉和未来维护成本

### 3.2 流式优先

大文件发送必须遵循：

- **按块读取**
- **按块发送**
- **按块落盘**

不能再出现任何“整文件读入内存后再处理”的路径。

### 3.3 体验与可靠性并重

只把上限改大不算解决问题。

真正稳定的大文件传输至少要满足：

- 发送过程中 UI 不假死
- WebSocket 缓冲不会无限堆积
- 失败后能明确报错
- 接收端不会留下脏的半成品临时文件

## 4. 第一阶段需求规格

### 4.1 功能需求

1. 手机文件选择发送支持大于 32MB 的文件
2. Android 系统分享进来的视频 / 文件支持大于 32MB
3. 桌面端按分片接收并保存到 `~/Downloads/VibeDrop 收件箱`
4. 传输完成后，桌面端回传 `incoming_file_saved`
5. 传输失败时，任一侧回传 `incoming_file_error`

### 4.2 性能需求

1. 单块大小控制在中等粒度，避免消息过大
2. 手机端发送时要监控 `WebSocket.bufferedAmount` 做背压控制
3. 接收端写文件时采用 append 模式，不构造完整内存副本

### 4.3 正确性需求

1. 传输开始时记录文件名、MIME、总字节数
2. 接收完成后校验最终文件大小是否和声明一致
3. 失败时必须清理临时 `.part` 与元数据文件
4. 历史记录要继续保留成功 / 失败结果

### 4.4 交互需求

1. 去掉“请选择 32MB 以内文件”的提示
2. 发送按钮在传输期间要显示发送中状态
3. 能给出明确错误，如：
   - 连接断开
   - 读取文件失败
   - 写入收件箱失败
   - 文件大小校验失败
4. macOS 原生端从桌面拖拽、选择文件或 Finder 分享发送到手机时，必须展示真实进度，而不是只有“发送中”。进度至少包含：
   - 正在准备 / 正在发送 / 等待手机保存 / 已完成 / 失败
   - 已发送字节数、总字节数和百分比
   - 当前目标手机名称
5. 进度事件由传输运行层产生，UI 层只订阅和渲染状态。这样测试可以直接验证分片发送进度，不需要启动 SwiftUI 界面。
6. macOS 拖拽入口不能只接受 `public.file-url`。Photos、预览、Quick Look 或其它 App 可能只提供 `NSItemProvider` 文件表示或 Photos object reference；原生端应先尝试 `loadFileRepresentation` 复制到临时目录，必要时再用 Photos 选择导出兜底，传输结束后清理临时目录。
7. Android 原生端发送到 Mac 收件箱时，文件按钮必须复刻旧 Tauri 的即时进度反馈：单文件显示百分比和“保存中”，多文件显示 `当前/总数 · 百分比`，系统分享入口也同样显示进度。

## 5. 协议设计

### 5.1 开始消息

发送方先发：

- `action: incoming_file_start`
- `transfer_id`
- `file_name`
- `mime_type`
- `size_bytes`
- `is_archive`

接收方如果能建立临时接收状态，立即返回标准 `status: ok`。
如果失败，立即返回标准 `status: error`。

### 5.2 分片消息

发送方循环发送：

- `action: incoming_file_chunk`
- `transfer_id`
- `chunk_base64`

第一阶段不做逐块 ACK。

原因：

- WebSocket 自身保证同连接内消息顺序
- 当前是局域网单连接传输
- 逐块 ACK 会显著增加往返次数和实现复杂度

但需要发送方本地做**背压控制**，避免 `bufferedAmount` 持续上涨。

### 5.3 完成消息

所有分片发完后发送：

- `action: incoming_file_complete`
- `transfer_id`

接收方完成文件落盘与校验后：

- 成功：回 `incoming_file_saved`
- 失败：回 `incoming_file_error`

### 5.4 失败消息

任意一侧如果发现传输失败，可发送：

- `action: incoming_file_error`
- `transfer_id`
- `error`

用于通知对端停止等待，并清理接收中的临时状态。

## 6. 实施方案

### 6.1 手机端 Web 前端

`mobile/src/app.js` 与 `desktop/static/app.js` 一起升级：

1. 删除 `MAX_FILE_TRANSFER_BYTES`
2. 新增分片发送函数，替代整包 `file_download`
3. 选中文件时用 `Blob.slice(...).arrayBuffer()` 逐块读取
4. Android 分享缓存文件时通过新的 `NativeShare` 分块读取桥接方法逐块读取
5. 通过 `bufferedAmount` 控制发送节奏
6. 为每个 `transfer_id` 建立等待最终完成确认的 Promise

### 6.2 Android 原生桥

`MainActivity.kt` 新增：

- `readPendingSharedContentChunkBase64(offsetBytes, lengthBytes)`

要求：

- 只读取指定区间
- 不再 `readBytes()` 整个文件
- 返回该区间对应的 base64

### 6.3 桌面端服务端

`desktop/src-tauri/src/main.rs` 新增桌面端接收态：

1. `incoming_file_start` 创建临时元数据与 `.part` 文件
2. `incoming_file_chunk` 逐块解码并 append
3. `incoming_file_complete` 校验大小并移动到 `~/Downloads/VibeDrop 收件箱`
4. `incoming_file_error` 时清理接收中的临时状态

## 7. 关键细节

### 7.1 为什么不能继续整文件 base64

因为真正的大文件问题不只是网络：

- 文件 300MB
- base64 后约 400MB
- JSON 再包一层
- 手机端和 WebView 里可能同时出现多个副本

这会非常容易造成：

- 内存飙升
- GC 压力
- WebView 卡顿
- 连接超时

### 7.2 为什么分片后仍然可能失败

分片不是万能的，还要处理：

- 发送过快导致 WebSocket 缓冲堆积
- 接收目录权限不足
- 落盘磁盘空间不足
- 中途断网

所以协议层之外，还必须有：

- 背压
- 临时文件
- 错误清理
- 完成确认

### 7.3 为什么第一阶段不做断点续传

断点续传需要额外引入：

- 已接收偏移量查询
- 传输会话持久化
- 重连后的状态协商
- 分片校验或哈希

这属于第二阶段能力。

第一阶段先把“同一连接下的大文件稳定传输”做实，比一次把系统做复杂更重要。

## 8. 验证方案

1. 小文件仍能正常发送
2. 32MB 以上文件不再被前端直接拒绝
3. 100MB 以上视频能成功保存到 Mac 的 VibeDrop 收件箱
4. Android 分享进入的文件也能成功发送
5. 断开连接时能给出失败提示且不留下脏临时文件
6. 桌面端历史记录仍能记录成功 / 失败

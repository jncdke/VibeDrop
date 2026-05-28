# Android 原生测试连接规格

## 背景

旧 Tauri Android 设置页有“测试连接”按钮，用来快速确认已保存 Mac 的 host、端口、PIN 和 WebSocket 服务是否可用。原生 Android 当前已经有连接诊断、自动重连和诊断日志，但缺少一个用户主动触发的等价入口；排查“看起来不稳定”时，只能从实时状态间接判断，不够直接。

## 目标

1. 设置页提供“测试已保存 Mac”入口。
2. 测试每台已保存 Mac 的真实发送通道，而不是只 ping host。
3. 成功条件必须是 `/ws` WebSocket 连接成功并通过 v1 `auth` PIN 认证。
4. host 与已知 IP 都要作为候选端点测试，优先 host，再用 IP 兜底。
5. 测试连接不得关闭或复用当前正在发送/接收的常驻连接。
6. UI 显示每台设备的成功/失败结果，并把结果写入诊断日志。

## 实现方案

`DiscoveryRepository` 新增一次性测试方法：

- 输入：`DesktopDevice`、Android `clientId`、`clientName`。
- 候选端点：`device.host` 与 `device.ip` 去重后依次尝试。
- 每个候选端点创建独立 OkHttp WebSocket 到 `ws://host:port/ws`。
- `onOpen` 后发送现有 v1 `AuthPayload`，其中 `device_role = "diagnostic"`，`receives_clipboard = false`，`can_receive_files = false`。
- 收到 `{ "status": "ok" }` 即关闭测试 socket 并返回成功。
- 收到 `{ "status": "error" }` 返回服务端错误，常见情况是 PIN 错误。
- 连接失败、HTTP upgrade 失败、超时都返回失败原因。

UI 接入：

- 设置页 `ConnectionDiagnosticsCard` 顶部增加“测试连接”按钮。
- 测试中显示“正在测试...”，禁用重复点击。
- 结果摘要显示在连接诊断卡内，每台设备一行。
- 没有保存设备时按钮禁用，提示“没有可测试的 Mac”。

## 非目标

1. 不把测试 socket 加入正式连接池。
2. 不因为测试成功/失败修改设备排序或 PIN。
3. 不记录正文、剪贴板内容或文件路径。
4. 不替代自动重连；它只是人工排障入口。

## 验证

1. Android debug 构建通过。
2. 设置页可以编译出测试入口。
3. 无保存设备时不会崩溃。
4. 有保存设备时测试结果可以区分成功、缺少 PIN、超时或认证失败。

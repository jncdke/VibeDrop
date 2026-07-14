# VibeDrop 连接事件日志规格

## 背景

稳定版 Tauri Android App 的发送页依赖两个连接层：前台 WebView 与 Mac 端的主 WebSocket，以及 Android 原生后台剪贴板通道。用户看到的绿灯变灰通常只表示某条 WebSocket 不再处于认证可用状态，但现在 App 内没有持久事件记录；排查只能临时依赖 `adb logcat`，一旦现场过去，就很难判断是 Wi-Fi 切换、旧 IP、Mac 服务停止、认证失败还是心跳超时。

当前前端已经在关键路径里调用了 `debugLog(...)`，但实现是空函数，导致这些信息被丢弃。原生后台剪贴板诊断只暴露当前连接状态，没有最近状态变化序列。

## 目标

1. 在 Android App 设置页的“连接诊断”里展示最近连接事件，用户不用连接电脑也能看到最近为什么断开或重连。
2. 前端事件持久保存，App 重启后仍能看到最近事件。
3. 原生后台剪贴板事件暴露给前端诊断，区分前台主连接和后台剪贴板通道。
4. 日志不记录发送正文、剪贴板正文、图片内容，只记录设备、地址、事件类型、时间、错误摘要、长度等排障元数据。
5. 改动保持轻量，避免影响当前稳定版发送/同步逻辑。

## 非目标

1. 不新增云端日志、不上传日志。
2. 不改变 Mac 端传输协议。
3. 不做长期无限日志归档；只保留最近事件，避免占用存储。
4. 不把危险清空操作放到数据管理主 UI；连接日志自动滚动截断。

## 数据模型

前端持久日志存储在 WebView localStorage：

```json
{
  "ts": 1781846400000,
  "source": "frontend",
  "type": "connect:close",
  "details": {
    "deviceId": "desktop-1",
    "deviceName": "overlorddeMacBook-Air.local",
    "endpoint": "192.168.3.7:9001",
    "error": "timeout"
  }
}
```

保留上限：最近 240 条。`details` 会经过清洗：字符串截断到 240 字符，数组最多 20 项，对象最多递归 2 层，不保存正文类字段。

原生后台事件由 `BackgroundClipboardDiagnosticsStore` 在内存中维护最近 120 条，并通过 `NativeBackgroundClipboard.getStatus()` 返回：

```json
{
  "events": [
    {
      "timeMs": 1781846400000,
      "type": "background:authenticated",
      "deviceId": "mac-mini",
      "deviceName": "minideMac-mini.local",
      "endpoint": "192.168.3.10:9001",
      "status": "authenticated",
      "error": ""
    }
  ]
}
```

原生后台事件先做内存环形日志，原因是它主要用于当前会话的断连/重连解释；跨进程持久化由前端日志承担。后续如果要完整保留后台事件，可以再把原生事件写入 SharedPreferences 或 SQLite。

## 事件来源

前端主连接：

- `connect:start/open/auth_ok/auth_fail/error/close`
- `ensureReady:*`
- `sendAction:*`
- `sendText:*`
- `sendTextAndEnter:*`
- `desktopRequest:*`

原生后台剪贴板：

- 配置刷新 `background:config_reloaded`
- 剪贴板写入 `background:clipboard_applied`
- 连接状态变化 `background:connecting/open/authenticated/closed/failed/scheduled_reconnect/idle`
- 设备配置被移除 `background:connection_removed`

## UI

设置页“连接诊断”展开后新增“最近连接事件”区块：

- 默认展示最近 30 条，按时间倒序。
- 每条显示相对/绝对时间、来源、事件类型、设备、地址、错误摘要。
- 总结区显示最近事件数量。
- 不提供主界面清空按钮，避免把排障信息误删；日志超过上限自动淘汰旧项。

## 验证

1. `node --check mobile/src/app.js` 通过。
2. Android Kotlin 编译通过。
3. 真机安装后打开设置页，连接诊断能看到前端事件和后台事件。
4. 手动断开/恢复 Mac 服务或切换目标地址后，诊断面板能显示 `failed/close/scheduled_reconnect/authenticated` 等事件。

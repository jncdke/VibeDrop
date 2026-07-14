# VibeDrop 连接自愈规格

## 背景

Android 稳定版运行时会遇到三类移动端特有情况：Wi-Fi 2.4G/5G/MLO 链路切换、WebView 被后台冻结后恢复、WebSocket 心跳超时。现有逻辑在启动时会强制发现并连接，但普通回到前台、网络恢复、连续重连失败时没有同等强度的全局自愈路径。用户体感是绿灯变灰后，手动划掉 App 再打开才能恢复；实际上重启有效，是因为启动流程重新执行了 discovery + connect。

## 目标

1. App 回到前台、页面重新显示、浏览器 online/focus 事件发生时，自动检查连接健康度。
2. 如果存在保存设备未认证、socket 不可用、心跳过久无消息，则调度一次全局重连。
3. 全局重连会同步原生后台剪贴板配置，并按需要触发智能发现，避免继续连接旧 IP。
4. 心跳超时、发送前等待连接超时、WebSocket close/error 进入统一自愈调度。
5. 加节流和串行保护，避免短时间内多次 focus/online/close 造成并发扫描。
6. 所有自愈动作写入连接事件日志，便于下次排查。

## 非目标

1. 不改变 Mac 端协议。
2. 不改变历史记录、媒体缓存、Mac mini Vault 同步逻辑。
3. 不在每次事件都强制全子网扫描；只有启动、原生能力就绪、回前台、连续失败等场景才允许强制发现。

## 策略

新增 `connectionRecoveryState`：

- `inFlight`：当前是否已有全局恢复任务。
- `timer`：延迟调度句柄。
- `lastAttemptAt`：最近一次全局恢复开始时间。
- `lastForceDiscoveryAt`：最近一次强制发现时间。
- `lastVisibleAt`：最近一次页面可见时间。

新增常量：

- `CONNECTION_RECOVERY_DEBOUNCE_MS = 400`
- `CONNECTION_RECOVERY_COOLDOWN_MS = 2500`
- `CONNECTION_RECOVERY_FORCE_DISCOVERY_COOLDOWN_MS = 12000`
- `CONNECTION_STALE_AFTER_MS = HEARTBEAT_TIMEOUT + HEARTBEAT_INTERVAL`

触发点：

1. `visibilitychange`：页面变为可见时调度恢复。
2. `pageshow`：WebView 页面从缓存或后台恢复时调度恢复。
3. `focus`：窗口获得焦点时调度恢复。
4. `online`：系统网络恢复时调度恢复。
5. `connect:error/close`：调度单设备重连，同时通知全局恢复节流器。
6. 心跳超时/心跳发送失败：写日志并调度恢复。
7. 发送前 `ensureReady` 超时：触发一次带强制发现的恢复。

健康判断：

保存设备中只要存在以下情况之一，就认为需要恢复：

- 没有对应连接对象。
- WebSocket 不存在或不是 `OPEN`。
- 已认证状态为 false。
- 最近消息超过 `CONNECTION_STALE_AFTER_MS`。

执行：

`runConnectionRecovery(reason, options)` 调用：

```js
connectAll({
  refreshDiscovery: true,
  alignNativeBackgroundConfig: true,
  forceDiscovery,
  discoveryReason: `recovery:${reason}`,
})
```

`forceDiscovery` 由触发原因和冷却时间决定。回前台、网络恢复、发送前失败、连续重连失败可以强制发现；普通 close/error 在冷却时间内只做轻量 refresh。

## 验证

1. `node --check mobile/src/app.js` 通过。
2. Android APK 构建安装成功。
3. 设置页连接诊断能看到 `recovery:*`、`heartbeat:*`、`reconnect:scheduled` 等事件。
4. 手机 Wi-Fi 切换或 App 从后台回前台后，不需要划掉 App 即可尝试恢复绿灯。

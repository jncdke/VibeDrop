# VibeDrop 当前项目交接上下文（2026-06-20）

## 读这份文档的目的

这份文档是给后续新对话里的 AI 快速接手用的。当前用户主要想继续研究和优化 VibeDrop 的断联问题，同时保留 Android 端历史、Mac mini Home Vault 同步、Tauri 稳定版体验。请先读完这份文档，再动代码；本项目用户明确要求做工程前先调查、规划、写规格，再实施。

## 项目是什么

VibeDrop 是用户自用的局域网传输工具，核心场景是 Android 手机把文字、回车、图片、文件发送到 macOS 电脑，macOS 端也能把剪贴板/文件同步回 Android。日常使用方式是：手机端看到多个 Mac 设备卡片，绿灯表示 WebSocket 已认证可发送；点击“发送”“回车”“发送并回车”“传图到剪贴板”“传到收件箱”等按钮执行操作。还有历史页、活跃热力图、媒体预览、设置页、连接诊断、Mac mini Home Vault 同步和网页查看器。

当前仓库路径：`/Users/overlord/Documents/安卓发送mac输入文字app`。当前分支：`codex/android-sqlite-media-restore`。当前 HEAD：`0945b21 Stabilize Tauri Vault restore`，带 tag `v0.1.4-tauri-vault-restore-stable`。当前工作区是 dirty 状态，很多改动尚未提交。

## 当前稳定路线

重要：用户已经放弃 `0.2.0-native` 这条原生重构试验线。原生 Android/macOS 版本曾经尝试过，但用户实测 Mac 端历史很卡、Android 历史点按复制缺失、手机到 Mac 传送不可用，因此明确要求回退到 Tauri 稳定版。后续不要默认继续推进 native 版本，除非用户重新明确要求。

当前用户认可的稳定 Android 版本是 Tauri 版：`versionName=0.1.4`，`versionCode=1101`，`applicationId=com.vibedrop.mobile`。安装脚本仍使用：

```bash
./scripts/deploy-android.sh --skip-icons --device 3B6F4FE910B8KRLS
```

最近连接的 Android 设备：`3B6F4FE910B8KRLS`，`model=PKG110`，OnePlus 机型。此前也提到 PLF110 / 3B15B8017W600000，但当前主要测试设备是 `3B6F4FE910B8KRLS`。

## 近期用户让我做过什么

用户先要求在手机端优化发送流程：剪贴板已有内容时，点发送/发送并回车可以自动读取剪贴板并发送，减少先点输入框再粘贴的步骤。随后用户关注历史页活跃热力图颜色对比，希望热力图按当前选择窗口做相对归一化，不按全历史对比，并增强最浅到最深的视觉对比。

后来用户发现发送页有严重体验问题：连接绿灯变灰或重新连接时，正在输入的文本会被清空。代码定位后确认需要保留草稿，不应因为连接状态变化或卡片重渲染丢掉输入内容。相关规格已有 `docs/send-draft-preservation-spec.md`。

随后用户要求研究连接不稳定，特别是手机端绿灯经常变灰，手动从后台划掉 App 再打开才能恢复。我们分析了 Android logcat、`ApplicationExitInfo`、Wi-Fi 状态、端口连通性，并加了连接事件日志、自愈逻辑和 debuggable 构建。

用户还让实现 Mac mini Home Vault：Mac mini 通过 `mini@minideMac-mini.local` 访问，外置盘 `/Volumes/SN850X` 下建立 `/Volumes/SN850X/VibeDropVault`。目标是把 Android/Mac 历史和媒体归档到家庭服务器，网页查看器可在局域网打开。Mac mini receiver 监听 `:8788`，viewer 监听 `:8787`。曾出现 `http://192.168.3.2:8788` 失败，后来发现 `192.168.3.2` 可能是旧 IP，当时 `minideMac-mini.local` 解析到 `192.168.3.10`，`http://minideMac-mini.local:8788/health` 可用。今后排查 Mac mini 连接时不要固定假设 `192.168.3.2` 永远正确。

## 当前未提交改动概览

当前 `git status --short --branch` 显示：

```text
## codex/android-sqlite-media-restore
 M mobile/src-tauri/gen/android/app/build.gradle.kts
 M mobile/src-tauri/gen/android/app/src/main/java/com/vibedrop/mobile/BackgroundClipboardSyncManager.kt
 M mobile/src-tauri/gen/android/app/src/main/java/com/vibedrop/mobile/MainActivity.kt
 M mobile/src/app.js
 M mobile/src/index.html
 M mobile/src/style.css
 M scripts/home-vault-receiver.py
?? docs/android-debuggable-always-on-spec.md
?? docs/android-sqlite-media-restore-spec.md
?? docs/connection-event-log-spec.md
?? docs/connection-self-healing-spec.md
```

这些改动包含多个主题，不只是断联修复：Android SQLite/文件存储、Mac mini full-media restore、Home Vault receiver、连接事件日志、连接自愈、debuggable 常开、设置页按钮精简等。不要随意 revert 用户未要求回退的文件。

## Android 历史存储与 Mac mini 恢复

当前 Android 历史存储已从纯 `localStorage/history.json` 升级为原生 SQLite/文件存储，通过 `MainActivity.kt` 注入 `window.NativeHistory`。前端启动时优先调用 `NativeHistory.loadHistory()`，写历史时用 `NativeHistory.upsertHistoryEntry()`。旧 `localStorage`/`history.json` 仍保留 fallback，避免丢旧数据。相关规格：`docs/android-sqlite-media-restore-spec.md`。

Mac mini Vault 恢复已支持“全量媒体缓存恢复”：从 Home Vault 拉历史索引、缩略图和 Vault 已拥有的媒体对象文件，写入 Android 私有目录。设置页已有恢复进度条，显示历史总数、新增、覆盖、媒体对象下载/跳过等。用户要求删除“导入历史 / 导出历史 / 分享历史 / 清空历史”四个按钮，保留“同步到 Mac mini”和“从 Mac mini 恢复”这条主路径，尤其避免把清空历史这种危险操作作为普通按钮暴露。

Mac mini Home Vault 查看器也做过优化：设备筛选显示数量，发送端/接收端按数量排序；用户指出 `未知发送端` 实际应合并到“一加 Ace 5”，接收端里 `MacBook` 与 `overlorddeMacBook-Air-4.local` 等别名应合并。相关规格有 `docs/home-vault-device-filter-key-spec.md`、`docs/home-vault-explicit-device-role-fields-spec.md` 等。

## Android release 已经永久 debuggable

用户明确要求“不用正式/内部版区分，直接全部 debuggable=true”。已在 `mobile/src-tauri/gen/android/app/build.gradle.kts` 中把 release buildType 设为：

```kotlin
isDebuggable = true
isJniDebuggable = true
```

规格：`docs/android-debuggable-always-on-spec.md`。最近已安装到手机并验证：`dumpsys package com.vibedrop.mobile` 里出现 `pkgFlags=[ DEBUGGABLE ... ]`，`adb shell run-as com.vibedrop.mobile ls -la` 成功，可看到 `app_webview`、`databases`、`files`、`shared_prefs`、`history.json` 等。这个改动的目的不是提升稳定性，而是提升可观测性：后续断联时可直接读取 App 私有目录、SQLite、WebView 存储和后台配置。

注意：Gradle 会警告 release 同时 debuggable 与 minifyEnabled=true 时，代码优化/混淆会被禁用。这是预期内的取舍，用户接受自用调试优先。

## 连接事件日志

以前 `mobile/src/app.js` 里有很多 `debugLog(...)` 调用，但 `debugLog` 是空函数，连接 close/error/timeout/send fail 都被丢弃。现在已实现前端连接事件日志，localStorage key 是 `vibedrop_connection_events`，保留最近 240 条，设置页“连接诊断”展示最近 30 条。日志会过滤正文、剪贴板内容、base64、chunk 等敏感/大字段，只记录设备、地址、事件类型、错误摘要、文本长度等元数据。规格：`docs/connection-event-log-spec.md`。

原生后台剪贴板连接也加了事件 ring buffer：`BackgroundClipboardDiagnosticsStore` 内存保留最近 120 条后台连接状态事件，通过 `NativeBackgroundClipboard.getStatus()` 返回。事件包含 `background:connecting/open/authenticated/failed/closed/scheduled_reconnect/idle`，用于区分前台 WebSocket 断了还是后台剪贴板通道断了。

## 连接自愈逻辑

针对用户“绿灯变灰后必须划掉 App 重开”的问题，已补连接自愈。规格：`docs/connection-self-healing-spec.md`。核心常量在 `mobile/src/app.js`：

```js
CONNECTION_RECOVERY_DEBOUNCE_MS = 400
CONNECTION_RECOVERY_COOLDOWN_MS = 2500
CONNECTION_RECOVERY_FORCE_DISCOVERY_COOLDOWN_MS = 12000
CONNECTION_STALE_AFTER_MS = HEARTBEAT_TIMEOUT + HEARTBEAT_INTERVAL
RECONNECT_FORCE_DISCOVERY_AFTER_ATTEMPTS = 2
```

新增 `initConnectionRecoveryLifecycle()`，在 `visibilitychange`、`pageshow`、`window focus`、`online/offline` 等生命周期事件发生时检查连接健康度；只有保存设备未认证、socket 不存在、socket 非 OPEN、心跳过久无消息时才调度恢复。恢复函数 `runConnectionRecovery()` 会调用：

```js
connectAll({
  refreshDiscovery: true,
  alignNativeBackgroundConfig: true,
  forceDiscovery,
  discoveryReason: `recovery:${reason}`,
})
```

心跳超时、心跳发送失败、WebSocket create error/error/close、发送前 `ensureReady` 超时都会进入自愈调度。发送前第一次 3.5 秒没连上时，会再跑一次带智能发现的恢复并短暂等待认证，尽量让同一次点击继续成功，而不是要求用户划掉重开。

## 当前断联问题的证据链

目前没有证据说明 `com.vibedrop.mobile` 自己崩溃。Android crash buffer 里最近的 fatal 是系统/ROM 进程 `tics.rom:worker`，不是 VibeDrop。`dumpsys activity exit-info com.vibedrop.mobile` 中 2026-06-20 13:07:44 和 13:08:25 的退出记录是 `reason=13 (OTHER KILLS BY SYSTEM)`；events buffer 进一步显示 `with swipe up`，即用户从后台任务里划掉 App 后，系统杀了主进程和 WebView 子进程。13:32、13:42 之后的退出是 `PACKAGE UPDATED`，是我们安装 APK 造成的。

手机侧网络当时和现在的关键事实：

1. 手机在 Wi-Fi `HUAWEI-81GJ7L`，IP 是 `192.168.3.5`。
2. Wi-Fi 标准显示 `11be`，即 Wi-Fi 7。
3. Wi-Fi/MLO 信息显示同时存在 2.4GHz 链路 `2412MHz` 和 5GHz 链路 `5200MHz`。
4. RSSI 很强，约 `-24dBm`，所以不是简单“信号弱”。
5. 日志有 `ConnectivityService ... lost immutable capabilities: specifier changed ... band=1 -> band=2`，说明同一个 Wi-Fi 网络对象的频段/链路属性发生变化。
6. 手机可以 ping 通 `192.168.3.7` 和 `192.168.3.10`；此前从手机侧测试 `192.168.3.7:9001` 和 `192.168.3.10:9001` 均返回 `exit=0`，MacBook 本机也有 `voicedrop` 监听 `*:9001`。

目前最可信的根因链路是：路由器/手机 Wi-Fi 7、MLO、智能双频或固件策略导致手机和路由器之间的底层 Wi-Fi 链路/主频段发生切换；这个切换不一定让系统状态栏显示断网，也不一定让 ping 失败，但可能让原来的 TCP/WebSocket 长连接断掉或心跳超时。旧版 VibeDrop 自愈不够强，所以绿灯变灰后不会可靠恢复；用户划掉重开后重新执行 startup discovery + connect，因此恢复。

重要：不能简单说“手机从 5G Wi-Fi 切到 2.4G Wi-Fi 所以断网”。更准确是：同一个 SSID 下，Android 观察到 Wi-Fi network specifier 的 band 属性变化，结合 Wi-Fi 7/MLO 双链路，推测是 2.4G/5G/MLO 主链路或网络能力切换引发长连接抖动。

## 用户怀疑路由器的原因

用户新买了路由器，最近固件更新后不只 VibeDrop，别的手机体验也变卡。这个现象与日志证据方向一致：路由器固件可能改变了 Smart Connect、MLO、802.11k/v/r、OFDMA/TWT、信道宽度、功率、AP 隔离/多播优化等策略。建议用户做低成本网络实验：

1. 关闭 Wi-Fi 7 MLO / 多链路聚合。
2. 关闭双频合一 / Smart Connect，把 2.4G 和 5G 分成不同 SSID。
3. 手机固定连接单独的 5GHz SSID，例如 `VibeDrop-5G`。
4. MacBook/Mac mini 尽量走有线或同一个主 LAN。
5. 关闭 802.11r 快速漫游试试。
6. 确认 AP 隔离、访客网络、终端隔离关闭。

如果这样 VibeDrop 稳定，基本可确认问题主要来自路由器固件/智能切频/MLO 策略。

## 后续断联时应如何取证

如果用户再次遇到绿灯变灰或发送无效，优先提醒用户：先不要划掉 App，保持现场。然后按这个顺序查：

```bash
adb shell pidof com.vibedrop.mobile
adb shell dumpsys activity exit-info com.vibedrop.mobile
adb logcat -b events -d -v time -t 20000 | grep -E "am_crash|am_anr|am_kill|am_proc_died|com.vibedrop.mobile|webview"
adb logcat -d -v time -t 15000 | grep -Ei "VibeDrop|vibedrop|WebSocket|EHOSTUNREACH|ECONN|timeout|ConnectivityService|Wifi|NetworkAgent"
adb shell dumpsys wifi
adb shell 'for host in 192.168.3.7 192.168.3.10; do echo === $host:9001 ===; toybox nc -z -w 2 $host 9001; echo exit=$?; done'
adb shell run-as com.vibedrop.mobile ls -la
adb shell run-as com.vibedrop.mobile find shared_prefs -maxdepth 2 -type f -print
adb shell run-as com.vibedrop.mobile find databases -maxdepth 2 -type f -print
adb shell run-as com.vibedrop.mobile find app_webview -maxdepth 5 -type f -print
```

`run-as` 现在应该可用。如果 ADB 在 Codex 沙箱里出现 `could not install *smartsocket* listener: Operation not permitted`，这是本机工具环境启动 ADB daemon 被限制，不是 App 权限问题；可改成单条命令执行，或在有批准前缀/提升权限的环境中执行。之前已验证 `run-as com.vibedrop.mobile ls -la` 成功。

下一步值得做的是把连接事件日志从 WebView localStorage 再镜像一份到原生文件或 SQLite。这样不需要解析 `app_webview` 的 LevelDB，就能直接用 `run-as` 读取例如 `files/connection-events.jsonl`。这会显著提升断联现场取证效率。

## 已验证的命令和状态

最近验证过：

```text
node --check mobile/src/app.js 通过
git diff --check 通过
./scripts/deploy-android.sh --skip-icons --device 3B6F4FE910B8KRLS 构建安装成功
dumpsys package 显示 versionName=0.1.4, versionCode=1101
dumpsys package 显示 pkgFlags 包含 DEBUGGABLE
adb shell run-as com.vibedrop.mobile ls -la 成功
```

安装后进程也能看到，例如 `adb shell pidof com.vibedrop.mobile` 返回过 `29993`。最近启动后未看到 `FATAL EXCEPTION`。

## 重要文件索引

- `mobile/src/app.js`：Android WebView 前端主逻辑，当前连接事件日志、自愈逻辑、NativeHistory 调用、Home Vault 按钮、历史页等都在这里。
- `mobile/src-tauri/gen/android/app/src/main/java/com/vibedrop/mobile/MainActivity.kt`：Android 原生桥，注入 `NativeHistory`、Home Vault 同步/恢复、媒体缓存、权限/网络绑定、后台剪贴板状态等。
- `mobile/src-tauri/gen/android/app/src/main/java/com/vibedrop/mobile/BackgroundClipboardSyncManager.kt`：原生后台剪贴板 WebSocket 连接和诊断事件。
- `mobile/src-tauri/gen/android/app/build.gradle.kts`：Android 构建配置，release 已设置 `isDebuggable=true`。
- `scripts/home-vault-receiver.py`：Mac mini Home Vault 接收服务，处理 Android 直同步和 viewer 数据刷新。
- `docs/android-sqlite-media-restore-spec.md`：SQLite/媒体恢复规格。
- `docs/connection-event-log-spec.md`：连接事件日志规格。
- `docs/connection-self-healing-spec.md`：连接自愈规格。
- `docs/android-debuggable-always-on-spec.md`：debuggable 常开规格。
- `docs/native-rewrite-spec.md`：此前原生重构规划，仅作参考；当前不要默认执行 native 重构。

## 下一步建议

优先级最高的是继续观察断联：让用户用新安装的 debuggable + 自愈版正常使用。如果再发生断联，不要先划掉 App，马上抓连接诊断和 `run-as` 数据。需要重点确认自愈是否触发了 `recovery:scheduled/start/done`，心跳是否出现 `heartbeat:timeout`，WebSocket 是否 `connect:close/error`，以及同一时间系统 Wi-Fi 是否出现 band/specifier/MLO 变化。

第二优先级是把前端连接事件日志镜像到原生可读文件或 SQLite，减少后续解析 WebView localStorage 的成本。建议新增 `NativeDiagnostics.appendConnectionEvent(json)` 或定期导出 `vibedrop_connection_events` 到 `files/connection-events.jsonl`。

第三优先级是网络实验：让用户在路由器里关闭 MLO/双频合一/802.11r，固定手机连接 5GHz SSID，测试 VibeDrop 是否明显稳定。如果稳定，基本确认路由器固件/智能切频是主要外部根因。

第四优先级是提交当前稳定改动。当前工作区包含多组相关但未提交的改动，提交前应先按主题拆分或至少写清楚提交说明：SQLite/媒体恢复、Home Vault receiver、连接事件日志、自愈、debuggable 常开。

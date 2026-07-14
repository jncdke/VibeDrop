# Targeted Connection Recovery And Loopback Endpoint Guard

## Background

On 2026-06-30 the Android app repeatedly switched between "connecting" and "connected" while the phone stayed on the same Wi-Fi SSID. Mac-side `~/.vibedrop/debug.log` showed real WebSocket close/open/auth cycles roughly every 2.5 to 3 seconds. Android logcat then showed the foreground WebView and native background clipboard service repeatedly reconnecting the healthy MacBook connection while the saved Mac mini endpoint was `127.0.0.1:9001`.

## Evidence

The phone was reachable at `192.168.3.6`, the MacBook server was reachable at `192.168.3.7:9001`, and both Mac mini advertised endpoints were reachable from the phone. The app private config contained:

```json
{
  "devices": [
    { "name": "overlorddeMacBook-Air.local", "ip": "192.168.3.7", "port": "9001" },
    { "name": "minideMac-mini.local", "ip": "127.0.0.1", "port": "9001" }
  ]
}
```

Frontend connection events showed a loop of `connect:error` for Mac mini, followed by `recovery:start`, followed by `connect:start` for both MacBook and Mac mini. Native logcat showed `后台剪贴板同步未配置设备，已清空连接` and then a fresh connection to the MacBook every few seconds.

## Root Cause

Two behaviors compounded each other:

1. `connectAll({ refreshDiscovery: true, alignNativeBackgroundConfig: true })` clears the native background clipboard config before discovery, then writes the full config again. During frequent recovery this intentionally drops healthy background clipboard sockets.
2. `runConnectionRecovery()` calls `connectAll()` for every saved device even when only one device is unhealthy. A bad saved endpoint therefore forces healthy devices to close and reopen.
3. The saved Mac mini endpoint was `127.0.0.1`. On Android this points to the phone itself, not the Mac mini, so it always fails and keeps the recovery loop alive.

## Implementation Plan

Add a mobile-native endpoint guard that treats loopback desktop endpoints as unusable only in native mobile mode. Browser-based local development can still use `localhost` if it is not running through the native bridge.

Add targeted recovery: compute unhealthy device IDs from the health snapshot and reconnect only those devices. Healthy authenticated sockets must not be closed during recovery. Native background clipboard config should be synced with usable remote endpoints, but recovery should not clear the whole config before every discovery pass.

Add connection no-op logic: if `connectDevice()` is asked to connect an already-authenticated socket to the same endpoint, keep it open.

## Acceptance Criteria

1. A saved `127.0.0.1` desktop endpoint on Android does not open a WebSocket and does not trigger endless global recovery.
2. A failed Mac mini connection no longer closes or recreates the healthy MacBook WebSocket.
3. Native background clipboard config excludes loopback desktop endpoints on Android and does not clear healthy connections during routine recovery.
4. Frontend connection events show recovery targeting the unhealthy device only.

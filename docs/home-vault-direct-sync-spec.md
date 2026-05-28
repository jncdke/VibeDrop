# Home Vault 手机直同步规格

## 背景

已有 Home Vault 同步脚本可以把 MacBook 桌面端历史、Android 公开导出 JSON、媒体对象和网页查看器同步到 Mac mini 外置盘。但 Android 当前安装包是非 debuggable，ADB 不能直接读取 app 私有目录里的 `history.json`。为了不依赖 ADB 和手动导出，需要在 App 内加入“直接同步到 Mac mini Vault”的能力。

## 目标

1. Android App 在“数据管理”里提供家庭服务器地址和“同步到 Mac mini”按钮。
2. App 直接读取当前历史内存/持久数据，复用现有导出格式，POST 到 Mac mini 的 Home Vault 接收服务。
3. Mac mini 接收服务把上传 JSON 保存到 `VibeDropVault/inbox/android/...`，然后刷新 SQLite 索引和静态网页查看器。
4. 接收服务使用稳定 ID 去重，同一条手机历史重复上传不会重复显示。
5. 同步不要求 ADB、不要求手机选择文件、不要求用户手动导出到下载目录。

## 非目标

1. 本阶段不上传 Android 原始媒体文件内容；历史里的缩略图 data URL 会保留，Android 本机 `content://` 或私有路径会在 Vault 中标记为不可直接读取。
2. 本阶段不做公网访问、账号登录或外网穿透；默认只面向家庭局域网。
3. 本阶段不改桌面端 WebSocket 传输协议。

## 架构

手机端流程：

```text
设置页点击同步
  -> getHistory()
  -> buildHistoryExportData(history)
  -> NativeHomeVault.syncHistory(endpoint, payload)
  -> Kotlin HttpURLConnection POST /api/android-history
  -> JS 收到 native-home-vault-sync-result
```

Mac mini 端流程：

```text
home-vault-receiver.py :8788
  -> 校验 JSON
  -> 写入 VibeDropVault/inbox/android/<device>/<timestamp>-<hash>.json
  -> 调用 sync-home-vault.py --local-vault-root ... --include-vault-inbox
  -> 更新 db/vibedrop.sqlite
  -> 更新 viewer/data/history.json 和 report.json
```

## 接口

`POST /api/android-history`

请求体：

```json
{
  "schemaVersion": 1,
  "app": "VibeDrop",
  "deviceId": "client_xxx",
  "deviceName": "PKG110",
  "exportedAt": "2026-05-28T16:00:00.000Z",
  "history": []
}
```

响应体：

```json
{
  "ok": true,
  "historyCount": 2337,
  "savedPath": "inbox/android/client_xxx/20260528-160000-abc123.json",
  "syncReport": {
    "totalEntryCountInDb": 4656
  }
}
```

## 安全边界

默认部署在家庭局域网，监听 `0.0.0.0:8788`。接收器支持后续增加 token，但当前先不强制 token，避免手机端配置复杂。服务只写入指定 Vault 根目录，并限制请求体大小，避免误传超大数据。

## 验收标准

1. `scripts/sync-home-vault.py` 支持读取 `VibeDropVault/inbox/android/**/*.json` 并刷新查看器。
2. `scripts/home-vault-receiver.py` 的 `/health` 和 `/api/android-history` 可用。
3. Android 设置页能保存家庭服务器地址并触发同步。
4. 同步成功后网页查看器总记录数与 SQLite `history_entries` 数一致。
5. `python3 -m py_compile`、`node --check mobile/src/app.js`、Android 编译检查通过。

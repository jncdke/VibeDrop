# Android SQLite History Store and Full Vault Media Restore

## 背景

当前稳定 Tauri Android 版把历史主数据放在 WebView `localStorage` 的 `voicedrop_history` key 里，并通过 Tauri Rust `save_history/load_history` 把同一份 JSON 数组镜像到 App 私有文件 `history.json`。这个方案对几千条纯文本可用，但不适合恢复完整媒体历史：缩略图 base64 和媒体元数据会快速放大 JSON，容易触发 WebView 配额、启动解析卡顿和历史页渲染压力。

Mac mini Home Vault 当前保存两类数据：

- `inbox/android/<deviceId>/*.json`：Android 端上传的原始历史快照，部分媒体只有 `thumbnailDataUrl`，多数旧发送图片没有原图文件。
- `db/vibedrop.sqlite` 与 `viewer/data/history.json`：Vault 归一化索引，`history_media.object_path`/`viewer.entries[].media[].objectPath` 指向 Vault 已拥有的对象文件，`thumbnailDataUrl` 指向可恢复的缩略图。

实际数据现状：Vault 有 5113 条归一化历史、382 个媒体引用、164 个真实媒体对象。全量恢复不能凭空恢复 Vault 没保存的原文件；它只能恢复真实对象文件，另对只有缩略图的记录恢复缩略图与历史索引。

## 目标

1. Android 历史主存储从 `localStorage` 升级为 App 私有 SQLite。
2. Android 媒体缓存从 `localStorage`/临时 data URL 升级为 App 私有文件目录。
3. 首次启动自动迁移旧 `localStorage` 和 `history.json` 历史到 SQLite，迁移后仍保留旧数据作为保险。
4. 从 Mac mini Vault 全量恢复历史索引、缩略图和 Vault 已拥有的媒体对象文件。
5. 保持现有 UI、筛选、热力图、点击复制、图片/视频预览、系统打开媒体等体验。
6. 保持现有 compact 恢复作为兜底路径，新增 full media restore 不破坏稳定版同步。

## 非目标

1. 不把不存在于 Vault 的原图伪装为已恢复原图。
2. 不公开 Android App 私有媒体目录到系统相册；缓存只服务 VibeDrop 历史查看。
3. 不重启原生重写项目。
4. 不删除旧 `localStorage`、`history.json` 或 Mac mini Vault 原始数据。

## Android 存储设计

### SQLite 数据库

位置：App 私有数据库 `vibedrop_history.db`。

表：

- `history_entries`
  - `entry_key TEXT PRIMARY KEY`
  - `timestamp TEXT NOT NULL`
  - `text TEXT`
  - `kind TEXT`
  - `status TEXT`
  - `target TEXT`
  - `target_name TEXT`
  - `target_alias TEXT`
  - `target_device_name TEXT`
  - `target_server_id TEXT`
  - `raw_json TEXT NOT NULL`
  - `updated_at INTEGER NOT NULL`
- `media_cache`
  - `cache_key TEXT PRIMARY KEY`
  - `entry_key TEXT NOT NULL`
  - `item_index INTEGER NOT NULL`
  - `kind TEXT`
  - `file_name TEXT`
  - `mime_type TEXT`
  - `object_hash TEXT`
  - `object_path TEXT`
  - `local_path TEXT`
  - `thumbnail_path TEXT`
  - `thumbnail_data_url TEXT`
  - `size_bytes INTEGER`
  - `status TEXT`
  - `missing_reason TEXT`
  - `updated_at INTEGER NOT NULL`

`entry_key` 生成规则：

1. 优先使用 Vault entry `id`，前缀 `vault:<id>`。
2. 其次使用原历史 `id`，前缀 `local:<id>`。
3. 兜底使用 `timestamp|text|target` 的 SHA-256，前缀 `derived:<hash>`。

### 文件缓存

位置：App 私有文件目录：

- `files/vibedrop_history_media/objects/<hash-prefix>/<hash>.<ext>`：Vault 对象文件。
- `files/vibedrop_history_media/thumbs/<entry-key>/<item-index>.<ext>`：缩略图缓存文件。

历史 JSON 中供 UI 使用的字段：

- `items[].savedPath` / `items[].filePath` 指向本地缓存原文件路径。
- `items[].thumbnailDataUrl` 可以继续保留 data URL；若需要降低 DB 体积，后续可换成 `thumbnailPath` 并在 JS 中通过 `NativeMediaLibrary.getPreviewUri` 读取。
- `items[].objectHash` / `objectPath` 保留 Vault 来源信息，便于增量恢复。

## Android JS Bridge

新增 `window.NativeHistory`：

- `loadHistory(): string`
  - 返回 SQLite 中按时间倒序排列的 JSON 数组。
- `replaceHistory(historyJson: string): string`
  - 事务性全量替换或批量 upsert 历史。
- `upsertHistoryEntry(entryJson: string): string`
  - 单条新增/更新，供发送/接收实时写入。
- `deleteHistory(): string`
  - 清空 SQLite 历史和媒体索引，默认不立即删除缓存对象文件。
- `stats(): string`
  - 返回条数、媒体数、缓存文件数、占用空间。

新增或扩展 `window.NativeHomeVault`：

- `restoreHistory(endpoint, deviceId, mode)`
  - 继续支持 `compact`。
  - 新增 `full`/`full-media` 模式，从 Vault 拉取 manifest，下载媒体对象和缩略图，最终写入 `NativeHistory`。

## Mac mini Vault API

新增接口：

- `GET /api/android-history/latest?deviceId=...&mode=full-media`

返回结构：

```json
{
  "ok": true,
  "mode": "full-media",
  "historyCount": 2794,
  "returnedCount": 2794,
  "history": [
    {
      "id": "vault-entry-id",
      "timestamp": "2026-06-18T13:22:15.257+08:00",
      "text": "[图片] 1000114627.jpg",
      "kind": "image",
      "thumbnailDataUrl": "data:image/jpeg;base64,...",
      "media": [
        {
          "itemIndex": 0,
          "kind": "image",
          "fileName": "1000114627.jpg",
          "mimeType": "image/jpeg",
          "objectHash": "...",
          "objectPath": "objects/ab/hash.jpg",
          "downloadUrl": "/objects/ab/hash.jpg",
          "thumbnailDataUrl": "data:image/jpeg;base64,...",
          "missingReason": null
        }
      ]
    }
  ]
}
```

`downloadUrl` 使用同一 receiver 服务提供，避免手机直接依赖 viewer 静态服务器是否启动。

新增对象接口：

- `GET /objects/<bucket>/<filename>`
  - 只允许读取 Vault root 下的 `objects` 文件。
  - 设置正确 `Content-Type` 和 `Content-Length`。

## 前端迁移策略

1. 启动时优先调用 `NativeHistory.loadHistory()`。
2. 如果 SQLite 为空，则读取旧 `localStorage` 和 Tauri `load_history()`，合并后写入 SQLite。
3. 前端内存继续使用 `storedHistoryEntriesCache` 和 `hydratedHistoryCache`，保证 UI 同步接口不大改。
4. `setStoredHistoryRaw()` 改为“更新内存缓存 + 写 SQLite + 兼容写入 localStorage 精简副本”。
5. `persistHistory()` 在支持 `NativeHistory` 时不再写 `history.json`，旧 Tauri 文件写入只作为 fallback。

## 全量恢复流程

1. 用户点击“从 Mac mini 恢复”。
2. 前端优先调用 `NativeHomeVault.restoreHistory(endpoint, deviceId, "full-media")`。
3. Android 原生层请求 `mode=full-media` manifest。
4. 原生层逐条处理历史：
   - 下载每个有 `downloadUrl/objectPath` 的媒体对象到私有文件缓存。
   - 写入或更新 `items[].savedPath/filePath` 为本地路径。
   - 对只有缩略图的媒体保留 `thumbnailDataUrl`，标记 `missingReason`。
   - 批量 upsert 到 SQLite。
5. 前端收到恢复结果后重新 `loadHistory()`，刷新历史页和热力图。

## 恢复进度与重复策略

恢复过程必须显示确定进度，而不是只显示“恢复中”。Android 原生层在实际恢复线程里发送 `native-home-vault-restore-progress` 事件，前端设置页展示进度条、百分比和明细文本。

进度阶段：

1. `requesting`：连接 Home Vault 并请求 manifest。
2. `manifest`：已收到 manifest，开始解析历史条目。
3. `processing`：逐条处理历史与媒体引用。
4. `writing`：媒体处理完成，批量写入 SQLite。
5. `done`：SQLite 写入完成，前端重新加载历史。

重复策略保持幂等：Vault 条目使用 `vaultEntryId/id` 派生成 `entry_key`，本地新条目使用 `local:<id>` 或 `derived:<hash>`，SQLite 写入使用 `CONFLICT_REPLACE`。因此用户以后再次点击恢复时，同一条 Vault 历史会覆盖更新同一行，不会重复插入。媒体对象也按 `objectHash` 落盘；如果文件已存在且大小大于 0，则计入 `skippedMediaCount`，不重复下载。

进度明细至少展示：

- 已处理历史条数 / 总条数。
- 新增历史条数。
- 已存在并覆盖的历史条数。
- 已下载媒体数 / 媒体引用数。
- 已缓存跳过媒体数。
- 失败媒体数。
- 已下载字节数。

## 错误处理

- Vault 无对象：历史仍恢复，媒体项显示文件名/缩略图，并保留 `missingReason`。
- 下载失败：记录失败数量，已下载成功的继续写入；用户可再次点击恢复重试。
- VPN/网络绑定 EPERM：沿用已有策略，Wi-Fi 绑定失败后降级默认网络。
- SQLite 写入失败：停止恢复并显示错误，不清空旧历史。
- 空间不足：显示“手机存储空间不足”，已写入部分不删除，允许下次重试。

## 验证清单

1. 升级安装后旧历史仍可见。
2. 发送文字后立即出现在历史，并重启 App 后仍存在。
3. 点击历史文字仍复制到剪贴板。
4. 从 Mac mini full-media 恢复后历史数量不减少，重复项跳过。
5. 有对象文件的图片/视频能在历史页打开预览或系统打开。
6. 只有缩略图的旧图片仍显示缩略图或占位，不显示误导性的原图路径。
7. 清空历史清空 SQLite 历史，UI 立即变空。
8. `versionName=0.1.4`、`versionCode=1101` 保持不变，除非另行决定发布版本号。

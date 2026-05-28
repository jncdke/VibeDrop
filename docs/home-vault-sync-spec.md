# Home Vault 同步与查看器规格

## 背景

当前 VibeDrop 的桌面端历史保存在用户目录，而不是项目仓库：新版路径是 `~/.vibedrop/history.jsonl`，旧版路径是 `~/.voicedrop/history.jsonl`，桌面端收到的图片主要在 `~/.vibedrop/received-images`。用户希望把 Mac mini 外置盘 `/Volumes/SN850X` 当作家庭服务器，先完整迁移当前可读取的数据，再提供一个局域网网页查看器，后续可以演进为定时增量同步。

## 已确认现状

1. Mac mini 可通过 `mini@minideMac-mini.local` 访问，外置盘 `/Volumes/SN850X` 可用。
2. 本机桌面端历史共有两份来源：`~/.vibedrop/history.jsonl` 和 `~/.voicedrop/history.jsonl`。
3. Android 端历史由 Tauri 原生命令写入 app 私有目录 `history.json`，当前没有在线 ADB 设备时，脚本不能直接读取手机私有数据。
4. `~/.vibedrop/signing` 包含签名证书、keychain 和密码类文件，不属于历史数据，默认必须排除，避免把敏感构建材料同步到家庭服务器。

## 目标

1. 在 Mac mini 外置盘创建稳定的 `VibeDropVault` 数据库目录。
2. 首次迁移 MacBook 当前可读取的 VibeDrop 桌面端历史、媒体、调试日志和基础元数据。
3. 媒体按内容哈希去重保存，重复图片或重复文件只存一份。
4. 生成 SQLite 索引、JSON 查看数据和静态网页查看器，用户可在局域网浏览器中核对时间、文本、图片缩略图；有缩略图但没有原图的 Android 记录视为可正常预览，不在界面上提示原图缺失。
5. 生成迁移报告，明确哪些源文件已读取、多少行被解析、多少媒体成功入库、哪些媒体缺失、Android 私有历史是否已导入。
6. 为后续每小时同步保留增量结构，不采用无限增长的全量快照文件夹。

## 非目标

1. 本阶段不重构 Android/Mac 原生主程序，也不改变 VibeDrop 传输协议。
2. 本阶段不把 Android 私有历史强行视为已迁移；没有设备在线或没有导出权限时，只在报告中标注未导入。
3. 不同步 `~/.vibedrop/signing`。
4. 不做公网访问、账号体系或远程加密分享；查看器默认只面向家庭局域网。

## 数据结构

Vault 根目录：

```text
VibeDropVault/
  open-home-vault-viewer.webloc   # Mac mini 本机双击入口，直接打开 localhost viewer
  open-home-vault-viewer.html     # HTML 兜底入口，立即跳转到 viewer
  objects/00..ff/                 # 内容寻址对象库，固定 256 个桶
  db/vibedrop.sqlite              # 规范化索引、快照和校验记录
  viewer/index.html               # 静态查看器
  viewer/app.js
  viewer/style.css
  viewer/data/history.json        # 查看器读取的数据快照
  viewer/data/report.json         # 最近一次同步报告
  manifests/*.json                # 每次同步的机器可读报告
  logs/viewer-http.log            # 可选本地 HTTP 服务日志
  sources/macbook/mirror/         # 原始数据镜像，rsync 增量维护
```

媒体对象路径由文件内容决定：`objects/<sha256 前两位>/<sha256><原扩展名>`。这和 Apple Photos 用固定分桶降低单目录压力的思路类似，但这里用完整内容哈希做去重和校验，比按 UUID 首字符更适合备份与同步。

SQLite 保存四类核心索引：`history_entries` 保存历史记录，`media_objects` 保存去重文件对象，`history_media` 保存历史和媒体对象的关联，`snapshots/snapshot_entries/source_files` 保存每次同步的可追溯状态。

## 查看器入口

Vault 根目录必须生成 `open-home-vault-viewer.webloc`，用于在 Mac mini Finder 中双击直接打开历史查看器；这个文件指向 `http://localhost:8787/viewer/`，不会先显示中转页面。Vault 根目录也保留 `open-home-vault-viewer.html` 作为兜底入口，但必须立即跳转，不展示中间卡片。项目根目录同样保留 `.webloc` 和 HTML 入口，默认指向 `http://192.168.3.2:8787/viewer/`，用于在 MacBook 上一键查看 Mac mini Vault，避免 `.local` 在浏览器或代理环境中返回 502。

入口页只负责跳转到 HTTP viewer，不直接用 `file://` 加载 `viewer/index.html`。原因是查看器需要读取 `viewer/data/history.json` 和 `report.json`，浏览器直接打开本地 HTML 时可能拦截本地 JSON 读取；HTTP 服务路径更稳定，也和手机同步后刷新的静态 viewer 形态一致。

## 同步流程

1. 在本机创建临时 staging 目录，并创建 256 个对象桶。
2. 如果远端已有 `db/vibedrop.sqlite`，先拉回本地继续写入，保留历史同步快照；没有则新建数据库。
3. 读取 `~/.vibedrop/history.jsonl` 和 `~/.voicedrop/history.jsonl`，逐行解析 JSON；历史条目 ID 使用原始 JSON 行的 SHA-256，和桌面端“相同行去重”的语义一致。
4. 从历史记录中提取 `image_path`、`file_path`、`saved_path`、`items[]`、camelCase/snake_case 变体字段；存在的本地文件计算 SHA-256 并进入对象库，不存在则记录缺失原因。
5. 生成查看器 JSON 和同步报告。
6. 使用 rsync 把 staging 中的 `objects/db/viewer/manifests` 增量同步到 Mac mini。
7. 使用 rsync 把本机原始数据镜像到 `sources/macbook/mirror`，排除 `signing`。
8. 可选启动 Mac mini 上的 Python 静态 HTTP 服务，浏览器访问 `http://minideMac-mini.local:<port>/viewer/`。

## 校验方式

同步报告需要至少包含：源历史文件行数、解析成功数、解析失败数、去重后历史条数、媒体引用数、成功入库媒体数、原始文件缺失数、对象库新增/复用状态、Android 导入状态、远端路径、Vault 路径和查看器 URL。查看器界面只把既没有对象文件也没有缩略图的媒体显示为缺失；有缩略图的记录直接展示缩略图，不额外提示原图状态。查看器顶部同步信息必须把 `androidStatus` 等机器状态翻译成用户可读文案，例如 `Android 上传：2 份 / 4712 条`，不能直接展示 `skipped;vault_inbox:...` 或空路径 `无`。

## 后续演进

后续做每小时同步时，不创建每小时全量目录；只新增一个 `snapshots` 记录和少量 manifest，媒体对象按 SHA-256 去重，原始镜像由 rsync 只传变化。Android 端完整接入可以增加“导出历史到 Mac 服务”命令，或在设备在线调试时由脚本读取 app 私有 `history.json`。

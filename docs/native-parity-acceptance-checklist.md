# 原生重构功能等价验收清单

## 状态结论

截至当前分支 `codex/native-rewrite-phase1`，原生 Android 和原生 macOS 已覆盖 VibeDrop 旧 Tauri 版的主闭环：局域网发现、配对、WebSocket 认证、文字输入、回车、剪贴板直发、Mac 剪贴板同步到 Android、Android 图片到 Mac 剪贴板、Android 文件到 Mac 收件箱、Mac 拖拽/Finder 分享到 Android、分片传输、历史、热力图、媒体预览、导入导出分享清空、Home Vault 手机直同步、Mac mini 查看器兼容、诊断、设备排序、release 覆盖升级路径和 macOS 正式 bundle 部署路径。

这份清单用于正式替换 Tauri 前做人工验收。代码构建已经通过，但局域网、系统权限、系统分享菜单、Android 媒体库、macOS 辅助功能授权这些能力必须在真实设备上最终确认。

## Android 端

- 发送页 UI：品牌、设置入口、设备卡片、固定高度输入框、发送、回车、发送并回车、传图到剪贴板、传到收件箱、底部“发送/历史”导航。
- 草稿保留：连接中、断开、重连、设备卡片重建、设置页返回都不能清空未发送输入；成功发送后才清空对应设备草稿。
- 剪贴板兜底：输入框为空时，“发送”和“发送并回车”读取 Android 剪贴板；“回车”不读取剪贴板。
- 连接：保存 Mac 后自动连接；支持 host/ip 双端点轮换、OkHttp WebSocket ping、指数退避、旧 socket 回调隔离、错误日志。
- 发现与配对：扫描使用 UDP、已保存设备直连、HTTP `/discover` 局域网扫描；配对轮询 `/pair/status/{id}` 并保存 PIN/server 信息。
- Android 到 Mac：文本、回车、图片剪贴板、单文件、多文件、未知大小 URI 暂存、192KiB 分片、WebSocket 队列背压、完成 ack、失败清理。
- Mac 到 Android：接收 `incoming_history_session_start`、`incoming_file_start/chunk/complete`；图片/视频进 MediaStore，普通文件进下载集合；保存后回传 `incoming_file_saved`。
- 系统分享：文本分享填入第一台 Mac 草稿；图片可传到 Mac 剪贴板或收件箱；一个或多个文件可传到收件箱，成功后清除 pending 分享内容。
- 后台剪贴板：前台服务独立连接，使用 `clipboard_sync` role；直接写剪贴板失败时用透明 Activity 兜底。
- 历史页：搜索、发送端筛选、接收端筛选、类型、状态、时间、时段、活跃热力图、媒体预览、系统打开策略。
- 设置页：连接诊断、测试连接、网络/VPN 提示、后台稳定性检查、诊断日志分享、Home Vault 地址、同步到 Mac mini、媒体打开方式、导入导出分享清空历史、手动 Mac 编辑/删除/排序。
- 覆盖升级：release `applicationId=com.vibedrop.mobile`，`versionCode=1100`，使用同一签名产出 `VibeDrop-native-release-signed.apk`；首次覆盖安装迁移旧 Tauri 私有 `history.json`。

## macOS 端

- 服务：SwiftNIO HTTP/WebSocket、UDP discovery，默认端口 9001；支持 `/discover`、`/pair/request`、`/pair/status/{id}`、`/ws`。
- 权限：辅助功能权限检测和跳转；未授权时记录失败历史，不伪装成功。
- 输入：`type`、`type_enter`、`enter` 通过原生输入服务执行。
- 剪贴板：Mac 文本剪贴板变化广播给 `receives_clipboard=true` 的 Android 后台连接。
- Android 到 Mac：图片写入 Mac 剪贴板并保存缩略图；文件分片进 `~/Downloads/VibeDrop 收件箱`；多文件聚合历史。
- Mac 到 Android：拖拽、选择文件、Finder Share Extension、Finder service workflow 都进入同一发送路径；目录或多文件打 ZIP；等待 Android 保存 ack。
- Share Extension：正式部署嵌入 `VibeDropShare.appex`；`/share-extension/paths` 只允许 loopback，且没有在线文件接收端时返回明确错误。
- UI：概览、连接信息、连接设备、配对确认、发送到手机、进度、诊断日志、历史页、筛选、热力图、媒体预览、系统打开/打开全部。
- 状态栏：MenuBarExtra 显示地址、PIN、复制、开机启动、打开窗口、退出。
- 历史：SQLite 主库，启动导入 `~/.vibedrop/history.jsonl` 和 legacy `~/.voicedrop/history.jsonl`，新记录同时追加 JSONL，保持 Home Vault 脚本兼容。
- 正式部署：`deploy-native-macos-release.sh` 生成 `VibeDrop.app`、bundle id `com.vibedrop.desktop`，嵌入 Share Extension，优先稳定签名，默认不覆盖安装，`--install` 才替换 `/Applications/VibeDrop.app`。

## Home Vault

- Android 直同步：设置页 POST 当前 Room 历史到 `/api/android-history`，payload 保持 `schemaVersion/app/deviceId/deviceName/exportedAt/history`。
- 接收服务：保存到 `VibeDropVault/inbox/android/...json`，刷新 SQLite/viewer。
- 查看器：搜索、类型、发送端、接收端、候选数量、按数量排序、设备 alias merge、缩略图展示；不再提示 Android 原图 `no-path`。
- 对象存储：使用 bucket 分目录，避免单目录文件过多。

## 自动验证已通过

- `swift test`：macOS 原生测试全通过。
- Android `:app:testDebugUnitTest`：协议 fixture、auth payload、历史导出字段测试全通过。
- `./scripts/deploy-native-macos.sh --skip-install --no-sign`：预览 `.app` 构建通过。
- `./scripts/deploy-native-macos-release.sh --skip-install --no-sign`：正式 `.app` 和 Share Extension 嵌入通过。
- `./scripts/deploy-native-macos-release.sh --skip-install`：稳定签名、Share Extension 签名、主 app 签名、`codesign --verify --deep --strict` 通过。
- Android `:app:assembleDebug` 通过。
- Android `:app:assembleRelease` 通过。
- `git diff --check` 通过。

## 正式替换前人工验收

1. 安装 Android release 覆盖包，确认旧历史迁移数量、历史筛选、Home Vault 同步、系统分享入口。
2. 安装 macOS release 到 `/Applications/VibeDrop.app`，确认辅助功能权限是否保留；如果签名身份变了，需要重新授权。
3. 用一台 Android 连接一台 Mac，验证文字、回车、发送并回车、剪贴板兜底。
4. Android 发图片到 Mac 剪贴板，Mac 直接粘贴确认。
5. Android 发单文件、多文件、未知大小 URI 到 Mac 收件箱，确认历史和文件落盘。
6. Mac 拖拽文件/文件夹到 App、Finder 分享、Finder 右键服务发送到 Android，确认 MediaStore/Downloads 落盘和历史聚合。
7. 断网/切 Wi-Fi/短暂关闭 Mac 服务，确认 Android 草稿不丢、自动重连、错误可读。
8. Home Vault 同步后打开 Mac mini viewer，确认总数、缩略图、发送端/接收端筛选数量。

## 暂不作为首版阻塞

- Home Vault 后台定时自动同步；当前按前台按钮触发，避免首版后台偷跑大流量。
- v2 协议 envelope、checksum、能力协商；当前先保持 v1 完整兼容。
- Android 原图上传到 Vault；当前只同步缩略图和元数据。
- iOS、Windows、Linux 原生端。

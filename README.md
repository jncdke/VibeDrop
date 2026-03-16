# VibeDrop — Mac ↔ Android 剪贴板同步 & 文字传输工具

VibeDrop 是一个局域网内的 **剪贴板同步 + 文字传输** 工具，由两个独立应用组成：

- **Mac 桌面端**（`desktop/`）
- **Android 手机端**（`mobile/`）

两端通过 **WebSocket**（`ws://`）在局域网内通信，无需互联网，无需云服务。

---

## 功能一览

| 功能 | Mac 端 | Android 端 |
|------|--------|-----------|
| 📝 文字传输（手机 → Mac） | ✅ 接收并模拟键盘输入 | ✅ 发送 |
| 📋 剪贴板同步（Mac → 手机） | ✅ 每 500ms 监听变化广播 | ✅ 原生插件写入剪贴板 |
| 🔒 PIN 码认证 | ✅ 随机生成，持久化到文件 | ✅ 输入连接 |
| 📜 历史记录 | ✅ localStorage + 文件导出 | ✅ 双重持久化（localStorage + 文件） |
| 📈 活跃热力图 | ✅ 最近 7 天接收热力图 | ✅ 历史页 5 天窗口发送热力图 |
| 📎 点击复制 | ✅ Toast 浮层提示 | ✅ Toast 浮层提示 |
| 🔍 测试连接 | — | ✅ 设置页内直接测试 |
| 📡 前台保活服务 | — | ✅ 通知栏常驻，后台不被杀 |
| 🖥 系统托盘 | ✅ 菜单栏图标 + 状态显示 | — |
| 🚀 开机自启 | ✅ LaunchAgent | — |

---

## 技术架构总览

```
┌──────────────────────────────────────────────────┐
│                 Mac 桌面端 (Tauri v2)              │
│                                                    │
│  ┌─────────┐  ┌─────────────────────────────┐     │
│  │ Tauri   │  │     Axum HTTP Server :9001   │     │
│  │ Window  │  │                              │     │
│  │         │  │  /ws ── WebSocket handler    │     │
│  │ main.js │  │  /* ── 静态文件 (ServeDir)    │     │
│  │ (UI)    │  │                              │     │
│  └─────────┘  └──────────────┬───────────────┘     │
│                              │                      │
│  ┌──────────┐  ┌──────────┐  │  ┌────────────┐     │
│  │ arboard  │  │ enigo    │  │  │ broadcast  │     │
│  │ 剪贴板   │→→│ 键盘模拟 │  │  │ channel    │     │
│  │ 每500ms  │  │ (独立线程)│  │  │ (剪贴板→WS)│     │
│  └──────────┘  └──────────┘  │  └────────────┘     │
└──────────────────────────────┼──────────────────────┘
                               │ ws://IP:9001/ws
                               │ (局域网 WiFi)
┌──────────────────────────────┼──────────────────────┐
│            Android 手机端 (Tauri v2 Mobile)          │
│                              │                       │
│  ┌──────────────────────┐    │                       │
│  │ Tauri WebView        │    │                       │
│  │                      │    │                       │
│  │ app.js (JS 逻辑)     │◄───┘                       │
│  │ - WebSocket 客户端    │                            │
│  │ - 发送文字            │                            │
│  │ - 接收剪贴板同步      │                            │
│  │ - 历史记录管理        │                            │
│  └──────────────────────┘                            │
│                                                      │
│  ┌──────────────┐  ┌──────────────┐                  │
│  │ lib.rs       │  │ Kotlin 原生   │                  │
│  │ (Rust 命令)  │  │              │                  │
│  │ save_history │  │ KeepAlive    │                  │
│  │ load_history │  │ Service      │                  │
│  │ export_file  │  │ (前台保活)    │                  │
│  └──────────────┘  └──────────────┘                  │
│                                                      │
│  ┌──────────────────────┐                            │
│  │ tauri-plugin-        │                            │
│  │ clipboard-manager    │                            │
│  │ (原生剪贴板写入)      │                            │
│  └──────────────────────┘                            │
└──────────────────────────────────────────────────────┘
```

---

## 核心流程详解

### 1. 文字传输（手机 → Mac）

```
手机 app.js                    Mac main.rs
    │                              │
    ├─ WebSocket connect ─────────►│ ws_handler() 接受连接
    ├─ {action:"auth",pin:"1234"}─►│ 验证 PIN
    │◄─ {status:"ok",hostname:..}──┤ 认证成功
    │                              │
    ├─ {action:"text",text:"Hi"}──►│
    │                              ├─ mpsc::channel 发到 enigo 线程
    │                              ├─ enigo.text("Hi") 模拟键盘输入
    │                              ├─ 写入历史文件 (~/.vibedrop/history.jsonl)
    │                              ├─ emit("new-log") 通知 Tauri 前端
    │◄─ {status:"ok"} ────────────┤
```

**关键细节：**
- `enigo` 运行在**独立线程**，因为它需要在主线程或专属线程上操作（macOS 限制）
- 通过 `mpsc::channel<TypeRequest>` 跨线程通信，`TypeRequest` 包含 `oneshot::Sender` 用于返回结果
- 文字通过 `enigo.text()` 模拟键盘输入到当前**光标所在位置**，不需要目标 APP 配合

### 2. 剪贴板同步（Mac → 手机）

```
Mac main.rs                     手机 app.js
    │                              │
    ├─ 剪贴板监听线程 (每500ms)      │
    ├─ arboard::Clipboard.get()    │
    ├─ 检测到变化                    │
    ├─ broadcast::channel.send()   │
    │                              │
    │  (所有已认证的 WebSocket      │
    │   连接都会收到广播)            │
    │                              │
    ├─ {action:"clipboard",       ►│ 收到剪贴板数据
    │   text:"..."}                │
    │                              ├─ writeClipboard(text)
    │                              │   优先: __TAURI__.clipboardManager (原生)
    │                              │   兜底: navigator.clipboard (浏览器)
    │                              ├─ showToast("已同步到剪贴板")
```

**关键细节：**
- 剪贴板监听在**独立线程**（非异步），每 500ms 轮询
- 使用 `tokio::sync::broadcast::channel` 一对多广播
- 每个 WebSocket 连接持有一个 `broadcast::Receiver`，认证后开始监听
- Android 原生插件 `tauri-plugin-clipboard-manager` 不受 WebView 前台/后台限制

### 3. WebSocket 连接管理（手机端）

```
app.js 连接生命周期：

  connectDevice(macId, ip, port, pin)
       │
       ├─ new WebSocket(`ws://${ip}:${port}/ws`)
       │
       ├─ onopen → 发送 auth 消息
       │
       ├─ onmessage
       │    ├─ auth 成功 → 启动心跳 (每5秒 ping)
       │    ├─ clipboard → writeClipboard()
       │    ├─ text 确认 → 更新历史状态
       │    └─ pong → 重置超时计时器
       │
       ├─ onerror / onclose
       │    ├─ 清除心跳/超时定时器
       │    └─ scheduleReconnect (3秒后重连)
       │
  心跳机制：
       ├─ 每 5 秒发送 {action:"ping"}
       ├─ 10 秒内没收到 pong → 关闭连接
       └─ 关闭后 3 秒自动重连
```

### 4. 历史记录持久化（手机端）

```
双重存储架构：

  localStorage (快速读取)     history.json (持久化)
       │                          │
       │◄── getHistory() ─────────┤ 启动时 loadPersistentHistory()
       │                          │ 从文件恢复到 localStorage
       │                          │
       ├── addHistory() ──────────►│ persistHistory() 同步到文件
       ├── updateHistory() ───────►│ persistHistory()
       ├── clearHistory() ────────►│ persistHistory()
       ├── importHistory() ───────►│ persistHistory()
       │                          │
       │  文件路径 (Android):       │
       │  /data/data/com.vibedrop.mobile/files/history.json
       │  √ adb install -r 不会清除
       │  × 卸载 APP 会清除
```

---

## 每个文件的职责

### Mac 桌面端

| 文件 | 行数 | 职责 |
|------|------|------|
| `src-tauri/src/main.rs` | ~507 | **核心**：HTTP 服务器、WebSocket 处理、PIN 认证、键盘模拟（enigo）、剪贴板监听（arboard）、系统托盘、历史日志写入 |
| `src/main.js` | ~400 | Mac 窗口 UI 逻辑：显示连接信息（IP/端口/PIN），实时日志列表，最近 7 天接收热力图，点击复制，辅助功能权限检测 |
| `src/index.html` | ~90 | Mac 窗口 HTML 结构 |
| `src/style.css` | ~520 | Mac 窗口样式（深色主题 + 接收热力图布局） |
| `static/*` | — | HTTP 服务器提供的文件（手机浏览器版），与 `mobile/src/` 保持同步 |

### Android 手机端

| 文件 | 行数 | 职责 |
|------|------|------|
| `src/app.js` | ~1400 | **核心**：WebSocket 客户端、多设备管理、发送文字、接收剪贴板、历史记录（增删改查导入导出）、历史页热力图、心跳重连、Toast 提示、持久化同步 |
| `src/index.html` | ~170 | 手机 UI 结构：设置页（连接配置/测试连接/数据管理）、发送页、历史页、热力图区域、底部导航 |
| `src/style.css` | ~820 | 手机样式（浅色玻璃质感，适配触控，包含历史热力图布局） |
| `src-tauri/src/lib.rs` | ~46 | Rust 命令：`save_history`（持久化）、`load_history`（恢复）、`export_history_file`（导出到 Download） |
| `gen/android/.../MainActivity.kt` | ~34 | 启动前台服务、请求通知权限 |
| `gen/android/.../KeepAliveService.kt` | ~66 | 前台服务：创建通知渠道、常驻通知"VibeDrop 同步中"、`START_STICKY` 被杀自动重启 |
| `gen/android/.../AndroidManifest.xml` | ~55 | 权限声明：INTERNET、FOREGROUND_SERVICE、POST_NOTIFICATIONS、WRITE/READ_EXTERNAL_STORAGE |
| `src-tauri/capabilities/default.json` | ~14 | Tauri IPC 权限：`clipboard-manager:allow-write-text/read-text` |

---

## Mac 端线程模型

```
main() 启动后有 4 个并行执行单元：

[主线程] Tauri App
    │
    ├─── [线程1] enigo 键盘模拟
    │    └─ 独立 tokio runtime
    │    └─ 通过 mpsc::channel 接收 TypeRequest
    │    └─ 调用 enigo.text() 模拟输入
    │
    ├─── [线程2] 剪贴板监听（非异步 loop）
    │    └─ arboard::Clipboard 每 500ms 轮询
    │    └─ 变化时通过 broadcast::channel 广播
    │
    └─── [线程3] Axum HTTP/WebSocket 服务器
         └─ 独立 tokio multi-thread runtime
         └─ 每个 WebSocket 连接一个异步 task
         └─ 内含：认证、文字处理、剪贴板订阅

线程间通信：
  - enigo:     mpsc::channel<TypeRequest>（带 oneshot 回复）
  - 剪贴板:    broadcast::channel<String>（一对多）
  - Tauri UI:  AppHandle.emit()（Rust → JS 事件）
```

---

## Android 端特殊处理

这些都是在开发过程中踩过的坑，接手时需要注意：

| 问题 | 根因 | 解决方案 | 相关文件 |
|------|------|---------|---------|
| `navigator.clipboard.writeText` 时有时无 | WebView 后台时失去焦点，浏览器 API 要求前台 | `tauri-plugin-clipboard-manager` 原生插件 | `app.js writeClipboard()` |
| Release APK 连不上 `ws://` | Android 9+ 默认禁止明文流量 | `build.gradle.kts` 中 `usesCleartextTraffic = "true"` | `build.gradle.kts:20` |
| APP 切后台后连接断开 | Android 系统杀后台进程 | `KeepAliveService` 前台服务 + 通知栏常驻 | `KeepAliveService.kt` |
| `<a download>` 导出没反应 | Tauri WebView 不支持此 API | Rust 命令 `export_history_file` 直写 `/storage/emulated/0/Download/` | `lib.rs` |
| `navigator.share` 没弹出 | Tauri WebView 限制 | 同上，Rust 命令代替 | `lib.rs` |
| 更新 APP 后历史丢失 | WebView localStorage 不保证跨版本 | Rust 命令持久化到 `app_data_dir/history.json` | `lib.rs` + `app.js persistHistory()` |
| `adb install -r` 后 APP 没更新 | 旧进程还在运行 | 安装后执行 `adb shell am force-stop` + `am start` | 构建脚本 |

---

## 构建与部署

### 前置要求

```bash
# Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-linux-android  # Android 交叉编译

# Android SDK + NDK（通过 Android Studio 或命令行）
# 环境变量：ANDROID_HOME, NDK_HOME, JAVA_HOME

# Node.js (npm,仅 Android Tauri 需要)
```

### Mac 桌面端

```bash
cd desktop

# 开发模式（热重载）
cargo tauri dev

# 生产构建（生成 .app 和 .dmg）
cargo tauri build
```

高频改代码测试时，可以直接用根目录脚本一键重装桌面端：

```bash
./scripts/deploy-desktop.sh
```

桌面端和 Android 端现在共用一张主图：

- 根目录 `图标.jpg` 作为 V1 原始图标源
- 部署脚本会在构建前自动重建各端需要的 PNG / ICO / ICNS / Android launcher 资源

注意：
- 桌面端辅助功能授权依赖稳定的 bundle identifier，当前固定为 `com.vibedrop.desktop`
- 不要手动把 `.app` 拖来拖去或自己 `cp` 覆盖安装，统一走 `./scripts/deploy-desktop.sh`
- 这个脚本会自动校验 bundle identifier、使用固定的本地签名身份并刷新 Launch Services，避免辅助功能开关点了又弹回去
- 默认签名材料在 `~/.vibedrop/signing/`，找不到稳定签名身份时脚本会直接报错，不再静默退回 ad-hoc

常用参数：

```bash
# 只重装和重启，不重新生成图标、不重新编译
./scripts/deploy-desktop.sh --skip-icons --skip-build

# 只重装和重启，不重新编译
./scripts/deploy-desktop.sh --skip-build

# 安装后不自动打开
./scripts/deploy-desktop.sh --no-open
```

### 桌面端本地签名与备份

桌面端现在默认使用一套本机专用的本地代码签名身份，而不是 ad-hoc 签名。这样重装 `.app` 时，macOS 更容易把它识别成同一个应用，辅助功能授权不会因为每次构建都变化的签名而反复失效。

签名材料默认放在：

```bash
~/.vibedrop/signing/
```

其中最重要的文件有：

- `~/.vibedrop/signing/vibedrop-codesign.keychain-db`
  桌面部署脚本默认使用的专用 keychain
- `~/.vibedrop/signing/.keychain-password`
  上面这个 keychain 的密码
- `~/.vibedrop/signing/.p12-password`
  `.p12` 备份文件的加密密码
- `~/.vibedrop/signing/backups/VibeTech-Local-Code-Signing-*.p12`
  可迁移到其他机器的签名身份备份

备份规则：

- `~/.vibedrop/signing/` 属于本机状态目录，不要放进 git 仓库
- 至少把最新的 `.p12` 备份文件和对应的 `.p12` 密码保存到密码管理器或离线加密介质
- 如果以后换机器，只需要导入最新的 `.p12`，并把脚本指向对应的 keychain 即可
- 除非只是临时救火，否则不要设置 `ALLOW_ADHOC_FALLBACK=1`；一旦退回 ad-hoc，辅助功能授权可能再次要求重授

如果要手动恢复到另一台 Mac，核心流程是：

```bash
security import ~/path/to/VibeTech-Local-Code-Signing-xxxx.p12 \
  -k ~/Library/Keychains/login.keychain-db \
  -f pkcs12
```

然后让 `KEYCHAIN_PATH` 指向你想用的 keychain，再跑：

```bash
./scripts/deploy-desktop.sh
```

### Android 手机端

```bash
cd mobile

# 构建 Release APK（仅 arm64）
cargo tauri android build --target aarch64

# 签名 APK
BUILD_TOOLS=$(ls -d $ANDROID_HOME/build-tools/* | tail -1)
$BUILD_TOOLS/apksigner sign \
  --ks ~/.android/vibedrop.keystore \
  --ks-pass pass:vibedrop123 \
  --out ~/Desktop/VibeDrop.apk \
  src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release-unsigned.apk

# USB 安装 + 强制重启（重要！!不重启不会生效）
adb install -r ~/Desktop/VibeDrop.apk
adb shell am force-stop com.vibedrop.mobile
adb shell am start -n com.vibedrop.mobile/.MainActivity
```

高频改代码测试时，可以直接用根目录脚本一键重装：

```bash
./scripts/deploy-android.sh
```

这个脚本现在会在构建前自动读取根目录的 V1 原图 `图标.jpg`，并重新生成：

- 根目录 `图标.jpg` 会先被转换成最新的：
- `mobile/src/icon.png`
- `mobile/src/icon-192.png`
- `mobile/src/icon-512.png`
- `desktop/src/icon.png`
- `desktop/static/icon.png`
- `desktop/static/icon-192.png`
- `desktop/static/icon-512.png`
- `mobile/src-tauri/icons/{32x32,64x64,128x128,128x128@2x,icon.png}`
- `desktop/src-tauri/icons/{32x32,64x64,128x128,128x128@2x,icon.png,icon.ico,icon.icns}`
- `mobile/src-tauri/icons/{icon.ico,icon.icns}`
- Android launcher 的各个 `mipmap-*` 图标资源

也就是说，以后你只需要改一张源图 `图标.jpg`，再运行部署脚本即可。

常用参数：

```bash
# 多设备时指定序列号
./scripts/deploy-android.sh --device 3B6F4FE910B8KRLS

# 只想重装 APK，不重新生成图标
./scripts/deploy-android.sh --skip-icons

# 如果同时改了 mobile/src 共享前端，顺手同步到 desktop/static
./scripts/deploy-android.sh --sync-static

# 只重新签名并安装现有 APK
./scripts/deploy-android.sh --skip-build
```

### 代码同步（两端共享前端文件）

手机端 `mobile/src/` 是前端源文件，修改后需要同步到 Mac 端的 `static/`：

```bash
cp mobile/src/app.js    desktop/static/app.js
cp mobile/src/index.html desktop/static/index.html
cp mobile/src/style.css  desktop/static/style.css
```

> **注意**：Mac 端自己的 UI 是 `src/main.js` + `src/index.html` + `src/style.css`，这三个文件和手机端**不共享**，是 Mac 桌面窗口专用的。Mac 端的 `static/` 目录是给手机浏览器版用的（通过 HTTP 服务器访问）。

---

## GitHub Actions

仓库现在带了两条自动化流水线：

- `.github/workflows/ci.yml`
  每次 `push` / `pull_request` 都会自动执行：
  - 从根目录 `图标.jpg` 重建图标资源
  - 校验 `mobile/src/*` 与 `desktop/static/*` 三个共享前端文件保持同步
  - `node --check` 校验 `mobile/src/app.js` 与 `desktop/src/main.js` 语法
  - `cargo check` 桌面端
  - `cargo tauri android build --debug --target aarch64` 验证 Android 工程

- `.github/workflows/release.yml`
  每次 push 一个 `v*` tag 时自动执行：
  - 重建图标资源
  - 校验共享前端文件同步状态
  - `node --check` 校验前端脚本语法
  - 构建 Android Release APK
  - 构建 macOS DMG
  - 生成 `SHA256SUMS`
  - 自动创建 / 更新 GitHub Release

标准发布方式：

```bash
git tag v0.1.4
git push origin v0.1.4
```

Android 自动签名是可选的。想让 Release 里直接得到**可安装**的签名 APK，需要在 GitHub 仓库设置里配置以下 Secrets：

- `ANDROID_KEYSTORE_BASE64`
  把 `~/.android/vibedrop.keystore` 做 base64 后存进去
- `ANDROID_KEYSTORE_PASSWORD`
  keystore 密码
- `ANDROID_KEY_PASSWORD`
  如果 key 密码和 keystore 密码不同，再单独配置；相同可不配

如果这些 Secrets 没配，Release workflow 仍然会产出 Android APK，但文件名会带 `-unsigned`，只能作为构建产物，不能直接正常安装覆盖。

这套做法就是比较标准的工程实践：

- 本地脚本负责你自己日常开发、快速部署到真机
- GitHub Actions 负责远程自动校验、自动打包、自动发布
- `tag` 对应 `release`
- 构建产物和校验文件都能回溯到具体提交

## Git / GitHub 协作建议

日常建议的最小流程：

```bash
# 1. 查看改动
git status

# 2. 提交本地改动
git add .
git commit -m "Add history heatmaps for mobile and desktop"

# 3. 推送到 GitHub
git push origin <your-branch>
```

如果这次改动涉及手机端共享前端，推送前建议至少确认：

```bash
cmp -s mobile/src/app.js desktop/static/app.js
cmp -s mobile/src/index.html desktop/static/index.html
cmp -s mobile/src/style.css desktop/static/style.css
```

发布时继续沿用现有 GitHub release 流程：

```bash
git tag v0.1.4
git push origin v0.1.4
```

---

## 配置

### 环境变量（Mac 端）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `VOICEDROP_PIN` | 随机 4 位数 | 覆盖 PIN；设置后优先于文件中的 PIN |
| `VOICEDROP_PORT` | `9001` | HTTP/WebSocket 端口 |

### 数据文件位置

| 文件 | 路径 | 说明 |
|------|------|------|
| Mac PIN | `~/.vibedrop/pin` | 4 位数字，重启不变，删除后重新生成 |
| Mac 历史日志 | `~/.vibedrop/history.jsonl` | 收到的文字记录（JSONL 格式，每行一条） |
| Mac 桌面签名 keychain | `~/.vibedrop/signing/vibedrop-codesign.keychain-db` | 桌面端固定本地签名身份，供 `deploy-desktop.sh` 使用 |
| Mac 桌面签名备份 | `~/.vibedrop/signing/backups/VibeTech-Local-Code-Signing-*.p12` | 可迁移到其他机器的桌面签名身份备份 |
| Mac 开机自启 | `~/Library/LaunchAgents/com.vibedrop.desktop.plist` | LaunchAgent 配置 |
| Android 历史 | `/data/data/com.vibedrop.mobile/files/history.json` | JSON 数组，`adb install -r` 不会清除 |
| Android 导出 | `/storage/emulated/0/Download/vibedrop_history_*.json` | 用户点导出时生成 |
| Android keystore | `~/.android/vibedrop.keystore` | APK 签名用，密码: `vibedrop123` |

---

## 通信协议

### WebSocket 端点

`ws://<Mac IP>:<端口>/ws`

### 消息格式（JSON）

**客户端 → 服务器：**

```jsonc
// 1. 认证（必须是第一条消息）
{ "action": "auth", "pin": "1234" }

// 2. 发送文字（认证后）
{ "action": "text", "text": "要输入的内容" }

// 3. 心跳（每 5 秒）
{ "action": "ping" }
```

**服务器 → 客户端：**

```jsonc
// 认证成功
{ "status": "ok", "hostname": "MacBook-Pro" }

// 认证失败
{ "status": "error", "error": "Invalid PIN" }

// 文字接收成功
{ "status": "ok" }

// 剪贴板同步广播（Mac 复制了新内容）
{ "action": "clipboard", "text": "Mac上复制的内容" }

// 心跳响应
{ "status": "pong" }
```

### 连接参数

| 参数 | 值 |
|------|------|
| 心跳间隔 | 5 秒 |
| 心跳超时 | 10 秒 |
| 自动重连间隔 | 3 秒 |
| 剪贴板轮询间隔 | 500ms |

---

## 技术栈详细

### Mac 桌面端 Rust 依赖

| crate | 版本 | 用途 |
|-------|------|------|
| `tauri` | 2.x | 桌面应用框架，WebView + Rust 后端 |
| `tauri-plugin-shell` | 2.x | Tauri shell 插件 |
| `axum` | 0.8 | HTTP/WebSocket 服务器框架 |
| `tokio` | 1.x | 异步运行时（多线程模式） |
| `enigo` | 0.2 | 键盘模拟，将文字输入到当前光标位置 |
| `arboard` | 3.x | 系统剪贴板读写 |
| `tower-http` | 0.6 | `ServeDir` 静态文件服务 |
| `local-ip-address` | 0.6 | 获取本机局域网 IP 地址 |
| `hostname` | 0.4 | 获取主机名 |
| `futures-util` | 0.3 | WebSocket Stream/Sink 分离 |
| `serde` / `serde_json` | 1.x | JSON 序列化/反序列化 |
| `tracing` / `tracing-subscriber` | 0.1/0.3 | 日志系统 |
| `chrono` | 0.4 | 时间戳格式化 |
| `png` | 0.17 | 解码系统托盘图标 PNG |

### Android 手机端 Rust 依赖

| crate | 版本 | 用途 |
|-------|------|------|
| `tauri` | 2.10.3 | 移动应用框架（WebView + native） |
| `tauri-plugin-clipboard-manager` | 2.x | 原生剪贴板写入（绕过 WebView 限制） |
| `serde` / `serde_json` | 1.x | JSON 序列化（历史记录文件） |

---

## 关键设计决策

1. **HTTP 而非 HTTPS**：原生 APP 不需要浏览器级别的 SSL，移除 HTTPS 后省去了证书管理的复杂性（之前尝试过 HTTPS，遇到证书信任问题后移除）

2. **enigo 键盘模拟**：收到文字后直接模拟键盘输入到当前光标位置，不需要目标应用配合。缺点是用户必须先点击目标输入框，优点是兼容所有应用

3. **broadcast channel 广播剪贴板**：`tokio::sync::broadcast` 支持多个接收者，每个已认证的 WebSocket 连接都会订阅一份。新连接认证后自动加入广播

4. **双重存储**（Android）：localStorage 用于快速同步读取，Rust 文件存储用于跨版本持久化。每次写入 localStorage 时同步调用 `persistHistory()`

5. **前台服务保活**（Android）：使用 `START_STICKY` 策略，被系统杀掉后自动重启。通知渠道设为 `IMPORTANCE_LOW`，不弹出不响铃

6. **静态文件服务器**：Mac 端的 Axum 除了 `/ws` 以外，还通过 `ServeDir` 提供整个 `static/` 目录，使得手机用浏览器访问 `http://IP:9001` 也能用（无需安装 APP）

---

## 注意事项（给接手者）

1. **辅助功能权限**：Mac 端首次运行需要在「系统设置→隐私与安全性→辅助功能」中授权 VibeDrop，否则 `enigo` 无法模拟键盘输入

2. **文件同步**：修改手机端前端代码后，**必须手动同步**到 Mac 端的 `static/` 目录，否则手机浏览器版不会更新。现在 CI / Release workflow 也会自动校验这三份共享文件是否同步

3. **gen 目录不要乱动**：`mobile/src-tauri/gen/android/` 是 Tauri 自动生成的 Android 项目，但我们手动修改了其中的 `AndroidManifest.xml`、`MainActivity.kt`、`KeepAliveService.kt`、`build.gradle.kts`。重新运行 `tauri init` 会覆盖这些修改

4. **签名**：APK 必须签名才能安装。keystore 在 `~/.android/vibedrop.keystore`，密码 `vibedrop123`。丢失 keystore 后用户需要卸载重装（无法覆盖安装）

5. **`adb install` 后必须强制重启 APP**：否则旧代码还在运行。命令：`adb shell am force-stop com.vibedrop.mobile && adb shell am start -n com.vibedrop.mobile/.MainActivity`

6. **Mac 端有两套前端**：`src/`（桌面窗口 UI）和 `static/`（手机浏览器版）。不要搞混

---

## 许可证

[MIT License](LICENSE) — 完全开源，自由使用。

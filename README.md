# VibeDrop — Mac ↔ Android 剪贴板同步工具

VibeDrop 是一个局域网内的 **剪贴板同步 + 文字传输** 工具，由两个独立应用组成：

- **Mac 桌面端**（本仓库）：接收手机发来的文字，自动输入到当前光标位置；同时监听 Mac 剪贴板变化，实时同步到手机
- **Android 手机端**（[vibedrop-mobile](../vibedrop-mobile)）：发送文字到 Mac，接收 Mac 剪贴板同步

两端通过 **WebSocket**（`ws://`）在局域网内通信，无需互联网，无需云服务。

---

## 功能一览

| 功能 | Mac 端 | Android 端 |
|------|--------|-----------|
| 📝 文字传输（手机 → Mac） | ✅ 接收并自动输入 | ✅ 发送 |
| 📋 剪贴板同步（Mac → 手机） | ✅ 监听剪贴板变化 | ✅ 原生写入剪贴板 |
| 🔒 PIN 码认证 | ✅ 随机生成，持久化 | ✅ 输入连接 |
| 📜 历史记录 | ✅ 本地存储 + 导出 | ✅ 持久化存储 + 导入/导出 |
| 📎 点击复制 | ✅ Toast 提示 | ✅ Toast 提示 |
| 🔍 设置页测试连接 | — | ✅ 不保存直接测试 |
| 📡 前台保活服务 | — | ✅ 通知栏常驻 |
| 🖥 系统托盘 | ✅ 菜单栏图标 | — |
| 🚀 开机自启 | ✅ LaunchAgent | — |

---

## 技术架构

```
┌─────────────────────────────────────────────┐
│                Mac 桌面端                      │
│  ┌──────────┐    ┌───────────┐               │
│  │ Tauri v2 │    │ Axum HTTP │               │
│  │ (窗口UI) │    │ Server    │               │
│  │          │    │ :9001     │               │
│  │ main.js  │    │           │               │
│  │ style.css│    │ /ws ──────┼── WebSocket   │
│  └──────────┘    │ /static   │               │
│                  └───────────┘               │
│  ┌──────────┐    ┌───────────┐               │
│  │ arboard  │    │ enigo     │               │
│  │ (剪贴板) │    │ (键盘模拟)│               │
│  └──────────┘    └───────────┘               │
└───────────────────────┬─────────────────────┘
                        │ ws://IP:9001/ws
                        │ (局域网)
┌───────────────────────┴─────────────────────┐
│              Android 手机端                    │
│  ┌──────────┐    ┌──────────────────┐        │
│  │ Tauri v2 │    │ WebSocket Client │        │
│  │ WebView  │    │ (app.js)         │        │
│  │          │    │                  │        │
│  │ app.js   │    │ 发送文字 ────────┤        │
│  │ style.css│    │ 接收剪贴板 ──────┤        │
│  └──────────┘    └──────────────────┘        │
│  ┌───────────────┐  ┌──────────────┐         │
│  │ clipboard     │  │ KeepAlive    │         │
│  │ plugin (原生) │  │ Service (保活)│         │
│  └───────────────┘  └──────────────┘         │
└──────────────────────────────────────────────┘
```

---

## 技术栈

### Mac 桌面端 (`安卓发送mac输入文字app/`)

| 技术 | 用途 | 版本 |
|------|------|------|
| **Tauri v2** | 桌面应用框架（Rust + WebView） | 2.x |
| **Axum** | HTTP/WebSocket 服务器 | 0.8 |
| **Tokio** | 异步运行时 | 1.x |
| **enigo** | 键盘模拟（自动输入文字） | 0.2 |
| **arboard** | 系统剪贴板读写 | 3.x |
| **tower-http** | 静态文件服务（ServeDir） | 0.6 |
| **local-ip-address** | 获取本机局域网 IP | 0.6 |
| **futures-util** | WebSocket Stream/Sink 操作 | 0.3 |

**前端**：原生 HTML + CSS + JavaScript（无框架）

### Android 手机端 (`vibedrop-mobile/`)

| 技术 | 用途 | 版本 |
|------|------|------|
| **Tauri v2 Mobile** | Android 应用框架 | 2.10.3 |
| **tauri-plugin-clipboard-manager** | 原生剪贴板写入 | 2.x |
| **Kotlin** | Android 前台服务 | — |
| **WebSocket (JS)** | 与 Mac 通信 | 浏览器原生 |

**前端**：同 Mac 端共享 `app.js` / `style.css` / `index.html`

---

## 项目结构

```
安卓发送mac输入文字app/          # Mac 桌面端
├── src/                         # Tauri 前端（Mac 桌面 UI）
│   ├── index.html               # Mac 窗口 HTML
│   ├── main.js                  # Mac 端 JS 逻辑
│   └── style.css                # Mac 端样式
├── static/                      # HTTP 服务器提供的静态文件（手机浏览器版）
│   ├── index.html               # 与 vibedrop-mobile/src/ 同步
│   ├── app.js                   # 与 vibedrop-mobile/src/ 同步
│   ├── style.css                # 与 vibedrop-mobile/src/ 同步
│   ├── icon.png                 # 应用图标 512x512
│   ├── manifest.json            # PWA manifest
│   └── sw.js                    # Service Worker
└── src-tauri/
    ├── Cargo.toml               # Rust 依赖
    ├── src/main.rs              # 核心逻辑（~500行）
    ├── tauri.conf.json          # Tauri 配置
    └── icons/                   # 应用图标

vibedrop-mobile/                 # Android 手机端
├── src/                         # Tauri 前端（共享代码）
│   ├── index.html
│   ├── app.js                   # 主逻辑（~900行）
│   └── style.css
├── src-tauri/
│   ├── Cargo.toml
│   ├── src/lib.rs               # Rust 命令（持久化、导出）
│   ├── tauri.conf.json
│   ├── capabilities/default.json # 权限配置
│   └── gen/android/             # Android 原生代码
│       └── app/src/main/
│           ├── AndroidManifest.xml
│           └── java/.../
│               ├── MainActivity.kt      # 启动前台服务
│               └── KeepAliveService.kt  # 保活服务
```

---

## 通信协议

### WebSocket 消息格式（JSON）

**客户端 → 服务器：**

```jsonc
// 认证
{ "action": "auth", "pin": "1234" }

// 发送文字（认证后）
{ "action": "text", "text": "要输入的内容" }

// 心跳
{ "action": "ping" }
```

**服务器 → 客户端：**

```jsonc
// 认证成功
{ "status": "ok", "hostname": "MacBook-Pro" }

// 认证失败
{ "status": "error", "error": "Invalid PIN" }

// 文字接收确认
{ "status": "ok" }

// 剪贴板同步（广播）
{ "action": "clipboard", "text": "Mac上复制的内容" }

// 心跳响应
{ "status": "pong" }
```

---

## 构建与部署

### 前置要求

- Rust（`rustup`）
- Node.js（仅 Android 构建需要）
- Android SDK + NDK（Android 构建）
- Xcode Command Line Tools（Mac 构建）

### Mac 桌面端

```bash
cd 安卓发送mac输入文字app

# 开发模式
cargo tauri dev

# 生产构建
cargo tauri build

# 安装
cp -a src-tauri/target/release/bundle/macos/VibeDrop.app /Applications/
codesign --force --deep --sign "Your Name" /Applications/VibeDrop.app
```

### Android 手机端

```bash
cd vibedrop-mobile

# 构建 APK
cargo tauri android build --target aarch64

# 签名（需要 keystore）
apksigner sign --ks ~/.android/vibedrop.keystore \
  --out ~/Desktop/VibeDrop.apk \
  src-tauri/gen/android/app/build/outputs/apk/universal/release/app-universal-release-unsigned.apk

# USB 安装
adb install -r ~/Desktop/VibeDrop.apk
adb shell am force-stop com.vibedrop.mobile
adb shell am start -n com.vibedrop.mobile/.MainActivity
```

---

## 配置

### 环境变量（Mac 端）

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `VOICEDROP_PIN` | 随机 4 位数 | 连接 PIN 码 |
| `VOICEDROP_PORT` | `9001` | HTTP 服务端口 |

### PIN 持久化

Mac 端的 PIN 保存在 `~/.vibedrop/pin` 文件中，重启不变。删除此文件会重新生成。

### 开机自启（Mac）

通过 `LaunchAgent` 实现：`~/Library/LaunchAgents/com.voicedrop.desktop.plist`

---

## Android 特殊处理

| 问题 | 解决方案 |
|------|---------|
| WebView `navigator.clipboard` 不稳定 | 使用 `tauri-plugin-clipboard-manager` 原生插件 |
| Release 模式禁止明文 `ws://` | `build.gradle.kts` 中 `usesCleartextTraffic = true` |
| 后台被系统杀掉 | `KeepAliveService` 前台服务 + 通知栏常驻 |
| WebView `<a download>` 不工作 | Rust 命令直写 `/storage/emulated/0/Download/` |
| WebView `localStorage` 更新后丢失 | Rust 命令持久化到 `app_data_dir/history.json` |

---

## 关键设计决策

1. **HTTP 而非 HTTPS**：原生 APP 不需要浏览器级别的 SSL，省去证书管理的复杂性
2. **Tauri v2**：跨平台（Mac + Android），共享前端代码，Rust 后端性能好
3. **WebSocket 双向通信**：文字传输 + 剪贴板同步复用同一连接
4. **enigo 键盘模拟**：收到文字后直接模拟键盘输入到当前光标位置，不需要目标应用配合
5. **broadcast channel**：剪贴板变化广播给所有已认证的 WebSocket 客户端
6. **前台服务保活**：Android 系统特性要求，避免后台被杀

---

## 许可证

私有项目。

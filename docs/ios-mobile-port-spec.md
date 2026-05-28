# iOS 移动端第一阶段规格

## 1. 背景与目标

当前 `mobile/` 已经是一套成熟的移动端前端，但它的“可用性”并不只来自 WebView 页面本身，而是来自三层叠加：

1. 前端页面：`mobile/src/index.html`、`mobile/src/style.css`、`mobile/src/app.js`
2. Rust 命令层：`mobile/src-tauri/src/lib.rs`
3. Android 宿主桥接层：`mobile/src-tauri/gen/android/app/src/main/java/com/vibedrop/mobile/MainActivity.kt`

这意味着 iOS 版不能简单理解为“把 Android target 切成 iOS”，因为：

- 发现附近电脑、历史持久化、配对轮询这类能力主要是跨平台的，已经在 Rust 命令层里
- 剪贴板后台写入、系统分享接入、媒体库扫描、设备命名、后台保活提示、外部文件打开等能力目前直接依赖 Android JS bridge
- Mac -> 手机收文件虽然前端协议是通用的，但当前 Rust 存储目录写死在 Android 的 `/storage/emulated/0/...`

因此 iOS 第一阶段的正确目标不是“补齐 Android 100% 功能”，而是先做出一条可上线的最小闭环：

- iPhone 安装 App
- 能发现附近 Mac 并配对连接
- 能稳定发送文字到 Mac
- 能接收 Mac 的剪贴板同步
- 能从 App 内选择文件/图片/视频发送到 Mac
- 能接收 Mac 发来的文件，并落到 iOS App 可见收件箱
- 历史记录、热力图、配对、连接管理继续可用

## 2. 第一阶段范围

### 2.1 本轮必须支持

1. `cargo tauri ios init` 后，仓库具备 iOS 工程脚手架
2. 前端继续复用现有 `mobile/src/*`
3. 附近电脑发现、配对、重连继续走 Rust 命令与 WebSocket
4. App 内文件选择上传继续可用，优先支持：
   - 照片/视频
   - 普通文件
   - 批量选择后的顺序发送
5. Mac -> iPhone 文件接收改为保存到 iOS 可见的 App 收件箱
6. 移动端 UI 不再把原生 App 一概显示为 “Android 手机”，而是根据平台显示更准确标签
7. 桌面端识别 iPhone 为支持接收文件的移动客户端

### 2.2 本轮明确不做

1. iOS 系统分享扩展（Share Extension）
2. iOS 后台剪贴板保活/后台服务等 Android 专属保活逻辑
3. iOS 相册级写入与“直接保存到系统照片”语义
4. iOS 原生媒体库扫描
5. iOS 原生“用指定 App 打开文件”的完整选择器
6. 与 Android 完全对齐的原生设备信息桥

这些不是不重要，而是它们属于第二阶段平台增强，不应该阻塞第一阶段闭环。

## 3. 现状问题拆解

### 3.1 前端层问题

当前 `mobile/src/app.js` 里存在两类问题：

1. 平台命名耦合：
   - `buildClientDisplayName()` 在有原生能力时默认文案是“Android 手机”
2. 原生能力判断过于抽象：
   - 现在的 `supportsNativeFileReceive()` 只是“有没有 Tauri invoke”
   - 这足够判断是否原生 App，但不足以区分 Android / iOS / 浏览器
3. 接收文件的保存目标语义是 Android 化的：
   - 图片默认去 `gallery-image`
   - 视频默认去 `gallery-video`
   - 这些语义在 iOS 第一阶段不成立

### 3.2 Rust 命令层问题

`mobile/src-tauri/src/lib.rs` 中目录模型写死为 Android 公共存储：

- `download_dir()` -> `/storage/emulated/0/Download`
- `incoming_save_dir()` -> `/storage/emulated/0/Download` / `Pictures/VibeDrop` / `Movies/VibeDrop`

这会直接导致 iOS 下：

- 编译或运行后目录逻辑不成立
- Mac 发给手机的文件没有可靠的落地位置
- 历史里的 `savedPath` 语义混乱

### 3.3 宿主桥接层问题

当前 Android 宿主桥负责：

- `NativeClipboard`
- `NativeShare`
- `NativeDevice`
- `NativeBackgroundClipboard`
- `NativeMediaLibrary`

iOS 第一阶段不可能马上把这些全部按 Android 语义一比一复制，所以必须做“能力分层”：

- Phase 1 只要求没有这些 bridge 时，核心功能仍能跑
- 真正必须的平台能力再逐项补 iOS 宿主实现

## 4. 第一阶段设计

### 4.1 平台能力分层

前端新增统一的平台判断：

- `web`
- `android`
- `ios`

判断原则：

1. 先判断是否为原生 Tauri 环境
2. 再根据 UA / 已存在 bridge / 运行平台特征区分 Android 与 iOS
3. 所有 UI 文案、默认设备名、接收文件保存策略，都走统一平台判断结果

这层抽象的意义是：以后不是“某个 if 写 Android 特判”，而是“宿主平台能力决定行为”。

### 4.2 iOS 收件箱模型

iOS 第一阶段不追求“系统下载目录”或“系统相册”，而是采用 App 可见收件箱：

- 目录：`Documents/VibeDrop Inbox`
- 原因：
  - `Documents` 是 iOS App 沙盒里最适合用户文件的目录
  - 可以通过 iOS 文件共享暴露给“文件”App
  - 不需要额外依赖相册写权限
  - 适合大文件和任意类型文件

最终策略：

1. 所有 Mac -> iPhone 传入文件都统一落到 `Documents/VibeDrop Inbox`
2. 历史记录里的 `savedPath` 保存实际绝对路径
3. UI toast 改成“已保存到 VibeDrop 收件箱”
4. iOS 第一阶段不区分图片/视频/下载目录三类目标

### 4.3 iOS 文件可见性

为了让用户在 iPhone 上真正能找到文件，需要打开 iOS 文档共享能力：

- `UIFileSharingEnabled = YES`
- `LSSupportsOpeningDocumentsInPlace = YES`

这样用户就能在“文件”App 里看到 VibeDrop 的文稿目录，而不是文件只存在于沙盒里却完全不可见。

### 4.4 剪贴板策略

Mac -> 手机剪贴板同步在第一阶段保持以下优先级：

1. Android：继续优先 `NativeClipboard`
2. 所有原生平台：尝试 Tauri clipboard plugin
3. 浏览器环境：退回 `navigator.clipboard`

这意味着 iOS 第一阶段不必先做独立 `NativeClipboard` 宿主桥，也能先跑起前台内可用的剪贴板写入能力。

### 4.5 iPhone 侧发送文件策略

第一阶段优先支持“App 内主动选择文件”：

- 继续复用 `<input type="file">`
- 继续复用当前大文件分块传输协议
- 继续复用当前批量顺序发送调度器

这条路线的好处是：

- 不依赖 iOS Share Extension
- 前端和协议层几乎无需重造
- 先保证用户在 App 内就能完成主要传输场景

### 4.6 设备展示文案

默认设备标签改为：

- Android 原生：`Android 手机`
- iOS 原生：`iPhone`
- 浏览器：`移动浏览器`

如果宿主能提供更具体的设备名，则仍以原生设备名优先。

### 4.7 桌面端兼容性

桌面端不需要为 iOS 新造协议，只需要继续依赖现有认证字段：

- `can_receive_files`

也就是说，只要 iOS 端能正常进入原生 Tauri 环境并发送正确认证，桌面端就会把它当成“支持接收文件的移动设备”。

## 5. 实施步骤

### Step 1. 初始化 iOS 工程

- 执行 `cargo tauri ios init`
- 生成 iOS 工程目录
- 确认工程能被 Xcode 打开

### Step 2. 改造前端平台层

- 新增移动宿主平台判断函数
- 调整默认设备名与部分 UI 文案
- 将“接收文件保存目标”对 iOS 统一改为收件箱

### Step 3. 改造 Rust 存储目录

- Android 保持现状
- iOS 改为 `Documents/VibeDrop Inbox`
- 导出历史也使用 iOS 可访问目录

### Step 4. 暴露 iOS 文档共享

- 在 iOS 工程配置里开启文件共享与原位打开

### Step 5. 验证链路

至少验证这些命令能成立：

- iOS 工程初始化成功
- Rust / JS 语法检查通过
- iOS target 已进入构建链路

如果本机签名条件允许，再进一步验证：

- `cargo tauri ios dev`

## 6. 验收标准

满足以下条件即可视为 iOS 第一阶段完成：

1. 仓库存在可维护的 iOS 工程脚手架
2. 代码里不再把所有原生移动端都当作 Android
3. iOS 环境下附近电脑发现、配对、文字发送逻辑可复用
4. iOS 环境下 Mac -> 手机收文件有明确稳定落点
5. 文件可从 iPhone “文件”App 找到
6. Android 现有能力不回退

## 7. 第二阶段预留

后续增强方向：

1. iOS 原生设备信息桥，展示具体机型名
2. iOS Share Extension，支持系统分享进 VibeDrop
3. iOS 原生媒体打开与 QuickLook 预览
4. iOS 照片库保存能力
5. iOS 更细的权限提示与首次引导
6. iOS 特有的前后台连接体验优化

第一阶段的重点是把“iPhone 成为可用客户端”做实，而不是一开始就把所有平台差异全部吃掉。

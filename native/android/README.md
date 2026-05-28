# VibeDrop native Android

这是 Android 原生重构入口，目标技术栈是 Kotlin + Jetpack Compose + Room + OkHttp。当前阶段先建立可编译骨架、复刻发送页结构、沉淀协议与本地数据模型；后续阶段再接入真实 WebSocket、发现、配对、文件传输、后台剪贴板和 Home Vault。

## 设计约束

1. release `applicationId` 保持 `com.vibedrop.mobile`，用于未来覆盖升级并读取旧 app 私有历史。
2. debug 使用 `.nativepreview` 后缀，便于和当前 Tauri 版并排安装测试。
3. UI 先复刻当前发送页肌肉记忆，连接逻辑后接入，不先重做设计。
4. Room schema 先覆盖设备、历史主表、历史 item，迁移器后续读取旧 `history.json`。
5. 当前已接入手动 Mac 配置、Room 历史列表、旧 `history.json` 一次性迁移、v1 文本发送和剪贴板直发兜底。
6. 当前已接入 UDP 广播发现、已知设备 `/discover` 校验、Mac 端配对请求和状态轮询。
7. 当前已接入前台服务版后台剪贴板同步，使用 v1 `clipboard_sync` 角色接收 Mac 剪贴板。

## 当前可用闭环

1. 设置页手动保存 Mac host、端口和 PIN。
2. 发送页自动建立 OkHttp WebSocket 到旧 Mac `/ws`。
3. 点击“发送”或“发送并回车”时，输入框为空会读取 Android 系统剪贴板。
4. WebSocket 入队成功后写入 Room 历史，历史页展示最近记录。
5. release 包首次覆盖安装时会尝试读取旧 Tauri 私有目录里的 `history.json` 并导入 Room；debug 预览包因为 applicationId suffix 不会读到旧正式包私有目录。
6. 设置页点击“扫描”会寻找当前局域网里的旧 Mac Tauri 服务；点击“配对”后 Mac 会弹出确认码，批准后原生 Android 自动保存 PIN 和连接信息。
7. App 运行后会启动 `ClipboardSyncService`，从 Room 读取已保存 Mac，建立后台 WebSocket，收到 `clipboard` 消息后写入 Android 剪贴板。透明 Activity 兜底和详细诊断仍属于后续补齐项。

## 构建

从仓库根目录可以复用现有 Gradle wrapper：

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  mobile/src-tauri/gen/android/gradlew -p native/android :app:assembleDebug
```

当前机器默认 Java 25 会让 Gradle Kotlin DSL 里的 Kotlin 编译器在解析 Java 版本时失败；Android Studio 自带 JBR 21 可以正常构建。

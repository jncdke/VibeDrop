# Android 原生旧设备配置迁移规格

## 背景

原生 release 覆盖安装后，真机启动正常，但发送页显示“还没有保存的 Mac”。当前原生迁移器只读取旧 Tauri 私有目录里的 `history.json`，会迁移历史和从历史里弱恢复接收端名称；但旧 Tauri 实际保存可连接 Mac/PIN 的路径还包括 Android 原生 SharedPreferences：`vibedrop_background_clipboard` 下的 `config_json`。这份配置由旧 WebView 前端通过 `NativeBackgroundClipboard.syncConfig()` 同步给后台剪贴板服务，结构包含 `devices[{id,name,ip,port,pin}]`。如果不迁移它，覆盖安装后用户需要重新配对 Mac，违背“保留现有数据”和“覆盖升级直接可用”的目标。

## 目标

1. 原生 Android 首次启动时读取旧 `vibedrop_background_clipboard/config_json`。
2. 从其中导入有 `ip` 和 `pin` 的设备到 Room `devices` 表。
3. 导入后保留设备名、IP、端口、PIN，并作为可连接 desktop 设备显示在发送页。
4. 如果迁移标记已存在但当前原生 devices 表为空，允许再次从旧配置恢复，避免早期原生测试版留下空库状态。
5. 解析失败时返回明确错误，不清除旧配置。

## 非目标

1. 本轮不直接解析 WebView LocalStorage LevelDB；它仍是旧实现细节，后续可另做工具化迁移。
2. 本轮不反推 hostname/serverId；旧后台配置只有 IP/PIN，后续 discovery 会刷新 hostname/serverId。
3. 本轮不改变配对协议和连接协议。

## 设计

新增 `LegacyDeviceImporter`：

1. 使用 `context.getSharedPreferences("vibedrop_background_clipboard", MODE_PRIVATE)` 读取 `config_json`。
2. 纯函数 `extractLegacyBackgroundClipboardDevices(rawJson)` 解析旧配置，跳过缺少 `ip` 或 `pin` 的项。
3. 使用 `DeviceRepository.saveObservedDesktop()` 写入 Room，id 采用 `legacy-background:<old-id>`，没有 old id 时用 `ip:port` 生成稳定 fallback。
4. 迁移标记写入现有 `native_migration`，key 为 `background_clipboard_devices_v1_imported`。
5. 当标记已存在但 `devices` 表为空时仍允许重试恢复。

## 验收

1. 旧后台配置 JSON 可解析出设备名、IP、端口、PIN。
2. 缺少 PIN 或 IP 的旧设备被跳过。
3. Android JVM 单测通过。
4. Android release 构建通过。
5. 真机覆盖安装后 App 启动无崩溃，并能从旧配置恢复已保存 Mac。

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        ConnectInfo, Path as AxumPath, State as AxumState,
    },
    http::{header::HOST, HeaderMap, StatusCode},
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};

use arboard::ImageData;
use base64::{engine::general_purpose::STANDARD as BASE64_STANDARD, Engine as _};
use enigo::{Direction, Enigo, Key, Keyboard, Settings};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::borrow::Cow;
use std::fs::{self, OpenOptions};
use std::io::Cursor;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{IpAddr, SocketAddr};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tauri::{
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::TrayIconBuilder,
    AppHandle, Emitter, Manager, WebviewWindowBuilder, WindowEvent,
};
use tokio::sync::{broadcast, mpsc, oneshot};
use tokio::time::{timeout, Duration};
use tower_http::services::ServeDir;
use tracing::{error, info, warn};
use walkdir::WalkDir;
use zip::write::FileOptions;

#[cfg(target_os = "macos")]
enum MacosResolvedDrop {
    None,
    PromisedFiles {
        expected: usize,
        staged_dir: PathBuf,
    },
    PhotosSelection {
        staged_dir: PathBuf,
        hinted_names: Vec<String>,
        hinted_content_types: Vec<String>,
    },
}

// ---- 数据结构 ----

#[derive(Debug, Deserialize)]
struct ClientMessage {
    action: String,
    #[serde(default)]
    pin: Option<String>,
    #[serde(default)]
    device_id: Option<String>,
    #[serde(default)]
    device_name: Option<String>,
    #[serde(default)]
    can_receive_files: Option<bool>,
    #[serde(default)]
    base_device_id: Option<String>,
    #[serde(default)]
    receives_clipboard: Option<bool>,
    #[serde(default)]
    device_role: Option<String>,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    file_name: Option<String>,
    #[serde(default)]
    mime_type: Option<String>,
    #[serde(default)]
    image_base64: Option<String>,
    #[serde(default)]
    file_base64: Option<String>,
    #[serde(default)]
    transfer_id: Option<String>,
    #[serde(default)]
    chunk_base64: Option<String>,
    #[serde(default)]
    size_bytes: Option<u64>,
    saved_path: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Debug, Serialize)]
struct ServerMessage {
    status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    hostname: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
struct IncomingDesktopTransferMeta {
    file_name: String,
    mime_type: String,
    size_bytes: u64,
    client_id: Option<String>,
    client_name: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
struct HistoryTransferItem {
    kind: String,
    file_name: String,
    mime_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    thumbnail_data_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    file_path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    saved_path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    status: Option<String>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
struct HistoryEntry {
    timestamp: String,
    text: String,
    client_ip: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    client_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    client_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    file_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    image_path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    thumbnail_data_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    file_path: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    direction: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    status: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    item_count: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    save_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    items: Option<Vec<HistoryTransferItem>>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
struct WindowState {
    width: f64,
    height: f64,
}

enum InputAction {
    TypeText(String),
    TypeTextAndEnter(String),
    PressEnter,
    // 外出模式：文字只写入剪贴板（配合 UU 远程等工具的剪贴板同步转发），不模拟键盘输入
    ClipboardText(String),
}

struct InputRequest {
    action: InputAction,
    reply: oneshot::Sender<Result<(), String>>,
}

struct SavedImage {
    file_name: String,
    image_path: String,
    thumbnail_data_url: String,
    clipboard_image: ImageData<'static>,
}

/// 前端可以调用的 Tauri 命令：获取服务信息
#[derive(Debug, Serialize, Clone)]
struct ServiceInfo {
    hostname: String,
    ip: String,
    port: u16,
    pin: String,
    running: bool,
}

#[derive(Debug, Serialize, Clone)]
struct ConnectedClientInfo {
    id: String,
    name: String,
    can_receive_files: bool,
}

#[derive(Debug, Serialize, Clone)]
struct DesktopTransferLaunch {
    transfer_id: String,
    client_id: String,
    client_name: String,
}

#[derive(Debug, Serialize, Clone)]
struct DesktopTransferEvent {
    transfer_id: String,
    client_id: String,
    client_name: String,
    file_name: String,
    status: String,
    progress: f64,
    sent_bytes: u64,
    total_bytes: u64,
    is_archive: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    detail: Option<String>,
}

#[derive(Debug)]
struct ResolvedDropPaths {
    paths: Vec<PathBuf>,
    cleanup_dir: Option<PathBuf>,
}

fn desktop_debug_log_path() -> PathBuf {
    dirs_log_dir().join("debug.log")
}

static DEBUG_LOG_LOCK: Mutex<()> = Mutex::new(());

fn append_debug_log(scope: &str, event: &str, detail: serde_json::Value) {
    let _debug_log_guard = DEBUG_LOG_LOCK
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let log_path = desktop_debug_log_path();
    if let Some(parent) = log_path.parent() {
        let _ = fs::create_dir_all(parent);
    }

    let record = serde_json::json!({
        "ts": chrono::Local::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false),
        "scope": scope,
        "event": event,
        "detail": detail,
    });

    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(&log_path) {
        let _ = writeln!(file, "{}", record);
    }
}

#[cfg(target_os = "macos")]
fn log_macos_drag_event(event: &str, detail: serde_json::Value) {
    append_debug_log("macos-drag", event, detail);
}

#[derive(Debug, Serialize, Clone)]
struct PairRequestInfo {
    request_id: String,
    client_id: String,
    client_name: String,
    code: String,
    requested_at: String,
}

#[derive(Debug, Deserialize)]
struct PairRequestPayload {
    client_id: String,
    client_name: String,
}

#[derive(Debug, Serialize)]
struct DiscoverResponse {
    kind: String,
    server_id: String,
    hostname: String,
    ip: String,
    port: u16,
    protocol_version: u16,
}

#[derive(Debug, Deserialize)]
struct DiscoverProbe {
    kind: String,
    protocol_version: u16,
}

#[derive(Debug, Serialize)]
struct PairRequestAccepted {
    request_id: String,
    code: String,
    hostname: String,
    expires_in_secs: u64,
}

#[derive(Debug, Serialize)]
struct PairRequestStatusResponse {
    status: String,
    request_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    server_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    hostname: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ip: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    port: Option<u16>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pin: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Debug, Deserialize)]
struct LocalShareExtensionRequest {
    paths: Vec<String>,
    #[serde(default)]
    source: Option<String>,
}

#[derive(Debug, Serialize)]
struct LocalShareExtensionAccepted {
    status: String,
    transfer_id: String,
    client_id: String,
    client_name: String,
}

#[derive(Clone)]
struct ConnectedClient {
    session_id: u64,
    id: String,
    base_id: String,
    name: String,
    can_receive_files: bool,
    receives_clipboard: bool,
    sender: mpsc::UnboundedSender<String>,
}

struct PreparedDesktopTransfer {
    source_path: PathBuf,
    file_name: String,
    mime_type: String,
    total_bytes: u64,
    is_archive: bool,
    cleanup_path: Option<PathBuf>,
}

#[derive(Debug, Clone)]
struct OutboundHistorySession {
    session_id: String,
    timestamp: String,
    kind: String,
    text: String,
    item_count: usize,
    save_target: String,
    items: Vec<HistoryTransferItem>,
}

#[derive(Debug, Clone)]
struct OutboundTransferItemContext {
    session_id: String,
    item_index: usize,
    item_count: usize,
}

#[derive(Debug, Clone)]
struct OutboundTransferResult {
    item_index: usize,
    status: String,
    saved_path: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PairRequestStatus {
    Pending,
    Approved,
    Rejected,
}

#[derive(Debug, Clone)]
struct PairRequestEntry {
    request_id: String,
    client_id: String,
    client_name: String,
    code: String,
    requested_at: chrono::DateTime<chrono::Local>,
    status: PairRequestStatus,
}

// ---- Tauri 命令 ----

// macOS 辅助功能权限检测
extern "C" {
    fn AXIsProcessTrusted() -> bool;
}

#[tauri::command]
fn check_accessibility() -> bool {
    unsafe { AXIsProcessTrusted() }
}

#[tauri::command]
fn open_accessibility_settings() {
    let _ = std::process::Command::new("open")
        .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
        .spawn();
}

#[tauri::command]
fn open_history_path(path: String) -> Result<(), String> {
    let path = PathBuf::from(&path);
    if !path.exists() {
        return Err("文件不存在".to_string());
    }

    std::process::Command::new("open")
        .arg(&path)
        .spawn()
        .map_err(|e| format!("无法打开文件: {}", e))?;

    Ok(())
}

#[tauri::command]
fn open_history_paths(paths: Vec<String>) -> Result<(), String> {
    let mut existing_paths = Vec::new();

    for raw in paths {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            continue;
        }
        let path = PathBuf::from(trimmed);
        if path.exists() && !existing_paths.iter().any(|item: &PathBuf| item == &path) {
            existing_paths.push(path);
        }
    }

    if existing_paths.is_empty() {
        return Err("没有可打开的原文件".to_string());
    }

    std::process::Command::new("open")
        .args(&existing_paths)
        .spawn()
        .map_err(|e| format!("无法打开文件: {}", e))?;

    Ok(())
}

#[tauri::command]
fn get_service_info(state: tauri::State<'_, AppState>) -> ServiceInfo {
    ServiceInfo {
        hostname: state.hostname.clone(),
        ip: state.ip.clone(),
        port: state.port,
        pin: state.pin.clone(),
        running: true,
    }
}

#[tauri::command]
fn load_history_entries() -> Vec<HistoryEntry> {
    let mut entries = Vec::new();
    let mut seen = std::collections::HashSet::new();

    for path in history_log_paths() {
        let Ok(file) = std::fs::File::open(&path) else {
            continue;
        };

        for line in BufReader::new(file).lines().map_while(Result::ok) {
            if line.trim().is_empty() || !seen.insert(line.clone()) {
                continue;
            }

            if let Ok(entry) = serde_json::from_str::<HistoryEntry>(&line) {
                entries.push(entry);
            }
        }
    }

    entries.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));
    entries
}

#[tauri::command]
fn list_connected_clients(ws_state: tauri::State<'_, Arc<WsState>>) -> Vec<ConnectedClientInfo> {
    connected_clients_snapshot(ws_state.inner())
}

#[tauri::command]
async fn send_dropped_paths(
    client_id: String,
    paths: Vec<String>,
    ws_state: tauri::State<'_, Arc<WsState>>,
    app: AppHandle,
) -> Result<DesktopTransferLaunch, String> {
    append_debug_log(
        "desktop-transfer",
        "send_dropped_paths:start",
        serde_json::json!({
            "client_id": client_id,
            "raw_paths": paths,
        }),
    );
    let client = {
        let clients = ws_state
            .clients
            .lock()
            .map_err(|_| "连接状态不可用".to_string())?;
        let client = clients
            .get(&client_id)
            .cloned()
            .ok_or_else(|| "目标手机已断开，请重新选择".to_string())?;
        if !client.can_receive_files {
            return Err("该设备当前不支持接收文件".to_string());
        }
        client
    };

    let transfer_seq = ws_state.transfer_counter.fetch_add(1, Ordering::Relaxed);
    let transfer_id = format!(
        "desktop-{}-{}",
        chrono::Local::now().format("%Y%m%d%H%M%S"),
        transfer_seq
    );
    let resolved_drop = resolve_desktop_drop_paths(&app, &transfer_id, paths)?;
    let client_name = client.name.clone();

    let ws_state_clone = ws_state.inner().clone();
    let app_handle = app.clone();
    let transfer_id_for_task = transfer_id.clone();
    tauri::async_runtime::spawn(async move {
        run_desktop_outbound_transfer(
            ws_state_clone,
            app_handle,
            client,
            transfer_id_for_task,
            resolved_drop,
        )
        .await;
    });

    Ok(DesktopTransferLaunch {
        transfer_id,
        client_id: client_id.clone(),
        client_name,
    })
}

fn resolve_desktop_drop_paths(
    app: &AppHandle,
    transfer_id: &str,
    raw_paths: Vec<String>,
) -> Result<ResolvedDropPaths, String> {
    let raw_paths_for_log = raw_paths.clone();
    let fallback_paths: Vec<PathBuf> = raw_paths
        .into_iter()
        .map(PathBuf::from)
        .filter(|path| path.exists())
        .collect();

    #[cfg(target_os = "macos")]
    if let Some(resolved) = resolve_macos_drag_promised_files(app, transfer_id)? {
        if !resolved.paths.is_empty() {
            log_macos_drag_event(
                "resolve-drop:using-promised-files",
                serde_json::json!({
                    "transfer_id": transfer_id,
                    "resolved_paths": resolved.paths.iter().map(|path| path.display().to_string()).collect::<Vec<_>>(),
                    "cleanup_dir": resolved.cleanup_dir.as_ref().map(|path| path.display().to_string()),
                }),
            );
            return Ok(resolved);
        }
    }

    if fallback_paths.is_empty() {
        append_debug_log(
            "desktop-transfer",
            "resolve-drop:no-usable-paths",
            serde_json::json!({
                "transfer_id": transfer_id,
                "raw_paths": raw_paths_for_log,
            }),
        );
        return Err("拖入的路径不存在或不可访问".to_string());
    }

    append_debug_log(
        "desktop-transfer",
        "resolve-drop:using-fallback-paths",
        serde_json::json!({
            "transfer_id": transfer_id,
            "paths": fallback_paths.iter().map(|path| path.display().to_string()).collect::<Vec<_>>(),
        }),
    );

    Ok(ResolvedDropPaths {
        paths: fallback_paths,
        cleanup_dir: None,
    })
}

#[cfg(target_os = "macos")]
fn resolve_macos_drag_promised_files(
    app: &AppHandle,
    transfer_id: &str,
) -> Result<Option<ResolvedDropPaths>, String> {
    let Some(window) = app.get_webview_window("main") else {
        return Ok(None);
    };

    let transfer_id = transfer_id.to_string();
    let transfer_id_for_webview = transfer_id.clone();
    let (control_tx, control_rx) = std::sync::mpsc::sync_channel(1);
    let (file_tx, file_rx) = std::sync::mpsc::channel::<Result<PathBuf, String>>();
    window
        .with_webview(move |_| {
            let result = unsafe {
                resolve_macos_drag_promised_files_inner(&transfer_id_for_webview, file_tx.clone())
            };
            let _ = control_tx.send(result);
        })
        .map_err(|error| format!("无法访问 macOS 拖拽内容: {}", error))?;

    let resolution = control_rx
        .recv_timeout(std::time::Duration::from_secs(3))
        .map_err(|_| "读取 macOS 拖拽内容超时，请重新拖一次".to_string())??;

    match resolution {
        MacosResolvedDrop::None => {
            log_macos_drag_event(
                "resolve-promised:none",
                serde_json::json!({
                    "transfer_id": transfer_id,
                }),
            );
            Ok(None)
        }
        MacosResolvedDrop::PromisedFiles {
            expected,
            staged_dir,
        } => {
            log_macos_drag_event(
                "resolve-promised:scheduled",
                serde_json::json!({
                    "transfer_id": transfer_id,
                    "expected_items": expected,
                    "staged_dir": staged_dir.display().to_string(),
                }),
            );

            let mut staged_paths = Vec::with_capacity(expected);
            for _ in 0..expected {
                match file_rx.recv_timeout(std::time::Duration::from_secs(20)) {
                    Ok(Ok(path)) => {
                        log_macos_drag_event(
                            "resolve-promised:file-ready",
                            serde_json::json!({
                                "transfer_id": transfer_id,
                                "path": path.display().to_string(),
                            }),
                        );
                        staged_paths.push(path);
                    }
                    Ok(Err(error)) => {
                        let _ = fs::remove_dir_all(&staged_dir);
                        log_macos_drag_event(
                            "resolve-promised:error",
                            serde_json::json!({
                                "transfer_id": transfer_id,
                                "error": error,
                            }),
                        );
                        return Err(format!("无法读取 Photos 拖拽内容: {}", error));
                    }
                    Err(_) => {
                        let _ = fs::remove_dir_all(&staged_dir);
                        log_macos_drag_event(
                            "resolve-promised:timeout",
                            serde_json::json!({
                                "transfer_id": transfer_id,
                                "staged_dir": staged_dir.display().to_string(),
                            }),
                        );
                        return Err("等待 Photos 拖拽内容超时，请重新拖一次".to_string());
                    }
                }
            }

            staged_paths.retain(|path| path.exists());
            if staged_paths.is_empty() {
                let _ = fs::remove_dir_all(&staged_dir);
                log_macos_drag_event(
                    "resolve-promised:empty",
                    serde_json::json!({
                        "transfer_id": transfer_id,
                        "staged_dir": staged_dir.display().to_string(),
                    }),
                );
                return Err("Photos 拖拽没有生成可发送文件".to_string());
            }

            info!(
                "resolved {} macOS promised drop item(s) into {}",
                staged_paths.len(),
                staged_dir.display()
            );

            Ok(Some(ResolvedDropPaths {
                paths: staged_paths,
                cleanup_dir: Some(staged_dir),
            }))
        }
        MacosResolvedDrop::PhotosSelection {
            staged_dir,
            hinted_names,
            hinted_content_types,
        } => {
            let staged_paths = export_photos_selection_to_stage_dir(
                &transfer_id,
                &staged_dir,
                &hinted_names,
                &hinted_content_types,
            )?;
            Ok(Some(ResolvedDropPaths {
                paths: staged_paths,
                cleanup_dir: Some(staged_dir),
            }))
        }
    }
}

#[cfg(target_os = "macos")]
unsafe fn resolve_macos_drag_promised_files_inner(
    transfer_id: &str,
    file_tx: std::sync::mpsc::Sender<Result<PathBuf, String>>,
) -> Result<MacosResolvedDrop, String> {
    use block2::RcBlock;
    use objc2::ClassType;
    use objc2_app_kit::{
        NSFilePromiseReceiver, NSPasteboard, NSPasteboardItem, NSPasteboardNameDrag,
    };
    use objc2_foundation::{NSArray, NSDictionary, NSError, NSOperationQueue, NSString, NSURL};
    use std::sync::mpsc::Sender;

    unsafe fn collect_item_strings(item: &NSPasteboardItem, type_name: &str) -> Option<String> {
        let data_type = NSString::from_str(type_name);
        item.stringForType(&data_type)
            .map(|value| value.to_string())
    }

    unsafe fn collect_photos_drag_hints(
        pasteboard: &NSPasteboard,
    ) -> (bool, Vec<String>, Vec<String>, serde_json::Value) {
        let types = pasteboard
            .types()
            .map(|types| {
                types
                    .iter()
                    .map(|item| item.to_string())
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();

        let mut contains_photos_asset_reference = false;
        let mut hinted_names = Vec::new();
        let mut hinted_content_types = Vec::new();

        let items = pasteboard
            .pasteboardItems()
            .map(|items| {
                items
                    .iter()
                    .map(|item| {
                        let item_types = item
                            .types()
                            .iter()
                            .map(|item_type| item_type.to_string())
                            .collect::<Vec<_>>();
                        if item_types
                            .iter()
                            .any(|item_type| item_type == "com.apple.photos.object-reference.asset")
                        {
                            contains_photos_asset_reference = true;
                        }
                        if let Some(value) = collect_item_strings(
                            &item,
                            "com.apple.pasteboard.promised-suggested-file-name",
                        ) {
                            hinted_names.push(value);
                        } else if let Some(value) =
                            collect_item_strings(&item, "com.apple.pasteboard.promised-file-name")
                        {
                            hinted_names.push(value);
                        }
                        if let Some(value) = collect_item_strings(
                            &item,
                            "com.apple.pasteboard.promised-file-content-type",
                        ) {
                            hinted_content_types.push(value);
                        }

                        serde_json::json!({
                            "types": item_types,
                            "values": {
                                "promised_file_name": collect_item_strings(&item, "com.apple.pasteboard.promised-file-name"),
                                "promised_suggested_file_name": collect_item_strings(&item, "com.apple.pasteboard.promised-suggested-file-name"),
                                "promised_file_content_type": collect_item_strings(&item, "com.apple.pasteboard.promised-file-content-type"),
                                "promised_file_url": collect_item_strings(&item, "com.apple.pasteboard.promised-file-url"),
                                "public_file_url": collect_item_strings(&item, "public.file-url"),
                                "photos_asset_reference": collect_item_strings(&item, "com.apple.photos.object-reference.asset"),
                            },
                        })
                    })
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();

        (
            contains_photos_asset_reference,
            hinted_names,
            hinted_content_types,
            serde_json::json!({
            "pasteboard_types": types,
            "item_types": items,
            }),
        )
    }

    unsafe fn schedule_promised_receivers(
        transfer_id: &str,
        file_tx: Sender<Result<PathBuf, String>>,
    ) -> Result<MacosResolvedDrop, String> {
        let pasteboard = NSPasteboard::pasteboardWithName(NSPasteboardNameDrag);
        let (contains_photos_asset_reference, hinted_names, hinted_content_types, snapshot) =
            collect_photos_drag_hints(&pasteboard);
        log_macos_drag_event(
            "pasteboard:snapshot",
            serde_json::json!({
                "transfer_id": transfer_id,
                "snapshot": snapshot,
            }),
        );

        if contains_photos_asset_reference {
            let staged_dir =
                outbound_transfer_temp_dir().join(format!("photos-export-{transfer_id}"));
            fs::create_dir_all(&staged_dir)
                .map_err(|error| format!("无法创建 Photos 导出暂存目录: {}", error))?;
            log_macos_drag_event(
                "photos-selection:detected",
                serde_json::json!({
                    "transfer_id": transfer_id,
                    "staged_dir": staged_dir.display().to_string(),
                    "hinted_names": hinted_names,
                    "hinted_content_types": hinted_content_types,
                }),
            );
            return Ok(MacosResolvedDrop::PhotosSelection {
                staged_dir,
                hinted_names,
                hinted_content_types,
            });
        }

        let classes = NSArray::from_slice(&[NSFilePromiseReceiver::class()]);
        log_macos_drag_event(
            "pasteboard:before-read-file-promises",
            serde_json::json!({
                "transfer_id": transfer_id,
            }),
        );
        let Some(objects) = pasteboard.readObjectsForClasses_options(&classes, None) else {
            log_macos_drag_event(
                "pasteboard:no-file-promises",
                serde_json::json!({
                    "transfer_id": transfer_id,
                }),
            );
            return Ok(MacosResolvedDrop::None);
        };
        log_macos_drag_event(
            "pasteboard:after-read-file-promises",
            serde_json::json!({
                "transfer_id": transfer_id,
            }),
        );
        let receivers: &NSArray<NSFilePromiseReceiver> = objects.cast_unchecked();
        let expected = receivers.count() as usize;
        if expected == 0 {
            log_macos_drag_event(
                "pasteboard:zero-receivers",
                serde_json::json!({
                    "transfer_id": transfer_id,
                }),
            );
            return Ok(MacosResolvedDrop::None);
        }

        let staged_dir = outbound_transfer_temp_dir().join(format!("promised-drop-{transfer_id}"));
        fs::create_dir_all(&staged_dir)
            .map_err(|error| format!("无法创建拖拽暂存目录: {}", error))?;
        let staged_dir_str = staged_dir.to_string_lossy().to_string();
        let destination_url = NSURL::fileURLWithPath(&NSString::from_str(&staged_dir_str));
        let options = NSDictionary::new();
        let queue = NSOperationQueue::new();
        log_macos_drag_event(
            "pasteboard:receivers-found",
            serde_json::json!({
                "transfer_id": transfer_id,
                "receiver_count": expected,
                "staged_dir": staged_dir.display().to_string(),
                "receivers": receivers.iter().map(|receiver| {
                    serde_json::json!({
                        "file_names": receiver.fileNames().iter().map(|item| item.to_string()).collect::<Vec<_>>(),
                        "file_types": receiver.fileTypes().iter().map(|item| item.to_string()).collect::<Vec<_>>(),
                    })
                }).collect::<Vec<_>>(),
            }),
        );

        for index in 0..expected {
            let receiver = receivers.objectAtIndex(index);
            let tx = file_tx.clone();
            let reader = RcBlock::new(
                move |url_ptr: core::ptr::NonNull<NSURL>, error_ptr: *mut NSError| {
                    if !error_ptr.is_null() {
                        let error = unsafe { &*error_ptr };
                        let _ = tx.send(Err(format!(
                            "{} (domain={}, code={})",
                            error.localizedDescription(),
                            error.domain(),
                            error.code()
                        )));
                        return;
                    }

                    let url = unsafe { url_ptr.as_ref() };
                    let Some(path) = url.path() else {
                        let _ = tx.send(Err("拖拽内容已生成，但没有可读路径".to_string()));
                        return;
                    };

                    let _ = tx.send(Ok(PathBuf::from(path.to_string())));
                },
            );

            receiver.receivePromisedFilesAtDestination_options_operationQueue_reader(
                &destination_url,
                &options,
                &queue,
                &reader,
            );
        }

        Ok(MacosResolvedDrop::PromisedFiles {
            expected,
            staged_dir,
        })
    }
    schedule_promised_receivers(transfer_id, file_tx)
}

#[cfg(target_os = "macos")]
fn export_photos_selection_to_stage_dir(
    transfer_id: &str,
    staged_dir: &Path,
    hinted_names: &[String],
    hinted_content_types: &[String],
) -> Result<Vec<PathBuf>, String> {
    let staged_dir_str = staged_dir
        .to_str()
        .ok_or_else(|| "Photos 导出目录路径不可读".to_string())?;
    let staged_dir_escaped = staged_dir_str.replace('\\', "\\\\").replace('"', "\\\"");
    let script = format!(
        r#"
tell application "Photos"
    set selectedItems to selection
    if (count of selectedItems) is 0 then error "Photos 当前没有选中任何项目"
    export selectedItems to POSIX file "{}" with using originals
end tell
"#,
        staged_dir_escaped
    );

    log_macos_drag_event(
        "photos-selection:export-start",
        serde_json::json!({
            "transfer_id": transfer_id,
            "staged_dir": staged_dir.display().to_string(),
            "hinted_names": hinted_names,
            "hinted_content_types": hinted_content_types,
        }),
    );

    let output = std::process::Command::new("/usr/bin/osascript")
        .arg("-e")
        .arg(script)
        .output()
        .map_err(|error| format!("无法启动 Photos 导出: {}", error))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
        log_macos_drag_event(
            "photos-selection:export-error",
            serde_json::json!({
                "transfer_id": transfer_id,
                "status": output.status.code(),
                "stderr": stderr,
                "stdout": stdout,
            }),
        );
        if stderr.contains("Not authorized to send Apple events") {
            return Err(
                "VibeDrop 还没有获得控制 Photos 的权限，请在“系统设置 -> 隐私与安全性 -> 自动化”里允许后再试一次"
                    .to_string(),
            );
        }
        return Err(if stderr.is_empty() {
            "无法从 Photos 导出当前拖拽项目".to_string()
        } else {
            format!("无法从 Photos 导出当前拖拽项目: {}", stderr)
        });
    }

    let mut exported_paths = WalkDir::new(staged_dir)
        .into_iter()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_file())
        .map(|entry| entry.into_path())
        .collect::<Vec<_>>();

    exported_paths.sort();
    if !hinted_names.is_empty() {
        let hinted_names_lower = hinted_names
            .iter()
            .map(|item| item.to_lowercase())
            .collect::<Vec<_>>();
        let matching = exported_paths
            .iter()
            .filter(|path| {
                path.file_name()
                    .and_then(|name| name.to_str())
                    .map(|name| hinted_names_lower.contains(&name.to_lowercase()))
                    .unwrap_or(false)
            })
            .cloned()
            .collect::<Vec<_>>();
        if !matching.is_empty() {
            exported_paths = matching;
        }
    }

    if exported_paths.is_empty() {
        log_macos_drag_event(
            "photos-selection:export-empty",
            serde_json::json!({
                "transfer_id": transfer_id,
                "staged_dir": staged_dir.display().to_string(),
            }),
        );
        return Err("Photos 没有导出任何可发送文件".to_string());
    }

    log_macos_drag_event(
        "photos-selection:export-finished",
        serde_json::json!({
            "transfer_id": transfer_id,
            "paths": exported_paths.iter().map(|path| path.display().to_string()).collect::<Vec<_>>(),
        }),
    );

    Ok(exported_paths)
}

#[cfg(target_os = "macos")]
fn configure_macos_native_drag_support(window: &tauri::WebviewWindow) -> Result<(), String> {
    let window_label = window.label().to_string();
    window
        .with_webview(move |webview| unsafe {
            use objc2_app_kit::{NSFilePromiseReceiver, NSPasteboardTypeFileURL, NSView, NSWindow};
            use objc2_foundation::NSMutableCopying;

            let ns_window: &NSWindow = &*webview.ns_window().cast();
            let ns_view: &NSView = &*webview.inner().cast();

            let drag_types = NSFilePromiseReceiver::readableDraggedTypes();
            let mutable_types = drag_types.mutableCopy();
            mutable_types.addObject(NSPasteboardTypeFileURL);

            ns_window.registerForDraggedTypes(&mutable_types);
            ns_view.registerForDraggedTypes(&mutable_types);

            log_macos_drag_event(
                "setup:register-drag-types",
                serde_json::json!({
                    "window_label": window_label,
                    "types": mutable_types.iter().map(|item| item.to_string()).collect::<Vec<_>>(),
                }),
            );
        })
        .map_err(|error| format!("无法配置 macOS 原生拖拽支持: {}", error))
}

#[tauri::command]
fn list_pair_requests(ws_state: tauri::State<'_, Arc<WsState>>) -> Vec<PairRequestInfo> {
    pair_requests_snapshot(ws_state.inner())
}

#[tauri::command]
fn approve_pair_request(
    request_id: String,
    ws_state: tauri::State<'_, Arc<WsState>>,
) -> Result<(), String> {
    set_pair_request_status(ws_state.inner(), &request_id, PairRequestStatus::Approved)
}

#[tauri::command]
fn reject_pair_request(
    request_id: String,
    ws_state: tauri::State<'_, Arc<WsState>>,
) -> Result<(), String> {
    set_pair_request_status(ws_state.inner(), &request_id, PairRequestStatus::Rejected)
}

#[tauri::command]
fn set_preferred_share_client(
    client_id: String,
    ws_state: tauri::State<'_, Arc<WsState>>,
) -> Result<(), String> {
    let normalized = client_id.trim().to_string();
    let mut preferred = ws_state
        .preferred_share_client
        .lock()
        .map_err(|_| "无法更新默认分享目标".to_string())?;

    if normalized.is_empty() {
        *preferred = None;
    } else {
        *preferred = Some(normalized);
    }

    Ok(())
}

async fn discover_handler(
    AxumState(state): AxumState<ServerState>,
    headers: HeaderMap,
) -> Json<DiscoverResponse> {
    let advertised_ip = resolve_advertised_request_ip(&state, &headers);
    Json(build_discover_response(&state, advertised_ip))
}

async fn run_udp_discovery_responder(state: ServerState) -> Result<(), String> {
    let bind_addr = SocketAddr::from(([0, 0, 0, 0], state.app.port));
    let socket = tokio::net::UdpSocket::bind(bind_addr)
        .await
        .map_err(|e| format!("无法绑定 UDP 发现端口 {}: {}", state.app.port, e))?;

    info!("UDP 发现应答已启动在 {}", bind_addr);
    let mut buffer = [0u8; 2048];

    loop {
        let (length, source_addr) = match socket.recv_from(&mut buffer).await {
            Ok(value) => value,
            Err(error) => {
                warn!("接收 UDP 发现报文失败: {}", error);
                continue;
            }
        };

        let probe = match serde_json::from_slice::<DiscoverProbe>(&buffer[..length]) {
            Ok(value) => value,
            Err(_) => continue,
        };

        if probe.kind != "discover_probe" || probe.protocol_version > DISCOVERY_PROTOCOL_VERSION {
            continue;
        }

        let payload = match serde_json::to_vec(&build_discover_response(
            &state,
            current_advertised_ip(&state),
        )) {
            Ok(value) => value,
            Err(error) => {
                warn!("编码 UDP 发现响应失败: {}", error);
                continue;
            }
        };

        let target_addr = SocketAddr::new(
            match source_addr.ip() {
                IpAddr::V4(addr) => IpAddr::V4(addr),
                IpAddr::V6(addr) => IpAddr::V6(addr),
            },
            source_addr.port(),
        );

        if let Err(error) = socket.send_to(&payload, target_addr).await {
            warn!("回复 UDP 发现响应失败: {}", error);
        }
    }
}

async fn request_pairing_handler(
    AxumState(state): AxumState<ServerState>,
    Json(payload): Json<PairRequestPayload>,
) -> Result<Json<PairRequestAccepted>, (StatusCode, Json<serde_json::Value>)> {
    let client_id = payload.client_id.trim();
    let client_name = payload.client_name.trim();
    if client_id.is_empty() || client_name.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "缺少客户端标识" })),
        ));
    }

    let accepted = {
        let mut requests = state.ws.pair_requests.lock().map_err(|_| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({ "error": "配对队列不可用" })),
            )
        })?;

        prune_pair_requests_locked(&mut requests);

        if let Some(existing) = requests.values().find(|entry| {
            entry.client_id == client_id && entry.status == PairRequestStatus::Pending
        }) {
            PairRequestAccepted {
                request_id: existing.request_id.clone(),
                code: existing.code.clone(),
                hostname: state.app.hostname.clone(),
                expires_in_secs: PAIR_REQUEST_TTL_SECS as u64,
            }
        } else {
            let sequence = state.ws.pair_counter.fetch_add(1, Ordering::Relaxed);
            let seed = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|duration| duration.as_millis() as u64)
                .unwrap_or(sequence);
            let request_id = format!("pair-{}-{}", sequence, seed % 100000);
            let code = format!("{:06}", (seed ^ (sequence * 7919)) % 1_000_000);
            let entry = PairRequestEntry {
                request_id: request_id.clone(),
                client_id: client_id.to_string(),
                client_name: client_name.to_string(),
                code: code.clone(),
                requested_at: chrono::Local::now(),
                status: PairRequestStatus::Pending,
            };
            requests.insert(request_id.clone(), entry);

            PairRequestAccepted {
                request_id,
                code,
                hostname: state.app.hostname.clone(),
                expires_in_secs: PAIR_REQUEST_TTL_SECS as u64,
            }
        }
    };

    broadcast_pair_requests(&state.ws);
    focus_main_window(&state.ws);

    Ok(Json(accepted))
}

async fn pair_status_handler(
    AxumState(state): AxumState<ServerState>,
    headers: HeaderMap,
    AxumPath(request_id): AxumPath<String>,
) -> Json<PairRequestStatusResponse> {
    let status = pair_request_status_snapshot(&state.ws, &request_id);
    let advertised_ip = resolve_advertised_request_ip(&state, &headers);
    Json(match status {
        Some(PairRequestStatus::Pending) => PairRequestStatusResponse {
            status: "pending".to_string(),
            request_id,
            server_id: None,
            hostname: None,
            ip: None,
            port: None,
            pin: None,
            error: None,
        },
        Some(PairRequestStatus::Approved) => PairRequestStatusResponse {
            status: "approved".to_string(),
            request_id,
            server_id: Some(state.app.server_id),
            hostname: Some(state.app.hostname),
            ip: Some(advertised_ip),
            port: Some(state.app.port),
            pin: Some(state.app.pin),
            error: None,
        },
        Some(PairRequestStatus::Rejected) => PairRequestStatusResponse {
            status: "rejected".to_string(),
            request_id,
            server_id: None,
            hostname: None,
            ip: None,
            port: None,
            pin: None,
            error: Some("桌面端拒绝了配对请求".to_string()),
        },
        None => PairRequestStatusResponse {
            status: "expired".to_string(),
            request_id,
            server_id: None,
            hostname: None,
            ip: None,
            port: None,
            pin: None,
            error: Some("配对请求已过期，请重新发起".to_string()),
        },
    })
}

async fn local_share_extension_handler(
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    AxumState(state): AxumState<ServerState>,
    Json(payload): Json<LocalShareExtensionRequest>,
) -> Result<Json<LocalShareExtensionAccepted>, (StatusCode, Json<serde_json::Value>)> {
    if !remote_addr.ip().is_loopback() {
        return Err((
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({ "error": "仅允许本机共享扩展调用" })),
        ));
    }

    let path_bufs = payload
        .paths
        .into_iter()
        .map(PathBuf::from)
        .filter(|path| path.exists())
        .collect::<Vec<_>>();
    if path_bufs.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({ "error": "没有可发送的文件或文件夹" })),
        ));
    }

    let Some(client) = resolve_finder_share_target_client(&state.ws) else {
        return Err((
            StatusCode::CONFLICT,
            Json(serde_json::json!({ "error": "当前没有支持接收文件的手机在线设备" })),
        ));
    };

    let app_handle = state
        .ws
        .app_handle
        .lock()
        .ok()
        .and_then(|guard| guard.clone())
        .ok_or_else(|| {
            (
                StatusCode::SERVICE_UNAVAILABLE,
                Json(serde_json::json!({ "error": "桌面端窗口尚未就绪，请先打开 VibeDrop" })),
            )
        })?;

    let transfer_id = format!(
        "share-extension-{}",
        state.ws.transfer_counter.fetch_add(1, Ordering::Relaxed)
    );
    let file_name = summarize_paths_for_ui(&path_bufs);
    let detail = match payload.source.as_deref() {
        Some("finder-share-extension") => "来自 Finder 共享".to_string(),
        Some(source) if !source.trim().is_empty() => format!("来自 {}", source),
        _ => "来自 Finder 共享".to_string(),
    };

    emit_desktop_transfer_event(
        &app_handle,
        &DesktopTransferEvent {
            transfer_id: transfer_id.clone(),
            client_id: client.id.clone(),
            client_name: client.name.clone(),
            file_name,
            status: "preparing".to_string(),
            progress: 0.0,
            sent_bytes: 0,
            total_bytes: 0,
            is_archive: path_bufs.len() > 1 || path_bufs.iter().any(|path| path.is_dir()),
            detail: Some(detail),
        },
    );

    let transfer_id_for_prepare = transfer_id.clone();
    let path_bufs_for_prepare = path_bufs.clone();
    let prepared = match tauri::async_runtime::spawn_blocking(move || {
        prepare_share_extension_transfer(&path_bufs_for_prepare, &transfer_id_for_prepare)
    })
    .await
    {
        Ok(Ok(prepared)) => prepared,
        Ok(Err(error)) => {
            emit_desktop_transfer_event(
                &app_handle,
                &DesktopTransferEvent {
                    transfer_id: transfer_id.clone(),
                    client_id: client.id.clone(),
                    client_name: client.name.clone(),
                    file_name: summarize_paths_for_ui(&path_bufs),
                    status: "error".to_string(),
                    progress: 0.0,
                    sent_bytes: 0,
                    total_bytes: 0,
                    is_archive: path_bufs.len() > 1 || path_bufs.iter().any(|path| path.is_dir()),
                    detail: Some(error.clone()),
                },
            );
            return Err((
                StatusCode::BAD_REQUEST,
                Json(serde_json::json!({ "error": error })),
            ));
        }
        Err(join_error) => {
            let message = format!("准备共享文件失败: {}", join_error);
            emit_desktop_transfer_event(
                &app_handle,
                &DesktopTransferEvent {
                    transfer_id: transfer_id.clone(),
                    client_id: client.id.clone(),
                    client_name: client.name.clone(),
                    file_name: summarize_paths_for_ui(&path_bufs),
                    status: "error".to_string(),
                    progress: 0.0,
                    sent_bytes: 0,
                    total_bytes: 0,
                    is_archive: path_bufs.len() > 1 || path_bufs.iter().any(|path| path.is_dir()),
                    detail: Some(message.clone()),
                },
            );
            return Err((
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({ "error": message })),
            ));
        }
    };

    tauri::async_runtime::spawn(run_prepared_desktop_outbound_transfer(
        Arc::clone(&state.ws),
        app_handle,
        client.clone(),
        transfer_id.clone(),
        prepared,
        None,
    ));
    focus_main_window(&state.ws);

    Ok(Json(LocalShareExtensionAccepted {
        status: "accepted".to_string(),
        transfer_id,
        client_id: client.id,
        client_name: client.name,
    }))
}

// ---- 应用状态 ----

#[derive(Clone)]
struct AppState {
    server_id: String,
    pin: String,
    hostname: String,
    ip: String,
    port: u16,
}

#[derive(Clone)]
struct ServerState {
    app: AppState,
    ws: Arc<WsState>,
}

fn parse_request_host_ip(host: &str) -> Option<String> {
    let trimmed = host.trim();
    if trimmed.is_empty() {
        return None;
    }

    let candidate = if let Some(stripped) = trimmed.strip_prefix('[') {
        stripped.split(']').next()?.trim()
    } else if trimmed.matches(':').count() == 1 {
        trimmed.split(':').next()?.trim()
    } else {
        trimmed
    };

    candidate
        .parse::<std::net::IpAddr>()
        .ok()
        .map(|ip| ip.to_string())
}

fn resolve_advertised_request_ip(state: &ServerState, headers: &HeaderMap) -> String {
    headers
        .get(HOST)
        .and_then(|value| value.to_str().ok())
        .and_then(parse_request_host_ip)
        .unwrap_or_else(|| current_advertised_ip(state))
}

fn current_advertised_ip(state: &ServerState) -> String {
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| state.app.ip.clone())
}

fn build_discover_response(state: &ServerState, advertised_ip: String) -> DiscoverResponse {
    DiscoverResponse {
        kind: "desktop".to_string(),
        server_id: state.app.server_id.clone(),
        hostname: state.app.hostname.clone(),
        ip: advertised_ip,
        port: state.app.port,
        protocol_version: DISCOVERY_PROTOCOL_VERSION,
    }
}

const MIN_WINDOW_WIDTH: f64 = 320.0;
const MIN_WINDOW_HEIGHT: f64 = 420.0;
// 超过该长度的文字改用「剪贴板 + Cmd+V」注入：
// 逐字模拟打字时，高速事件流会被目标 App（网页/终端输入框）的输入法或
// 事件队列乱序消费，导致个别字符跳到整段开头/结尾；粘贴是原子操作，不会乱序。
const TYPE_TEXT_PASTE_THRESHOLD_CHARS: usize = 30;
const TYPE_TEXT_CHUNK_CHARS: usize = 8;
const TYPE_TEXT_CHUNK_DELAY_MS: u64 = 12;
const PASTE_SETTLE_BEFORE_MS: u64 = 150;
const PASTE_SETTLE_AFTER_MS: u64 = 400;
const CLIPBOARD_SUPPRESS_WINDOW_SECS: u64 = 10;

type ClipboardSuppressList = Arc<Mutex<Vec<(String, std::time::Instant)>>>;

fn type_text_chunked(enigo: &mut Enigo, text: &str) -> Result<(), String> {
    let chars: Vec<char> = text.chars().collect();
    for chunk in chars.chunks(TYPE_TEXT_CHUNK_CHARS) {
        let piece: String = chunk.iter().collect();
        enigo.text(&piece).map_err(|e| format!("{:?}", e))?;
        std::thread::sleep(std::time::Duration::from_millis(TYPE_TEXT_CHUNK_DELAY_MS));
    }
    Ok(())
}

fn suppress_clipboard_broadcast(suppress: &ClipboardSuppressList, text: &str) {
    let mut guard = suppress.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.retain(|(_, at)| at.elapsed().as_secs() < CLIPBOARD_SUPPRESS_WINDOW_SECS);
    guard.push((text.to_string(), std::time::Instant::now()));
}

fn is_clipboard_broadcast_suppressed(suppress: &ClipboardSuppressList, text: &str) -> bool {
    let mut guard = suppress.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    guard.retain(|(_, at)| at.elapsed().as_secs() < CLIPBOARD_SUPPRESS_WINDOW_SECS);
    guard.iter().any(|(item, _)| item == text)
}

// 直接构造自带 Command 标志位的 V 键事件来触发粘贴。
// 不用「按下 Meta → 点 V → 松开 Meta」三段式：分开发送时目标 App 可能在
// 修饰键状态登记前就处理了 V 键，表现为偶发粘贴失败或输入一个裸 "v"。
// 修饰键内嵌在按键事件里则不存在这个时序竞态。
fn send_cmd_v_event() -> Result<(), String> {
    use core_graphics::event::{CGEvent, CGEventFlags, CGEventTapLocation};
    use core_graphics::event_source::{CGEventSource, CGEventSourceStateID};

    const KVK_ANSI_V: u16 = 9;
    let source = CGEventSource::new(CGEventSourceStateID::HIDSystemState)
        .map_err(|_| "无法创建键盘事件源".to_string())?;
    let key_down = CGEvent::new_keyboard_event(source.clone(), KVK_ANSI_V, true)
        .map_err(|_| "无法创建粘贴按键事件".to_string())?;
    key_down.set_flags(CGEventFlags::CGEventFlagCommand);
    key_down.post(CGEventTapLocation::HID);
    std::thread::sleep(std::time::Duration::from_millis(30));
    let key_up = CGEvent::new_keyboard_event(source, KVK_ANSI_V, false)
        .map_err(|_| "无法创建粘贴按键抬起事件".to_string())?;
    key_up.set_flags(CGEventFlags::CGEventFlagCommand);
    key_up.post(CGEventTapLocation::HID);
    Ok(())
}

fn paste_inject_text(text: &str, suppress: &ClipboardSuppressList) -> Result<(), String> {
    let mut clipboard =
        arboard::Clipboard::new().map_err(|e| format!("无法访问剪贴板: {}", e))?;
    let previous = clipboard.get_text().ok().filter(|t| !t.is_empty());

    suppress_clipboard_broadcast(suppress, text);
    if let Some(prev) = &previous {
        suppress_clipboard_broadcast(suppress, prev);
    }

    clipboard
        .set_text(text.to_string())
        .map_err(|e| format!("无法写入剪贴板: {}", e))?;
    std::thread::sleep(std::time::Duration::from_millis(PASTE_SETTLE_BEFORE_MS));

    send_cmd_v_event()?;

    std::thread::sleep(std::time::Duration::from_millis(PASTE_SETTLE_AFTER_MS));
    if let Some(prev) = previous {
        let _ = clipboard.set_text(prev);
    }
    Ok(())
}

fn inject_text(
    enigo: &mut Enigo,
    text: &str,
    suppress: &ClipboardSuppressList,
) -> Result<(), String> {
    if text.chars().count() <= TYPE_TEXT_PASTE_THRESHOLD_CHARS {
        type_text_chunked(enigo, text)
    } else {
        paste_inject_text(text, suppress)
    }
}
const DESKTOP_TO_MOBILE_CHUNK_BYTES: usize = 192 * 1024;
const DESKTOP_INBOX_DIR_NAME: &str = "VibeDrop 收件箱";
const DESKTOP_TO_MOBILE_ACK_TIMEOUT_SECS: u64 = 90;
const HISTORY_MEDIA_PREVIEW_LIMIT: usize = 6;
const PAIR_REQUEST_TTL_SECS: i64 = 120;
const DISCOVERY_PROTOCOL_VERSION: u16 = 1;

impl HistoryEntry {
    fn text_entry(
        text: &str,
        client_ip: &str,
        client_id: Option<String>,
        client_name: Option<String>,
    ) -> Self {
        Self {
            timestamp: chrono::Local::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false),
            text: text.to_string(),
            client_ip: client_ip.to_string(),
            client_id,
            client_name,
            kind: None,
            file_name: None,
            image_path: None,
            thumbnail_data_url: None,
            file_path: None,
            direction: None,
            status: None,
            session_id: None,
            item_count: None,
            save_target: None,
            items: None,
        }
    }
}

// ---- 主函数 ----

fn main() {
    tracing_subscriber::fmt::init();

    let server_id_path = dirs_log_dir().join("server-id");
    let server_id = std::env::var("VIBEDROP_SERVER_ID")
        .unwrap_or_else(|_| load_or_create_stable_id(&server_id_path, "desktop"));

    // PIN 持久化：保存到 ~/.vibedrop/pin，重启不变
    let pin_path = dirs_log_dir().join("pin");
    let pin = std::env::var("VOICEDROP_PIN").unwrap_or_else(|_| {
        // 尝试读取已保存的 PIN
        if let Ok(saved) = std::fs::read_to_string(&pin_path) {
            let saved = saved.trim().to_string();
            if !saved.is_empty() {
                return saved;
            }
        }
        // 首次运行：生成随机 PIN 并保存
        use std::time::{SystemTime, UNIX_EPOCH};
        let seed = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .subsec_nanos();
        let new_pin = format!("{:04}", seed % 10000);
        let _ = fs::create_dir_all(pin_path.parent().unwrap());
        let _ = std::fs::write(&pin_path, &new_pin);
        new_pin
    });
    let port: u16 = std::env::var("VOICEDROP_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(9001);

    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "Unknown Mac".to_string());

    let ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string());

    // 确保日志目录存在
    let log_dir = dirs_log_dir();
    fs::create_dir_all(&log_dir).expect("无法创建日志目录");

    // enigo 键盘模拟线程
    let (input_tx, mut input_rx) = mpsc::channel::<InputRequest>(32);

    // 注入文本时抑制剪贴板监听误广播（enigo 线程写入、监听线程读取）
    let clipboard_suppress: ClipboardSuppressList = Arc::new(Mutex::new(Vec::new()));
    let clipboard_suppress_for_input = Arc::clone(&clipboard_suppress);

    std::thread::spawn(move || {
        let mut enigo = Enigo::new(&Settings::default())
            .expect("无法初始化键盘模拟。请在系统设置中授予辅助功能权限。");
        let suppress = clipboard_suppress_for_input;

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();

        rt.block_on(async {
            while let Some(req) = input_rx.recv().await {
                let reply = match req.action {
                    InputAction::TypeText(text) => inject_text(&mut enigo, &text, &suppress),
                    InputAction::TypeTextAndEnter(text) => {
                        match inject_text(&mut enigo, &text, &suppress) {
                            Ok(()) => enigo
                                .key(Key::Return, Direction::Click)
                                .map_err(|e| format!("文字已发送，但回车失败: {:?}", e)),
                            Err(e) => Err(e),
                        }
                    }
                    InputAction::PressEnter => enigo
                        .key(Key::Return, Direction::Click)
                        .map_err(|e| format!("{:?}", e)),
                    InputAction::ClipboardText(text) => {
                        // 写入的内容会被剪贴板监听线程看到，先登记抑制避免回声广播给手机
                        suppress_clipboard_broadcast(&suppress, &text);
                        arboard::Clipboard::new()
                            .and_then(|mut clipboard| clipboard.set_text(text))
                            .map_err(|e| format!("无法写入剪贴板: {}", e))
                    }
                };
                let _ = req.reply.send(reply);
            }
        });
    });

    let state = AppState {
        server_id: server_id.clone(),
        pin: pin.clone(),
        hostname: hostname.clone(),
        ip: ip.clone(),
        port,
    };

    // 获取 static 目录路径（跟随可执行文件）
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|p| p.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."));

    let static_dir = if exe_dir.join("../Resources/static").exists() {
        // .app bundle 内（直接 Resources/static）
        exe_dir.join("../Resources/static")
    } else if exe_dir.join("../Resources/_up_/static").exists() {
        // .app bundle 内（Tauri resources 打包的 ../static → _up_/static）
        exe_dir.join("../Resources/_up_/static")
    } else if PathBuf::from("static").exists() {
        PathBuf::from("static")
    } else {
        // 开发时，从项目根目录找
        PathBuf::from("../static")
    };

    let ws_pin = pin.clone();
    let ws_hostname = hostname.clone();
    let ws_input_tx = input_tx.clone();
    let app_handle: Arc<Mutex<Option<AppHandle>>> = Arc::new(Mutex::new(None));
    let app_handle_ws = Arc::clone(&app_handle);
    let ws_static_dir = static_dir.clone();

    // 剪贴板广播 channel
    let (clipboard_tx, _) = broadcast::channel::<String>(16);
    let clipboard_tx_clone = clipboard_tx.clone();

    // 剪贴板监听线程（每 500ms 检查变化）
    let clipboard_suppress_for_watcher = Arc::clone(&clipboard_suppress);
    std::thread::spawn(move || {
        let mut clipboard = arboard::Clipboard::new().expect("无法初始化剪贴板");
        let mut last_text = clipboard.get_text().unwrap_or_default();
        info!("剪贴板监听已启动");

        loop {
            std::thread::sleep(std::time::Duration::from_millis(500));
            if let Ok(current) = clipboard.get_text() {
                if current != last_text && !current.is_empty() {
                    last_text = current.clone();
                    if is_clipboard_broadcast_suppressed(&clipboard_suppress_for_watcher, &current)
                    {
                        continue;
                    }
                    info!(
                        "剪贴板变化: {}...",
                        &last_text.chars().take(50).collect::<String>()
                    );
                    let _ = clipboard_tx_clone.send(last_text.clone());
                }
            }
        }
    });

    let ws_state = Arc::new(WsState {
        server_id: server_id.clone(),
        pin: ws_pin,
        hostname: ws_hostname,
        input_tx: ws_input_tx,
        app_handle: app_handle_ws,
        clipboard_tx,
        clients: Arc::new(Mutex::new(std::collections::HashMap::new())),
        preferred_share_client: Arc::new(Mutex::new(None)),
        pair_requests: Arc::new(Mutex::new(std::collections::HashMap::new())),
        pending_transfers: Arc::new(Mutex::new(std::collections::HashMap::new())),
        session_counter: AtomicU64::new(1),
        pair_counter: AtomicU64::new(1),
        transfer_counter: AtomicU64::new(1),
    });
    let ws_state_for_server = Arc::clone(&ws_state);
    let server_state = ServerState {
        app: state.clone(),
        ws: Arc::clone(&ws_state_for_server),
    };

    start_finder_share_request_worker(Arc::clone(&ws_state));

    // 启动 HTTP 服务器（后台）
    let ws_port = port;
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();

        rt.block_on(async {
            let udp_state = server_state.clone();
            tokio::spawn(async move {
                if let Err(error) = run_udp_discovery_responder(udp_state).await {
                    warn!("UDP 发现应答未启动: {}", error);
                }
            });

            let app = Router::new()
                .route("/ws", get(ws_handler))
                .route("/discover", get(discover_handler))
                .route("/pair/request", post(request_pairing_handler))
                .route("/pair/status/{request_id}", get(pair_status_handler))
                .route(
                    "/share-extension/paths",
                    post(local_share_extension_handler),
                )
                .fallback_service(ServeDir::new(ws_static_dir))
                .with_state(server_state);

            let http_addr = format!("0.0.0.0:{}", ws_port);
            info!("HTTP 服务启动在 {}", http_addr);
            let listener = tokio::net::TcpListener::bind(&http_addr)
                .await
                .unwrap_or_else(|_| panic!("无法绑定端口 {}", ws_port));
            axum::serve(
                listener,
                app.into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            .unwrap();
        });
    });

    // 启动 Tauri APP
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.unminimize();
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_shell::init())
        .manage(state)
        .manage(ws_state)
        .on_window_event(|window, event| {
            if window.label() != "main" {
                return;
            }

            let next_state = match event {
                WindowEvent::Resized(size) => {
                    let scale_factor = window.scale_factor().unwrap_or(1.0);
                    let logical = size.to_logical::<f64>(scale_factor);
                    Some(WindowState {
                        width: logical.width,
                        height: logical.height,
                    })
                }
                WindowEvent::ScaleFactorChanged {
                    scale_factor,
                    new_inner_size,
                    ..
                } => {
                    let logical = new_inner_size.to_logical::<f64>(*scale_factor);
                    Some(WindowState {
                        width: logical.width,
                        height: logical.height,
                    })
                }
                _ => None,
            };

            if let Some(window_state) = next_state {
                if let Err(err) = save_window_state(&window_state) {
                    warn!("保存窗口尺寸失败: {}", err);
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_service_info,
            check_accessibility,
            open_accessibility_settings,
            load_history_entries,
            open_history_path,
            open_history_paths,
            list_connected_clients,
            list_pair_requests,
            approve_pair_request,
            reject_pair_request,
            set_preferred_share_client,
            send_dropped_paths
        ])
        .setup(move |app| {
            if let Some(window_config) = app.config().app.windows.first().cloned() {
                let mut window_config = window_config;

                if let Some(saved) = load_window_state() {
                    window_config.width = saved.width;
                    window_config.height = saved.height;
                    window_config.center = false;
                }

                let window = WebviewWindowBuilder::from_config(app, &window_config)?.build()?;
                #[cfg(target_os = "macos")]
                if let Err(error) = configure_macos_native_drag_support(&window) {
                    warn!("配置 macOS 原生拖拽支持失败: {}", error);
                    log_macos_drag_event(
                        "setup:register-drag-types:error",
                        serde_json::json!({
                            "window_label": window.label(),
                            "error": error,
                        }),
                    );
                }
            }

            // 把 app_handle 传给 WebSocket 线程
            if let Ok(mut h) = app_handle.lock() {
                *h = Some(app.handle().clone());
            }
            // 创建系统托盘菜单（类似 KDE Connect 风格）
            let server_addr = format!("{}:{}", ip, port);
            let tray_pin = pin.clone();
            let title = MenuItem::with_id(app, "title", "VibeDrop", false, None::<&str>)?;
            let addr_label = MenuItem::with_id(
                app,
                "addr",
                &format!("地址 {}", server_addr),
                false,
                None::<&str>,
            )?;
            let pin_label = MenuItem::with_id(
                app,
                "pin",
                &format!("PIN {}", tray_pin),
                false,
                None::<&str>,
            )?;
            let sep1 = PredefinedMenuItem::separator(app)?;
            let copy_addr = MenuItem::with_id(app, "copy_addr", "复制地址", true, None::<&str>)?;
            let copy_pin = MenuItem::with_id(app, "copy_pin", "复制 PIN", true, None::<&str>)?;
            let sep2 = PredefinedMenuItem::separator(app)?;
            let show = MenuItem::with_id(app, "show", "打开 VibeDrop", true, None::<&str>)?;
            let sep3 = PredefinedMenuItem::separator(app)?;
            let quit = MenuItem::with_id(app, "quit", "退出 VibeDrop", true, None::<&str>)?;

            let menu = Menu::with_items(
                app,
                &[
                    &title,
                    &addr_label,
                    &pin_label,
                    &sep1,
                    &copy_addr,
                    &copy_pin,
                    &sep2,
                    &show,
                    &sep3,
                    &quit,
                ],
            )?;

            // 加载托盘图标（白色透明底）
            let tray_png = include_bytes!("../icons/tray-icon.png");
            let decoder = png::Decoder::new(std::io::Cursor::new(tray_png));
            let mut reader = decoder.read_info().expect("读取托盘图标失败");
            let mut buf = vec![0; reader.output_buffer_size()];
            let info = reader.next_frame(&mut buf).expect("解码托盘图标失败");
            buf.truncate(info.buffer_size());
            let tray_icon = tauri::image::Image::new(&buf, info.width, info.height);

            let _tray = TrayIconBuilder::new()
                .icon(tray_icon)
                .icon_as_template(true)
                .menu(&menu)
                .show_menu_on_left_click(true)
                .tooltip(&format!("VibeDrop · {}", server_addr))
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "copy_addr" => {
                        if let Err(err) = arboard::Clipboard::new()
                            .and_then(|mut clipboard| clipboard.set_text(server_addr.clone()))
                        {
                            warn!("复制地址失败: {}", err);
                        }
                    }
                    "copy_pin" => {
                        if let Err(err) = arboard::Clipboard::new()
                            .and_then(|mut clipboard| clipboard.set_text(tray_pin.clone()))
                        {
                            warn!("复制 PIN 失败: {}", err);
                        }
                    }
                    "quit" => {
                        app.exit(0);
                    }
                    "show" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                        }
                    }
                    _ => {}
                })
                .build(app)?;

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("启动 Tauri 应用出错");
}

// ---- WebSocket 相关 ----

struct WsState {
    server_id: String,
    pin: String,
    hostname: String,
    input_tx: mpsc::Sender<InputRequest>,
    app_handle: Arc<Mutex<Option<AppHandle>>>,
    clipboard_tx: broadcast::Sender<String>,
    clients: Arc<Mutex<std::collections::HashMap<String, ConnectedClient>>>,
    preferred_share_client: Arc<Mutex<Option<String>>>,
    pair_requests: Arc<Mutex<std::collections::HashMap<String, PairRequestEntry>>>,
    pending_transfers:
        Arc<Mutex<std::collections::HashMap<String, oneshot::Sender<Result<String, String>>>>>,
    session_counter: AtomicU64,
    pair_counter: AtomicU64,
    transfer_counter: AtomicU64,
}

#[derive(Debug, Deserialize)]
struct QueuedFinderShareRequest {
    paths: Vec<String>,
    #[serde(default)]
    source: Option<String>,
}

async fn ws_handler(
    ws: WebSocketUpgrade,
    AxumState(state): AxumState<ServerState>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, Arc::clone(&state.ws)))
}

async fn handle_socket(socket: WebSocket, state: Arc<WsState>) {
    let mut authenticated = false;
    let session_id = state.session_counter.fetch_add(1, Ordering::Relaxed);
    info!("新的 WebSocket 连接");
    append_debug_log(
        "websocket",
        "open",
        serde_json::json!({
            "session_id": session_id,
        }),
    );
    let mut authenticated_client: Option<(String, u64)> = None;
    let mut current_client_id = String::new();
    let mut current_client_name = String::new();
    let mut current_receives_clipboard = false;
    let mut active_incoming_file_transfers = std::collections::HashSet::new();

    // 拆分 WebSocket 为 sender/receiver，以便同时接收客户端消息和广播剪贴板
    let (mut ws_sender, mut ws_receiver) = socket.split();
    let mut clipboard_rx = state.clipboard_tx.subscribe();
    let (outgoing_tx, mut outgoing_rx) = mpsc::unbounded_channel::<String>();

    loop {
        tokio::select! {
            // 接收客户端发来的消息
            msg = ws_receiver.next() => {
                match msg {
                    Some(Ok(Message::Text(text))) => {
                        let client_msg: ClientMessage = match serde_json::from_str(&text) {
                            Ok(m) => m,
                            Err(e) => {
                                warn!("无效 JSON: {}", e);
                                let reply = ServerMessage {
                                    status: "error".to_string(),
                                    hostname: None,
                                    error: Some("无效的 JSON 格式".to_string()),
                                };
                                let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                continue;
                            }
                        };

                        match client_msg.action.as_str() {
                            "auth" => {
                                if let Some(pin) = &client_msg.pin {
                                    if pin == &state.pin {
                                        authenticated = true;
                                        let client_id = client_msg
                                            .device_id
                                            .clone()
                                            .filter(|value| !value.trim().is_empty())
                                            .unwrap_or_else(|| format!("client-{session_id}"));
                                        let client_name = client_msg
                                            .device_name
                                            .clone()
                                            .filter(|value| !value.trim().is_empty())
                                            .unwrap_or_else(|| format!("手机 {session_id}"));
                                        let base_id = client_msg
                                            .base_device_id
                                            .clone()
                                            .filter(|value| !value.trim().is_empty())
                                            .unwrap_or_else(|| client_id.clone());
                                        let can_receive_files = client_msg.can_receive_files.unwrap_or(false);
                                        let receives_clipboard = client_msg.receives_clipboard.unwrap_or(false);
                                        let device_role = client_msg
                                            .device_role
                                            .as_deref()
                                            .unwrap_or("primary");
                                        current_receives_clipboard = receives_clipboard;
                                        if let Ok(mut clients) = state.clients.lock() {
                                            clients.insert(
                                                client_id.clone(),
                                                ConnectedClient {
                                                    session_id,
                                                    id: client_id.clone(),
                                                    base_id: base_id.clone(),
                                                    name: client_name.clone(),
                                                    can_receive_files,
                                                    receives_clipboard,
                                                    sender: outgoing_tx.clone(),
                                                },
                                            );
                                        }
                                        current_client_id = client_id.clone();
                                        current_client_name = client_name.clone();
                                        authenticated_client = Some((client_id.clone(), session_id));
                                        broadcast_connected_clients(&state);

                                        info!("客户端认证成功: {} ({})", client_name, device_role);
                                        append_debug_log(
                                            "websocket",
                                            "auth_ok",
                                            serde_json::json!({
                                                "session_id": session_id,
                                                "client_id": client_id,
                                                "base_id": base_id,
                                                "client_name": client_name,
                                                "device_role": device_role,
                                                "can_receive_files": can_receive_files,
                                                "receives_clipboard": receives_clipboard,
                                            }),
                                        );
                                        let reply = serde_json::json!({
                                            "status": "ok",
                                            "hostname": state.hostname.clone(),
                                            "server_id": state.server_id.clone(),
                                        });
                                        let _ = ws_sender.send(Message::Text(reply.to_string().into())).await;
                                    } else {
                                        warn!("PIN 错误");
                                        append_debug_log(
                                            "websocket",
                                            "auth_failed",
                                            serde_json::json!({
                                                "session_id": session_id,
                                                "reason": "pin_mismatch",
                                            }),
                                        );
                                        let reply = ServerMessage {
                                            status: "error".to_string(),
                                            hostname: None,
                                            error: Some("PIN 码错误".to_string()),
                                        };
                                        let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    }
                                }
                            }

                            "incoming_file_saved" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                if let Some(transfer_id) = client_msg.transfer_id.as_deref() {
                                    if let Ok(mut pending) = state.pending_transfers.lock() {
                                        if let Some(done_tx) = pending.remove(transfer_id) {
                                            let saved_path = client_msg.saved_path.clone().unwrap_or_default();
                                            let _ = done_tx.send(Ok(saved_path));
                                        }
                                    }
                                }
                            }

                            "incoming_file_error" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                if let Some(transfer_id) = client_msg.transfer_id.as_deref() {
                                    let mut handled_outbound = false;
                                    if let Ok(mut pending) = state.pending_transfers.lock() {
                                        if let Some(done_tx) = pending.remove(transfer_id) {
                                            let message = client_msg
                                                .error
                                                .clone()
                                                .unwrap_or_else(|| "手机端保存失败".to_string());
                                            let _ = done_tx.send(Err(message));
                                            handled_outbound = true;
                                        }
                                    }

                                    if !handled_outbound {
                                        let _ = cancel_downloaded_file_transfer(transfer_id);
                                        active_incoming_file_transfers.remove(transfer_id);
                                    }
                                }
                            }

                            "incoming_file_start" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                let Some(transfer_id) = client_msg.transfer_id.as_deref() else {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("缺少传输标识".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                };

                                let Some(size_bytes) = client_msg.size_bytes else {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("缺少文件大小".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                };

                                let result = begin_downloaded_file_transfer(
                                    transfer_id,
                                    client_msg.file_name.as_deref(),
                                    client_msg.mime_type.as_deref(),
                                    size_bytes,
                                    if current_client_id.is_empty() {
                                        None
                                    } else {
                                        Some(current_client_id.as_str())
                                    },
                                    if current_client_name.is_empty() {
                                        None
                                    } else {
                                        Some(current_client_name.as_str())
                                    },
                                );

                                let reply = match result {
                                    Ok(()) => {
                                        active_incoming_file_transfers
                                            .insert(transfer_id.to_string());
                                        ServerMessage {
                                            status: "ok".to_string(),
                                            hostname: None,
                                            error: None,
                                        }
                                    }
                                    Err(error) => ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some(error),
                                    },
                                };
                                let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                            }

                            "incoming_file_chunk" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                let Some(transfer_id) = client_msg.transfer_id.as_deref() else {
                                    continue;
                                };
                                let Some(chunk_base64) = client_msg.chunk_base64.as_deref() else {
                                    let reply = serde_json::json!({
                                        "action": "incoming_file_error",
                                        "transfer_id": transfer_id,
                                        "error": "缺少文件分块数据",
                                    });
                                    let _ = ws_sender.send(Message::Text(reply.to_string().into())).await;
                                    let _ = cancel_downloaded_file_transfer(transfer_id);
                                    active_incoming_file_transfers.remove(transfer_id);
                                    continue;
                                };

                                if let Err(error) =
                                    append_downloaded_file_transfer_chunk(transfer_id, chunk_base64)
                                {
                                    let reply = serde_json::json!({
                                        "action": "incoming_file_error",
                                        "transfer_id": transfer_id,
                                        "error": error,
                                    });
                                    let _ = ws_sender.send(Message::Text(reply.to_string().into())).await;
                                    let _ = cancel_downloaded_file_transfer(transfer_id);
                                    active_incoming_file_transfers.remove(transfer_id);
                                }
                            }

                            "incoming_file_complete" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                let Some(transfer_id) = client_msg.transfer_id.as_deref() else {
                                    continue;
                                };

                                match finish_downloaded_file_transfer(transfer_id) {
                                    Ok((meta, saved_file_name, saved_file_path)) => {
                                        active_incoming_file_transfers.remove(transfer_id);

                                        let history_entry = HistoryEntry {
                                            timestamp: chrono::Local::now().to_rfc3339_opts(
                                                chrono::SecondsFormat::Millis,
                                                false,
                                            ),
                                            text: format!("[文件] {}", saved_file_name),
                                            client_ip: "ws-client".to_string(),
                                            client_id: meta.client_id.clone(),
                                            client_name: meta.client_name.clone(),
                                            kind: Some("file".to_string()),
                                            file_name: Some(saved_file_name.clone()),
                                            image_path: None,
                                            thumbnail_data_url: None,
                                            file_path: Some(saved_file_path.clone()),
                                            direction: None,
                                            status: None,
                                            session_id: None,
                                            item_count: None,
                                            save_target: None,
                                            items: None,
                                        };

                                        append_history_entry(&history_entry);

                                        if let Ok(guard) = state.app_handle.lock() {
                                            if let Some(handle) = guard.as_ref() {
                                                let _ = handle.emit("text-received", &history_entry);
                                            }
                                        }

                                        let reply = serde_json::json!({
                                            "action": "incoming_file_saved",
                                            "transfer_id": transfer_id,
                                            "saved_path": saved_file_path,
                                        });
                                        let _ = ws_sender.send(Message::Text(reply.to_string().into())).await;
                                    }
                                    Err(error) => {
                                        let reply = serde_json::json!({
                                            "action": "incoming_file_error",
                                            "transfer_id": transfer_id,
                                            "error": error,
                                        });
                                        let _ = ws_sender.send(Message::Text(reply.to_string().into())).await;
                                        let _ = cancel_downloaded_file_transfer(transfer_id);
                                        active_incoming_file_transfers.remove(transfer_id);
                                    }
                                }
                            }

                            "type" | "type_enter" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                if let Some(text_content) = &client_msg.text {
                                    let send_with_enter = client_msg.action == "type_enter";
                                    if send_with_enter {
                                        info!("收到文字并回车: {}", text_content);
                                    } else {
                                        info!("收到文字: {}", text_content);
                                    }
                                    let history_entry = HistoryEntry::text_entry(
                                        text_content,
                                        "ws-client",
                                        if current_client_id.is_empty() {
                                            None
                                        } else {
                                            Some(current_client_id.clone())
                                        },
                                        if current_client_name.is_empty() {
                                            None
                                        } else {
                                            Some(current_client_name.clone())
                                        },
                                    );
                                    append_history_entry(&history_entry);

                                    let (reply_tx, reply_rx) = oneshot::channel();
                                    let req = InputRequest {
                                        action: if send_with_enter {
                                            InputAction::TypeTextAndEnter(text_content.clone())
                                        } else {
                                            InputAction::TypeText(text_content.clone())
                                        },
                                        reply: reply_tx,
                                    };

                                    if state.input_tx.send(req).await.is_ok() {
                                        match reply_rx.await {
                                            Ok(Ok(())) => {
                                                info!("文字已输入");

                                                // 通知 Tauri 前端窗口
                                                if let Ok(guard) = state.app_handle.lock() {
                                                    if let Some(handle) = guard.as_ref() {
                                                        let _ = handle.emit("text-received", &history_entry);
                                                    }
                                                }

                                                let reply = ServerMessage {
                                                    status: "ok".to_string(),
                                                    hostname: None,
                                                    error: None,
                                                };
                                                let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                            }
                                            Ok(Err(e)) => {
                                                error!("键盘模拟失败: {}", e);
                                                let reply = ServerMessage {
                                                    status: "error".to_string(),
                                                    hostname: None,
                                                    error: Some(e),
                                                };
                                                let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                            }
                                            Err(_) => {
                                                error!("键盘输入线程无响应");
                                            }
                                        }
                                    }
                                }
                            }

                            // 外出模式：文字写入本机剪贴板（不模拟键盘）。
                            // 典型链路：手机 → 本机剪贴板 → UU 远程等远控工具的剪贴板同步 → 被控电脑。
                            "clipboard_text" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                if let Some(text_content) = &client_msg.text {
                                    info!("收到文字进剪贴板: {}", text_content);
                                    let history_entry = HistoryEntry::text_entry(
                                        text_content,
                                        "ws-client",
                                        if current_client_id.is_empty() {
                                            None
                                        } else {
                                            Some(current_client_id.clone())
                                        },
                                        if current_client_name.is_empty() {
                                            None
                                        } else {
                                            Some(current_client_name.clone())
                                        },
                                    );
                                    append_history_entry(&history_entry);

                                    let (reply_tx, reply_rx) = oneshot::channel();
                                    let req = InputRequest {
                                        action: InputAction::ClipboardText(text_content.clone()),
                                        reply: reply_tx,
                                    };

                                    if state.input_tx.send(req).await.is_ok() {
                                        match reply_rx.await {
                                            Ok(Ok(())) => {
                                                info!("文字已写入剪贴板");

                                                if let Ok(guard) = state.app_handle.lock() {
                                                    if let Some(handle) = guard.as_ref() {
                                                        let _ = handle.emit("text-received", &history_entry);
                                                    }
                                                }

                                                let reply = ServerMessage {
                                                    status: "ok".to_string(),
                                                    hostname: None,
                                                    error: None,
                                                };
                                                let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                            }
                                            Ok(Err(e)) => {
                                                error!("写入剪贴板失败: {}", e);
                                                let reply = ServerMessage {
                                                    status: "error".to_string(),
                                                    hostname: None,
                                                    error: Some(e),
                                                };
                                                let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                            }
                                            Err(_) => {
                                                error!("键盘输入线程无响应");
                                            }
                                        }
                                    }
                                }
                            }

                            "image_clipboard" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                let Some(image_base64) = client_msg.image_base64.as_deref() else {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("缺少图片数据".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                };

                                let requested_file_name = client_msg.file_name.as_deref().unwrap_or("图片");
                                info!(
                                    "收到图片: {} ({})",
                                    requested_file_name,
                                    client_msg.mime_type.as_deref().unwrap_or("unknown")
                                );

                                match prepare_saved_image(
                                    image_base64,
                                    client_msg.file_name.as_deref(),
                                    client_msg.mime_type.as_deref(),
                                ) {
                                    Ok(saved_image) => {
                                        let history_entry = HistoryEntry {
                                            timestamp: chrono::Local::now().to_rfc3339_opts(
                                                chrono::SecondsFormat::Millis,
                                                false,
                                            ),
                                            text: format!("[图片] {}", saved_image.file_name),
                                            client_ip: "ws-client".to_string(),
                                            client_id: if current_client_id.is_empty() {
                                                None
                                            } else {
                                                Some(current_client_id.clone())
                                            },
                                            client_name: if current_client_name.is_empty() {
                                                None
                                            } else {
                                                Some(current_client_name.clone())
                                            },
                                            kind: Some("image".to_string()),
                                            file_name: Some(saved_image.file_name.clone()),
                                            image_path: Some(saved_image.image_path.clone()),
                                            thumbnail_data_url: Some(saved_image.thumbnail_data_url.clone()),
                                            file_path: None,
                                            direction: None,
                                            status: None,
                                            session_id: None,
                                            item_count: None,
                                            save_target: None,
                                            items: None,
                                        };

                                        if let Err(e) = set_clipboard_image(saved_image.clipboard_image) {
                                            error!("图片写入剪贴板失败: {}", e);
                                            let reply = ServerMessage {
                                                status: "error".to_string(),
                                                hostname: None,
                                                error: Some(e),
                                            };
                                            let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                            continue;
                                        }

                                        append_history_entry(&history_entry);

                                        if let Ok(guard) = state.app_handle.lock() {
                                            if let Some(handle) = guard.as_ref() {
                                                let _ = handle.emit("text-received", &history_entry);
                                            }
                                        }

                                        let reply = ServerMessage {
                                            status: "ok".to_string(),
                                            hostname: None,
                                            error: None,
                                        };
                                        let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    }
                                    Err(e) => {
                                        error!("图片处理失败: {}", e);
                                        let reply = ServerMessage {
                                            status: "error".to_string(),
                                            hostname: None,
                                            error: Some(e),
                                        };
                                        let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    }
                                }
                            }

                            "file_download" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                let Some(file_base64) = client_msg.file_base64.as_deref() else {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("缺少文件数据".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                };

                                let requested_file_name = client_msg.file_name.as_deref().unwrap_or("file.bin");
                                info!(
                                    "收到文件: {} ({})",
                                    requested_file_name,
                                    client_msg.mime_type.as_deref().unwrap_or("unknown")
                                );

                                match save_downloaded_file(
                                    file_base64,
                                    client_msg.file_name.as_deref(),
                                ) {
                                    Ok((saved_file_name, saved_file_path)) => {
                                        let history_entry = HistoryEntry {
                                            timestamp: chrono::Local::now().to_rfc3339_opts(
                                                chrono::SecondsFormat::Millis,
                                                false,
                                            ),
                                            text: format!("[文件] {}", saved_file_name),
                                            client_ip: "ws-client".to_string(),
                                            client_id: if current_client_id.is_empty() {
                                                None
                                            } else {
                                                Some(current_client_id.clone())
                                            },
                                            client_name: if current_client_name.is_empty() {
                                                None
                                            } else {
                                                Some(current_client_name.clone())
                                            },
                                            kind: Some("file".to_string()),
                                            file_name: Some(saved_file_name),
                                            image_path: None,
                                            thumbnail_data_url: None,
                                            file_path: Some(saved_file_path),
                                            direction: None,
                                            status: None,
                                            session_id: None,
                                            item_count: None,
                                            save_target: None,
                                            items: None,
                                        };

                                        append_history_entry(&history_entry);

                                        if let Ok(guard) = state.app_handle.lock() {
                                            if let Some(handle) = guard.as_ref() {
                                                let _ = handle.emit("text-received", &history_entry);
                                            }
                                        }

                                        let reply = ServerMessage {
                                            status: "ok".to_string(),
                                            hostname: None,
                                            error: None,
                                        };
                                        let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    }
                                    Err(e) => {
                                        error!("文件保存失败: {}", e);
                                        let reply = ServerMessage {
                                            status: "error".to_string(),
                                            hostname: None,
                                            error: Some(e),
                                        };
                                        let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    }
                                }
                            }

                            "enter" => {
                                if !authenticated {
                                    let reply = ServerMessage {
                                        status: "error".to_string(),
                                        hostname: None,
                                        error: Some("未认证".to_string()),
                                    };
                                    let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    continue;
                                }

                                info!("收到回车请求");

                                let (reply_tx, reply_rx) = oneshot::channel();
                                let req = InputRequest {
                                    action: InputAction::PressEnter,
                                    reply: reply_tx,
                                };

                                if state.input_tx.send(req).await.is_ok() {
                                    match reply_rx.await {
                                        Ok(Ok(())) => {
                                            let reply = ServerMessage {
                                                status: "ok".to_string(),
                                                hostname: None,
                                                error: None,
                                            };
                                            let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                        }
                                        Ok(Err(e)) => {
                                            error!("回车模拟失败: {}", e);
                                            let reply = ServerMessage {
                                                status: "error".to_string(),
                                                hostname: None,
                                                error: Some(format!("回车失败: {}", e)),
                                            };
                                            let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                        }
                                        Err(_) => {
                                            error!("键盘输入线程无响应");
                                        }
                                    }
                                }
                            }

                            "ping" => {
                                let reply = serde_json::json!({"action": "pong"});
                                let _ = ws_sender.send(Message::Text(reply.to_string().into())).await;
                            }

                            _ => {}
                        }
                    }
                    Some(Ok(Message::Ping(data))) => {
                        let _ = ws_sender.send(Message::Pong(data)).await;
                    }
                    Some(Ok(Message::Close(_))) | None => break,
                    _ => {}
                }
            }
            outgoing = outgoing_rx.recv() => {
                match outgoing {
                    Some(payload) => {
                        if ws_sender.send(Message::Text(payload.into())).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                }
            }
            // 接收剪贴板广播，转发给已认证的客户端
            clipboard_text = clipboard_rx.recv() => {
                if authenticated {
                    if let Ok(text) = clipboard_text {
                        let send_to_explicit_receivers_only = state
                            .clients
                            .lock()
                            .map(|clients| clients.values().any(|client| client.receives_clipboard))
                            .unwrap_or(false);
                        if send_to_explicit_receivers_only && !current_receives_clipboard {
                            continue;
                        }
                        let msg = serde_json::json!({
                            "action": "clipboard",
                            "text": text
                        });
                        let _ = ws_sender.send(Message::Text(msg.to_string().into())).await;
                    }
                }
            }
        }
    }

    if let Some((client_id, client_session_id)) = authenticated_client {
        if let Ok(mut clients) = state.clients.lock() {
            let should_remove = clients
                .get(&client_id)
                .map(|client| client.session_id == client_session_id)
                .unwrap_or(false);
            if should_remove {
                clients.remove(&client_id);
            }
        }
        broadcast_connected_clients(&state);
    }
    for transfer_id in active_incoming_file_transfers {
        let _ = cancel_downloaded_file_transfer(&transfer_id);
    }
    info!("WebSocket 连接关闭");
    append_debug_log(
        "websocket",
        "close",
        serde_json::json!({
            "session_id": session_id,
            "authenticated": authenticated,
            "client_id": current_client_id,
            "client_name": current_client_name,
        }),
    );
}

fn connected_clients_snapshot(state: &Arc<WsState>) -> Vec<ConnectedClientInfo> {
    let mut clients = state
        .clients
        .lock()
        .map(|clients| {
            let mut grouped = std::collections::HashMap::<String, ConnectedClientInfo>::new();
            for client in clients.values() {
                let entry =
                    grouped
                        .entry(client.base_id.clone())
                        .or_insert_with(|| ConnectedClientInfo {
                            id: client.base_id.clone(),
                            name: client.name.clone(),
                            can_receive_files: client.can_receive_files,
                        });
                if entry.name.trim().is_empty() {
                    entry.name = client.name.clone();
                }
                if client.can_receive_files {
                    entry.can_receive_files = true;
                }
            }
            grouped.into_values().collect::<Vec<_>>()
        })
        .unwrap_or_default();

    clients.sort_by(|a, b| a.name.cmp(&b.name).then(a.id.cmp(&b.id)));
    clients
}

fn broadcast_connected_clients(state: &Arc<WsState>) {
    let clients = connected_clients_snapshot(state);
    if let Ok(guard) = state.app_handle.lock() {
        if let Some(handle) = guard.as_ref() {
            let _ = handle.emit("connected-clients-changed", &clients);
        }
    }
}

fn focus_main_window(state: &Arc<WsState>) {
    if let Ok(guard) = state.app_handle.lock() {
        if let Some(handle) = guard.as_ref() {
            if let Some(window) = handle.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }
    }
}

fn prune_pair_requests_locked(requests: &mut std::collections::HashMap<String, PairRequestEntry>) {
    let now = chrono::Local::now();
    requests.retain(|_, entry| (now - entry.requested_at).num_seconds() < PAIR_REQUEST_TTL_SECS);
}

fn pair_requests_snapshot(state: &Arc<WsState>) -> Vec<PairRequestInfo> {
    let mut requests = state
        .pair_requests
        .lock()
        .map(|mut requests| {
            prune_pair_requests_locked(&mut requests);
            requests
                .values()
                .filter(|entry| entry.status == PairRequestStatus::Pending)
                .map(|entry| PairRequestInfo {
                    request_id: entry.request_id.clone(),
                    client_id: entry.client_id.clone(),
                    client_name: entry.client_name.clone(),
                    code: entry.code.clone(),
                    requested_at: entry.requested_at.to_rfc3339(),
                })
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();

    requests.sort_by(|a, b| b.requested_at.cmp(&a.requested_at));
    requests
}

fn broadcast_pair_requests(state: &Arc<WsState>) {
    let requests = pair_requests_snapshot(state);
    if let Ok(guard) = state.app_handle.lock() {
        if let Some(handle) = guard.as_ref() {
            let _ = handle.emit("pair-requests-changed", &requests);
        }
    }
}

fn set_pair_request_status(
    state: &Arc<WsState>,
    request_id: &str,
    status: PairRequestStatus,
) -> Result<(), String> {
    let updated = {
        let mut requests = state
            .pair_requests
            .lock()
            .map_err(|_| "配对请求队列不可用".to_string())?;
        prune_pair_requests_locked(&mut requests);
        let Some(entry) = requests.get_mut(request_id) else {
            return Err("配对请求不存在或已过期".to_string());
        };
        entry.status = status;
        true
    };

    if updated {
        broadcast_pair_requests(state);
    }
    Ok(())
}

fn pair_request_status_snapshot(
    state: &Arc<WsState>,
    request_id: &str,
) -> Option<PairRequestStatus> {
    state.pair_requests.lock().ok().and_then(|mut requests| {
        prune_pair_requests_locked(&mut requests);
        requests.get(request_id).map(|entry| entry.status)
    })
}

fn start_finder_share_request_worker(state: Arc<WsState>) {
    std::thread::spawn(move || {
        let queue_dir = finder_share_requests_dir();
        let _ = fs::create_dir_all(&queue_dir);

        loop {
            process_pending_finder_share_requests(&state);
            std::thread::sleep(std::time::Duration::from_millis(900));
        }
    });
}

fn process_pending_finder_share_requests(state: &Arc<WsState>) {
    let Ok(app_handle) = state.app_handle.lock().map(|guard| guard.clone()) else {
        return;
    };
    let Some(app_handle) = app_handle else {
        return;
    };

    let Some(client) = resolve_finder_share_target_client(state) else {
        return;
    };

    let queue_dir = finder_share_requests_dir();
    let Ok(entries) = fs::read_dir(&queue_dir) else {
        return;
    };

    let mut pending_files = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.extension().and_then(|ext| ext.to_str()) == Some("json"))
        .collect::<Vec<_>>();
    pending_files.sort();

    for request_path in pending_files {
        let request = match load_finder_share_request(&request_path) {
            Ok(request) if !request.paths.is_empty() => request,
            Ok(_) | Err(_) => {
                let _ = fs::remove_file(&request_path);
                continue;
            }
        };

        let share_paths = request.paths.iter().map(PathBuf::from).collect::<Vec<_>>();
        if share_paths.is_empty() {
            let _ = fs::remove_file(&request_path);
            continue;
        }

        let transfer_id = format!(
            "finder-share-{}",
            state.transfer_counter.fetch_add(1, Ordering::Relaxed)
        );
        let file_name = summarize_paths_for_ui(&share_paths);
        let detail = match request.source.as_deref() {
            Some("finder-service") => "来自 Finder 右键发送".to_string(),
            Some(source) if !source.trim().is_empty() => format!("来自 {}", source),
            _ => "来自 Finder 发送".to_string(),
        };

        if fs::remove_file(&request_path).is_err() {
            continue;
        }

        emit_desktop_transfer_event(
            &app_handle,
            &DesktopTransferEvent {
                transfer_id: transfer_id.clone(),
                client_id: client.id.clone(),
                client_name: client.name.clone(),
                file_name,
                status: "preparing".to_string(),
                progress: 0.0,
                sent_bytes: 0,
                total_bytes: 0,
                is_archive: share_paths.len() > 1 || share_paths.iter().any(|path| path.is_dir()),
                detail: Some(detail),
            },
        );

        tauri::async_runtime::spawn(run_desktop_outbound_transfer(
            Arc::clone(state),
            app_handle.clone(),
            client.clone(),
            transfer_id,
            ResolvedDropPaths {
                paths: share_paths,
                cleanup_dir: None,
            },
        ));
        focus_main_window(state);
        break;
    }
}

fn resolve_finder_share_target_client(state: &Arc<WsState>) -> Option<ConnectedClient> {
    let preferred_id = state
        .preferred_share_client
        .lock()
        .ok()
        .and_then(|value| value.clone());

    let clients = state.clients.lock().ok()?;
    if let Some(preferred_id) = preferred_id {
        if let Some(client) = clients
            .get(&preferred_id)
            .filter(|client| client.can_receive_files)
        {
            return Some(client.clone());
        }
    }

    let mut file_ready_clients = clients
        .values()
        .filter(|client| client.can_receive_files)
        .cloned()
        .collect::<Vec<_>>();
    file_ready_clients.sort_by(|a, b| a.name.cmp(&b.name).then(a.id.cmp(&b.id)));
    file_ready_clients.into_iter().next()
}

fn load_finder_share_request(path: &Path) -> Result<QueuedFinderShareRequest, String> {
    let payload =
        std::fs::read_to_string(path).map_err(|error| format!("无法读取共享请求: {}", error))?;
    serde_json::from_str::<QueuedFinderShareRequest>(&payload)
        .map_err(|error| format!("共享请求无效: {}", error))
}

fn emit_desktop_transfer_event(app: &AppHandle, event: &DesktopTransferEvent) {
    let _ = app.emit("desktop-transfer-progress", event);
}

fn emit_desktop_history_entry(app: &AppHandle, entry: &HistoryEntry) {
    let _ = app.emit("text-received", entry);
}

fn emit_incoming_history_session_start(
    client: &ConnectedClient,
    session: &OutboundHistorySession,
) -> Result<(), String> {
    let message = serde_json::json!({
        "action": "incoming_history_session_start",
        "session_id": &session.session_id,
        "timestamp": &session.timestamp,
        "kind": &session.kind,
        "text": &session.text,
        "item_count": session.item_count,
        "save_target": &session.save_target,
        "items": &session.items,
    });

    client
        .sender
        .send(message.to_string())
        .map_err(|_| "手机连接已断开".to_string())
}

async fn run_desktop_outbound_transfer(
    state: Arc<WsState>,
    app: AppHandle,
    client: ConnectedClient,
    transfer_id: String,
    resolved_drop: ResolvedDropPaths,
) {
    let _cleanup = resolved_drop
        .cleanup_dir
        .as_ref()
        .map(|path| ScopedPathCleanup::new(path.clone()));
    let paths = resolved_drop.paths;
    let history_session = match build_outbound_history_session(
        &paths,
        &transfer_id,
        resolved_drop.cleanup_dir.as_deref(),
    ) {
        Ok(session) => session,
        Err(error) => {
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id,
                    client_name: client.name,
                    file_name: summarize_paths_for_ui(&paths),
                    status: "error".to_string(),
                    progress: 0.0,
                    sent_bytes: 0,
                    total_bytes: 0,
                    is_archive: false,
                    detail: Some(error),
                },
            );
            return;
        }
    };

    if let Err(error) = emit_incoming_history_session_start(&client, &history_session) {
        emit_desktop_transfer_event(
            &app,
            &DesktopTransferEvent {
                transfer_id,
                client_id: client.id,
                client_name: client.name,
                file_name: summarize_paths_for_ui(&paths),
                status: "error".to_string(),
                progress: 0.0,
                sent_bytes: 0,
                total_bytes: 0,
                is_archive: false,
                detail: Some(error),
            },
        );
        return;
    }

    let mut results = Vec::new();

    if should_split_gallery_media_paths(&paths) {
        for (index, path) in paths.into_iter().enumerate() {
            let next_transfer_id = if index == 0 {
                transfer_id.clone()
            } else {
                format!("{}-{}", transfer_id, index + 1)
            };
            let result = run_single_desktop_outbound_transfer(
                Arc::clone(&state),
                app.clone(),
                client.clone(),
                next_transfer_id,
                vec![path],
                Some(OutboundTransferItemContext {
                    session_id: history_session.session_id.clone(),
                    item_index: index,
                    item_count: history_session.item_count,
                }),
            )
            .await;
            results.push(result);
        }
    } else {
        let result = run_single_desktop_outbound_transfer(
            state,
            app.clone(),
            client.clone(),
            transfer_id,
            paths,
            Some(OutboundTransferItemContext {
                session_id: history_session.session_id.clone(),
                item_index: 0,
                item_count: history_session.item_count,
            }),
        )
        .await;
        results.push(result);
    }

    let history_entry = build_outbound_history_entry(&history_session, &client, &results);
    append_history_entry(&history_entry);
    emit_desktop_history_entry(&app, &history_entry);
}

struct ScopedPathCleanup {
    path: PathBuf,
}

impl ScopedPathCleanup {
    fn new(path: PathBuf) -> Self {
        Self { path }
    }
}

impl Drop for ScopedPathCleanup {
    fn drop(&mut self) {
        let result = if self.path.is_dir() {
            fs::remove_dir_all(&self.path)
        } else {
            fs::remove_file(&self.path)
        };

        if let Err(error) = result {
            warn!("cleanup for {} failed: {}", self.path.display(), error);
        }
    }
}

async fn run_single_desktop_outbound_transfer(
    state: Arc<WsState>,
    app: AppHandle,
    client: ConnectedClient,
    transfer_id: String,
    paths: Vec<PathBuf>,
    history_context: Option<OutboundTransferItemContext>,
) -> OutboundTransferResult {
    let item_index = history_context
        .as_ref()
        .map(|context| context.item_index)
        .unwrap_or(0);
    emit_desktop_transfer_event(
        &app,
        &DesktopTransferEvent {
            transfer_id: transfer_id.clone(),
            client_id: client.id.clone(),
            client_name: client.name.clone(),
            file_name: summarize_paths_for_ui(&paths),
            status: "preparing".to_string(),
            progress: 0.0,
            sent_bytes: 0,
            total_bytes: 0,
            is_archive: false,
            detail: Some("正在准备发送内容".to_string()),
        },
    );

    let transfer_id_for_prepare = transfer_id.clone();
    let prepared = match tauri::async_runtime::spawn_blocking(move || {
        prepare_desktop_transfer(&paths, &transfer_id_for_prepare)
    })
    .await
    {
        Ok(Ok(prepared)) => prepared,
        Ok(Err(error)) => {
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id.clone(),
                    client_name: client.name.clone(),
                    file_name: "拖拽内容".to_string(),
                    status: "error".to_string(),
                    progress: 0.0,
                    sent_bytes: 0,
                    total_bytes: 0,
                    is_archive: false,
                    detail: Some(error.clone()),
                },
            );
            return OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            };
        }
        Err(join_error) => {
            let message = format!("准备文件失败: {}", join_error);
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id.clone(),
                    client_name: client.name.clone(),
                    file_name: "拖拽内容".to_string(),
                    status: "error".to_string(),
                    progress: 0.0,
                    sent_bytes: 0,
                    total_bytes: 0,
                    is_archive: false,
                    detail: Some(message.clone()),
                },
            );
            return OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            };
        }
    };

    run_prepared_desktop_outbound_transfer(
        state,
        app,
        client,
        transfer_id,
        prepared,
        history_context,
    )
    .await
}

fn should_split_gallery_media_paths(paths: &[PathBuf]) -> bool {
    if paths.len() <= 1 {
        return false;
    }

    paths.iter().all(|path| {
        path.is_file()
            && is_gallery_media_mime(
                mime_guess::from_path(path)
                    .first_or_octet_stream()
                    .essence_str(),
            )
    })
}

fn is_gallery_media_mime(mime_type: &str) -> bool {
    mime_type.starts_with("image/") || mime_type.starts_with("video/")
}

fn normalize_history_kind_from_mime(mime_type: &str) -> String {
    if mime_type.starts_with("image/") {
        return "image".to_string();
    }
    if mime_type.starts_with("video/") {
        return "video".to_string();
    }
    "file".to_string()
}

fn build_outbound_history_session(
    paths: &[PathBuf],
    session_id: &str,
    cleanup_dir: Option<&Path>,
) -> Result<OutboundHistorySession, String> {
    let timestamp = chrono::Local::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false);
    let mut items = Vec::new();

    for (index, path) in paths.iter().enumerate() {
        let mime_type = mime_guess::from_path(path)
            .first_or_octet_stream()
            .essence_str()
            .to_string();
        let kind = normalize_history_kind_from_mime(&mime_type);
        let allow_preview = index < HISTORY_MEDIA_PREVIEW_LIMIT;
        let thumbnail_data_url = if allow_preview {
            build_path_thumbnail_data_url(path, &mime_type).ok()
        } else {
            None
        };
        let file_path = if cleanup_dir.is_some_and(|root| path.starts_with(root)) {
            cache_outbound_history_media(path, session_id, index)
                .map_err(|error| {
                    warn!("缓存桌面发送历史媒体失败: {}", error);
                    error
                })
                .ok()
        } else {
            Some(path.to_string_lossy().to_string())
        };

        items.push(HistoryTransferItem {
            kind,
            file_name: sanitize_file_name(path.file_name().and_then(|name| name.to_str()), "文件"),
            mime_type,
            thumbnail_data_url,
            file_path,
            saved_path: None,
            status: Some("pending".to_string()),
        });
    }

    let item_count = items.len().max(1);
    let media_count = items
        .iter()
        .filter(|item| item.kind == "image" || item.kind == "video")
        .count();
    let image_count = items.iter().filter(|item| item.kind == "image").count();
    let video_count = items.iter().filter(|item| item.kind == "video").count();
    let save_target = if media_count == item_count && item_count > 0 {
        "gallery".to_string()
    } else {
        "download".to_string()
    };

    let (kind, text) = if item_count == 1 {
        let item = items.first().ok_or_else(|| "发送内容为空".to_string())?;
        let prefix = match item.kind.as_str() {
            "image" => "图片",
            "video" => "视频",
            _ => "文件",
        };
        (
            item.kind.clone(),
            format!("[{}] {}", prefix, item.file_name),
        )
    } else if image_count == item_count {
        ("image".to_string(), format!("[图片] {} 张", item_count))
    } else if video_count == item_count {
        ("video".to_string(), format!("[视频] {} 个", item_count))
    } else if media_count == item_count {
        ("media".to_string(), format!("[媒体] {} 项", item_count))
    } else {
        ("file".to_string(), format!("[文件] {} 项", item_count))
    };

    Ok(OutboundHistorySession {
        session_id: session_id.to_string(),
        timestamp,
        kind,
        text,
        item_count,
        save_target,
        items,
    })
}

fn build_outbound_history_entry(
    session: &OutboundHistorySession,
    client: &ConnectedClient,
    results: &[OutboundTransferResult],
) -> HistoryEntry {
    let mut items = session.items.clone();
    for result in results {
        if let Some(item) = items.get_mut(result.item_index) {
            item.status = Some(if result.status == "success" {
                "success".to_string()
            } else {
                "failed".to_string()
            });
            if let Some(saved_path) = &result.saved_path {
                item.saved_path = Some(saved_path.clone());
            }
        }
    }

    let success_count = items
        .iter()
        .filter(|item| item.status.as_deref() == Some("success"))
        .count();
    let failed_count = items
        .iter()
        .filter(|item| item.status.as_deref() == Some("failed"))
        .count();
    let status = if failed_count == 0 {
        "success".to_string()
    } else if success_count == 0 {
        "failed".to_string()
    } else {
        "partial".to_string()
    };

    let first_item = items.first().cloned();
    HistoryEntry {
        timestamp: session.timestamp.clone(),
        text: session.text.clone(),
        client_ip: "desktop-outbound".to_string(),
        client_id: Some(client.id.clone()),
        client_name: Some(client.name.clone()),
        kind: Some(session.kind.clone()),
        file_name: first_item.as_ref().map(|item| item.file_name.clone()),
        image_path: None,
        thumbnail_data_url: first_item
            .as_ref()
            .and_then(|item| item.thumbnail_data_url.clone()),
        file_path: first_item.as_ref().and_then(|item| item.file_path.clone()),
        direction: Some("desktop_to_mobile".to_string()),
        status: Some(status),
        session_id: Some(session.session_id.clone()),
        item_count: Some(session.item_count),
        save_target: Some(session.save_target.clone()),
        items: Some(items),
    }
}

fn build_path_thumbnail_data_url(path: &Path, mime_type: &str) -> Result<String, String> {
    if mime_type.starts_with("image/") {
        let image = image::open(path).map_err(|e| format!("无法生成图片预览: {}", e))?;
        return build_thumbnail_data_url(&image);
    }

    if mime_type.starts_with("video/") {
        return build_video_thumbnail_data_url(path);
    }

    Err("当前文件类型不支持缩略图".to_string())
}

#[cfg(target_os = "macos")]
fn build_video_thumbnail_data_url(path: &Path) -> Result<String, String> {
    let preview_dir = outbound_transfer_temp_dir().join(format!(
        "history-preview-{}",
        chrono::Local::now().timestamp_millis()
    ));
    fs::create_dir_all(&preview_dir).map_err(|e| format!("无法创建视频预览目录: {}", e))?;

    let output = std::process::Command::new("/usr/bin/qlmanage")
        .args(["-t", "-s", "256", "-o"])
        .arg(&preview_dir)
        .arg(path)
        .output()
        .map_err(|e| format!("无法调用系统视频预览: {}", e))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        let _ = fs::remove_dir_all(&preview_dir);
        return Err(if stderr.is_empty() {
            "系统视频预览生成失败".to_string()
        } else {
            format!("系统视频预览生成失败: {}", stderr)
        });
    }

    let preview_path = WalkDir::new(&preview_dir)
        .into_iter()
        .filter_map(Result::ok)
        .find(|entry| {
            entry.file_type().is_file()
                && entry.path().extension().and_then(|ext| ext.to_str()) == Some("png")
        })
        .map(|entry| entry.path().to_path_buf())
        .ok_or_else(|| "系统未返回视频预览图".to_string())?;

    let bytes = fs::read(&preview_path).map_err(|e| format!("无法读取视频预览图: {}", e))?;
    let _ = fs::remove_dir_all(&preview_dir);
    Ok(format!(
        "data:image/png;base64,{}",
        BASE64_STANDARD.encode(bytes)
    ))
}

#[cfg(not(target_os = "macos"))]
fn build_video_thumbnail_data_url(_path: &Path) -> Result<String, String> {
    Err("当前系统不支持视频预览".to_string())
}

async fn run_prepared_desktop_outbound_transfer(
    state: Arc<WsState>,
    app: AppHandle,
    client: ConnectedClient,
    transfer_id: String,
    prepared: PreparedDesktopTransfer,
    history_context: Option<OutboundTransferItemContext>,
) -> OutboundTransferResult {
    let item_index = history_context
        .as_ref()
        .map(|context| context.item_index)
        .unwrap_or(0);
    emit_desktop_transfer_event(
        &app,
        &DesktopTransferEvent {
            transfer_id: transfer_id.clone(),
            client_id: client.id.clone(),
            client_name: client.name.clone(),
            file_name: prepared.file_name.clone(),
            status: "sending".to_string(),
            progress: 0.0,
            sent_bytes: 0,
            total_bytes: prepared.total_bytes,
            is_archive: prepared.is_archive,
            detail: Some(if prepared.is_archive {
                "文件夹/多文件已打包为 ZIP，开始发送".to_string()
            } else {
                "开始发送到手机".to_string()
            }),
        },
    );

    let (done_tx, done_rx) = oneshot::channel();
    if let Ok(mut pending) = state.pending_transfers.lock() {
        pending.insert(transfer_id.clone(), done_tx);
    } else {
        cleanup_temp_transfer(&prepared);
        emit_desktop_transfer_event(
            &app,
            &DesktopTransferEvent {
                transfer_id,
                client_id: client.id.clone(),
                client_name: client.name.clone(),
                file_name: prepared.file_name,
                status: "error".to_string(),
                progress: 0.0,
                sent_bytes: 0,
                total_bytes: prepared.total_bytes,
                is_archive: prepared.is_archive,
                detail: Some("发送状态初始化失败".to_string()),
            },
        );
        return OutboundTransferResult {
            item_index,
            status: "error".to_string(),
            saved_path: None,
        };
    }

    let start_message = serde_json::json!({
        "action": "incoming_file_start",
        "transfer_id": transfer_id.clone(),
        "file_name": prepared.file_name.clone(),
        "mime_type": prepared.mime_type.clone(),
        "size_bytes": prepared.total_bytes,
        "is_archive": prepared.is_archive,
        "history_session_id": history_context.as_ref().map(|context| context.session_id.clone()),
        "history_item_index": history_context.as_ref().map(|context| context.item_index),
        "history_item_count": history_context.as_ref().map(|context| context.item_count),
    });

    if client.sender.send(start_message.to_string()).is_err() {
        let _ = state
            .pending_transfers
            .lock()
            .map(|mut pending| pending.remove(&transfer_id));
        cleanup_temp_transfer(&prepared);
        emit_desktop_transfer_event(
            &app,
            &DesktopTransferEvent {
                transfer_id,
                client_id: client.id.clone(),
                client_name: client.name.clone(),
                file_name: prepared.file_name,
                status: "error".to_string(),
                progress: 0.0,
                sent_bytes: 0,
                total_bytes: prepared.total_bytes,
                is_archive: prepared.is_archive,
                detail: Some("手机连接已断开".to_string()),
            },
        );
        return OutboundTransferResult {
            item_index,
            status: "error".to_string(),
            saved_path: None,
        };
    }

    let mut file = match fs::File::open(&prepared.source_path) {
        Ok(file) => file,
        Err(error) => {
            let _ = state
                .pending_transfers
                .lock()
                .map(|mut pending| pending.remove(&transfer_id));
            cleanup_temp_transfer(&prepared);
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id.clone(),
                    client_name: client.name.clone(),
                    file_name: prepared.file_name,
                    status: "error".to_string(),
                    progress: 0.0,
                    sent_bytes: 0,
                    total_bytes: prepared.total_bytes,
                    is_archive: prepared.is_archive,
                    detail: Some(format!("无法读取待发送文件: {}", error)),
                },
            );
            return OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            };
        }
    };

    let mut sent_bytes = 0u64;
    let mut buffer = vec![0u8; DESKTOP_TO_MOBILE_CHUNK_BYTES];

    loop {
        let read = match file.read(&mut buffer) {
            Ok(read) => read,
            Err(error) => {
                let _ = state
                    .pending_transfers
                    .lock()
                    .map(|mut pending| pending.remove(&transfer_id));
                cleanup_temp_transfer(&prepared);
                emit_desktop_transfer_event(
                    &app,
                    &DesktopTransferEvent {
                        transfer_id,
                        client_id: client.id.clone(),
                        client_name: client.name.clone(),
                        file_name: prepared.file_name,
                        status: "error".to_string(),
                        progress: 0.0,
                        sent_bytes,
                        total_bytes: prepared.total_bytes,
                        is_archive: prepared.is_archive,
                        detail: Some(format!("发送过程中读取文件失败: {}", error)),
                    },
                );
                return OutboundTransferResult {
                    item_index,
                    status: "error".to_string(),
                    saved_path: None,
                };
            }
        };

        if read == 0 {
            break;
        }

        let chunk_message = serde_json::json!({
            "action": "incoming_file_chunk",
            "transfer_id": transfer_id.clone(),
            "chunk_base64": BASE64_STANDARD.encode(&buffer[..read]),
        });

        if client.sender.send(chunk_message.to_string()).is_err() {
            let _ = state
                .pending_transfers
                .lock()
                .map(|mut pending| pending.remove(&transfer_id));
            cleanup_temp_transfer(&prepared);
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id.clone(),
                    client_name: client.name.clone(),
                    file_name: prepared.file_name,
                    status: "error".to_string(),
                    progress: sent_bytes as f64 / prepared.total_bytes.max(1) as f64,
                    sent_bytes,
                    total_bytes: prepared.total_bytes,
                    is_archive: prepared.is_archive,
                    detail: Some("手机连接在发送过程中断开".to_string()),
                },
            );
            return OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            };
        }

        sent_bytes += read as u64;
        emit_desktop_transfer_event(
            &app,
            &DesktopTransferEvent {
                transfer_id: transfer_id.clone(),
                client_id: client.id.clone(),
                client_name: client.name.clone(),
                file_name: prepared.file_name.clone(),
                status: "sending".to_string(),
                progress: sent_bytes as f64 / prepared.total_bytes.max(1) as f64,
                sent_bytes,
                total_bytes: prepared.total_bytes,
                is_archive: prepared.is_archive,
                detail: Some("正在发送到手机".to_string()),
            },
        );
    }

    let complete_message = serde_json::json!({
        "action": "incoming_file_complete",
        "transfer_id": transfer_id.clone(),
    });

    if client.sender.send(complete_message.to_string()).is_err() {
        let _ = state
            .pending_transfers
            .lock()
            .map(|mut pending| pending.remove(&transfer_id));
        cleanup_temp_transfer(&prepared);
        emit_desktop_transfer_event(
            &app,
            &DesktopTransferEvent {
                transfer_id,
                client_id: client.id.clone(),
                client_name: client.name.clone(),
                file_name: prepared.file_name,
                status: "error".to_string(),
                progress: 1.0,
                sent_bytes: prepared.total_bytes,
                total_bytes: prepared.total_bytes,
                is_archive: prepared.is_archive,
                detail: Some("文件已传出，但手机未确认接收完成".to_string()),
            },
        );
        return OutboundTransferResult {
            item_index,
            status: "error".to_string(),
            saved_path: None,
        };
    }

    let final_result = timeout(
        Duration::from_secs(DESKTOP_TO_MOBILE_ACK_TIMEOUT_SECS),
        done_rx,
    )
    .await;
    let _ = state
        .pending_transfers
        .lock()
        .map(|mut pending| pending.remove(&transfer_id));
    cleanup_temp_transfer(&prepared);

    match final_result {
        Ok(Ok(Ok(saved_path))) => {
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id,
                    client_name: client.name,
                    file_name: prepared.file_name,
                    status: "success".to_string(),
                    progress: 1.0,
                    sent_bytes: prepared.total_bytes,
                    total_bytes: prepared.total_bytes,
                    is_archive: prepared.is_archive,
                    detail: Some(format!("已保存到手机：{}", saved_path)),
                },
            );
            OutboundTransferResult {
                item_index,
                status: "success".to_string(),
                saved_path: Some(saved_path),
            }
        }
        Ok(Ok(Err(error))) => {
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id,
                    client_name: client.name,
                    file_name: prepared.file_name,
                    status: "error".to_string(),
                    progress: 1.0,
                    sent_bytes: prepared.total_bytes,
                    total_bytes: prepared.total_bytes,
                    is_archive: prepared.is_archive,
                    detail: Some(error.clone()),
                },
            );
            OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            }
        }
        Ok(Err(_)) => {
            let message = "手机端确认回执异常".to_string();
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id,
                    client_name: client.name,
                    file_name: prepared.file_name,
                    status: "error".to_string(),
                    progress: 1.0,
                    sent_bytes: prepared.total_bytes,
                    total_bytes: prepared.total_bytes,
                    is_archive: prepared.is_archive,
                    detail: Some(message.clone()),
                },
            );
            OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            }
        }
        Err(_) => {
            let message = "等待手机保存结果超时".to_string();
            emit_desktop_transfer_event(
                &app,
                &DesktopTransferEvent {
                    transfer_id,
                    client_id: client.id,
                    client_name: client.name,
                    file_name: prepared.file_name,
                    status: "error".to_string(),
                    progress: 1.0,
                    sent_bytes: prepared.total_bytes,
                    total_bytes: prepared.total_bytes,
                    is_archive: prepared.is_archive,
                    detail: Some(message.clone()),
                },
            );
            OutboundTransferResult {
                item_index,
                status: "error".to_string(),
                saved_path: None,
            }
        }
    }
}

fn prepare_share_extension_transfer(
    paths: &[PathBuf],
    transfer_id: &str,
) -> Result<PreparedDesktopTransfer, String> {
    let existing_paths: Vec<PathBuf> = paths.iter().filter(|path| path.exists()).cloned().collect();
    if existing_paths.is_empty() {
        return Err("共享的文件不存在或已失效，请重新从 Finder 发起一次".to_string());
    }

    if existing_paths.len() != 1 || !existing_paths[0].is_file() {
        return prepare_desktop_transfer(&existing_paths, transfer_id);
    }

    let source_path = existing_paths[0].clone();
    let metadata =
        fs::metadata(&source_path).map_err(|e| format!("无法读取共享文件信息: {}", e))?;

    let file_name = sanitize_file_name(
        source_path.file_name().and_then(|name| name.to_str()),
        "file.bin",
    );
    let temp_dir = outbound_transfer_temp_dir();
    fs::create_dir_all(&temp_dir).map_err(|e| format!("无法创建共享暂存目录: {}", e))?;

    let staged_path = temp_dir.join(format!("{transfer_id}-{}", file_name));
    fs::copy(&source_path, &staged_path)
        .map_err(|e| format!("无法暂存共享文件，请检查 Finder 共享权限: {}", e))?;

    Ok(PreparedDesktopTransfer {
        source_path: staged_path.clone(),
        file_name,
        mime_type: mime_guess::from_path(&source_path)
            .first_or_octet_stream()
            .essence_str()
            .to_string(),
        total_bytes: metadata.len(),
        is_archive: false,
        cleanup_path: Some(staged_path),
    })
}

fn cleanup_temp_transfer(prepared: &PreparedDesktopTransfer) {
    if let Some(path) = prepared.cleanup_path.as_ref() {
        let _ = fs::remove_file(path);
    }
}

fn prepare_desktop_transfer(
    paths: &[PathBuf],
    transfer_id: &str,
) -> Result<PreparedDesktopTransfer, String> {
    let existing_paths: Vec<PathBuf> = paths.iter().filter(|path| path.exists()).cloned().collect();
    if existing_paths.is_empty() {
        return Err("拖入的文件不存在或已被移走".to_string());
    }

    if existing_paths.len() == 1 && existing_paths[0].is_file() {
        let path = existing_paths[0].clone();
        let metadata = fs::metadata(&path).map_err(|e| format!("无法读取文件信息: {}", e))?;

        let file_name =
            sanitize_file_name(path.file_name().and_then(|name| name.to_str()), "file.bin");

        return Ok(PreparedDesktopTransfer {
            source_path: path.clone(),
            file_name,
            mime_type: mime_guess::from_path(&path)
                .first_or_octet_stream()
                .essence_str()
                .to_string(),
            total_bytes: metadata.len(),
            is_archive: false,
            cleanup_path: None,
        });
    }

    let archive_file_name = if existing_paths.len() == 1 && existing_paths[0].is_dir() {
        let folder_name = sanitize_file_name(
            existing_paths[0].file_name().and_then(|name| name.to_str()),
            "folder",
        );
        if folder_name.to_ascii_lowercase().ends_with(".zip") {
            folder_name
        } else {
            format!("{folder_name}.zip")
        }
    } else {
        format!(
            "vibedrop-bundle-{}.zip",
            chrono::Local::now().format("%Y%m%d-%H%M%S")
        )
    };

    let temp_dir = outbound_transfer_temp_dir();
    fs::create_dir_all(&temp_dir).map_err(|e| format!("无法创建临时目录: {}", e))?;
    let archive_path = temp_dir.join(format!("{transfer_id}-{}", archive_file_name));
    create_zip_archive(&existing_paths, &archive_path)?;

    let archive_size = fs::metadata(&archive_path)
        .map_err(|e| format!("无法读取打包结果: {}", e))?
        .len();

    Ok(PreparedDesktopTransfer {
        source_path: archive_path.clone(),
        file_name: archive_file_name,
        mime_type: "application/zip".to_string(),
        total_bytes: archive_size,
        is_archive: true,
        cleanup_path: Some(archive_path),
    })
}

fn outbound_transfer_temp_dir() -> PathBuf {
    dirs_log_dir().join("outbound-transfer-temp")
}

fn outbound_history_media_dir() -> PathBuf {
    dirs_log_dir().join("history-media-cache")
}

fn cache_outbound_history_media(
    source_path: &Path,
    session_id: &str,
    item_index: usize,
) -> Result<String, String> {
    let cache_dir = outbound_history_media_dir().join(session_id);
    fs::create_dir_all(&cache_dir).map_err(|e| format!("无法创建历史媒体缓存目录: {}", e))?;

    let preferred_name = source_path
        .file_name()
        .and_then(|name| name.to_str())
        .map(|name| sanitize_file_name(Some(name), &format!("media-{}", item_index + 1)))
        .unwrap_or_else(|| format!("media-{}", item_index + 1));
    let cache_path = unique_path(&cache_dir, &preferred_name);
    fs::copy(source_path, &cache_path).map_err(|e| format!("无法缓存历史媒体文件: {}", e))?;

    Ok(cache_path.to_string_lossy().to_string())
}

fn create_zip_archive(source_paths: &[PathBuf], archive_path: &Path) -> Result<(), String> {
    let file = fs::File::create(archive_path).map_err(|e| format!("无法创建 ZIP 文件: {}", e))?;
    let mut zip = zip::ZipWriter::new(file);
    let file_options = FileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .unix_permissions(0o644);
    let dir_options = FileOptions::default()
        .compression_method(zip::CompressionMethod::Stored)
        .unix_permissions(0o755);

    for source in source_paths {
        let archive_root = PathBuf::from(sanitize_file_name(
            source.file_name().and_then(|name| name.to_str()),
            "item",
        ));
        append_path_to_zip(
            &mut zip,
            source,
            &archive_root,
            file_options.clone(),
            dir_options.clone(),
        )?;
    }

    zip.finish()
        .map_err(|e| format!("完成 ZIP 打包失败: {}", e))?;
    Ok(())
}

fn load_or_create_stable_id(path: &Path, prefix: &str) -> String {
    if let Ok(saved) = std::fs::read_to_string(path) {
        let saved = saved.trim().to_string();
        if !saved.is_empty() {
            return saved;
        }
    }

    let seed = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0);
    let generated = format!("{}-{:x}{:x}", prefix, seed, std::process::id());
    let _ = fs::create_dir_all(path.parent().unwrap_or_else(|| Path::new(".")));
    let _ = std::fs::write(path, &generated);
    generated
}

fn append_path_to_zip<W: Write + std::io::Seek>(
    zip: &mut zip::ZipWriter<W>,
    source: &Path,
    archive_root: &Path,
    file_options: FileOptions,
    dir_options: FileOptions,
) -> Result<(), String> {
    if source.is_file() {
        let entry_name = zip_entry_name(archive_root);
        zip.start_file(entry_name, file_options)
            .map_err(|e| format!("写入 ZIP 文件头失败: {}", e))?;
        let mut file = fs::File::open(source).map_err(|e| format!("无法读取文件: {}", e))?;
        std::io::copy(&mut file, zip).map_err(|e| format!("写入 ZIP 失败: {}", e))?;
        return Ok(());
    }

    let mut has_entries = false;
    for entry in WalkDir::new(source) {
        let entry = entry.map_err(|e| format!("遍历目录失败: {}", e))?;
        let current_path = entry.path();
        let relative = current_path
            .strip_prefix(source)
            .map_err(|e| format!("目录相对路径失败: {}", e))?;
        let archive_path = if relative.as_os_str().is_empty() {
            archive_root.to_path_buf()
        } else {
            archive_root.join(relative)
        };
        let archive_name = zip_entry_name(&archive_path);

        if entry.file_type().is_dir() {
            if !archive_name.is_empty() {
                zip.add_directory(format!("{archive_name}/"), dir_options)
                    .map_err(|e| format!("写入 ZIP 目录失败: {}", e))?;
                has_entries = true;
            }
            continue;
        }

        if entry.file_type().is_file() {
            zip.start_file(archive_name, file_options)
                .map_err(|e| format!("写入 ZIP 文件头失败: {}", e))?;
            let mut file =
                fs::File::open(current_path).map_err(|e| format!("无法读取文件: {}", e))?;
            std::io::copy(&mut file, zip).map_err(|e| format!("写入 ZIP 失败: {}", e))?;
            has_entries = true;
        }
    }

    if !has_entries {
        let archive_name = zip_entry_name(archive_root);
        zip.add_directory(format!("{archive_name}/"), dir_options)
            .map_err(|e| format!("写入空目录失败: {}", e))?;
    }

    Ok(())
}

fn zip_entry_name(path: &Path) -> String {
    path.components()
        .map(|component| component.as_os_str().to_string_lossy().to_string())
        .collect::<Vec<_>>()
        .join("/")
}

fn summarize_paths_for_ui(paths: &[PathBuf]) -> String {
    if paths.len() == 1 {
        return paths[0]
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("拖拽内容")
            .to_string();
    }

    format!("{} 个项目", paths.len())
}

fn finder_share_requests_dir() -> PathBuf {
    dirs_log_dir().join("finder-share-requests")
}

fn dirs_log_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".vibedrop")
}

fn window_state_path() -> PathBuf {
    dirs_log_dir().join("window-state.json")
}

fn normalize_window_state(state: WindowState) -> Option<WindowState> {
    if !state.width.is_finite() || !state.height.is_finite() {
        return None;
    }

    let width = state.width.max(MIN_WINDOW_WIDTH);
    let height = state.height.max(MIN_WINDOW_HEIGHT);

    Some(WindowState { width, height })
}

fn load_window_state() -> Option<WindowState> {
    let state_path = window_state_path();
    let Ok(raw) = std::fs::read_to_string(state_path) else {
        return None;
    };

    let Ok(state) = serde_json::from_str::<WindowState>(&raw) else {
        return None;
    };

    normalize_window_state(state)
}

fn save_window_state(state: &WindowState) -> Result<(), String> {
    let Some(normalized) = normalize_window_state(state.clone()) else {
        return Err("窗口尺寸无效".to_string());
    };

    let state_path = window_state_path();
    if let Some(parent) = state_path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }

    let payload = serde_json::to_string_pretty(&normalized).map_err(|e| e.to_string())?;
    fs::write(state_path, payload).map_err(|e| e.to_string())
}

fn history_log_paths() -> Vec<PathBuf> {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    let home = PathBuf::from(home);

    vec![
        home.join(".vibedrop").join("history.jsonl"),
        home.join(".voicedrop").join("history.jsonl"),
    ]
}

fn append_history_entry(entry: &HistoryEntry) {
    let log_path = dirs_log_dir().join("history.jsonl");
    match OpenOptions::new().create(true).append(true).open(&log_path) {
        Ok(mut file) => {
            if let Ok(json) = serde_json::to_string(&entry) {
                let _ = writeln!(file, "{}", json);
            }
        }
        Err(e) => error!("无法写入日志: {}", e),
    }
}

fn decode_base64_bytes(encoded: &str) -> Result<Vec<u8>, String> {
    BASE64_STANDARD
        .decode(encoded)
        .map_err(|e| format!("数据解码失败: {}", e))
}

fn file_extension(file_name: Option<&str>, mime_type: Option<&str>) -> &'static str {
    if let Some(name) = file_name {
        if let Some(ext) = Path::new(name).extension().and_then(|ext| ext.to_str()) {
            let ext = ext.to_ascii_lowercase();
            return match ext.as_str() {
                "png" => "png",
                "jpg" | "jpeg" => "jpg",
                "gif" => "gif",
                "webp" => "webp",
                _ => "png",
            };
        }
    }

    match mime_type.unwrap_or_default() {
        "image/jpeg" => "jpg",
        "image/gif" => "gif",
        "image/webp" => "webp",
        _ => "png",
    }
}

fn sanitize_file_name(file_name: Option<&str>, fallback_name: &str) -> String {
    let source = file_name
        .and_then(|name| Path::new(name).file_name())
        .and_then(|name| name.to_str())
        .unwrap_or(fallback_name);

    let sanitized: String = source
        .chars()
        .map(|ch| {
            if ch == '/' || ch == '\\' || ch == ':' || ch.is_control() {
                '_'
            } else {
                ch
            }
        })
        .collect();

    let trimmed = sanitized.trim_matches('_');
    if trimmed.is_empty() {
        fallback_name.to_string()
    } else {
        trimmed.to_string()
    }
}

fn preferred_image_name(file_name: Option<&str>, mime_type: Option<&str>) -> String {
    let sanitized = sanitize_file_name(file_name, "image.png");
    if Path::new(&sanitized).extension().is_some() {
        return sanitized;
    }

    format!("{}.{}", sanitized, file_extension(file_name, mime_type))
}

fn unique_path(dir: &Path, preferred_name: &str) -> PathBuf {
    let preferred_path = dir.join(preferred_name);
    if !preferred_path.exists() {
        return preferred_path;
    }

    let stem = Path::new(preferred_name)
        .file_stem()
        .and_then(|stem| stem.to_str())
        .unwrap_or("file");
    let ext = Path::new(preferred_name)
        .extension()
        .and_then(|ext| ext.to_str());

    for index in 1.. {
        let candidate = if let Some(ext) = ext {
            format!("{} ({index}).{}", stem, ext)
        } else {
            format!("{} ({index})", stem)
        };
        let path = dir.join(candidate);
        if !path.exists() {
            return path;
        }
    }

    unreachable!()
}

fn downloads_root_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join("Downloads")
}

fn desktop_inbox_dir() -> PathBuf {
    downloads_root_dir().join(DESKTOP_INBOX_DIR_NAME)
}

fn incoming_download_temp_dir() -> PathBuf {
    dirs_log_dir().join("incoming-download-temp")
}

fn sanitize_transfer_id(value: &str) -> String {
    let sanitized: String = value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
                ch
            } else {
                '_'
            }
        })
        .collect();
    let trimmed = sanitized.trim_matches('_');
    if trimmed.is_empty() {
        "transfer".to_string()
    } else {
        trimmed.to_string()
    }
}

fn incoming_download_part_path(transfer_id: &str) -> PathBuf {
    incoming_download_temp_dir().join(format!("{}.part", sanitize_transfer_id(transfer_id)))
}

fn incoming_download_meta_path(transfer_id: &str) -> PathBuf {
    incoming_download_temp_dir().join(format!("{}.json", sanitize_transfer_id(transfer_id)))
}

fn begin_downloaded_file_transfer(
    transfer_id: &str,
    file_name: Option<&str>,
    mime_type: Option<&str>,
    size_bytes: u64,
    client_id: Option<&str>,
    client_name: Option<&str>,
) -> Result<(), String> {
    let temp_dir = incoming_download_temp_dir();
    fs::create_dir_all(&temp_dir).map_err(|e| format!("无法创建接收临时目录: {}", e))?;

    let meta = IncomingDesktopTransferMeta {
        file_name: sanitize_file_name(file_name, "file.bin"),
        mime_type: mime_type.unwrap_or("application/octet-stream").to_string(),
        size_bytes,
        client_id: client_id.map(str::to_string),
        client_name: client_name.map(str::to_string),
    };

    let meta_payload =
        serde_json::to_string(&meta).map_err(|e| format!("无法序列化接收元数据: {}", e))?;
    fs::write(incoming_download_meta_path(transfer_id), meta_payload)
        .map_err(|e| format!("无法写入接收元数据: {}", e))?;
    fs::write(incoming_download_part_path(transfer_id), [])
        .map_err(|e| format!("无法创建接收临时文件: {}", e))?;
    Ok(())
}

fn append_downloaded_file_transfer_chunk(
    transfer_id: &str,
    chunk_base64: &str,
) -> Result<(), String> {
    let chunk = decode_base64_bytes(chunk_base64)?;
    let part_path = incoming_download_part_path(transfer_id);
    if !part_path.exists() {
        return Err("接收中的临时文件不存在".to_string());
    }

    let mut file = OpenOptions::new()
        .append(true)
        .open(&part_path)
        .map_err(|e| format!("无法写入接收临时文件: {}", e))?;
    file.write_all(&chunk)
        .map_err(|e| format!("写入接收临时文件失败: {}", e))
}

fn move_file_with_fallback(source: &Path, destination: &Path) -> Result<(), String> {
    match fs::rename(source, destination) {
        Ok(()) => Ok(()),
        Err(rename_error) => {
            fs::copy(source, destination).map_err(|copy_error| {
                format!(
                    "无法移动文件: {}；回退复制也失败: {}",
                    rename_error, copy_error
                )
            })?;
            fs::remove_file(source)
                .map_err(|e| format!("复制后无法清理临时文件: {}", e))?;
            Ok(())
        }
    }
}

fn finish_downloaded_file_transfer(
    transfer_id: &str,
) -> Result<(IncomingDesktopTransferMeta, String, String), String> {
    let meta_path = incoming_download_meta_path(transfer_id);
    let part_path = incoming_download_part_path(transfer_id);
    if !meta_path.exists() || !part_path.exists() {
        return Err("接收临时文件已失效".to_string());
    }

    let meta_raw =
        fs::read_to_string(&meta_path).map_err(|e| format!("无法读取接收元数据: {}", e))?;
    let meta: IncomingDesktopTransferMeta = serde_json::from_str(&meta_raw)
        .map_err(|e| format!("接收元数据无效: {}", e))?;

    let actual_size = fs::metadata(&part_path)
        .map_err(|e| format!("无法读取接收临时文件信息: {}", e))?
        .len();
    if actual_size != meta.size_bytes {
        return Err(format!(
            "文件大小校验失败：预期 {} 字节，实际 {} 字节",
            meta.size_bytes, actual_size
        ));
    }

    let dir = desktop_inbox_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("无法创建 {}: {}", DESKTOP_INBOX_DIR_NAME, e))?;

    let file_path = unique_path(&dir, &meta.file_name);
    let final_file_name = file_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("file.bin")
        .to_string();

    move_file_with_fallback(&part_path, &file_path)?;
    let _ = fs::remove_file(&meta_path);

    Ok((meta, final_file_name, file_path.to_string_lossy().to_string()))
}

fn cancel_downloaded_file_transfer(transfer_id: &str) -> Result<(), String> {
    let part_path = incoming_download_part_path(transfer_id);
    let meta_path = incoming_download_meta_path(transfer_id);
    fs::remove_file(part_path).ok();
    fs::remove_file(meta_path).ok();
    Ok(())
}

fn save_received_image(
    image_bytes: &[u8],
    file_name: Option<&str>,
    mime_type: Option<&str>,
) -> Result<(String, String), String> {
    let image_dir = dirs_log_dir().join("received-images");
    fs::create_dir_all(&image_dir).map_err(|e| format!("无法创建图片目录: {}", e))?;
    let image_path = unique_path(&image_dir, &preferred_image_name(file_name, mime_type));
    let final_file_name = image_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("image.png")
        .to_string();

    fs::write(&image_path, image_bytes).map_err(|e| format!("无法保存原图: {}", e))?;

    Ok((final_file_name, image_path.to_string_lossy().to_string()))
}

fn save_downloaded_file(
    file_base64: &str,
    file_name: Option<&str>,
) -> Result<(String, String), String> {
    let file_bytes = decode_base64_bytes(file_base64)?;
    let dir = desktop_inbox_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("无法创建 {}: {}", DESKTOP_INBOX_DIR_NAME, e))?;

    let preferred_name = sanitize_file_name(file_name, "file.bin");
    let file_path = unique_path(&dir, &preferred_name);
    let final_file_name = file_path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or("file.bin")
        .to_string();

    fs::write(&file_path, file_bytes).map_err(|e| format!("无法保存文件: {}", e))?;
    Ok((final_file_name, file_path.to_string_lossy().to_string()))
}

fn build_thumbnail_data_url(image: &image::DynamicImage) -> Result<String, String> {
    let thumb = image.thumbnail(220, 220);
    let mut cursor = Cursor::new(Vec::new());
    thumb
        .write_to(&mut cursor, image::ImageFormat::Png)
        .map_err(|e| format!("无法生成缩略图: {}", e))?;

    let encoded = BASE64_STANDARD.encode(cursor.into_inner());
    Ok(format!("data:image/png;base64,{}", encoded))
}

fn image_to_clipboard_data(image: image::DynamicImage) -> Result<ImageData<'static>, String> {
    let image = image.to_rgba8();

    let width = usize::try_from(image.width()).map_err(|_| "图片宽度无效".to_string())?;
    let height = usize::try_from(image.height()).map_err(|_| "图片高度无效".to_string())?;

    Ok(ImageData {
        width,
        height,
        bytes: Cow::Owned(image.into_raw()),
    })
}

fn prepare_saved_image(
    image_base64: &str,
    file_name: Option<&str>,
    mime_type: Option<&str>,
) -> Result<SavedImage, String> {
    let image_bytes = decode_base64_bytes(image_base64)?;
    let (final_file_name, image_path) = save_received_image(&image_bytes, file_name, mime_type)?;
    let image =
        image::load_from_memory(&image_bytes).map_err(|e| format!("无法读取图片数据: {}", e))?;
    let thumbnail_data_url = build_thumbnail_data_url(&image)?;
    let clipboard_image = image_to_clipboard_data(image)?;

    Ok(SavedImage {
        file_name: final_file_name,
        image_path,
        thumbnail_data_url,
        clipboard_image,
    })
}

fn set_clipboard_image(image_data: ImageData<'static>) -> Result<(), String> {
    let mut clipboard = arboard::Clipboard::new().map_err(|e| format!("无法访问剪贴板: {}", e))?;
    clipboard
        .set_image(image_data)
        .map_err(|e| format!("无法写入图片到剪贴板: {}", e))
}

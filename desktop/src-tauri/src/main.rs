#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use axum::{
    extract::{
        ConnectInfo,
        ws::{Message, WebSocket, WebSocketUpgrade},
        Path as AxumPath, State as AxumState,
    },
    http::StatusCode,
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
use std::net::SocketAddr;
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

#[derive(Debug, Serialize, Deserialize, Clone)]
struct HistoryEntry {
    timestamp: String,
    text: String,
    client_ip: String,
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
    if paths.is_empty() {
        return Err("没有可发送的文件".to_string());
    }

    let path_bufs: Vec<PathBuf> = paths
        .into_iter()
        .map(PathBuf::from)
        .filter(|path| path.exists())
        .collect();

    if path_bufs.is_empty() {
        return Err("拖入的路径不存在或不可访问".to_string());
    }

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
            path_bufs,
        )
        .await;
    });

    Ok(DesktopTransferLaunch {
        transfer_id,
        client_id: client_id.clone(),
        client_name,
    })
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

async fn discover_handler(AxumState(state): AxumState<ServerState>) -> Json<DiscoverResponse> {
    Json(DiscoverResponse {
        kind: "desktop".to_string(),
        server_id: state.app.server_id,
        hostname: state.app.hostname,
        ip: state.app.ip,
        port: state.app.port,
        protocol_version: DISCOVERY_PROTOCOL_VERSION,
    })
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
    AxumPath(request_id): AxumPath<String>,
) -> Json<PairRequestStatusResponse> {
    let status = pair_request_status_snapshot(&state.ws, &request_id);
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
            ip: Some(state.app.ip),
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

const MIN_WINDOW_WIDTH: f64 = 320.0;
const MIN_WINDOW_HEIGHT: f64 = 420.0;
const DESKTOP_TO_MOBILE_MAX_BYTES: u64 = 64 * 1024 * 1024;
const DESKTOP_TO_MOBILE_CHUNK_BYTES: usize = 192 * 1024;
const DESKTOP_TO_MOBILE_ACK_TIMEOUT_SECS: u64 = 90;
const PAIR_REQUEST_TTL_SECS: i64 = 120;
const DISCOVERY_PROTOCOL_VERSION: u16 = 1;

impl HistoryEntry {
    fn text_entry(text: &str, client_ip: &str) -> Self {
        Self {
            timestamp: chrono::Local::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false),
            text: text.to_string(),
            client_ip: client_ip.to_string(),
            kind: None,
            file_name: None,
            image_path: None,
            thumbnail_data_url: None,
            file_path: None,
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

    std::thread::spawn(move || {
        let mut enigo = Enigo::new(&Settings::default())
            .expect("无法初始化键盘模拟。请在系统设置中授予辅助功能权限。");

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();

        rt.block_on(async {
            while let Some(req) = input_rx.recv().await {
                let reply = match req.action {
                    InputAction::TypeText(text) => {
                        enigo.text(&text).map_err(|e| format!("{:?}", e))
                    }
                    InputAction::TypeTextAndEnter(text) => match enigo.text(&text) {
                        Ok(()) => enigo
                            .key(Key::Return, Direction::Click)
                            .map_err(|e| format!("文字已发送，但回车失败: {:?}", e)),
                        Err(e) => Err(format!("{:?}", e)),
                    },
                    InputAction::PressEnter => enigo
                        .key(Key::Return, Direction::Click)
                        .map_err(|e| format!("{:?}", e)),
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
    std::thread::spawn(move || {
        let mut clipboard = arboard::Clipboard::new().expect("无法初始化剪贴板");
        let mut last_text = clipboard.get_text().unwrap_or_default();
        info!("剪贴板监听已启动");

        loop {
            std::thread::sleep(std::time::Duration::from_millis(500));
            if let Ok(current) = clipboard.get_text() {
                if current != last_text && !current.is_empty() {
                    last_text = current.clone();
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
            let app = Router::new()
                .route("/ws", get(ws_handler))
                .route("/discover", get(discover_handler))
                .route("/pair/request", post(request_pairing_handler))
                .route("/pair/status/{request_id}", get(pair_status_handler))
                .route("/share-extension/paths", post(local_share_extension_handler))
                .fallback_service(ServeDir::new(ws_static_dir))
                .with_state(server_state);

            let http_addr = format!("0.0.0.0:{}", ws_port);
            info!("HTTP 服务启动在 {}", http_addr);
            let listener = tokio::net::TcpListener::bind(&http_addr)
                .await
                .unwrap_or_else(|_| panic!("无法绑定端口 {}", ws_port));
            axum::serve(listener, app.into_make_service_with_connect_info::<SocketAddr>())
                .await
                .unwrap();
        });
    });

    // 启动 Tauri APP
    tauri::Builder::default()
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

                WebviewWindowBuilder::from_config(app, &window_config)?.build()?;
            }

            // 把 app_handle 传给 WebSocket 线程
            if let Ok(mut h) = app_handle.lock() {
                *h = Some(app.handle().clone());
            }
            // 创建系统托盘菜单（类似 KDE Connect 风格）
            let title = MenuItem::with_id(app, "title", "VibeDrop - 运行中", false, None::<&str>)?;
            let addr_label = MenuItem::with_id(
                app,
                "addr",
                &format!("📡 {}:{}", ip, port),
                false,
                None::<&str>,
            )?;
            let pin_label =
                MenuItem::with_id(app, "pin", &format!("🔑 PIN: {}", pin), false, None::<&str>)?;
            let sep1 = PredefinedMenuItem::separator(app)?;
            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let sep2 = PredefinedMenuItem::separator(app)?;
            let quit = MenuItem::with_id(app, "quit", "退出 VibeDrop", true, None::<&str>)?;

            let menu = Menu::with_items(
                app,
                &[&title, &addr_label, &pin_label, &sep1, &show, &sep2, &quit],
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
                .tooltip(&format!("VibeDrop - {}:{}", ip, port))
                .on_menu_event(|app, event| match event.id.as_ref() {
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
    info!("新的 WebSocket 连接");
    let mut authenticated = false;
    let session_id = state.session_counter.fetch_add(1, Ordering::Relaxed);
    let mut authenticated_client: Option<(String, u64)> = None;
    let mut current_receives_clipboard = false;

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
                                                    base_id,
                                                    name: client_name.clone(),
                                                    can_receive_files,
                                                    receives_clipboard,
                                                    sender: outgoing_tx.clone(),
                                                },
                                            );
                                        }
                                        authenticated_client = Some((client_id, session_id));
                                        broadcast_connected_clients(&state);

                                        info!("客户端认证成功: {} ({})", client_name, device_role);
                                        let reply = ServerMessage {
                                            status: "ok".to_string(),
                                            hostname: Some(state.hostname.clone()),
                                            error: None,
                                        };
                                        let _ = ws_sender.send(Message::Text(serde_json::to_string(&reply).unwrap().into())).await;
                                    } else {
                                        warn!("PIN 错误");
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
                                    if let Ok(mut pending) = state.pending_transfers.lock() {
                                        if let Some(done_tx) = pending.remove(transfer_id) {
                                            let message = client_msg
                                                .error
                                                .clone()
                                                .unwrap_or_else(|| "手机端保存失败".to_string());
                                            let _ = done_tx.send(Err(message));
                                        }
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
                                    let history_entry = HistoryEntry::text_entry(text_content, "ws-client");
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
                                            kind: Some("image".to_string()),
                                            file_name: Some(saved_image.file_name.clone()),
                                            image_path: Some(saved_image.image_path.clone()),
                                            thumbnail_data_url: Some(saved_image.thumbnail_data_url.clone()),
                                            file_path: None,
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
                                            kind: Some("file".to_string()),
                                            file_name: Some(saved_file_name),
                                            image_path: None,
                                            thumbnail_data_url: None,
                                            file_path: Some(saved_file_path),
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
    info!("WebSocket 连接关闭");
}

fn connected_clients_snapshot(state: &Arc<WsState>) -> Vec<ConnectedClientInfo> {
    let mut clients = state
        .clients
        .lock()
        .map(|clients| {
            let mut grouped = std::collections::HashMap::<String, ConnectedClientInfo>::new();
            for client in clients.values() {
                let entry = grouped
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

        let share_paths = request
            .paths
            .iter()
            .map(PathBuf::from)
            .collect::<Vec<_>>();
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
            share_paths,
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

async fn run_desktop_outbound_transfer(
    state: Arc<WsState>,
    app: AppHandle,
    client: ConnectedClient,
    transfer_id: String,
    paths: Vec<PathBuf>,
) {
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
                    detail: Some(error),
                },
            );
            return;
        }
        Err(join_error) => {
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
                    detail: Some(format!("准备文件失败: {}", join_error)),
                },
            );
            return;
        }
    };

    run_prepared_desktop_outbound_transfer(state, app, client, transfer_id, prepared).await;
}

async fn run_prepared_desktop_outbound_transfer(
    state: Arc<WsState>,
    app: AppHandle,
    client: ConnectedClient,
    transfer_id: String,
    prepared: PreparedDesktopTransfer,
) {

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
        return;
    }

    let start_message = serde_json::json!({
        "action": "incoming_file_start",
        "transfer_id": transfer_id.clone(),
        "file_name": prepared.file_name.clone(),
        "mime_type": prepared.mime_type.clone(),
        "size_bytes": prepared.total_bytes,
        "is_archive": prepared.is_archive,
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
        return;
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
            return;
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
                return;
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
            return;
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
        return;
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
                    detail: Some(error),
                },
            );
        }
        Ok(Err(_)) => {
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
                    detail: Some("手机端确认回执异常".to_string()),
                },
            );
        }
        Err(_) => {
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
                    detail: Some("等待手机保存结果超时".to_string()),
                },
            );
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
    let metadata = fs::metadata(&source_path).map_err(|e| format!("无法读取共享文件信息: {}", e))?;
    if metadata.len() > DESKTOP_TO_MOBILE_MAX_BYTES {
        return Err(format!(
            "文件过大，请控制在 {}MB 以内",
            DESKTOP_TO_MOBILE_MAX_BYTES / (1024 * 1024)
        ));
    }

    let file_name =
        sanitize_file_name(source_path.file_name().and_then(|name| name.to_str()), "file.bin");
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
        if metadata.len() > DESKTOP_TO_MOBILE_MAX_BYTES {
            return Err(format!(
                "文件过大，请控制在 {}MB 以内",
                DESKTOP_TO_MOBILE_MAX_BYTES / (1024 * 1024)
            ));
        }

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

    if archive_size > DESKTOP_TO_MOBILE_MAX_BYTES {
        let _ = fs::remove_file(&archive_path);
        return Err(format!(
            "打包后的 ZIP 过大，请控制在 {}MB 以内",
            DESKTOP_TO_MOBILE_MAX_BYTES / (1024 * 1024)
        ));
    }

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

fn downloads_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join("Downloads")
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
    let dir = downloads_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("无法创建下载目录: {}", e))?;

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

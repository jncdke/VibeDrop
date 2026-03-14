use axum::{
    Router,
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    response::IntoResponse,
    routing::get,
};

use enigo::{Enigo, Keyboard, Settings};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use tauri::{
    AppHandle, Emitter, Manager,
    menu::{Menu, MenuItem, PredefinedMenuItem},
    tray::TrayIconBuilder,
};
use tokio::sync::{broadcast, mpsc, oneshot};
use tower_http::services::ServeDir;
use tracing::{error, info, warn};

// ---- 数据结构 ----

#[derive(Debug, Deserialize)]
struct ClientMessage {
    action: String,
    #[serde(default)]
    pin: Option<String>,
    #[serde(default)]
    text: Option<String>,
}

#[derive(Debug, Serialize)]
struct ServerMessage {
    status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    hostname: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
struct HistoryEntry {
    timestamp: String,
    text: String,
    client_ip: String,
}

struct TypeRequest {
    text: String,
    reply: oneshot::Sender<Result<(), String>>,
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
fn get_service_info(state: tauri::State<'_, AppState>) -> ServiceInfo {
    ServiceInfo {
        hostname: state.hostname.clone(),
        ip: state.ip.clone(),
        port: state.port,
        pin: state.pin.clone(),
        running: true,
    }
}

// ---- 应用状态 ----

struct AppState {
    pin: String,
    hostname: String,
    ip: String,
    port: u16,
}

// ---- 主函数 ----

#[cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
fn main() {
    tracing_subscriber::fmt::init();

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
        let seed = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().subsec_nanos();
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
    let (type_tx, mut type_rx) = mpsc::channel::<TypeRequest>(32);

    std::thread::spawn(move || {
        let mut enigo = Enigo::new(&Settings::default())
            .expect("无法初始化键盘模拟。请在系统设置中授予辅助功能权限。");

        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();

        rt.block_on(async {
            while let Some(req) = type_rx.recv().await {
                let result = enigo.text(&req.text);
                let reply = match result {
                    Ok(_) => Ok(()),
                    Err(e) => Err(format!("{:?}", e)),
                };
                let _ = req.reply.send(reply);
            }
        });
    });

    let state = AppState {
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
    let ws_type_tx = type_tx.clone();
    let app_handle: Arc<std::sync::Mutex<Option<AppHandle>>> = Arc::new(std::sync::Mutex::new(None));
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
                    info!("剪贴板变化: {}...", &last_text.chars().take(50).collect::<String>());
                    let _ = clipboard_tx_clone.send(last_text.clone());
                }
            }
        }
    });

    // 启动 HTTP 服务器（后台）
    let ws_port = port;
    std::thread::spawn(move || {
        let rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .unwrap();

        rt.block_on(async {
            let shared = Arc::new(WsState {
                pin: ws_pin,
                hostname: ws_hostname,
                type_tx: ws_type_tx,
                app_handle: app_handle_ws,
                clipboard_tx,
            });

            let shared_clone = Arc::clone(&shared);
            let app = Router::new()
                .route("/ws", get(move |ws| ws_handler(ws, shared_clone)))
                .fallback_service(ServeDir::new(ws_static_dir));

            let http_addr = format!("0.0.0.0:{}", ws_port);
            info!("HTTP 服务启动在 {}", http_addr);
            let listener = tokio::net::TcpListener::bind(&http_addr)
                .await
                .unwrap_or_else(|_| panic!("无法绑定端口 {}", ws_port));
            axum::serve(listener, app).await.unwrap();
        });
    });

    // 启动 Tauri APP
    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(state)
        .invoke_handler(tauri::generate_handler![get_service_info, check_accessibility, open_accessibility_settings])
        .setup(move |app| {
            // 把 app_handle 传给 WebSocket 线程
            if let Ok(mut h) = app_handle.lock() {
                *h = Some(app.handle().clone());
            }
            // 创建系统托盘菜单（类似 KDE Connect 风格）
            let title = MenuItem::with_id(app, "title", "VibeDrop - 运行中", false, None::<&str>)?;
            let addr_label = MenuItem::with_id(
                app, "addr",
                &format!("📡 {}:{}", ip, port),
                false, None::<&str>
            )?;
            let pin_label = MenuItem::with_id(
                app, "pin",
                &format!("🔑 PIN: {}", pin),
                false, None::<&str>
            )?;
            let sep1 = PredefinedMenuItem::separator(app)?;
            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let sep2 = PredefinedMenuItem::separator(app)?;
            let quit = MenuItem::with_id(app, "quit", "退出 VibeDrop", true, None::<&str>)?;

            let menu = Menu::with_items(app, &[
                &title, &addr_label, &pin_label,
                &sep1, &show, &sep2, &quit
            ])?;

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
                .on_menu_event(|app, event| {
                    match event.id.as_ref() {
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
                    }
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
    type_tx: mpsc::Sender<TypeRequest>,
    app_handle: Arc<std::sync::Mutex<Option<AppHandle>>>,
    clipboard_tx: broadcast::Sender<String>,
}

async fn ws_handler(ws: WebSocketUpgrade, state: Arc<WsState>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

async fn handle_socket(socket: WebSocket, state: Arc<WsState>) {
    info!("新的 WebSocket 连接");
    let mut authenticated = false;

    // 拆分 WebSocket 为 sender/receiver，以便同时接收客户端消息和广播剪贴板
    let (mut ws_sender, mut ws_receiver) = socket.split();
    let mut clipboard_rx = state.clipboard_tx.subscribe();

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
                                        info!("客户端认证成功");
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

                            "type" => {
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
                                    info!("收到文字: {}", text_content);
                                    log_history(text_content, "ws-client");

                                    let (reply_tx, reply_rx) = oneshot::channel();
                                    let req = TypeRequest {
                                        text: text_content.clone(),
                                        reply: reply_tx,
                                    };

                                    if state.type_tx.send(req).await.is_ok() {
                                        match reply_rx.await {
                                            Ok(Ok(())) => {
                                                info!("文字已输入");

                                                // 通知 Tauri 前端窗口
                                                if let Ok(guard) = state.app_handle.lock() {
                                                    if let Some(handle) = guard.as_ref() {
                                                        let _ = handle.emit("text-received", serde_json::json!({
                                                            "text": text_content,
                                                            "timestamp": chrono::Local::now().to_rfc3339()
                                                        }));
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
                                                    error: Some(format!("键盘输入失败: {}", e)),
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
            // 接收剪贴板广播，转发给已认证的客户端
            clipboard_text = clipboard_rx.recv() => {
                if authenticated {
                    if let Ok(text) = clipboard_text {
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
    info!("WebSocket 连接关闭");
}

fn dirs_log_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".vibedrop")
}

fn log_history(text: &str, client_ip: &str) {
    let entry = HistoryEntry {
        timestamp: chrono::Local::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false),
        text: text.to_string(),
        client_ip: client_ip.to_string(),
    };

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

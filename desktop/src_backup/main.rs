use axum::{
    Router,
    extract::ws::{Message, WebSocket, WebSocketUpgrade},
    response::IntoResponse,
    routing::get,
};
use clap::Parser;
use enigo::{Enigo, Keyboard, Settings};
use serde::{Deserialize, Serialize};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::{mpsc, oneshot};
use tower_http::services::ServeDir;
use tracing::{error, info, warn};

/// VoiceDrop — 从安卓手机发送文字到 Mac 电脑
#[derive(Parser, Debug)]
#[command(name = "voicedrop", version, about)]
struct Args {
    /// 监听端口
    #[arg(short, long, default_value_t = 9001)]
    port: u16,

    /// PIN 认证码（客户端连接时需要提供）
    #[arg(long)]
    pin: String,
}

/// 客户端发来的消息
#[derive(Debug, Deserialize)]
struct ClientMessage {
    action: String,
    #[serde(default)]
    pin: Option<String>,
    #[serde(default)]
    text: Option<String>,
}

/// 服务端回复的消息
#[derive(Debug, Serialize)]
struct ServerMessage {
    status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    hostname: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

/// 历史记录条目
#[derive(Debug, Serialize)]
struct HistoryEntry {
    timestamp: String,
    text: String,
    client_ip: String,
}

/// 发送给键盘输入线程的请求
struct TypeRequest {
    text: String,
    reply: oneshot::Sender<Result<(), String>>,
}

/// 应用共享状态
struct AppState {
    pin: String,
    hostname: String,
    type_tx: mpsc::Sender<TypeRequest>,
}

#[tokio::main]
async fn main() {
    // 初始化日志
    tracing_subscriber::fmt::init();

    // 解析命令行参数
    let args = Args::parse();

    // 获取主机名
    let hostname = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "Unknown Mac".to_string());

    // 确保日志目录存在
    let log_dir = dirs_log_dir();
    fs::create_dir_all(&log_dir).expect("无法创建日志目录 ~/.voicedrop/");

    // 创建键盘输入的 channel
    // Enigo 包含 macOS CoreGraphics 指针，不是 Send 的，
    // 所以我们在一个专用线程里运行 Enigo，通过 channel 发送请求
    let (type_tx, mut type_rx) = mpsc::channel::<TypeRequest>(32);

    // 在专用线程中运行 Enigo
    std::thread::spawn(move || {
        let mut enigo = Enigo::new(&Settings::default())
            .expect("无法初始化键盘模拟（enigo）。请确保已授予辅助功能权限。");

        // 使用 block_on 来接收 async channel 的消息
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

    // 构建共享状态
    let state = Arc::new(AppState {
        pin: args.pin.clone(),
        hostname: hostname.clone(),
        type_tx,
    });

    // 构建路由
    let app = Router::new()
        .route("/ws", get({
            let state = Arc::clone(&state);
            move |ws| ws_handler(ws, state)
        }))
        .fallback_service(ServeDir::new("static"));

    // 获取本机局域网 IP
    let local_ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string());

    let addr = format!("0.0.0.0:{}", args.port);

    println!();
    println!("╔══════════════════════════════════════════════╗");
    println!("║           🚀 VoiceDrop 已启动                ║");
    println!("╠══════════════════════════════════════════════╣");
    println!("║  主机名: {:<35} ║", hostname);
    println!("║  地址:   http://{}:{:<20} ║", local_ip, args.port);
    println!("║  PIN:    {:<35} ║", args.pin);
    println!("╠══════════════════════════════════════════════╣");
    println!("║  📱 用手机浏览器打开上面的地址即可          ║");
    println!("╚══════════════════════════════════════════════╝");
    println!();

    // 启动服务器
    let listener = tokio::net::TcpListener::bind(&addr)
        .await
        .unwrap_or_else(|_| panic!("无法绑定端口 {}。可能被其他程序占用，试试 --port 换个端口。", args.port));

    info!("服务器启动在 {}", addr);

    axum::serve(listener, app)
        .await
        .expect("服务器运行出错");
}

/// WebSocket 连接处理入口
async fn ws_handler(ws: WebSocketUpgrade, state: Arc<AppState>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

/// 处理单个 WebSocket 连接
async fn handle_socket(mut socket: WebSocket, state: Arc<AppState>) {
    info!("新的 WebSocket 连接");
    let mut authenticated = false;

    while let Some(Ok(msg)) = socket.recv().await {
        match msg {
            Message::Text(text) => {
                // 尝试解析 JSON
                let client_msg: ClientMessage = match serde_json::from_str(&text) {
                    Ok(m) => m,
                    Err(e) => {
                        warn!("收到无效 JSON: {}", e);
                        let reply = ServerMessage {
                            status: "error".to_string(),
                            hostname: None,
                            error: Some("无效的 JSON 格式".to_string()),
                        };
                        let _ = socket.send(Message::Text(
                            serde_json::to_string(&reply).unwrap().into(),
                        )).await;
                        continue;
                    }
                };

                match client_msg.action.as_str() {
                    // PIN 认证
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
                                let _ = socket.send(Message::Text(
                                    serde_json::to_string(&reply).unwrap().into(),
                                )).await;
                            } else {
                                warn!("客户端 PIN 错误");
                                let reply = ServerMessage {
                                    status: "error".to_string(),
                                    hostname: None,
                                    error: Some("PIN 码错误".to_string()),
                                };
                                let _ = socket.send(Message::Text(
                                    serde_json::to_string(&reply).unwrap().into(),
                                )).await;
                            }
                        }
                    }

                    // 文字输入
                    "type" => {
                        if !authenticated {
                            let reply = ServerMessage {
                                status: "error".to_string(),
                                hostname: None,
                                error: Some("未认证，请先发送 PIN".to_string()),
                            };
                            let _ = socket.send(Message::Text(
                                serde_json::to_string(&reply).unwrap().into(),
                            )).await;
                            continue;
                        }

                        if let Some(text_content) = &client_msg.text {
                            info!("收到文字: {}", text_content);

                            // 记录日志
                            log_history(text_content, "ws-client");

                            // 通过 channel 发送给键盘输入线程
                            let (reply_tx, reply_rx) = oneshot::channel();
                            let req = TypeRequest {
                                text: text_content.clone(),
                                reply: reply_tx,
                            };

                            if state.type_tx.send(req).await.is_ok() {
                                match reply_rx.await {
                                    Ok(Ok(())) => {
                                        info!("文字已输入到光标位置");
                                        let reply = ServerMessage {
                                            status: "ok".to_string(),
                                            hostname: None,
                                            error: None,
                                        };
                                        let _ = socket.send(Message::Text(
                                            serde_json::to_string(&reply).unwrap().into(),
                                        )).await;
                                    }
                                    Ok(Err(e)) => {
                                        error!("键盘模拟失败: {}", e);
                                        let reply = ServerMessage {
                                            status: "error".to_string(),
                                            hostname: None,
                                            error: Some(format!("键盘输入失败: {}", e)),
                                        };
                                        let _ = socket.send(Message::Text(
                                            serde_json::to_string(&reply).unwrap().into(),
                                        )).await;
                                    }
                                    Err(_) => {
                                        error!("键盘输入线程无响应");
                                    }
                                }
                            }
                        }
                    }

                    // 心跳
                    "ping" => {
                        let reply = serde_json::json!({"action": "pong"});
                        let _ = socket.send(Message::Text(
                            reply.to_string().into(),
                        )).await;
                    }

                    other => {
                        warn!("未知 action: {}", other);
                    }
                }
            }
            Message::Ping(data) => {
                let _ = socket.send(Message::Pong(data)).await;
            }
            Message::Close(_) => {
                info!("客户端断开连接");
                break;
            }
            _ => {}
        }
    }

    info!("WebSocket 连接已关闭");
}

/// 获取日志目录路径
fn dirs_log_dir() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".to_string());
    PathBuf::from(home).join(".voicedrop")
}

/// 将历史记录追加到 JSONL 文件
fn log_history(text: &str, client_ip: &str) {
    let entry = HistoryEntry {
        timestamp: chrono::Local::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, false),
        text: text.to_string(),
        client_ip: client_ip.to_string(),
    };

    let log_path = dirs_log_dir().join("history.jsonl");

    match OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
    {
        Ok(mut file) => {
            if let Ok(json) = serde_json::to_string(&entry) {
                let _ = writeln!(file, "{}", json);
            }
        }
        Err(e) => {
            error!("无法写入日志文件 {:?}: {}", log_path, e);
        }
    }
}

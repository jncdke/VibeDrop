use base64::{engine::general_purpose::STANDARD as BASE64_STANDARD, Engine as _};
use futures_util::stream::{self, StreamExt};
use serde::{Deserialize, Serialize};
use std::fs::OpenOptions;
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr};
use std::path::{Path, PathBuf};
use tauri::Manager;

const DESKTOP_DISCOVERY_PORT: u16 = 9001;
const DISCOVERY_PROTOCOL_VERSION: u16 = 1;
const DISCOVERY_TIMEOUT_MS: u64 = 450;
const DISCOVERY_CONCURRENCY: usize = 24;

#[derive(Serialize, Deserialize)]
struct DiscoveredDesktop {
    kind: String,
    server_id: String,
    hostname: String,
    ip: String,
    port: u16,
    protocol_version: u16,
}

#[derive(Serialize, Deserialize)]
struct PairRequestAccepted {
    request_id: String,
    code: String,
    hostname: String,
    expires_in_secs: u64,
}

#[derive(Serialize, Deserialize)]
struct PairRequestStatusResponse {
    status: String,
    request_id: String,
    #[serde(default)]
    server_id: Option<String>,
    #[serde(default)]
    hostname: Option<String>,
    #[serde(default)]
    ip: Option<String>,
    #[serde(default)]
    port: Option<u16>,
    #[serde(default)]
    pin: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Serialize, Deserialize)]
struct IncomingTransferMeta {
    file_name: String,
    mime_type: String,
    size_bytes: u64,
}

#[tauri::command]
fn save_history(app: tauri::AppHandle, data: String) -> Result<(), String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    std::fs::write(dir.join("history.json"), &data).map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
fn load_history(app: tauri::AppHandle) -> Result<String, String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    let path = dir.join("history.json");
    if path.exists() {
        std::fs::read_to_string(path).map_err(|e| e.to_string())
    } else {
        Ok("[]".to_string())
    }
}

#[tauri::command]
fn export_history_file(
    app: tauri::AppHandle,
    filename: String,
    data: String,
) -> Result<String, String> {
    let dir = download_dir(&app)?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(&filename);
    std::fs::write(&path, &data).map_err(|e| e.to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[tauri::command]
fn begin_incoming_file(
    app: tauri::AppHandle,
    transfer_id: String,
    file_name: String,
    mime_type: String,
    size_bytes: u64,
) -> Result<(), String> {
    let temp_dir = incoming_transfer_dir(&app)?;
    std::fs::create_dir_all(&temp_dir).map_err(|e| format!("无法创建临时目录: {}", e))?;

    let meta = IncomingTransferMeta {
        file_name: sanitize_file_name(Some(&file_name), "file.bin"),
        mime_type,
        size_bytes,
    };
    let meta_payload = serde_json::to_string(&meta).map_err(|e| e.to_string())?;
    std::fs::write(
        incoming_transfer_meta_path(&app, &transfer_id)?,
        meta_payload,
    )
    .map_err(|e| format!("无法写入临时元数据: {}", e))?;
    std::fs::write(incoming_transfer_part_path(&app, &transfer_id)?, [])
        .map_err(|e| format!("无法创建临时文件: {}", e))?;
    Ok(())
}

#[tauri::command]
fn append_incoming_file_chunk(
    app: tauri::AppHandle,
    transfer_id: String,
    chunk_base64: String,
) -> Result<(), String> {
    let chunk = BASE64_STANDARD
        .decode(chunk_base64)
        .map_err(|e| format!("分块解码失败: {}", e))?;
    let part_path = incoming_transfer_part_path(&app, &transfer_id)?;
    if !part_path.exists() {
        return Err("接收中的临时文件不存在".to_string());
    }

    let mut file = OpenOptions::new()
        .append(true)
        .open(&part_path)
        .map_err(|e| format!("无法写入临时文件: {}", e))?;
    file.write_all(&chunk)
        .map_err(|e| format!("写入临时文件失败: {}", e))
}

#[tauri::command]
fn finish_incoming_file(app: tauri::AppHandle, transfer_id: String) -> Result<String, String> {
    let meta_path = incoming_transfer_meta_path(&app, &transfer_id)?;
    let part_path = incoming_transfer_part_path(&app, &transfer_id)?;
    if !meta_path.exists() || !part_path.exists() {
        return Err("接收临时文件已失效".to_string());
    }

    let meta_raw =
        std::fs::read_to_string(&meta_path).map_err(|e| format!("无法读取临时元数据: {}", e))?;
    let meta: IncomingTransferMeta =
        serde_json::from_str(&meta_raw).map_err(|e| format!("临时元数据无效: {}", e))?;

    let download = download_dir(&app)?;
    std::fs::create_dir_all(&download).map_err(|e| format!("无法创建下载目录: {}", e))?;

    let destination = unique_path(&download, &meta.file_name);
    if let Err(rename_error) = std::fs::rename(&part_path, &destination) {
        std::fs::copy(&part_path, &destination).map_err(|copy_error| {
            format!("无法保存到下载目录: {}; {}", rename_error, copy_error)
        })?;
        let _ = std::fs::remove_file(&part_path);
    }

    let _ = std::fs::remove_file(meta_path);
    Ok(destination.to_string_lossy().to_string())
}

#[tauri::command]
fn cancel_incoming_file(app: tauri::AppHandle, transfer_id: String) -> Result<(), String> {
    let part_path = incoming_transfer_part_path(&app, &transfer_id)?;
    let meta_path = incoming_transfer_meta_path(&app, &transfer_id)?;
    let _ = std::fs::remove_file(part_path);
    let _ = std::fs::remove_file(meta_path);
    Ok(())
}

#[tauri::command]
async fn discover_desktops() -> Result<Vec<DiscoveredDesktop>, String> {
    let local_ip = local_ipv4().ok_or_else(|| "无法识别当前局域网 IP".to_string())?;
    let octets = local_ip.octets();
    let prefix = format!("{}.{}.{}", octets[0], octets[1], octets[2]);
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_millis(DISCOVERY_TIMEOUT_MS))
        .build()
        .map_err(|e| format!("初始化发现客户端失败: {}", e))?;

    let discovered = stream::iter(1u16..=254)
        .map(|host| {
            let client = client.clone();
            let prefix = prefix.clone();
            async move {
                if host as u8 == octets[3] {
                    return None;
                }

                let ip = format!("{prefix}.{host}");
                let url = format!("http://{}:{}/discover", ip, DESKTOP_DISCOVERY_PORT);
                let response = client.get(&url).send().await.ok()?;
                if !response.status().is_success() {
                    return None;
                }

                let mut desktop = response.json::<DiscoveredDesktop>().await.ok()?;
                if desktop.kind != "desktop"
                    || desktop.protocol_version < DISCOVERY_PROTOCOL_VERSION
                {
                    return None;
                }
                if desktop.ip.trim().is_empty() {
                    desktop.ip = ip;
                }
                Some(desktop)
            }
        })
        .buffer_unordered(DISCOVERY_CONCURRENCY)
        .filter_map(|desktop| async move { desktop })
        .collect::<Vec<_>>()
        .await;

    let mut unique = std::collections::HashMap::new();
    for desktop in discovered {
        unique.entry(desktop.server_id.clone()).or_insert(desktop);
    }

    let mut items = unique.into_values().collect::<Vec<_>>();
    items.sort_by(|a, b| a.hostname.cmp(&b.hostname).then(a.ip.cmp(&b.ip)));
    Ok(items)
}

#[tauri::command]
async fn request_desktop_pairing(
    ip: String,
    port: u16,
    client_id: String,
    client_name: String,
) -> Result<PairRequestAccepted, String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(6))
        .build()
        .map_err(|e| format!("初始化配对请求失败: {}", e))?;
    let url = format!("http://{}:{}/pair/request", ip.trim(), port);
    let response = client
        .post(&url)
        .json(&serde_json::json!({
            "client_id": client_id,
            "client_name": client_name,
        }))
        .send()
        .await
        .map_err(|e| format!("无法连接桌面端: {}", e))?;

    if !response.status().is_success() {
        let fallback = response
            .json::<serde_json::Value>()
            .await
            .ok()
            .and_then(|payload| {
                payload
                    .get("error")
                    .and_then(|value| value.as_str())
                    .map(str::to_string)
            })
            .unwrap_or_else(|| "桌面端拒绝了配对请求".to_string());
        return Err(fallback);
    }

    response
        .json::<PairRequestAccepted>()
        .await
        .map_err(|e| format!("桌面端配对响应无效: {}", e))
}

#[tauri::command]
async fn poll_desktop_pairing(
    ip: String,
    port: u16,
    request_id: String,
) -> Result<PairRequestStatusResponse, String> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(6))
        .build()
        .map_err(|e| format!("初始化配对状态查询失败: {}", e))?;
    let url = format!(
        "http://{}:{}/pair/status/{}",
        ip.trim(),
        port,
        request_id.trim()
    );
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("无法查询配对状态: {}", e))?;

    response
        .json::<PairRequestStatusResponse>()
        .await
        .map_err(|e| format!("配对状态响应无效: {}", e))
}

fn download_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let download = PathBuf::from("/storage/emulated/0/Download");
    if download.exists() {
        Ok(download)
    } else {
        app.path().app_data_dir().map_err(|e| e.to_string())
    }
}

fn incoming_transfer_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    Ok(dir.join("incoming-transfer"))
}

fn incoming_transfer_part_path(
    app: &tauri::AppHandle,
    transfer_id: &str,
) -> Result<PathBuf, String> {
    Ok(incoming_transfer_dir(app)?.join(format!("{}.part", sanitize_transfer_id(transfer_id))))
}

fn incoming_transfer_meta_path(
    app: &tauri::AppHandle,
    transfer_id: &str,
) -> Result<PathBuf, String> {
    Ok(incoming_transfer_dir(app)?.join(format!("{}.json", sanitize_transfer_id(transfer_id))))
}

fn sanitize_transfer_id(value: &str) -> String {
    let cleaned: String = value
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
                ch
            } else {
                '_'
            }
        })
        .collect();
    if cleaned.is_empty() {
        "transfer".to_string()
    } else {
        cleaned
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

fn local_ipv4() -> Option<Ipv4Addr> {
    let detected = local_ip_address::local_ip().ok()?;
    match detected {
        IpAddr::V4(addr) if !addr.is_loopback() => Some(addr),
        _ => None,
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .invoke_handler(tauri::generate_handler![
            save_history,
            load_history,
            export_history_file,
            begin_incoming_file,
            append_incoming_file_chunk,
            finish_incoming_file,
            cancel_incoming_file,
            discover_desktops,
            request_desktop_pairing,
            poll_desktop_pairing
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

use base64::{engine::general_purpose::STANDARD as BASE64_STANDARD, Engine as _};
use futures_util::stream::{self, StreamExt};
use local_ip_address::list_afinet_netifas;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::fs::OpenOptions;
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use tauri::Manager;

const DESKTOP_DISCOVERY_PORT: u16 = 9001;
const DISCOVERY_PROTOCOL_VERSION: u16 = 1;
const DISCOVERY_TIMEOUT_MS: u64 = 450;
const DISCOVERY_CONCURRENCY: usize = 24;
const DISCOVERY_HTTP_SUBNET_LIMIT: usize = 2;
const UDP_DISCOVERY_RECEIVE_WINDOW_MS: u64 = 900;
const UDP_DISCOVERY_READ_SLICE_MS: u64 = 120;
const UDP_DISCOVERY_PROBE_ATTEMPTS: usize = 3;
const UDP_DISCOVERY_PROBE_INTERVAL_MS: u64 = 70;

#[derive(Serialize, Deserialize)]
struct DiscoveryProbe {
    kind: String,
    protocol_version: u16,
}

#[derive(Clone, Serialize, Deserialize)]
struct DiscoveredDesktop {
    kind: String,
    server_id: String,
    hostname: String,
    ip: String,
    port: u16,
    protocol_version: u16,
}

#[derive(Clone, Debug)]
struct DiscoveryIpv4Candidate {
    interface_name: String,
    ip: Ipv4Addr,
    priority: u8,
    allow_directed_broadcast: bool,
    allow_http_sweep: bool,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
struct DiscoveryKnownDeviceHint {
    device_id: String,
    server_id: String,
    ip: String,
    port: u16,
    hostnames: Vec<String>,
}

#[derive(Clone, Debug)]
struct DiscoveryDirectTarget {
    known_device_key: String,
    server_id: String,
    ip: String,
    hostnames: Vec<String>,
    target_host: String,
    port: u16,
    target_kind: &'static str,
}

#[derive(Default)]
struct DiscoveryDirectOutcome {
    discovered: Vec<DiscoveredDesktop>,
    expected_device_keys: HashSet<String>,
    verified_device_keys: HashSet<String>,
}

#[derive(Clone, Serialize, Default)]
struct DiscoveryDiagnosticsInterface {
    interface_name: String,
    ip: String,
    priority: u8,
    allow_directed_broadcast: bool,
    allow_http_sweep: bool,
}

#[derive(Clone, Serialize, Default)]
struct DiscoveryDiagnosticsTarget {
    target_host: String,
    port: u16,
    target_kind: String,
}

#[derive(Clone, Serialize, Default)]
struct DiscoveryDiagnosticsDesktop {
    hostname: String,
    ip: String,
    port: u16,
    server_id: String,
}

#[derive(Clone, Serialize, Default)]
struct DiscoveryDiagnosticsSnapshot {
    last_started_at_ms: u64,
    last_finished_at_ms: u64,
    last_duration_ms: u64,
    in_progress: bool,
    last_error: String,
    candidate_interfaces: Vec<DiscoveryDiagnosticsInterface>,
    udp_targets: Vec<String>,
    direct_targets: Vec<DiscoveryDiagnosticsTarget>,
    expected_direct_devices: usize,
    verified_direct_devices: usize,
    skip_http_sweep: bool,
    full_http_target_count: usize,
    udp_result_count: usize,
    direct_http_result_count: usize,
    merged_result_count: usize,
    merged_results: Vec<DiscoveryDiagnosticsDesktop>,
}

static DISCOVERY_DIAGNOSTICS: OnceLock<Mutex<DiscoveryDiagnosticsSnapshot>> = OnceLock::new();

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

#[cfg_attr(target_os = "ios", allow(dead_code))]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum IncomingSaveTarget {
    Download,
    GalleryImage,
    GalleryVideo,
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
fn finish_incoming_file(
    app: tauri::AppHandle,
    transfer_id: String,
    save_target: Option<String>,
) -> Result<String, String> {
    let meta_path = incoming_transfer_meta_path(&app, &transfer_id)?;
    let part_path = incoming_transfer_part_path(&app, &transfer_id)?;
    if !meta_path.exists() || !part_path.exists() {
        return Err("接收临时文件已失效".to_string());
    }

    let meta_raw =
        std::fs::read_to_string(&meta_path).map_err(|e| format!("无法读取临时元数据: {}", e))?;
    let meta: IncomingTransferMeta =
        serde_json::from_str(&meta_raw).map_err(|e| format!("临时元数据无效: {}", e))?;

    let target = incoming_save_target(save_target.as_deref(), &meta.mime_type);
    let destination_dir = incoming_save_dir(&app, target)?;
    std::fs::create_dir_all(&destination_dir).map_err(|e| {
        let label = match target {
            IncomingSaveTarget::Download => "下载目录",
            IncomingSaveTarget::GalleryImage | IncomingSaveTarget::GalleryVideo => "相册目录",
        };
        format!("无法创建{}: {}", label, e)
    })?;

    let destination = unique_path(&destination_dir, &meta.file_name);
    if let Err(rename_error) = std::fs::rename(&part_path, &destination) {
        std::fs::copy(&part_path, &destination).map_err(|copy_error| {
            let label = match target {
                IncomingSaveTarget::Download => "下载目录",
                IncomingSaveTarget::GalleryImage | IncomingSaveTarget::GalleryVideo => "相册目录",
            };
            format!("无法保存到{}: {}; {}", label, rename_error, copy_error)
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
fn get_discovery_diagnostics() -> DiscoveryDiagnosticsSnapshot {
    current_discovery_diagnostics()
}

#[tauri::command]
async fn discover_desktops(
    known_devices: Option<Vec<DiscoveryKnownDeviceHint>>,
) -> Result<Vec<DiscoveredDesktop>, String> {
    let local_ips = candidate_discovery_ipv4s();
    if local_ips.is_empty() {
        let started_at_ms = now_ms();
        update_discovery_diagnostics(|snapshot| {
            snapshot.last_started_at_ms = started_at_ms;
            snapshot.last_finished_at_ms = started_at_ms;
            snapshot.last_duration_ms = 0;
            snapshot.in_progress = false;
            snapshot.last_error = "无法识别当前局域网 IP".to_string();
            snapshot.candidate_interfaces.clear();
            snapshot.udp_targets.clear();
            snapshot.direct_targets.clear();
            snapshot.expected_direct_devices = 0;
            snapshot.verified_direct_devices = 0;
            snapshot.skip_http_sweep = false;
            snapshot.full_http_target_count = 0;
            snapshot.udp_result_count = 0;
            snapshot.direct_http_result_count = 0;
            snapshot.merged_result_count = 0;
            snapshot.merged_results.clear();
        });
        return Err("无法识别当前局域网 IP".to_string());
    }

    let started_at_ms = now_ms();
    let udp_targets = discovery_broadcast_targets(&local_ips)
        .into_iter()
        .map(|target| target.to_string())
        .collect::<Vec<_>>();
    let (direct_targets, expected_direct_device_keys) =
        if let Some(hints) = known_devices.as_ref().filter(|items| !items.is_empty()) {
            discovery_direct_targets(hints)
        } else {
            (Vec::new(), HashSet::new())
        };
    update_discovery_diagnostics(|snapshot| {
        snapshot.last_started_at_ms = started_at_ms;
        snapshot.last_finished_at_ms = 0;
        snapshot.last_duration_ms = 0;
        snapshot.in_progress = true;
        snapshot.last_error.clear();
        snapshot.candidate_interfaces = local_ips
            .iter()
            .map(discovery_candidate_to_diagnostics_interface)
            .collect();
        snapshot.udp_targets = udp_targets.clone();
        snapshot.direct_targets = direct_targets
            .iter()
            .map(discovery_direct_target_to_diagnostics_target)
            .collect();
        snapshot.expected_direct_devices = expected_direct_device_keys.len();
        snapshot.verified_direct_devices = 0;
        snapshot.skip_http_sweep = false;
        snapshot.full_http_target_count = 0;
        snapshot.udp_result_count = 0;
        snapshot.direct_http_result_count = 0;
        snapshot.merged_result_count = 0;
        snapshot.merged_results.clear();
    });

    println!(
        "[VibeDrop discover] candidate IPv4s: {:?}",
        local_ips
            .iter()
            .map(|candidate| format!(
                "{}={} (priority={}, directed_broadcast={}, http_sweep={})",
                candidate.interface_name,
                candidate.ip,
                candidate.priority,
                candidate.allow_directed_broadcast,
                candidate.allow_http_sweep
            ))
            .collect::<Vec<_>>()
    );

    let udp_local_ips = local_ips.clone();
    let udp_discovered = match tauri::async_runtime::spawn_blocking(move || {
        discover_desktops_via_udp(udp_local_ips)
    })
    .await
    .map_err(|e| format!("UDP 发现任务失败: {}", e))
    .unwrap_or_else(|_| Ok(Vec::new()))
    {
        Ok(items) => items,
        Err(error) => {
            finish_discovery_diagnostics_with_error(started_at_ms, &error);
            return Err(error);
        }
    };

    let direct_outcome = match discover_desktops_via_known_targets(
        direct_targets.clone(),
        expected_direct_device_keys.clone(),
    )
    .await
    {
        Ok(outcome) => outcome,
        Err(error) => {
            finish_discovery_diagnostics_with_error(started_at_ms, &error);
            return Err(error);
        }
    };
    let direct_http_count = direct_outcome.discovered.len();
    let expected_direct_devices = direct_outcome.expected_device_keys.len();
    let verified_direct_devices = direct_outcome.verified_device_keys.len();
    let skip_http_sweep =
        expected_direct_devices > 0 && expected_direct_devices == verified_direct_devices;

    let mut http_discovered = direct_outcome.discovered.clone();
    let full_http_count = if skip_http_sweep {
        println!(
            "[VibeDrop discover] direct probe verified all saved devices, skipping HTTP subnet sweep"
        );
        0
    } else {
        if expected_direct_devices > 0 {
            println!(
                "[VibeDrop discover] direct probe unresolved saved devices: expected={}, verified={}, falling back to HTTP subnet sweep",
                expected_direct_devices, verified_direct_devices
            );
        }
        let http_targets = discovery_http_targets(&local_ips);
        update_discovery_diagnostics(|snapshot| {
            snapshot.full_http_target_count = http_targets.len();
        });
        let full_http = match discover_desktops_via_http_targets(http_targets).await {
            Ok(items) => items,
            Err(error) => {
                finish_discovery_diagnostics_with_error(started_at_ms, &error);
                return Err(error);
            }
        };
        let count = full_http.len();
        http_discovered = merge_discovered_desktops(http_discovered, full_http);
        count
    };
    let merged = merge_discovered_desktops(udp_discovered.clone(), http_discovered.clone());
    let finished_at_ms = now_ms();
    update_discovery_diagnostics(|snapshot| {
        snapshot.last_finished_at_ms = finished_at_ms;
        snapshot.last_duration_ms = finished_at_ms.saturating_sub(started_at_ms);
        snapshot.in_progress = false;
        snapshot.last_error.clear();
        snapshot.expected_direct_devices = expected_direct_devices;
        snapshot.verified_direct_devices = verified_direct_devices;
        snapshot.skip_http_sweep = skip_http_sweep;
        snapshot.udp_result_count = udp_discovered.len();
        snapshot.direct_http_result_count = direct_http_count;
        snapshot.merged_result_count = merged.len();
        snapshot.merged_results = merged
            .iter()
            .map(discovery_desktop_to_diagnostics_desktop)
            .collect();
    });
    println!(
        "[VibeDrop discover] udp={}, direct_http={}, full_http={}, merged={}",
        udp_discovered.len(),
        direct_http_count,
        full_http_count,
        merged.len()
    );
    Ok(merged)
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
    #[cfg(target_os = "ios")]
    {
        return Ok(mobile_documents_dir(app)?.join("VibeDrop Inbox"));
    }

    #[cfg(target_os = "android")]
    {
        let download = PathBuf::from("/storage/emulated/0/Download");
        if download.exists() {
            return Ok(download);
        }
        return app.path().app_data_dir().map_err(|e| e.to_string());
    }

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        return app
            .path()
            .download_dir()
            .or_else(|_| app.path().app_data_dir())
            .map_err(|e| e.to_string());
    }
}

#[cfg(target_os = "ios")]
fn mobile_documents_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .document_dir()
        .or_else(|_| app.path().app_data_dir())
        .map_err(|e| e.to_string())
}

fn incoming_save_target(requested: Option<&str>, mime_type: &str) -> IncomingSaveTarget {
    #[cfg(target_os = "ios")]
    {
        let _ = requested;
        let _ = mime_type;
        return IncomingSaveTarget::Download;
    }

    #[cfg(not(target_os = "ios"))]
    {
        match requested.unwrap_or_default() {
            "gallery-image" => IncomingSaveTarget::GalleryImage,
            "gallery-video" => IncomingSaveTarget::GalleryVideo,
            "download" => IncomingSaveTarget::Download,
            _ => {
                if mime_type.starts_with("image/") {
                    IncomingSaveTarget::GalleryImage
                } else if mime_type.starts_with("video/") {
                    IncomingSaveTarget::GalleryVideo
                } else {
                    IncomingSaveTarget::Download
                }
            }
        }
    }
}

fn incoming_save_dir(
    app: &tauri::AppHandle,
    target: IncomingSaveTarget,
) -> Result<PathBuf, String> {
    #[cfg(target_os = "ios")]
    {
        let _ = target;
        return Ok(mobile_documents_dir(app)?.join("VibeDrop Inbox"));
    }

    #[cfg(target_os = "android")]
    {
        let public_dir = match target {
            IncomingSaveTarget::Download => PathBuf::from("/storage/emulated/0/Download"),
            IncomingSaveTarget::GalleryImage => {
                PathBuf::from("/storage/emulated/0/Pictures/VibeDrop")
            }
            IncomingSaveTarget::GalleryVideo => {
                PathBuf::from("/storage/emulated/0/Movies/VibeDrop")
            }
        };

        if public_dir.exists() || public_dir.parent().is_some() {
            return Ok(public_dir);
        }

        return app.path().app_data_dir().map_err(|e| e.to_string());
    }

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        let _ = target;
        return download_dir(app);
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

fn candidate_discovery_ipv4s() -> Vec<DiscoveryIpv4Candidate> {
    let mut seen = HashSet::new();
    let mut candidates = Vec::new();

    if let Ok(interfaces) = list_afinet_netifas() {
        let mut entries = interfaces
            .into_iter()
            .filter_map(|(name, addr)| match addr {
                IpAddr::V4(ip) if is_viable_discovery_ipv4(ip) => {
                    Some(build_discovery_ipv4_candidate(name, ip))
                }
                _ => None,
            })
            .collect::<Vec<_>>();
        entries.sort_by(|a, b| {
            a.priority
                .cmp(&b.priority)
                .then(a.interface_name.cmp(&b.interface_name))
                .then(a.ip.octets().cmp(&b.ip.octets()))
        });

        for candidate in entries {
            if seen.insert(candidate.ip) {
                println!(
                    "[VibeDrop discover] interface {} -> {} (priority={}, directed_broadcast={}, http_sweep={})",
                    candidate.interface_name,
                    candidate.ip,
                    candidate.priority,
                    candidate.allow_directed_broadcast,
                    candidate.allow_http_sweep
                );
                candidates.push(candidate);
            }
        }
    }

    if candidates.is_empty() {
        if let Some(ip) = local_ipv4().filter(|ip| is_viable_discovery_ipv4(*ip)) {
            if seen.insert(ip) {
                println!("[VibeDrop discover] fallback local_ip -> {}", ip);
                candidates.push(DiscoveryIpv4Candidate {
                    interface_name: "fallback_local_ip".to_string(),
                    ip,
                    priority: 0,
                    allow_directed_broadcast: true,
                    allow_http_sweep: true,
                });
            }
        }
    }

    candidates
}

fn build_discovery_ipv4_candidate(interface_name: String, ip: Ipv4Addr) -> DiscoveryIpv4Candidate {
    let normalized = interface_name.trim().to_ascii_lowercase();
    let is_primary_lan =
        interface_name_matches_any_prefix(&normalized, &["wlan", "ap", "swlan", "softap"]);
    let is_secondary_lan = interface_name_matches_any_prefix(
        &normalized,
        &["eth", "en", "bridge", "br", "rndis", "usb"],
    );
    let is_point_to_point_or_virtual = interface_name_matches_any_prefix(
        &normalized,
        &[
            "tun", "ppp", "rmnet", "ccmni", "clat", "dummy", "ifb", "tap", "utun",
        ],
    );

    let allow_http_sweep = is_primary_lan || is_secondary_lan;
    let allow_directed_broadcast = allow_http_sweep && !is_point_to_point_or_virtual;
    let priority = if is_primary_lan {
        0
    } else if is_secondary_lan {
        1
    } else if is_point_to_point_or_virtual {
        3
    } else {
        2
    };

    DiscoveryIpv4Candidate {
        interface_name,
        ip,
        priority,
        allow_directed_broadcast,
        allow_http_sweep,
    }
}

fn interface_name_matches_any_prefix(name: &str, prefixes: &[&str]) -> bool {
    prefixes.iter().any(|prefix| name.starts_with(prefix))
}

fn is_viable_discovery_ipv4(addr: Ipv4Addr) -> bool {
    !addr.is_loopback() && !addr.is_link_local() && !addr.is_unspecified() && addr.is_private()
}

fn discover_desktops_via_udp(
    local_ips: Vec<DiscoveryIpv4Candidate>,
) -> Result<Vec<DiscoveredDesktop>, String> {
    let bind_addr = SocketAddr::from((Ipv4Addr::UNSPECIFIED, 0));
    let socket = UdpSocket::bind(bind_addr).map_err(|e| format!("无法绑定 UDP 发现端口: {}", e))?;
    socket
        .set_broadcast(true)
        .map_err(|e| format!("无法启用 UDP 广播: {}", e))?;

    let probe = serde_json::to_vec(&DiscoveryProbe {
        kind: "discover_probe".to_string(),
        protocol_version: DISCOVERY_PROTOCOL_VERSION,
    })
    .map_err(|e| format!("无法编码发现探测包: {}", e))?;

    let targets = discovery_broadcast_targets(&local_ips);
    println!(
        "[VibeDrop discover] udp targets: {:?}",
        targets
            .iter()
            .map(|target| target.to_string())
            .collect::<Vec<_>>()
    );
    for _ in 0..UDP_DISCOVERY_PROBE_ATTEMPTS {
        for target in &targets {
            let _ = socket.send_to(&probe, target);
        }
        std::thread::sleep(Duration::from_millis(UDP_DISCOVERY_PROBE_INTERVAL_MS));
    }

    let deadline = Instant::now() + Duration::from_millis(UDP_DISCOVERY_RECEIVE_WINDOW_MS);
    let mut buffer = [0u8; 2048];
    let mut unique = HashMap::new();

    while Instant::now() < deadline {
        let remaining = deadline.saturating_duration_since(Instant::now());
        let slice = remaining.min(Duration::from_millis(UDP_DISCOVERY_READ_SLICE_MS));
        let _ = socket.set_read_timeout(Some(slice));

        match socket.recv_from(&mut buffer) {
            Ok((length, source)) => {
                let Ok(mut desktop) =
                    serde_json::from_slice::<DiscoveredDesktop>(&buffer[..length])
                else {
                    continue;
                };
                if desktop.kind != "desktop"
                    || desktop.protocol_version < DISCOVERY_PROTOCOL_VERSION
                {
                    continue;
                }

                desktop.ip = source.ip().to_string();
                let key = discovery_desktop_key(&desktop);
                unique.entry(key).or_insert(desktop);
            }
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) => {}
            Err(error) => return Err(format!("UDP 发现失败: {}", error)),
        }
    }

    Ok(unique.into_values().collect())
}

async fn discover_desktops_via_http_targets(
    targets: Vec<Ipv4Addr>,
) -> Result<Vec<DiscoveredDesktop>, String> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(DISCOVERY_TIMEOUT_MS))
        .build()
        .map_err(|e| format!("初始化发现客户端失败: {}", e))?;

    let discovered = stream::iter(targets)
        .map(|ip| {
            let client = client.clone();
            async move {
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
                desktop.ip = ip.to_string();
                Some(desktop)
            }
        })
        .buffer_unordered(DISCOVERY_CONCURRENCY)
        .filter_map(|desktop| async move { desktop })
        .collect::<Vec<_>>()
        .await;

    Ok(discovered)
}

async fn discover_desktops_via_known_targets(
    targets: Vec<DiscoveryDirectTarget>,
    expected_device_keys: HashSet<String>,
) -> Result<DiscoveryDirectOutcome, String> {
    if targets.is_empty() {
        println!("[VibeDrop discover] no direct targets from saved devices");
        return Ok(DiscoveryDirectOutcome::default());
    }

    println!(
        "[VibeDrop discover] direct targets: {:?}",
        targets
            .iter()
            .map(|target| format!(
                "{}:{} ({})",
                target.target_host, target.port, target.target_kind
            ))
            .collect::<Vec<_>>()
    );

    let client = reqwest::Client::builder()
        .timeout(Duration::from_millis(DISCOVERY_TIMEOUT_MS))
        .build()
        .map_err(|e| format!("初始化定向发现客户端失败: {}", e))?;

    let discovered = stream::iter(targets)
        .map(|target| {
            let client = client.clone();
            async move {
                let url = format!("http://{}:{}/discover", target.target_host, target.port);
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
                if target.target_kind == "ip" && desktop.ip.trim().is_empty() {
                    desktop.ip = target.target_host.clone();
                }
                if !discovery_direct_target_matches(&target, &desktop) {
                    println!(
                        "[VibeDrop discover] direct target mismatch: target={}:{} ({}) response_server_id={} response_hostname={} response_ip={}",
                        target.target_host,
                        target.port,
                        target.target_kind,
                        desktop.server_id,
                        desktop.hostname,
                        desktop.ip
                    );
                    return None;
                }
                if desktop.ip.trim().is_empty() {
                    desktop.ip = target.target_host.clone();
                }
                Some((target.known_device_key, desktop))
            }
        })
        .buffer_unordered(DISCOVERY_CONCURRENCY)
        .filter_map(|item| async move { item })
        .collect::<Vec<_>>()
        .await;

    let verified_device_keys = discovered
        .iter()
        .map(|(known_device_key, _)| known_device_key.clone())
        .collect::<HashSet<_>>();
    let merged = merge_discovered_desktops(
        Vec::new(),
        discovered.into_iter().map(|(_, desktop)| desktop).collect(),
    );

    println!(
        "[VibeDrop discover] direct probe verified {}/{} saved devices",
        verified_device_keys.len(),
        expected_device_keys.len()
    );

    Ok(DiscoveryDirectOutcome {
        discovered: merged,
        expected_device_keys,
        verified_device_keys,
    })
}

fn discovery_diagnostics_store() -> &'static Mutex<DiscoveryDiagnosticsSnapshot> {
    DISCOVERY_DIAGNOSTICS.get_or_init(|| Mutex::new(DiscoveryDiagnosticsSnapshot::default()))
}

fn update_discovery_diagnostics(update: impl FnOnce(&mut DiscoveryDiagnosticsSnapshot)) {
    let mut snapshot = discovery_diagnostics_store()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    update(&mut snapshot);
}

fn current_discovery_diagnostics() -> DiscoveryDiagnosticsSnapshot {
    discovery_diagnostics_store()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn finish_discovery_diagnostics_with_error(started_at_ms: u64, error: &str) {
    let finished_at_ms = now_ms();
    update_discovery_diagnostics(|snapshot| {
        snapshot.last_finished_at_ms = finished_at_ms;
        snapshot.last_duration_ms = finished_at_ms.saturating_sub(started_at_ms);
        snapshot.in_progress = false;
        snapshot.last_error = error.to_string();
    });
}

fn discovery_candidate_to_diagnostics_interface(
    candidate: &DiscoveryIpv4Candidate,
) -> DiscoveryDiagnosticsInterface {
    DiscoveryDiagnosticsInterface {
        interface_name: candidate.interface_name.clone(),
        ip: candidate.ip.to_string(),
        priority: candidate.priority,
        allow_directed_broadcast: candidate.allow_directed_broadcast,
        allow_http_sweep: candidate.allow_http_sweep,
    }
}

fn discovery_direct_target_to_diagnostics_target(
    target: &DiscoveryDirectTarget,
) -> DiscoveryDiagnosticsTarget {
    DiscoveryDiagnosticsTarget {
        target_host: target.target_host.clone(),
        port: target.port,
        target_kind: target.target_kind.to_string(),
    }
}

fn discovery_desktop_to_diagnostics_desktop(
    desktop: &DiscoveredDesktop,
) -> DiscoveryDiagnosticsDesktop {
    DiscoveryDiagnosticsDesktop {
        hostname: desktop.hostname.clone(),
        ip: desktop.ip.clone(),
        port: desktop.port,
        server_id: desktop.server_id.clone(),
    }
}

fn discovery_http_targets(local_ips: &[DiscoveryIpv4Candidate]) -> Vec<Ipv4Addr> {
    let local_set = local_ips
        .iter()
        .map(|candidate| candidate.ip)
        .collect::<HashSet<_>>();
    let mut seen = HashSet::new();
    let mut targets = Vec::new();
    let preferred_candidates = local_ips
        .iter()
        .filter(|candidate| candidate.allow_http_sweep)
        .collect::<Vec<_>>();
    let using_fallback_candidates = preferred_candidates.is_empty();
    let source_candidates = if using_fallback_candidates {
        println!("[VibeDrop discover] no preferred LAN interface found for HTTP sweep, falling back to all candidates");
        local_ips.iter().collect::<Vec<_>>()
    } else {
        preferred_candidates
    };
    let max_subnets = if using_fallback_candidates {
        source_candidates.len().max(1)
    } else {
        DISCOVERY_HTTP_SUBNET_LIMIT
    };
    let mut selected_subnets = HashSet::new();

    for candidate in source_candidates {
        let octets = candidate.ip.octets();
        let subnet = (octets[0], octets[1], octets[2]);
        if selected_subnets.contains(&subnet) {
            continue;
        }
        if selected_subnets.len() >= max_subnets {
            break;
        }
        selected_subnets.insert(subnet);
        for host in 1u16..=254 {
            let ip = Ipv4Addr::new(octets[0], octets[1], octets[2], host as u8);
            if local_set.contains(&ip) || !seen.insert(ip) {
                continue;
            }
            targets.push(ip);
        }
    }

    targets
}

fn discovery_broadcast_targets(local_ips: &[DiscoveryIpv4Candidate]) -> Vec<SocketAddr> {
    let mut seen = HashSet::new();
    let mut targets = Vec::new();
    let global = SocketAddr::from((Ipv4Addr::new(255, 255, 255, 255), DESKTOP_DISCOVERY_PORT));
    seen.insert(global);
    targets.push(global);

    let preferred_candidates = local_ips
        .iter()
        .filter(|candidate| candidate.allow_directed_broadcast)
        .collect::<Vec<_>>();
    let source_candidates = if preferred_candidates.is_empty() {
        local_ips.iter().collect::<Vec<_>>()
    } else {
        preferred_candidates
    };

    for candidate in source_candidates {
        let octets = candidate.ip.octets();
        let directed = SocketAddr::from((
            Ipv4Addr::new(octets[0], octets[1], octets[2], 255),
            DESKTOP_DISCOVERY_PORT,
        ));
        if seen.insert(directed) {
            targets.push(directed);
        }
    }

    targets
}

fn discovery_direct_targets(
    known_devices: &[DiscoveryKnownDeviceHint],
) -> (Vec<DiscoveryDirectTarget>, HashSet<String>) {
    let mut targets = Vec::new();
    let mut expected_device_keys = HashSet::new();

    for hint in known_devices {
        let known_device_key = discovery_known_device_key(hint);
        let port = discovery_known_device_port(hint);
        let normalized_hostnames = discovery_hint_hostnames(hint);
        let mut seen_targets = HashSet::new();

        if let Some(ip) = discovery_hint_ip(hint) {
            let dedupe_key = format!("ip:{ip}:{port}");
            if seen_targets.insert(dedupe_key) {
                targets.push(DiscoveryDirectTarget {
                    known_device_key: known_device_key.clone(),
                    server_id: hint.server_id.trim().to_string(),
                    ip: ip.clone(),
                    hostnames: normalized_hostnames.clone(),
                    target_host: ip,
                    port,
                    target_kind: "ip",
                });
            }
        }

        for hostname in discovery_hint_hostnames(hint) {
            let dedupe_key = format!("host:{hostname}:{port}");
            if seen_targets.insert(dedupe_key) {
                targets.push(DiscoveryDirectTarget {
                    known_device_key: known_device_key.clone(),
                    server_id: hint.server_id.trim().to_string(),
                    ip: hint.ip.trim().to_string(),
                    hostnames: normalized_hostnames.clone(),
                    target_host: hostname,
                    port,
                    target_kind: "hostname",
                });
            }
        }

        if !seen_targets.is_empty() {
            expected_device_keys.insert(known_device_key);
        }
    }

    (targets, expected_device_keys)
}

fn discovery_direct_target_matches(
    target: &DiscoveryDirectTarget,
    desktop: &DiscoveredDesktop,
) -> bool {
    let expected_server_id = target.server_id.trim();
    let actual_server_id = desktop.server_id.trim();
    if !expected_server_id.is_empty() && !actual_server_id.is_empty() {
        return expected_server_id == actual_server_id;
    }

    if !target.hostnames.is_empty() {
        let matches_hostname = !desktop.hostname.trim().is_empty()
            && target.hostnames.iter().any(|expected| {
                discovery_host_identities_look_like_same_machine(expected, &desktop.hostname)
            });
        if !matches_hostname {
            return false;
        }
        return true;
    }

    let expected_ip = target.ip.trim();
    if !expected_ip.is_empty() {
        return desktop.ip.trim() == expected_ip;
    }

    false
}

fn discovery_known_device_key(hint: &DiscoveryKnownDeviceHint) -> String {
    let device_id = hint.device_id.trim();
    if !device_id.is_empty() {
        return format!("device:{device_id}");
    }
    let server_id = hint.server_id.trim();
    if !server_id.is_empty() {
        return format!("server:{server_id}");
    }
    if let Some(ip) = discovery_hint_ip(hint) {
        return format!("ip:{ip}:{}", discovery_known_device_port(hint));
    }
    if let Some(hostname) = discovery_hint_hostnames(hint).into_iter().next() {
        return format!("host:{hostname}:{}", discovery_known_device_port(hint));
    }
    format!("unknown:{}", discovery_known_device_port(hint))
}

fn discovery_known_device_port(hint: &DiscoveryKnownDeviceHint) -> u16 {
    if hint.port == 0 {
        DESKTOP_DISCOVERY_PORT
    } else {
        hint.port
    }
}

fn discovery_hint_ip(hint: &DiscoveryKnownDeviceHint) -> Option<String> {
    let ip = hint.ip.trim();
    if ip.is_empty() {
        None
    } else {
        Some(ip.to_string())
    }
}

fn discovery_hint_hostnames(hint: &DiscoveryKnownDeviceHint) -> Vec<String> {
    let mut seen = HashSet::new();
    let mut hostnames = Vec::new();
    for hostname in &hint.hostnames {
        let normalized = hostname.trim().to_ascii_lowercase();
        if normalized.is_empty() || !seen.insert(normalized.clone()) {
            continue;
        }
        hostnames.push(normalized);
    }
    hostnames
}

fn discovery_host_identities_look_like_same_machine(a: &str, b: &str) -> bool {
    let normalized_a = normalize_discovery_machine_identity(a);
    let normalized_b = normalize_discovery_machine_identity(b);
    if normalized_a.is_empty() || normalized_b.is_empty() {
        return false;
    }
    if normalized_a == normalized_b {
        return true;
    }

    let (shorter, longer) = if normalized_a.len() <= normalized_b.len() {
        (normalized_a, normalized_b)
    } else {
        (normalized_b, normalized_a)
    };
    shorter.len() >= 6 && longer.contains(&shorter)
}

fn normalize_discovery_machine_identity(value: &str) -> String {
    let trimmed = value.trim().to_ascii_lowercase();
    let base = trimmed.strip_suffix(".local").unwrap_or(trimmed.as_str());
    base.chars()
        .filter(|ch| ch.is_ascii_alphanumeric())
        .collect()
}

fn discovery_desktop_key(desktop: &DiscoveredDesktop) -> String {
    let server_id = desktop.server_id.trim();
    if !server_id.is_empty() {
        return format!("server:{server_id}");
    }
    format!("ip:{}:{}", desktop.ip.trim(), desktop.port)
}

fn merge_discovered_desktops(
    primary: Vec<DiscoveredDesktop>,
    secondary: Vec<DiscoveredDesktop>,
) -> Vec<DiscoveredDesktop> {
    let mut unique = HashMap::new();

    for desktop in primary.into_iter().chain(secondary.into_iter()) {
        let key = discovery_desktop_key(&desktop);
        unique
            .entry(key)
            .and_modify(|existing: &mut DiscoveredDesktop| {
                if existing.hostname.trim().is_empty() && !desktop.hostname.trim().is_empty() {
                    existing.hostname = desktop.hostname.clone();
                }
                if existing.ip.trim().is_empty() && !desktop.ip.trim().is_empty() {
                    existing.ip = desktop.ip.clone();
                }
                if existing.port == 0 && desktop.port != 0 {
                    existing.port = desktop.port;
                }
                if existing.server_id.trim().is_empty() && !desktop.server_id.trim().is_empty() {
                    existing.server_id = desktop.server_id.clone();
                }
            })
            .or_insert(desktop);
    }

    let mut items = unique.into_values().collect::<Vec<_>>();
    items.sort_by(|a, b| a.hostname.cmp(&b.hostname).then(a.ip.cmp(&b.ip)));
    items
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_desktop(server_id: &str, hostname: &str, ip: &str) -> DiscoveredDesktop {
        DiscoveredDesktop {
            kind: "desktop".to_string(),
            server_id: server_id.to_string(),
            hostname: hostname.to_string(),
            ip: ip.to_string(),
            port: DESKTOP_DISCOVERY_PORT,
            protocol_version: DISCOVERY_PROTOCOL_VERSION,
        }
    }

    #[test]
    fn direct_target_prefers_server_id_validation() {
        let target = DiscoveryDirectTarget {
            known_device_key: "device:test".to_string(),
            server_id: "server-123".to_string(),
            ip: "192.168.31.180".to_string(),
            hostnames: vec!["minidemac-mini.local".to_string()],
            target_host: "192.168.31.180".to_string(),
            port: DESKTOP_DISCOVERY_PORT,
            target_kind: "ip",
        };

        assert!(discovery_direct_target_matches(
            &target,
            &sample_desktop("server-123", "other-host.local", "192.168.31.55")
        ));
        assert!(!discovery_direct_target_matches(
            &target,
            &sample_desktop("server-456", "minidemac-mini.local", "192.168.31.180")
        ));
    }

    #[test]
    fn direct_target_falls_back_to_hostname_identity() {
        let target = DiscoveryDirectTarget {
            known_device_key: "device:test".to_string(),
            server_id: String::new(),
            ip: String::new(),
            hostnames: vec!["minidemac-mini.local".to_string()],
            target_host: "minidemac-mini.local".to_string(),
            port: DESKTOP_DISCOVERY_PORT,
            target_kind: "hostname",
        };

        assert!(discovery_direct_target_matches(
            &target,
            &sample_desktop("", "minideMac-mini.local", "192.168.31.180")
        ));
        assert!(!discovery_direct_target_matches(
            &target,
            &sample_desktop("", "other-mac.local", "192.168.31.180")
        ));
    }

    #[test]
    fn direct_target_falls_back_to_ip_when_no_stronger_identity_exists() {
        let target = DiscoveryDirectTarget {
            known_device_key: "device:test".to_string(),
            server_id: String::new(),
            ip: "192.168.31.45".to_string(),
            hostnames: Vec::new(),
            target_host: "192.168.31.45".to_string(),
            port: DESKTOP_DISCOVERY_PORT,
            target_kind: "ip",
        };

        assert!(discovery_direct_target_matches(
            &target,
            &sample_desktop("", "", "192.168.31.45")
        ));
        assert!(!discovery_direct_target_matches(
            &target,
            &sample_desktop("", "", "192.168.31.180")
        ));
    }

    #[test]
    fn http_targets_ignore_non_http_interfaces_when_lan_exists() {
        let targets = discovery_http_targets(&[
            DiscoveryIpv4Candidate {
                interface_name: "wlan0".to_string(),
                ip: Ipv4Addr::new(192, 168, 31, 70),
                priority: 0,
                allow_directed_broadcast: true,
                allow_http_sweep: true,
            },
            DiscoveryIpv4Candidate {
                interface_name: "tun0".to_string(),
                ip: Ipv4Addr::new(172, 19, 0, 1),
                priority: 3,
                allow_directed_broadcast: false,
                allow_http_sweep: false,
            },
        ]);

        assert_eq!(targets.len(), 253);
        assert!(targets.iter().all(|ip| {
            let octets = ip.octets();
            octets[0] == 192 && octets[1] == 168 && octets[2] == 31
        }));
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
            get_discovery_diagnostics,
            discover_desktops,
            request_desktop_pairing,
            poll_desktop_pairing
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

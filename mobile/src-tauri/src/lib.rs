use tauri::Manager;

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
fn export_history_file(app: tauri::AppHandle, filename: String, data: String) -> Result<String, String> {
    // Android: /storage/emulated/0/Download/
    let download = std::path::PathBuf::from("/storage/emulated/0/Download");
    let dir = if download.exists() {
        download
    } else {
        // 非 Android: 存到 app data dir
        app.path().app_data_dir().map_err(|e| e.to_string())?
    };
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(&filename);
    std::fs::write(&path, &data).map_err(|e| e.to_string())?;
    Ok(path.to_string_lossy().to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .invoke_handler(tauri::generate_handler![save_history, load_history, export_history_file])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

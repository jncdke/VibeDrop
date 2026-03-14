use std::{
    env,
    path::{Path, PathBuf},
};

fn rewrite_nested_path(path: &Path, wrong_prefix: &Path, correct_prefix: &Path) -> Option<PathBuf> {
    let relative = path.strip_prefix(wrong_prefix).ok()?;
    let candidate = correct_prefix.join(relative);
    candidate.exists().then_some(candidate)
}

fn env_cache_file_name(key: &str) -> String {
    let mut name = String::with_capacity(key.len());
    for ch in key.chars() {
        if ch.is_ascii_alphanumeric() {
            name.push(ch.to_ascii_lowercase());
        } else {
            name.push('_');
        }
    }
    name
}

fn maybe_rewrite_tauri_env_path(
    key: &str,
    value: &std::ffi::OsStr,
    wrong_prefix: &Path,
    correct_prefix: &Path,
    cache_dir: &Path,
) -> bool {
    let is_permission_manifest = key.ends_with("_PERMISSION_FILES_PATH");
    let is_global_scope_schema = key.ends_with("_GLOBAL_SCOPE_SCHEMA_PATH");
    if !is_permission_manifest && !is_global_scope_schema {
        return false;
    }

    let original = PathBuf::from(value);
    let source = if original.exists() {
        original.clone()
    } else if let Some(candidate) = rewrite_nested_path(&original, wrong_prefix, correct_prefix) {
        candidate
    } else {
        return false;
    };

    if is_global_scope_schema {
        if source != original {
            env::set_var(key, &source);
            return true;
        }
        return false;
    }

    let Ok(contents) = std::fs::read_to_string(&source) else {
        return false;
    };
    let Ok(paths) = serde_json::from_str::<Vec<PathBuf>>(&contents) else {
        return false;
    };

    let mut changed = source != original;
    let rewritten_paths = paths
        .into_iter()
        .map(|path| {
            if let Some(candidate) = rewrite_nested_path(&path, wrong_prefix, correct_prefix) {
                changed = true;
                candidate
            } else {
                path
            }
        })
        .collect::<Vec<_>>();

    if changed {
        std::fs::create_dir_all(cache_dir).expect("failed to create patched Tauri env cache");
        let rewritten_manifest_path = cache_dir.join(env_cache_file_name(key));
        std::fs::write(
            &rewritten_manifest_path,
            serde_json::to_vec(&rewritten_paths).expect("failed to serialize patched permissions"),
        )
        .expect("failed to write patched Tauri permissions manifest");
        env::set_var(key, &rewritten_manifest_path);
        return true;
    }

    false
}

fn repair_nested_tauri_paths() {
    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let Some(repo_root) = manifest_dir.parent().and_then(|dir| dir.parent()) else {
        return;
    };

    let wrong_prefix = repo_root.join("src-tauri");
    if wrong_prefix == manifest_dir {
        return;
    }

    let cache_dir = PathBuf::from(env::var("OUT_DIR").unwrap()).join("patched-tauri-env");
    let vars = env::vars_os().collect::<Vec<_>>();
    let mut patched_entries = 0usize;
    for (key, value) in vars {
        let key = key.to_string_lossy();
        if maybe_rewrite_tauri_env_path(&key, &value, &wrong_prefix, &manifest_dir, &cache_dir) {
            patched_entries += 1;
        }
    }

    if patched_entries > 0 {
        println!(
            "cargo:warning=patched {patched_entries} broken Tauri dependency path entries for nested desktop/src-tauri build"
        );
    }
}

fn main() {
    repair_nested_tauri_paths();
    tauri_build::build()
}

const { invoke } = window.__TAURI__.core;

// 初始化
document.addEventListener('DOMContentLoaded', async () => {
    // 首先检查辅助功能权限
    await checkPermission();

    // 按钮事件
    document.getElementById('btn-open-settings').addEventListener('click', async () => {
        await invoke('open_accessibility_settings');
    });

    document.getElementById('btn-check-again').addEventListener('click', async () => {
        await checkPermission();
    });
});

async function checkPermission() {
    try {
        const hasPermission = await invoke('check_accessibility');
        if (hasPermission) {
            showMainView();
        } else {
            showPermissionView();
        }
    } catch (e) {
        console.error('权限检查失败:', e);
        // 出错时也显示主界面
        showMainView();
    }
}

function showPermissionView() {
    document.getElementById('permission-view').style.display = 'flex';
    document.getElementById('main-view').style.display = 'none';
}

async function showMainView() {
    document.getElementById('permission-view').style.display = 'none';
    document.getElementById('main-view').style.display = 'flex';

    // 加载服务信息
    try {
        const info = await invoke('get_service_info');
        document.getElementById('hostname').textContent = info.hostname;
        document.getElementById('ip-address').textContent = info.ip;
        document.getElementById('port').textContent = info.port;
        document.getElementById('pin').textContent = info.pin;

        // 点击任意 copyable 字段复制
        document.querySelectorAll('.copyable').forEach(el => {
            el.addEventListener('click', () => {
                navigator.clipboard.writeText(el.textContent);
                const original = el.textContent;
                el.textContent = '已复制';
                setTimeout(() => el.textContent = original, 1500);
            });
        });
    } catch (e) {
        console.error('获取服务信息失败:', e);
    }

    // 恢复历史记录
    await restoreLog();

    // 监听后端发来的文字接收事件
    if (window.__TAURI__.event) {
        window.__TAURI__.event.listen('text-received', (event) => {
            addLogItem(event.payload.text, event.payload.timestamp);
        });
    }

    // 导出按钮
    document.getElementById('btn-export-log').addEventListener('click', exportLog);
}

// ---- localStorage 持久化 ----
const LOG_KEY = 'voicedrop_received_log';

function getLog() {
    const data = localStorage.getItem(LOG_KEY);
    return data ? JSON.parse(data) : [];
}

function saveLog(log) {
    localStorage.setItem(LOG_KEY, JSON.stringify(log));
}

async function restoreLog() {
    let log = [];

    try {
        log = await invoke('load_history_entries');
        if (Array.isArray(log) && log.length > 0) {
            saveLog(log);
        } else {
            log = getLog();
        }
    } catch (e) {
        console.error('读取桌面历史失败:', e);
        log = getLog();
    }

    if (log.length === 0) return;

    const list = document.getElementById('log-list');
    const empty = list.querySelector('.empty');
    if (empty) empty.remove();

    log.forEach(entry => {
        const item = createLogElement(entry.text, entry.timestamp);
        list.appendChild(item);
    });
}

function addLogItem(text, timestamp) {
    const ts = timestamp || new Date().toISOString();

    // 保存到 localStorage
    const log = getLog();
    log.unshift({ text, timestamp: ts });
    saveLog(log);

    // 显示到界面
    const list = document.getElementById('log-list');
    const empty = list.querySelector('.empty');
    if (empty) empty.remove();

    const item = createLogElement(text, ts);
    list.insertBefore(item, list.firstChild);
}

function createLogElement(text, timestamp) {
    const time = new Date(timestamp || Date.now());
    const timeStr = time.toLocaleTimeString('zh-CN', {
        hour: '2-digit', minute: '2-digit', second: '2-digit'
    });

    const item = document.createElement('div');
    item.className = 'log-item';
    item.title = '点击复制';
    item.style.cursor = 'pointer';
    item.innerHTML = `
        <div class="log-time">${timeStr}</div>
        <div class="log-text">${escapeHtml(text)}</div>
    `;
    item.addEventListener('click', () => {
        navigator.clipboard.writeText(text);
        showToast('已复制');
    });
    return item;
}

function exportLog() {
    const log = getLog();
    if (log.length === 0) {
        alert('没有记录可导出');
        return;
    }
    const data = JSON.stringify(log, null, 2);
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const time = new Date().toISOString().slice(0, 10);
    a.href = url;
    a.download = `vibedrop_received_${time}.json`;
    a.click();
    URL.revokeObjectURL(url);
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function showToast(message) {
    let toast = document.getElementById('toast');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'toast';
        toast.style.cssText = 'position:fixed;top:72px;left:50%;transform:translateX(-50%);background:rgba(28,28,30,0.9);color:#fff;padding:12px 18px;border-radius:18px;font-size:13px;font-weight:600;z-index:9999;transition:opacity 0.18s ease;pointer-events:none;backdrop-filter:blur(18px);box-shadow:0 18px 34px rgba(15,23,42,0.18);border:1px solid rgba(255,255,255,0.14);';
        document.body.appendChild(toast);
    }
    toast.style.opacity = '0';
    clearTimeout(toast._timer);
    clearTimeout(toast._showTimer);
    toast._showTimer = setTimeout(() => {
        toast.textContent = message;
        toast.style.opacity = '1';
        toast._timer = setTimeout(() => { toast.style.opacity = '0'; }, 2000);
    }, 150);
}

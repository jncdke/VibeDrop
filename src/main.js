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
        document.getElementById('address').textContent = `http://${info.ip}:${info.port}`;
        document.getElementById('pin').textContent = info.pin;

        // 点击地址复制
        document.getElementById('address').addEventListener('click', () => {
            navigator.clipboard.writeText(`http://${info.ip}:${info.port}`);
            const el = document.getElementById('address');
            const original = el.textContent;
            el.textContent = '已复制 ✓';
            setTimeout(() => el.textContent = original, 1500);
        });
    } catch (e) {
        console.error('获取服务信息失败:', e);
    }

    // 恢复历史记录
    restoreLog();

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

function restoreLog() {
    const log = getLog();
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
    item.innerHTML = `
        <div class="log-time">${timeStr}</div>
        <div class="log-text">${escapeHtml(text)}</div>
    `;
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

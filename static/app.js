// ============================================
// VibeDrop — 前端逻辑
// ============================================

// ---- 常量 ----
const HEARTBEAT_INTERVAL = 5000; // 心跳间隔 5 秒
const HEARTBEAT_TIMEOUT = 10000; // 心跳超时 10 秒
const RECONNECT_INTERVAL = 3000; // 重连间隔 3 秒
const HISTORY_KEY = 'voicedrop_history';
const SETTINGS_KEY = 'voicedrop_settings';


// ---- 状态 ----
const connections = {
    mac1: { ws: null, authenticated: false, hostname: null, heartbeatTimer: null, timeoutTimer: null, reconnectTimer: null },
    mac2: { ws: null, authenticated: false, hostname: null, heartbeatTimer: null, timeoutTimer: null, reconnectTimer: null },
};

// ---- DOM 元素缓存 ----
const $ = (id) => document.getElementById(id);

// ---- 初始化 ----
document.addEventListener('DOMContentLoaded', () => {
    loadSettings();
    initNavigation();
    initSendButtons();
    initHistoryFilter();
    initSettingsButton();
});

// ============================================
// 设置管理
// ============================================

function loadSettings() {
    const saved = localStorage.getItem(SETTINGS_KEY);
    if (saved) {
        const settings = JSON.parse(saved);
        $('mac1-ip').value = settings.mac1Ip || '';
        $('mac1-port').value = settings.mac1Port || '9001';
        $('mac1-pin').value = settings.mac1Pin || '';
        $('mac2-ip').value = settings.mac2Ip || '';
        $('mac2-port').value = settings.mac2Port || '9001';
        $('mac2-pin').value = settings.mac2Pin || '';

        // 如果有保存过设置，直接跳到发送页并连接
        if (settings.mac1Ip || settings.mac2Ip) {
            showView('send-view');
            connectAll();
        }
    } else {
        // 首次打开：自动从当前页面 URL 获取 Mac 的 IP 和端口
        const currentHost = window.location.hostname;
        const currentPort = window.location.port || '9001';
        if (currentHost && currentHost !== 'localhost' && currentHost !== '127.0.0.1') {
            $('mac1-ip').value = currentHost;
            $('mac1-port').value = currentPort;
        }
    }
}

function saveSettings() {
    const settings = {
        mac1Ip: $('mac1-ip').value.trim(),
        mac1Port: $('mac1-port').value.trim() || '9001',
        mac1Pin: $('mac1-pin').value.trim(),
        mac2Ip: $('mac2-ip').value.trim(),
        mac2Port: $('mac2-port').value.trim() || '9001',
        mac2Pin: $('mac2-pin').value.trim(),
    };
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    return settings;
}

function getSettings() {
    const saved = localStorage.getItem(SETTINGS_KEY);
    return saved ? JSON.parse(saved) : {};
}

// ============================================
// 导航
// ============================================

function initNavigation() {
    document.querySelectorAll('.nav-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            const viewId = btn.dataset.view;
            showView(viewId);
            document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
        });
    });
}

function initSettingsButton() {
    $('nav-settings-btn').addEventListener('click', () => {
        showView('settings-view');
        document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
    });

    $('save-settings-btn').addEventListener('click', () => {
        saveSettings();
        disconnectAll();
        showView('send-view');
        $('nav-send-btn').classList.add('active');
        connectAll();
    });
}

function showView(viewId) {
    document.querySelectorAll('.view').forEach(v => v.classList.add('hidden'));
    $(viewId).classList.remove('hidden');

    if (viewId === 'history-view') {
        renderHistory();
    }
}

// ============================================
// WebSocket 连接
// ============================================

function connectAll() {
    const settings = getSettings();
    if (settings.mac1Ip) {
        connectMac('mac1', settings.mac1Ip, settings.mac1Port || '9001', settings.mac1Pin || '');
    }
    if (settings.mac2Ip) {
        connectMac('mac2', settings.mac2Ip, settings.mac2Port || '9001', settings.mac2Pin || '');
    }
}

function disconnectAll() {
    ['mac1', 'mac2'].forEach(id => {
        const conn = connections[id];
        clearTimers(conn);
        if (conn.ws) {
            conn.ws.close();
            conn.ws = null;
        }
        conn.authenticated = false;
        conn.hostname = null;
        updateMacUI(id, 'disconnected');
    });
}

function connectMac(macId, ip, port, pin) {
    const conn = connections[macId];

    // 清理旧连接
    clearTimers(conn);
    if (conn.ws) {
        conn.ws.close();
    }
    conn.authenticated = false;

    updateMacUI(macId, 'connecting');

    const url = `ws://${ip}:${port}/ws`;
    console.log(`[${macId}] 正在连接: ${url}`);
    let ws;
    try {
        ws = new WebSocket(url);
    } catch (e) {
        console.error(`[${macId}] WebSocket 创建失败:`, e);
        updateMacUI(macId, 'error', '连接失败');
        scheduleReconnect(macId, ip, port, pin);
        return;
    }

    conn.ws = ws;

    ws.onopen = () => {
        console.log(`[${macId}] WebSocket 已连接，发送 PIN 认证`);
        ws.send(JSON.stringify({ action: 'auth', pin: pin }));
        updateMacUI(macId, 'connecting', '认证中...');
    };

    ws.onmessage = (event) => {
        console.log(`[${macId}] 收到消息:`, event.data);
        let data;
        try {
            data = JSON.parse(event.data);
        } catch {
            return;
        }

        // 心跳响应
        if (data.action === 'pong') {
            clearTimeout(conn.timeoutTimer);
            return;
        }

        // 剪贴板同步（Mac → 手机）
        if (data.action === 'clipboard') {
            if (data.text && navigator.clipboard) {
                navigator.clipboard.writeText(data.text).then(() => {
                    showToast('📋 已同步到剪贴板');
                }).catch(() => {
                    showToast('⚠️ 剪贴板写入失败');
                });
            }
            return;
        }

        // 认证响应
        if (!conn.authenticated) {
            if (data.status === 'ok' && data.hostname) {
                conn.authenticated = true;
                conn.hostname = data.hostname;
                console.log(`[${macId}] 认证成功，主机名: ${data.hostname}`);
                updateMacUI(macId, 'connected', data.hostname);
                startHeartbeat(macId, ip, port, pin);
            } else {
                console.warn(`[${macId}] 认证失败:`, data.error);
                updateMacUI(macId, 'error', data.error || 'PIN 错误');
            }
            return;
        }

        // 发送结果响应（通过回调处理）
        if (conn._sendCallback) {
            conn._sendCallback(data);
            conn._sendCallback = null;
        }
    };

    ws.onerror = (e) => {
        console.error(`[${macId}] WebSocket 错误:`, e);
        updateMacUI(macId, 'error', '连接出错');
    };

    ws.onclose = (e) => {
        console.log(`[${macId}] WebSocket 关闭, code=${e.code}, reason=${e.reason}`);
        conn.authenticated = false;
        updateMacUI(macId, 'disconnected', '已断开');
        clearTimers(conn);
        scheduleReconnect(macId, ip, port, pin);
    };
}

// ---- 心跳 ----

function startHeartbeat(macId, ip, port, pin) {
    const conn = connections[macId];
    clearTimers(conn);

    conn.heartbeatTimer = setInterval(() => {
        if (conn.ws && conn.ws.readyState === WebSocket.OPEN) {
            conn.ws.send(JSON.stringify({ action: 'ping' }));

            // 设置超时检测
            conn.timeoutTimer = setTimeout(() => {
                // 心跳超时，关闭连接触发重连
                if (conn.ws) {
                    conn.ws.close();
                }
            }, HEARTBEAT_TIMEOUT);
        }
    }, HEARTBEAT_INTERVAL);
}

function scheduleReconnect(macId, ip, port, pin) {
    const conn = connections[macId];
    clearTimeout(conn.reconnectTimer);
    conn.reconnectTimer = setTimeout(() => {
        connectMac(macId, ip, port, pin);
    }, RECONNECT_INTERVAL);
}

function clearTimers(conn) {
    clearInterval(conn.heartbeatTimer);
    clearTimeout(conn.timeoutTimer);
    clearTimeout(conn.reconnectTimer);
    conn.heartbeatTimer = null;
    conn.timeoutTimer = null;
    conn.reconnectTimer = null;
}

// ---- UI 更新 ----

function updateMacUI(macId, status, detail) {
    const dot = $(`${macId}-status-dot`);
    const name = $(`${macId}-name`);
    const text = $(`${macId}-status-text`);
    const card = $(`${macId}-card`);
    const sendBtn = $(`${macId}-send-btn`);

    // 清除所有状态类
    dot.className = 'status-dot';
    card.className = 'mac-card';

    switch (status) {
        case 'connected':
            dot.classList.add('connected');
            card.classList.add('connected');
            text.textContent = '已连接';
            sendBtn.disabled = false;
            if (detail) name.textContent = detail;
            break;
        case 'connecting':
            dot.classList.add('connecting');
            text.textContent = detail || '连接中...';
            sendBtn.disabled = true;
            break;
        case 'error':
            dot.classList.add('error');
            card.classList.add('error');
            text.textContent = detail || '错误';
            sendBtn.disabled = true;
            break;
        case 'disconnected':
        default:
            text.textContent = detail || '未连接';
            sendBtn.disabled = true;
            break;
    }
}

// ============================================
// 发送文字
// ============================================

function initSendButtons() {
    ['mac1', 'mac2'].forEach(macId => {
        const btn = $(`${macId}-send-btn`);
        const input = $(`${macId}-input`);

        btn.addEventListener('click', () => sendText(macId));

        // 可选：按 Enter 发送（Shift+Enter 换行）
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendText(macId);
            }
        });
    });
}

async function sendText(macId) {
    const conn = connections[macId];
    const input = $(`${macId}-input`);
    const btn = $(`${macId}-send-btn`);
    const text = input.value.trim();

    if (!text || !conn.authenticated || !conn.ws) return;

    // 按钮动画
    btn.classList.add('sending');
    btn.disabled = true;
    const originalText = btn.textContent;
    btn.textContent = '发送中...';

    // 记录到本地历史（先记录，确保不丢）
    const historyEntry = {
        id: Date.now(),
        timestamp: new Date().toISOString(),
        text: text,
        target: macId,
        targetName: conn.hostname || macId,
        status: 'pending',
    };
    addHistory(historyEntry);

    // 发送
    try {
        const result = await new Promise((resolve, reject) => {
            conn._sendCallback = resolve;
            conn.ws.send(JSON.stringify({ action: 'type', text: text }));

            // 超时
            setTimeout(() => {
                if (conn._sendCallback) {
                    conn._sendCallback = null;
                    reject(new Error('超时'));
                }
            }, 5000);
        });

        if (result.status === 'ok') {
            historyEntry.status = 'success';
            btn.classList.add('success');
            btn.textContent = '✓';
            input.value = '';
        } else {
            historyEntry.status = 'failed';
            btn.classList.add('fail');
            btn.textContent = '✗';
        }
    } catch (e) {
        historyEntry.status = 'failed';
        btn.classList.add('fail');
        btn.textContent = '✗';
    }

    // 更新历史记录状态
    updateHistory(historyEntry);

    // 恢复按钮
    setTimeout(() => {
        btn.classList.remove('sending', 'success', 'fail');
        btn.textContent = originalText;
        btn.disabled = !conn.authenticated;
    }, 800);
}

// ============================================
// 历史记录（localStorage）
// ============================================

function getHistory() {
    const data = localStorage.getItem(HISTORY_KEY);
    return data ? JSON.parse(data) : [];
}

function addHistory(entry) {
    const history = getHistory();
    history.unshift(entry);
    localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
}

function exportHistory() {
    const history = getHistory();
    if (history.length === 0) {
        alert('没有历史记录可导出');
        return;
    }
    const data = JSON.stringify(history, null, 2);
    const blob = new Blob([data], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    const time = new Date().toISOString().slice(0, 10);
    a.href = url;
    a.download = `vibedrop_history_${time}.json`;
    a.click();
    URL.revokeObjectURL(url);
}

function updateHistory(entry) {
    const history = getHistory();
    const idx = history.findIndex(h => h.id === entry.id);
    if (idx !== -1) {
        history[idx] = entry;
        localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
    }
}

function clearHistory() {
    localStorage.removeItem(HISTORY_KEY);
    renderHistory();
}

// ---- 历史 UI ----

let currentFilter = 'all';

function initHistoryFilter() {
    document.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentFilter = btn.dataset.filter;
            renderHistory();
        });
    });

    $('clear-history-btn').addEventListener('click', () => {
        if (confirm('确定清空所有发送历史？')) {
            clearHistory();
        }
    });

    // 导出按钮
    const exportBtn = $('export-history-btn');
    if (exportBtn) {
        exportBtn.addEventListener('click', () => exportHistory());
    }
}

function renderHistory() {
    const list = $('history-list');
    let history = getHistory();

    // 筛选
    if (currentFilter !== 'all') {
        history = history.filter(h => h.target === currentFilter);
    }

    if (history.length === 0) {
        list.innerHTML = '<p class="empty-hint">暂无发送记录</p>';
        return;
    }

    list.innerHTML = history.map(h => {
        const time = formatTime(h.timestamp);
        const statusIcon = h.status === 'success' ? '✅' : h.status === 'failed' ? '❌' : '⏳';
        return `
            <div class="history-item">
                <div class="history-item-header">
                    <span class="history-time">${time}</span>
                    <span class="history-target">${h.targetName || h.target}</span>
                    <span class="history-status">${statusIcon}</span>
                </div>
                <div class="history-text">${escapeHtml(h.text)}</div>
            </div>
        `;
    }).join('');
}

function formatTime(isoString) {
    const d = new Date(isoString);
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();

    const time = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

    if (isToday) {
        return `今天 ${time}`;
    }

    const date = d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
    return `${date} ${time}`;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ---- Toast 提示 ----
function showToast(message) {
    let toast = document.getElementById('toast');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'toast';
        toast.style.cssText = 'position:fixed;top:80px;left:50%;transform:translateX(-50%);background:rgba(108,92,231,0.95);color:#fff;padding:10px 20px;border-radius:20px;font-size:14px;z-index:9999;transition:opacity 0.3s;pointer-events:none;';
        document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.style.opacity = '1';
    clearTimeout(toast._timer);
    toast._timer = setTimeout(() => { toast.style.opacity = '0'; }, 2000);
}

// ---- Service Worker 注册 ----
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => { });
}

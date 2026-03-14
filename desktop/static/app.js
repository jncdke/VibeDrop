// ============================================
// VibeDrop — 前端逻辑（动态多设备版）
// ============================================

// ---- 常量 ----
const HEARTBEAT_INTERVAL = 5000;
const HEARTBEAT_TIMEOUT = 10000;
const RECONNECT_INTERVAL = 3000;
const HISTORY_KEY = 'voicedrop_history';
const SETTINGS_KEY = 'voicedrop_settings';

// ---- 状态 ----
const connections = {}; // key = device.id, value = { ws, authenticated, hostname, ... }

// ---- DOM 缓存 ----
const $ = (id) => document.getElementById(id);

// ---- 初始化 ----
document.addEventListener('DOMContentLoaded', async () => {
    await loadPersistentHistory(); // 从文件恢复历史（优先于 localStorage）
    loadSettings();
    initNavigation();
    initSettingsButton();
    initHistoryActions();
});

// ---- 持久化存储（Tauri 文件系统）----
async function loadPersistentHistory() {
    if (!window.__TAURI__) return;
    try {
        const data = await window.__TAURI__.core.invoke('load_history');
        if (data && data !== '[]') {
            localStorage.setItem(HISTORY_KEY, data);
        }
    } catch (e) { console.log('加载持久化历史失败:', e); }
}

function persistHistory() {
    if (!window.__TAURI__) return;
    const data = localStorage.getItem(HISTORY_KEY) || '[]';
    window.__TAURI__.core.invoke('save_history', { data }).catch(() => {});
}

// ============================================
// 设备管理
// ============================================

function getDevices() {
    const saved = localStorage.getItem(SETTINGS_KEY);
    if (!saved) return [];
    const settings = JSON.parse(saved);

    // 向后兼容：旧格式 mac1Ip/mac2Ip → 新格式 devices[]
    if (!settings.devices) {
        const devices = [];
        if (settings.mac1Ip) {
            devices.push({
                id: 'dev_migrated_1',
                name: '设备 1',
                ip: settings.mac1Ip,
                port: settings.mac1Port || '9001',
                pin: settings.mac1Pin || ''
            });
        }
        if (settings.mac2Ip) {
            devices.push({
                id: 'dev_migrated_2',
                name: '设备 2',
                ip: settings.mac2Ip,
                port: settings.mac2Port || '9001',
                pin: settings.mac2Pin || ''
            });
        }
        if (devices.length > 0) {
            saveDevices(devices);
        }
        return devices;
    }

    return settings.devices;
}

function saveDevices(devices) {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ devices }));
}

function newDeviceId() {
    return 'dev_' + Date.now();
}

function ensureConnection(deviceId) {
    if (!connections[deviceId]) {
        connections[deviceId] = {
            ws: null, authenticated: false, hostname: null,
            heartbeatTimer: null, timeoutTimer: null, reconnectTimer: null,
            _sendCallback: null
        };
    }
    return connections[deviceId];
}

// ============================================
// 设置管理
// ============================================

function loadSettings() {
    const devices = getDevices();

    if (devices.length > 0) {
        renderDeviceCards(devices);
        renderSendCards(devices);
        renderHistoryFilters(devices);
        showView('send-view');
        connectAll();
    } else {
        // 首次打开：创建一个空设备
        const defaultDevices = [{ id: newDeviceId(), name: '设备 1', ip: '', port: '9001', pin: '' }];

        // 自动填充当前页面的 host（浏览器模式）
        const currentHost = window.location.hostname;
        const currentPort = window.location.port || '9001';
        if (currentHost && currentHost !== 'localhost' && currentHost !== '127.0.0.1') {
            defaultDevices[0].ip = currentHost;
            defaultDevices[0].port = currentPort;
        }

        renderDeviceCards(defaultDevices);
        renderSendCards(defaultDevices);
        renderHistoryFilters(defaultDevices);
    }
}

function saveSettingsFromUI() {
    const cards = document.querySelectorAll('#device-cards .settings-card');
    const devices = [];
    cards.forEach(card => {
        devices.push({
            id: card.dataset.deviceId,
            name: card.querySelector('.device-name-input').value.trim() || '未命名设备',
            ip: card.querySelector('.device-ip-input').value.trim(),
            port: card.querySelector('.device-port-input').value.trim() || '9001',
            pin: card.querySelector('.device-pin-input').value.trim(),
        });
    });
    saveDevices(devices);
    return devices;
}

function getDevicesFromUI() {
    const cards = document.querySelectorAll('#device-cards .settings-card');
    const devices = [];
    cards.forEach(card => {
        devices.push({
            id: card.dataset.deviceId,
            name: card.querySelector('.device-name-input').value.trim() || '未命名设备',
            ip: card.querySelector('.device-ip-input').value.trim(),
            port: card.querySelector('.device-port-input').value.trim() || '9001',
            pin: card.querySelector('.device-pin-input').value.trim(),
        });
    });
    return devices;
}

// ============================================
// 动态渲染 — 设置页设备卡片
// ============================================

function renderDeviceCards(devices) {
    const container = $('device-cards');
    container.innerHTML = '';

    devices.forEach((dev, i) => {
        const card = document.createElement('div');
        card.className = 'settings-card';
        card.dataset.deviceId = dev.id;
        card.innerHTML = `
            <div class="device-card-header">
                <input type="text" class="device-name-input" value="${escapeHtml(dev.name)}" placeholder="设备名称">
                <button class="device-delete-btn danger-btn-sm" title="删除">删除</button>
            </div>
            <div class="setting-row">
                <label>IP 地址</label>
                <input type="text" class="device-ip-input" value="${escapeHtml(dev.ip)}" placeholder="192.168.1.10">
            </div>
            <div class="setting-row">
                <label>端口</label>
                <input type="number" class="device-port-input" value="${escapeHtml(dev.port)}" placeholder="9001">
            </div>
            <div class="setting-row">
                <label>PIN 码</label>
                <input type="text" class="device-pin-input" value="${escapeHtml(dev.pin)}" placeholder="输入 PIN">
            </div>
        `;
        container.appendChild(card);

        // 删除按钮
        card.querySelector('.device-delete-btn').addEventListener('click', () => {
            const allCards = document.querySelectorAll('#device-cards .settings-card');
            if (allCards.length <= 1) {
                showToast('至少保留一个设备');
                return;
            }
            if (confirm(`确定删除"${dev.name}"？`)) {
                card.remove();
            }
        });
    });
}

// ============================================
// 动态渲染 — 发送页
// ============================================

function renderSendCards(devices) {
    const container = $('send-cards');
    container.innerHTML = '';

    devices.forEach(dev => {
        if (!dev.ip) return; // 未配置的设备不显示

        const card = document.createElement('div');
        card.className = 'mac-card';
        card.id = `card-${dev.id}`;
        card.innerHTML = `
            <div class="mac-header">
                <span class="status-dot" id="dot-${dev.id}"></span>
                <span class="mac-name" id="name-${dev.id}">${escapeHtml(dev.name)}</span>
                <span class="status-text" id="status-${dev.id}">未连接</span>
            </div>
            <div class="input-group">
                <textarea id="input-${dev.id}" placeholder="输入要发送的文字..." rows="3"></textarea>
                <div class="send-actions">
                    <button class="send-btn aux-btn" id="sendbtn-${dev.id}" disabled>发送</button>
                    <button class="send-btn aux-btn enter-btn" id="enterbtn-${dev.id}" disabled>回车</button>
                </div>
                <button class="send-btn combo-btn" id="sendenterbtn-${dev.id}" disabled>发送并回车</button>
            </div>
        `;
        container.appendChild(card);

        // 发送按钮
        const sendBtn = card.querySelector(`#sendbtn-${dev.id}`);
        const enterBtn = card.querySelector(`#enterbtn-${dev.id}`);
        const sendEnterBtn = card.querySelector(`#sendenterbtn-${dev.id}`);
        const input = card.querySelector('textarea');

        sendBtn.addEventListener('click', () => sendText(dev.id));
        enterBtn.addEventListener('click', () => sendEnter(dev.id));
        sendEnterBtn.addEventListener('click', () => sendTextAndEnter(dev.id));
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendText(dev.id);
            }
        });
    });
}

// ============================================
// 动态渲染 — 历史筛选按钮
// ============================================

function renderHistoryFilters(devices) {
    const container = $('history-filter-btns');
    container.innerHTML = '<button class="filter-btn active" data-filter="all">全部</button>';

    devices.forEach(dev => {
        if (!dev.ip) return;
        const btn = document.createElement('button');
        btn.className = 'filter-btn';
        btn.dataset.filter = dev.id;
        btn.textContent = dev.name;
        container.appendChild(btn);
    });

    // 绑定事件
    container.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            container.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            currentFilter = btn.dataset.filter;
            renderHistory();
        });
    });
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
        // 进入设置时重新加载设备卡片（丢弃未保存的更改）
        const devices = getDevices();
        if (devices.length === 0) {
            renderDeviceCards([{ id: newDeviceId(), name: '设备 1', ip: '', port: '9001', pin: '' }]);
        } else {
            renderDeviceCards(devices);
        }
        showView('settings-view');
        document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
    });

    // 返回按钮（不保存）
    $('settings-back-btn').addEventListener('click', () => {
        showView('send-view');
        $('nav-send-btn').classList.add('active');
    });

    // 添加设备
    $('add-device-btn').addEventListener('click', () => {
        const current = getDevicesFromUI();
        const newDev = { id: newDeviceId(), name: `设备 ${current.length + 1}`, ip: '', port: '9001', pin: '' };
        current.push(newDev);
        renderDeviceCards(current);
    });

    // 测试连接
    $('test-connection-btn').addEventListener('click', () => {
        testConnection();
    });

    // 保存并连接
    $('save-settings-btn').addEventListener('click', () => {
        const devices = saveSettingsFromUI();
        disconnectAll();
        renderSendCards(devices);
        renderHistoryFilters(devices);
        showView('send-view');
        $('nav-send-btn').classList.add('active');
        connectAll();
    });
}

function testConnection() {
    const resultDiv = $('test-result');
    resultDiv.style.display = 'block';
    resultDiv.innerHTML = '正在测试...';
    resultDiv.style.color = '#667085';

    const devices = getDevicesFromUI();

    function testOne(dev) {
        return new Promise((resolve) => {
            if (!dev.ip) { resolve(`${dev.name}: 未配置`); return; }
            const ws = new WebSocket(`ws://${dev.ip}:${dev.port}/ws`);
            const timeout = setTimeout(() => { ws.close(); resolve(`${dev.name}: 连接超时`); }, 3000);
            ws.onopen = () => {
                ws.send(JSON.stringify({ action: 'auth', pin: dev.pin }));
            };
            ws.onmessage = (e) => {
                clearTimeout(timeout);
                const data = JSON.parse(e.data);
                ws.close();
                if (data.status === 'ok') {
                    resolve(`${dev.name}: 已连接 (${data.hostname})`);
                } else {
                    resolve(`${dev.name}: ${data.error || 'PIN 错误'}`);
                }
            };
            ws.onerror = () => { clearTimeout(timeout); resolve(`${dev.name}: 无法连接`); };
        });
    }

    Promise.all(devices.map(d => testOne(d))).then(res => {
        resultDiv.innerHTML = res.join('<br>');
        resultDiv.style.color = '#344054';
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
    const devices = getDevices();
    devices.forEach(dev => {
        if (dev.ip) {
            connectDevice(dev.id, dev.ip, dev.port || '9001', dev.pin || '');
        }
    });
}

function disconnectAll() {
    Object.keys(connections).forEach(id => {
        const conn = connections[id];
        clearTimers(conn);
        if (conn.ws) {
            conn.ws.close();
            conn.ws = null;
        }
        conn.authenticated = false;
        conn.hostname = null;
        updateDeviceUI(id, 'disconnected');
    });
}

function connectDevice(deviceId, ip, port, pin) {
    const conn = ensureConnection(deviceId);

    clearTimers(conn);
    if (conn.ws) {
        conn.ws.close();
    }
    conn.authenticated = false;

    updateDeviceUI(deviceId, 'connecting');

    const url = `ws://${ip}:${port}/ws`;
    console.log(`[${deviceId}] 正在连接: ${url}`);
    let ws;
    try {
        ws = new WebSocket(url);
    } catch (e) {
        console.error(`[${deviceId}] WebSocket 创建失败:`, e);
        updateDeviceUI(deviceId, 'error', '连接失败');
        scheduleReconnect(deviceId, ip, port, pin);
        return;
    }

    conn.ws = ws;

    ws.onopen = () => {
        console.log(`[${deviceId}] WebSocket 已连接，发送 PIN 认证`);
        ws.send(JSON.stringify({ action: 'auth', pin: pin }));
        updateDeviceUI(deviceId, 'connecting', '认证中...');
    };

    ws.onmessage = (event) => {
        let data;
        try { data = JSON.parse(event.data); } catch { return; }

        if (data.action === 'pong') {
            clearTimeout(conn.timeoutTimer);
            return;
        }

        if (data.action === 'clipboard') {
            if (data.text) {
                writeClipboard(data.text).then(() => {
                    showToast('已同步到剪贴板');
                }).catch(() => {
                    showToast('剪贴板写入失败');
                });
            }
            return;
        }

        if (!conn.authenticated) {
            if (data.status === 'ok' && data.hostname) {
                conn.authenticated = true;
                conn.hostname = data.hostname;
                updateDeviceUI(deviceId, 'connected', data.hostname);
                startHeartbeat(deviceId, ip, port, pin);
            } else {
                updateDeviceUI(deviceId, 'error', data.error || 'PIN 错误');
            }
            return;
        }

        if (conn._sendCallback) {
            conn._sendCallback(data);
            conn._sendCallback = null;
        }
    };

    ws.onerror = () => {
        updateDeviceUI(deviceId, 'error', '连接出错');
    };

    ws.onclose = () => {
        conn.authenticated = false;
        updateDeviceUI(deviceId, 'disconnected', '已断开');
        clearTimers(conn);
        scheduleReconnect(deviceId, ip, port, pin);
    };
}

// ---- 心跳 ----

function startHeartbeat(deviceId, ip, port, pin) {
    const conn = connections[deviceId];
    clearTimers(conn);

    conn.heartbeatTimer = setInterval(() => {
        if (conn.ws && conn.ws.readyState === WebSocket.OPEN) {
            conn.ws.send(JSON.stringify({ action: 'ping' }));
            conn.timeoutTimer = setTimeout(() => {
                if (conn.ws) conn.ws.close();
            }, HEARTBEAT_TIMEOUT);
        }
    }, HEARTBEAT_INTERVAL);
}

function scheduleReconnect(deviceId, ip, port, pin) {
    const conn = connections[deviceId];
    if (!conn) return;
    clearTimeout(conn.reconnectTimer);
    conn.reconnectTimer = setTimeout(() => {
        connectDevice(deviceId, ip, port, pin);
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

function updateDeviceUI(deviceId, status, detail) {
    const dot = $(`dot-${deviceId}`);
    const name = $(`name-${deviceId}`);
    const text = $(`status-${deviceId}`);
    const card = $(`card-${deviceId}`);
    const sendBtn = $(`sendbtn-${deviceId}`);
    const enterBtn = $(`enterbtn-${deviceId}`);
    const sendEnterBtn = $(`sendenterbtn-${deviceId}`);

    if (!dot || !card) return; // 设备卡片可能不存在

    dot.className = 'status-dot';
    card.className = 'mac-card';

    switch (status) {
        case 'connected':
            dot.classList.add('connected');
            card.classList.add('connected');
            text.textContent = '已连接';
            if (sendBtn) sendBtn.disabled = false;
            if (enterBtn) enterBtn.disabled = false;
            if (sendEnterBtn) sendEnterBtn.disabled = false;
            if (detail) name.textContent = detail;
            break;
        case 'connecting':
            dot.classList.add('connecting');
            text.textContent = detail || '连接中...';
            if (sendBtn) sendBtn.disabled = true;
            if (enterBtn) enterBtn.disabled = true;
            if (sendEnterBtn) sendEnterBtn.disabled = true;
            break;
        case 'error':
            dot.classList.add('error');
            card.classList.add('error');
            text.textContent = detail || '错误';
            if (sendBtn) sendBtn.disabled = true;
            if (enterBtn) enterBtn.disabled = true;
            if (sendEnterBtn) sendEnterBtn.disabled = true;
            break;
        case 'disconnected':
        default:
            text.textContent = detail || '未连接';
            if (sendBtn) sendBtn.disabled = true;
            if (enterBtn) enterBtn.disabled = true;
            if (sendEnterBtn) sendEnterBtn.disabled = true;
            break;
    }
}

// ============================================
// 发送文字
// ============================================

function setActionButtonsDisabled(deviceId, disabled) {
    const sendBtn = $(`sendbtn-${deviceId}`);
    const enterBtn = $(`enterbtn-${deviceId}`);
    const sendEnterBtn = $(`sendenterbtn-${deviceId}`);
    if (sendBtn) sendBtn.disabled = disabled;
    if (enterBtn) enterBtn.disabled = disabled;
    if (sendEnterBtn) sendEnterBtn.disabled = disabled;
}

async function sendDeviceAction(deviceId, {
    action,
    payload = {},
    buttonId,
    pendingText,
    clearInput = false,
    historyEntry = null,
    failureToast = false,
}) {
    const conn = connections[deviceId];
    const btn = $(buttonId);
    const input = $(`input-${deviceId}`);

    if (!btn || !conn || !conn.authenticated || !conn.ws) {
        return { ok: false, error: '未连接' };
    }

    const originalText = btn.textContent;
    btn.classList.add('sending');
    btn.textContent = pendingText;
    setActionButtonsDisabled(deviceId, true);

    try {
        const result = await new Promise((resolve, reject) => {
            conn._sendCallback = resolve;
            conn.ws.send(JSON.stringify({ action, ...payload }));
            setTimeout(() => {
                if (conn._sendCallback) {
                    conn._sendCallback = null;
                    reject(new Error('超时'));
                }
            }, 5000);
        });

        if (result.status === 'ok') {
            if (historyEntry) {
                historyEntry.status = 'success';
                updateHistory(historyEntry);
            }
            btn.classList.add('success');
            btn.textContent = '✓';
            if (clearInput && input) {
                input.value = '';
            }
            return { ok: true };
        }

        if (historyEntry) {
            historyEntry.status = 'failed';
            updateHistory(historyEntry);
        }
        if (failureToast) {
            showToast(result.error || '操作失败');
        }
        btn.classList.add('fail');
        btn.textContent = '✗';
        return { ok: false, error: result.error || '操作失败' };
    } catch (e) {
        if (historyEntry) {
            historyEntry.status = 'failed';
            updateHistory(historyEntry);
        }
        if (failureToast) {
            showToast(e.message || '操作失败');
        }
        btn.classList.add('fail');
        btn.textContent = '✗';
        return { ok: false, error: e.message || '操作失败' };
    } finally {
        setTimeout(() => {
            btn.classList.remove('sending', 'success', 'fail');
            btn.textContent = originalText;
            setActionButtonsDisabled(deviceId, !conn.authenticated);
        }, 800);
    }

}

async function sendText(deviceId) {
    const conn = connections[deviceId];
    const input = $(`input-${deviceId}`);
    const text = input.value.trim();

    if (!text || !conn || !conn.authenticated || !conn.ws) return;

    // 查找设备名称
    const devices = getDevices();
    const dev = devices.find(d => d.id === deviceId);
    const targetName = conn.hostname || (dev ? dev.name : deviceId);

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: text,
        target: deviceId,
        targetName: targetName,
        status: 'pending',
    };
    addHistory(historyEntry);

    await sendDeviceAction(deviceId, {
        action: 'type',
        payload: { text },
        buttonId: `sendbtn-${deviceId}`,
        pendingText: '发送中...',
        clearInput: true,
        historyEntry,
    });
}

async function sendEnter(deviceId) {
    await sendDeviceAction(deviceId, {
        action: 'enter',
        buttonId: `enterbtn-${deviceId}`,
        pendingText: '回车中...',
    });
}

async function sendTextAndEnter(deviceId) {
    const conn = connections[deviceId];
    const input = $(`input-${deviceId}`);
    const text = input.value.trim();

    if (!text || !conn || !conn.authenticated || !conn.ws) return;

    const devices = getDevices();
    const dev = devices.find(d => d.id === deviceId);
    const targetName = conn.hostname || (dev ? dev.name : deviceId);

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text,
        target: deviceId,
        targetName,
        status: 'pending',
    };
    addHistory(historyEntry);

    const result = await sendDeviceAction(deviceId, {
        action: 'type_enter',
        payload: { text },
        buttonId: `sendenterbtn-${deviceId}`,
        pendingText: '发送并回车中...',
        clearInput: true,
        historyEntry,
        failureToast: true,
    });

    if (!result.ok && result.error && result.error.startsWith('文字已发送')) {
        historyEntry.status = 'success';
        updateHistory(historyEntry);
        input.value = '';
    }
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
    persistHistory();
}

function pad2(value) {
    return String(value).padStart(2, '0');
}

function getLocalTimestamp(value = new Date()) {
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) {
        return new Date().toISOString();
    }

    const year = date.getFullYear();
    const month = pad2(date.getMonth() + 1);
    const day = pad2(date.getDate());
    const hour = pad2(date.getHours());
    const minute = pad2(date.getMinutes());
    const second = pad2(date.getSeconds());
    const millisecond = String(date.getMilliseconds()).padStart(3, '0');

    const offsetMinutes = -date.getTimezoneOffset();
    const sign = offsetMinutes >= 0 ? '+' : '-';
    const absoluteOffset = Math.abs(offsetMinutes);
    const offsetHour = pad2(Math.floor(absoluteOffset / 60));
    const offsetMinute = pad2(absoluteOffset % 60);

    return `${year}-${month}-${day}T${hour}:${minute}:${second}.${millisecond}${sign}${offsetHour}:${offsetMinute}`;
}

function buildHistoryExportData(history) {
    return history.map(entry => ({
        ...entry,
        timestamp: getLocalTimestamp(entry.timestamp),
    }));
}

function normalizeImportedHistoryEntry(entry) {
    const timestampSource = entry.timestamp_iso || entry.timestamp;
    const normalizedTimestamp = getLocalTimestamp(timestampSource);

    return {
        ...entry,
        timestamp: normalizedTimestamp,
    };
}

function waitForNativeBridgeEvent(eventName, invokeNative) {
    return new Promise((resolve, reject) => {
        let settled = false;

        const handler = (event) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            window.removeEventListener(eventName, handler);
            resolve(event.detail || {});
        };

        const timer = setTimeout(() => {
            if (settled) return;
            settled = true;
            window.removeEventListener(eventName, handler);
            reject(new Error('原生操作超时'));
        }, 15000);

        window.addEventListener(eventName, handler);

        try {
            invokeNative();
        } catch (error) {
            settled = true;
            clearTimeout(timer);
            window.removeEventListener(eventName, handler);
            reject(error);
        }
    });
}

async function exportHistory() {
    const history = getHistory();
    if (history.length === 0) {
        showToast('暂无历史可导出');
        return;
    }
    const data = JSON.stringify(buildHistoryExportData(history), null, 2);
    const now = new Date();
    const ts = now.getFullYear()
        + '-' + String(now.getMonth() + 1).padStart(2, '0')
        + '-' + String(now.getDate()).padStart(2, '0')
        + '_' + String(now.getHours()).padStart(2, '0')
        + '-' + String(now.getMinutes()).padStart(2, '0')
        + '-' + String(now.getSeconds()).padStart(2, '0');
    const filename = `vibedrop_history_${ts}.json`;
    const mimeType = 'application/json';

    if (window.NativeShare && window.NativeShare.exportHistory) {
        try {
            const result = await waitForNativeBridgeEvent('native-export-result', () => {
                window.NativeShare.exportHistory(filename, data);
            });

            if (result.cancelled) {
                showToast(result.message || '已取消导出');
                return;
            }

            if (!result.ok) {
                throw new Error(result.error || '导出失败');
            }

            showToast(result.message || `已导出 ${filename}`);
        } catch (err) {
            showToast('导出失败：' + err.message);
        }
        return;
    }

    if (window.showSaveFilePicker) {
        try {
            const handle = await window.showSaveFilePicker({
                suggestedName: filename,
                types: [{ description: 'JSON 文件', accept: { [mimeType]: ['.json'] } }],
            });
            const writable = await handle.createWritable();
            await writable.write(data);
            await writable.close();
            showToast('已导出到你选择的位置');
        } catch (err) {
            if (err.name !== 'AbortError') {
                showToast('导出失败：' + err.message);
            }
        }
        return;
    }

    const blob = new Blob([data], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
    showToast('导出成功');
}

async function shareHistory() {
    const history = getHistory();
    if (history.length === 0) {
        showToast('暂无历史可分享');
        return;
    }

    const data = JSON.stringify(buildHistoryExportData(history), null, 2);
    const now = new Date();
    const ts = now.getFullYear()
        + '-' + String(now.getMonth() + 1).padStart(2, '0')
        + '-' + String(now.getDate()).padStart(2, '0')
        + '_' + String(now.getHours()).padStart(2, '0')
        + '-' + String(now.getMinutes()).padStart(2, '0')
        + '-' + String(now.getSeconds()).padStart(2, '0');
    const filename = `vibedrop_history_${ts}.json`;
    const mimeType = 'application/json';

    if (window.NativeShare && window.NativeShare.shareHistory) {
        try {
            const result = await waitForNativeBridgeEvent('native-share-result', () => {
                window.NativeShare.shareHistory(filename, data);
            });

            if (!result.ok) {
                throw new Error(result.error || '分享失败');
            }

            showToast(result.message || '已打开分享面板');
        } catch (err) {
            showToast('分享失败：' + err.message);
        }
        return;
    }

    if (navigator.share) {
        try {
            const file = new File([data], filename, { type: mimeType });
            if (navigator.canShare && navigator.canShare({ files: [file] })) {
                await navigator.share({
                    title: 'VibeDrop 历史记录',
                    text: 'VibeDrop 导出的历史记录',
                    files: [file],
                });
            } else {
                await navigator.share({
                    title: 'VibeDrop 历史记录',
                    text: data,
                });
            }
            showToast('已打开分享面板');
        } catch (err) {
            if (err.name !== 'AbortError') {
                showToast('分享失败：' + err.message);
            }
        }
        return;
    }

    showToast('当前环境不支持系统分享');
}

function updateHistory(entry) {
    const history = getHistory();
    const idx = history.findIndex(h => h.id === entry.id);
    if (idx !== -1) {
        history[idx] = entry;
        localStorage.setItem(HISTORY_KEY, JSON.stringify(history));
        persistHistory();
    }
}

function clearHistory() {
    localStorage.removeItem(HISTORY_KEY);
    persistHistory();
    renderHistory();
}

// ---- 历史 UI ----

let currentFilter = 'all';

function initHistoryActions() {
    $('clear-history-btn').addEventListener('click', () => {
        if (confirm('确定清空所有发送历史？')) {
            clearHistory();
        }
    });

    const exportBtn = $('export-history-btn');
    if (exportBtn) {
        exportBtn.addEventListener('click', () => exportHistory());
    }

    const shareBtn = $('share-history-btn');
    if (shareBtn) {
        shareBtn.addEventListener('click', () => shareHistory());
    }

    const importBtn = $('import-history-btn');
    const importInput = $('import-file-input');
    if (importBtn && importInput) {
        importBtn.addEventListener('click', () => importInput.click());
        importInput.addEventListener('change', (e) => {
            if (e.target.files.length > 0) {
                importHistory(e.target.files[0]);
                e.target.value = '';
            }
        });
    }
}

function importHistory(file) {
    const resultDiv = $('import-result');
    resultDiv.style.display = 'block';
    resultDiv.innerHTML = '正在导入...';
    resultDiv.style.color = '#667085';

    const reader = new FileReader();
    reader.onload = (e) => {
        try {
            const imported = JSON.parse(e.target.result);
            if (!Array.isArray(imported)) {
                resultDiv.innerHTML = '文件格式错误，需要 JSON 数组';
                resultDiv.style.color = '#c73b31';
                return;
            }

            const existing = getHistory();
            const existingKeys = new Set(
                existing.map(h => `${h.timestamp}|${h.text}`)
            );

            let added = 0;
            let skipped = 0;

            for (const rawEntry of imported) {
                const entry = normalizeImportedHistoryEntry(rawEntry);
                const key = `${entry.timestamp}|${entry.text}`;
                if (existingKeys.has(key)) {
                    skipped++;
                } else {
                    existing.push(entry);
                    existingKeys.add(key);
                    added++;
                }
            }

            existing.sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp));
            localStorage.setItem(HISTORY_KEY, JSON.stringify(existing));
            persistHistory();

            resultDiv.innerHTML = `已导入 ${added} 条，跳过 ${skipped} 条重复`;
            resultDiv.style.color = '#147d33';
        } catch (err) {
            resultDiv.innerHTML = `解析失败：${err.message}`;
            resultDiv.style.color = '#c73b31';
        }
    };
    reader.readAsText(file);
}

function renderHistory() {
    const list = $('history-list');
    if (!list) return;
    const history = getHistory();

    const filtered = currentFilter === 'all'
        ? history
        : history.filter(h => h.target === currentFilter);

    if (filtered.length === 0) {
        list.innerHTML = '<p class="empty-hint">暂无发送记录</p>';
        return;
    }

    list.innerHTML = filtered.map((h, i) => {
        const time = formatTime(h.timestamp);
        const statusIcon = h.status === 'success' ? '已送达' : h.status === 'failed' ? '失败' : '发送中';
        return `
            <div class="history-item" data-idx="${i}" style="cursor:pointer" title="点击复制">
                <div class="history-item-header">
                    <span class="history-time">${time}</span>
                    <span class="history-target">${h.targetName || h.target}</span>
                    <span class="history-status">${statusIcon}</span>
                </div>
                <div class="history-text">${escapeHtml(h.text)}</div>
            </div>
        `;
    }).join('');

    // 点击复制
    list.querySelectorAll('.history-item').forEach((item, i) => {
        item.addEventListener('click', () => {
            const text = filtered[i].text;
            writeClipboard(text).then(() => {
                showToast('已复制');
            }).catch(() => {
                showToast('复制失败');
            });
        });
    });
}

function formatTime(isoString) {
    const d = new Date(isoString);
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();
    const time = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    if (isToday) return `今天 ${time}`;
    const date = d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' });
    return `${date} ${time}`;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// ---- 剪贴板写入（透明 Activity 优先 → Tauri 原生 → 浏览器 API）----
async function writeClipboard(text) {
    // 方案 1：透明 Activity 写入（绕过 Android 后台限制）
    if (window.NativeClipboard) {
        try {
            window.NativeClipboard.writeText(text);
            return; // 透明 Activity 是异步的，无法等结果，但成功率最高
        } catch (e) {
            console.warn('NativeClipboard 调用失败，降级到 Tauri 插件', e);
        }
    }
    // 方案 2：Tauri 原生插件
    if (window.__TAURI__ && window.__TAURI__.clipboardManager) {
        return window.__TAURI__.clipboardManager.writeText(text);
    }
    // 方案 3：浏览器 API
    if (navigator.clipboard) {
        return navigator.clipboard.writeText(text);
    }
    throw new Error('无可用剪贴板 API');
}

// ---- Toast 提示 ----
function showToast(message) {
    let toast = document.getElementById('toast');
    if (!toast) {
        toast = document.createElement('div');
        toast.id = 'toast';
        toast.style.cssText = 'position:fixed;top:92px;left:50%;transform:translateX(-50%);background:rgba(28,28,30,0.9);color:#fff;padding:12px 18px;border-radius:18px;font-size:13px;font-weight:600;z-index:9999;transition:opacity 0.18s ease;pointer-events:none;backdrop-filter:blur(18px);box-shadow:0 18px 34px rgba(15,23,42,0.18);border:1px solid rgba(255,255,255,0.14);';
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

// ---- Service Worker 注册 ----
if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
}

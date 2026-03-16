// ============================================
// VibeDrop — 前端逻辑（动态多设备版）
// ============================================

// ---- 常量 ----
const HEARTBEAT_INTERVAL = 5000;
const HEARTBEAT_TIMEOUT = 10000;
const RECONNECT_INTERVAL = 3000;
const HISTORY_KEY = 'voicedrop_history';
const SETTINGS_KEY = 'voicedrop_settings';
const CLIENT_IDENTITY_KEY = 'vibedrop_client_identity';
const MAX_IMAGE_FILE_BYTES = 10 * 1024 * 1024;
const MAX_FILE_TRANSFER_BYTES = 32 * 1024 * 1024;
const HEATMAP_VISIBLE_DAYS = 5;
const HEATMAP_RENDER_BUFFER_DAYS = 14;
const HEATMAP_HOUR_SLOTS = 24;
const HEATMAP_COLUMN_STEP = 52;
const HEATMAP_DRAG_THRESHOLD = 8;
const HEATMAP_INERTIA_MS = 180;
const DESKTOP_DISCOVERY_DEFAULT_PORT = 9001;
const DESKTOP_PAIRING_POLL_MS = 1200;
const DEFAULT_HISTORY_FILTERS = {
    device: 'all',
    quickTime: 'all',
    startDate: '',
    endDate: '',
    timeRange: 'all',
    startTime: '',
    endTime: '',
    kind: 'all',
    status: 'all',
};

// ---- 状态 ----
const connections = {}; // key = device.id, value = { ws, authenticated, hostname, ... }
const incomingDesktopTransfers = {};
let pendingSharedContent = null;
let currentHistoryFilters = { ...DEFAULT_HISTORY_FILTERS };
let historyDatePickerState = {
    field: null,
    month: null,
    availableDates: new Set(),
    minDate: '',
    maxDate: '',
};
let historyHeatmapState = {
    viewportEndDate: '',
    rangeToken: '',
    selectionDate: '',
    selectionHour: null,
    dragPointerId: null,
    dragStartX: 0,
    dragOffsetX: 0,
    dragLastX: 0,
    dragLastTime: 0,
    dragVelocity: 0,
    dragMoved: false,
    dragMinOffsetX: 0,
    dragMaxOffsetX: 0,
    suppressCellClickUntil: 0,
};
let nearbyDesktopState = {
    items: [],
    scanning: false,
    scanError: '',
    pairing: null,
    pollTimer: null,
};

// ---- DOM 缓存 ----
const $ = (id) => document.getElementById(id);
let clientIdentity = getClientIdentity();

// ---- 初始化 ----
document.addEventListener('DOMContentLoaded', async () => {
    clientIdentity = getClientIdentity();
    await loadPersistentHistory(); // 从文件恢复历史（优先于 localStorage）
    loadSettings();
    syncNativeBackgroundClipboardConfig(clientIdentity);
    initNavigation();
    initSettingsButton();
    initNearbyDesktopDiscovery();
    initHistoryActions();
    initHistoryFilterControls();
    initHistoryHeatmapInteractions();
    initNativeShareInbox();
    await loadPendingSharedContent();
    void discoverNearbyDesktops({ silent: true, syncKnownDevices: true });
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

function syncNativeBackgroundClipboardConfig(identity = clientIdentity) {
    if (!window.NativeBackgroundClipboard || !window.NativeBackgroundClipboard.syncConfig) {
        return;
    }

    try {
        const devices = getDevices()
            .filter((device) => device && device.id && device.ip && device.pin)
            .map((device) => ({
                id: device.id,
                name: device.name || '设备',
                ip: String(device.ip || '').trim(),
                port: String(device.port || '9001').trim() || '9001',
                pin: String(device.pin || '').trim(),
            }));
        const payload = JSON.stringify({
            identity: identity ? {
                id: identity.id,
                name: identity.name,
            } : null,
            devices,
        });
        window.NativeBackgroundClipboard.syncConfig(payload);
    } catch (error) {
        console.warn('同步后台剪贴板配置失败', error);
    }
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
    syncNativeBackgroundClipboardConfig();
}

function newDeviceId() {
    return 'dev_' + Date.now();
}

function ensureConnection(deviceId) {
    if (!connections[deviceId]) {
        connections[deviceId] = {
            ws: null, authenticated: false, hostname: null,
            heartbeatTimer: null, timeoutTimer: null, reconnectTimer: null,
            _sendCallback: null,
            incomingTransferQueue: Promise.resolve(),
        };
    }
    return connections[deviceId];
}

function getClientIdentity() {
    const nativeInfo = getNativeDeviceInfo();

    try {
        const raw = localStorage.getItem(CLIENT_IDENTITY_KEY);
        if (raw) {
            const parsed = JSON.parse(raw);
            if (parsed && parsed.id) {
                const identity = {
                    id: parsed.id,
                    name: buildClientDisplayName(parsed.id, nativeInfo) || parsed.name || buildClientDisplayName(parsed.id),
                };
                persistClientIdentity(identity);
                return identity;
            }
        }
    } catch (error) {
        console.warn('读取客户端标识失败，使用新标识', error);
    }

    const id = `client_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
    const identity = {
        id,
        name: buildClientDisplayName(id, nativeInfo),
    };
    persistClientIdentity(identity);
    return identity;
}

function persistClientIdentity(identity) {
    try {
        localStorage.setItem(CLIENT_IDENTITY_KEY, JSON.stringify(identity));
        syncNativeBackgroundClipboardConfig(identity);
    } catch (error) {
        console.warn('保存客户端标识失败', error);
    }
}

function getNativeDeviceInfo() {
    if (!window.NativeDevice || !window.NativeDevice.getDeviceInfo) {
        return null;
    }

    try {
        const raw = window.NativeDevice.getDeviceInfo();
        if (!raw) {
            return null;
        }
        const parsed = typeof raw === 'string' ? JSON.parse(raw) : raw;
        return parsed && typeof parsed === 'object' ? parsed : null;
    } catch (error) {
        console.warn('读取原生设备信息失败', error);
        return null;
    }
}

function buildClientDisplayName(clientId, nativeInfo = null) {
    const preferredName = nativeInfo?.friendlyName || nativeInfo?.marketName;
    if (preferredName) {
        return preferredName;
    }

    const suffix = String(clientId || '').slice(-4).toUpperCase();
    const label = supportsNativeFileReceive() ? 'Android 手机' : '移动浏览器';
    return suffix ? `${label} ${suffix}` : label;
}

function supportsNativeFileReceive() {
    return Boolean(window.__TAURI__ && window.__TAURI__.core && typeof window.__TAURI__.core.invoke === 'function');
}

function invokeNative(command, payload = {}) {
    if (!supportsNativeFileReceive()) {
        return Promise.reject(new Error('当前环境不支持原生命令'));
    }
    return window.__TAURI__.core.invoke(command, payload);
}

function supportsNearbyDesktopDiscovery() {
    return supportsNativeFileReceive();
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
// 自动发现桌面端 / 配对
// ============================================

function initNearbyDesktopDiscovery() {
    $('scan-nearby-desktops-btn')?.addEventListener('click', () => {
        void discoverNearbyDesktops();
    });

    $('desktop-pairing-close-btn')?.addEventListener('click', closeDesktopPairingModal);
    $('desktop-pairing-cancel-btn')?.addEventListener('click', closeDesktopPairingModal);
    $('desktop-pairing-modal')?.addEventListener('click', (event) => {
        if (event.target === $('desktop-pairing-modal')) {
            closeDesktopPairingModal();
        }
    });

    renderNearbyDesktops();
}

async function discoverNearbyDesktops({ silent = false, syncKnownDevices = false } = {}) {
    if (!supportsNearbyDesktopDiscovery()) {
        nearbyDesktopState.scanError = '当前浏览器模式不支持自动扫描，请继续使用手动 IP / PIN。';
        nearbyDesktopState.items = [];
        nearbyDesktopState.scanning = false;
        renderNearbyDesktops();
        return [];
    }

    nearbyDesktopState.scanning = true;
    nearbyDesktopState.scanError = '';
    renderNearbyDesktops();

    try {
        const discovered = await invokeNative('discover_desktops');
        nearbyDesktopState.items = Array.isArray(discovered) ? discovered.map((item) => ({
            ...item,
            ip: item.ip || '',
            hostname: item.hostname || '未命名电脑',
            port: Number(item.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            server_id: item.server_id || '',
        })) : [];

        if (syncKnownDevices && nearbyDesktopState.items.length) {
            syncKnownDevicesWithDiscovery(nearbyDesktopState.items);
        }
        renderNearbyDesktops();
        return nearbyDesktopState.items;
    } catch (error) {
        console.error('扫描附近电脑失败:', error);
        nearbyDesktopState.items = [];
        nearbyDesktopState.scanError = error?.message || String(error);
        renderNearbyDesktops();
        if (!silent) {
            showToast(`扫描失败：${nearbyDesktopState.scanError}`);
        }
        return [];
    } finally {
        nearbyDesktopState.scanning = false;
        renderNearbyDesktops();
    }
}

function renderNearbyDesktops() {
    const list = $('nearby-desktops-list');
    const empty = $('nearby-desktops-empty');
    const caption = $('nearby-desktops-caption');
    const scanBtn = $('scan-nearby-desktops-btn');
    if (!list || !empty || !caption || !scanBtn) return;

    scanBtn.textContent = nearbyDesktopState.scanning ? '扫描中…' : '扫描';
    scanBtn.disabled = nearbyDesktopState.scanning;

    if (!supportsNearbyDesktopDiscovery()) {
        list.innerHTML = '';
        empty.textContent = '当前浏览器模式不支持局域网自动发现，请使用手动输入的方式连接桌面端。';
        empty.classList.remove('hidden');
        caption.textContent = '安装后的手机 App 支持自动扫描附近电脑；浏览器模式仅保留手动连接。';
        return;
    }

    caption.textContent = nearbyDesktopState.scanning
        ? '正在扫描当前局域网中的 VibeDrop 桌面端…'
        : '自动扫描当前局域网中的 VibeDrop 桌面端，发起配对后即可保存并自动连接。';

    const devices = getDevices();
    const visibleItems = buildVisibleNearbyDesktops(devices);

    if (!visibleItems.length) {
        list.innerHTML = '';
        empty.textContent = nearbyDesktopState.scanError
            ? `扫描失败：${nearbyDesktopState.scanError}`
            : nearbyDesktopState.scanning
                ? '正在搜索附近电脑…'
                : '还没有发现附近的桌面端。确认电脑端已打开，且与手机在同一局域网后再试。';
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    list.innerHTML = visibleItems.map((desktop) => {
        const matched = resolveNearbyDesktopMatch(desktop, devices);
        const isConnected = matched && connections[matched.id]?.authenticated;
        const badgeText = isConnected ? '已连接' : matched ? '已配对' : '附近电脑';
        const primaryLabel = isConnected ? '已连接' : matched ? '连接' : '请求配对';
        const primaryClass = matched ? 'secondary-btn' : 'primary-btn';
        const secondaryLabel = desktop.source === 'saved' ? '查看参数' : '填入配置';
        const disabledAttr = nearbyDesktopState.pairing ? 'disabled' : '';

        return `
            <div class="nearby-desktop-item">
                <div class="nearby-desktop-top">
                    <div>
                        <div class="nearby-desktop-name">${escapeHtml(desktop.hostname)}</div>
                        <div class="nearby-desktop-meta">${escapeHtml(desktop.ip)}:${escapeHtml(String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT))}</div>
                    </div>
                    <span class="nearby-desktop-badge${matched ? ' is-paired' : ''}">${escapeHtml(badgeText)}</span>
                </div>
                <div class="nearby-desktop-actions">
                    <button type="button" class="${primaryClass}" data-desktop-action="${matched ? 'connect' : 'pair'}" data-nearby-key="${escapeHtml(getNearbyDesktopKey(desktop))}" ${disabledAttr}>${escapeHtml(primaryLabel)}</button>
                    <button type="button" class="secondary-btn" data-desktop-action="${desktop.source === 'saved' ? 'advanced' : 'fill'}" data-nearby-key="${escapeHtml(getNearbyDesktopKey(desktop))}" ${disabledAttr}>${escapeHtml(secondaryLabel)}</button>
                </div>
            </div>
        `;
    }).join('');

    list.querySelectorAll('[data-desktop-action]').forEach((button) => {
        button.addEventListener('click', async () => {
            const nearbyKey = button.dataset.nearbyKey;
            const action = button.dataset.desktopAction;
            const desktop = visibleItems.find((item) => getNearbyDesktopKey(item) === nearbyKey);
            if (!desktop) return;
            const matched = resolveNearbyDesktopMatch(desktop, devices);
            if (action === 'advanced') {
                $('advanced-connect-panel')?.setAttribute('open', 'open');
                return;
            }
            if (action === 'fill') {
                fillDesktopIntoSettings(desktop);
                return;
            }
            if (action === 'connect') {
                if (desktop.source === 'saved' && matched) {
                    connectDevice(matched.id, matched.ip, matched.port || '9001', matched.pin || '');
                    showToast(`正在连接 ${matched.name}`);
                    return;
                }
                connectMatchedDesktop(desktop);
                return;
            }
            await startDesktopPairing(desktop);
        });
    });
}

function getNearbyDesktopKey(desktop) {
    if (desktop.server_id) return `server:${desktop.server_id}`;
    if (desktop.deviceId) return `device:${desktop.deviceId}`;
    return `ip:${desktop.ip || ''}:${String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT)}`;
}

function resolveNearbyDesktopMatch(desktop, devices = getDevices()) {
    if (desktop.deviceId) {
        return devices.find((device) => device.id === desktop.deviceId) || null;
    }
    return matchSavedDevice(desktop, devices);
}

function buildVisibleNearbyDesktops(devices = getDevices()) {
    const discovered = nearbyDesktopState.items.map((desktop) => ({
        ...desktop,
        source: 'discovered',
    }));
    const seen = new Set(discovered.map((desktop) => getNearbyDesktopKey(desktop)));
    const saved = devices
        .filter((device) => device && (device.ip || device.serverId))
        .map((device) => ({
            server_id: device.serverId || '',
            hostname: device.name || '未命名电脑',
            ip: device.ip || '',
            port: Number(device.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            source: 'saved',
            deviceId: device.id,
        }))
        .filter((desktop) => {
            const key = getNearbyDesktopKey(desktop);
            if (seen.has(key)) {
                return false;
            }
            seen.add(key);
            return true;
        });

    return [...discovered, ...saved];
}

function matchSavedDevice(desktop, devices = getDevices()) {
    return devices.find((device) => (
        (device.serverId && desktop.server_id && device.serverId === desktop.server_id)
        || (!device.serverId && device.ip === desktop.ip && String(device.port || DESKTOP_DISCOVERY_DEFAULT_PORT) === String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT))
    )) || null;
}

function syncKnownDevicesWithDiscovery(discovered) {
    const devices = getDevices();
    if (!devices.length) return;

    let changed = false;
    discovered.forEach((desktop) => {
        const matched = matchSavedDevice(desktop, devices);
        if (!matched) return;
        if (matched.ip !== desktop.ip || String(matched.port || DESKTOP_DISCOVERY_DEFAULT_PORT) !== String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT) || matched.name !== desktop.hostname) {
            matched.ip = desktop.ip;
            matched.port = String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
            matched.name = desktop.hostname;
            matched.serverId = desktop.server_id || matched.serverId || '';
            changed = true;
            if (connections[matched.id]) {
                connectDevice(matched.id, matched.ip, matched.port, matched.pin || '');
            }
        }
    });

    if (!changed) return;

    saveDevices(devices);
    renderDeviceCards(devices);
    renderSendCards(devices);
    renderHistoryFilters(devices);
}

function fillDesktopIntoSettings(desktop) {
    const devices = getDevicesFromUI();
    const matched = matchSavedDevice(desktop, devices);
    if (matched) {
        matched.name = desktop.hostname;
        matched.ip = desktop.ip;
        matched.port = String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
        matched.serverId = desktop.server_id || matched.serverId || '';
    } else {
        devices.unshift({
            id: newDeviceId(),
            name: desktop.hostname,
            ip: desktop.ip,
            port: String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            pin: '',
            serverId: desktop.server_id || '',
        });
    }
    renderDeviceCards(devices);
    showToast('已填入桌面端配置');
}

function connectMatchedDesktop(desktop) {
    const devices = getDevices();
    const matched = matchSavedDevice(desktop, devices);
    if (!matched || !matched.pin) {
        fillDesktopIntoSettings(desktop);
        showToast('这个电脑还没完成配对，请先请求配对');
        return;
    }

    matched.name = desktop.hostname;
    matched.ip = desktop.ip;
    matched.port = String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
    matched.serverId = desktop.server_id || matched.serverId || '';
    saveDevices(devices);
    renderDeviceCards(devices);
    renderSendCards(devices);
    renderHistoryFilters(devices);
    connectDevice(matched.id, matched.ip, matched.port, matched.pin || '');
    showToast(`正在连接 ${matched.name}`);
}

async function startDesktopPairing(desktop) {
    if (nearbyDesktopState.pairing) {
        showToast('已有一个配对请求正在等待确认');
        return;
    }

    try {
        const response = await invokeNative('request_desktop_pairing', {
            ip: desktop.ip,
            port: Number(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            clientId: clientIdentity.id,
            clientName: clientIdentity.name,
        });
        nearbyDesktopState.pairing = {
            requestId: response.request_id,
            code: response.code,
            hostname: response.hostname || desktop.hostname,
            ip: desktop.ip,
            port: Number(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            serverId: desktop.server_id || '',
            expiresAt: Date.now() + Number(response.expires_in_secs || 120) * 1000,
        };
        openDesktopPairingModal();
        startDesktopPairingPoll();
    } catch (error) {
        console.error('发起桌面配对失败:', error);
        showToast(`发起配对失败：${error?.message || error}`);
    }
}

function openDesktopPairingModal() {
    const pairing = nearbyDesktopState.pairing;
    if (!pairing) return;
    $('desktop-pairing-target').textContent = pairing.hostname || pairing.ip;
    $('desktop-pairing-code').textContent = pairing.code || '------';
    $('desktop-pairing-status').textContent = '配对请求已发出，请在电脑端确认同样的验证码并点击同意。';
    $('desktop-pairing-subtitle').textContent = `目标电脑：${pairing.hostname || pairing.ip}。确认两边看到的是同一组 6 位验证码。`;
    $('desktop-pairing-modal')?.classList.remove('hidden');
}

function closeDesktopPairingModal() {
    stopDesktopPairingPoll();
    nearbyDesktopState.pairing = null;
    $('desktop-pairing-modal')?.classList.add('hidden');
    renderNearbyDesktops();
}

function startDesktopPairingPoll() {
    stopDesktopPairingPoll();
    nearbyDesktopState.pollTimer = setInterval(() => {
        void pollDesktopPairingStatus();
    }, DESKTOP_PAIRING_POLL_MS);
    void pollDesktopPairingStatus();
}

function stopDesktopPairingPoll() {
    clearInterval(nearbyDesktopState.pollTimer);
    nearbyDesktopState.pollTimer = null;
}

async function pollDesktopPairingStatus() {
    const pairing = nearbyDesktopState.pairing;
    if (!pairing) return;

    if (Date.now() > pairing.expiresAt) {
        showToast('配对请求已过期，请重新发起');
        closeDesktopPairingModal();
        return;
    }

    try {
        const status = await invokeNative('poll_desktop_pairing', {
            ip: pairing.ip,
            port: pairing.port,
            requestId: pairing.requestId,
        });

        if (status.status === 'pending') {
            $('desktop-pairing-status').textContent = '配对请求已送达，正在等待电脑端确认。';
            return;
        }

        if (status.status === 'approved') {
            finalizePairedDesktop({
                serverId: status.server_id || pairing.serverId,
                hostname: status.hostname || pairing.hostname,
                ip: status.ip || pairing.ip,
                port: Number(status.port || pairing.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
                pin: status.pin || '',
            });
            showToast(`已配对 ${status.hostname || pairing.hostname}`);
            closeDesktopPairingModal();
            focusSendView();
            return;
        }

        const errorMessage = status.error || (status.status === 'rejected' ? '桌面端拒绝了配对请求' : '配对请求已失效');
        showToast(errorMessage);
        closeDesktopPairingModal();
    } catch (error) {
        console.error('查询桌面配对状态失败:', error);
        $('desktop-pairing-status').textContent = `状态查询失败，正在重试…`;
    }
}

function finalizePairedDesktop(pairing) {
    const devices = getDevices();
    let matched = devices.find((device) => (
        (pairing.serverId && device.serverId === pairing.serverId)
        || (device.ip === pairing.ip && String(device.port || DESKTOP_DISCOVERY_DEFAULT_PORT) === String(pairing.port || DESKTOP_DISCOVERY_DEFAULT_PORT))
    ));

    if (!matched) {
        matched = {
            id: newDeviceId(),
            name: pairing.hostname,
            ip: pairing.ip,
            port: String(pairing.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            pin: pairing.pin || '',
            serverId: pairing.serverId || '',
        };
        devices.unshift(matched);
    } else {
        matched.name = pairing.hostname;
        matched.ip = pairing.ip;
        matched.port = String(pairing.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
        matched.pin = pairing.pin || matched.pin || '';
        matched.serverId = pairing.serverId || matched.serverId || '';
    }

    saveDevices(devices);
    renderDeviceCards(devices);
    renderSendCards(devices);
    renderHistoryFilters(devices);
    connectDevice(matched.id, matched.ip, matched.port, matched.pin || '');
    void discoverNearbyDesktops({ silent: true });
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
                <div class="send-actions media-actions">
                    <button class="send-btn image-btn" id="imagebtn-${dev.id}" disabled>传图到剪贴板</button>
                    <button class="send-btn image-btn" id="filebtn-${dev.id}" disabled>传文件到下载</button>
                </div>
                <input type="file" id="imageinput-${dev.id}" accept="image/*" class="hidden-file-input">
                <input type="file" id="fileinput-${dev.id}" class="hidden-file-input">
            </div>
        `;
        container.appendChild(card);

        // 发送按钮
        const sendBtn = card.querySelector(`#sendbtn-${dev.id}`);
        const enterBtn = card.querySelector(`#enterbtn-${dev.id}`);
        const sendEnterBtn = card.querySelector(`#sendenterbtn-${dev.id}`);
        const imageBtn = card.querySelector(`#imagebtn-${dev.id}`);
        const fileBtn = card.querySelector(`#filebtn-${dev.id}`);
        const imageInput = card.querySelector(`#imageinput-${dev.id}`);
        const fileInput = card.querySelector(`#fileinput-${dev.id}`);
        const input = card.querySelector('textarea');

        sendBtn.addEventListener('click', () => sendText(dev.id));
        enterBtn.addEventListener('click', () => sendEnter(dev.id));
        sendEnterBtn.addEventListener('click', () => sendTextAndEnter(dev.id));
        imageBtn.addEventListener('click', () => {
            if (pendingSharedContent) {
                if (!pendingSharedContent.isImage) {
                    showToast('当前共享内容不是图片，请使用“传文件到下载”');
                    return;
                }
                sendPendingSharedImage(dev.id);
                return;
            }
            imageInput.click();
        });
        fileBtn.addEventListener('click', () => {
            if (pendingSharedContent) {
                sendPendingSharedFile(dev.id);
                return;
            }
            fileInput.click();
        });
        imageInput.addEventListener('change', async (event) => {
            const [file] = event.target.files || [];
            event.target.value = '';
            if (file) {
                await sendSelectedImage(dev.id, file);
            }
        });
        fileInput.addEventListener('change', async (event) => {
            const [file] = event.target.files || [];
            event.target.value = '';
            if (file) {
                await sendSelectedFile(dev.id, file);
            }
        });
    });
}

// ============================================
// 动态渲染 — 历史筛选按钮
// ============================================

function renderHistoryFilters(devices) {
    const container = $('history-filter-btns');
    container.innerHTML = '<button class="filter-btn" data-filter="all">全部</button>';

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
            currentHistoryFilters.device = btn.dataset.filter;
            clearHistoryHeatmapSelection();
            renderDeviceFilterState();
            renderHistoryDateInputs();
            renderHistory();
        });
    });

    renderDeviceFilterState();
    renderHistoryDateInputs();
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
        void discoverNearbyDesktops({ silent: true, syncKnownDevices: true });
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

function activateNavButton(buttonId) {
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
    const button = $(buttonId);
    if (button) {
        button.classList.add('active');
    }
}

function focusSendView() {
    showView('send-view');
    activateNavButton('nav-send-btn');
}

function initHistoryFilterControls() {
    const timeContainer = $('history-time-filter-btns');
    if (timeContainer) {
        timeContainer.querySelectorAll('.filter-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const value = btn.dataset.timeFilter;
                if (value === 'custom') {
                    openHistoryFilterSheet();
                    return;
                }
                currentHistoryFilters.quickTime = value;
                clearHistoryHeatmapSelection();
                renderTimeFilterState();
                renderHistory();
            });
        });
    }

    $('history-filter-close-btn')?.addEventListener('click', closeHistoryFilterSheet);
    $('history-filter-reset-btn')?.addEventListener('click', () => {
        currentHistoryFilters = { ...DEFAULT_HISTORY_FILTERS, device: currentHistoryFilters.device };
        clearHistoryHeatmapSelection();
        syncHistoryFilterForm();
        renderDeviceFilterState();
        renderTimeFilterState();
        renderHistory();
        closeHistoryFilterSheet();
    });
    $('history-filter-apply-btn')?.addEventListener('click', applyHistoryFilterForm);
    $('history-filter-modal')?.addEventListener('click', (event) => {
        if (event.target === $('history-filter-modal')) {
            closeHistoryFilterSheet();
        }
    });

    $('history-time-range')?.addEventListener('change', () => {
        syncCustomTimeInputsState();
        renderHistoryDateInputs();
    });
    $('history-start-time')?.addEventListener('input', renderHistoryDateInputs);
    $('history-end-time')?.addEventListener('input', renderHistoryDateInputs);
    $('history-kind-filter')?.addEventListener('change', renderHistoryDateInputs);
    $('history-status-filter')?.addEventListener('change', renderHistoryDateInputs);
    $('history-start-date-trigger')?.addEventListener('click', () => openHistoryDatePicker('startDate'));
    $('history-end-date-trigger')?.addEventListener('click', () => openHistoryDatePicker('endDate'));
    $('history-date-picker-close-btn')?.addEventListener('click', closeHistoryDatePicker);
    $('history-date-picker-done-btn')?.addEventListener('click', closeHistoryDatePicker);
    $('history-date-picker-clear-btn')?.addEventListener('click', clearHistoryDateField);
    $('history-date-picker-prev-btn')?.addEventListener('click', () => shiftHistoryDatePickerMonth(-1));
    $('history-date-picker-next-btn')?.addEventListener('click', () => shiftHistoryDatePickerMonth(1));
    $('history-date-picker-modal')?.addEventListener('click', (event) => {
        if (event.target === $('history-date-picker-modal')) {
            closeHistoryDatePicker();
        }
    });

    syncHistoryFilterForm();
    renderTimeFilterState();
}

function openHistoryFilterSheet() {
    syncHistoryFilterForm();
    $('history-filter-modal')?.classList.remove('hidden');
}

function closeHistoryFilterSheet() {
    $('history-filter-modal')?.classList.add('hidden');
}

function syncHistoryFilterForm() {
    const {
        startDate,
        endDate,
        timeRange,
        startTime,
        endTime,
        kind,
        status,
    } = currentHistoryFilters;

    if ($('history-start-date')) $('history-start-date').value = startDate;
    if ($('history-end-date')) $('history-end-date').value = endDate;
    if ($('history-time-range')) $('history-time-range').value = timeRange;
    if ($('history-start-time')) $('history-start-time').value = startTime;
    if ($('history-end-time')) $('history-end-time').value = endTime;
    if ($('history-kind-filter')) $('history-kind-filter').value = kind;
    if ($('history-status-filter')) $('history-status-filter').value = status;
    syncCustomTimeInputsState();
    renderHistoryDateInputs();
}

function syncCustomTimeInputsState() {
    const custom = $('history-time-range')?.value === 'custom';
    if ($('history-start-time')) $('history-start-time').disabled = !custom;
    if ($('history-end-time')) $('history-end-time').disabled = !custom;
}

function applyHistoryFilterForm() {
    const startDate = $('history-start-date')?.value || '';
    const endDate = $('history-end-date')?.value || '';
    const timeRange = $('history-time-range')?.value || 'all';
    const startTime = $('history-start-time')?.value || '';
    const endTime = $('history-end-time')?.value || '';
    const kind = $('history-kind-filter')?.value || 'all';
    const status = $('history-status-filter')?.value || 'all';

    if (startDate && endDate && startDate > endDate) {
        showToast('开始日期不能晚于结束日期');
        return;
    }

    if (timeRange === 'custom' && startTime && endTime && startTime > endTime) {
        showToast('开始时间不能晚于结束时间');
        return;
    }

    currentHistoryFilters = {
        ...currentHistoryFilters,
        quickTime: startDate || endDate
            ? 'custom'
            : (currentHistoryFilters.quickTime === 'custom' ? 'all' : currentHistoryFilters.quickTime),
        startDate,
        endDate,
        timeRange,
        startTime: timeRange === 'custom' ? startTime : '',
        endTime: timeRange === 'custom' ? endTime : '',
        kind,
        status,
    };

    clearHistoryHeatmapSelection();
    renderTimeFilterState();
    renderHistory();
    closeHistoryFilterSheet();
}

function renderDeviceFilterState() {
    $('history-filter-btns')?.querySelectorAll('.filter-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.filter === currentHistoryFilters.device);
    });
}

function renderTimeFilterState() {
    $('history-time-filter-btns')?.querySelectorAll('.filter-btn').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.timeFilter === currentHistoryFilters.quickTime);
    });
}

function renderHistoryDateInputs() {
    const availability = getHistoryDateAvailability();
    const startDate = $('history-start-date')?.value || '';
    const endDate = $('history-end-date')?.value || '';

    if ($('history-start-date-label')) {
        $('history-start-date-label').textContent = startDate ? formatHistoryDateLabel(startDate) : '选择开始日期';
    }
    if ($('history-end-date-label')) {
        $('history-end-date-label').textContent = endDate ? formatHistoryDateLabel(endDate) : '选择结束日期';
    }

    const hasAvailableDates = availability.availableDates.size > 0;
    if ($('history-start-date-trigger')) $('history-start-date-trigger').disabled = !hasAvailableDates;
    if ($('history-end-date-trigger')) $('history-end-date-trigger').disabled = !hasAvailableDates;

    const hint = $('history-date-availability');
    if (!hint) return;

    if (!hasAvailableDates) {
        hint.classList.remove('hidden');
        hint.textContent = '当前条件下没有可选日期。';
        return;
    }

    hint.classList.remove('hidden');
    hint.textContent = `有记录日期：${formatHistoryDateLabel(availability.minDate)} 至 ${formatHistoryDateLabel(availability.maxDate)}，不可选日期会置灰。`;
}

function readHistoryFilterDraft() {
    const timeRange = $('history-time-range')?.value || currentHistoryFilters.timeRange || 'all';
    return {
        ...currentHistoryFilters,
        quickTime: 'all',
        startDate: '',
        endDate: '',
        timeRange,
        startTime: timeRange === 'custom' ? ($('history-start-time')?.value || '') : '',
        endTime: timeRange === 'custom' ? ($('history-end-time')?.value || '') : '',
        kind: $('history-kind-filter')?.value || currentHistoryFilters.kind || 'all',
        status: $('history-status-filter')?.value || currentHistoryFilters.status || 'all',
    };
}

function getHistoryDateAvailability() {
    const history = getHistory();
    const filters = readHistoryFilterDraft();
    const availableDates = new Set();
    let minDate = '';
    let maxDate = '';

    history.forEach((entry) => {
        if (filters.device !== 'all' && entry.target !== filters.device) {
            return;
        }

        const entryDate = new Date(entry.timestamp);
        if (Number.isNaN(entryDate.getTime())) {
            return;
        }

        if (!matchesTimeRange(entryDate, filters)) {
            return;
        }

        if (!matchesKind(entry, filters) || !matchesStatus(entry, filters)) {
            return;
        }

        const dayKey = formatDateKey(entryDate);
        availableDates.add(dayKey);
        if (!minDate || dayKey < minDate) minDate = dayKey;
        if (!maxDate || dayKey > maxDate) maxDate = dayKey;
    });

    return { availableDates, minDate, maxDate };
}

function openHistoryDatePicker(field) {
    const availability = getHistoryDateAvailability();
    if (availability.availableDates.size === 0) {
        showToast('当前条件下没有可选日期');
        return;
    }

    historyDatePickerState = {
        field,
        availableDates: availability.availableDates,
        minDate: availability.minDate,
        maxDate: availability.maxDate,
        month: getHistoryPickerInitialMonth(field, availability),
    };

    const title = field === 'startDate' ? '选择开始日期' : '选择结束日期';
    if ($('history-date-picker-title')) $('history-date-picker-title').textContent = title;
    if ($('history-date-picker-subtitle')) {
        $('history-date-picker-subtitle').textContent = `仅显示有记录的日期。范围：${formatHistoryDateLabel(availability.minDate)} 至 ${formatHistoryDateLabel(availability.maxDate)}`;
    }

    renderHistoryDatePicker();
    $('history-date-picker-modal')?.classList.remove('hidden');
}

function getHistoryPickerInitialMonth(field, availability) {
    const currentValue = field === 'startDate'
        ? $('history-start-date')?.value
        : $('history-end-date')?.value;
    const fallback = currentValue || availability.maxDate || formatDateKey(new Date());
    const [year, month] = fallback.split('-').map(Number);
    return new Date(year, (month || 1) - 1, 1);
}

function closeHistoryDatePicker() {
    $('history-date-picker-modal')?.classList.add('hidden');
    historyDatePickerState = {
        field: null,
        month: null,
        availableDates: new Set(),
        minDate: '',
        maxDate: '',
    };
}

function clearHistoryDateField() {
    if (!historyDatePickerState.field) return;
    const inputId = historyDatePickerState.field === 'startDate' ? 'history-start-date' : 'history-end-date';
    if ($(inputId)) {
        $(inputId).value = '';
    }
    renderHistoryDateInputs();
    closeHistoryDatePicker();
}

function shiftHistoryDatePickerMonth(delta) {
    if (!historyDatePickerState.month) return;
    historyDatePickerState.month = new Date(
        historyDatePickerState.month.getFullYear(),
        historyDatePickerState.month.getMonth() + delta,
        1
    );
    renderHistoryDatePicker();
}

function renderHistoryDatePicker() {
    const grid = $('history-date-picker-grid');
    const monthLabel = $('history-date-picker-month-label');
    if (!grid || !monthLabel || !historyDatePickerState.month) return;

    const month = historyDatePickerState.month;
    const year = month.getFullYear();
    const monthIndex = month.getMonth();
    monthLabel.textContent = `${year}年${monthIndex + 1}月`;

    const firstDay = new Date(year, monthIndex, 1);
    const startOffset = (firstDay.getDay() + 6) % 7;
    const daysInMonth = new Date(year, monthIndex + 1, 0).getDate();
    const cells = [];

    for (let i = 0; i < startOffset; i += 1) {
        cells.push('<span class="history-date-cell spacer" aria-hidden="true"></span>');
    }

    const selectedValue = historyDatePickerState.field === 'startDate'
        ? $('history-start-date')?.value
        : $('history-end-date')?.value;
    const todayKey = formatDateKey(new Date());

    for (let day = 1; day <= daysInMonth; day += 1) {
        const dayKey = `${year}-${pad2(monthIndex + 1)}-${pad2(day)}`;
        const available = historyDatePickerState.availableDates.has(dayKey);
        const isSelected = dayKey === selectedValue;
        const isToday = dayKey === todayKey;
        const className = [
            'history-date-cell',
            available ? 'available' : 'disabled',
            isSelected ? 'active' : '',
            isToday ? 'today' : '',
        ].filter(Boolean).join(' ');

        cells.push(`
            <button
                type="button"
                class="${className}"
                data-date-value="${dayKey}"
                ${available ? '' : 'disabled'}
            >${day}</button>
        `);
    }

    grid.innerHTML = cells.join('');
    grid.querySelectorAll('.history-date-cell.available').forEach((button) => {
        button.addEventListener('click', () => {
            const value = button.dataset.dateValue;
            const inputId = historyDatePickerState.field === 'startDate' ? 'history-start-date' : 'history-end-date';
            if ($(inputId)) {
                $(inputId).value = value;
            }
            renderHistoryDateInputs();
            closeHistoryDatePicker();
        });
    });

    updateHistoryDatePickerNavState();
}

function updateHistoryDatePickerNavState() {
    const prevBtn = $('history-date-picker-prev-btn');
    const nextBtn = $('history-date-picker-next-btn');
    if (!prevBtn || !nextBtn || !historyDatePickerState.month) return;

    const currentMonthKey = formatMonthKey(historyDatePickerState.month);
    const minMonthKey = historyDatePickerState.minDate ? historyDatePickerState.minDate.slice(0, 7) : '';
    const maxMonthKey = historyDatePickerState.maxDate ? historyDatePickerState.maxDate.slice(0, 7) : '';

    prevBtn.disabled = Boolean(minMonthKey) && currentMonthKey <= minMonthKey;
    nextBtn.disabled = Boolean(maxMonthKey) && currentMonthKey >= maxMonthKey;
}

function formatMonthKey(date) {
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}`;
}

function formatDateKey(date) {
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

function formatHistoryDateLabel(value) {
    if (!value) return '';
    const [year, month, day] = String(value).split('-').map(Number);
    if (!year || !month || !day) return value;
    return `${year}年${month}月${day}日`;
}

function dateKeyToUtcDate(value) {
    const [year, month, day] = String(value).split('-').map(Number);
    if (!year || !month || !day) {
        return null;
    }
    return new Date(Date.UTC(year, month - 1, day));
}

function shiftDateKey(value, deltaDays) {
    const date = dateKeyToUtcDate(value);
    if (!date) {
        return formatDateKey(new Date());
    }
    date.setUTCDate(date.getUTCDate() + deltaDays);
    return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

function diffDateKeys(start, end) {
    const startDate = dateKeyToUtcDate(start);
    const endDate = dateKeyToUtcDate(end);
    if (!startDate || !endDate) {
        return 0;
    }
    return Math.round((endDate.getTime() - startDate.getTime()) / 86400000);
}

function clampDateKey(value, minValue, maxValue) {
    if (minValue && value < minValue) {
        return minValue;
    }
    if (maxValue && value > maxValue) {
        return maxValue;
    }
    return value;
}

function formatShortDateLabel(value) {
    if (!value) return '';
    const [year, month, day] = String(value).split('-').map(Number);
    if (!year || !month || !day) return value;
    return `${month}/${day}`;
}

function formatHeatmapHourLabel(hour) {
    return `${pad2(hour)}:00-${pad2(hour)}:59`;
}

function formatHeatmapSelectionLabel(dateKey, hour) {
    if (!dateKey || hour == null) return '';
    return `${formatHistoryDateLabel(dateKey)} ${formatHeatmapHourLabel(hour)}`;
}

function getHistoryHeatmapRangeToken() {
    return [
        currentHistoryFilters.quickTime,
        currentHistoryFilters.startDate,
        currentHistoryFilters.endDate,
    ].join('|');
}

function getHistoryHeatmapBounds(baseEntries) {
    const todayKey = formatDateKey(new Date());
    const quick = currentHistoryFilters.quickTime;
    const preferredEnd = quick === 'custom' && currentHistoryFilters.endDate
        ? currentHistoryFilters.endDate
        : todayKey;

    let minDate = '';
    let maxDate = preferredEnd;

    if (quick === 'today') {
        minDate = preferredEnd;
    } else if (quick === '7d') {
        minDate = shiftDateKey(preferredEnd, -(HEATMAP_VISIBLE_DAYS - 1));
    } else if (quick === '30d') {
        minDate = shiftDateKey(preferredEnd, -29);
    } else if (quick === 'custom') {
        minDate = currentHistoryFilters.startDate || '';
    }

    if (!minDate) {
        if (baseEntries.length > 0) {
            minDate = baseEntries.reduce((earliest, entry) => {
                const entryDate = new Date(entry.timestamp);
                if (Number.isNaN(entryDate.getTime())) {
                    return earliest;
                }
                const dayKey = formatDateKey(entryDate);
                return !earliest || dayKey < earliest ? dayKey : earliest;
            }, '');
        }
    }

    if (!minDate) {
        minDate = shiftDateKey(preferredEnd, -(HEATMAP_VISIBLE_DAYS - 1));
    }

    if (minDate > maxDate) {
        minDate = maxDate;
    }

    const spanDays = diffDateKeys(minDate, maxDate) + 1;
    const minViewportEndDate = spanDays >= HEATMAP_VISIBLE_DAYS
        ? shiftDateKey(minDate, HEATMAP_VISIBLE_DAYS - 1)
        : maxDate;

    return {
        minDate,
        maxDate,
        preferredEnd,
        minViewportEndDate,
    };
}

function getHistoryHeatmapDragOffsetBounds(bounds) {
    const currentEnd = historyHeatmapState.viewportEndDate || bounds.maxDate;
    const minDeltaDays = diffDateKeys(currentEnd, bounds.minViewportEndDate);
    const maxDeltaDays = diffDateKeys(currentEnd, bounds.maxDate);

    return {
        minOffsetX: -maxDeltaDays * HEATMAP_COLUMN_STEP,
        maxOffsetX: -minDeltaDays * HEATMAP_COLUMN_STEP,
    };
}

function clampHistoryHeatmapOffset(offsetX) {
    return Math.max(
        historyHeatmapState.dragMinOffsetX,
        Math.min(historyHeatmapState.dragMaxOffsetX, offsetX)
    );
}

function clearHistoryHeatmapSelection() {
    historyHeatmapState.selectionDate = '';
    historyHeatmapState.selectionHour = null;
}

function applyHistoryHeatmapSelection(entries) {
    if (!historyHeatmapState.selectionDate || historyHeatmapState.selectionHour == null) {
        return entries;
    }

    return entries.filter((entry) => {
        const entryDate = new Date(entry.timestamp);
        if (Number.isNaN(entryDate.getTime())) {
            return false;
        }
        return formatDateKey(entryDate) === historyHeatmapState.selectionDate
            && entryDate.getHours() === historyHeatmapState.selectionHour;
    });
}

function buildHistoryHeatmapCountMap(entries) {
    const counts = new Map();

    entries.forEach((entry) => {
        const entryDate = new Date(entry.timestamp);
        if (Number.isNaN(entryDate.getTime())) {
            return;
        }
        const dayKey = formatDateKey(entryDate);
        const hour = entryDate.getHours();
        const key = `${dayKey}|${hour}`;
        counts.set(key, (counts.get(key) || 0) + 1);
    });

    return counts;
}

function getHistoryHeatmapCellCount(counts, dateKey, hour) {
    return counts.get(`${dateKey}|${hour}`) || 0;
}

function getHistoryHeatmapCellColor(count, maxCount) {
    if (count <= 0 || maxCount <= 0) {
        return 'rgba(152, 162, 179, 0.12)';
    }
    const ratio = Math.pow(count / maxCount, 0.72);
    const hue = 138 - (ratio * 6);
    const saturation = 46 + (ratio * 28);
    const lightness = 94 - (ratio * 68);
    const alpha = 0.22 + (ratio * 0.76);
    return `hsla(${hue}, ${saturation}%, ${lightness}%, ${alpha})`;
}

function buildHistoryHeatmapStats(entries, visibleStart, visibleEnd) {
    const visibleEntries = entries.filter((entry) => {
        const entryDate = new Date(entry.timestamp);
        if (Number.isNaN(entryDate.getTime())) {
            return false;
        }
        const dayKey = formatDateKey(entryDate);
        return dayKey >= visibleStart && dayKey <= visibleEnd;
    });

    const total = visibleEntries.length;
    const perHour = new Map();
    const perDay = new Map();

    visibleEntries.forEach((entry) => {
        const entryDate = new Date(entry.timestamp);
        const dayKey = formatDateKey(entryDate);
        const hour = entryDate.getHours();

        perHour.set(hour, (perHour.get(hour) || 0) + 1);
        perDay.set(dayKey, (perDay.get(dayKey) || 0) + 1);
    });

    let peakHour = null;
    let peakHourCount = 0;
    perHour.forEach((count, hour) => {
        if (count > peakHourCount) {
            peakHour = hour;
            peakHourCount = count;
        }
    });

    let peakDay = '';
    let peakDayCount = 0;
    perDay.forEach((count, dayKey) => {
        if (count > peakDayCount) {
            peakDay = dayKey;
            peakDayCount = count;
        }
    });

    return [
        {
            label: '窗口',
            value: `${formatShortDateLabel(visibleStart)} - ${formatShortDateLabel(visibleEnd)}`,
        },
        {
            label: '发送',
            value: `${total} 条`,
        },
        {
            label: '高峰时段',
            value: peakHour == null ? '暂无' : `${pad2(peakHour)}:00 · ${peakHourCount}`,
        },
        {
            label: '最忙日期',
            value: !peakDay ? '暂无' : `${formatShortDateLabel(peakDay)} · ${peakDayCount}`,
        },
    ];
}

function renderHistoryHeatmap(baseEntries) {
    const track = $('history-heatmap-track');
    const stats = $('history-heatmap-stats');
    const caption = $('history-heatmap-caption');
    const resetBtn = $('history-heatmap-reset-btn');
    if (!track || !stats || !caption || !resetBtn) return;

    const rangeToken = getHistoryHeatmapRangeToken();
    const bounds = getHistoryHeatmapBounds(baseEntries);

    if (!historyHeatmapState.viewportEndDate || historyHeatmapState.rangeToken !== rangeToken) {
        historyHeatmapState.viewportEndDate = bounds.preferredEnd;
        historyHeatmapState.rangeToken = rangeToken;
    }

    historyHeatmapState.viewportEndDate = clampDateKey(
        historyHeatmapState.viewportEndDate,
        bounds.minViewportEndDate,
        bounds.maxDate
    );

    const visibleEnd = historyHeatmapState.viewportEndDate;
    const visibleStart = shiftDateKey(visibleEnd, -(HEATMAP_VISIBLE_DAYS - 1));
    const renderStart = shiftDateKey(visibleStart, -HEATMAP_RENDER_BUFFER_DAYS);
    const renderEnd = shiftDateKey(visibleEnd, HEATMAP_RENDER_BUFFER_DAYS);
    const counts = buildHistoryHeatmapCountMap(baseEntries);
    const maxCount = Array.from(counts.values()).reduce((max, value) => Math.max(max, value), 0);
    const todayKey = formatDateKey(new Date());
    const days = [];

    for (let dayKey = renderStart; dayKey <= renderEnd; dayKey = shiftDateKey(dayKey, 1)) {
        days.push(dayKey);
    }

    track.innerHTML = days.map((dayKey) => {
        const dayDate = dateKeyToUtcDate(dayKey);
        const weekday = dayDate
            ? dayDate.toLocaleDateString('zh-CN', { weekday: 'short', timeZone: 'UTC' }).replace('周', '周')
            : '';
        const isToday = dayKey === todayKey;
        const headerLabel = isToday ? '今天' : weekday;

        const hoursHtml = Array.from({ length: HEATMAP_HOUR_SLOTS }, (_, hour) => {
            const count = getHistoryHeatmapCellCount(counts, dayKey, hour);
            const isSelected = historyHeatmapState.selectionDate === dayKey
                && historyHeatmapState.selectionHour === hour;
            const classes = ['history-heatmap-cell'];
            if (count > 0) classes.push('has-data');
            if (isSelected) classes.push('selected');
            const style = `background:${getHistoryHeatmapCellColor(count, maxCount)};`;
            const label = `${formatHistoryDateLabel(dayKey)} ${formatHeatmapHourLabel(hour)}，${count} 条`;

            return `
                <button
                    type="button"
                    class="${classes.join(' ')}"
                    style="${style}"
                    data-date-key="${dayKey}"
                    data-hour="${hour}"
                    aria-label="${label}"
                    title="${label}"
                ></button>
            `;
        }).join('');

        return `
            <div class="history-heatmap-day ${isToday ? 'is-today' : ''}">
                <div class="history-heatmap-day-header">
                    <span class="history-heatmap-day-weekday">${escapeHtml(headerLabel)}</span>
                    <span class="history-heatmap-day-date">${escapeHtml(formatShortDateLabel(dayKey))}</span>
                </div>
                <div class="history-heatmap-hours">
                    ${hoursHtml}
                </div>
            </div>
        `;
    }).join('');

    const baseOffset = -(diffDateKeys(renderStart, visibleStart) * HEATMAP_COLUMN_STEP);
    track.dataset.baseOffset = String(baseOffset);
    track.style.transition = historyHeatmapState.dragPointerId == null
        ? 'transform 220ms cubic-bezier(0.22, 1, 0.36, 1)'
        : 'none';
    track.style.transform = `translate3d(${baseOffset + historyHeatmapState.dragOffsetX}px, 0, 0)`;

    stats.innerHTML = buildHistoryHeatmapStats(baseEntries, visibleStart, visibleEnd).map((item) => `
        <div class="history-heatmap-stat">
            <span class="history-heatmap-stat-label">${escapeHtml(item.label)}</span>
            <span class="history-heatmap-stat-value">${escapeHtml(item.value)}</span>
        </div>
    `).join('');

    caption.textContent = `当前窗口 ${formatHistoryDateLabel(visibleStart)} 至 ${formatHistoryDateLabel(visibleEnd)}。左右拖动查看更多日期，点方块筛选该小时。`;
    resetBtn.classList.toggle('hidden', visibleEnd === bounds.maxDate);

    track.querySelectorAll('.history-heatmap-cell').forEach((cell) => {
        cell.addEventListener('click', () => {
            if (Date.now() < historyHeatmapState.suppressCellClickUntil) {
                return;
            }

            const dayKey = cell.dataset.dateKey || '';
            const hour = Number(cell.dataset.hour);

            if (historyHeatmapState.selectionDate === dayKey && historyHeatmapState.selectionHour === hour) {
                clearHistoryHeatmapSelection();
            } else {
                historyHeatmapState.selectionDate = dayKey;
                historyHeatmapState.selectionHour = hour;
            }

            renderHistory();
        });
    });
}

function initHistoryHeatmapInteractions() {
    const viewport = $('history-heatmap-viewport');
    const resetBtn = $('history-heatmap-reset-btn');
    if (!viewport || viewport.dataset.ready === '1') {
        return;
    }

    viewport.dataset.ready = '1';

    viewport.addEventListener('pointerdown', (event) => {
        const bounds = getHistoryHeatmapBounds(filterHistoryEntries(getHistory()));
        const dragBounds = getHistoryHeatmapDragOffsetBounds(bounds);
        historyHeatmapState.dragPointerId = event.pointerId;
        historyHeatmapState.dragStartX = event.clientX;
        historyHeatmapState.dragOffsetX = 0;
        historyHeatmapState.dragLastX = event.clientX;
        historyHeatmapState.dragLastTime = Date.now();
        historyHeatmapState.dragVelocity = 0;
        historyHeatmapState.dragMoved = false;
        historyHeatmapState.dragMinOffsetX = dragBounds.minOffsetX;
        historyHeatmapState.dragMaxOffsetX = dragBounds.maxOffsetX;
        viewport.setPointerCapture?.(event.pointerId);
    });

    viewport.addEventListener('pointermove', (event) => {
        if (event.pointerId !== historyHeatmapState.dragPointerId) {
            return;
        }

        const rawOffsetX = event.clientX - historyHeatmapState.dragStartX;
        const offsetX = clampHistoryHeatmapOffset(rawOffsetX);
        const now = Date.now();
        const deltaTime = Math.max(now - historyHeatmapState.dragLastTime, 1);

        historyHeatmapState.dragVelocity = (event.clientX - historyHeatmapState.dragLastX) / deltaTime;
        historyHeatmapState.dragLastX = event.clientX;
        historyHeatmapState.dragLastTime = now;

        if (!historyHeatmapState.dragMoved && Math.abs(offsetX) < HEATMAP_DRAG_THRESHOLD) {
            return;
        }

        historyHeatmapState.dragMoved = true;
        historyHeatmapState.dragOffsetX = offsetX;

        const track = $('history-heatmap-track');
        if (!track) return;

        const baseOffset = Number(track.dataset.baseOffset || 0);
        track.style.transition = 'none';
        track.style.transform = `translate3d(${baseOffset + offsetX}px, 0, 0)`;
    });

    const finishDrag = (event) => {
        if (event.pointerId !== historyHeatmapState.dragPointerId) {
            return;
        }

        viewport.releasePointerCapture?.(event.pointerId);

        const moved = historyHeatmapState.dragMoved;
        const predictedOffset = clampHistoryHeatmapOffset(
            historyHeatmapState.dragOffsetX + (historyHeatmapState.dragVelocity * HEATMAP_INERTIA_MS)
        );

        historyHeatmapState.dragPointerId = null;
        historyHeatmapState.dragOffsetX = 0;
        historyHeatmapState.dragVelocity = 0;
        historyHeatmapState.dragLastX = 0;
        historyHeatmapState.dragLastTime = 0;
        historyHeatmapState.dragMoved = false;
        historyHeatmapState.dragMinOffsetX = 0;
        historyHeatmapState.dragMaxOffsetX = 0;

        if (!moved) {
            return;
        }

        const bounds = getHistoryHeatmapBounds(filterHistoryEntries(getHistory()));
        const deltaDays = -Math.round(predictedOffset / HEATMAP_COLUMN_STEP);
        historyHeatmapState.viewportEndDate = clampDateKey(
            shiftDateKey(historyHeatmapState.viewportEndDate, deltaDays),
            bounds.minViewportEndDate,
            bounds.maxDate
        );
        clearHistoryHeatmapSelection();
        historyHeatmapState.suppressCellClickUntil = Date.now() + 180;
        renderHistory();
    };

    viewport.addEventListener('pointerup', finishDrag);
    viewport.addEventListener('pointercancel', finishDrag);

    resetBtn?.addEventListener('click', () => {
        const bounds = getHistoryHeatmapBounds(filterHistoryEntries(getHistory()));
        historyHeatmapState.viewportEndDate = bounds.maxDate;
        clearHistoryHeatmapSelection();
        renderHistory();
    });
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
        ws.send(JSON.stringify({
            action: 'auth',
            pin: pin,
            device_id: clientIdentity.id,
            device_name: clientIdentity.name,
            can_receive_files: supportsNativeFileReceive(),
        }));
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

        if (
            data.action === 'incoming_file_start'
            || data.action === 'incoming_file_chunk'
            || data.action === 'incoming_file_complete'
        ) {
            queueIncomingDesktopTransfer(deviceId, data);
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

function queueIncomingDesktopTransfer(deviceId, payload) {
    const conn = ensureConnection(deviceId);
    conn.incomingTransferQueue = (conn.incomingTransferQueue || Promise.resolve())
        .then(() => handleIncomingDesktopTransfer(deviceId, payload))
        .catch((error) => {
            console.error('处理桌面文件传输失败:', error);
        });
}

async function handleIncomingDesktopTransfer(deviceId, payload) {
    const transferId = payload.transfer_id;
    const conn = connections[deviceId];
    if (!transferId || !conn || !conn.ws || conn.ws.readyState !== WebSocket.OPEN) {
        return;
    }

    try {
        if (payload.action === 'incoming_file_start') {
            await beginIncomingDesktopFile(payload);
            const fileName = payload.file_name || '文件';
            showToast(`正在接收：${fileName}`);
            return;
        }

        if (payload.action === 'incoming_file_chunk') {
            await appendIncomingDesktopFileChunk(payload);
            return;
        }

        if (payload.action === 'incoming_file_complete') {
            const savedPath = await finishIncomingDesktopFile(payload);
            conn.ws.send(JSON.stringify({
                action: 'incoming_file_saved',
                transfer_id: transferId,
                saved_path: savedPath,
            }));
            return;
        }
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error || '接收失败');
        await cancelIncomingDesktopFile(transferId);
        if (conn.ws && conn.ws.readyState === WebSocket.OPEN) {
            conn.ws.send(JSON.stringify({
                action: 'incoming_file_error',
                transfer_id: transferId,
                error: message,
            }));
        }
        showToast(`接收文件失败：${message}`);
    }
}

async function beginIncomingDesktopFile(payload) {
    const transferId = payload.transfer_id;
    if (!transferId) {
        throw new Error('缺少传输标识');
    }

    incomingDesktopTransfers[transferId] = {
        fileName: payload.file_name || 'file.bin',
        mimeType: payload.mime_type || 'application/octet-stream',
        chunks: [],
    };

    if (window.__TAURI__) {
        await window.__TAURI__.core.invoke('begin_incoming_file', {
            transferId,
            fileName: payload.file_name || 'file.bin',
            mimeType: payload.mime_type || 'application/octet-stream',
            sizeBytes: Number(payload.size_bytes || 0),
        });
    }
}

async function appendIncomingDesktopFileChunk(payload) {
    const transferId = payload.transfer_id;
    const transfer = incomingDesktopTransfers[transferId];
    if (!transfer) {
        throw new Error('接收状态不存在');
    }

    if (window.__TAURI__) {
        await window.__TAURI__.core.invoke('append_incoming_file_chunk', {
            transferId,
            chunkBase64: payload.chunk_base64 || '',
        });
        return;
    }

    transfer.chunks.push(base64ToUint8Array(payload.chunk_base64 || ''));
}

async function finishIncomingDesktopFile(payload) {
    const transferId = payload.transfer_id;
    const transfer = incomingDesktopTransfers[transferId];
    if (!transfer) {
        throw new Error('接收状态不存在');
    }

    try {
        if (window.__TAURI__) {
            const savedPath = await window.__TAURI__.core.invoke('finish_incoming_file', {
                transferId,
            });
            showToast(`已保存到下载：${transfer.fileName}`);
            return savedPath;
        }

        const blob = new Blob(transfer.chunks, { type: transfer.mimeType || 'application/octet-stream' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = transfer.fileName;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1200);
        showToast(`已下载：${transfer.fileName}`);
        return transfer.fileName;
    } finally {
        delete incomingDesktopTransfers[transferId];
    }
}

async function cancelIncomingDesktopFile(transferId) {
    if (!transferId) {
        return;
    }

    delete incomingDesktopTransfers[transferId];
    if (window.__TAURI__) {
        try {
            await window.__TAURI__.core.invoke('cancel_incoming_file', {
                transferId,
            });
        } catch (error) {
            console.warn('清理接收中的桌面文件失败', error);
        }
    }
}

function base64ToUint8Array(base64) {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
        bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
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
    const imageBtn = $(`imagebtn-${deviceId}`);
    const fileBtn = $(`filebtn-${deviceId}`);

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
            if (imageBtn) imageBtn.disabled = false;
            if (fileBtn) fileBtn.disabled = false;
            if (detail) name.textContent = detail;
            break;
        case 'connecting':
            dot.classList.add('connecting');
            text.textContent = detail || '连接中...';
            if (sendBtn) sendBtn.disabled = true;
            if (enterBtn) enterBtn.disabled = true;
            if (sendEnterBtn) sendEnterBtn.disabled = true;
            if (imageBtn) imageBtn.disabled = true;
            if (fileBtn) fileBtn.disabled = true;
            break;
        case 'error':
            dot.classList.add('error');
            card.classList.add('error');
            text.textContent = detail || '错误';
            if (sendBtn) sendBtn.disabled = true;
            if (enterBtn) enterBtn.disabled = true;
            if (sendEnterBtn) sendEnterBtn.disabled = true;
            if (imageBtn) imageBtn.disabled = true;
            if (fileBtn) fileBtn.disabled = true;
            break;
        case 'disconnected':
        default:
            text.textContent = detail || '未连接';
            if (sendBtn) sendBtn.disabled = true;
            if (enterBtn) enterBtn.disabled = true;
            if (sendEnterBtn) sendEnterBtn.disabled = true;
            if (imageBtn) imageBtn.disabled = true;
            if (fileBtn) fileBtn.disabled = true;
            break;
    }

    renderNearbyDesktops();
}

// ============================================
// 发送文字
// ============================================

function setActionButtonsDisabled(deviceId, disabled) {
    const sendBtn = $(`sendbtn-${deviceId}`);
    const enterBtn = $(`enterbtn-${deviceId}`);
    const sendEnterBtn = $(`sendenterbtn-${deviceId}`);
    const imageBtn = $(`imagebtn-${deviceId}`);
    const fileBtn = $(`filebtn-${deviceId}`);
    if (sendBtn) sendBtn.disabled = disabled;
    if (enterBtn) enterBtn.disabled = disabled;
    if (sendEnterBtn) sendEnterBtn.disabled = disabled;
    if (imageBtn) imageBtn.disabled = disabled;
    if (fileBtn) fileBtn.disabled = disabled;
}

async function sendDeviceAction(deviceId, {
    action,
    payload = {},
    buttonId,
    pendingText,
    clearInput = false,
    historyEntry = null,
    failureToast = false,
    successToast = '',
    timeoutMs = 5000,
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
            }, timeoutMs);
        });

        if (result.status === 'ok') {
            if (historyEntry) {
                historyEntry.status = 'success';
                updateHistory(historyEntry);
            }
            if (successToast) {
                showToast(successToast);
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

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: text,
        status: 'pending',
        ...buildHistoryTargetMeta(deviceId),
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

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text,
        status: 'pending',
        ...buildHistoryTargetMeta(deviceId),
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

function readFileAsDataUrl(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(reader.error || new Error('读取文件失败'));
        reader.readAsDataURL(file);
    });
}

function getTargetName(deviceId) {
    const conn = connections[deviceId];
    const devices = getDevices();
    const dev = devices.find(d => d.id === deviceId);
    return (dev ? dev.name : '') || conn?.hostname || deviceId;
}

function getTargetDeviceName(deviceId) {
    const conn = connections[deviceId];
    return conn?.hostname || '';
}

function buildHistoryTargetMeta(deviceId) {
    const targetAlias = getTargetName(deviceId);
    const targetDeviceName = getTargetDeviceName(deviceId);
    return {
        target: deviceId,
        targetName: targetAlias || targetDeviceName || deviceId,
        targetAlias,
        targetDeviceName,
    };
}

function createThumbnailDataUrl(sourceDataUrl, outputType = 'image/jpeg') {
    return new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = () => {
            const maxEdge = 180;
            const scale = Math.min(maxEdge / image.width, maxEdge / image.height, 1);
            const width = Math.max(1, Math.round(image.width * scale));
            const height = Math.max(1, Math.round(image.height * scale));
            const canvas = document.createElement('canvas');
            canvas.width = width;
            canvas.height = height;
            const context = canvas.getContext('2d');

            if (!context) {
                reject(new Error('无法创建图片预览'));
                return;
            }

            context.drawImage(image, 0, 0, width, height);
            resolve(canvas.toDataURL(outputType, 0.82));
        };
        image.onerror = () => reject(new Error('无法生成图片预览'));
        image.src = sourceDataUrl;
    });
}

async function sendSelectedImage(deviceId, file) {
    const conn = connections[deviceId];
    if (!file || !conn || !conn.authenticated || !conn.ws) return;

    if (!file.type || !file.type.startsWith('image/')) {
        showToast('请选择图片文件');
        return;
    }

    if (file.size > MAX_IMAGE_FILE_BYTES) {
        showToast('图片过大，请选择 10MB 以内的图片');
        return;
    }

    const displayText = `[图片] ${file.name}`;

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: displayText,
        status: 'pending',
        kind: 'image',
        fileName: file.name,
        mimeType: file.type,
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);

    try {
        const dataUrl = await readFileAsDataUrl(file);
        const commaIndex = String(dataUrl).indexOf(',');
        if (commaIndex === -1) {
            throw new Error('图片编码失败');
        }

        try {
            historyEntry.thumbnailDataUrl = await createThumbnailDataUrl(
                dataUrl,
                file.type === 'image/png' ? 'image/png' : 'image/jpeg'
            );
            updateHistory(historyEntry);
        } catch (previewError) {
            console.warn('生成缩略图失败，继续发送原图:', previewError);
        }

        const imageBase64 = String(dataUrl).slice(commaIndex + 1);
        await sendDeviceAction(deviceId, {
            action: 'image_clipboard',
            payload: {
                file_name: file.name,
                mime_type: file.type,
                image_base64: imageBase64,
            },
            buttonId: `imagebtn-${deviceId}`,
            pendingText: '传图中...',
            historyEntry,
            failureToast: true,
            successToast: '图片已放入 Mac 剪贴板',
            timeoutMs: 20000,
        });
    } catch (error) {
        historyEntry.status = 'failed';
        updateHistory(historyEntry);
        showToast(`图片发送失败：${error.message}`);
    }
}

async function sendSelectedFile(deviceId, file) {
    const conn = connections[deviceId];
    if (!file || !conn || !conn.authenticated || !conn.ws) return;

    if (file.size > MAX_FILE_TRANSFER_BYTES) {
        showToast('文件过大，请选择 32MB 以内的文件');
        return;
    }

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: `[文件] ${file.name}`,
        status: 'pending',
        kind: 'file',
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);

    try {
        const dataUrl = await readFileAsDataUrl(file);
        const commaIndex = String(dataUrl).indexOf(',');
        if (commaIndex === -1) {
            throw new Error('文件编码失败');
        }

        const fileBase64 = String(dataUrl).slice(commaIndex + 1);
        await sendDeviceAction(deviceId, {
            action: 'file_download',
            payload: {
                file_name: file.name,
                mime_type: file.type || 'application/octet-stream',
                file_base64: fileBase64,
            },
            buttonId: `filebtn-${deviceId}`,
            pendingText: '传文件中...',
            historyEntry,
            failureToast: true,
            successToast: '文件已保存到 Mac 下载文件夹',
            timeoutMs: 45000,
        });
    } catch (error) {
        historyEntry.status = 'failed';
        updateHistory(historyEntry);
        showToast(`文件传输失败：${error.message}`);
    }
}

function normalizePendingSharedContent(raw) {
    if (!raw) {
        return null;
    }

    let data = raw;
    if (typeof raw === 'string') {
        try {
            data = JSON.parse(raw);
        } catch {
            return null;
        }
    }

    if (!data || !data.displayName) {
        return null;
    }

    return {
        displayName: data.displayName,
        mimeType: data.mimeType || 'application/octet-stream',
        sizeBytes: Number(data.sizeBytes || 0),
        isImage: Boolean(data.isImage),
    };
}

function formatFileSize(sizeBytes) {
    if (!Number.isFinite(sizeBytes) || sizeBytes <= 0) {
        return '';
    }
    if (sizeBytes < 1024) {
        return `${sizeBytes} B`;
    }
    if (sizeBytes < 1024 * 1024) {
        return `${(sizeBytes / 1024).toFixed(1)} KB`;
    }
    return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
}

function renderPendingSharedContent() {
    const card = $('share-inbox');
    const title = $('share-inbox-title');
    const hint = $('share-inbox-hint');
    if (!card || !title || !hint) return;

    if (!pendingSharedContent) {
        card.classList.add('hidden');
        title.textContent = '';
        hint.textContent = '';
        return;
    }

    const sizeText = formatFileSize(pendingSharedContent.sizeBytes);
    title.textContent = pendingSharedContent.displayName;
    hint.textContent = pendingSharedContent.isImage
        ? `图片已从系统分享导入${sizeText ? ` · ${sizeText}` : ''}，现在可直接点任意设备的“传图到剪贴板”或“传文件到下载”。`
        : `文件已从系统分享导入${sizeText ? ` · ${sizeText}` : ''}，现在可直接点任意设备的“传文件到下载”。`;
    card.classList.remove('hidden');
}

function clearPendingSharedContentState({ silent = false } = {}) {
    if (window.NativeShare && window.NativeShare.clearPendingSharedContent) {
        try {
            window.NativeShare.clearPendingSharedContent();
        } catch (error) {
            console.warn('清理共享内容失败', error);
        }
    }
    pendingSharedContent = null;
    renderPendingSharedContent();
    if (!silent) {
        showToast('已清除共享内容');
    }
}

function setPendingSharedContent(raw, { announce = false } = {}) {
    const normalized = normalizePendingSharedContent(raw);
    if (!normalized) {
        return;
    }
    pendingSharedContent = normalized;
    renderPendingSharedContent();
    focusSendView();
    if (announce) {
        showToast(`已接收共享内容：${normalized.displayName}`);
    }
}

async function loadPendingSharedContent() {
    if (!window.NativeShare || !window.NativeShare.getPendingSharedContent) {
        return;
    }

    try {
        const raw = window.NativeShare.getPendingSharedContent();
        if (raw) {
            setPendingSharedContent(raw);
        } else {
            renderPendingSharedContent();
        }
    } catch (error) {
        console.warn('读取共享内容失败', error);
    }
}

function initNativeShareInbox() {
    const clearBtn = $('clear-share-btn');
    if (clearBtn) {
        clearBtn.addEventListener('click', () => clearPendingSharedContentState());
    }

    window.addEventListener('native-incoming-share', (event) => {
        setPendingSharedContent(event.detail, { announce: true });
    });
}

function readPendingSharedContentBase64() {
    if (!window.NativeShare || !window.NativeShare.readPendingSharedContentBase64) {
        throw new Error('当前环境不支持系统分享内容读取');
    }

    const base64 = window.NativeShare.readPendingSharedContentBase64();
    if (!base64) {
        throw new Error('共享内容已失效，请重新分享一次');
    }

    return base64;
}

async function sendPendingSharedImage(deviceId) {
    const conn = connections[deviceId];
    if (!pendingSharedContent || !pendingSharedContent.isImage || !conn || !conn.authenticated || !conn.ws) {
        return;
    }

    const shared = pendingSharedContent;
    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: `[图片] ${shared.displayName}`,
        status: 'pending',
        kind: 'image',
        fileName: shared.displayName,
        mimeType: shared.mimeType,
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);

    try {
        const imageBase64 = readPendingSharedContentBase64();
        const dataUrl = `data:${shared.mimeType};base64,${imageBase64}`;

        try {
            historyEntry.thumbnailDataUrl = await createThumbnailDataUrl(
                dataUrl,
                shared.mimeType === 'image/png' ? 'image/png' : 'image/jpeg'
            );
            updateHistory(historyEntry);
        } catch (previewError) {
            console.warn('生成共享图片缩略图失败，继续发送原图:', previewError);
        }

        const result = await sendDeviceAction(deviceId, {
            action: 'image_clipboard',
            payload: {
                file_name: shared.displayName,
                mime_type: shared.mimeType,
                image_base64: imageBase64,
            },
            buttonId: `imagebtn-${deviceId}`,
            pendingText: '传图中...',
            historyEntry,
            failureToast: true,
            successToast: '图片已放入 Mac 剪贴板',
            timeoutMs: 20000,
        });

        if (result.ok) {
            clearPendingSharedContentState({ silent: true });
        }
    } catch (error) {
        historyEntry.status = 'failed';
        updateHistory(historyEntry);
        showToast(`共享图片发送失败：${error.message}`);
    }
}

async function sendPendingSharedFile(deviceId) {
    const conn = connections[deviceId];
    if (!pendingSharedContent || !conn || !conn.authenticated || !conn.ws) {
        return;
    }

    const shared = pendingSharedContent;
    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: `[文件] ${shared.displayName}`,
        status: 'pending',
        kind: 'file',
        fileName: shared.displayName,
        mimeType: shared.mimeType,
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);

    try {
        const fileBase64 = readPendingSharedContentBase64();
        const result = await sendDeviceAction(deviceId, {
            action: 'file_download',
            payload: {
                file_name: shared.displayName,
                mime_type: shared.mimeType,
                file_base64: fileBase64,
            },
            buttonId: `filebtn-${deviceId}`,
            pendingText: '传文件中...',
            historyEntry,
            failureToast: true,
            successToast: '文件已保存到 Mac 下载文件夹',
            timeoutMs: 45000,
        });

        if (result.ok) {
            clearPendingSharedContentState({ silent: true });
        }
    } catch (error) {
        historyEntry.status = 'failed';
        updateHistory(historyEntry);
        showToast(`共享文件发送失败：${error.message}`);
    }
}

// ============================================
// 历史记录（localStorage）
// ============================================

function getHistory() {
    const data = localStorage.getItem(HISTORY_KEY);
    const history = data ? JSON.parse(data) : [];
    let changed = false;
    const hydrated = history.map((entry) => {
        const normalized = hydrateHistoryEntry(entry);
        if (JSON.stringify(normalized) !== JSON.stringify(entry)) {
            changed = true;
        }
        return normalized;
    });

    if (changed) {
        localStorage.setItem(HISTORY_KEY, JSON.stringify(hydrated));
        persistHistory();
    }

    return hydrated;
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

function looksLikeInternalDeviceId(value) {
    return typeof value === 'string' && /^dev(?:[_-]|$)/.test(value);
}

function looksLikeTargetHost(value) {
    if (typeof value !== 'string' || !value) {
        return false;
    }
    return value.includes('.local')
        || value.includes('.lan')
        || /^(\d{1,3}\.){3}\d{1,3}$/.test(value)
        || value.includes(':');
}

function hydrateHistoryEntry(entry) {
    const storedTarget = typeof entry.target === 'string' ? entry.target : '';
    const storedTargetName = typeof entry.targetName === 'string' ? entry.targetName : '';
    const storedTargetAlias = typeof entry.targetAlias === 'string' ? entry.targetAlias : '';
    const storedTargetHost = typeof entry.targetDeviceName === 'string'
        ? entry.targetDeviceName
        : (typeof entry.targetHost === 'string' ? entry.targetHost : '');
    const targetId = entry.targetId
        || entry.deviceId
        || (looksLikeInternalDeviceId(storedTarget) ? storedTarget : '');

    const devices = getDevices();
    const device = targetId ? devices.find((item) => item.id === targetId) : null;

    const targetAlias = [
        storedTargetAlias,
        device?.name || '',
        !looksLikeInternalDeviceId(storedTarget) && !looksLikeTargetHost(storedTarget) ? storedTarget : '',
        !looksLikeInternalDeviceId(storedTargetName) && !looksLikeTargetHost(storedTargetName) ? storedTargetName : '',
    ].find(Boolean) || '';

    const targetDeviceName = [
        storedTargetHost,
        looksLikeTargetHost(storedTargetName) ? storedTargetName : '',
        looksLikeTargetHost(storedTarget) ? storedTarget : '',
        targetId ? getTargetDeviceName(targetId) : '',
    ].find(Boolean) || '';

    const displayTarget = targetAlias || targetDeviceName || targetId || storedTarget || storedTargetName || '未知设备';

    return {
        ...entry,
        target: targetId || storedTarget || displayTarget,
        targetName: displayTarget,
        targetAlias,
        targetDeviceName,
    };
}

function resolveHistoryExportTarget(entry) {
    return entry.targetAlias || entry.targetName || entry.targetDeviceName || '未知设备';
}

function buildHistoryExportData(history) {
    return history.map((entry) => {
        const hydrated = hydrateHistoryEntry(entry);
        const targetAlias = resolveHistoryExportTarget(hydrated);
        const targetHost = hydrated.targetDeviceName || '';
        const {
            target: _targetId,
            targetName,
            targetAlias: _targetAlias,
            targetDeviceName: _targetDeviceName,
            ...rest
        } = hydrated;

        return {
            ...rest,
            timestamp: getLocalTimestamp(hydrated.timestamp),
            target: targetAlias,
            ...(targetHost && targetHost !== targetAlias ? { targetHost } : {}),
        };
    });
}

function normalizeImportedHistoryEntry(entry) {
    const timestampSource = entry.timestamp_iso || entry.timestamp;
    const normalizedTimestamp = getLocalTimestamp(timestampSource);
    const targetAlias = entry.targetAlias || entry.targetName || entry.target || '';
    const targetDeviceName = entry.targetDeviceName || entry.targetHost || entry.hostname || '';
    const targetId = entry.targetId
        || entry.deviceId
        || (typeof entry.target === 'string' && /^dev[_-]/.test(entry.target) ? entry.target : '')
        || resolveHistoryImportTargetId(targetAlias, targetDeviceName)
        || targetAlias
        || targetDeviceName
        || '';

    return {
        ...entry,
        timestamp: normalizedTimestamp,
        target: targetId,
        targetName: targetAlias || targetDeviceName || targetId || '未知设备',
        targetAlias,
        targetDeviceName,
    };
}

function resolveHistoryImportTargetId(targetAlias, targetDeviceName) {
    const devices = getDevices();
    const aliasMatch = devices.find((device) => device.name === targetAlias);
    if (aliasMatch) {
        return aliasMatch.id;
    }

    for (const [deviceId, conn] of Object.entries(connections)) {
        if (conn?.hostname && conn.hostname === targetDeviceName) {
            return deviceId;
        }
    }

    return '';
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
    clearHistoryHeatmapSelection();
    renderHistory();
}

// ---- 历史 UI ----

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
            renderHistory();
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
    const baseEntries = filterHistoryEntries(history);

    if (history.length === 0) {
        renderHistoryHeatmap([]);
        renderHistoryFilterSummary([]);
        list.innerHTML = '<p class="empty-hint">暂无发送记录</p>';
        return;
    }

    renderHistoryHeatmap(baseEntries);
    renderHistoryFilterSummary(baseEntries);
    const filtered = applyHistoryHeatmapSelection(baseEntries);

    if (filtered.length === 0) {
        const emptyText = historyHeatmapState.selectionDate && historyHeatmapState.selectionHour != null
            ? '这个时段没有符合条件的记录'
            : '没有符合筛选条件的记录';
        list.innerHTML = `<p class="empty-hint">${emptyText}</p>`;
        return;
    }

    list.innerHTML = filtered.map((h, i) => {
        const time = formatTime(h.timestamp);
        const statusIcon = h.status === 'success' ? '已送达' : h.status === 'failed' ? '失败' : '发送中';
        const primaryTarget = getHistoryPrimaryTargetLabel(h);
        const secondaryTarget = getHistorySecondaryTargetLabel(h);
        const title = h.kind === 'image'
            ? '图片记录'
            : h.kind === 'file'
                ? '文件记录'
                : '点击复制';
        const thumbnail = h.thumbnailDataUrl || h.thumbnail_data_url;
        const content = h.kind === 'image' && thumbnail
            ? `
                <div class="history-image-row">
                    <img class="history-thumb" src="${thumbnail}" alt="${escapeHtml(h.fileName || h.file_name || h.text || '图片')}">
                    <div class="history-image-meta">
                        <div class="history-text">${escapeHtml(h.text)}</div>
                        <div class="history-image-hint">已发送到 Mac 剪贴板</div>
                    </div>
                </div>
            `
            : h.kind === 'file'
                ? `
                    <div class="history-image-row history-file-row">
                        <div class="history-file-badge">文件</div>
                        <div class="history-image-meta">
                            <div class="history-text">${escapeHtml(h.text)}</div>
                            <div class="history-image-hint">已保存到 Mac 下载文件夹</div>
                        </div>
                    </div>
                `
            : `<div class="history-text">${escapeHtml(h.text)}</div>`;
        return `
            <div class="history-item" data-idx="${i}" style="cursor:pointer" title="${title}">
                <div class="history-item-header">
                    <span class="history-time">${time}</span>
                    <div class="history-target-group">
                        <span class="history-target">${escapeHtml(primaryTarget)}</span>
                        ${secondaryTarget ? `<span class="history-target-detail">${escapeHtml(secondaryTarget)}</span>` : ''}
                    </div>
                    <span class="history-status">${statusIcon}</span>
                </div>
                ${content}
            </div>
        `;
    }).join('');

    // 点击复制
    list.querySelectorAll('.history-item').forEach((item, i) => {
        item.addEventListener('click', () => {
            const entry = filtered[i];
            if (entry.kind === 'image') {
                showToast('图片记录不支持复制');
                return;
            }
            if (entry.kind === 'file') {
                showToast('文件记录不支持复制');
                return;
            }
            const text = entry.text;
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

function filterHistoryEntries(history, filters = currentHistoryFilters) {
    return history.filter((entry) => {
        if (filters.device !== 'all' && entry.target !== filters.device) {
            return false;
        }

        const entryDate = new Date(entry.timestamp);
        if (Number.isNaN(entryDate.getTime())) {
            return false;
        }

        if (!matchesQuickTimeFilter(entryDate, filters)) {
            return false;
        }

        if (!matchesCustomDateRange(entryDate, filters)) {
            return false;
        }

        if (!matchesTimeRange(entryDate, filters)) {
            return false;
        }

        if (!matchesKind(entry, filters)) {
            return false;
        }

        if (!matchesStatus(entry, filters)) {
            return false;
        }

        return true;
    });
}

function matchesQuickTimeFilter(entryDate, filters = currentHistoryFilters) {
    const quick = filters.quickTime;
    if (quick === 'all' || quick === 'custom') {
        return true;
    }

    const now = new Date();
    if (quick === 'today') {
        return entryDate.toDateString() === now.toDateString();
    }

    const days = quick === '7d' ? 7 : 30;
    const threshold = new Date(now);
    threshold.setHours(0, 0, 0, 0);
    threshold.setDate(threshold.getDate() - (days - 1));
    return entryDate >= threshold;
}

function matchesCustomDateRange(entryDate, filters = currentHistoryFilters) {
    if (filters.quickTime !== 'custom') {
        return true;
    }

    const { startDate, endDate } = filters;
    if (!startDate && !endDate) {
        return true;
    }

    const entryDay = `${entryDate.getFullYear()}-${pad2(entryDate.getMonth() + 1)}-${pad2(entryDate.getDate())}`;
    if (startDate && entryDay < startDate) {
        return false;
    }
    if (endDate && entryDay > endDate) {
        return false;
    }
    return true;
}

function matchesTimeRange(entryDate, filters = currentHistoryFilters) {
    const { timeRange, startTime, endTime } = filters;
    if (timeRange === 'all') {
        return true;
    }

    const minutes = entryDate.getHours() * 60 + entryDate.getMinutes();
    const presets = {
        morning: [6 * 60, 11 * 60 + 59],
        afternoon: [12 * 60, 17 * 60 + 59],
        evening: [18 * 60, 23 * 60 + 59],
        night: [0, 5 * 60 + 59],
    };

    if (timeRange !== 'custom') {
        const [start, end] = presets[timeRange] || [0, 24 * 60 - 1];
        return minutes >= start && minutes <= end;
    }

    if (!startTime && !endTime) {
        return true;
    }

    const startMinutes = startTime ? parseClockMinutes(startTime) : 0;
    const endMinutes = endTime ? parseClockMinutes(endTime) : 24 * 60 - 1;
    return minutes >= startMinutes && minutes <= endMinutes;
}

function parseClockMinutes(value) {
    const [hour = '0', minute = '0'] = String(value).split(':');
    return Number(hour) * 60 + Number(minute);
}

function matchesKind(entry, filters = currentHistoryFilters) {
    if (filters.kind === 'all') {
        return true;
    }
    const kind = entry.kind || 'text';
    return kind === filters.kind;
}

function matchesStatus(entry, filters = currentHistoryFilters) {
    if (filters.status === 'all') {
        return true;
    }
    return (entry.status || 'success') === filters.status;
}

function renderHistoryFilterSummary(baseEntries = filterHistoryEntries(getHistory())) {
    const toolbar = $('history-toolbar');
    const summary = $('history-filter-summary');
    if (!summary) return;

    const labels = [];
    const deviceLabel = getHistoryDeviceLabel(currentHistoryFilters.device);
    if (deviceLabel) labels.push(deviceLabel);

    const quickTimeLabels = {
        today: '今天',
        '7d': '近7天',
        '30d': '近30天',
        custom: '自定义日期',
    };
    if (currentHistoryFilters.quickTime !== 'all') {
        labels.push(quickTimeLabels[currentHistoryFilters.quickTime] || '自定义日期');
    }
    if (currentHistoryFilters.quickTime === 'custom' && (currentHistoryFilters.startDate || currentHistoryFilters.endDate)) {
        labels.push(`${currentHistoryFilters.startDate || '开始'} - ${currentHistoryFilters.endDate || '结束'}`);
    }

    const timeRangeLabels = {
        morning: '上午',
        afternoon: '下午',
        evening: '晚上',
        night: '凌晨',
        custom: '自定义时段',
    };
    if (currentHistoryFilters.timeRange !== 'all') {
        if (currentHistoryFilters.timeRange === 'custom') {
            labels.push(`${currentHistoryFilters.startTime || '00:00'} - ${currentHistoryFilters.endTime || '23:59'}`);
        } else {
            labels.push(timeRangeLabels[currentHistoryFilters.timeRange]);
        }
    }

    const kindLabels = {
        text: '文字',
        image: '图片',
        file: '文件',
    };
    if (currentHistoryFilters.kind !== 'all') {
        labels.push(kindLabels[currentHistoryFilters.kind]);
    }

    const statusLabels = {
        success: '成功',
        failed: '失败',
        pending: '发送中',
    };
    if (currentHistoryFilters.status !== 'all') {
        labels.push(statusLabels[currentHistoryFilters.status]);
    }

    if (historyHeatmapState.selectionDate && historyHeatmapState.selectionHour != null) {
        const selectedCount = applyHistoryHeatmapSelection(baseEntries).length;
        labels.push(`${formatHeatmapSelectionLabel(historyHeatmapState.selectionDate, historyHeatmapState.selectionHour)} · ${selectedCount} 条`);
    }

    if (labels.length === 0) {
        toolbar?.classList.add('hidden');
        summary.classList.add('hidden');
        summary.innerHTML = '';
        return;
    }

    toolbar?.classList.remove('hidden');
    summary.classList.remove('hidden');
    summary.innerHTML = `
        <div class="history-filter-chip-list">
            ${labels.map(label => `<span class="history-filter-chip">${escapeHtml(label)}</span>`).join('')}
        </div>
        <button class="history-filter-clear" id="history-filter-clear-btn">清除</button>
    `;
    $('history-filter-clear-btn')?.addEventListener('click', () => {
        currentHistoryFilters = { ...DEFAULT_HISTORY_FILTERS };
        clearHistoryHeatmapSelection();
        renderDeviceFilterState();
        renderTimeFilterState();
        syncHistoryFilterForm();
        renderHistory();
    });
}

function getHistoryDeviceLabel(deviceId) {
    if (deviceId === 'all') {
        return '';
    }
    const devices = getDevices();
    return devices.find(device => device.id === deviceId)?.name || deviceId;
}

function getHistoryPrimaryTargetLabel(entry) {
    return entry.targetAlias || entry.targetName || entry.targetDeviceName || entry.target || '未知设备';
}

function getHistorySecondaryTargetLabel(entry) {
    const primary = getHistoryPrimaryTargetLabel(entry);
    const secondary = entry.targetDeviceName || '';
    if (!secondary || secondary === primary) {
        return '';
    }
    return secondary;
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

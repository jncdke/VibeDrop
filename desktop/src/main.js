const { invoke } = window.__TAURI__.core;
const LOG_HEATMAP_VISIBLE_DAYS = 7;
const LOG_HEATMAP_RENDER_BUFFER_DAYS = 14;
const LOG_HEATMAP_HOUR_SLOTS = 24;
const LOG_HEATMAP_COLUMN_STEP = 90;
const LOG_HEATMAP_DRAG_THRESHOLD = 8;
const LOG_HEATMAP_INERTIA_MS = 180;
const DEFAULT_LOG_HISTORY_FILTERS = {
    device: 'all',
    quickTime: '7d',
    startDate: '',
    endDate: '',
    timeRange: 'all',
    startTime: '',
    endTime: '',
    kind: 'all',
};
let connectedDropClients = [];
let selectedDropClientId = '';
let desktopDropInitialized = false;
let desktopTabsInitialized = false;
let exportLogBound = false;
let textReceiveListenerBound = false;
let desktopPairingInitialized = false;
let desktopHistoryControlsInitialized = false;
let desktopHistoryAdvancedPanelVisible = false;
let lastNonCustomLogQuickTime = DEFAULT_LOG_HISTORY_FILTERS.quickTime;
const desktopTransferItems = new Map();
let currentDesktopTab = 'overview';
let currentLogHistoryFilters = { ...DEFAULT_LOG_HISTORY_FILTERS };
let logHeatmapState = {
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
let pendingPairRequests = [];

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
    initDesktopTabs();
    initDesktopHistoryControls();

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
    await initDesktopPairingExperience();
    await initDesktopDropExperience();

    // 监听后端发来的文字接收事件
    if (window.__TAURI__.event && !textReceiveListenerBound) {
        window.__TAURI__.event.listen('text-received', (event) => {
            addLogItem(event.payload);
        });
        textReceiveListenerBound = true;
    }

    // 导出按钮
    if (!exportLogBound) {
        document.getElementById('btn-export-log').addEventListener('click', exportLog);
        exportLogBound = true;
    }
}

async function initDesktopPairingExperience() {
    if (!desktopPairingInitialized && window.__TAURI__.event) {
        window.__TAURI__.event.listen('pair-requests-changed', (event) => {
            pendingPairRequests = normalizePairRequests(event.payload);
            renderPairRequests();
        });
        desktopPairingInitialized = true;
    }

    await refreshPairRequests();
    renderPairRequests();
}

function initDesktopTabs() {
    if (desktopTabsInitialized) {
        renderDesktopTabState();
        return;
    }

    document.querySelectorAll('.desktop-tab').forEach((tab) => {
        tab.addEventListener('click', () => {
            const nextTab = tab.dataset.tab;
            if (nextTab) {
                showDesktopTab(nextTab, { resetScroll: true });
            }
        });
    });

    desktopTabsInitialized = true;
    renderDesktopTabState();
}

function showDesktopTab(tab, { resetScroll = false } = {}) {
    currentDesktopTab = tab === 'history' ? 'history' : 'overview';
    renderDesktopTabState();

    if (resetScroll) {
        const scroller = document.getElementById('desktop-scroll');
        if (scroller) {
            scroller.scrollTop = 0;
        }
    }
}

function renderDesktopTabState() {
    document.querySelectorAll('.desktop-tab').forEach((tab) => {
        const active = tab.dataset.tab === currentDesktopTab;
        tab.classList.toggle('is-active', active);
        tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });

    document.querySelectorAll('.desktop-page').forEach((page) => {
        page.classList.toggle('hidden', page.dataset.page !== currentDesktopTab);
    });
}

async function initDesktopDropExperience() {
    if (!desktopDropInitialized && window.__TAURI__.event) {
        const eventApi = window.__TAURI__.event;

        eventApi.listen('connected-clients-changed', (event) => {
            connectedDropClients = normalizeConnectedClients(event.payload);
            renderConnectedDevicePanel();
            renderConnectedDropClients();
            renderLog();
        });

        eventApi.listen('desktop-transfer-progress', (event) => {
            upsertDesktopTransferItem(event.payload);
        });

        eventApi.listen('tauri://drag-enter', (event) => {
            const paths = event.payload?.paths || [];
            if (paths.length) {
                toggleWindowDropOverlay(true, paths.length);
                document.getElementById('drop-zone')?.classList.add('is-active');
            }
        });

        eventApi.listen('tauri://drag-leave', () => {
            toggleWindowDropOverlay(false);
            document.getElementById('drop-zone')?.classList.remove('is-active');
        });

        eventApi.listen('tauri://drag-drop', async (event) => {
            toggleWindowDropOverlay(false);
            document.getElementById('drop-zone')?.classList.remove('is-active');
            const paths = event.payload?.paths || [];
            await handleDroppedPaths(paths);
        });

        desktopDropInitialized = true;
    }

    await refreshConnectedDropClients();
    renderConnectedDevicePanel();
    renderConnectedDropClients();
}

async function refreshConnectedDropClients() {
    try {
        const clients = await invoke('list_connected_clients');
        connectedDropClients = normalizeConnectedClients(clients);
    } catch (error) {
        console.error('读取已连接手机客户端失败:', error);
        connectedDropClients = [];
    }
}

function normalizeConnectedClients(clients) {
    if (!Array.isArray(clients)) {
        return [];
    }

    return clients
        .filter((client) => client && client.id)
        .map((client) => ({
            id: client.id,
            name: client.name || '未命名设备',
            can_receive_files: Boolean(client.can_receive_files),
        }))
        .sort((a, b) => {
            if (a.can_receive_files !== b.can_receive_files) {
                return a.can_receive_files ? -1 : 1;
            }
            return String(a.name).localeCompare(String(b.name), 'zh-CN');
        });
}

async function refreshPairRequests() {
    try {
        const requests = await invoke('list_pair_requests');
        pendingPairRequests = normalizePairRequests(requests);
    } catch (error) {
        console.error('读取待确认配对失败:', error);
        pendingPairRequests = [];
    }
}

function normalizePairRequests(requests) {
    if (!Array.isArray(requests)) {
        return [];
    }

    return requests
        .filter((request) => request && request.request_id)
        .map((request) => ({
            request_id: request.request_id,
            client_id: request.client_id || '',
            client_name: request.client_name || '未命名手机',
            code: request.code || '------',
            requested_at: request.requested_at || '',
        }))
        .sort((a, b) => String(b.requested_at).localeCompare(String(a.requested_at)));
}

function renderPairRequests() {
    const list = document.getElementById('pair-request-list');
    const empty = document.getElementById('pair-request-empty');
    const count = document.getElementById('pair-requests-count');
    if (!list || !empty || !count) return;

    count.textContent = String(pendingPairRequests.length);

    if (!pendingPairRequests.length) {
        list.innerHTML = '';
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    list.innerHTML = pendingPairRequests.map((request) => `
        <div class="pair-request-item">
            <div class="pair-request-top">
                <div>
                    <div class="pair-request-name">${escapeHtml(request.client_name)}</div>
                    <div class="pair-request-subtitle">确认手机端显示同一验证码后，再点击同意配对。</div>
                </div>
                <div class="pair-request-code">${escapeHtml(request.code)}</div>
            </div>
            <div class="connected-device-meta">
                <span>${escapeHtml(request.client_id || '未知客户端')}</span>
                <span>${escapeHtml(formatPairRequestTime(request.requested_at))}</span>
            </div>
            <div class="pair-request-actions">
                <button type="button" class="pair-request-btn is-reject" data-pair-action="reject" data-request-id="${escapeHtml(request.request_id)}">拒绝</button>
                <button type="button" class="pair-request-btn is-approve" data-pair-action="approve" data-request-id="${escapeHtml(request.request_id)}">同意配对</button>
            </div>
        </div>
    `).join('');

    list.querySelectorAll('[data-pair-action]').forEach((button) => {
        button.addEventListener('click', async () => {
            const requestId = button.dataset.requestId;
            const action = button.dataset.pairAction;
            if (!requestId) return;
            button.disabled = true;
            try {
                if (action === 'approve') {
                    await invoke('approve_pair_request', { requestId });
                    showToast('已同意配对，请在手机端确认');
                } else {
                    await invoke('reject_pair_request', { requestId });
                    showToast('已拒绝配对请求');
                }
                await refreshPairRequests();
                renderPairRequests();
            } catch (error) {
                showToast(`处理配对失败：${error}`);
                button.disabled = false;
            }
        });
    });
}

function formatPairRequestTime(value) {
    const time = new Date(value || '');
    if (Number.isNaN(time.getTime())) {
        return '刚刚';
    }
    return time.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
    });
}

function renderConnectedDevicePanel() {
    const list = document.getElementById('connected-device-list');
    const empty = document.getElementById('connected-device-empty');
    const count = document.getElementById('connected-devices-count');
    if (!list || !empty || !count) return;

    count.textContent = String(connectedDropClients.length);

    if (!connectedDropClients.length) {
        list.innerHTML = '';
        empty.classList.remove('hidden');
        return;
    }

    empty.classList.add('hidden');
    list.innerHTML = connectedDropClients.map((client) => {
        const capabilityClass = client.can_receive_files ? '' : ' is-limited';
        const capabilityText = client.can_receive_files ? '支持接收文件' : '当前仅支持发送到 Mac';
        return `
            <div class="connected-device-item">
                <div class="connected-device-top">
                    <div class="connected-device-name">${escapeHtml(client.name)}</div>
                    <span class="connected-device-status">已连接</span>
                </div>
                <div class="connected-device-meta">
                    <span class="connected-device-capability${capabilityClass}">${escapeHtml(capabilityText)}</span>
                </div>
            </div>
        `;
    }).join('');
}

function renderConnectedDropClients() {
    const row = document.getElementById('drop-client-row');
    const empty = document.getElementById('drop-client-empty');
    const zone = document.getElementById('drop-zone');
    const caption = document.getElementById('drop-send-caption');
    if (!row || !empty || !zone || !caption) return;
    const fileReadyClients = connectedDropClients.filter((client) => client.can_receive_files);

    if (selectedDropClientId && !fileReadyClients.some((client) => client.id === selectedDropClientId)) {
        selectedDropClientId = '';
    }
    if (!selectedDropClientId && fileReadyClients.length > 0) {
        selectedDropClientId = fileReadyClients[0].id;
    }

    row.innerHTML = '';
    if (!fileReadyClients.length) {
        empty.classList.remove('hidden');
        zone.classList.add('is-disabled');
        void syncPreferredShareClient('');
        caption.textContent = connectedDropClients.length
            ? '已有设备连上这台 Mac，但当前没有支持接收文件的客户端。'
            : '把 Finder 里的文件或文件夹拖进这个窗口，直接发到手机默认下载目录。';
        empty.textContent = connectedDropClients.length
            ? '已有设备在线，但它当前不支持接收文件。请使用最新版手机 App 连接这台 Mac。'
            : '当前没有已连接的手机 App，先在手机端连上这台 Mac。';
        return;
    }

    empty.classList.add('hidden');
    zone.classList.remove('is-disabled');

    fileReadyClients.forEach((client) => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = `drop-client-chip${client.id === selectedDropClientId ? ' is-selected' : ''}`;
        chip.textContent = client.name;
        chip.addEventListener('click', () => {
            selectedDropClientId = client.id;
            void syncPreferredShareClient(selectedDropClientId);
            renderConnectedDropClients();
        });
        row.appendChild(chip);
    });

    const selected = fileReadyClients.find((client) => client.id === selectedDropClientId) || fileReadyClients[0];
    if (selected?.id !== selectedDropClientId) {
        selectedDropClientId = selected?.id || '';
    }
    void syncPreferredShareClient(selectedDropClientId);
    caption.textContent = selected
        ? `把 Finder 里的文件或文件夹拖进这个窗口，直接发到 ${selected.name} 的默认下载目录。`
        : '把 Finder 里的文件或文件夹拖进这个窗口，直接发到手机默认下载目录。';
}

async function syncPreferredShareClient(clientId) {
    try {
        await invoke('set_preferred_share_client', {
            clientId,
        });
    } catch (error) {
        console.error('同步默认分享目标失败:', error);
    }
}

function toggleWindowDropOverlay(visible, itemCount = 0) {
    const overlay = document.getElementById('window-drop-overlay');
    const text = document.getElementById('window-drop-overlay-text');
    if (!overlay || !text) return;

    if (!visible) {
        overlay.classList.add('hidden');
        return;
    }

    const selected = connectedDropClients.find((client) => client.id === selectedDropClientId);
    const targetText = selected ? `发送到 ${selected.name}` : '先选择一个已连接的手机';
    const itemText = itemCount > 1 ? `${itemCount} 个项目` : '当前项目';
    text.textContent = `${itemText}将${targetText}。文件夹和多文件会自动打包成 ZIP。`;
    overlay.classList.remove('hidden');
}

async function handleDroppedPaths(paths) {
    if (!Array.isArray(paths) || !paths.length) {
        return;
    }

    if (!selectedDropClientId) {
        showToast('先选择一个已连接的手机');
        return;
    }

    const normalizedPaths = paths
        .map((path) => String(path || '').trim())
        .filter(Boolean);

    if (!normalizedPaths.length) {
        showToast('没有可发送的文件');
        return;
    }

    try {
        const launch = await invoke('send_dropped_paths', {
            clientId: selectedDropClientId,
            paths: normalizedPaths,
        });
        if (launch && launch.transfer_id) {
            upsertDesktopTransferItem({
                transfer_id: launch.transfer_id,
                client_id: launch.client_id,
                client_name: launch.client_name,
                file_name: normalizedPaths.length > 1 ? `${normalizedPaths.length} 个项目` : pathBaseName(normalizedPaths[0]),
                status: 'preparing',
                progress: 0,
                sent_bytes: 0,
                total_bytes: 0,
                is_archive: normalizedPaths.length > 1,
                detail: '正在准备发送内容',
            });
        }
    } catch (error) {
        showToast(`发送失败：${error}`);
    }
}

function upsertDesktopTransferItem(payload) {
    if (!payload || !payload.transfer_id) {
        return;
    }

    const transferId = payload.transfer_id;
    desktopTransferItems.set(transferId, {
        ...(desktopTransferItems.get(transferId) || {}),
        ...payload,
    });

    const list = document.getElementById('drop-transfer-list');
    if (!list) return;

    const items = Array.from(desktopTransferItems.values())
        .sort((a, b) => String(b.transfer_id).localeCompare(String(a.transfer_id)))
        .slice(0, 6);

    list.innerHTML = items.map((item) => {
        const statusLabel = formatTransferStatus(item.status);
        const progressPercent = Math.max(0, Math.min(100, Math.round((Number(item.progress) || 0) * 100)));
        const metaRight = item.total_bytes
            ? `${formatBytes(item.sent_bytes || 0)} / ${formatBytes(item.total_bytes || 0)}`
            : '等待开始';
        const classes = ['drop-transfer-item'];
        if (item.status === 'success') classes.push('is-success');
        if (item.status === 'error') classes.push('is-error');

        return `
            <div class="${classes.join(' ')}">
                <div class="drop-transfer-top">
                    <div class="drop-transfer-name">${escapeHtml(item.file_name || '发送内容')}</div>
                    <div class="drop-transfer-status">${escapeHtml(statusLabel)}</div>
                </div>
                <div class="drop-transfer-meta">
                    <span>${escapeHtml(item.client_name || '')}${item.is_archive ? ' · ZIP' : ''}</span>
                    <span>${escapeHtml(metaRight)}</span>
                </div>
                <div class="drop-transfer-bar">
                    <div class="drop-transfer-fill" style="width:${progressPercent}%;"></div>
                </div>
                <div class="drop-transfer-meta" style="margin-top:8px;margin-bottom:0;">
                    <span>${escapeHtml(item.detail || '')}</span>
                    <span>${progressPercent}%</span>
                </div>
            </div>
        `;
    }).join('');

    list.classList.toggle('hidden', items.length === 0);
}

function formatTransferStatus(status) {
    switch (status) {
        case 'preparing':
            return '准备中';
        case 'sending':
            return '发送中';
        case 'success':
            return '已完成';
        case 'error':
            return '失败';
        default:
            return '处理中';
    }
}

function pathBaseName(path) {
    const normalized = String(path || '').replace(/\\/g, '/');
    return normalized.split('/').filter(Boolean).pop() || '文件';
}

function formatBytes(value) {
    const size = Number(value || 0);
    if (!Number.isFinite(size) || size <= 0) return '0 B';
    if (size < 1024) return `${size} B`;
    if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
    return `${(size / (1024 * 1024)).toFixed(1)} MB`;
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

    renderLog();
}

function normalizeLogEntry(entryOrText, timestamp) {
    if (typeof entryOrText === 'string') {
        return {
            text: entryOrText,
            timestamp: timestamp || new Date().toISOString(),
            kind: 'text',
            client_id: '',
            client_name: '',
            client_ip: '',
        };
    }

    return {
        ...entryOrText,
        timestamp: entryOrText.timestamp || timestamp || new Date().toISOString(),
        kind: entryOrText.kind || 'text',
        client_id: entryOrText.client_id || entryOrText.clientId || '',
        client_name: entryOrText.client_name || entryOrText.clientName || '',
        client_ip: entryOrText.client_ip || entryOrText.clientIp || '',
    };
}

function addLogItem(entryOrText, timestamp) {
    const entry = normalizeLogEntry(entryOrText, timestamp);

    // 保存到 localStorage
    const log = getLog();
    log.unshift(entry);
    saveLog(log);

    renderLog();
}

function initDesktopHistoryControls() {
    if (desktopHistoryControlsInitialized) {
        renderDesktopHistoryTimeFilterState();
        renderDesktopHistoryAdvancedPanelState();
        syncDesktopHistoryFilterForm();
        return;
    }

    document.getElementById('desktop-history-device-filter')?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-device-filter]');
        if (!button) return;
        currentLogHistoryFilters.device = button.dataset.deviceFilter || 'all';
        clearLogHeatmapSelection();
        renderLog();
    });

    document.getElementById('desktop-history-time-filter')?.addEventListener('click', (event) => {
        const button = event.target.closest('[data-time-filter]');
        if (!button) return;
        const value = button.dataset.timeFilter || 'all';
        if (value === 'custom') {
            desktopHistoryAdvancedPanelVisible = !desktopHistoryAdvancedPanelVisible;
            syncDesktopHistoryFilterForm();
            renderDesktopHistoryTimeFilterState();
            renderDesktopHistoryAdvancedPanelState();
            return;
        }

        currentLogHistoryFilters.quickTime = value;
        lastNonCustomLogQuickTime = value;
        clearLogHeatmapSelection();
        renderLog();
    });

    document.getElementById('desktop-history-time-range')?.addEventListener('change', () => {
        desktopHistoryAdvancedPanelVisible = true;
        syncDesktopCustomTimeInputsState();
        renderDesktopHistoryTimeFilterState();
        renderDesktopHistoryAdvancedPanelState();
    });

    ['desktop-history-start-date', 'desktop-history-end-date', 'desktop-history-start-time', 'desktop-history-end-time', 'desktop-history-kind-filter']
        .forEach((id) => {
            document.getElementById(id)?.addEventListener('input', () => {
                desktopHistoryAdvancedPanelVisible = true;
                renderDesktopHistoryTimeFilterState();
                renderDesktopHistoryAdvancedPanelState();
            });
            document.getElementById(id)?.addEventListener('change', () => {
                desktopHistoryAdvancedPanelVisible = true;
                renderDesktopHistoryTimeFilterState();
                renderDesktopHistoryAdvancedPanelState();
            });
        });

    document.getElementById('desktop-history-advanced-apply')?.addEventListener('click', applyDesktopHistoryFilterForm);
    document.getElementById('desktop-history-advanced-reset')?.addEventListener('click', resetDesktopHistoryAdvancedFilters);
    document.getElementById('btn-clear-history-filter')?.addEventListener('click', resetDesktopHistoryFilters);

    initDesktopLogHeatmapInteractions();
    syncDesktopHistoryFilterForm();
    renderDesktopHistoryTimeFilterState();
    renderDesktopHistoryAdvancedPanelState();
    desktopHistoryControlsInitialized = true;
}

function renderLog() {
    const log = getLog()
        .map((entry) => normalizeLogEntry(entry))
        .sort((a, b) => new Date(b.timestamp || 0) - new Date(a.timestamp || 0));

    renderDesktopHistoryDeviceFilters(log);
    renderDesktopHistoryTimeFilterState();
    renderDesktopHistoryAdvancedPanelState();

    const baseEntries = filterLogEntries(log);
    renderLogHeatmap(baseEntries);
    renderHistoryFilterBanner(baseEntries, log.length);
    renderLogList(applyLogHeatmapSelection(baseEntries), log.length);
}

function renderDesktopHistoryDeviceFilters(entries) {
    const container = document.getElementById('desktop-history-device-filter');
    if (!container) return;

    const devices = getDesktopHistoryDeviceOptions(entries);
    container.innerHTML = [
        `<button type="button" class="desktop-history-filter-btn${currentLogHistoryFilters.device === 'all' ? ' is-active' : ''}" data-device-filter="all">全部</button>`,
        ...devices.map((device) => `
            <button
                type="button"
                class="desktop-history-filter-btn${currentLogHistoryFilters.device === device.id ? ' is-active' : ''}"
                data-device-filter="${escapeHtml(device.id)}"
                title="${escapeHtml(device.label)}"
            >${escapeHtml(device.label)}</button>
        `),
    ].join('');
}

function getDesktopHistoryDeviceOptions(entries) {
    const deviceMap = new Map();

    entries.forEach((entry) => {
        const id = getLogEntryDeviceId(entry);
        if (!id || deviceMap.has(id)) {
            return;
        }
        deviceMap.set(id, {
            id,
            label: getLogEntryDeviceName(entry),
        });
    });

    if (currentLogHistoryFilters.device !== 'all' && !deviceMap.has(currentLogHistoryFilters.device)) {
        deviceMap.set(currentLogHistoryFilters.device, {
            id: currentLogHistoryFilters.device,
            label: currentLogHistoryFilters.device,
        });
    }

    return Array.from(deviceMap.values()).sort((a, b) =>
        a.label.localeCompare(b.label, 'zh-CN')
    );
}

function renderDesktopHistoryTimeFilterState() {
    document.querySelectorAll('#desktop-history-time-filter .desktop-history-filter-btn').forEach((button) => {
        const value = button.dataset.timeFilter;
        const active = value === 'custom'
            ? currentLogHistoryFilters.quickTime === 'custom' || hasDesktopSecondaryFilters()
            : currentLogHistoryFilters.quickTime === value;
        button.classList.toggle('is-active', active);
    });
}

function renderDesktopHistoryAdvancedPanelState() {
    const panel = document.getElementById('desktop-history-advanced-panel');
    if (!panel) return;

    const shouldShow = desktopHistoryAdvancedPanelVisible
        || currentLogHistoryFilters.quickTime === 'custom'
        || hasDesktopSecondaryFilters();

    panel.classList.toggle('hidden', !shouldShow);
}

function syncDesktopHistoryFilterForm() {
    const {
        startDate,
        endDate,
        timeRange,
        startTime,
        endTime,
        kind,
    } = currentLogHistoryFilters;

    if (document.getElementById('desktop-history-start-date')) document.getElementById('desktop-history-start-date').value = startDate;
    if (document.getElementById('desktop-history-end-date')) document.getElementById('desktop-history-end-date').value = endDate;
    if (document.getElementById('desktop-history-time-range')) document.getElementById('desktop-history-time-range').value = timeRange;
    if (document.getElementById('desktop-history-start-time')) document.getElementById('desktop-history-start-time').value = startTime;
    if (document.getElementById('desktop-history-end-time')) document.getElementById('desktop-history-end-time').value = endTime;
    if (document.getElementById('desktop-history-kind-filter')) document.getElementById('desktop-history-kind-filter').value = kind;
    syncDesktopCustomTimeInputsState();
}

function syncDesktopCustomTimeInputsState() {
    const customTime = document.getElementById('desktop-history-time-range')?.value === 'custom';
    if (document.getElementById('desktop-history-start-time')) document.getElementById('desktop-history-start-time').disabled = !customTime;
    if (document.getElementById('desktop-history-end-time')) document.getElementById('desktop-history-end-time').disabled = !customTime;
}

function readDesktopHistoryFilterForm() {
    const timeRange = document.getElementById('desktop-history-time-range')?.value || 'all';
    return {
        startDate: document.getElementById('desktop-history-start-date')?.value || '',
        endDate: document.getElementById('desktop-history-end-date')?.value || '',
        timeRange,
        startTime: timeRange === 'custom' ? (document.getElementById('desktop-history-start-time')?.value || '') : '',
        endTime: timeRange === 'custom' ? (document.getElementById('desktop-history-end-time')?.value || '') : '',
        kind: document.getElementById('desktop-history-kind-filter')?.value || 'all',
    };
}

function applyDesktopHistoryFilterForm() {
    const nextFilters = readDesktopHistoryFilterForm();
    if (nextFilters.startDate && nextFilters.endDate && nextFilters.startDate > nextFilters.endDate) {
        showToast('开始日期不能晚于结束日期');
        return;
    }

    if (nextFilters.timeRange === 'custom'
        && nextFilters.startTime
        && nextFilters.endTime
        && nextFilters.startTime > nextFilters.endTime) {
        showToast('开始时间不能晚于结束时间');
        return;
    }

    const hasAdvanced = Boolean(
        nextFilters.startDate
        || nextFilters.endDate
        || nextFilters.timeRange !== 'all'
        || nextFilters.kind !== 'all'
    );

    currentLogHistoryFilters = {
        ...currentLogHistoryFilters,
        quickTime: hasAdvanced ? 'custom' : lastNonCustomLogQuickTime,
        startDate: nextFilters.startDate,
        endDate: nextFilters.endDate,
        timeRange: nextFilters.timeRange,
        startTime: nextFilters.startTime,
        endTime: nextFilters.endTime,
        kind: nextFilters.kind,
    };

    desktopHistoryAdvancedPanelVisible = hasAdvanced;
    clearLogHeatmapSelection();
    renderLog();
}

function resetDesktopHistoryAdvancedFilters() {
    currentLogHistoryFilters = {
        ...currentLogHistoryFilters,
        quickTime: lastNonCustomLogQuickTime,
        startDate: '',
        endDate: '',
        timeRange: 'all',
        startTime: '',
        endTime: '',
        kind: 'all',
    };
    desktopHistoryAdvancedPanelVisible = false;
    clearLogHeatmapSelection();
    syncDesktopHistoryFilterForm();
    renderLog();
}

function resetDesktopHistoryFilters() {
    currentLogHistoryFilters = { ...DEFAULT_LOG_HISTORY_FILTERS };
    lastNonCustomLogQuickTime = DEFAULT_LOG_HISTORY_FILTERS.quickTime;
    desktopHistoryAdvancedPanelVisible = false;
    clearLogHeatmapSelection();
    syncDesktopHistoryFilterForm();
    renderLog();
}

function hasDesktopSecondaryFilters(filters = currentLogHistoryFilters) {
    return filters.timeRange !== 'all'
        || filters.kind !== 'all'
        || (filters.quickTime === 'custom' && Boolean(filters.startDate || filters.endDate));
}

function renderHistoryFilterBanner(baseEntries, totalCount = 0) {
    const banner = document.getElementById('history-filter-banner');
    const text = document.getElementById('history-filter-text');
    if (!banner || !text) return;

    const labels = [];
    const deviceLabel = getLogFilterDeviceLabel(currentLogHistoryFilters.device, getLog());
    if (deviceLabel) {
        labels.push(deviceLabel);
    }

    const quickTimeLabels = {
        today: '今天',
        '7d': '近7天',
        '30d': '近30天',
        custom: '精确筛选',
    };
    if (currentLogHistoryFilters.quickTime !== 'all') {
        labels.push(quickTimeLabels[currentLogHistoryFilters.quickTime] || currentLogHistoryFilters.quickTime);
    }

    if (currentLogHistoryFilters.quickTime === 'custom'
        && (currentLogHistoryFilters.startDate || currentLogHistoryFilters.endDate)) {
        labels.push(`${currentLogHistoryFilters.startDate || '开始'} - ${currentLogHistoryFilters.endDate || '结束'}`);
    }

    const timeRangeLabels = {
        morning: '上午',
        afternoon: '下午',
        evening: '晚上',
        night: '凌晨',
        custom: `${currentLogHistoryFilters.startTime || '00:00'} - ${currentLogHistoryFilters.endTime || '23:59'}`,
    };
    if (currentLogHistoryFilters.timeRange !== 'all') {
        labels.push(timeRangeLabels[currentLogHistoryFilters.timeRange] || '自定义时段');
    }

    const kindLabels = {
        text: '文字',
        image: '图片',
        file: '文件',
    };
    if (currentLogHistoryFilters.kind !== 'all') {
        labels.push(kindLabels[currentLogHistoryFilters.kind] || currentLogHistoryFilters.kind);
    }

    if (logHeatmapState.selectionDate && logHeatmapState.selectionHour != null) {
        const selectedCount = applyLogHeatmapSelection(baseEntries).length;
        labels.push(`${formatHeatmapSelectionLabel(logHeatmapState.selectionDate, logHeatmapState.selectionHour)} · ${selectedCount} 条`);
    }

    if (!labels.length) {
        banner.classList.add('hidden');
        text.textContent = '';
        return;
    }

    const count = logHeatmapState.selectionDate && logHeatmapState.selectionHour != null
        ? applyLogHeatmapSelection(baseEntries).length
        : baseEntries.length;

    text.textContent = `${labels.join(' · ')} · 共 ${count} 条记录`;
    banner.classList.remove('hidden');
}

function renderLogList(entries, totalCount = 0) {
    const list = document.getElementById('log-list');
    if (!list) return;

    if (!entries.length) {
        const emptyText = totalCount === 0
            ? '等待接收...'
            : logHeatmapState.selectionDate && logHeatmapState.selectionHour != null
                ? '这个时段没有符合条件的记录'
                : '没有符合筛选条件的记录';
        list.innerHTML = `<p class="empty">${emptyText}</p>`;
        return;
    }

    list.innerHTML = '';
    entries.forEach((entry) => {
        list.appendChild(createLogElement(entry));
    });
}

function filterLogEntries(entries, filters = currentLogHistoryFilters) {
    return entries.filter((entry) => {
        if (filters.device !== 'all' && getLogEntryDeviceId(entry) !== filters.device) {
            return false;
        }

        const entryDate = new Date(entry.timestamp || '');
        if (Number.isNaN(entryDate.getTime())) {
            return false;
        }

        if (!matchesLogQuickTimeFilter(entryDate, filters)) {
            return false;
        }

        if (!matchesLogCustomDateRange(entryDate, filters)) {
            return false;
        }

        if (!matchesLogTimeRange(entryDate, filters)) {
            return false;
        }

        if (!matchesLogKind(entry, filters)) {
            return false;
        }

        return true;
    });
}

function matchesLogQuickTimeFilter(entryDate, filters = currentLogHistoryFilters) {
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

function matchesLogCustomDateRange(entryDate, filters = currentLogHistoryFilters) {
    if (filters.quickTime !== 'custom') {
        return true;
    }

    const { startDate, endDate } = filters;
    if (!startDate && !endDate) {
        return true;
    }

    const entryDay = formatDateKey(entryDate);
    if (startDate && entryDay < startDate) {
        return false;
    }
    if (endDate && entryDay > endDate) {
        return false;
    }
    return true;
}

function matchesLogTimeRange(entryDate, filters = currentLogHistoryFilters) {
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

    const startMinutes = startTime ? parseClockMinutes(startTime) : 0;
    const endMinutes = endTime ? parseClockMinutes(endTime) : (24 * 60) - 1;
    return minutes >= startMinutes && minutes <= endMinutes;
}

function matchesLogKind(entry, filters = currentLogHistoryFilters) {
    if (filters.kind === 'all') {
        return true;
    }
    return (entry.kind || 'text') === filters.kind;
}

function parseClockMinutes(value) {
    const [hour = '0', minute = '0'] = String(value).split(':');
    return (Number(hour) * 60) + Number(minute);
}

function getLogHeatmapRangeToken() {
    return [
        currentLogHistoryFilters.device,
        currentLogHistoryFilters.quickTime,
        currentLogHistoryFilters.startDate,
        currentLogHistoryFilters.endDate,
        currentLogHistoryFilters.timeRange,
        currentLogHistoryFilters.startTime,
        currentLogHistoryFilters.endTime,
        currentLogHistoryFilters.kind,
    ].join('|');
}

function getLogHeatmapBounds(baseEntries) {
    const todayKey = formatDateKey(new Date());
    const quick = currentLogHistoryFilters.quickTime;
    const preferredEnd = quick === 'custom' && currentLogHistoryFilters.endDate
        ? currentLogHistoryFilters.endDate
        : todayKey;

    let minDate = '';
    let maxDate = preferredEnd;

    if (quick === 'today') {
        minDate = preferredEnd;
    } else if (quick === '7d') {
        minDate = shiftDateKey(preferredEnd, -(LOG_HEATMAP_VISIBLE_DAYS - 1));
    } else if (quick === '30d') {
        minDate = shiftDateKey(preferredEnd, -29);
    } else if (quick === 'custom') {
        minDate = currentLogHistoryFilters.startDate || '';
    }

    if (!minDate && baseEntries.length > 0) {
        minDate = baseEntries.reduce((earliest, entry) => {
            const entryDate = new Date(entry.timestamp || '');
            if (Number.isNaN(entryDate.getTime())) {
                return earliest;
            }
            const dayKey = formatDateKey(entryDate);
            return !earliest || dayKey < earliest ? dayKey : earliest;
        }, '');
    }

    if (!minDate) {
        minDate = shiftDateKey(preferredEnd, -(LOG_HEATMAP_VISIBLE_DAYS - 1));
    }

    if (minDate > maxDate) {
        minDate = maxDate;
    }

    const spanDays = diffDateKeys(minDate, maxDate) + 1;
    const minViewportEndDate = spanDays >= LOG_HEATMAP_VISIBLE_DAYS
        ? shiftDateKey(minDate, LOG_HEATMAP_VISIBLE_DAYS - 1)
        : maxDate;

    return {
        minDate,
        maxDate,
        preferredEnd,
        minViewportEndDate,
    };
}

function getLogHeatmapDragOffsetBounds(bounds) {
    const currentEnd = logHeatmapState.viewportEndDate || bounds.maxDate;
    const minDeltaDays = diffDateKeys(currentEnd, bounds.minViewportEndDate);
    const maxDeltaDays = diffDateKeys(currentEnd, bounds.maxDate);

    return {
        minOffsetX: -maxDeltaDays * LOG_HEATMAP_COLUMN_STEP,
        maxOffsetX: -minDeltaDays * LOG_HEATMAP_COLUMN_STEP,
    };
}

function clampLogHeatmapOffset(offsetX) {
    return Math.max(
        logHeatmapState.dragMinOffsetX,
        Math.min(logHeatmapState.dragMaxOffsetX, offsetX)
    );
}

function clearLogHeatmapSelection() {
    logHeatmapState.selectionDate = '';
    logHeatmapState.selectionHour = null;
}

function applyLogHeatmapSelection(entries) {
    if (!logHeatmapState.selectionDate || logHeatmapState.selectionHour == null) {
        return entries;
    }

    return entries.filter((entry) => {
        const entryDate = new Date(entry.timestamp || '');
        if (Number.isNaN(entryDate.getTime())) {
            return false;
        }

        return formatDateKey(entryDate) === logHeatmapState.selectionDate
            && entryDate.getHours() === logHeatmapState.selectionHour;
    });
}

function buildLogHeatmapCountMap(entries) {
    const counts = new Map();

    entries.forEach((entry) => {
        const entryDate = new Date(entry.timestamp || '');
        if (Number.isNaN(entryDate.getTime())) {
            return;
        }
        const key = `${formatDateKey(entryDate)}|${entryDate.getHours()}`;
        counts.set(key, (counts.get(key) || 0) + 1);
    });

    return counts;
}

function buildLogHeatmapStats(entries, visibleStart, visibleEnd) {
    const visibleEntries = entries.filter((entry) => {
        const entryDate = new Date(entry.timestamp || '');
        if (Number.isNaN(entryDate.getTime())) {
            return false;
        }
        const dayKey = formatDateKey(entryDate);
        return dayKey >= visibleStart && dayKey <= visibleEnd;
    });

    const perHour = new Map();
    const perDay = new Map();

    visibleEntries.forEach((entry) => {
        const entryDate = new Date(entry.timestamp || '');
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
        { label: '窗口', value: `${formatShortDateLabel(visibleStart)} - ${formatShortDateLabel(visibleEnd)}` },
        { label: '收到', value: `${visibleEntries.length} 条` },
        { label: '高峰时段', value: peakHour == null ? '暂无' : `${pad2(peakHour)}:00 · ${peakHourCount}` },
        { label: '最忙日期', value: peakDay ? `${formatShortDateLabel(peakDay)} · ${peakDayCount}` : '暂无' },
    ];
}

function renderLogHeatmap(baseEntries) {
    const track = document.getElementById('log-heatmap-track');
    const stats = document.getElementById('log-heatmap-stats');
    const caption = document.getElementById('log-heatmap-caption');
    const resetBtn = document.getElementById('btn-log-heatmap-reset');
    if (!track || !stats || !caption || !resetBtn) return;

    const rangeToken = getLogHeatmapRangeToken();
    const bounds = getLogHeatmapBounds(baseEntries);

    if (!logHeatmapState.viewportEndDate || logHeatmapState.rangeToken !== rangeToken) {
        logHeatmapState.viewportEndDate = bounds.preferredEnd;
        logHeatmapState.rangeToken = rangeToken;
    }

    logHeatmapState.viewportEndDate = clampDateKey(
        logHeatmapState.viewportEndDate,
        bounds.minViewportEndDate,
        bounds.maxDate
    );

    const visibleEnd = logHeatmapState.viewportEndDate;
    const visibleStart = shiftDateKey(visibleEnd, -(LOG_HEATMAP_VISIBLE_DAYS - 1));
    const renderStart = shiftDateKey(visibleStart, -LOG_HEATMAP_RENDER_BUFFER_DAYS);
    const renderEnd = shiftDateKey(visibleEnd, LOG_HEATMAP_RENDER_BUFFER_DAYS);
    const counts = buildLogHeatmapCountMap(baseEntries);
    const maxCount = Array.from(counts.values()).reduce((max, value) => Math.max(max, value), 0);
    const todayKey = formatDateKey(new Date());
    const days = [];

    for (let dayKey = renderStart; dayKey <= renderEnd; dayKey = shiftDateKey(dayKey, 1)) {
        days.push(dayKey);
    }

    track.innerHTML = days.map((dayKey) => {
        const dayDate = dateKeyToUtcDate(dayKey);
        const headerLabel = dayKey === todayKey
            ? '今天'
            : dayDate.toLocaleDateString('zh-CN', { weekday: 'short', timeZone: 'UTC' });

        const hoursHtml = Array.from({ length: LOG_HEATMAP_HOUR_SLOTS }, (_, hour) => {
            const count = counts.get(`${dayKey}|${hour}`) || 0;
            const selected = logHeatmapState.selectionDate === dayKey && logHeatmapState.selectionHour === hour;
            const classes = ['heatmap-cell'];
            if (count > 0) classes.push('has-data');
            if (selected) classes.push('selected');
            const label = `${formatHeatmapDateLabel(dayKey)} ${formatHeatmapHourLabel(hour)}，${count} 条`;

            return `
                <button
                    type="button"
                    class="${classes.join(' ')}"
                    style="background:${getHeatmapCellColor(count, maxCount)};"
                    data-date-key="${dayKey}"
                    data-hour="${hour}"
                    aria-label="${label}"
                    title="${label}"
                ></button>
            `;
        }).join('');

        return `
            <div class="heatmap-day ${dayKey === todayKey ? 'is-today' : ''}">
                <div class="heatmap-day-header">
                    <span class="heatmap-day-weekday">${escapeHtml(headerLabel)}</span>
                    <span class="heatmap-day-date">${escapeHtml(formatShortDateLabel(dayKey))}</span>
                </div>
                <div class="heatmap-hours">${hoursHtml}</div>
            </div>
        `;
    }).join('');

    const baseOffset = -(diffDateKeys(renderStart, visibleStart) * LOG_HEATMAP_COLUMN_STEP);
    track.dataset.baseOffset = String(baseOffset);
    track.style.transition = logHeatmapState.dragPointerId == null
        ? 'transform 220ms cubic-bezier(0.22, 1, 0.36, 1)'
        : 'none';
    track.style.transform = `translate3d(${baseOffset + logHeatmapState.dragOffsetX}px, 0, 0)`;

    stats.innerHTML = buildLogHeatmapStats(baseEntries, visibleStart, visibleEnd).map((item) => `
        <div class="heatmap-stat">
            <span class="heatmap-stat-label">${escapeHtml(item.label)}</span>
            <span class="heatmap-stat-value">${escapeHtml(item.value)}</span>
        </div>
    `).join('');

    caption.textContent = `当前窗口 ${formatHeatmapDateLabel(visibleStart)} 至 ${formatHeatmapDateLabel(visibleEnd)}。左右拖动查看更多日期，点方块筛选该小时。`;
    resetBtn.classList.toggle('hidden', visibleEnd === bounds.maxDate);

    track.querySelectorAll('.heatmap-cell').forEach((cell) => {
        cell.addEventListener('click', () => {
            if (Date.now() < logHeatmapState.suppressCellClickUntil) {
                return;
            }

            const dayKey = cell.dataset.dateKey || '';
            const hour = Number(cell.dataset.hour);

            if (logHeatmapState.selectionDate === dayKey && logHeatmapState.selectionHour === hour) {
                clearLogHeatmapSelection();
            } else {
                logHeatmapState.selectionDate = dayKey;
                logHeatmapState.selectionHour = hour;
            }

            renderLog();
        });
    });
}

function initDesktopLogHeatmapInteractions() {
    const viewport = document.getElementById('log-heatmap-viewport');
    const resetBtn = document.getElementById('btn-log-heatmap-reset');
    if (!viewport || viewport.dataset.ready === '1') {
        return;
    }

    viewport.dataset.ready = '1';

    viewport.addEventListener('pointerdown', (event) => {
        const bounds = getLogHeatmapBounds(filterLogEntries(getLog().map((entry) => normalizeLogEntry(entry))));
        const dragBounds = getLogHeatmapDragOffsetBounds(bounds);
        logHeatmapState.dragPointerId = event.pointerId;
        logHeatmapState.dragStartX = event.clientX;
        logHeatmapState.dragOffsetX = 0;
        logHeatmapState.dragLastX = event.clientX;
        logHeatmapState.dragLastTime = Date.now();
        logHeatmapState.dragVelocity = 0;
        logHeatmapState.dragMoved = false;
        logHeatmapState.dragMinOffsetX = dragBounds.minOffsetX;
        logHeatmapState.dragMaxOffsetX = dragBounds.maxOffsetX;
        viewport.setPointerCapture?.(event.pointerId);
    });

    viewport.addEventListener('pointermove', (event) => {
        if (event.pointerId !== logHeatmapState.dragPointerId) {
            return;
        }

        const rawOffsetX = event.clientX - logHeatmapState.dragStartX;
        const offsetX = clampLogHeatmapOffset(rawOffsetX);
        const now = Date.now();
        const deltaTime = Math.max(now - logHeatmapState.dragLastTime, 1);

        logHeatmapState.dragVelocity = (event.clientX - logHeatmapState.dragLastX) / deltaTime;
        logHeatmapState.dragLastX = event.clientX;
        logHeatmapState.dragLastTime = now;

        if (!logHeatmapState.dragMoved && Math.abs(offsetX) < LOG_HEATMAP_DRAG_THRESHOLD) {
            return;
        }

        logHeatmapState.dragMoved = true;
        logHeatmapState.dragOffsetX = offsetX;

        const track = document.getElementById('log-heatmap-track');
        if (!track) return;

        const baseOffset = Number(track.dataset.baseOffset || 0);
        track.style.transition = 'none';
        track.style.transform = `translate3d(${baseOffset + offsetX}px, 0, 0)`;
    });

    const finishDrag = (event) => {
        if (event.pointerId !== logHeatmapState.dragPointerId) {
            return;
        }

        viewport.releasePointerCapture?.(event.pointerId);

        const moved = logHeatmapState.dragMoved;
        const predictedOffset = clampLogHeatmapOffset(
            logHeatmapState.dragOffsetX + (logHeatmapState.dragVelocity * LOG_HEATMAP_INERTIA_MS)
        );

        logHeatmapState.dragPointerId = null;
        logHeatmapState.dragOffsetX = 0;
        logHeatmapState.dragVelocity = 0;
        logHeatmapState.dragLastX = 0;
        logHeatmapState.dragLastTime = 0;
        logHeatmapState.dragMoved = false;
        logHeatmapState.dragMinOffsetX = 0;
        logHeatmapState.dragMaxOffsetX = 0;

        if (!moved) {
            return;
        }

        const bounds = getLogHeatmapBounds(filterLogEntries(getLog().map((entry) => normalizeLogEntry(entry))));
        const deltaDays = -Math.round(predictedOffset / LOG_HEATMAP_COLUMN_STEP);
        logHeatmapState.viewportEndDate = clampDateKey(
            shiftDateKey(logHeatmapState.viewportEndDate, deltaDays),
            bounds.minViewportEndDate,
            bounds.maxDate
        );
        clearLogHeatmapSelection();
        logHeatmapState.suppressCellClickUntil = Date.now() + 180;
        renderLog();
    };

    viewport.addEventListener('pointerup', finishDrag);
    viewport.addEventListener('pointercancel', finishDrag);

    resetBtn?.addEventListener('click', () => {
        const bounds = getLogHeatmapBounds(filterLogEntries(getLog().map((entry) => normalizeLogEntry(entry))));
        logHeatmapState.viewportEndDate = bounds.maxDate;
        clearLogHeatmapSelection();
        renderLog();
    });
}

function createLogElement(entryOrText, timestamp) {
    const entry = normalizeLogEntry(entryOrText, timestamp);
    const timeLabel = formatLogTime(entry.timestamp);
    const isImage = entry.kind === 'image';
    const isFile = entry.kind === 'file';
    const thumbnail = entry.thumbnail_data_url || entry.thumbnailDataUrl;
    const imagePath = entry.image_path || entry.imagePath;
    const filePath = entry.file_path || entry.filePath;
    const sourceName = getLogEntryDeviceName(entry);
    const sourceDetail = getLogEntryDeviceDetail(entry);
    const kindLabel = isImage ? '图片' : isFile ? '文件' : '文字';

    const item = document.createElement('div');
    item.className = 'log-item';
    item.title = isImage ? '点击打开原图' : isFile ? '点击打开文件' : '点击复制';
    item.style.cursor = 'pointer';

    const headerHtml = `
        <div class="log-item-top">
            <div class="log-time">${escapeHtml(timeLabel)}</div>
            <div class="log-source-row">
                <span class="log-source-chip">${escapeHtml(sourceName)}</span>
                ${sourceDetail ? `<span class="log-source-detail">${escapeHtml(sourceDetail)}</span>` : ''}
                <span class="log-kind-chip">${escapeHtml(kindLabel)}</span>
            </div>
        </div>
    `;

    if (isImage && thumbnail) {
        item.classList.add('log-item-image');
        item.innerHTML = `
            ${headerHtml}
            <div class="log-image-row">
                <img class="log-image-thumb" src="${thumbnail}" alt="${escapeHtml(entry.file_name || entry.text || '图片')}">
                <div class="log-image-meta">
                    <div class="log-text">${escapeHtml(entry.text || '图片')}</div>
                    <div class="log-image-hint">点击打开原图</div>
                </div>
            </div>
        `;
        item.addEventListener('click', async () => {
            if (!imagePath) {
                showToast('原图文件不可用');
                return;
            }
            try {
                await invoke('open_history_path', { path: imagePath });
            } catch (error) {
                showToast(`打开失败：${error}`);
            }
        });
        return item;
    }

    if (isFile) {
        item.classList.add('log-item-file');
        item.innerHTML = `
            ${headerHtml}
            <div class="log-image-row">
                <div class="log-file-badge">文件</div>
                <div class="log-image-meta">
                    <div class="log-text">${escapeHtml(entry.text || '文件')}</div>
                    <div class="log-image-hint">点击打开下载的文件</div>
                </div>
            </div>
        `;
        item.addEventListener('click', async () => {
            if (!filePath) {
                showToast('文件路径不可用');
                return;
            }
            try {
                await invoke('open_history_path', { path: filePath });
            } catch (error) {
                showToast(`打开失败：${error}`);
            }
        });
        return item;
    }

    item.innerHTML = `
        ${headerHtml}
        <div class="log-text">${escapeHtml(entry.text || '')}</div>
    `;
    item.addEventListener('click', () => {
        navigator.clipboard.writeText(entry.text || '');
        showToast('已复制');
    });
    return item;
}

function getLogEntryDeviceId(entry) {
    const fallback = getSingleConnectedClientFallback();
    return entry.client_id || fallback?.id || entry.client_ip || entry.client_name || '';
}

function getLogEntryDeviceName(entry) {
    if (entry.client_name) {
        return entry.client_name;
    }
    const connectedMatch = entry.client_id
        ? connectedDropClients.find((client) => client.id === entry.client_id)
        : null;
    if (connectedMatch?.name) {
        return connectedMatch.name;
    }
    const fallback = getSingleConnectedClientFallback();
    if (fallback?.name) {
        return fallback.name;
    }
    if (entry.client_ip && entry.client_ip !== 'ws-client') {
        return entry.client_ip;
    }
    return '未标记设备';
}

function getLogEntryDeviceDetail(entry) {
    if (entry.client_ip && entry.client_ip !== 'ws-client' && entry.client_ip !== entry.client_name) {
        return entry.client_ip;
    }
    return '';
}

function getSingleConnectedClientFallback() {
    if (connectedDropClients.length === 1) {
        return connectedDropClients[0];
    }
    return null;
}

function getLogFilterDeviceLabel(deviceId, entries = []) {
    if (deviceId === 'all') {
        return '';
    }
    const option = getDesktopHistoryDeviceOptions(entries.map((entry) => normalizeLogEntry(entry)))
        .find((item) => item.id === deviceId);
    return option?.label || deviceId;
}

function formatLogTime(value) {
    const timestamp = new Date(value || '');
    if (Number.isNaN(timestamp.getTime())) {
        return '未知时间';
    }

    const now = new Date();
    const time = timestamp.toLocaleTimeString('zh-CN', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
    });
    if (timestamp.toDateString() === now.toDateString()) {
        return `今天 ${time}`;
    }
    const date = timestamp.toLocaleDateString('zh-CN', {
        month: '2-digit',
        day: '2-digit',
    });
    return `${date} ${time}`;
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

function pad2(value) {
    return String(value).padStart(2, '0');
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

function dateKeyToUtcDate(value) {
    const [year, month, day] = String(value).split('-').map(Number);
    if (!year || !month || !day) {
        return null;
    }
    return new Date(Date.UTC(year, (month || 1) - 1, day || 1));
}

function shiftDateKey(value, deltaDays) {
    const date = dateKeyToUtcDate(value);
    if (!date) {
        return formatDateKey(new Date());
    }
    date.setUTCDate(date.getUTCDate() + deltaDays);
    return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

function formatDateKey(date) {
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
}

function formatShortDate(value) {
    const [year, month, day] = String(value).split('-').map(Number);
    if (!year || !month || !day) return value;
    return `${month}/${day}`;
}

function formatShortDateLabel(value) {
    return formatShortDate(value);
}

function formatHeatmapDateLabel(value) {
    const [year, month, day] = String(value).split('-').map(Number);
    if (!year || !month || !day) return value;
    return `${year}年${month}月${day}日`;
}

function formatHeatmapHourLabel(hour) {
    return `${pad2(hour)}:00-${pad2(hour)}:59`;
}

function formatHeatmapSelectionLabel(dateKey, hour) {
    if (!dateKey || hour == null) return '';
    return `${formatHeatmapDateLabel(dateKey)} ${formatHeatmapHourLabel(hour)}`;
}

function getHeatmapCellColor(count, maxCount) {
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

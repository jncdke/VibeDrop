// ============================================
// VibeDrop — 前端逻辑（动态多设备版）
// ============================================

// ---- 常量 ----
const HEARTBEAT_INTERVAL = 15000;
const HEARTBEAT_TIMEOUT = 45000;
const RECONNECT_BASE_INTERVAL = 2000;
const RECONNECT_MAX_INTERVAL = 30000;
const HISTORY_KEY = 'voicedrop_history';
const SETTINGS_KEY = 'voicedrop_settings';
const CLIENT_IDENTITY_KEY = 'vibedrop_client_identity';
const MAX_IMAGE_FILE_BYTES = 10 * 1024 * 1024;
const FILE_TRANSFER_CHUNK_BYTES = 192 * 1024;
const FILE_TRANSFER_START_TIMEOUT_MS = 10000;
const FILE_TRANSFER_ACK_TIMEOUT_MS = 10 * 60 * 1000;
const FILE_TRANSFER_WS_BUFFER_HIGH_WATER_BYTES = 768 * 1024;
const FILE_TRANSFER_WS_BUFFER_POLL_MS = 20;
const HEATMAP_VISIBLE_DAYS = 5;
const HEATMAP_HOUR_SLOTS = 24;
const HISTORY_RENDER_INITIAL_BATCH = 12;
const HISTORY_RENDER_BATCH_SIZE = 20;
const DESKTOP_DISCOVERY_DEFAULT_PORT = 9001;
const DESKTOP_PAIRING_POLL_MS = 1200;
const MEDIA_OPENER_KINDS = ['image', 'video'];
const NEARBY_REORDER_LONG_PRESS_MS = 260;
const NEARBY_REORDER_CANCEL_DISTANCE = 10;
const NEARBY_REORDER_HINT_KEY = 'vibedrop_nearby_reorder_hint_seen';
const SMART_DISCOVERY_REFRESH_COOLDOWN_MS = 15000;
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
    query: '',
};

// ---- 状态 ----
const connections = {}; // key = device.id, value = { ws, authenticated, hostname, ... }
const incomingDesktopTransfers = {};
const outboundDesktopFileTransfers = {};
const sendDrafts = new Map();
let sendDraftFocusState = null;
let pendingSharedContents = [];
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
    suppressCellClickUntil: 0,
    scrollFrame: 0,
    renderedEntries: [],
    renderedCounts: new Map(),
    renderedBounds: null,
};
let nearbyDesktopState = {
    items: [],
    scanning: false,
    scanError: '',
    pairing: null,
    pollTimer: null,
};
let smartDiscoveryRefreshState = {
    inFlight: null,
    lastStartedAt: 0,
};
let nearbyDesktopReorderState = {
    pointerId: null,
    list: null,
    item: null,
    placeholder: null,
    deviceId: '',
    originX: 0,
    originY: 0,
    originLeft: 0,
    originTop: 0,
    offsetX: 0,
    offsetY: 0,
    itemWidth: 0,
    itemHeight: 0,
    longPressTimer: null,
    active: false,
};
let historyMediaPreviewInitialized = false;
let historyMediaPreviewState = {
    entry: null,
    items: [],
};
let mediaOpenerPickerState = {
    kind: '',
    item: null,
    mode: 'open',
    apps: [],
    remember: false,
};
let historyMediaViewerInitialized = false;
let historyMediaViewerState = {
    entry: null,
    items: [],
    currentIndex: 0,
    plyr: null,
};
let historyRenderScheduled = false;
let historyRenderToken = 0;
let currentRenderedHistoryEntries = [];
const historyMediaPreviewUriCache = new Map();
const historyMediaPreviewDimensionsCache = new Map();
let settingsRevision = 0;
let historyRevision = 0;
let storedSettingsCache = {
    raw: null,
    value: {},
};
let storedDevicesCache = {
    key: null,
    value: [],
};
let storedHistoryEntriesCache = {
    raw: null,
    value: [],
};
let hydratedHistoryCache = {
    historyRevision: -1,
    devicesKey: null,
    value: [],
};
let knownDeviceHostNamesCache = {
    historyRevision: -1,
    values: new Map(),
};
let photoswipeReady = typeof window.PhotoSwipe === 'function';

window.addEventListener('vibedrop:photoswipe-ready', () => {
    photoswipeReady = typeof window.PhotoSwipe === 'function';
});

// ---- DOM 缓存 ----
const $ = (id) => document.getElementById(id);
let clientIdentity = getClientIdentity();
let nativeCapabilityWatcherStarted = false;
let lastNativeCapabilityState = supportsNativeFileReceive();

// ---- 初始化 ----
document.addEventListener('DOMContentLoaded', async () => {
    clientIdentity = getClientIdentity();
    await loadPersistentHistory(); // 从文件恢复历史（优先于 localStorage）
    loadSettings();
    syncNativeBackgroundClipboardConfig(clientIdentity);
    initNavigation();
    initSettingsButton();
    initMediaOpenerSettings();
    initNearbyDesktopDiscovery();
    initHistoryActions();
    initHistoryFilterControls();
    initHistoryHeatmapInteractions();
    initHistoryMediaPreview();
    initHistoryMediaViewer();
    initNativeShareInbox();
    startNativeCapabilityWatcher();
    await loadPendingSharedContent();
    void requestSmartDiscoveryRefresh('startup');
});

// ---- 持久化存储（Tauri 文件系统）----
async function loadPersistentHistory() {
    if (!supportsNativeFileReceive()) return;
    try {
        const data = await invokeNative('load_history');
        if (data && data !== '[]') {
            setStoredHistoryRaw(data);
        }
    } catch (e) { console.log('加载持久化历史失败:', e); }
}

function persistHistory() {
    if (!supportsNativeFileReceive()) return;
    const data = localStorage.getItem(HISTORY_KEY) || '[]';
    invokeNative('save_history', { data }).catch(() => {});
}

function startNativeCapabilityWatcher() {
    if (nativeCapabilityWatcherStarted) {
        return;
    }

    nativeCapabilityWatcherStarted = true;
    lastNativeCapabilityState = supportsNativeFileReceive();
    if (lastNativeCapabilityState) {
        return;
    }

    let remainingChecks = 40;
    const poll = () => {
        const currentState = supportsNativeFileReceive();
        if (currentState !== lastNativeCapabilityState) {
            lastNativeCapabilityState = currentState;
            if (currentState) {
                console.log('检测到 Tauri 原生 bridge 已就绪，刷新原生能力状态');
                handleNativeCapabilityReady();
                return;
            }
        }

        if (remainingChecks <= 0) {
            return;
        }
        remainingChecks -= 1;
        window.setTimeout(poll, 150);
    };

    window.setTimeout(poll, 150);
}

function handleNativeCapabilityReady() {
    clientIdentity = getClientIdentity();
    syncNativeBackgroundClipboardConfig(clientIdentity);
    void loadPersistentHistory();
    void loadPendingSharedContent();
    reconnectDevicesForNativeCapability();
    renderNearbyDesktops();
    void requestSmartDiscoveryRefresh('native-capability-ready');
}

function reconnectDevicesForNativeCapability() {
    console.log('原生接收能力已就绪，重连已保存设备以刷新 can_receive_files 能力');
    void connectAll({ refreshDiscovery: true });
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
    const settings = getStoredSettingsObject();
    if (!settings || typeof settings !== 'object') return [];

    // 向后兼容：旧格式 mac1Ip/mac2Ip → 新格式 devices[]
    if (!settings.devices) {
        const devices = [];
        if (settings.mac1Ip) {
            devices.push({
                id: 'dev_migrated_1',
                name: '设备 1',
                ip: settings.mac1Ip,
                port: settings.mac1Port || '9001',
                pin: settings.mac1Pin || '',
                serverId: '',
                legacyIds: [],
            });
        }
        if (settings.mac2Ip) {
            devices.push({
                id: 'dev_migrated_2',
                name: '设备 2',
                ip: settings.mac2Ip,
                port: settings.mac2Port || '9001',
                pin: settings.mac2Pin || '',
                serverId: '',
                legacyIds: [],
            });
        }
        if (devices.length > 0) {
            saveDevices(devices);
        }
        return devices;
    }

    const cacheKey = `${settingsRevision}:${historyRevision}`;
    if (storedDevicesCache.key === cacheKey) {
        return storedDevicesCache.value;
    }

    const normalized = normalizeDevices(settings.devices, readStoredHistoryEntries());
    if (JSON.stringify(normalized) !== JSON.stringify(settings.devices)) {
        saveStoredSettingsObject({
            ...settings,
            devices: normalized,
        });
    }
    storedDevicesCache = {
        key: cacheKey,
        value: normalized,
    };
    return normalized;
}

function saveDevices(devices) {
    const normalized = normalizeDevices(devices);
    saveStoredSettingsObject({
        ...getStoredSettingsObject(),
        devices: normalized,
    });
    syncNativeBackgroundClipboardConfig();
}

function rerenderAllDeviceViews(devices = getDevices()) {
    renderDeviceCards(devices);
    renderSendCards(devices);
    renderHistoryFilters(devices);
    renderNearbyDesktops();
    return devices;
}

function getStoredSettingsObject() {
    const saved = localStorage.getItem(SETTINGS_KEY);
    if (saved === storedSettingsCache.raw) {
        return storedSettingsCache.value;
    }
    if (!saved) {
        storedSettingsCache = {
            raw: null,
            value: {},
        };
        settingsRevision += 1;
        return storedSettingsCache.value;
    }

    try {
        const parsed = JSON.parse(saved);
        storedSettingsCache = {
            raw: saved,
            value: parsed && typeof parsed === 'object' ? parsed : {},
        };
        settingsRevision += 1;
        return storedSettingsCache.value;
    } catch (error) {
        console.warn('读取设置失败，使用默认设置', error);
        storedSettingsCache = {
            raw: saved,
            value: {},
        };
        settingsRevision += 1;
        return storedSettingsCache.value;
    }
}

function saveStoredSettingsObject(settings) {
    const normalized = settings && typeof settings === 'object' ? settings : {};
    const raw = JSON.stringify(normalized);
    const previousRaw = storedSettingsCache.raw;
    localStorage.setItem(SETTINGS_KEY, raw);
    storedSettingsCache = {
        raw,
        value: normalized,
    };
    if (raw !== previousRaw) {
        settingsRevision += 1;
    }
    storedDevicesCache.key = null;
    hydratedHistoryCache.devicesKey = null;
    hydratedHistoryCache.value = [];
}

function normalizeMediaOpenerPreference(pref) {
    if (!pref || typeof pref !== 'object') {
        return null;
    }
    const packageName = String(pref.packageName || '').trim();
    if (!packageName) {
        return null;
    }
    return {
        packageName,
        label: String(pref.label || packageName).trim() || packageName,
    };
}

function getPreferredMediaOpeners() {
    const settings = getStoredSettingsObject();
    const raw = settings.mediaOpeners && typeof settings.mediaOpeners === 'object'
        ? settings.mediaOpeners
        : {};
    return {
        image: normalizeMediaOpenerPreference(raw.image),
        video: normalizeMediaOpenerPreference(raw.video),
    };
}

function getPreferredMediaOpener(kind) {
    const openers = getPreferredMediaOpeners();
    return MEDIA_OPENER_KINDS.includes(kind) ? openers[kind] : null;
}

function setPreferredMediaOpener(kind, opener) {
    if (!MEDIA_OPENER_KINDS.includes(kind) || !opener?.packageName) {
        return;
    }
    const settings = getStoredSettingsObject();
    const current = getPreferredMediaOpeners();
    current[kind] = normalizeMediaOpenerPreference(opener);
    saveStoredSettingsObject({
        ...settings,
        mediaOpeners: current,
    });
    renderMediaOpenerSettings();
}

function clearPreferredMediaOpener(kind) {
    if (!MEDIA_OPENER_KINDS.includes(kind)) {
        return;
    }
    const settings = getStoredSettingsObject();
    const current = getPreferredMediaOpeners();
    current[kind] = null;
    saveStoredSettingsObject({
        ...settings,
        mediaOpeners: current,
    });
    renderMediaOpenerSettings();
}

function supportsMediaOpenerPreferences() {
    return Boolean(
        window.NativeMediaLibrary
        && typeof window.NativeMediaLibrary.listOpeners === 'function'
        && typeof window.NativeMediaLibrary.openPathWithPackage === 'function'
    );
}

function normalizeDeviceRecord(device) {
    const rawHostName = String(device?.hostName || device?.hostname || '').trim();
    return {
        id: String(device?.id || newDeviceId()),
        name: String(device?.name || '未命名设备').trim() || '未命名设备',
        hostName: looksLikeTargetHost(rawHostName) ? rawHostName : '',
        ip: String(device?.ip || '').trim(),
        port: String(device?.port || DESKTOP_DISCOVERY_DEFAULT_PORT).trim() || String(DESKTOP_DISCOVERY_DEFAULT_PORT),
        pin: String(device?.pin || '').trim(),
        serverId: String(device?.serverId || '').trim(),
        legacyIds: Array.from(new Set((Array.isArray(device?.legacyIds) ? device.legacyIds : [])
            .map((item) => String(item || '').trim())
            .filter(Boolean))),
    };
}

function normalizeDesktopName(value) {
    return String(value || '').trim().toLowerCase();
}

function normalizeMachineIdentity(value) {
    return String(value || '')
        .trim()
        .toLowerCase()
        .replace(/\.local$/g, '')
        .replace(/[^a-z0-9]+/g, '');
}

function getStoredHistoryRaw() {
    return localStorage.getItem(HISTORY_KEY) || '[]';
}

function setStoredHistoryRaw(raw) {
    const nextRaw = typeof raw === 'string' ? raw : '[]';
    const previousRaw = storedHistoryEntriesCache.raw;
    localStorage.setItem(HISTORY_KEY, nextRaw);
    storedHistoryEntriesCache = {
        raw: nextRaw,
        value: null,
    };
    if (nextRaw !== previousRaw) {
        historyRevision += 1;
    }
    hydratedHistoryCache = {
        historyRevision: -1,
        devicesKey: null,
        value: [],
    };
    knownDeviceHostNamesCache = {
        historyRevision: -1,
        values: new Map(),
    };
    storedDevicesCache.key = null;
}

function clearStoredHistory() {
    const hadHistory = storedHistoryEntriesCache.raw !== null;
    localStorage.removeItem(HISTORY_KEY);
    storedHistoryEntriesCache = {
        raw: null,
        value: [],
    };
    if (hadHistory) {
        historyRevision += 1;
    }
    hydratedHistoryCache = {
        historyRevision: -1,
        devicesKey: null,
        value: [],
    };
    knownDeviceHostNamesCache = {
        historyRevision: -1,
        values: new Map(),
    };
    storedDevicesCache.key = null;
}

function readStoredHistoryEntries() {
    const raw = getStoredHistoryRaw();
    if (raw === storedHistoryEntriesCache.raw && Array.isArray(storedHistoryEntriesCache.value)) {
        return storedHistoryEntriesCache.value;
    }
    try {
        if (!raw) {
            return [];
        }
        const parsed = JSON.parse(raw);
        const entries = Array.isArray(parsed) ? parsed : [];
        storedHistoryEntriesCache = {
            raw,
            value: entries,
        };
        return entries;
    } catch (error) {
        console.warn('读取历史记录失败，跳过设备主机名推断', error);
        storedHistoryEntriesCache = {
            raw,
            value: [],
        };
        return [];
    }
}

function getKnownDeviceHostNames(device, historyEntries = readStoredHistoryEntries()) {
    const currentHistoryRevision = historyEntries === storedHistoryEntriesCache.value ? historyRevision : -1;
    const cacheKey = [
        device?.id || '',
        device?.serverId || '',
        device?.name || '',
        device?.hostName || '',
        ...(Array.isArray(device?.legacyIds) ? device.legacyIds : []),
    ].join('|');

    if (
        currentHistoryRevision >= 0
        && knownDeviceHostNamesCache.historyRevision === currentHistoryRevision
        && knownDeviceHostNamesCache.values.has(cacheKey)
    ) {
        return knownDeviceHostNamesCache.values.get(cacheKey);
    }

    const ids = new Set([
        String(device?.id || '').trim(),
        ...(Array.isArray(device?.legacyIds) ? device.legacyIds : []).map((item) => String(item || '').trim()),
    ].filter(Boolean));
    const tokens = new Set();

    const pushToken = (value) => {
        const normalized = normalizeDesktopName(value);
        if (normalized && looksLikeTargetHost(normalized)) {
            tokens.add(normalized);
        }
    };

    pushToken(device?.hostName);
    pushToken(device?.name);
    pushToken(connections[device?.id]?.hostname);

    historyEntries.forEach((entry) => {
        const targetId = String(entry?.target || entry?.targetId || entry?.deviceId || '').trim();
        const targetServerId = String(entry?.targetServerId || entry?.serverId || '').trim();
        const targetAlias = normalizeDesktopName(
            entry?.targetAlias
            || entry?.targetName
            || (!looksLikeTargetHost(entry?.target) ? entry?.target : '')
            || ''
        );
        const targetHostCandidate = entry?.targetDeviceName || entry?.targetHost || entry?.hostname || '';
        if (
            (targetId && ids.has(targetId))
            || (device?.serverId && targetServerId && device.serverId === targetServerId)
            || (normalizeDesktopName(device?.name) && targetAlias === normalizeDesktopName(device?.name))
        ) {
            pushToken(targetHostCandidate);
        }
    });

    const result = Array.from(tokens);
    if (currentHistoryRevision >= 0) {
        if (knownDeviceHostNamesCache.historyRevision !== currentHistoryRevision) {
            knownDeviceHostNamesCache = {
                historyRevision: currentHistoryRevision,
                values: new Map(),
            };
        }
        knownDeviceHostNamesCache.values.set(cacheKey, result);
    }
    return result;
}

function getDeviceIdentityLabels(device, historyEntries = readStoredHistoryEntries()) {
    return Array.from(new Set([
        String(device?.name || '').trim(),
        String(device?.hostName || '').trim(),
        String(device?.ip || '').trim(),
        ...getKnownDeviceHostNames(device, historyEntries),
        String(getTargetDeviceName(device?.id) || '').trim(),
    ].filter(Boolean)));
}

function getDeviceHostIdentityTokens(device, historyEntries = readStoredHistoryEntries()) {
    return Array.from(new Set([
        String(device?.hostName || '').trim(),
        ...getKnownDeviceHostNames(device, historyEntries),
    ]
        .map((value) => normalizeDesktopName(value))
        .filter((value) => value && looksLikeTargetHost(value))));
}

function labelsLookLikeSameMachine(a, b) {
    const normalizedA = normalizeMachineIdentity(a);
    const normalizedB = normalizeMachineIdentity(b);
    if (!normalizedA || !normalizedB) {
        return false;
    }
    if (normalizedA === normalizedB) {
        return true;
    }

    const shorter = normalizedA.length <= normalizedB.length ? normalizedA : normalizedB;
    const longer = normalizedA.length <= normalizedB.length ? normalizedB : normalizedA;
    return shorter.length >= 6 && longer.includes(shorter);
}

function getDeviceIdentityKey(device) {
    if (device.serverId) {
        return `server:${device.serverId}`;
    }
    if (device.hostName) {
        return `host:${normalizeDesktopName(device.hostName)}`;
    }
    if (device.ip) {
        return `endpoint:${device.ip}:${String(device.port || DESKTOP_DISCOVERY_DEFAULT_PORT)}`;
    }
    return `device:${device.id}`;
}

function mergeDeviceRecords(primary, secondary) {
    const merged = { ...primary };
    const primaryNameIsPlaceholder = /^设备\s+\d+$/.test(merged.name) || merged.name === '未命名设备';
    const secondaryNameIsUsable = secondary.name && !/^设备\s+\d+$/.test(secondary.name) && secondary.name !== '未命名设备';

    if ((!merged.name || primaryNameIsPlaceholder) && secondaryNameIsUsable) {
        merged.name = secondary.name;
    }
    if (!merged.ip && secondary.ip) {
        merged.ip = secondary.ip;
    }
    if ((!merged.port || merged.port === String(DESKTOP_DISCOVERY_DEFAULT_PORT)) && secondary.port) {
        merged.port = secondary.port;
    }
    if (!merged.pin && secondary.pin) {
        merged.pin = secondary.pin;
    }
    if (!merged.hostName && secondary.hostName) {
        merged.hostName = secondary.hostName;
    }
    if (!merged.serverId && secondary.serverId) {
        merged.serverId = secondary.serverId;
    }

    const legacyIds = new Set([
        ...merged.legacyIds,
        merged.id,
        ...secondary.legacyIds,
        secondary.id,
    ].filter(Boolean));
    legacyIds.delete(merged.id);
    merged.legacyIds = Array.from(legacyIds);

    return merged;
}

function shouldMergeDeviceRecords(primary, secondary, historyEntries = readStoredHistoryEntries()) {
    if (!primary || !secondary) {
        return false;
    }

    if (primary.serverId && secondary.serverId && primary.serverId !== secondary.serverId) {
        return false;
    }

    if (primary.serverId && secondary.serverId && primary.serverId === secondary.serverId) {
        return true;
    }

    const primaryHostName = normalizeDesktopName(primary.hostName);
    const secondaryHostName = normalizeDesktopName(secondary.hostName);
    if (primaryHostName && secondaryHostName && primaryHostName === secondaryHostName) {
        return true;
    }

    const primaryPort = String(primary.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
    const secondaryPort = String(secondary.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
    if (primary.ip && secondary.ip && primary.ip === secondary.ip && primaryPort === secondaryPort) {
        return true;
    }

    const primaryLabels = getDeviceIdentityLabels(primary, historyEntries);
    const secondaryLabels = getDeviceIdentityLabels(secondary, historyEntries);
    const fuzzyOverlap = primaryLabels.some((left) => secondaryLabels.some((right) => labelsLookLikeSameMachine(left, right)));
    if (fuzzyOverlap && (
        !primary.serverId
        || !secondary.serverId
        || !primary.hostName
        || !secondary.hostName
    )) {
        return true;
    }

    const primaryName = normalizeDesktopName(primary.name);
    const secondaryName = normalizeDesktopName(secondary.name);
    if (!primaryName || primaryName !== secondaryName) {
        return false;
    }

    return Boolean((primary.serverId && !secondary.serverId) || (!primary.serverId && secondary.serverId));
}

function shouldForceMergeDeviceRecords(primary, secondary, historyEntries = readStoredHistoryEntries()) {
    if (!primary || !secondary) {
        return false;
    }

    const primaryHostTokens = getDeviceHostIdentityTokens(primary, historyEntries);
    const secondaryHostTokens = getDeviceHostIdentityTokens(secondary, historyEntries);
    const exactHostOverlap = primaryHostTokens.some((left) => secondaryHostTokens.includes(left));
    const primaryHasRealHost = primaryHostTokens.length > 0;
    const secondaryHasRealHost = secondaryHostTokens.length > 0;

    if (
        primary.serverId
        && secondary.serverId
        && primary.serverId !== secondary.serverId
        && primaryHasRealHost
        && secondaryHasRealHost
        && !exactHostOverlap
    ) {
        return false;
    }

    const primaryLabels = getDeviceIdentityLabels(primary, historyEntries);
    const secondaryLabels = getDeviceIdentityLabels(secondary, historyEntries);
    const fuzzyOverlap = primaryLabels.some((left) => secondaryLabels.some((right) => labelsLookLikeSameMachine(left, right)));
    if (!fuzzyOverlap) {
        return false;
    }

    if (exactHostOverlap) {
        return true;
    }

    return !primaryHasRealHost || !secondaryHasRealHost;
}

function normalizeDevices(devices = [], historyEntries = readStoredHistoryEntries()) {
    const merged = new Map();
    const order = [];

    devices
        .map(normalizeDeviceRecord)
        .map((device) => {
            if (!device.hostName) {
                const inferredHost = getKnownDeviceHostNames(device, historyEntries)[0] || '';
                if (inferredHost) {
                    device.hostName = inferredHost;
                }
            }
            return device;
        })
        .forEach((device) => {
            const key = getDeviceIdentityKey(device);
            if (!merged.has(key)) {
                merged.set(key, device);
                order.push(key);
                return;
            }
            merged.set(key, mergeDeviceRecords(merged.get(key), device));
        });

    const normalized = [];
    order.forEach((key) => {
        const device = merged.get(key);
        const mergeIndex = normalized.findIndex((existing) => (
            shouldMergeDeviceRecords(existing, device, historyEntries)
            || shouldForceMergeDeviceRecords(existing, device, historyEntries)
        ));
        if (mergeIndex === -1) {
            normalized.push(device);
            return;
        }
        normalized[mergeIndex] = mergeDeviceRecords(normalized[mergeIndex], device);
    });

    return normalized;
}

function findDeviceByAnyId(deviceId, devices = getDevices()) {
    if (!deviceId) {
        return null;
    }

    return devices.find((device) => (
        device.id === deviceId
        || (Array.isArray(device.legacyIds) && device.legacyIds.includes(deviceId))
    )) || null;
}

function newDeviceId() {
    return 'dev_' + Date.now();
}

function ensureConnection(deviceId) {
    if (!connections[deviceId]) {
        connections[deviceId] = {
            ws: null, authenticated: false, hostname: null,
            heartbeatTimer: null, timeoutTimer: null, reconnectTimer: null,
            heartbeatPending: false, heartbeatStartedAt: 0, lastMessageAt: 0,
            reconnectAttempts: 0,
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

function looksLikeIOSUserAgent() {
    const ua = navigator.userAgent || '';
    const platform = navigator.platform || '';
    return /iPhone|iPad|iPod/i.test(ua) || (platform === 'MacIntel' && Number(navigator.maxTouchPoints || 0) > 1);
}

function getNativeMobilePlatform() {
    if (!supportsNativeFileReceive()) {
        return 'web';
    }

    const ua = navigator.userAgent || '';
    if (
        window.NativeClipboard
        || window.NativeShare
        || window.NativeBackgroundClipboard
        || window.NativeMediaLibrary
        || window.NativeDevice
        || /Android/i.test(ua)
    ) {
        return 'android';
    }

    if (looksLikeIOSUserAgent()) {
        return 'ios';
    }

    return 'native';
}

function isIOSNativeApp() {
    return getNativeMobilePlatform() === 'ios';
}

function getNativeMobileDefaultLabel() {
    const platform = getNativeMobilePlatform();
    if (platform === 'android') {
        return 'Android 手机';
    }
    if (platform === 'ios') {
        return 'iPhone';
    }
    if (platform === 'native') {
        return '移动 App';
    }
    return '移动浏览器';
}

function buildClientDisplayName(clientId, nativeInfo = null) {
    const preferredName = nativeInfo?.friendlyName || nativeInfo?.marketName;
    if (preferredName) {
        return preferredName;
    }

    const suffix = String(clientId || '').slice(-4).toUpperCase();
    const label = getNativeMobileDefaultLabel();
    return suffix ? `${label} ${suffix}` : label;
}

function getNativeInvoker() {
    const tauriInvoke = window.__TAURI__?.core?.invoke;
    if (typeof tauriInvoke === 'function') {
        return tauriInvoke.bind(window.__TAURI__.core);
    }

    const internalInvoke = window.__TAURI_INTERNALS__?.invoke;
    if (typeof internalInvoke === 'function') {
        return internalInvoke;
    }

    return null;
}

function supportsNativeFileReceive() {
    return Boolean(getNativeInvoker());
}

function invokeNative(command, payload = {}) {
    const invoke = getNativeInvoker();
    if (!invoke) {
        return Promise.reject(new Error('当前环境不支持原生命令'));
    }
    return invoke(command, payload);
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
        void connectAll({ refreshDiscovery: true });
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
            hostName: card.dataset.hostName || '',
            serverId: card.dataset.serverId || '',
            legacyIds: parseDeviceLegacyIds(card.dataset.legacyIds),
            name: card.querySelector('.device-name-input').value.trim() || '未命名设备',
            ip: card.querySelector('.device-ip-input').value.trim(),
            port: card.querySelector('.device-port-input').value.trim() || '9001',
            pin: card.querySelector('.device-pin-input').value.trim(),
        });
    });
    saveDevices(devices);
    return getDevices();
}

function getDevicesFromUI() {
    const cards = document.querySelectorAll('#device-cards .settings-card');
    const devices = [];
    cards.forEach(card => {
        devices.push({
            id: card.dataset.deviceId,
            hostName: card.dataset.hostName || '',
            serverId: card.dataset.serverId || '',
            legacyIds: parseDeviceLegacyIds(card.dataset.legacyIds),
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
        card.dataset.hostName = dev.hostName || '';
        card.dataset.serverId = dev.serverId || '';
        card.dataset.legacyIds = JSON.stringify(Array.isArray(dev.legacyIds) ? dev.legacyIds : []);
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

function parseDeviceLegacyIds(rawValue) {
    if (!rawValue) {
        return [];
    }
    try {
        const parsed = JSON.parse(rawValue);
        return Array.isArray(parsed) ? parsed.map((item) => String(item || '').trim()).filter(Boolean) : [];
    } catch (error) {
        console.warn('解析 legacyIds 失败', error);
        return [];
    }
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

    initNearbyDesktopReorder();
    renderNearbyDesktops();
}

function hasSavedDevicesForSmartDiscovery() {
    return getDevices().some((device) => device && (device.ip || device.serverId || device.hostName));
}

function requestSmartDiscoveryRefresh(reason = 'endpoint-refresh', { force = false } = {}) {
    if (!supportsNearbyDesktopDiscovery() || !hasSavedDevicesForSmartDiscovery()) {
        return Promise.resolve([]);
    }

    if (smartDiscoveryRefreshState.inFlight) {
        return smartDiscoveryRefreshState.inFlight;
    }

    const now = Date.now();
    if (!force && now - smartDiscoveryRefreshState.lastStartedAt < SMART_DISCOVERY_REFRESH_COOLDOWN_MS) {
        return Promise.resolve(nearbyDesktopState.items);
    }

    smartDiscoveryRefreshState.lastStartedAt = now;
    console.log(`[smart-discovery] refreshing saved endpoints: ${reason}`);
    smartDiscoveryRefreshState.inFlight = discoverNearbyDesktops({
        silent: true,
        syncKnownDevices: true,
    })
        .catch((error) => {
            console.warn(`[smart-discovery] endpoint refresh failed: ${reason}`, error);
            return [];
        })
        .finally(() => {
            smartDiscoveryRefreshState.inFlight = null;
        });

    return smartDiscoveryRefreshState.inFlight;
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

    resetNearbyDesktopReorderState();

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
        const displayName = matched?.name || desktop.displayName || desktop.hostname || '未命名电脑';
        const metaLines = Array.from(new Set([
            matched?.hostName || desktop.resolvedHostname || desktop.hostname || '',
            desktop.ip ? `${desktop.ip}:${String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT)}` : '',
        ].filter(Boolean)));
        const isConnected = matched && connections[matched.id]?.authenticated;
        const badgeText = isConnected ? '已连接' : matched ? '已配对' : '附近电脑';
        const primaryLabel = isConnected ? '已连接' : matched ? '连接' : '请求配对';
        const primaryClass = matched ? 'secondary-btn' : 'primary-btn';
        const secondaryLabel = matched ? '查看参数' : '填入配置';
        const disabledAttr = nearbyDesktopState.pairing ? 'disabled' : '';
        const nearbyKey = getNearbyDesktopKey(desktop, devices);
        const sortableDeviceId = matched?.id || '';
        const sortableClass = sortableDeviceId ? ' is-sortable' : '';

        return `
            <div class="nearby-desktop-item${sortableClass}" data-nearby-key="${escapeHtml(nearbyKey)}"${sortableDeviceId ? ` data-sortable-device-id="${escapeHtml(sortableDeviceId)}"` : ''}>
                <div class="nearby-desktop-top">
                    <div>
                        <div class="nearby-desktop-name">${escapeHtml(displayName)}</div>
                        <div class="nearby-desktop-meta">${metaLines.map((line) => escapeHtml(line)).join('<br>')}</div>
                    </div>
                    <span class="nearby-desktop-badge${matched ? ' is-paired' : ''}">${escapeHtml(badgeText)}</span>
                </div>
                <div class="nearby-desktop-actions">
                    <button type="button" class="${primaryClass}" data-desktop-action="${matched ? 'connect' : 'pair'}" data-nearby-key="${escapeHtml(nearbyKey)}" ${disabledAttr}>${escapeHtml(primaryLabel)}</button>
                    <button type="button" class="secondary-btn" data-desktop-action="${matched ? 'advanced' : 'fill'}" data-nearby-key="${escapeHtml(nearbyKey)}" ${disabledAttr}>${escapeHtml(secondaryLabel)}</button>
                </div>
            </div>
        `;
    }).join('');

    list.querySelectorAll('[data-desktop-action]').forEach((button) => {
        button.addEventListener('click', async () => {
            const nearbyKey = button.dataset.nearbyKey;
            const action = button.dataset.desktopAction;
            const desktop = visibleItems.find((item) => getNearbyDesktopKey(item, devices) === nearbyKey);
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

function getDesktopIdentityKey(desktop, matchedDevice = null) {
    const serverId = desktop.server_id || matchedDevice?.serverId || '';
    if (serverId) {
        return `server:${serverId}`;
    }

    const ip = desktop.ip || matchedDevice?.ip || '';
    const port = String(desktop.port || matchedDevice?.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
    if (ip) {
        return `endpoint:${ip}:${port}`;
    }

    const deviceId = desktop.deviceId || matchedDevice?.id || '';
    if (deviceId) {
        return `device:${deviceId}`;
    }

    return `host:${desktop.resolvedHostname || desktop.hostname || matchedDevice?.hostName || matchedDevice?.name || 'unknown'}`;
}

function getNearbyDesktopKey(desktop, devices = getDevices()) {
    return getDesktopIdentityKey(desktop, resolveNearbyDesktopMatch(desktop, devices));
}

function resolveNearbyDesktopMatch(desktop, devices = getDevices()) {
    if (desktop.deviceId) {
        return findDeviceByAnyId(desktop.deviceId, devices);
    }
    return matchSavedDevice(desktop, devices);
}

function buildVisibleNearbyDesktops(devices = getDevices()) {
    const visible = [];
    const seen = new Set();
    const discoveredByKey = new Map();

    nearbyDesktopState.items.forEach((desktop) => {
        const matched = matchSavedDevice(desktop, devices);
        const item = {
            ...desktop,
            source: 'discovered',
            deviceId: matched?.id || desktop.deviceId || '',
            displayName: matched?.name || desktop.hostname || desktop.resolvedHostname || '未命名电脑',
        };
        const key = getDesktopIdentityKey(item, matched);
        if (!discoveredByKey.has(key)) {
            discoveredByKey.set(key, item);
        }
    });

    devices
        .filter((device) => device && (device.ip || device.serverId))
        .forEach((device) => {
            const savedItem = {
                server_id: device.serverId || '',
                hostname: device.hostName || device.name || '未命名电脑',
                resolvedHostname: device.hostName || '',
                ip: device.ip || '',
                port: Number(device.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
                source: 'saved',
                deviceId: device.id,
                displayName: device.name || device.hostName || '未命名电脑',
            };
            const key = getDesktopIdentityKey(savedItem, device);
            if (seen.has(key)) {
                return;
            }

            const discovered = discoveredByKey.get(key);
            const merged = discovered ? {
                ...savedItem,
                ...discovered,
                source: discovered.source || savedItem.source,
                deviceId: device.id,
                server_id: discovered.server_id || savedItem.server_id,
                hostname: discovered.hostname || savedItem.hostname,
                resolvedHostname: discovered.resolvedHostname || discovered.hostname || savedItem.resolvedHostname || '',
                ip: discovered.ip || savedItem.ip,
                port: Number(discovered.port || savedItem.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
                displayName: device.name || discovered.displayName || discovered.hostname || savedItem.displayName,
            } : savedItem;

            seen.add(key);
            visible.push(merged);
        });

    nearbyDesktopState.items.forEach((desktop) => {
        const matched = matchSavedDevice(desktop, devices);
        const item = {
            ...desktop,
            source: 'discovered',
            deviceId: matched?.id || desktop.deviceId || '',
            displayName: matched?.name || desktop.hostname || desktop.resolvedHostname || '未命名电脑',
        };
        const key = getDesktopIdentityKey(item, matched);
        if (seen.has(key)) {
            return;
        }
        seen.add(key);
        visible.push(item);
    });

    return visible;
}

function initNearbyDesktopReorder() {
    const list = $('nearby-desktops-list');
    if (!list || list.dataset.reorderBound === 'true') {
        return;
    }

    list.dataset.reorderBound = 'true';
    list.addEventListener('pointerdown', handleNearbyDesktopPointerDown);
    window.addEventListener('pointermove', handleNearbyDesktopPointerMove, { passive: false });
    window.addEventListener('pointerup', handleNearbyDesktopPointerEnd);
    window.addEventListener('pointercancel', handleNearbyDesktopPointerEnd);
}

function handleNearbyDesktopPointerDown(event) {
    if ((event.pointerType === 'mouse' && event.button !== 0) || nearbyDesktopReorderState.active || nearbyDesktopReorderState.longPressTimer) {
        return;
    }

    const item = event.target.closest('.nearby-desktop-item[data-sortable-device-id]');
    if (!item || event.target.closest('button')) {
        return;
    }

    nearbyDesktopReorderState.pointerId = event.pointerId;
    nearbyDesktopReorderState.list = item.parentElement;
    nearbyDesktopReorderState.item = item;
    nearbyDesktopReorderState.deviceId = item.dataset.sortableDeviceId || '';
    nearbyDesktopReorderState.originX = event.clientX;
    nearbyDesktopReorderState.originY = event.clientY;
    nearbyDesktopReorderState.longPressTimer = window.setTimeout(() => {
        activateNearbyDesktopReorder(event);
    }, NEARBY_REORDER_LONG_PRESS_MS);
    item.classList.add('is-pressing');
}

function handleNearbyDesktopPointerMove(event) {
    if (!nearbyDesktopReorderState.pointerId || event.pointerId !== nearbyDesktopReorderState.pointerId) {
        return;
    }

    if (!nearbyDesktopReorderState.active) {
        const dx = event.clientX - nearbyDesktopReorderState.originX;
        const dy = event.clientY - nearbyDesktopReorderState.originY;
        if (Math.hypot(dx, dy) > NEARBY_REORDER_CANCEL_DISTANCE) {
            resetNearbyDesktopReorderState();
        }
        return;
    }

    event.preventDefault();
    updateNearbyDesktopDraggedItemPosition(event.clientX, event.clientY);
    moveNearbyDesktopPlaceholder(event.clientY);
}

function handleNearbyDesktopPointerEnd(event) {
    if (!nearbyDesktopReorderState.pointerId || event.pointerId !== nearbyDesktopReorderState.pointerId) {
        return;
    }

    if (!nearbyDesktopReorderState.active) {
        resetNearbyDesktopReorderState();
        return;
    }

    finishNearbyDesktopReorder();
}

function activateNearbyDesktopReorder(event) {
    const item = nearbyDesktopReorderState.item;
    const list = nearbyDesktopReorderState.list;
    if (!item || !list) {
        resetNearbyDesktopReorderState();
        return;
    }

    const rect = item.getBoundingClientRect();
    const placeholder = document.createElement('div');
    placeholder.className = 'nearby-desktop-placeholder';
    placeholder.style.height = `${rect.height}px`;
    placeholder.dataset.sortablePlaceholder = 'true';
    item.after(placeholder);

    nearbyDesktopReorderState.placeholder = placeholder;
    nearbyDesktopReorderState.active = true;
    nearbyDesktopReorderState.originLeft = rect.left;
    nearbyDesktopReorderState.originTop = rect.top;
    nearbyDesktopReorderState.offsetX = event.clientX - rect.left;
    nearbyDesktopReorderState.offsetY = event.clientY - rect.top;
    nearbyDesktopReorderState.itemWidth = rect.width;
    nearbyDesktopReorderState.itemHeight = rect.height;

    item.classList.remove('is-pressing');
    item.classList.add('is-dragging');
    item.style.width = `${rect.width}px`;
    item.style.height = `${rect.height}px`;
    item.style.left = `${rect.left}px`;
    item.style.top = `${rect.top}px`;

    list.classList.add('is-reordering');
    document.body.classList.add('nearby-reorder-active');
    updateNearbyDesktopDraggedItemPosition(event.clientX, event.clientY);

    if (!localStorage.getItem(NEARBY_REORDER_HINT_KEY)) {
        localStorage.setItem(NEARBY_REORDER_HINT_KEY, '1');
        showToast('长按后拖动卡片，可以调整设备顺序');
    }
    if (navigator.vibrate) {
        navigator.vibrate(12);
    }
}

function updateNearbyDesktopDraggedItemPosition(clientX, clientY) {
    const item = nearbyDesktopReorderState.item;
    if (!item) {
        return;
    }

    const x = clientX - nearbyDesktopReorderState.offsetX;
    const y = clientY - nearbyDesktopReorderState.offsetY;
    item.style.transform = `translate3d(${x - nearbyDesktopReorderState.originLeft}px, ${y - nearbyDesktopReorderState.originTop}px, 0) scale(1.02)`;
}

function moveNearbyDesktopPlaceholder(clientY) {
    const list = nearbyDesktopReorderState.list;
    const placeholder = nearbyDesktopReorderState.placeholder;
    const item = nearbyDesktopReorderState.item;
    if (!list || !placeholder || !item) {
        return;
    }

    const sortableItems = Array.from(list.querySelectorAll('.nearby-desktop-item[data-sortable-device-id]'))
        .filter((element) => element !== item);
    let target = null;
    for (const sibling of sortableItems) {
        const rect = sibling.getBoundingClientRect();
        if (clientY < rect.top + rect.height / 2) {
            target = sibling;
            break;
        }
    }

    const fallbackTarget = Array.from(list.children).find((child) => (
        child !== item
        && child !== placeholder
        && !child.matches('.nearby-desktop-item[data-sortable-device-id]')
    )) || null;
    const desiredParent = list;
    const desiredNextSibling = target || fallbackTarget;
    if (placeholder.parentElement === desiredParent && placeholder.nextSibling === desiredNextSibling) {
        return;
    }

    animateNearbyDesktopListMutation(list, () => {
        if (desiredNextSibling) {
            desiredParent.insertBefore(placeholder, desiredNextSibling);
        } else {
            desiredParent.appendChild(placeholder);
        }
    });
}

function animateNearbyDesktopListMutation(list, mutate) {
    const trackedBefore = Array.from(list.children).filter((child) => child !== nearbyDesktopReorderState.item);
    const firstRects = new Map(trackedBefore.map((child) => [child, child.getBoundingClientRect()]));

    mutate();

    const trackedAfter = Array.from(list.children).filter((child) => child !== nearbyDesktopReorderState.item);
    trackedAfter.forEach((child) => {
        const firstRect = firstRects.get(child);
        if (!firstRect) {
            return;
        }
        const lastRect = child.getBoundingClientRect();
        const deltaX = firstRect.left - lastRect.left;
        const deltaY = firstRect.top - lastRect.top;
        if (Math.abs(deltaX) < 1 && Math.abs(deltaY) < 1) {
            return;
        }
        child.style.transition = 'none';
        child.style.transform = `translate(${deltaX}px, ${deltaY}px)`;
        child.offsetHeight;
        child.style.transition = '';
        child.style.transform = '';
    });
}

function finishNearbyDesktopReorder() {
    const list = nearbyDesktopReorderState.list;
    const placeholder = nearbyDesktopReorderState.placeholder;
    const movedDeviceId = nearbyDesktopReorderState.deviceId;
    if (!list || !placeholder || !movedDeviceId) {
        resetNearbyDesktopReorderState();
        return;
    }

    const orderedIds = [];
    Array.from(list.children).forEach((child) => {
        if (child === placeholder) {
            orderedIds.push(movedDeviceId);
            return;
        }
        if (child.matches('.nearby-desktop-item[data-sortable-device-id]')) {
            orderedIds.push(child.dataset.sortableDeviceId);
        }
    });

    const currentDevices = document.querySelectorAll('#device-cards .settings-card').length ? getDevicesFromUI() : getDevices();
    const originalOrder = currentDevices.map((device) => device.id);
    const reorderedDevices = [];
    const used = new Set();

    orderedIds.forEach((deviceId) => {
        const match = findDeviceByAnyId(deviceId, currentDevices);
        if (match && !used.has(match.id)) {
            reorderedDevices.push(match);
            used.add(match.id);
        }
    });

    currentDevices.forEach((device) => {
        if (!used.has(device.id)) {
            reorderedDevices.push(device);
            used.add(device.id);
        }
    });

    resetNearbyDesktopReorderState();

    if (JSON.stringify(originalOrder) === JSON.stringify(reorderedDevices.map((device) => device.id))) {
        return;
    }

    saveDevices(reorderedDevices);
    rerenderAllDeviceViews(getDevices());
    showToast('设备顺序已更新');
}

function resetNearbyDesktopReorderState() {
    if (nearbyDesktopReorderState.longPressTimer) {
        clearTimeout(nearbyDesktopReorderState.longPressTimer);
    }

    if (nearbyDesktopReorderState.item) {
        nearbyDesktopReorderState.item.classList.remove('is-pressing', 'is-dragging');
        nearbyDesktopReorderState.item.style.width = '';
        nearbyDesktopReorderState.item.style.height = '';
        nearbyDesktopReorderState.item.style.left = '';
        nearbyDesktopReorderState.item.style.top = '';
        nearbyDesktopReorderState.item.style.transform = '';
    }

    nearbyDesktopReorderState.placeholder?.remove();
    nearbyDesktopReorderState.list?.classList.remove('is-reordering');
    document.body.classList.remove('nearby-reorder-active');

    nearbyDesktopReorderState = {
        pointerId: null,
        list: null,
        item: null,
        placeholder: null,
        deviceId: '',
        originX: 0,
        originY: 0,
        originLeft: 0,
        originTop: 0,
        offsetX: 0,
        offsetY: 0,
        itemWidth: 0,
        itemHeight: 0,
        longPressTimer: null,
        active: false,
    };
}

function matchSavedDevice(desktop, devices = getDevices()) {
    return getSavedDeviceMatches(desktop, devices)[0] || null;
}

function getDeviceDesktopMatchScore(device, desktop) {
    if (!device || !desktop) {
        return 0;
    }

    if (device.serverId && desktop.server_id) {
        return device.serverId === desktop.server_id ? 300 : -1;
    }

    if (device.ip && desktop.ip && device.ip === desktop.ip && String(device.port || DESKTOP_DISCOVERY_DEFAULT_PORT) === String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT)) {
        return 200;
    }

    const knownHostNames = getKnownDeviceHostNames(device);
    if (knownHostNames.includes(normalizeDesktopName(desktop.resolvedHostname || desktop.hostname))) {
        return (device.serverId || desktop.server_id) ? 120 : 100;
    }

    const desktopLabels = [
        desktop.resolvedHostname,
        desktop.hostname,
        desktop.ip,
    ].filter(Boolean);
    const weakLabelMatch = getDeviceIdentityLabels(device).some((candidate) => (
        desktopLabels.some((desktopLabel) => labelsLookLikeSameMachine(candidate, desktopLabel))
    ));
    if (weakLabelMatch) {
        return (device.serverId || desktop.server_id || device.hostName) ? 80 : 60;
    }

    if (
        device.serverId
        && desktop.server_id
        && device.serverId !== desktop.server_id
    ) {
        return -1;
    }

    return 0;
}

function getSavedDeviceMatches(desktop, devices = getDevices()) {
    return devices
        .map((device) => ({ device, score: getDeviceDesktopMatchScore(device, desktop) }))
        .filter((entry) => entry.score > 0)
        .sort((a, b) => b.score - a.score)
        .map((entry) => entry.device);
}

function deviceMatchesDesktop(device, desktop) {
    return getDeviceDesktopMatchScore(device, desktop) > 0;
}

function syncKnownDevicesWithDiscovery(discovered) {
    const devices = getDevices();
    if (!devices.length) return false;

    let changed = false;
    discovered.forEach((desktop) => {
        const matches = getSavedDeviceMatches(desktop, devices);
        if (!matches.length) return;

        matches.forEach((matched) => {
            const nextPort = String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
            const nextServerId = desktop.server_id || matched.serverId || '';
            const nextHostName = desktop.resolvedHostname || desktop.hostname || matched.hostName || '';
            if (
                matched.ip !== desktop.ip
                || String(matched.port || DESKTOP_DISCOVERY_DEFAULT_PORT) !== nextPort
                || matched.hostName !== nextHostName
                || matched.serverId !== nextServerId
            ) {
                const previousEndpoint = `${matched.ip || ''}:${String(matched.port || DESKTOP_DISCOVERY_DEFAULT_PORT)}`;
                matched.ip = desktop.ip;
                matched.port = nextPort;
                matched.hostName = nextHostName;
                matched.serverId = nextServerId;
                changed = true;
                console.log(
                    `[smart-discovery] matched ${matched.name || matched.hostName || matched.id}: ${previousEndpoint} -> ${matched.ip}:${matched.port}`
                );
                if (connections[matched.id]) {
                    connectDevice(matched.id, matched.ip, matched.port, matched.pin || '');
                }
            }
        });
    });

    if (!changed) return false;

    saveDevices(devices);
    rerenderAllDeviceViews();
    return true;
}

function fillDesktopIntoSettings(desktop) {
    const devices = getDevicesFromUI();
    const matched = matchSavedDevice(desktop, devices);
    if (matched) {
        matched.hostName = desktop.resolvedHostname || desktop.hostname || matched.hostName || '';
        matched.ip = desktop.ip;
        matched.port = String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
        matched.serverId = desktop.server_id || matched.serverId || '';
    } else {
        devices.unshift({
            id: newDeviceId(),
            name: desktop.hostname,
            hostName: desktop.resolvedHostname || desktop.hostname || '',
            ip: desktop.ip,
            port: String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            pin: '',
            serverId: desktop.server_id || '',
        });
    }
    renderDeviceCards(normalizeDevices(devices));
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

    matched.hostName = desktop.resolvedHostname || desktop.hostname || matched.hostName || '';
    matched.ip = desktop.ip;
    matched.port = String(desktop.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
    matched.serverId = desktop.server_id || matched.serverId || '';
    saveDevices(devices);
    const normalized = rerenderAllDeviceViews();
    const normalizedMatched = findDeviceByAnyId(matched.id, normalized) || matched;
    connectDevice(normalizedMatched.id, normalizedMatched.ip, normalizedMatched.port, normalizedMatched.pin || '');
    showToast(`正在连接 ${normalizedMatched.name}`);
}

function reconcileAuthenticatedDesktopIdentity(deviceId, payload = {}) {
    const devices = getDevices();
    const matched = findDeviceByAnyId(deviceId, devices);
    if (!matched) {
        return;
    }

    const nextHostName = String(payload.hostname || '').trim();
    const nextServerId = String(payload.server_id || payload.serverId || '').trim();
    let changed = false;

    if (nextHostName && matched.hostName !== nextHostName) {
        matched.hostName = nextHostName;
        changed = true;
    }

    if (nextServerId && matched.serverId !== nextServerId) {
        matched.serverId = nextServerId;
        changed = true;
    }

    if (!changed) {
        return;
    }

    saveDevices(devices);
    rerenderAllDeviceViews();
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
                ip: pairing.ip || status.ip,
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
            hostName: pairing.hostname,
            ip: pairing.ip,
            port: String(pairing.port || DESKTOP_DISCOVERY_DEFAULT_PORT),
            pin: pairing.pin || '',
            serverId: pairing.serverId || '',
        };
        devices.unshift(matched);
    } else {
        matched.hostName = pairing.hostname || matched.hostName || '';
        matched.ip = pairing.ip;
        matched.port = String(pairing.port || DESKTOP_DISCOVERY_DEFAULT_PORT);
        matched.pin = pairing.pin || matched.pin || '';
        matched.serverId = pairing.serverId || matched.serverId || '';
    }

    saveDevices(devices);
    const normalized = rerenderAllDeviceViews();
    const normalizedMatched = findDeviceByAnyId(matched.id, normalized) || matched;
    connectDevice(normalizedMatched.id, normalizedMatched.ip, normalizedMatched.port, normalizedMatched.pin || '');
    void discoverNearbyDesktops({ silent: true });
}

// ============================================
// 动态渲染 — 发送页
// ============================================

function setSendDraft(deviceId, value) {
    const text = String(value || '');
    if (text) {
        sendDrafts.set(deviceId, text);
    } else {
        sendDrafts.delete(deviceId);
    }
}

function getSendDraft(deviceId) {
    return sendDrafts.get(deviceId) || '';
}

function captureSendDraftsFromDom() {
    sendDraftFocusState = null;
    document.querySelectorAll('#send-cards textarea[id^="input-"]').forEach((input) => {
        const deviceId = input.id.replace(/^input-/, '');
        if (deviceId) {
            setSendDraft(deviceId, input.value);
        }
    });

    const active = document.activeElement;
    if (active?.matches?.('#send-cards textarea[id^="input-"]')) {
        sendDraftFocusState = {
            deviceId: active.id.replace(/^input-/, ''),
            selectionStart: active.selectionStart,
            selectionEnd: active.selectionEnd,
        };
    }
}

function pruneSendDrafts(devices = []) {
    const activeIds = new Set(devices.map((device) => device.id).filter(Boolean));
    Array.from(sendDrafts.keys()).forEach((deviceId) => {
        if (!activeIds.has(deviceId)) {
            sendDrafts.delete(deviceId);
        }
    });
}

function restoreSendDraftFocus() {
    if (!sendDraftFocusState?.deviceId) {
        return;
    }

    const input = $(`input-${sendDraftFocusState.deviceId}`);
    if (!input) {
        sendDraftFocusState = null;
        return;
    }

    const start = Math.min(sendDraftFocusState.selectionStart ?? input.value.length, input.value.length);
    const end = Math.min(sendDraftFocusState.selectionEnd ?? start, input.value.length);
    requestAnimationFrame(() => {
        input.focus();
        if (typeof input.setSelectionRange === 'function') {
            input.setSelectionRange(start, end);
        }
    });
}

function renderSendCards(devices) {
    const container = $('send-cards');
    captureSendDraftsFromDom();
    pruneSendDrafts(devices);
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
                    <button class="send-btn image-btn" id="filebtn-${dev.id}" disabled>传到收件箱</button>
                </div>
                <input type="file" id="imageinput-${dev.id}" accept="image/*" class="hidden-file-input">
                <input type="file" id="fileinput-${dev.id}" class="hidden-file-input" multiple>
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
        input.value = getSendDraft(dev.id);
        input.addEventListener('input', () => setSendDraft(dev.id, input.value));

        sendBtn.addEventListener('click', () => sendText(dev.id));
        enterBtn.addEventListener('click', () => sendEnter(dev.id));
        sendEnterBtn.addEventListener('click', () => sendTextAndEnter(dev.id));
        imageBtn.addEventListener('click', () => {
            const primaryShared = getPrimaryPendingSharedContent();
            if (pendingSharedContents.length) {
                if (pendingSharedContents.length > 1) {
                    showToast('批量内容请使用“传到收件箱”');
                    return;
                }
                if (!primaryShared?.isImage) {
                    showToast('当前共享内容不是图片，请使用“传到收件箱”');
                    return;
                }
                sendPendingSharedImage(dev.id);
                return;
            }
            imageInput.click();
        });
        fileBtn.addEventListener('click', () => {
            if (pendingSharedContents.length) {
                if (pendingSharedContents.length > 1) {
                    sendPendingSharedFilesBatch(dev.id);
                } else {
                    sendPendingSharedFile(dev.id);
                }
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
            const files = Array.from(event.target.files || []);
            event.target.value = '';
            if (files.length > 1) {
                await sendSelectedFilesBatch(dev.id, files);
            } else if (files[0]) {
                const [file] = files;
                await sendSelectedFile(dev.id, file);
            }
        });
    });
    restoreSendDraftFocus();
}

// ============================================
// 动态渲染 — 历史筛选按钮
// ============================================

function renderHistoryFilters(devices) {
    const container = $('history-filter-btns');
    if (!container) return;
    container.innerHTML = '<button class="filter-btn" data-filter="all">全部</button>';

    const options = getHistoryDeviceOptions(getHistory(), devices || getDevices());
    const validFilters = new Set(['all', ...options.map((option) => option.value)]);
    if (!validFilters.has(currentHistoryFilters.device)) {
        currentHistoryFilters.device = 'all';
    }

    options.forEach((option) => {
        const btn = document.createElement('button');
        btn.className = 'filter-btn';
        btn.dataset.filter = option.value;
        btn.textContent = option.label;
        container.appendChild(btn);
    });

    // 绑定事件
    container.querySelectorAll('.filter-btn').forEach(btn => {
        btn.addEventListener('click', () => {
            currentHistoryFilters.device = btn.dataset.filter;
            clearHistoryHeatmapSelection();
            renderDeviceFilterState();
            renderHistoryDateInputs();
            scheduleHistoryRender();
        });
    });

    renderDeviceFilterState();
    renderHistoryDateInputs();
}

function getHistoryDeviceOptions(history = getHistory(), devices = getDevices()) {
    const options = new Map();

    devices
        .filter((device) => device && (device.ip || device.serverId))
        .forEach((device) => {
            options.set(device.id, {
                value: device.id,
                label: device.name || device.ip || '未命名电脑',
            });
        });

    history.forEach((entry) => {
        const hydrated = entry;
        const value = hydrated.target;
        if (!value || options.has(value)) {
            return;
        }

        options.set(value, {
            value,
            label: getHistoryPrimaryTargetLabel(hydrated),
        });
    });

    return Array.from(options.values());
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
        renderMediaOpenerSettings();
        showView('settings-view');
        document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));
        requestAnimationFrame(() => {
            void discoverNearbyDesktops({ silent: true, syncKnownDevices: true });
        });
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
        rerenderAllDeviceViews(devices);
        showView('send-view');
        $('nav-send-btn').classList.add('active');
        connectAll();
    });
}

function initMediaOpenerSettings() {
    const card = $('media-opener-settings-card');
    const modal = $('media-opener-picker-modal');
    const backdrop = $('media-opener-picker-backdrop');
    const closeBtn = $('media-opener-picker-close-btn');
    const rememberToggle = $('media-opener-picker-remember-toggle');

    if (!card || !modal || !backdrop || !closeBtn || !rememberToggle) {
        return;
    }

    renderMediaOpenerSettings();

    $('media-opener-image-select-btn')?.addEventListener('click', () => {
        showMediaOpenerPicker({ kind: 'image', mode: 'configure', item: null });
    });
    $('media-opener-video-select-btn')?.addEventListener('click', () => {
        showMediaOpenerPicker({ kind: 'video', mode: 'configure', item: null });
    });
    $('media-opener-image-clear-btn')?.addEventListener('click', () => {
        clearPreferredMediaOpener('image');
        showToast('图片默认打开应用已清除');
    });
    $('media-opener-video-clear-btn')?.addEventListener('click', () => {
        clearPreferredMediaOpener('video');
        showToast('视频默认打开应用已清除');
    });

    rememberToggle.addEventListener('change', () => {
        mediaOpenerPickerState.remember = Boolean(rememberToggle.checked);
    });

    const close = () => closeMediaOpenerPicker();
    backdrop.addEventListener('click', close);
    closeBtn.addEventListener('click', close);
}

function renderMediaOpenerSettings() {
    const card = $('media-opener-settings-card');
    if (!card) {
        return;
    }

    if (!supportsMediaOpenerPreferences()) {
        card.classList.add('hidden');
        return;
    }

    card.classList.remove('hidden');
    const openers = getPreferredMediaOpeners();
    const specs = [
        { kind: 'image', labelEl: $('media-opener-image-value'), selectEl: $('media-opener-image-select-btn'), clearEl: $('media-opener-image-clear-btn') },
        { kind: 'video', labelEl: $('media-opener-video-value'), selectEl: $('media-opener-video-select-btn'), clearEl: $('media-opener-video-clear-btn') },
    ];

    specs.forEach(({ kind, labelEl, selectEl, clearEl }) => {
        const pref = openers[kind];
        if (labelEl) {
            labelEl.textContent = pref?.label || '每次询问';
        }
        if (selectEl) {
            selectEl.textContent = pref ? '更改' : '选择';
        }
        if (clearEl) {
            clearEl.classList.toggle('hidden', !pref);
        }
    });
}

function inferMediaOpenerKind(item) {
    const mimeType = String(item?.mimeType || '').toLowerCase();
    if (item?.kind === 'video' || mimeType.startsWith('video/')) {
        return 'video';
    }
    if (item?.kind === 'image' || mimeType.startsWith('image/')) {
        return 'image';
    }
    return '';
}

function getGenericMimeTypeForKind(kind) {
    if (kind === 'video') {
        return 'video/*';
    }
    if (kind === 'image') {
        return 'image/*';
    }
    return '*/*';
}

function listMediaOpenersForKind(kind, item = null) {
    if (!supportsMediaOpenerPreferences()) {
        return [];
    }

    try {
        const openPath = item?.savedPath || item?.filePath || '';
        const mimeType = item?.mimeType || getGenericMimeTypeForKind(kind);
        const raw = window.NativeMediaLibrary.listOpeners(openPath, mimeType);
        const parsed = JSON.parse(raw || '[]');
        return Array.isArray(parsed)
            ? parsed
                .map((entry) => ({
                    packageName: String(entry?.packageName || '').trim(),
                    label: String(entry?.label || entry?.packageName || '').trim(),
                }))
                .filter((entry) => entry.packageName)
            : [];
    } catch (error) {
        console.warn('读取打开应用列表失败', error);
        return [];
    }
}

function showMediaOpenerPicker({ kind, mode = 'open', item = null } = {}) {
    const modal = $('media-opener-picker-modal');
    const title = $('media-opener-picker-title');
    const subtitle = $('media-opener-picker-subtitle');
    const list = $('media-opener-picker-list');
    const empty = $('media-opener-picker-empty');
    const rememberWrap = $('media-opener-picker-remember');
    const rememberLabel = $('media-opener-picker-remember-label');
    const rememberToggle = $('media-opener-picker-remember-toggle');

    if (!modal || !title || !subtitle || !list || !empty || !rememberWrap || !rememberLabel || !rememberToggle) {
        return;
    }

    const apps = listMediaOpenersForKind(kind, item);
    mediaOpenerPickerState = {
        kind,
        item,
        mode,
        apps,
        remember: false,
    };

    title.textContent = mode === 'configure'
        ? `选择${kind === 'video' ? '视频' : '图片'}默认打开应用`
        : `选择${kind === 'video' ? '视频' : '图片'}打开应用`;
    subtitle.textContent = mode === 'configure'
        ? '这里只影响 VibeDrop 内部，不会修改系统全局默认。'
        : '点一个应用立即打开；勾选后会记住这次选择。';
    rememberWrap.classList.toggle('hidden', mode !== 'open');
    rememberLabel.textContent = `记住这次选择，以后默认用它打开${kind === 'video' ? '视频' : '图片'}`;
    rememberToggle.checked = false;

    if (!apps.length) {
        list.innerHTML = '';
        empty.classList.remove('hidden');
    } else {
        empty.classList.add('hidden');
        list.innerHTML = apps.map((app, index) => `
            <button class="media-opener-app-btn" data-media-opener-index="${index}" type="button">
                <span class="media-opener-app-copy">
                    <span class="media-opener-app-label">${escapeHtml(app.label || app.packageName)}</span>
                    <span class="media-opener-app-package">${escapeHtml(app.packageName)}</span>
                </span>
            </button>
        `).join('');

        list.querySelectorAll('[data-media-opener-index]').forEach((node) => {
            node.addEventListener('click', async () => {
                const index = Number(node.dataset.mediaOpenerIndex || '0');
                const app = mediaOpenerPickerState.apps[index];
                if (!app) {
                    return;
                }

                if (mediaOpenerPickerState.mode === 'configure') {
                    setPreferredMediaOpener(kind, app);
                    showToast(`已将${app.label || app.packageName}设为默认打开应用`);
                    closeMediaOpenerPicker();
                    return;
                }

                if (mediaOpenerPickerState.remember) {
                    setPreferredMediaOpener(kind, app);
                }

                closeMediaOpenerPicker();
                await openHistoryMediaExternallyWithPackage(mediaOpenerPickerState.item, app, { retryOnFailure: false });
            });
        });
    }

    modal.classList.remove('hidden');
}

function closeMediaOpenerPicker() {
    const modal = $('media-opener-picker-modal');
    const list = $('media-opener-picker-list');
    const empty = $('media-opener-picker-empty');
    const rememberToggle = $('media-opener-picker-remember-toggle');
    if (!modal || !list || !empty || !rememberToggle) {
        return;
    }

    modal.classList.add('hidden');
    list.innerHTML = '';
    empty.classList.add('hidden');
    rememberToggle.checked = false;
    mediaOpenerPickerState = {
        kind: '',
        item: null,
        mode: 'open',
        apps: [],
        remember: false,
    };
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
        scheduleHistoryRender();
    }
}

function scheduleHistoryRender() {
    if (historyRenderScheduled) {
        return;
    }
    historyRenderScheduled = true;
    requestAnimationFrame(() => {
        historyRenderScheduled = false;
        if ($('history-view')?.classList.contains('hidden')) {
            return;
        }
        renderHistory();
    });
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
                scheduleHistoryRender();
            });
        });
    }

    $('history-search-input')?.addEventListener('input', (event) => {
        currentHistoryFilters.query = event.target.value || '';
        clearHistoryHeatmapSelection();
        renderHistorySearchState();
        scheduleHistoryRender();
    });
    $('history-search-clear-btn')?.addEventListener('click', () => {
        currentHistoryFilters.query = '';
        clearHistoryHeatmapSelection();
        syncHistoryFilterForm();
        scheduleHistoryRender();
        $('history-search-input')?.focus();
    });
    $('history-filter-close-btn')?.addEventListener('click', closeHistoryFilterSheet);
    $('history-filter-reset-btn')?.addEventListener('click', () => {
        currentHistoryFilters = {
            ...DEFAULT_HISTORY_FILTERS,
            device: currentHistoryFilters.device,
            query: currentHistoryFilters.query,
        };
        clearHistoryHeatmapSelection();
        syncHistoryFilterForm();
        renderDeviceFilterState();
        renderTimeFilterState();
        scheduleHistoryRender();
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
        query,
    } = currentHistoryFilters;

    if ($('history-start-date')) $('history-start-date').value = startDate;
    if ($('history-end-date')) $('history-end-date').value = endDate;
    if ($('history-time-range')) $('history-time-range').value = timeRange;
    if ($('history-start-time')) $('history-start-time').value = startTime;
    if ($('history-end-time')) $('history-end-time').value = endTime;
    if ($('history-kind-filter')) $('history-kind-filter').value = kind;
    if ($('history-status-filter')) $('history-status-filter').value = status;
    if ($('history-search-input')) $('history-search-input').value = query;
    syncCustomTimeInputsState();
    renderHistorySearchState();
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
    scheduleHistoryRender();
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

function renderHistorySearchState() {
    const hasQuery = Boolean(normalizeSearchText(currentHistoryFilters.query));
    $('history-search-clear-btn')?.classList.toggle('hidden', !hasQuery);
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
        const hydratedEntry = entry;
        if (filters.device !== 'all' && hydratedEntry.target !== filters.device) {
            return;
        }

        const entryDate = new Date(hydratedEntry.timestamp);
        if (Number.isNaN(entryDate.getTime())) {
            return;
        }

        if (!matchesTimeRange(entryDate, filters)) {
            return;
        }

        if (!matchesKind(entry, filters) || !matchesStatus(entry, filters)) {
            return;
        }

        if (!matchesHistorySearch(hydratedEntry, filters)) {
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
        currentHistoryFilters.device,
        currentHistoryFilters.quickTime,
        currentHistoryFilters.startDate,
        currentHistoryFilters.endDate,
        currentHistoryFilters.timeRange,
        currentHistoryFilters.startTime,
        currentHistoryFilters.endTime,
        currentHistoryFilters.kind,
        currentHistoryFilters.status,
        normalizeSearchText(currentHistoryFilters.query),
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

function getHistoryHeatmapDayKeys(minDate, maxDate) {
    const days = [];
    if (!minDate || !maxDate) {
        return days;
    }

    for (let dayKey = minDate; dayKey <= maxDate; dayKey = shiftDateKey(dayKey, 1)) {
        days.push(dayKey);
    }

    return days;
}

function getHistoryHeatmapMaxStartIndex(bounds) {
    return Math.max(0, diffDateKeys(bounds.minDate, bounds.maxDate) - (HEATMAP_VISIBLE_DAYS - 1));
}

function clampHistoryHeatmapStartIndex(startIndex, bounds) {
    return Math.max(0, Math.min(getHistoryHeatmapMaxStartIndex(bounds), startIndex));
}

function getHistoryHeatmapStartIndexForEndDate(bounds, endDate = bounds.preferredEnd) {
    const clampedEnd = clampDateKey(endDate || bounds.preferredEnd, bounds.minViewportEndDate, bounds.maxDate);
    const visibleStart = clampDateKey(
        shiftDateKey(clampedEnd, -(HEATMAP_VISIBLE_DAYS - 1)),
        bounds.minDate,
        bounds.maxDate
    );
    return clampHistoryHeatmapStartIndex(diffDateKeys(bounds.minDate, visibleStart), bounds);
}

function getHistoryHeatmapVisibleWindow(bounds, startIndex) {
    const safeStartIndex = clampHistoryHeatmapStartIndex(startIndex, bounds);
    const visibleStart = shiftDateKey(bounds.minDate, safeStartIndex);
    const visibleEnd = clampDateKey(
        shiftDateKey(visibleStart, HEATMAP_VISIBLE_DAYS - 1),
        bounds.minDate,
        bounds.maxDate
    );

    return {
        startIndex: safeStartIndex,
        visibleStart,
        visibleEnd,
    };
}

function getHistoryHeatmapColumnStep(track = $('history-heatmap-track')) {
    const first = track?.children?.[0];
    const second = track?.children?.[1];
    if (first && second) {
        return second.offsetLeft - first.offsetLeft;
    }

    const rootStyles = getComputedStyle(document.documentElement);
    const columnWidth = parseFloat(rootStyles.getPropertyValue('--heatmap-column-width')) || 44;
    const columnGap = parseFloat(rootStyles.getPropertyValue('--heatmap-column-gap')) || 8;
    return columnWidth + columnGap;
}

function setHistoryHeatmapScrollPosition(viewport, track, startIndex) {
    if (!viewport || !track) {
        return;
    }

    const step = getHistoryHeatmapColumnStep(track);
    const targetLeft = Math.max(0, startIndex) * step;
    const previousBehavior = viewport.style.scrollBehavior;
    viewport.style.scrollBehavior = 'auto';
    viewport.scrollLeft = targetLeft;
    viewport.style.scrollBehavior = previousBehavior;
}

function syncHistoryHeatmapViewportMeta(baseEntries, counts, bounds) {
    const viewport = $('history-heatmap-viewport');
    const stats = $('history-heatmap-stats');
    const caption = $('history-heatmap-caption');
    const resetBtn = $('history-heatmap-reset-btn');
    const track = $('history-heatmap-track');
    if (!viewport || !stats || !caption || !resetBtn || !track || !bounds) {
        return;
    }

    const step = getHistoryHeatmapColumnStep(track);
    const rawStartIndex = step > 0
        ? Math.round(viewport.scrollLeft / step)
        : getHistoryHeatmapStartIndexForEndDate(bounds, historyHeatmapState.viewportEndDate);
    const { startIndex, visibleStart, visibleEnd } = getHistoryHeatmapVisibleWindow(bounds, rawStartIndex);

    historyHeatmapState.viewportEndDate = visibleEnd;
    updateHistoryHeatmapCellColors(counts, visibleStart, visibleEnd);
    stats.innerHTML = buildHistoryHeatmapStats(baseEntries, visibleStart, visibleEnd).map((item) => `
        <div class="history-heatmap-stat">
            <span class="history-heatmap-stat-label">${escapeHtml(item.label)}</span>
            <span class="history-heatmap-stat-value">${escapeHtml(item.value)}</span>
        </div>
    `).join('');

    caption.textContent = `当前窗口 ${formatHistoryDateLabel(visibleStart)} 至 ${formatHistoryDateLabel(visibleEnd)}。左右滑动查看更多日期，点方块筛选该小时。`;
    resetBtn.classList.toggle('hidden', startIndex >= getHistoryHeatmapMaxStartIndex(bounds));
}

function scheduleHistoryHeatmapViewportMetaSync() {
    if (historyHeatmapState.scrollFrame) {
        return;
    }

    historyHeatmapState.scrollFrame = requestAnimationFrame(() => {
        historyHeatmapState.scrollFrame = 0;
        syncHistoryHeatmapViewportMeta(
            historyHeatmapState.renderedEntries,
            historyHeatmapState.renderedCounts,
            historyHeatmapState.renderedBounds
        );
    });
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

function getHistoryHeatmapWindowMaxCount(counts, visibleStart, visibleEnd) {
    let maxCount = 0;
    if (!visibleStart || !visibleEnd) {
        return maxCount;
    }

    for (let dayKey = visibleStart; dayKey <= visibleEnd; dayKey = shiftDateKey(dayKey, 1)) {
        for (let hour = 0; hour < HEATMAP_HOUR_SLOTS; hour += 1) {
            maxCount = Math.max(maxCount, getHistoryHeatmapCellCount(counts, dayKey, hour));
        }
    }

    return maxCount;
}

function mixHistoryHeatmapRgb(from, to, amount) {
    const t = Math.max(0, Math.min(1, amount));
    const channel = (index) => Math.round(from[index] + ((to[index] - from[index]) * t));
    return `rgb(${channel(0)}, ${channel(1)}, ${channel(2)})`;
}

function getHistoryHeatmapCellColor(count, maxCount) {
    if (count <= 0 || maxCount <= 0) {
        return 'rgb(255, 255, 255)';
    }

    const rawRatio = Math.min(1, count / maxCount);
    const ratio = Math.max(0.1, Math.pow(rawRatio, 0.78));
    const stops = [
        { at: 0, color: [255, 255, 255] },
        { at: 0.22, color: [220, 252, 231] },
        { at: 0.48, color: [74, 222, 128] },
        { at: 0.74, color: [22, 163, 74] },
        { at: 1, color: [6, 78, 59] },
    ];

    for (let index = 1; index < stops.length; index += 1) {
        const previous = stops[index - 1];
        const next = stops[index];
        if (ratio <= next.at) {
            return mixHistoryHeatmapRgb(
                previous.color,
                next.color,
                (ratio - previous.at) / (next.at - previous.at)
            );
        }
    }

    return mixHistoryHeatmapRgb(stops[stops.length - 1].color, stops[stops.length - 1].color, 1);
}

function updateHistoryHeatmapCellColors(counts, visibleStart, visibleEnd) {
    const track = $('history-heatmap-track');
    if (!track || !counts) {
        return;
    }

    const maxCount = getHistoryHeatmapWindowMaxCount(counts, visibleStart, visibleEnd);
    track.querySelectorAll('.history-heatmap-cell').forEach((cell) => {
        const dayKey = cell.dataset.dateKey;
        const hour = Number(cell.dataset.hour);
        const count = getHistoryHeatmapCellCount(counts, dayKey, hour);
        cell.style.background = getHistoryHeatmapCellColor(count, maxCount);
    });
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
    const viewport = $('history-heatmap-viewport');
    const track = $('history-heatmap-track');
    const stats = $('history-heatmap-stats');
    const caption = $('history-heatmap-caption');
    const resetBtn = $('history-heatmap-reset-btn');
    if (!viewport || !track || !stats || !caption || !resetBtn) return;

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

    const counts = buildHistoryHeatmapCountMap(baseEntries);
    const initialStartIndex = getHistoryHeatmapStartIndexForEndDate(bounds, historyHeatmapState.viewportEndDate);
    const initialWindow = getHistoryHeatmapVisibleWindow(bounds, initialStartIndex);
    const initialMaxCount = getHistoryHeatmapWindowMaxCount(counts, initialWindow.visibleStart, initialWindow.visibleEnd);
    const todayKey = formatDateKey(new Date());
    const days = getHistoryHeatmapDayKeys(bounds.minDate, bounds.maxDate);

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
            const style = `background:${getHistoryHeatmapCellColor(count, initialMaxCount)};`;
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
            <div class="history-heatmap-day ${isToday ? 'is-today' : ''}" data-day-key="${dayKey}">
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

    historyHeatmapState.renderedEntries = baseEntries;
    historyHeatmapState.renderedCounts = counts;
    historyHeatmapState.renderedBounds = bounds;

    setHistoryHeatmapScrollPosition(viewport, track, initialStartIndex);
    syncHistoryHeatmapViewportMeta(baseEntries, counts, bounds);

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

            scheduleHistoryRender();
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

    viewport.addEventListener('scroll', () => {
        historyHeatmapState.suppressCellClickUntil = Date.now() + 120;
        scheduleHistoryHeatmapViewportMetaSync();
    });

    resetBtn?.addEventListener('click', () => {
        const bounds = historyHeatmapState.renderedBounds || getHistoryHeatmapBounds(filterHistoryEntries(getHistory()));
        historyHeatmapState.viewportEndDate = bounds.maxDate;
        clearHistoryHeatmapSelection();
        scheduleHistoryRender();
    });
}

// ============================================
// WebSocket 连接
// ============================================

async function connectAll({ refreshDiscovery = false } = {}) {
    if (refreshDiscovery) {
        await requestSmartDiscoveryRefresh('connect-all', { force: true });
    }

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
        const previousWs = conn.ws;
        conn.ws = null;
        previousWs.close();
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
    conn.lastMessageAt = Date.now();
    conn.heartbeatPending = false;
    conn.heartbeatStartedAt = 0;

    ws.onopen = () => {
        if (conn.ws !== ws) return;
        conn.lastMessageAt = Date.now();
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
        if (conn.ws !== ws) return;
        conn.lastMessageAt = Date.now();
        conn.heartbeatPending = false;
        conn.heartbeatStartedAt = 0;
        clearTimeout(conn.timeoutTimer);

        let data;
        try { data = JSON.parse(event.data); } catch { return; }

        if (data.action === 'pong') {
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
            data.action === 'incoming_history_session_start'
            || data.action === 'incoming_file_start'
            || data.action === 'incoming_file_chunk'
            || data.action === 'incoming_file_complete'
        ) {
            queueIncomingDesktopTransfer(deviceId, data);
            return;
        }

        if (
            data.action === 'incoming_file_saved'
            || data.action === 'incoming_file_error'
        ) {
            if (settleOutboundDesktopFileTransfer(deviceId, data)) {
                return;
            }
        }

        if (!conn.authenticated) {
            if (data.status === 'ok' && data.hostname) {
                conn.authenticated = true;
                conn.hostname = data.hostname;
                conn.reconnectAttempts = 0;
                reconcileAuthenticatedDesktopIdentity(deviceId, data);
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
        if (conn.ws !== ws) return;
        updateDeviceUI(deviceId, 'error', '连接出错');
    };

    ws.onclose = () => {
        if (conn.ws !== ws) return;
        rejectOutboundDesktopTransfersForDevice(deviceId, '连接已断开');
        conn.ws = null;
        conn.authenticated = false;
        conn.hostname = null;
        updateDeviceUI(deviceId, 'disconnected', '已断开');
        clearTimers(conn);
        scheduleReconnect(deviceId, ip, port, pin);
    };
}

// ---- 心跳 ----

function startHeartbeat(deviceId, ip, port, pin) {
    const conn = connections[deviceId];
    clearTimers(conn);
    conn.lastMessageAt = Date.now();
    conn.heartbeatPending = false;
    conn.heartbeatStartedAt = 0;

    conn.heartbeatTimer = setInterval(() => {
        if (!conn.ws || conn.ws.readyState !== WebSocket.OPEN) {
            return;
        }

        const now = Date.now();
        if (conn.heartbeatPending) {
            const pendingFor = now - Number(conn.heartbeatStartedAt || 0);
            const quietFor = now - Number(conn.lastMessageAt || 0);
            if (pendingFor >= HEARTBEAT_TIMEOUT && quietFor >= HEARTBEAT_TIMEOUT) {
                console.warn(`[${deviceId}] 心跳超时，关闭 WebSocket 以触发重连`);
                conn.ws.close();
            }
            return;
        }

        try {
            conn.ws.send(JSON.stringify({ action: 'ping' }));
            conn.heartbeatPending = true;
            conn.heartbeatStartedAt = now;
        } catch (error) {
            console.warn(`[${deviceId}] 心跳发送失败，关闭 WebSocket 以触发重连`, error);
            if (conn.ws) {
                conn.ws.close();
            }
        }
    }, HEARTBEAT_INTERVAL);
}

function scheduleReconnect(deviceId, ip, port, pin) {
    const conn = connections[deviceId];
    if (!conn) return;
    clearTimeout(conn.reconnectTimer);
    void requestSmartDiscoveryRefresh(`reconnect:${deviceId}`);
    const attempt = Math.max(0, Number(conn.reconnectAttempts || 0));
    const delay = Math.min(RECONNECT_MAX_INTERVAL, RECONNECT_BASE_INTERVAL * (2 ** Math.min(attempt, 6)));
    conn.reconnectAttempts = attempt + 1;
    conn.reconnectTimer = setTimeout(() => {
        const latest = findDeviceByAnyId(deviceId, getDevices());
        connectDevice(
            deviceId,
            latest?.ip || ip,
            latest?.port || port,
            latest?.pin || pin
        );
    }, delay);
}

function clearTimers(conn) {
    clearInterval(conn.heartbeatTimer);
    clearTimeout(conn.timeoutTimer);
    clearTimeout(conn.reconnectTimer);
    conn.heartbeatTimer = null;
    conn.timeoutTimer = null;
    conn.reconnectTimer = null;
    conn.heartbeatPending = false;
    conn.heartbeatStartedAt = 0;
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
    if (!conn || !conn.ws || conn.ws.readyState !== WebSocket.OPEN) {
        return;
    }

    try {
        if (payload.action === 'incoming_history_session_start') {
            upsertDesktopIncomingHistorySession(deviceId, payload);
            return;
        }

        if (!transferId) {
            throw new Error('缺少传输标识');
        }

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
        if (transferId && incomingDesktopTransfers[transferId]) {
            markDesktopIncomingHistoryTransfer(incomingDesktopTransfers[transferId], {
                status: 'failed',
                error: message,
            });
        }
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

    const mimeType = payload.mime_type || 'application/octet-stream';
    const saveTarget = resolveIncomingDesktopSaveTarget({
        mimeType,
        isArchive: Boolean(payload.is_archive),
    });

    incomingDesktopTransfers[transferId] = {
        fileName: payload.file_name || 'file.bin',
        mimeType,
        isArchive: Boolean(payload.is_archive),
        saveTarget,
        chunks: [],
        historySessionId: payload.history_session_id || '',
        historyItemIndex: Number(payload.history_item_index || 0),
        historyItemCount: Number(payload.history_item_count || 1),
    };

    if (supportsNativeFileReceive()) {
        await invokeNative('begin_incoming_file', {
            transferId,
            fileName: payload.file_name || 'file.bin',
            mimeType,
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

    if (supportsNativeFileReceive()) {
        await invokeNative('append_incoming_file_chunk', {
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
        if (supportsNativeFileReceive()) {
            const savedPath = await invokeNative('finish_incoming_file', {
                transferId,
                saveTarget: transfer.saveTarget,
            });
            markDesktopIncomingHistoryTransfer(transfer, {
                status: 'success',
                savedPath,
            });
            if (transfer.saveTarget === 'gallery-image' || transfer.saveTarget === 'gallery-video') {
                try {
                    window.NativeMediaLibrary?.scanPath?.(savedPath, transfer.mimeType || '');
                } catch (scanError) {
                    console.warn('通知系统媒体库扫描失败', scanError);
                }
                showToast(`已保存到${getIncomingDesktopSavedLocationLabel(transfer.saveTarget)}：${transfer.fileName}`);
            } else {
                showToast(`已保存到${getIncomingDesktopSavedLocationLabel(transfer.saveTarget)}：${transfer.fileName}`);
            }
            return savedPath;
        }

        const blob = new Blob(transfer.chunks, { type: transfer.mimeType || 'application/octet-stream' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = transfer.fileName;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1200);
        markDesktopIncomingHistoryTransfer(transfer, {
            status: 'success',
            savedPath: transfer.fileName,
        });
        showToast(`已下载：${transfer.fileName}`);
        return transfer.fileName;
    } finally {
        delete incomingDesktopTransfers[transferId];
    }
}

function resolveIncomingDesktopSaveTarget({ mimeType = '', isArchive = false } = {}) {
    if (isIOSNativeApp()) {
        return 'download';
    }

    if (isArchive) {
        return 'download';
    }

    const normalizedMime = String(mimeType || '').toLowerCase();
    if (normalizedMime.startsWith('image/')) {
        return 'gallery-image';
    }
    if (normalizedMime.startsWith('video/')) {
        return 'gallery-video';
    }
    return 'download';
}

function normalizeHistoryItem(item = {}) {
    return {
        kind: item.kind || normalizeHistoryKind(item.mime_type || item.mimeType || ''),
        fileName: item.fileName || item.file_name || '文件',
        mimeType: item.mimeType || item.mime_type || 'application/octet-stream',
        thumbnailDataUrl: item.thumbnailDataUrl || item.thumbnail_data_url || '',
        filePath: item.filePath || item.file_path || '',
        savedPath: item.savedPath || item.saved_path || '',
        status: item.status || 'pending',
        error: item.error || '',
    };
}

function normalizeHistoryKind(mimeType = '') {
    const normalized = String(mimeType || '').toLowerCase();
    if (normalized.startsWith('image/')) {
        return 'image';
    }
    if (normalized.startsWith('video/')) {
        return 'video';
    }
    return 'file';
}

function buildHistoryItemsSummary(items = []) {
    const normalizedItems = items.map((item) => normalizeHistoryItem(item));
    const imageCount = normalizedItems.filter((item) => item.kind === 'image').length;
    const videoCount = normalizedItems.filter((item) => item.kind === 'video').length;

    if (normalizedItems.length === 1) {
        const item = normalizedItems[0];
        const prefix = item.kind === 'image' ? '图片' : item.kind === 'video' ? '视频' : '文件';
        return {
            kind: item.kind,
            text: `[${prefix}] ${item.fileName}`,
        };
    }

    if (imageCount === normalizedItems.length) {
        return { kind: 'image', text: `[图片] ${normalizedItems.length} 张` };
    }
    if (videoCount === normalizedItems.length) {
        return { kind: 'video', text: `[视频] ${normalizedItems.length} 个` };
    }
    if (imageCount + videoCount === normalizedItems.length) {
        return { kind: 'media', text: `[媒体] ${normalizedItems.length} 项` };
    }
    return { kind: 'file', text: `[文件] ${normalizedItems.length} 项` };
}

function upsertHistoryEntry(entry) {
    const history = getHistory();
    const idx = history.findIndex((item) => item.id === entry.id);
    if (idx === -1) {
        history.unshift(entry);
    } else {
        history[idx] = entry;
    }
    history.sort((a, b) => new Date(b.timestamp || 0) - new Date(a.timestamp || 0));
    setStoredHistoryRaw(JSON.stringify(history));
    persistHistory();
}

function upsertDesktopIncomingHistorySession(deviceId, payload) {
    const sessionId = payload.session_id || payload.sessionId;
    if (!sessionId) {
        return;
    }

    const historyId = `desktop-inbound:${sessionId}`;
    const history = getHistory();
    const existingRaw = history.find((entry) => entry.id === historyId);
    const existing = existingRaw ? hydrateHistoryEntry(existingRaw) : null;
    const items = Array.isArray(payload.items)
        ? payload.items.map((item) => normalizeHistoryItem(item))
        : (existing?.items || []).map((item) => normalizeHistoryItem(item));
    const summary = buildHistoryItemsSummary(items);
    const firstItem = items[0] || null;

    upsertHistoryEntry({
        id: historyId,
        sessionId,
        timestamp: payload.timestamp || existing?.timestamp || getLocalTimestamp(),
        text: payload.text || existing?.text || summary.text,
        status: existing?.status || 'pending',
        kind: payload.kind || existing?.kind || summary.kind,
        direction: 'desktop_to_mobile',
        itemCount: Number(payload.item_count || payload.itemCount || items.length || 1),
        saveTarget: payload.save_target || payload.saveTarget || existing?.saveTarget || 'download',
        items,
        fileName: firstItem?.fileName || existing?.fileName || '',
        mimeType: firstItem?.mimeType || existing?.mimeType || '',
        thumbnailDataUrl: firstItem?.thumbnailDataUrl || existing?.thumbnailDataUrl || '',
        ...buildHistoryTargetMeta(deviceId),
    });
}

function computeDesktopIncomingSessionStatus(items = []) {
    const normalizedItems = items.map((item) => normalizeHistoryItem(item));
    if (!normalizedItems.length) {
        return 'pending';
    }

    if (normalizedItems.some((item) => item.status === 'pending')) {
        return 'pending';
    }

    const successCount = normalizedItems.filter((item) => item.status === 'success').length;
    const failedCount = normalizedItems.filter((item) => item.status === 'failed').length;
    if (failedCount === 0) {
        return 'success';
    }
    if (successCount === 0) {
        return 'failed';
    }
    return 'partial';
}

function markDesktopIncomingHistoryTransfer(transfer, { status, savedPath = '', error = '' } = {}) {
    const sessionId = transfer?.historySessionId;
    if (!sessionId) {
        return;
    }

    const historyId = `desktop-inbound:${sessionId}`;
    const history = getHistory();
    const idx = history.findIndex((entry) => entry.id === historyId);
    if (idx === -1) {
        return;
    }

    const entry = hydrateHistoryEntry(history[idx]);
    const items = Array.isArray(entry.items) ? entry.items.map((item) => normalizeHistoryItem(item)) : [];
    const itemIndex = Math.max(0, Number(transfer.historyItemIndex || 0));
    if (!items[itemIndex]) {
        items[itemIndex] = normalizeHistoryItem({
            kind: normalizeHistoryKind(transfer.mimeType),
            fileName: transfer.fileName,
            mimeType: transfer.mimeType,
        });
    }

    items[itemIndex] = {
        ...items[itemIndex],
        status,
        savedPath: savedPath || items[itemIndex].savedPath,
        error: error || items[itemIndex].error,
    };

    const firstItem = items[0] || null;
    const updatedEntry = {
        ...entry,
        items,
        status: computeDesktopIncomingSessionStatus(items),
        fileName: firstItem?.fileName || entry.fileName || '',
        mimeType: firstItem?.mimeType || entry.mimeType || '',
        thumbnailDataUrl: firstItem?.thumbnailDataUrl || entry.thumbnailDataUrl || '',
    };
    upsertHistoryEntry(updatedEntry);
}

async function cancelIncomingDesktopFile(transferId) {
    if (!transferId) {
        return;
    }

    delete incomingDesktopTransfers[transferId];
    if (supportsNativeFileReceive()) {
        try {
            await invokeNative('cancel_incoming_file', {
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
        const result = await sendDesktopRequest(conn, { action, ...payload }, timeoutMs);

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
                setSendDraft(deviceId, '');
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
        setSendDraft(deviceId, '');
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

function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function buildOutboundTransferId(prefix = 'mobile-file') {
    return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

function uint8ArrayToBase64(bytes) {
    let binary = '';
    const chunkSize = 0x8000;
    for (let index = 0; index < bytes.length; index += chunkSize) {
        const slice = bytes.subarray(index, index + chunkSize);
        binary += String.fromCharCode(...slice);
    }
    return btoa(binary);
}

async function readBlobChunkAsBase64(blob, offsetBytes, lengthBytes) {
    const chunk = blob.slice(offsetBytes, offsetBytes + lengthBytes);
    const buffer = await chunk.arrayBuffer();
    return uint8ArrayToBase64(new Uint8Array(buffer));
}

function readPendingSharedContentChunkBase64At(itemIndex, offsetBytes, lengthBytes) {
    if (!window.NativeShare) {
        throw new Error('当前环境不支持分块读取共享文件');
    }

    const base64 = typeof window.NativeShare.readPendingSharedContentChunkBase64At === 'function'
        ? window.NativeShare.readPendingSharedContentChunkBase64At(itemIndex, offsetBytes, lengthBytes)
        : window.NativeShare.readPendingSharedContentChunkBase64?.(offsetBytes, lengthBytes);
    if (typeof base64 !== 'string') {
        throw new Error('共享文件分块读取失败');
    }
    return base64;
}

function readPendingSharedContentChunkBase64(offsetBytes, lengthBytes) {
    return readPendingSharedContentChunkBase64At(0, offsetBytes, lengthBytes);
}

function calculateFileTransferAckTimeout(sizeBytes) {
    const bySize = Math.ceil(Math.max(Number(sizeBytes) || 0, 1) / (8 * 1024 * 1024)) * 15000;
    return Math.max(FILE_TRANSFER_ACK_TIMEOUT_MS, bySize);
}

function registerOutboundDesktopFileTransfer(deviceId, transferId, timeoutMs) {
    const transfer = {
        deviceId,
        transferId,
        state: 'pending',
        errorMessage: '',
        payload: null,
        timeoutTimer: null,
        resolve: null,
        reject: null,
        promise: null,
    };

    transfer.promise = new Promise((resolve, reject) => {
        transfer.resolve = (payload) => {
            if (transfer.state !== 'pending') {
                return;
            }
            transfer.state = 'resolved';
            transfer.payload = payload;
            clearTimeout(transfer.timeoutTimer);
            resolve(payload);
        };
        transfer.reject = (message) => {
            if (transfer.state !== 'pending') {
                return;
            }
            transfer.state = 'rejected';
            transfer.errorMessage = message || '桌面端接收失败';
            clearTimeout(transfer.timeoutTimer);
            reject(new Error(transfer.errorMessage));
        };
    });

    transfer.timeoutTimer = setTimeout(() => {
        transfer.reject('等待桌面端完成确认超时');
    }, timeoutMs);

    outboundDesktopFileTransfers[transferId] = transfer;
    return transfer;
}

function clearOutboundDesktopFileTransfer(transferId) {
    const transfer = outboundDesktopFileTransfers[transferId];
    if (!transfer) {
        return;
    }
    clearTimeout(transfer.timeoutTimer);
    delete outboundDesktopFileTransfers[transferId];
}

function settleOutboundDesktopFileTransfer(deviceId, payload) {
    const transferId = payload?.transfer_id;
    const transfer = transferId ? outboundDesktopFileTransfers[transferId] : null;
    if (!transfer || transfer.deviceId !== deviceId) {
        return false;
    }

    if (payload.action === 'incoming_file_saved') {
        transfer.resolve(payload);
    } else {
        transfer.reject(payload.error || '桌面端接收失败');
    }
    return true;
}

function rejectOutboundDesktopTransfersForDevice(deviceId, message) {
    Object.keys(outboundDesktopFileTransfers).forEach((transferId) => {
        const transfer = outboundDesktopFileTransfers[transferId];
        if (transfer?.deviceId === deviceId) {
            transfer.reject(message || '连接已断开');
        }
    });
}

function sendWsJson(ws, payload) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        throw new Error('连接已断开');
    }
    ws.send(JSON.stringify(payload));
}

function sendDesktopRequest(conn, payload, timeoutMs = 5000) {
    if (!conn || !conn.ws || conn.ws.readyState !== WebSocket.OPEN) {
        return Promise.reject(new Error('未连接'));
    }
    if (conn._sendCallback) {
        return Promise.reject(new Error('当前有未完成操作'));
    }

    return new Promise((resolve, reject) => {
        let settled = false;
        let timer = null;
        const finish = (handler) => (value) => {
            if (settled) {
                return;
            }
            settled = true;
            clearTimeout(timer);
            if (conn._sendCallback === callback) {
                conn._sendCallback = null;
            }
            handler(value);
        };
        const callback = finish(resolve);
        timer = setTimeout(() => {
            finish(reject)(new Error('超时'));
        }, timeoutMs);

        conn._sendCallback = callback;
        try {
            sendWsJson(conn.ws, payload);
        } catch (error) {
            finish(reject)(error instanceof Error ? error : new Error(String(error || '发送失败')));
        }
    });
}

async function waitForFileTransferBufferDrain(conn, transferId) {
    while (conn?.ws && conn.ws.readyState === WebSocket.OPEN && conn.ws.bufferedAmount > FILE_TRANSFER_WS_BUFFER_HIGH_WATER_BYTES) {
        const transfer = outboundDesktopFileTransfers[transferId];
        if (transfer?.state === 'rejected') {
            throw new Error(transfer.errorMessage || '桌面端接收失败');
        }
        await sleep(FILE_TRANSFER_WS_BUFFER_POLL_MS);
    }

    if (!conn?.ws || conn.ws.readyState !== WebSocket.OPEN) {
        throw new Error('连接已断开');
    }

    const transfer = outboundDesktopFileTransfers[transferId];
    if (transfer?.state === 'rejected') {
        throw new Error(transfer.errorMessage || '桌面端接收失败');
    }
}

async function transferFileToDesktop(deviceId, {
    fileName,
    mimeType,
    sizeBytes,
    historyEntry = null,
    readChunkBase64,
    onProgress = null,
}) {
    const conn = connections[deviceId];

    if (!conn || !conn.authenticated || !conn.ws) {
        return { ok: false, error: '未连接' };
    }

    const safeFileName = fileName || 'file.bin';
    const safeMimeType = mimeType || 'application/octet-stream';
    const totalBytes = Math.max(Number(sizeBytes) || 0, 0);
    const transferId = buildOutboundTransferId();
    let transfer = null;

    try {
        const startResult = await sendDesktopRequest(conn, {
            action: 'incoming_file_start',
            transfer_id: transferId,
            file_name: safeFileName,
            mime_type: safeMimeType,
            size_bytes: totalBytes,
            is_archive: false,
        }, FILE_TRANSFER_START_TIMEOUT_MS);

        if (startResult.status !== 'ok') {
            throw new Error(startResult.error || '桌面端拒绝接收文件');
        }

        transfer = registerOutboundDesktopFileTransfer(
            deviceId,
            transferId,
            calculateFileTransferAckTimeout(totalBytes)
        );

        let sentBytes = 0;
        while (sentBytes < totalBytes) {
            const chunkLength = Math.min(FILE_TRANSFER_CHUNK_BYTES, totalBytes - sentBytes);
            const chunkBase64 = await readChunkBase64(sentBytes, chunkLength);
            if (typeof chunkBase64 !== 'string' || chunkBase64.length === 0) {
                throw new Error('文件分块读取失败');
            }

            await waitForFileTransferBufferDrain(conn, transferId);
            sendWsJson(conn.ws, {
                action: 'incoming_file_chunk',
                transfer_id: transferId,
                chunk_base64: chunkBase64,
            });

            sentBytes += chunkLength;
            if (onProgress && totalBytes > 0) {
                const progress = Math.min(99, Math.max(1, Math.round((sentBytes / totalBytes) * 100)));
                onProgress({
                    stage: 'sending',
                    sentBytes,
                    totalBytes,
                    progress,
                });
            }
        }

        await waitForFileTransferBufferDrain(conn, transferId);
        onProgress?.({
            stage: 'saving',
            sentBytes: totalBytes,
            totalBytes,
            progress: 100,
        });
        sendWsJson(conn.ws, {
            action: 'incoming_file_complete',
            transfer_id: transferId,
        });

        const result = await transfer.promise;

        if (historyEntry) {
            historyEntry.status = 'success';
            historyEntry.savedPath = result.saved_path || '';
            updateHistory(historyEntry);
        }
        return { ok: true, savedPath: result.saved_path || '' };
    } catch (error) {
        const message = error instanceof Error ? error.message : String(error || '文件传输失败');
        if (historyEntry) {
            historyEntry.status = 'failed';
            updateHistory(historyEntry);
        }
        try {
            if (conn.ws && conn.ws.readyState === WebSocket.OPEN) {
                sendWsJson(conn.ws, {
                    action: 'incoming_file_error',
                    transfer_id: transferId,
                    error: message,
                });
            }
        } catch (notifyError) {
            console.warn('通知桌面端取消文件传输失败', notifyError);
        }
        return { ok: false, error: message };
    } finally {
        clearOutboundDesktopFileTransfer(transferId);
    }
}

async function sendFileToDesktopInChunks(deviceId, {
    fileName,
    mimeType,
    sizeBytes,
    buttonId,
    pendingText,
    historyEntry = null,
    failureToast = false,
    successToast = '',
    readChunkBase64,
}) {
    const conn = connections[deviceId];
    const btn = $(buttonId);

    if (!btn || !conn || !conn.authenticated || !conn.ws) {
        return { ok: false, error: '未连接' };
    }

    const originalText = btn.textContent;
    btn.classList.add('sending');
    btn.textContent = pendingText;
    setActionButtonsDisabled(deviceId, true);

    try {
        const result = await transferFileToDesktop(deviceId, {
            fileName,
            mimeType,
            sizeBytes,
            historyEntry,
            readChunkBase64,
            onProgress: ({ stage, progress }) => {
                btn.textContent = stage === 'saving' ? '保存中...' : `${progress}%`;
            },
        });

        if (result.ok) {
            if (successToast) {
                showToast(successToast);
            }
            btn.classList.add('success');
            btn.textContent = '✓';
            return result;
        }

        if (failureToast) {
            showToast(result.error || '文件传输失败');
        }
        btn.classList.add('fail');
        btn.textContent = '✗';
        return result;
    } finally {
        setTimeout(() => {
            btn.classList.remove('sending', 'success', 'fail');
            btn.textContent = originalText;
            setActionButtonsDisabled(deviceId, !conn.authenticated);
        }, 800);
    }
}

function computeHistoryItemsAggregateStatus(items = []) {
    const normalizedItems = items.map((item) => normalizeHistoryItem(item));
    if (!normalizedItems.length) {
        return 'pending';
    }

    if (normalizedItems.some((item) => item.status === 'pending')) {
        return 'pending';
    }

    const successCount = normalizedItems.filter((item) => item.status === 'success').length;
    const failedCount = normalizedItems.filter((item) => item.status === 'failed').length;
    if (failedCount === 0) {
        return 'success';
    }
    if (successCount === 0) {
        return 'failed';
    }
    return 'partial';
}

function createOutboundBatchHistoryEntry(deviceId, items = []) {
    const normalizedItems = items.map((item) => normalizeHistoryItem({
        kind: item.kind || normalizeHistoryKind(item.mimeType || ''),
        fileName: item.fileName || '文件',
        mimeType: item.mimeType || 'application/octet-stream',
        thumbnailDataUrl: item.thumbnailDataUrl || '',
        status: 'pending',
    }));
    const summary = buildHistoryItemsSummary(normalizedItems);
    const firstItem = normalizedItems[0] || null;
    const historyEntry = {
        id: buildOutboundTransferId('mobile-batch-history'),
        sessionId: buildOutboundTransferId('mobile-batch-session'),
        timestamp: getLocalTimestamp(),
        text: summary.text,
        status: 'pending',
        kind: summary.kind,
        itemCount: normalizedItems.length,
        saveTarget: 'desktop-inbox',
        items: normalizedItems,
        fileName: firstItem?.fileName || '',
        mimeType: firstItem?.mimeType || '',
        thumbnailDataUrl: firstItem?.thumbnailDataUrl || '',
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);
    return historyEntry;
}

function buildBatchTransferSummaryToast({ totalCount, successCount, failedCount }) {
    if (successCount === totalCount) {
        return `已发送 ${totalCount} 项到 VibeDrop 收件箱`;
    }
    if (failedCount === totalCount) {
        return `${totalCount} 项发送失败`;
    }
    return `已发送 ${successCount} 项，失败 ${failedCount} 项`;
}

async function sendFilesToDesktopBatch(deviceId, items, {
    buttonId,
    pendingText = '批量传输中...',
}) {
    const conn = connections[deviceId];
    const btn = $(buttonId);
    const normalizedItems = Array.isArray(items) ? items : [];

    if (!btn || !conn || !conn.authenticated || !conn.ws || normalizedItems.length <= 1) {
        return { ok: false, error: '未连接或批量内容无效' };
    }

    const historyEntry = createOutboundBatchHistoryEntry(deviceId, normalizedItems);
    const originalText = btn.textContent;
    let successCount = 0;
    let failedCount = 0;
    const failedIndexes = [];

    btn.classList.add('sending');
    btn.textContent = pendingText;
    setActionButtonsDisabled(deviceId, true);

    try {
        for (let index = 0; index < normalizedItems.length; index += 1) {
            const item = normalizedItems[index];
            const batchItem = historyEntry.items[index];
            const prefix = `${index + 1}/${normalizedItems.length}`;

            const result = await transferFileToDesktop(deviceId, {
                fileName: item.fileName,
                mimeType: item.mimeType,
                sizeBytes: item.sizeBytes,
                readChunkBase64: item.readChunkBase64,
                onProgress: ({ stage, progress }) => {
                    btn.textContent = stage === 'saving'
                        ? `${prefix} · 保存中`
                        : `${prefix} · ${progress}%`;
                },
            });

            if (result.ok) {
                successCount += 1;
                historyEntry.items[index] = normalizeHistoryItem({
                    ...batchItem,
                    status: 'success',
                    savedPath: result.savedPath || '',
                    error: '',
                });
            } else {
                failedCount += 1;
                failedIndexes.push(index);
                historyEntry.items[index] = normalizeHistoryItem({
                    ...batchItem,
                    status: 'failed',
                    error: result.error || '发送失败',
                });
            }

            historyEntry.status = computeHistoryItemsAggregateStatus(historyEntry.items);
            updateHistory(historyEntry);
        }

        showToast(buildBatchTransferSummaryToast({
            totalCount: normalizedItems.length,
            successCount,
            failedCount,
        }));

        if (failedCount === 0) {
            btn.classList.add('success');
            btn.textContent = '✓';
        } else if (successCount === 0) {
            btn.classList.add('fail');
            btn.textContent = '✗';
        } else {
            btn.classList.add('success');
            btn.textContent = `${successCount}/${normalizedItems.length}`;
        }

        return {
            ok: failedCount === 0,
            partial: successCount > 0 && failedCount > 0,
            successCount,
            failedCount,
            failedIndexes,
        };
    } finally {
        setTimeout(() => {
            btn.classList.remove('sending', 'success', 'fail');
            btn.textContent = originalText;
            setActionButtonsDisabled(deviceId, !conn.authenticated);
        }, 900);
    }
}

function getTargetName(deviceId) {
    const conn = connections[deviceId];
    const devices = getDevices();
    const dev = findDeviceByAnyId(deviceId, devices);
    return (dev ? dev.name : '') || conn?.hostname || deviceId;
}

function getTargetDeviceName(deviceId) {
    const conn = connections[deviceId];
    return conn?.hostname || '';
}

function buildHistoryTargetMeta(deviceId) {
    const device = findDeviceByAnyId(deviceId);
    const targetAlias = getTargetName(deviceId);
    const targetDeviceName = getTargetDeviceName(deviceId);
    return {
        target: deviceId,
        targetName: targetAlias || targetDeviceName || deviceId,
        targetAlias,
        targetDeviceName,
        targetServerId: device?.serverId || '',
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

    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: `[文件] ${file.name}`,
        status: 'pending',
        kind: 'file',
        saveTarget: 'desktop-inbox',
        fileName: file.name,
        mimeType: file.type || 'application/octet-stream',
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);

    try {
        await sendFileToDesktopInChunks(deviceId, {
            fileName: file.name,
            mimeType: file.type || 'application/octet-stream',
            sizeBytes: file.size,
            buttonId: `filebtn-${deviceId}`,
            pendingText: '传文件中...',
            historyEntry,
            failureToast: true,
            successToast: '文件已保存到 VibeDrop 收件箱',
            readChunkBase64: (offsetBytes, lengthBytes) => readBlobChunkAsBase64(file, offsetBytes, lengthBytes),
        });
    } catch (error) {
        historyEntry.status = 'failed';
        updateHistory(historyEntry);
        showToast(`文件传输失败：${error.message}`);
    }
}

async function sendSelectedFilesBatch(deviceId, files) {
    const conn = connections[deviceId];
    const normalizedFiles = Array.from(files || []).filter(Boolean);
    if (normalizedFiles.length <= 1 || !conn || !conn.authenticated || !conn.ws) {
        return;
    }

    const batchItems = normalizedFiles.map((file) => ({
        fileName: file.name || 'file.bin',
        mimeType: file.type || 'application/octet-stream',
        sizeBytes: Number(file.size || 0),
        kind: normalizeHistoryKind(file.type || ''),
        readChunkBase64: (offsetBytes, lengthBytes) => readBlobChunkAsBase64(file, offsetBytes, lengthBytes),
    }));

    await sendFilesToDesktopBatch(deviceId, batchItems, {
        buttonId: `filebtn-${deviceId}`,
        pendingText: '批量传输中...',
    });
}

function normalizePendingSharedContentItem(raw) {
    if (!raw || !raw.displayName) {
        return null;
    }

    const mimeType = raw.mimeType || 'application/octet-stream';
    return {
        cachePath: raw.cachePath || '',
        displayName: raw.displayName,
        mimeType,
        sizeBytes: Number(raw.sizeBytes || 0),
        isImage: Boolean(raw.isImage || mimeType.startsWith('image/')),
        kind: normalizeHistoryKind(mimeType),
    };
}

function normalizePendingSharedContents(raw) {
    if (!raw) {
        return [];
    }

    let data = raw;
    if (typeof raw === 'string') {
        try {
            data = JSON.parse(raw);
        } catch {
            return [];
        }
    }

    const candidates = Array.isArray(data)
        ? data
        : Array.isArray(data?.items)
            ? data.items
            : [data];

    return candidates
        .map((item) => normalizePendingSharedContentItem(item))
        .filter(Boolean);
}

function getPrimaryPendingSharedContent() {
    return pendingSharedContents[0] || null;
}

function summarizePendingSharedContents(items = pendingSharedContents) {
    const normalizedItems = items.map((item) => normalizePendingSharedContentItem(item)).filter(Boolean);
    const imageCount = normalizedItems.filter((item) => item.kind === 'image').length;
    const videoCount = normalizedItems.filter((item) => item.kind === 'video').length;
    const fileCount = normalizedItems.length - imageCount - videoCount;
    return {
        totalCount: normalizedItems.length,
        imageCount,
        videoCount,
        fileCount,
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

    if (!pendingSharedContents.length) {
        card.classList.add('hidden');
        title.textContent = '';
        hint.textContent = '';
        return;
    }

    if (pendingSharedContents.length === 1) {
        const [shared] = pendingSharedContents;
        const sizeText = formatFileSize(shared.sizeBytes);
        title.textContent = shared.displayName;
        hint.textContent = shared.isImage
            ? `图片已从系统分享导入${sizeText ? ` · ${sizeText}` : ''}，现在可直接点任意设备的“传图到剪贴板”或“传到收件箱”。`
            : `文件已从系统分享导入${sizeText ? ` · ${sizeText}` : ''}，现在可直接点任意设备的“传到收件箱”。`;
        card.classList.remove('hidden');
        return;
    }

    const summary = summarizePendingSharedContents();
    const segments = [];
    if (summary.imageCount) {
        segments.push(`图片 ${summary.imageCount} 张`);
    }
    if (summary.videoCount) {
        segments.push(`视频 ${summary.videoCount} 个`);
    }
    if (summary.fileCount) {
        segments.push(`文件 ${summary.fileCount} 项`);
    }

    title.textContent = `已选择 ${summary.totalCount} 项`;
    hint.textContent = `${segments.join(' · ')}。现在可直接点任意设备的“传到收件箱”批量发送。`;
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
    pendingSharedContents = [];
    renderPendingSharedContent();
    if (!silent) {
        showToast('已清除共享内容');
    }
}

function setPendingSharedContents(raw, { announce = false } = {}) {
    const normalized = normalizePendingSharedContents(raw);
    if (!normalized.length) {
        pendingSharedContents = [];
        renderPendingSharedContent();
        return;
    }
    pendingSharedContents = normalized;
    renderPendingSharedContent();
    focusSendView();
    if (announce) {
        showToast(
            normalized.length === 1
                ? `已接收共享内容：${normalized[0].displayName}`
                : `已接收共享内容：${normalized.length} 项`
        );
    }
}

async function loadPendingSharedContent() {
    if (!window.NativeShare) {
        return;
    }

    try {
        const raw = typeof window.NativeShare.getPendingSharedContents === 'function'
            ? window.NativeShare.getPendingSharedContents()
            : window.NativeShare.getPendingSharedContent?.();
        if (raw) {
            setPendingSharedContents(raw);
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
        const payload = event.detail?.items || event.detail;
        setPendingSharedContents(payload, { announce: true });
    });
}

function readPendingSharedContentBase64At(itemIndex) {
    if (!window.NativeShare) {
        throw new Error('当前环境不支持系统分享内容读取');
    }

    const base64 = typeof window.NativeShare.readPendingSharedContentBase64At === 'function'
        ? window.NativeShare.readPendingSharedContentBase64At(itemIndex)
        : window.NativeShare.readPendingSharedContentBase64?.();
    if (!base64) {
        throw new Error('共享内容已失效，请重新分享一次');
    }

    return base64;
}

function readPendingSharedContentBase64() {
    return readPendingSharedContentBase64At(0);
}

function retainPendingSharedContentsByIndexes(indexes = []) {
    const keepIndexes = Array.from(new Set(indexes.map((value) => Number(value)).filter((value) => Number.isInteger(value) && value >= 0)));
    if (window.NativeShare && typeof window.NativeShare.retainPendingSharedContentIndexes === 'function') {
        try {
            window.NativeShare.retainPendingSharedContentIndexes(JSON.stringify(keepIndexes));
        } catch (error) {
            console.warn('原生保留失败项缓存失败', error);
        }
    }
    pendingSharedContents = keepIndexes
        .map((index) => pendingSharedContents[index])
        .filter(Boolean);
    renderPendingSharedContent();
}

async function sendPendingSharedImage(deviceId) {
    const shared = getPrimaryPendingSharedContent();
    const conn = connections[deviceId];
    if (!shared || pendingSharedContents.length !== 1 || !shared.isImage || !conn || !conn.authenticated || !conn.ws) {
        return;
    }
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
        const imageBase64 = readPendingSharedContentBase64At(0);
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
    const shared = getPrimaryPendingSharedContent();
    const conn = connections[deviceId];
    if (!shared || pendingSharedContents.length !== 1 || !conn || !conn.authenticated || !conn.ws) {
        return;
    }
    const historyEntry = {
        id: Date.now(),
        timestamp: getLocalTimestamp(),
        text: `[文件] ${shared.displayName}`,
        status: 'pending',
        kind: 'file',
        saveTarget: 'desktop-inbox',
        fileName: shared.displayName,
        mimeType: shared.mimeType,
        ...buildHistoryTargetMeta(deviceId),
    };
    addHistory(historyEntry);

    try {
        const result = await sendFileToDesktopInChunks(deviceId, {
            fileName: shared.displayName,
            mimeType: shared.mimeType,
            sizeBytes: shared.sizeBytes,
            buttonId: `filebtn-${deviceId}`,
            pendingText: '传文件中...',
            historyEntry,
            failureToast: true,
            successToast: '文件已保存到 VibeDrop 收件箱',
            readChunkBase64: (offsetBytes, lengthBytes) => (
                Promise.resolve(readPendingSharedContentChunkBase64At(0, offsetBytes, lengthBytes))
            ),
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

async function sendPendingSharedFilesBatch(deviceId) {
    const conn = connections[deviceId];
    if (pendingSharedContents.length <= 1 || !conn || !conn.authenticated || !conn.ws) {
        return;
    }

    const sharedItems = pendingSharedContents.slice();
    const batchItems = sharedItems.map((shared, index) => ({
        fileName: shared.displayName,
        mimeType: shared.mimeType,
        sizeBytes: shared.sizeBytes,
        kind: shared.kind,
        readChunkBase64: (offsetBytes, lengthBytes) => (
            Promise.resolve(readPendingSharedContentChunkBase64At(index, offsetBytes, lengthBytes))
        ),
    }));

    const result = await sendFilesToDesktopBatch(deviceId, batchItems, {
        buttonId: `filebtn-${deviceId}`,
        pendingText: '批量传输中...',
    });

    if (result.ok) {
        clearPendingSharedContentState({ silent: true });
        return;
    }

    if (result.partial) {
        retainPendingSharedContentsByIndexes(result.failedIndexes || []);
    }
}

// ============================================
// 历史记录（localStorage）
// ============================================

function getHistory() {
    const devicesKey = `${settingsRevision}:${historyRevision}`;
    if (hydratedHistoryCache.historyRevision === historyRevision && hydratedHistoryCache.devicesKey === devicesKey) {
        return hydratedHistoryCache.value;
    }

    const devices = getDevices();
    const history = readStoredHistoryEntries();
    const hydrated = history.map((entry) => {
        return hydrateHistoryEntry(entry, devices, history);
    });

    hydratedHistoryCache = {
        historyRevision,
        devicesKey,
        value: hydrated,
    };
    return hydrated;
}

function addHistory(entry) {
    const history = getHistory();
    history.unshift(entry);
    setStoredHistoryRaw(JSON.stringify(history));
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

function normalizeHistoryDeviceToken(value) {
    return String(value || '').trim().toLowerCase();
}

function resolveHistoryDevice(targetId, targetAlias, targetDeviceName, targetServerId = '', devices = getDevices(), historyEntries = readStoredHistoryEntries()) {
    const directMatch = findDeviceByAnyId(targetId, devices);
    if (directMatch) {
        return directMatch;
    }

    if (targetServerId) {
        const serverMatch = devices.find((device) => device.serverId && device.serverId === targetServerId);
        if (serverMatch) {
            return serverMatch;
        }
    }

    const tokens = new Set([
        normalizeHistoryDeviceToken(targetAlias),
        normalizeHistoryDeviceToken(targetDeviceName),
    ].filter(Boolean));
    if (!tokens.size) {
        return null;
    }

    return devices.find((device) => {
        const candidates = getDeviceIdentityLabels(device, historyEntries);
        return Array.from(tokens).some((token) => (
            candidates.some((candidate) => (
                normalizeHistoryDeviceToken(candidate) === token
                || labelsLookLikeSameMachine(candidate, token)
            ))
        ));
    }) || null;
}

function hydrateHistoryEntry(entry, devices = getDevices(), historyEntries = readStoredHistoryEntries()) {
    const storedTarget = typeof entry.target === 'string' ? entry.target : '';
    const storedTargetName = typeof entry.targetName === 'string' ? entry.targetName : '';
    const storedTargetAlias = typeof entry.targetAlias === 'string' ? entry.targetAlias : '';
    const storedTargetHost = typeof entry.targetDeviceName === 'string'
        ? entry.targetDeviceName
        : (typeof entry.targetHost === 'string' ? entry.targetHost : '');
    const storedTargetServerId = typeof entry.targetServerId === 'string' ? entry.targetServerId : '';
    const targetId = entry.targetId
        || entry.deviceId
        || (looksLikeInternalDeviceId(storedTarget) ? storedTarget : '');

    const device = resolveHistoryDevice(
        targetId,
        storedTargetAlias || (!looksLikeTargetHost(storedTargetName) ? storedTargetName : ''),
        storedTargetHost || (looksLikeTargetHost(storedTargetName) ? storedTargetName : ''),
        storedTargetServerId,
        devices,
        historyEntries
    );
    const resolvedTargetId = device?.id || targetId;

    const targetAlias = [
        device?.name || '',
        storedTargetAlias,
        !looksLikeInternalDeviceId(storedTarget) && !looksLikeTargetHost(storedTarget) ? storedTarget : '',
        !looksLikeInternalDeviceId(storedTargetName) && !looksLikeTargetHost(storedTargetName) ? storedTargetName : '',
    ].find(Boolean) || '';

    const targetDeviceName = [
        storedTargetHost,
        looksLikeTargetHost(storedTargetName) ? storedTargetName : '',
        looksLikeTargetHost(storedTarget) ? storedTarget : '',
        resolvedTargetId ? getTargetDeviceName(resolvedTargetId) : '',
    ].find(Boolean) || '';

    const displayTarget = targetAlias || targetDeviceName || resolvedTargetId || storedTarget || storedTargetName || '未知设备';
    const items = Array.isArray(entry.items) ? entry.items.map((item) => normalizeHistoryItem(item)) : undefined;
    const summary = items?.length ? buildHistoryItemsSummary(items) : null;
    const firstItem = items?.[0];

    return {
        ...entry,
        target: resolvedTargetId || storedTarget || displayTarget,
        targetName: displayTarget,
        targetAlias,
        targetDeviceName,
        targetServerId: storedTargetServerId || device?.serverId || '',
        direction: entry.direction || '',
        status: entry.status || 'success',
        kind: entry.kind || summary?.kind || 'text',
        itemCount: entry.itemCount || entry.item_count || items?.length,
        saveTarget: entry.saveTarget || entry.save_target || '',
        items,
        fileName: entry.fileName || entry.file_name || firstItem?.fileName || '',
        mimeType: entry.mimeType || entry.mime_type || firstItem?.mimeType || '',
        thumbnailDataUrl: entry.thumbnailDataUrl || entry.thumbnail_data_url || firstItem?.thumbnailDataUrl || '',
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
    const targetServerId = entry.targetServerId || entry.serverId || '';
    const targetId = entry.targetId
        || entry.deviceId
        || (typeof entry.target === 'string' && /^dev[_-]/.test(entry.target) ? entry.target : '')
        || resolveHistoryImportTargetId(targetAlias, targetDeviceName, targetServerId)
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
        targetServerId,
    };
}

function resolveHistoryImportTargetId(targetAlias, targetDeviceName, targetServerId = '') {
    const devices = getDevices();
    if (targetServerId) {
        const serverMatch = devices.find((device) => device.serverId && device.serverId === targetServerId);
        if (serverMatch) {
            return serverMatch.id;
        }
    }
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
        setStoredHistoryRaw(JSON.stringify(history));
        persistHistory();
    }
}

function clearHistory() {
    clearStoredHistory();
    persistHistory();
    clearHistoryHeatmapSelection();
    scheduleHistoryRender();
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

    const historyList = $('history-list');
    if (historyList) {
        historyList.addEventListener('click', async (event) => {
            const itemElement = event.target.closest('.history-item');
            if (!itemElement || !historyList.contains(itemElement)) {
                return;
            }

            const idx = Number(itemElement.dataset.idx || '-1');
            const entry = currentRenderedHistoryEntries[idx];
            if (!entry) {
                return;
            }

            if (isHistoryMediaEntry(entry)) {
                const mediaCell = event.target.closest('[data-media-index]');
                if (mediaCell) {
                    const itemIndex = Number(mediaCell.dataset.mediaIndex || '0');
                    await openHistoryMediaItem(entry, itemIndex);
                    return;
                }

                const mediaItems = getHistoryEntryItems(entry);
                if (mediaItems.length > 1) {
                    showHistoryMediaPreview(entry, mediaItems);
                }
                return;
            }

            if (entry.kind === 'file') {
                showToast('文件记录不支持复制');
                return;
            }

            writeClipboard(entry.text).then(() => {
                showToast('已复制');
            }).catch(() => {
                showToast('复制失败');
            });
        });
    }
}

function initHistoryMediaPreview() {
    if (historyMediaPreviewInitialized) {
        return;
    }

    const modal = $('history-media-preview-modal');
    const backdrop = $('history-media-preview-backdrop');
    const closeBtn = $('history-media-preview-close');

    if (!modal || !backdrop || !closeBtn) {
        return;
    }

    const close = () => closeHistoryMediaPreview();
    backdrop.addEventListener('click', close);
    closeBtn.addEventListener('click', close);
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            closeHistoryMediaPreview();
        }
    });

    historyMediaPreviewInitialized = true;
}

function initHistoryMediaViewer() {
    if (historyMediaViewerInitialized) {
        return;
    }

    const modal = $('history-media-viewer-modal');
    const backdrop = $('history-media-viewer-backdrop');
    const closeBtn = $('history-media-viewer-close');

    if (!modal || !backdrop || !closeBtn) {
        return;
    }

    const close = () => closeHistoryMediaViewer();
    backdrop.addEventListener('click', close);
    closeBtn.addEventListener('click', close);
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape') {
            closeHistoryMediaViewer();
        }
    });

    if (window.Plyr?.defaults) {
        window.Plyr.defaults.iconUrl = '/vendor/plyr/plyr.svg';
    }

    historyMediaViewerInitialized = true;
}

function showHistoryMediaPreview(entry, items, { initialIndex = 0 } = {}) {
    const modal = $('history-media-preview-modal');
    const title = $('history-media-preview-title');
    const subtitle = $('history-media-preview-subtitle');
    const body = $('history-media-preview-body');

    if (!modal || !title || !subtitle || !body) {
        return;
    }

    const normalizedItems = (Array.isArray(items) ? items : [])
        .map((item) => normalizeHistoryItem(item));
    if (!normalizedItems.length) {
        showToast('没有可预览的媒体内容');
        return;
    }

    const orderedItems = normalizedItems.slice();
    if (initialIndex > 0 && initialIndex < orderedItems.length) {
        const [selected] = orderedItems.splice(initialIndex, 1);
        orderedItems.unshift(selected);
    }

    historyMediaPreviewState = {
        entry,
        items: orderedItems,
    };

    title.textContent = entry.text || '媒体预览';
    subtitle.textContent = getHistoryEntryHint(entry) || '查看这次传输里的全部媒体。';
    body.innerHTML = `
        <div class="history-media-preview-grid">
            ${orderedItems.map((item, index) => renderHistoryMediaPreviewItem(item, index)).join('')}
        </div>
    `;

    body.querySelectorAll('[data-preview-media-index]').forEach((node) => {
        node.addEventListener('click', async () => {
            const itemIndex = Number(node.dataset.previewMediaIndex || '0');
            const previewItem = historyMediaPreviewState.items[itemIndex];
            if (!previewItem) {
                return;
            }
            const openPath = previewItem.savedPath || previewItem.filePath || '';
            if (!openPath) {
                showToast('原文件路径未保留');
                return;
            }
            await openHistoryMediaItem({
                ...(historyMediaPreviewState.entry || {}),
                items: historyMediaPreviewState.items,
            }, itemIndex);
        });
    });

    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');
}

function closeHistoryMediaPreview() {
    const modal = $('history-media-preview-modal');
    const body = $('history-media-preview-body');
    if (!modal || !body) {
        return;
    }

    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');
    body.innerHTML = '';
    historyMediaPreviewState = {
        entry: null,
        items: [],
    };
}

function showHistoryMediaViewerLoading(item) {
    const modal = $('history-media-viewer-modal');
    const title = $('history-media-viewer-title');
    const subtitle = $('history-media-viewer-subtitle');
    const stage = $('history-media-viewer-stage');
    if (!modal || !title || !subtitle || !stage) {
        return;
    }

    title.textContent = truncateFilenamePreserveExtension(item.fileName || '媒体');
    subtitle.textContent = item.kind === 'video' ? '正在加载视频播放器…' : '正在加载原始图片…';
    stage.classList.add('is-loading');
    stage.innerHTML = '正在准备媒体预览…';
    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');
}

function destroyHistoryMediaViewerPlayer() {
    if (!historyMediaViewerState.plyr) {
        return;
    }
    try {
        historyMediaViewerState.plyr.destroy();
    } catch (error) {
        console.warn('销毁 Plyr 实例失败', error);
    }
    historyMediaViewerState.plyr = null;
}

function closeHistoryMediaViewer() {
    const modal = $('history-media-viewer-modal');
    const stage = $('history-media-viewer-stage');
    if (!modal || !stage) {
        return;
    }

    destroyHistoryMediaViewerPlayer();
    stage.classList.remove('is-loading');
    stage.innerHTML = '';
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');
    historyMediaViewerState = {
        entry: null,
        items: [],
        currentIndex: 0,
        plyr: null,
    };
}

function showHistoryMediaViewerError(item, message, { allowFallback = true } = {}) {
    const modal = $('history-media-viewer-modal');
    const title = $('history-media-viewer-title');
    const subtitle = $('history-media-viewer-subtitle');
    const stage = $('history-media-viewer-stage');
    if (!modal || !title || !subtitle || !stage) {
        return;
    }

    const hasFallback = allowFallback && window.NativeMediaLibrary && typeof window.NativeMediaLibrary.openPath === 'function';
    title.textContent = truncateFilenamePreserveExtension(item.fileName || '媒体');
    subtitle.textContent = item.kind === 'video' ? '这个视频暂时无法在应用内直接播放。' : '这个图片暂时无法在应用内直接显示。';
    stage.classList.remove('is-loading');
    stage.innerHTML = `
        <div class="history-media-viewer-error">
            <div>${escapeHtml(message || '媒体预览失败')}</div>
            ${hasFallback ? '<button id="history-media-viewer-fallback" class="secondary-btn history-media-viewer-fallback">改用系统打开</button>' : ''}
        </div>
    `;
    modal.classList.remove('hidden');
    modal.setAttribute('aria-hidden', 'false');

    if (hasFallback) {
        $('history-media-viewer-fallback')?.addEventListener('click', () => {
            fallbackOpenHistoryMediaExternally(item);
        });
    }
}

async function resolveHistoryMediaPreviewUri(item) {
    const openPath = item.savedPath || item.filePath || '';
    if (!openPath) {
        return '';
    }

    if (historyMediaPreviewUriCache.has(openPath)) {
        return historyMediaPreviewUriCache.get(openPath);
    }

    let previewUri = '';
    if (openPath.startsWith('data:') || openPath.startsWith('blob:') || openPath.startsWith('http://') || openPath.startsWith('https://')) {
        previewUri = openPath;
    } else if (window.NativeMediaLibrary && typeof window.NativeMediaLibrary.getPreviewUri === 'function') {
        try {
            previewUri = window.NativeMediaLibrary.getPreviewUri(openPath, item.mimeType || '') || '';
        } catch (error) {
            console.warn('NativeMediaLibrary.getPreviewUri 调用失败', error);
        }
    }

    if (!previewUri && item.kind === 'image' && item.thumbnailDataUrl) {
        previewUri = item.thumbnailDataUrl;
    }

    if (previewUri) {
        historyMediaPreviewUriCache.set(openPath, previewUri);
    }
    return previewUri;
}

function resolveImageDimensions(source) {
    return new Promise((resolve) => {
        if (!source) {
            resolve({ width: 1200, height: 1200 });
            return;
        }

        if (historyMediaPreviewDimensionsCache.has(source)) {
            resolve(historyMediaPreviewDimensionsCache.get(source));
            return;
        }

        const image = new Image();
        image.onload = () => {
            const dimensions = {
                width: image.naturalWidth || 1200,
                height: image.naturalHeight || 1200,
            };
            historyMediaPreviewDimensionsCache.set(source, dimensions);
            resolve(dimensions);
        };
        image.onerror = () => {
            const dimensions = { width: 1200, height: 1200 };
            historyMediaPreviewDimensionsCache.set(source, dimensions);
            resolve(dimensions);
        };
        image.src = source;
    });
}

async function buildPhotoSwipeSlides(items) {
    const slides = [];
    for (const item of items) {
        const src = await resolveHistoryMediaPreviewUri(item);
        if (!src) {
            continue;
        }
        const dimensions = await resolveImageDimensions(item.thumbnailDataUrl || src);
        slides.push({
            src,
            width: dimensions.width,
            height: dimensions.height,
            alt: item.fileName || '图片',
        });
    }
    return slides;
}

async function showHistoryMediaImageViewer(entry, itemIndex) {
    if (!photoswipeReady || typeof window.PhotoSwipe !== 'function') {
        throw new Error('图片预览组件还没准备好');
    }

    const items = getHistoryEntryItems(entry).filter((item) => item.kind === 'image');
    if (!items.length) {
        throw new Error('没有可预览的图片');
    }

    const clickedItem = getHistoryEntryItems(entry)[itemIndex];
    const clickedPath = clickedItem?.savedPath || clickedItem?.filePath || '';
    let startIndex = items.findIndex((item) => (item.savedPath || item.filePath || '') === clickedPath);
    if (startIndex < 0) {
        startIndex = 0;
    }

    const slides = await buildPhotoSwipeSlides(items);
    if (!slides.length) {
        throw new Error('图片预览地址无效');
    }

    const pswp = new window.PhotoSwipe({
        dataSource: slides,
        index: Math.min(startIndex, slides.length - 1),
        loop: slides.length > 1,
        bgOpacity: 0.96,
        spacing: 0.08,
        showHideAnimationType: 'zoom',
        closeOnVerticalDrag: true,
        pinchToClose: true,
        secondaryZoomLevel: 2.5,
        maxZoomLevel: 4,
    });
    pswp.init();
}

async function showHistoryMediaVideoViewer(entry, itemIndex) {
    const items = getHistoryEntryItems(entry);
    const item = items[itemIndex];
    if (!item) {
        return;
    }

    showHistoryMediaViewerLoading(item);

    const previewUri = await resolveHistoryMediaPreviewUri(item);
    if (!previewUri) {
        showHistoryMediaViewerError(item, '没有可用的视频预览地址。');
        return;
    }

    const title = $('history-media-viewer-title');
    const subtitle = $('history-media-viewer-subtitle');
    const stage = $('history-media-viewer-stage');
    if (!title || !subtitle || !stage) {
        return;
    }

    destroyHistoryMediaViewerPlayer();
    historyMediaViewerState = {
        entry,
        items,
        currentIndex: itemIndex,
        plyr: null,
    };

    title.textContent = truncateFilenamePreserveExtension(item.fileName || '视频');
    subtitle.textContent = '应用内直接播放原始视频';
    stage.classList.remove('is-loading');
    stage.innerHTML = `
        <div class="history-media-video-shell">
            <div class="history-media-video-player">
                <video id="history-media-viewer-video" playsinline controls preload="metadata"></video>
            </div>
        </div>
    `;

    const video = $('history-media-viewer-video');
    if (!video) {
        showHistoryMediaViewerError(item, '视频播放器初始化失败。');
        return;
    }

    if (item.thumbnailDataUrl) {
        video.poster = item.thumbnailDataUrl;
    }
    video.src = previewUri;
    video.setAttribute('playsinline', '');
    video.setAttribute('webkit-playsinline', '');
    video.load();

    try {
        if (window.Plyr) {
            historyMediaViewerState.plyr = new window.Plyr(video, {
                controls: [
                    'play-large',
                    'play',
                    'progress',
                    'current-time',
                    'duration',
                    'mute',
                    'volume',
                    'settings',
                    'picture-in-picture',
                    'fullscreen',
                ],
                fullscreen: {
                    enabled: true,
                    iosNative: true,
                },
                storage: {
                    enabled: false,
                },
                iconUrl: '/vendor/plyr/plyr.svg',
            });
        }
    } catch (error) {
        console.warn('Plyr 初始化失败，回退到原生 video 控件', error);
    }
}

function fallbackOpenHistoryMediaExternally(item) {
    const openPath = item.savedPath || item.filePath || '';
    if (!openPath) {
        showToast('原文件路径未保留');
        return;
    }

    if (window.NativeMediaLibrary && typeof window.NativeMediaLibrary.openPath === 'function') {
        try {
            const errorMessage = window.NativeMediaLibrary.openPath(openPath, item.mimeType || '');
            if (errorMessage) {
                showToast(`打开失败：${errorMessage}`);
            }
            return;
        } catch (error) {
            console.warn('NativeMediaLibrary.openPath 调用失败', error);
        }
    }

    showToast('当前环境不支持直接打开原文件');
}

async function openHistoryMediaExternallyWithPackage(item, app, { retryOnFailure = true } = {}) {
    const openPath = item?.savedPath || item?.filePath || '';
    if (!openPath) {
        showToast('原文件路径未保留');
        return;
    }

    if (!supportsMediaOpenerPreferences()) {
        fallbackOpenHistoryMediaExternally(item);
        return;
    }

    try {
        const raw = window.NativeMediaLibrary.openPathWithPackage(
            openPath,
            item?.mimeType || '',
            app?.packageName || ''
        );
        const result = JSON.parse(raw || '{}');
        if (result.ok) {
            return;
        }

        const kind = inferMediaOpenerKind(item);
        if (result.code === 'package_unavailable' && kind && getPreferredMediaOpener(kind)?.packageName === app?.packageName) {
            clearPreferredMediaOpener(kind);
            showToast(`${app?.label || '默认应用'}已不可用，请重新选择`);
            if (retryOnFailure) {
                showMediaOpenerPicker({ kind, mode: 'open', item });
                return;
            }
        }

        showToast(`打开失败：${result.message || '操作失败'}`);
    } catch (error) {
        console.warn('按指定应用打开媒体失败', error);
        showToast('打开失败');
    }
}

function renderHistoryMediaPreviewItem(item, index) {
    const displayName = truncateFilenamePreserveExtension(item.fileName || '媒体');
    const thumb = item.thumbnailDataUrl
        ? `<img class="history-media-preview-thumb" src="${item.thumbnailDataUrl}" alt="${escapeHtml(item.fileName || '媒体')}">`
        : `<div class="history-media-preview-thumb-placeholder">${item.kind === 'video' ? '视频' : item.kind === 'image' ? '图片' : '文件'}</div>`;
    const playBadge = item.kind === 'video'
        ? '<span class="history-media-play history-media-play-single">▶</span>'
        : '';
    const openPath = item.savedPath || item.filePath || '';
    const openableClass = openPath ? ' is-openable' : '';
    const dataAttr = openPath ? ` data-preview-media-index="${index}"` : '';

    return `
        <div class="history-media-preview-item${openableClass}"${dataAttr}>
            <div class="history-media-preview-thumb-wrap">
                ${thumb}
                ${playBadge}
            </div>
            <div class="history-media-preview-meta">
                <div class="history-media-preview-name" title="${escapeHtml(item.fileName || '媒体')}">${escapeHtml(displayName)}</div>
            </div>
        </div>
    `;
}

function truncateFilenamePreserveExtension(filename, maxBaseLength = 16, maxTotalLength = 28) {
    const value = String(filename || '媒体').trim() || '媒体';
    if (value.length <= maxTotalLength) {
        return value;
    }

    const lastDot = value.lastIndexOf('.');
    if (lastDot <= 0 || lastDot === value.length - 1) {
        return `${value.slice(0, Math.max(8, maxTotalLength - 1))}…`;
    }

    const ext = value.slice(lastDot);
    if (ext.length > 12) {
        return `${value.slice(0, Math.max(8, maxTotalLength - 1))}…`;
    }

    const base = value.slice(0, lastDot);
    const allowedBaseLength = Math.max(8, Math.min(maxBaseLength, maxTotalLength - ext.length - 1));
    if (base.length <= allowedBaseLength) {
        return value;
    }

    return `${base.slice(0, allowedBaseLength)}…${ext}`;
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
            setStoredHistoryRaw(JSON.stringify(existing));
            persistHistory();

            resultDiv.innerHTML = `已导入 ${added} 条，跳过 ${skipped} 条重复`;
            resultDiv.style.color = '#147d33';
            scheduleHistoryRender();
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
    const renderToken = ++historyRenderToken;

    if (history.length === 0) {
        currentRenderedHistoryEntries = [];
        renderHistoryHeatmap([]);
        renderHistoryFilterSummary([]);
        list.innerHTML = '<p class="empty-hint">暂无发送记录</p>';
        return;
    }

    renderHistoryHeatmap(baseEntries);
    renderHistoryFilterSummary(baseEntries);
    const filtered = applyHistoryHeatmapSelection(baseEntries);
    const hasSearchQuery = Boolean(normalizeSearchText(currentHistoryFilters.query));

    if (filtered.length === 0) {
        currentRenderedHistoryEntries = [];
        const emptyText = historyHeatmapState.selectionDate && historyHeatmapState.selectionHour != null
            ? '这个时段没有符合条件的记录'
            : hasSearchQuery
                ? '没有匹配的历史记录'
                : '没有符合筛选条件的记录';
        list.innerHTML = `<p class="empty-hint">${emptyText}</p>`;
        return;
    }

    currentRenderedHistoryEntries = filtered;

    const renderItemMarkup = (entry, index) => {
        const time = formatTime(entry.timestamp);
        const statusIcon = getHistoryStatusLabel(entry);
        const primaryTarget = getHistoryPrimaryTargetLabel(entry);
        const secondaryTarget = getHistorySecondaryTargetLabel(entry);
        const title = getHistoryEntryTitle(entry);
        const content = renderHistoryEntryContent(entry);
        const clickable = isHistoryMediaEntry(entry) ? '' : ' style="cursor:pointer"';
        return `
            <div class="history-item" data-idx="${index}"${clickable} title="${title}">
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
    };

    const initialEnd = Math.min(filtered.length, HISTORY_RENDER_INITIAL_BATCH);
    list.innerHTML = filtered
        .slice(0, initialEnd)
        .map((entry, index) => renderItemMarkup(entry, index))
        .join('');

    if (initialEnd >= filtered.length) {
        return;
    }

    let nextIndex = initialEnd;
    const appendBatch = () => {
        if (renderToken !== historyRenderToken) {
            return;
        }
        if ($('history-view')?.classList.contains('hidden')) {
            return;
        }

        const end = Math.min(nextIndex + HISTORY_RENDER_BATCH_SIZE, filtered.length);
        const html = [];
        for (let i = nextIndex; i < end; i += 1) {
            html.push(renderItemMarkup(filtered[i], i));
        }

        if (html.length > 0) {
            list.insertAdjacentHTML('beforeend', html.join(''));
        }

        nextIndex = end;
        if (nextIndex < filtered.length) {
            requestAnimationFrame(appendBatch);
        }
    };

    requestAnimationFrame(appendBatch);
}

async function openHistoryMediaItem(entry, itemIndex) {
    const items = getHistoryEntryItems(entry);
    const item = items[itemIndex];
    if (!item) {
        return;
    }

    const openPath = item.savedPath || item.filePath || '';
    if (!openPath) {
        showToast('原文件路径未保留');
        return;
    }

    closeHistoryMediaViewer();
    closeHistoryMediaPreview();
    const kind = inferMediaOpenerKind(item);
    if (!kind) {
        fallbackOpenHistoryMediaExternally(item);
        return;
    }

    const preferred = getPreferredMediaOpener(kind);
    if (preferred?.packageName) {
        await openHistoryMediaExternallyWithPackage(item, preferred);
        return;
    }

    if (supportsMediaOpenerPreferences()) {
        showMediaOpenerPicker({ kind, mode: 'open', item });
        return;
    }

    fallbackOpenHistoryMediaExternally(item);
}

function getHistoryEntryItems(entry) {
    const items = Array.isArray(entry.items) ? entry.items.map((item) => normalizeHistoryItem(item)) : [];
    if (items.length) {
        return items;
    }

    if (entry.kind === 'image' || entry.kind === 'video') {
        return [normalizeHistoryItem({
            kind: entry.kind,
            fileName: entry.fileName || entry.file_name || entry.text || '媒体',
            mimeType: entry.mimeType || entry.mime_type || '',
            thumbnailDataUrl: entry.thumbnailDataUrl || entry.thumbnail_data_url || '',
        })];
    }

    return [];
}

function isHistoryMediaEntry(entry) {
    return ['image', 'video', 'media'].includes(entry.kind);
}

function getIncomingDesktopSavedLocationLabel(saveTarget = '') {
    if (saveTarget === 'gallery' || saveTarget === 'gallery-image' || saveTarget === 'gallery-video') {
        return '相册';
    }
    if (isIOSNativeApp()) {
        return 'VibeDrop 收件箱';
    }
    return '下载';
}

function getDesktopIncomingHistoryHint(saveTarget = '') {
    if (saveTarget === 'gallery' || saveTarget === 'gallery-image' || saveTarget === 'gallery-video') {
        return '已从 Mac 保存到手机相册';
    }
    if (isIOSNativeApp()) {
        return '已从 Mac 保存到 VibeDrop 收件箱';
    }
    return '已从 Mac 保存到手机下载';
}

function getHistoryEntryHint(entry) {
    if (entry.direction === 'desktop_to_mobile') {
        return getDesktopIncomingHistoryHint(entry.saveTarget);
    }

    if (entry.saveTarget === 'desktop-inbox') {
        return '已保存到 VibeDrop 收件箱';
    }

    if (entry.kind === 'image') {
        return '已发送到 Mac 剪贴板';
    }
    if (entry.kind === 'video' || entry.kind === 'media') {
        return '已发送到 Mac';
    }
    if (entry.kind === 'file') {
        return '已保存到 VibeDrop 收件箱';
    }
    return '';
}

function getHistoryEntryTitle(entry) {
    if (isHistoryMediaEntry(entry)) {
        return getHistoryEntryItems(entry).length > 1
            ? '点缩略图直接预览，点卡片空白查看全部'
            : '点缩略图直接预览';
    }
    if (entry.kind === 'file') {
        return '文件记录';
    }
    return '点击复制';
}

function getHistoryStatusLabel(entry) {
    if (entry.direction === 'desktop_to_mobile') {
        if (entry.status === 'success') return '已接收';
        if (entry.status === 'partial') return '部分成功';
        if (entry.status === 'failed') return '失败';
        return '接收中';
    }

    if (entry.status === 'success') return '已送达';
    if (entry.status === 'partial') return '部分成功';
    if (entry.status === 'failed') return '失败';
    return '发送中';
}

function getHistoryKindLabel(kind = 'text') {
    const labels = {
        text: '文字',
        image: '图片',
        video: '视频',
        media: '媒体',
        file: '文件',
    };
    return labels[kind] || kind || '文字';
}

function renderHistoryEntryContent(entry) {
    const items = getHistoryEntryItems(entry);
    if (items.length > 1) {
        const visibleItems = items.slice(0, 4);
        const remaining = items.length - visibleItems.length;
        const cells = visibleItems.map((item, index) => {
            const overlay = item.kind === 'video'
                ? '<span class="history-media-play">▶</span>'
                : '';
            const countBadge = remaining > 0 && index === visibleItems.length - 1
                ? `<span class="history-media-count">+${remaining}</span>`
                : '';
            if (item.thumbnailDataUrl) {
                return `
                    <div class="history-media-cell" data-media-index="${index}">
                        <img class="history-media-thumb" src="${item.thumbnailDataUrl}" alt="${escapeHtml(item.fileName)}">
                        ${overlay}
                        ${countBadge}
                    </div>
                `;
            }
            return `
                <div class="history-media-cell history-media-cell-placeholder" data-media-index="${index}">
                    <span class="history-media-placeholder">${item.kind === 'video' ? '视频' : item.kind === 'image' ? '图片' : '文件'}</span>
                    ${countBadge}
                </div>
            `;
        }).join('');

        return `
            <div class="history-media-stack">
                <div class="history-media-grid">${cells}</div>
                <div class="history-image-meta">
                    <div class="history-text">${escapeHtml(entry.text)}</div>
                    <div class="history-image-hint">${escapeHtml(getHistoryEntryHint(entry))}</div>
                </div>
            </div>
        `;
    }

    if (items.length === 1 && (entry.kind === 'image' || entry.kind === 'video' || entry.kind === 'media')) {
        const item = items[0];
        const thumbContent = item.thumbnailDataUrl
            ? `
                <div class="history-thumb-wrap" data-media-index="0">
                    <img class="history-thumb" src="${item.thumbnailDataUrl}" alt="${escapeHtml(item.fileName || entry.text || '媒体')}">
                    ${item.kind === 'video' ? '<span class="history-media-play history-media-play-single">▶</span>' : ''}
                </div>
            `
            : `<div class="history-file-badge" data-media-index="0">${item.kind === 'video' ? '视频' : '图片'}</div>`;

        return `
            <div class="history-image-row">
                ${thumbContent}
                <div class="history-image-meta">
                    <div class="history-text">${escapeHtml(entry.text)}</div>
                    <div class="history-image-hint">${escapeHtml(getHistoryEntryHint(entry))}</div>
                </div>
            </div>
        `;
    }

    if (entry.kind === 'file') {
        return `
            <div class="history-image-row history-file-row">
                <div class="history-file-badge">文件</div>
                <div class="history-image-meta">
                    <div class="history-text">${escapeHtml(entry.text)}</div>
                    <div class="history-image-hint">${escapeHtml(getHistoryEntryHint(entry))}</div>
                </div>
            </div>
        `;
    }

    return `<div class="history-text">${escapeHtml(entry.text || '')}</div>`;
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
        const hydratedEntry = entry;
        if (filters.device !== 'all' && hydratedEntry.target !== filters.device) {
            return false;
        }

        const entryDate = new Date(hydratedEntry.timestamp);
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

        if (!matchesKind(hydratedEntry, filters)) {
            return false;
        }

        if (!matchesStatus(hydratedEntry, filters)) {
            return false;
        }

        if (!matchesHistorySearch(hydratedEntry, filters)) {
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

function normalizeSearchText(value) {
    return String(value || '')
        .toLowerCase()
        .replace(/\s+/g, ' ')
        .trim();
}

function tokenizeSearchQuery(query) {
    const normalized = normalizeSearchText(query);
    return normalized ? normalized.split(' ') : [];
}

function buildHistorySearchIndex(entry) {
    const items = getHistoryEntryItems(entry);
    return normalizeSearchText([
        entry.text,
        entry.targetAlias,
        entry.targetName,
        entry.targetDeviceName,
        entry.fileName,
        entry.kind,
        entry.status,
        getHistoryKindLabel(entry.kind),
        getHistoryStatusLabel(entry),
        ...items.map((item) => item.fileName),
    ].join(' '));
}

function matchesHistorySearch(entry, filters = currentHistoryFilters) {
    const tokens = tokenizeSearchQuery(filters.query);
    if (!tokens.length) {
        return true;
    }

    const searchIndex = buildHistorySearchIndex(entry);
    return tokens.every((token) => searchIndex.includes(token));
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
    const queryLabel = String(currentHistoryFilters.query || '').trim();
    if (queryLabel) {
        labels.push(`搜索：${queryLabel}`);
    }
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
        video: '视频',
        media: '媒体',
        file: '文件',
    };
    if (currentHistoryFilters.kind !== 'all') {
        labels.push(kindLabels[currentHistoryFilters.kind] || currentHistoryFilters.kind);
    }

    const statusLabels = {
        success: '成功',
        partial: '部分成功',
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
        scheduleHistoryRender();
    });
}

function getHistoryDeviceLabel(deviceId) {
    if (deviceId === 'all') {
        return '';
    }
    const options = getHistoryDeviceOptions();
    return options.find((option) => option.value === deviceId)?.label || deviceId;
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

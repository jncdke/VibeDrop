import AppKit
import Foundation
import SwiftUI
import VibeDropMacRuntime
import VibeDropMacServer
import VibeDropMacStorage
import VibeDropNativeCore

struct MacTransferListItem: Identifiable, Equatable {
    var id: String
    var fileName: String
    var peerName: String
    var status: String
    var detail: String
}

@MainActor
final class MacNativeAppModel: ObservableObject {
    @Published var configuration: MacServerConfiguration?
    @Published var serviceStatus = "启动中"
    @Published var serviceError: String?
    @Published var isAccessibilityTrusted = MacKeyboardInputService().isAccessibilityTrusted
    @Published var connectedClients: [MacConnectedClientSnapshot] = []
    @Published var pendingPairRequests: [PairRequestInfo] = []
    @Published var recentHistory: [HistoryEntry] = []
    @Published var selectedSessionId: UInt64?
    @Published var transferItems: [MacTransferListItem] = []
    @Published var launchAtLoginEnabled = MacLaunchAtLoginController.isEnabled
    @Published var launchAtLoginStatus = MacLaunchAtLoginController.statusText
    @Published var diagnosticEvents: [MacDiagnosticLogEvent] = []
    @Published var diagnosticExportPath: String?
    @Published var historyExportPath: String?

    private var started = false
    private var server: VibeDropMacServer?
    private var database: MacHistoryDatabase?
    private var pairManager: PairRequestManager?
    private var outboundTransfers: MacOutboundTransferRegistry?
    private var outboundService: MacOutboundFileTransferService?
    private var clipboardBroadcastService: MacClipboardBroadcastService?
    private var refreshTimer: Timer?
    private let diagnosticLogStore = try? MacDiagnosticLogStore()
    private var lastConnectedClientKeys: Set<String> = []

    var addressText: String {
        guard let configuration else { return "加载中" }
        return "\(configuration.ip):\(configuration.port)"
    }

    var pinText: String {
        configuration?.pin ?? "加载中"
    }

    var selectedPeer: ConnectedPeer? {
        guard let selectedSessionId else { return connectedClients.first?.peer }
        return connectedClients.first(where: { $0.peer.sessionId == selectedSessionId })?.peer
    }

    var selectedFilePeer: ConnectedPeer? {
        if let selectedPeer, selectedPeer.canReceiveFiles {
            return selectedPeer
        }
        return connectedClients.first(where: { $0.peer.canReceiveFiles })?.peer
    }

    func startIfNeeded() {
        guard !started else { return }
        started = true
        log("app", "start")

        do {
            let configuration = try MacRuntimeConfigurationFactory.load()
            let databaseURL = try MacRuntimePaths.defaultDatabaseURL()
            let database = try MacHistoryDatabase(url: databaseURL)
            importLegacyHistoryIfAvailable(database: database, configuration: configuration)

            let pairManager = PairRequestManager()
            let outboundTransfers = MacOutboundTransferRegistry()
            let runtime = MacRuntimeEffectHandler(
                configuration: configuration,
                historyDatabase: database,
                contentStore: try MacReceivedContentStore(),
                outboundTransfers: outboundTransfers
            )
            let server = VibeDropMacServer(
                configuration: configuration,
                pairManager: pairManager,
                effectHandler: runtime.handler
            )
            try server.start()

            self.configuration = configuration
            self.database = database
            self.pairManager = pairManager
            self.outboundTransfers = outboundTransfers
            self.server = server
            self.outboundService = MacOutboundFileTransferService(
                sender: server,
                transferRegistry: outboundTransfers,
                historyDatabase: database,
                configuration: configuration
            )
            let clipboardBroadcastService = MacClipboardBroadcastService(broadcaster: server)
            clipboardBroadcastService.start()
            self.clipboardBroadcastService = clipboardBroadcastService
            serviceStatus = "服务在线"
            serviceError = nil
            log(
                "service",
                "started",
                [
                    "host": configuration.hostname,
                    "ip": configuration.ip,
                    "port": "\(configuration.port)"
                ]
            )
            refresh()
            refreshTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
        } catch {
            serviceStatus = "启动失败"
            serviceError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            log("service", "start_failed", ["error": serviceError ?? "unknown"])
        }
    }

    func refresh() {
        isAccessibilityTrusted = MacKeyboardInputService().isAccessibilityTrusted
        launchAtLoginEnabled = MacLaunchAtLoginController.isEnabled
        launchAtLoginStatus = MacLaunchAtLoginController.statusText
        connectedClients = server?.connectedClientSnapshots ?? []
        logClientChanges(connectedClients)
        pendingPairRequests = pairManager?.pendingRequests() ?? []
        if selectedSessionId == nil || !connectedClients.contains(where: { $0.peer.sessionId == selectedSessionId }) {
            selectedSessionId = connectedClients.first(where: { $0.peer.canReceiveFiles })?.peer.sessionId
                ?? connectedClients.first?.peer.sessionId
        }
        recentHistory = (try? database?.fetchAll()) ?? []
        diagnosticEvents = diagnosticLogStore?.recent(limit: 60) ?? []
        processPendingFinderShareRequests()
    }

    func approvePairRequest(_ request: PairRequestInfo) {
        do {
            try pairManager?.approve(request.requestId)
            log("pair", "approved", ["clientName": request.clientName, "requestId": request.requestId])
            refresh()
        } catch {
            serviceError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            log("pair", "approve_failed", ["clientName": request.clientName, "error": serviceError ?? "unknown"])
        }
    }

    func rejectPairRequest(_ request: PairRequestInfo) {
        do {
            try pairManager?.reject(request.requestId)
            log("pair", "rejected", ["clientName": request.clientName, "requestId": request.requestId])
            refresh()
        } catch {
            serviceError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            log("pair", "reject_failed", ["clientName": request.clientName, "error": serviceError ?? "unknown"])
        }
    }

    func sendFiles(_ urls: [URL]) {
        guard let outboundService else {
            serviceError = "发送服务还没有启动"
            log("transfer", "send_blocked", ["reason": "service_not_ready", "itemCount": "\(urls.count)"])
            return
        }
        guard let peer = selectedFilePeer else {
            serviceError = "当前没有可接收文件的手机"
            log("transfer", "send_blocked", ["reason": "no_peer", "itemCount": "\(urls.count)"])
            return
        }

        let itemId = UUID().uuidString
        log(
            "transfer",
            "send_start",
            [
                "peer": peer.deviceName,
                "itemCount": "\(urls.count)"
            ]
        )
        transferItems.insert(
            MacTransferListItem(
                id: itemId,
                fileName: transferDisplayName(for: urls),
                peerName: peer.deviceName,
                status: "sending",
                detail: urls.count > 1 ? "正在准备多文件发送" : "正在发送到手机"
            ),
            at: 0
        )
        Task.detached {
            do {
                let reports = try outboundService.sendURLs(urls, to: peer)
                let failed = reports.first(where: { $0.status != "success" })
                await MainActor.run {
                    if let failed {
                        self.log(
                            "transfer",
                            "send_failed",
                            [
                                "peer": peer.deviceName,
                                "error": failed.error ?? "unknown",
                                "itemCount": "\(reports.count)"
                            ]
                        )
                        self.updateTransfer(
                            id: itemId,
                            status: "failed",
                            detail: failed.error ?? "发送失败"
                        )
                    } else {
                        self.log(
                            "transfer",
                            "send_success",
                            [
                                "peer": peer.deviceName,
                                "itemCount": "\(reports.count)"
                            ]
                        )
                        self.updateTransfer(
                            id: itemId,
                            status: "success",
                            detail: reports.first?.savedPath.map { "已保存到手机：\($0)" } ?? "发送完成"
                        )
                    }
                    self.refresh()
                }
            } catch {
                let message = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                await MainActor.run {
                    self.log("transfer", "send_failed", ["peer": peer.deviceName, "error": message])
                    self.updateTransfer(id: itemId, status: "failed", detail: message)
                    self.refresh()
                }
            }
        }
    }

    func requestAccessibilityPermission() {
        _ = MacKeyboardInputService.requestAccessibilityTrust(prompt: true)
        isAccessibilityTrusted = MacKeyboardInputService().isAccessibilityTrusted
        log("permission", "accessibility_check", ["trusted": "\(isAccessibilityTrusted)"])
    }

    func openAccessibilitySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") else {
            return
        }
        NSWorkspace.shared.open(url)
        log("permission", "open_accessibility_settings")
    }

    func setLaunchAtLoginEnabled(_ enabled: Bool) {
        do {
            try MacLaunchAtLoginController.setEnabled(enabled)
            log("login-item", enabled ? "enable_requested" : "disable_requested")
            refresh()
        } catch {
            launchAtLoginEnabled = MacLaunchAtLoginController.isEnabled
            launchAtLoginStatus = MacLaunchAtLoginController.statusText
            serviceError = "开机启动设置失败：\((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)"
            log("login-item", "update_failed", ["enabled": "\(enabled)", "error": serviceError ?? "unknown"])
        }
    }

    func openLoginItemsSettings() {
        MacLaunchAtLoginController.openSystemSettings()
    }

    func copyAddress() {
        copy(addressText)
        log("ui", "copy_address")
    }

    func copyPin() {
        copy(pinText)
        log("ui", "copy_pin")
    }

    func refreshDiagnostics() {
        diagnosticEvents = diagnosticLogStore?.recent(limit: 60) ?? []
    }

    func exportDiagnostics() {
        do {
            let url = try diagnosticLogStore?.exportSnapshot(
                configuration: configuration,
                serviceStatus: serviceStatus,
                serviceError: serviceError,
                isAccessibilityTrusted: isAccessibilityTrusted,
                launchAtLoginStatus: launchAtLoginStatus,
                connectedClients: connectedClients,
                pendingPairRequests: pendingPairRequests,
                recentHistoryCount: recentHistory.count
            )
            guard let url else {
                serviceError = "诊断日志尚未初始化"
                return
            }
            diagnosticExportPath = url.path
            log("diagnostics", "exported", ["path": url.lastPathComponent])
            NSWorkspace.shared.activateFileViewerSelecting([url])
            refresh()
        } catch {
            serviceError = "导出诊断失败：\((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)"
            log("diagnostics", "export_failed", ["error": serviceError ?? "unknown"])
        }
    }

    func exportHistory() {
        guard let database else {
            serviceError = "历史数据库尚未初始化"
            return
        }
        do {
            let entries = try database.fetchAll()
            guard !entries.isEmpty else {
                serviceError = "没有历史可导出"
                return
            }
            let archive = MacHistoryExportArchive(
                exportedAt: Date(),
                sourceDevice: configuration.map {
                    DeviceIdentity(
                        deviceId: $0.serverId,
                        displayName: $0.hostname,
                        role: "desktop",
                        ip: $0.ip,
                        port: $0.port
                    )
                },
                history: entries
            )
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(archive)
            let fileName = "vibedrop_macos_history_\(Self.fileStamp()).json"
            let url = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
                .appendingPathComponent(fileName)
            try data.write(to: url, options: .atomic)
            historyExportPath = url.path
            log("history", "exported", ["count": "\(entries.count)", "path": url.lastPathComponent])
            NSWorkspace.shared.activateFileViewerSelecting([url])
            refresh()
        } catch {
            serviceError = "导出历史失败：\((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)"
            log("history", "export_failed", ["error": serviceError ?? "unknown"])
        }
    }

    private func updateTransfer(id: String, status: String, detail: String) {
        guard let index = transferItems.firstIndex(where: { $0.id == id }) else { return }
        transferItems[index].status = status
        transferItems[index].detail = detail
    }

    private func transferDisplayName(for urls: [URL]) -> String {
        if urls.count == 1 {
            return urls[0].lastPathComponent
        }
        return "\(urls.count) 项"
    }

    private func copy(_ text: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    private func log(_ scope: String, _ event: String, _ detail: [String: String] = [:]) {
        diagnosticLogStore?.append(scope: scope, event: event, detail: detail)
        diagnosticEvents = diagnosticLogStore?.recent(limit: 60) ?? []
    }

    private func logClientChanges(_ clients: [MacConnectedClientSnapshot]) {
        let keys = Set(clients.map { "\($0.peer.sessionId):\($0.peer.deviceId):\($0.peer.deviceRole)" })
        let added = keys.subtracting(lastConnectedClientKeys)
        let removed = lastConnectedClientKeys.subtracting(keys)
        guard !added.isEmpty || !removed.isEmpty else { return }
        log(
            "clients",
            "changed",
            [
                "connected": "\(clients.count)",
                "added": "\(added.count)",
                "removed": "\(removed.count)"
            ]
        )
        lastConnectedClientKeys = keys
    }

    private func processPendingFinderShareRequests() {
        guard outboundService != nil, selectedFilePeer != nil else { return }
        let queueDirectory = Self.finderShareRequestsDirectory()
        guard let entries = try? FileManager.default.contentsOfDirectory(
            at: queueDirectory,
            includingPropertiesForKeys: nil
        ) else { return }
        let requests = entries
            .filter { $0.pathExtension == "json" }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
        guard let requestURL = requests.first else { return }

        do {
            let request = try Self.loadQueuedShareRequest(at: requestURL)
            let urls = request.paths.map { URL(fileURLWithPath: $0) }
                .filter { FileManager.default.fileExists(atPath: $0.path) }
            try? FileManager.default.removeItem(at: requestURL)
            guard !urls.isEmpty else {
                log("share", "request_dropped", ["reason": "no_existing_paths"])
                return
            }
            log(
                "share",
                "request_accepted",
                [
                    "source": request.source ?? "unknown",
                    "itemCount": "\(urls.count)"
                ]
            )
            NSApp.activate(ignoringOtherApps: true)
            sendFiles(urls)
        } catch {
            try? FileManager.default.removeItem(at: requestURL)
            log("share", "request_invalid", ["error": error.localizedDescription])
        }
    }

    private static func fileStamp() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter.string(from: Date())
    }

    private static func finderShareRequestsDirectory() -> URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent(".vibedrop", isDirectory: true)
            .appendingPathComponent("finder-share-requests", isDirectory: true)
    }

    private static func loadQueuedShareRequest(at url: URL) throws -> QueuedFinderShareRequest {
        let data = try Data(contentsOf: url)
        return try JSONDecoder().decode(QueuedFinderShareRequest.self, from: data)
    }

    private func importLegacyHistoryIfAvailable(
        database: MacHistoryDatabase,
        configuration: MacServerConfiguration
    ) {
        let receiver = DeviceIdentity(
            deviceId: configuration.serverId,
            displayName: configuration.hostname,
            role: "desktop",
            ip: configuration.ip,
            port: configuration.port
        )
        let home = FileManager.default.homeDirectoryForCurrentUser
        let candidates = [
            home.appendingPathComponent(".vibedrop/history.jsonl"),
            home.appendingPathComponent(".voicedrop/history.jsonl")
        ]
        for url in candidates {
            _ = try? database.importLegacyJsonl(at: url, receiver: receiver)
        }
    }
}

private struct MacHistoryExportArchive: Codable {
    var schemaVersion = 1
    var app = "VibeDrop Native macOS"
    var exportedAt: Date
    var sourceDevice: DeviceIdentity?
    var history: [HistoryEntry]
}

private struct QueuedFinderShareRequest: Codable {
    var paths: [String]
    var source: String?
}

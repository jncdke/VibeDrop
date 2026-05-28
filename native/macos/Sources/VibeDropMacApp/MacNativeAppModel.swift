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

    private var started = false
    private var server: VibeDropMacServer?
    private var database: MacHistoryDatabase?
    private var pairManager: PairRequestManager?
    private var outboundTransfers: MacOutboundTransferRegistry?
    private var outboundService: MacOutboundFileTransferService?
    private var clipboardBroadcastService: MacClipboardBroadcastService?
    private var refreshTimer: Timer?

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

    func startIfNeeded() {
        guard !started else { return }
        started = true

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
            refresh()
            refreshTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.refresh() }
            }
        } catch {
            serviceStatus = "启动失败"
            serviceError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    func refresh() {
        isAccessibilityTrusted = MacKeyboardInputService().isAccessibilityTrusted
        connectedClients = server?.connectedClientSnapshots ?? []
        pendingPairRequests = pairManager?.pendingRequests() ?? []
        if selectedSessionId == nil || !connectedClients.contains(where: { $0.peer.sessionId == selectedSessionId }) {
            selectedSessionId = connectedClients.first(where: { $0.peer.canReceiveFiles })?.peer.sessionId
                ?? connectedClients.first?.peer.sessionId
        }
        recentHistory = (try? database?.fetchRecent(limit: 80)) ?? []
    }

    func approvePairRequest(_ request: PairRequestInfo) {
        do {
            try pairManager?.approve(request.requestId)
            refresh()
        } catch {
            serviceError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    func rejectPairRequest(_ request: PairRequestInfo) {
        do {
            try pairManager?.reject(request.requestId)
            refresh()
        } catch {
            serviceError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    func sendFiles(_ urls: [URL]) {
        guard let outboundService else {
            serviceError = "发送服务还没有启动"
            return
        }
        guard let peer = selectedPeer else {
            serviceError = "当前没有可接收文件的手机"
            return
        }

        let itemId = UUID().uuidString
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
                        self.updateTransfer(
                            id: itemId,
                            status: "failed",
                            detail: failed.error ?? "发送失败"
                        )
                    } else {
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
                    self.updateTransfer(id: itemId, status: "failed", detail: message)
                    self.refresh()
                }
            }
        }
    }

    func requestAccessibilityPermission() {
        _ = MacKeyboardInputService.requestAccessibilityTrust(prompt: true)
        isAccessibilityTrusted = MacKeyboardInputService().isAccessibilityTrusted
    }

    func openAccessibilitySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") else {
            return
        }
        NSWorkspace.shared.open(url)
    }

    func copyAddress() {
        copy(addressText)
    }

    func copyPin() {
        copy(pinText)
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

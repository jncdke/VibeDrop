import Foundation
import VibeDropMacServer
import VibeDropMacStorage
import VibeDropNativeCore

public final class MacRuntimeEffectHandler: @unchecked Sendable {
    private let configuration: MacServerConfiguration
    private let historyDatabase: MacHistoryDatabase?
    private let inputController: MacInputControlling
    private let imageClipboard: MacImageClipboardControlling
    private let contentStore: MacReceivedContentStore?
    private let outboundTransfers: MacOutboundTransferRegistry?
    private let idGenerator: @Sendable () -> String
    private let now: @Sendable () -> Date
    private let incomingFileMetadataLock = NSLock()
    private var incomingFileMetadataByTransferId: [String: IncomingFileHistoryMetadata] = [:]

    public init(
        configuration: MacServerConfiguration,
        historyDatabase: MacHistoryDatabase?,
        inputController: MacInputControlling = MacKeyboardInputService(),
        imageClipboard: MacImageClipboardControlling = MacImageClipboardService(),
        contentStore: MacReceivedContentStore? = nil,
        outboundTransfers: MacOutboundTransferRegistry? = nil,
        idGenerator: @escaping @Sendable () -> String = { UUID().uuidString },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.configuration = configuration
        self.historyDatabase = historyDatabase
        self.inputController = inputController
        self.imageClipboard = imageClipboard
        self.contentStore = contentStore
        self.outboundTransfers = outboundTransfers
        self.idGenerator = idGenerator
        self.now = now
    }

    public var handler: MacServerEffectHandler {
        { [weak self] effect in
            self?.handle(effect) ?? MacServerDefaultEffectHandler.preview(effect)
        }
    }

    public func handle(_ effect: MacServerEffect) -> [MacServerOutbound] {
        switch effect {
        case .authenticated:
            return []
        case let .typeText(text, peer):
            return performTextInput(text: text, pressEnter: false, peer: peer)
        case let .typeTextAndEnter(text, peer):
            return performTextInput(text: text, pressEnter: true, peer: peer)
        case let .pressEnter(peer):
            do {
                try inputController.pressEnter()
                return [.status(MacServerStatusEnvelope(status: "ok"))]
            } catch {
                recordTextHistory(
                    text: nil,
                    pressEnter: true,
                    peer: peer,
                    status: "failed",
                    error: error
                )
                return [.status(.runtimeError(error))]
            }
        case let .imageClipboard(message, peer):
            return performImageClipboard(message, peer: peer)
        case let .legacyFileDownload(message, peer):
            return performLegacyFileDownload(message, peer: peer)
        case let .incomingFileStart(message, _):
            return beginIncomingFile(message)
        case let .incomingFileChunk(message, _):
            return appendIncomingFileChunk(message)
        case let .incomingFileComplete(message, peer):
            return finishIncomingFile(message, peer: peer)
        case let .incomingFileSaved(transferId, savedPath, _):
            guard let outboundTransfers else {
                return MacServerDefaultEffectHandler.preview(effect)
            }
            outboundTransfers.resolveSaved(transferId: transferId, savedPath: savedPath)
            return []
        case let .incomingFileError(transferId, error, _):
            guard let outboundTransfers else {
                return MacServerDefaultEffectHandler.preview(effect)
            }
            outboundTransfers.resolveFailed(transferId: transferId, error: error)
            return []
        }
    }

    private func performTextInput(
        text: String,
        pressEnter: Bool,
        peer: ConnectedPeer
    ) -> [MacServerOutbound] {
        do {
            try inputController.typeText(text)
            if pressEnter {
                try inputController.pressEnter()
            }
            recordTextHistory(
                text: text,
                pressEnter: pressEnter,
                peer: peer,
                status: "success",
                error: nil
            )
            return [.status(MacServerStatusEnvelope(status: "ok"))]
        } catch {
            recordTextHistory(
                text: text,
                pressEnter: pressEnter,
                peer: peer,
                status: "failed",
                error: error
            )
            return [.status(.runtimeError(error))]
        }
    }

    private func performImageClipboard(
        _ message: VibeDropMessage,
        peer: ConnectedPeer
    ) -> [MacServerOutbound] {
        guard let contentStore else {
            return [.status(.runtimeError(MacReceivedContentError.fileOperationFailed("接收目录不可用")))]
        }
        do {
            let (savedImage, imageData) = try contentStore.saveImage(
                imageBase64: message.imageBase64 ?? "",
                fileName: message.fileName,
                mimeType: message.mimeType
            )
            try imageClipboard.writeImage(data: imageData, mimeType: message.mimeType)
            recordMediaHistory(
                kind: "image",
                label: "图片",
                fileName: savedImage.fileName,
                mimeType: message.mimeType ?? "image/*",
                sizeBytes: savedImage.sizeBytes,
                path: savedImage.filePath,
                thumbnailDataURL: savedImage.thumbnailDataURL,
                peer: peer,
                status: "success",
                saveTarget: "clipboard"
            )
            return [.status(MacServerStatusEnvelope(status: "ok"))]
        } catch {
            recordMediaFailure(
                kind: "image",
                text: message.fileName.map { "[图片] \($0)" },
                peer: peer,
                error: error,
                saveTarget: "clipboard"
            )
            return [.status(.runtimeError(error))]
        }
    }

    private func performLegacyFileDownload(
        _ message: VibeDropMessage,
        peer: ConnectedPeer
    ) -> [MacServerOutbound] {
        guard let contentStore else {
            return [.status(.runtimeError(MacReceivedContentError.fileOperationFailed("接收目录不可用")))]
        }
        do {
            let savedFile = try contentStore.saveLegacyFile(
                fileBase64: message.fileBase64 ?? "",
                fileName: message.fileName,
                mimeType: message.mimeType
            )
            recordMediaHistory(
                kind: "file",
                label: "文件",
                fileName: savedFile.fileName,
                mimeType: savedFile.mimeType,
                sizeBytes: savedFile.sizeBytes,
                path: savedFile.filePath,
                thumbnailDataURL: nil,
                peer: peer,
                status: "success",
                saveTarget: "desktop_inbox"
            )
            return [.status(MacServerStatusEnvelope(status: "ok"))]
        } catch {
            recordMediaFailure(
                kind: "file",
                text: message.fileName.map { "[文件] \($0)" },
                peer: peer,
                error: error,
                saveTarget: "desktop_inbox"
            )
            return [.status(.runtimeError(error))]
        }
    }

    private func beginIncomingFile(_ message: VibeDropMessage) -> [MacServerOutbound] {
        guard let contentStore else {
            return [.status(.runtimeError(MacReceivedContentError.fileOperationFailed("接收目录不可用")))]
        }
        do {
            try contentStore.beginIncomingFile(
                transferId: message.transferId,
                fileName: message.fileName,
                mimeType: message.mimeType,
                sizeBytes: message.sizeBytes
            )
            storeIncomingFileMetadata(message)
            return [.status(MacServerStatusEnvelope(status: "ok"))]
        } catch {
            return incomingFileError(transferId: message.transferId, error: error)
        }
    }

    private func appendIncomingFileChunk(_ message: VibeDropMessage) -> [MacServerOutbound] {
        guard let contentStore else {
            return [.status(.runtimeError(MacReceivedContentError.fileOperationFailed("接收目录不可用")))]
        }
        do {
            try contentStore.appendIncomingFileChunk(
                transferId: message.transferId,
                chunkBase64: message.chunkBase64
            )
            return []
        } catch {
            contentStore.cancelIncomingFile(transferId: message.transferId)
            removeIncomingFileMetadata(transferId: message.transferId)
            return incomingFileError(transferId: message.transferId, error: error)
        }
    }

    private func finishIncomingFile(
        _ message: VibeDropMessage,
        peer: ConnectedPeer
    ) -> [MacServerOutbound] {
        guard let contentStore else {
            return [.status(.runtimeError(MacReceivedContentError.fileOperationFailed("接收目录不可用")))]
        }
        let historyMetadata = takeIncomingFileMetadata(transferId: message.transferId)
        do {
            let savedFile = try contentStore.finishIncomingFile(transferId: message.transferId)
            if historyMetadata?.sessionId != nil || normalizedHistorySessionId(message) != nil {
                recordIncomingSessionFile(
                    savedFile: savedFile,
                    message: message,
                    metadata: historyMetadata,
                    peer: peer
                )
            } else {
                recordMediaHistory(
                    kind: kindFromMime(savedFile.mimeType),
                    label: labelFromMime(savedFile.mimeType),
                    fileName: savedFile.fileName,
                    mimeType: savedFile.mimeType,
                    sizeBytes: savedFile.sizeBytes,
                    path: savedFile.filePath,
                    thumbnailDataURL: nil,
                    peer: peer,
                    status: "success",
                    saveTarget: "desktop_inbox"
                )
            }
            return [
                .json(
                    [
                        "action": VibeDropAction.incomingFileSaved.rawValue,
                        "transfer_id": message.transferId ?? "",
                        "saved_path": savedFile.filePath
                    ]
                )
            ]
        } catch {
            contentStore.cancelIncomingFile(transferId: message.transferId)
            recordMediaFailure(
                kind: "file",
                text: message.fileName.map { "[文件] \($0)" },
                peer: peer,
                error: error,
                saveTarget: "desktop_inbox"
            )
            return incomingFileError(transferId: message.transferId, error: error)
        }
    }

    private func incomingFileError(transferId: String?, error: Error) -> [MacServerOutbound] {
        [
            .json(
                [
                    "action": VibeDropAction.incomingFileError.rawValue,
                    "transfer_id": transferId ?? "",
                    "error": (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                ]
            )
        ]
    }

    private func recordTextHistory(
        text: String?,
        pressEnter: Bool,
        peer: ConnectedPeer,
        status: String,
        error: Error?
    ) {
        guard let historyDatabase else { return }
        let entry = HistoryEntry(
            id: "native-mac:\(idGenerator())",
            timestamp: now(),
            direction: "mobile_to_desktop",
            kind: "text",
            status: status,
            text: text,
            sender: DeviceIdentity(
                deviceId: peer.deviceId,
                baseDeviceId: peer.baseDeviceId,
                displayName: peer.deviceName,
                role: peer.deviceRole
            ),
            receiver: DeviceIdentity(
                deviceId: configuration.serverId,
                displayName: configuration.hostname,
                role: "desktop",
                ip: configuration.ip,
                port: configuration.port
            ),
            saveTarget: pressEnter ? "type_enter" : "type"
        )
        try? historyDatabase.insert(entry, rawJSON: error.map { String(describing: $0) })
    }

    private func recordMediaHistory(
        kind: String,
        label: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Int64,
        path: String,
        thumbnailDataURL: String?,
        peer: ConnectedPeer,
        status: String,
        saveTarget: String
    ) {
        guard let historyDatabase else { return }
        let entryId = "native-mac:\(idGenerator())"
        let item = HistoryItem(
            id: "\(entryId):item:0",
            kind: kind,
            fileName: fileName,
            mimeType: mimeType,
            sizeBytes: sizeBytes,
            localPath: path,
            savedPath: path,
            thumbnailDataUrl: thumbnailDataURL,
            status: status,
            error: nil
        )
        let entry = HistoryEntry(
            id: entryId,
            timestamp: now(),
            direction: "mobile_to_desktop",
            kind: kind,
            status: status,
            text: "[\(label)] \(fileName)",
            sender: DeviceIdentity(
                deviceId: peer.deviceId,
                baseDeviceId: peer.baseDeviceId,
                displayName: peer.deviceName,
                role: peer.deviceRole
            ),
            receiver: DeviceIdentity(
                deviceId: configuration.serverId,
                displayName: configuration.hostname,
                role: "desktop",
                ip: configuration.ip,
                port: configuration.port
            ),
            itemCount: 1,
            saveTarget: saveTarget,
            items: [item]
        )
        try? historyDatabase.insert(entry)
    }

    private func recordIncomingSessionFile(
        savedFile: MacSavedFile,
        message: VibeDropMessage,
        metadata: IncomingFileHistoryMetadata?,
        peer: ConnectedPeer
    ) {
        guard let historyDatabase,
              let sessionId = metadata?.sessionId ?? normalizedHistorySessionId(message) else { return }
        let entryId = "native-mac-inbound:\(sessionId)"
        let existing = try? historyDatabase.fetchEntry(id: entryId)
        let itemIndex = max(0, metadata?.itemIndex ?? message.historyItemIndex ?? 0)
        let itemCount = max(metadata?.itemCount ?? message.historyItemCount ?? 1, itemIndex + 1, existing?.itemCount ?? 0)
        var items = existing?.items ?? []
        while items.count < itemCount {
            let index = items.count
            items.append(
                HistoryItem(
                    id: "\(entryId):item:\(index)",
                    kind: "file",
                    status: "pending"
                )
            )
        }

        let kind = kindFromMime(savedFile.mimeType)
        items[itemIndex] = HistoryItem(
            id: "\(entryId):item:\(itemIndex)",
            kind: kind,
            fileName: savedFile.fileName,
            mimeType: savedFile.mimeType,
            sizeBytes: savedFile.sizeBytes,
            localPath: savedFile.filePath,
            savedPath: savedFile.filePath,
            thumbnailDataUrl: nil,
            status: "success",
            error: nil
        )

        let summary = historyItemsSummary(items)
        let entry = HistoryEntry(
            id: entryId,
            timestamp: existing?.timestamp ?? now(),
            direction: "mobile_to_desktop",
            kind: summary.kind,
            status: computeSessionStatus(items),
            text: summary.text,
            sender: DeviceIdentity(
                deviceId: peer.deviceId,
                baseDeviceId: peer.baseDeviceId,
                displayName: peer.deviceName,
                role: peer.deviceRole
            ),
            receiver: DeviceIdentity(
                deviceId: configuration.serverId,
                displayName: configuration.hostname,
                role: "desktop",
                ip: configuration.ip,
                port: configuration.port
            ),
            sessionId: sessionId,
            itemCount: itemCount,
            saveTarget: metadata?.saveTarget ?? message.saveTarget ?? "desktop_inbox",
            items: items
        )
        try? historyDatabase.insert(entry)
    }

    private func recordMediaFailure(
        kind: String,
        text: String?,
        peer: ConnectedPeer,
        error: Error,
        saveTarget: String
    ) {
        guard let historyDatabase else { return }
        let entry = HistoryEntry(
            id: "native-mac:\(idGenerator())",
            timestamp: now(),
            direction: "mobile_to_desktop",
            kind: kind,
            status: "failed",
            text: text,
            sender: DeviceIdentity(
                deviceId: peer.deviceId,
                baseDeviceId: peer.baseDeviceId,
                displayName: peer.deviceName,
                role: peer.deviceRole
            ),
            receiver: DeviceIdentity(
                deviceId: configuration.serverId,
                displayName: configuration.hostname,
                role: "desktop",
                ip: configuration.ip,
                port: configuration.port
            ),
            saveTarget: saveTarget
        )
        try? historyDatabase.insert(entry, rawJSON: String(describing: error))
    }

    private func normalizedHistorySessionId(_ message: VibeDropMessage) -> String? {
        message.historySessionId?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
    }

    private func storeIncomingFileMetadata(_ message: VibeDropMessage) {
        guard let transferId = message.transferId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
            return
        }
        guard let sessionId = normalizedHistorySessionId(message) else {
            removeIncomingFileMetadata(transferId: transferId)
            return
        }
        incomingFileMetadataLock.lock()
        incomingFileMetadataByTransferId[transferId] = IncomingFileHistoryMetadata(
            sessionId: sessionId,
            itemIndex: message.historyItemIndex,
            itemCount: message.historyItemCount,
            saveTarget: message.saveTarget
        )
        incomingFileMetadataLock.unlock()
    }

    private func takeIncomingFileMetadata(transferId: String?) -> IncomingFileHistoryMetadata? {
        guard let transferId = transferId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
            return nil
        }
        incomingFileMetadataLock.lock()
        let metadata = incomingFileMetadataByTransferId.removeValue(forKey: transferId)
        incomingFileMetadataLock.unlock()
        return metadata
    }

    private func removeIncomingFileMetadata(transferId: String?) {
        _ = takeIncomingFileMetadata(transferId: transferId)
    }

    private func computeSessionStatus(_ items: [HistoryItem]) -> String {
        if items.isEmpty { return "pending" }
        if items.contains(where: { ($0.status ?? "").isEmpty || $0.status == "pending" }) {
            return "pending"
        }
        let successCount = items.filter { $0.status == "success" }.count
        let failedCount = items.filter { $0.status == "failed" }.count
        if failedCount == 0 { return "success" }
        if successCount == 0 { return "failed" }
        return "partial"
    }

    private func historyItemsSummary(_ items: [HistoryItem]) -> (kind: String, text: String) {
        if items.count == 1 {
            let item = items[0]
            let kind = item.kind
            return (kind, "[\(labelFromKind(kind))] \(item.fileName ?? "文件")")
        }
        let imageCount = items.filter { $0.kind == "image" }.count
        let videoCount = items.filter { $0.kind == "video" }.count
        if imageCount == items.count {
            return ("image", "[图片] \(items.count) 张")
        }
        if videoCount == items.count {
            return ("video", "[视频] \(items.count) 个")
        }
        if imageCount + videoCount == items.count {
            return ("media", "[媒体] \(items.count) 项")
        }
        return ("file", "[文件] \(items.count) 项")
    }

    private func kindFromMime(_ mimeType: String) -> String {
        if mimeType.hasPrefix("image/") { return "image" }
        if mimeType.hasPrefix("video/") { return "video" }
        return "file"
    }

    private func labelFromMime(_ mimeType: String) -> String {
        if mimeType.hasPrefix("image/") { return "图片" }
        if mimeType.hasPrefix("video/") { return "视频" }
        return "文件"
    }

    private func labelFromKind(_ kind: String) -> String {
        switch kind {
        case "image":
            return "图片"
        case "video":
            return "视频"
        case "media":
            return "媒体"
        default:
            return "文件"
        }
    }
}

private extension MacServerStatusEnvelope {
    static func runtimeError(_ error: Error) -> MacServerStatusEnvelope {
        MacServerStatusEnvelope(
            status: "error",
            error: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        )
    }
}

private struct IncomingFileHistoryMetadata: Sendable {
    var sessionId: String
    var itemIndex: Int?
    var itemCount: Int?
    var saveTarget: String?
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}

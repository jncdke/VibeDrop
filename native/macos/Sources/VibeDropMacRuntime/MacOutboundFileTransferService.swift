import Foundation
import UniformTypeIdentifiers
import VibeDropMacServer
import VibeDropMacStorage
import VibeDropNativeCore

public struct MacOutboundFileTransferReport: Equatable, Sendable {
    public var transferId: String
    public var fileName: String
    public var mimeType: String
    public var sizeBytes: Int64
    public var chunksSent: Int
    public var status: String
    public var savedPath: String?
    public var error: String?

    public init(
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Int64,
        chunksSent: Int,
        status: String,
        savedPath: String? = nil,
        error: String? = nil
    ) {
        self.transferId = transferId
        self.fileName = fileName
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.chunksSent = chunksSent
        self.status = status
        self.savedPath = savedPath
        self.error = error
    }
}

public final class MacOutboundFileTransferService: @unchecked Sendable {
    public static let defaultChunkSize = 192 * 1024
    public static let defaultAckTimeoutSeconds: TimeInterval = 90

    private let sender: MacServerMessageSending
    private let transferRegistry: MacOutboundTransferRegistry
    private let historyDatabase: MacHistoryDatabase?
    private let configuration: MacServerConfiguration
    private let chunkSize: Int
    private let ackTimeoutSeconds: TimeInterval
    private let idGenerator: @Sendable () -> String
    private let now: @Sendable () -> Date

    public init(
        sender: MacServerMessageSending,
        transferRegistry: MacOutboundTransferRegistry,
        historyDatabase: MacHistoryDatabase?,
        configuration: MacServerConfiguration,
        chunkSize: Int = MacOutboundFileTransferService.defaultChunkSize,
        ackTimeoutSeconds: TimeInterval = MacOutboundFileTransferService.defaultAckTimeoutSeconds,
        idGenerator: @escaping @Sendable () -> String = { UUID().uuidString },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.sender = sender
        self.transferRegistry = transferRegistry
        self.historyDatabase = historyDatabase
        self.configuration = configuration
        self.chunkSize = chunkSize
        self.ackTimeoutSeconds = ackTimeoutSeconds
        self.idGenerator = idGenerator
        self.now = now
    }

    public func sendFile(
        at fileURL: URL,
        to peer: ConnectedPeer
    ) throws -> MacOutboundFileTransferReport {
        let metadata = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        guard let type = metadata[.type] as? FileAttributeType, type == .typeRegular else {
            throw MacOutboundFileTransferError.notRegularFile
        }
        let sizeBytes = (metadata[.size] as? NSNumber)?.int64Value ?? 0
        let fileName = safeFileName(fileURL.lastPathComponent)
        let mimeType = mimeTypeForFileName(fileName)
        let transferId = "native-mac-\(idGenerator())"
        transferRegistry.register(transferId)

        do {
            try send(
                OutboundFileStartMessage(
                    transferId: transferId,
                    fileName: fileName,
                    mimeType: mimeType,
                    sizeBytes: sizeBytes
                ),
                to: peer.sessionId
            )

            let chunksSent = try sendChunks(
                fileURL: fileURL,
                transferId: transferId,
                sessionId: peer.sessionId
            )
            try send(OutboundFileCompleteMessage(transferId: transferId), to: peer.sessionId)

            let result = transferRegistry.wait(
                transferId: transferId,
                timeoutSeconds: ackTimeoutSeconds
            )
            let report = report(
                transferId: transferId,
                fileName: fileName,
                mimeType: mimeType,
                sizeBytes: sizeBytes,
                chunksSent: chunksSent,
                result: result
            )
            recordHistory(report: report, fileURL: fileURL, peer: peer)
            return report
        } catch {
            transferRegistry.resolveFailed(transferId: transferId, error: error.localizedDescription)
            let report = MacOutboundFileTransferReport(
                transferId: transferId,
                fileName: fileName,
                mimeType: mimeType,
                sizeBytes: sizeBytes,
                chunksSent: 0,
                status: "failed",
                error: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            )
            recordHistory(report: report, fileURL: fileURL, peer: peer)
            throw error
        }
    }

    private func sendChunks(
        fileURL: URL,
        transferId: String,
        sessionId: UInt64
    ) throws -> Int {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var count = 0
        while true {
            let chunk = try handle.read(upToCount: chunkSize) ?? Data()
            if chunk.isEmpty { break }
            try send(
                OutboundFileChunkMessage(
                    transferId: transferId,
                    chunkBase64: chunk.base64EncodedString()
                ),
                to: sessionId
            )
            count += 1
        }
        return count
    }

    private func send<T: Encodable>(_ message: T, to sessionId: UInt64) throws {
        let data = try JSONEncoder().encode(message)
        try sender.sendJSONData(data, to: sessionId)
    }

    private func report(
        transferId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Int64,
        chunksSent: Int,
        result: MacOutboundTransferResult
    ) -> MacOutboundFileTransferReport {
        switch result {
        case let .saved(path):
            return MacOutboundFileTransferReport(
                transferId: transferId,
                fileName: fileName,
                mimeType: mimeType,
                sizeBytes: sizeBytes,
                chunksSent: chunksSent,
                status: "success",
                savedPath: path
            )
        case let .failed(message):
            return MacOutboundFileTransferReport(
                transferId: transferId,
                fileName: fileName,
                mimeType: mimeType,
                sizeBytes: sizeBytes,
                chunksSent: chunksSent,
                status: "failed",
                error: message
            )
        case .timedOut:
            return MacOutboundFileTransferReport(
                transferId: transferId,
                fileName: fileName,
                mimeType: mimeType,
                sizeBytes: sizeBytes,
                chunksSent: chunksSent,
                status: "failed",
                error: "等待手机保存结果超时"
            )
        }
    }

    private func recordHistory(
        report: MacOutboundFileTransferReport,
        fileURL: URL,
        peer: ConnectedPeer
    ) {
        guard let historyDatabase else { return }
        let kind = kindFromMime(report.mimeType)
        let label = labelFromKind(kind)
        let entryId = "native-mac:\(idGenerator())"
        let item = HistoryItem(
            id: "\(entryId):item:0",
            kind: kind,
            fileName: report.fileName,
            mimeType: report.mimeType,
            sizeBytes: report.sizeBytes,
            localPath: fileURL.path,
            savedPath: report.savedPath,
            thumbnailDataUrl: nil,
            status: report.status,
            error: report.error
        )
        let entry = HistoryEntry(
            id: entryId,
            timestamp: now(),
            direction: "desktop_to_mobile",
            kind: kind,
            status: report.status,
            text: "[\(label)] \(report.fileName)",
            sender: DeviceIdentity(
                deviceId: configuration.serverId,
                displayName: configuration.hostname,
                role: "desktop",
                ip: configuration.ip,
                port: configuration.port
            ),
            receiver: DeviceIdentity(
                deviceId: peer.deviceId,
                baseDeviceId: peer.baseDeviceId,
                displayName: peer.deviceName,
                role: peer.deviceRole
            ),
            sessionId: report.transferId,
            itemCount: 1,
            saveTarget: saveTargetFromMime(report.mimeType),
            items: [item]
        )
        try? historyDatabase.insert(entry, rawJSON: report.error)
    }

    private func safeFileName(_ value: String) -> String {
        let sanitized = value
            .split(separator: "/", omittingEmptySubsequences: false)
            .last
            .map(String.init)?
            .split(separator: "\\", omittingEmptySubsequences: false)
            .last
            .map(String.init) ?? "file.bin"
        let cleaned = sanitized.map { character in
            character.isASCII && (character == ":" || character.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) })
                ? "_"
                : String(character)
        }.joined()
        return cleaned.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "file.bin" : cleaned
    }

    private func mimeTypeForFileName(_ fileName: String) -> String {
        let ext = (fileName as NSString).pathExtension
        guard !ext.isEmpty else { return "application/octet-stream" }
        return UTType(filenameExtension: ext)?.preferredMIMEType ?? "application/octet-stream"
    }

    private func kindFromMime(_ mimeType: String) -> String {
        if mimeType.hasPrefix("image/") { return "image" }
        if mimeType.hasPrefix("video/") { return "video" }
        return "file"
    }

    private func labelFromKind(_ kind: String) -> String {
        switch kind {
        case "image": return "图片"
        case "video": return "视频"
        default: return "文件"
        }
    }

    private func saveTargetFromMime(_ mimeType: String) -> String {
        if mimeType.hasPrefix("image/") || mimeType.hasPrefix("video/") {
            return "gallery"
        }
        return "download"
    }
}

public enum MacOutboundFileTransferError: LocalizedError, Equatable {
    case notRegularFile

    public var errorDescription: String? {
        switch self {
        case .notRegularFile:
            return "当前只支持发送普通文件，文件夹打包会在后续 App 壳接入"
        }
    }
}

private struct OutboundFileStartMessage: Encodable {
    var action = VibeDropAction.incomingFileStart.rawValue
    var transferId: String
    var fileName: String
    var mimeType: String
    var sizeBytes: Int64

    enum CodingKeys: String, CodingKey {
        case action
        case transferId = "transfer_id"
        case fileName = "file_name"
        case mimeType = "mime_type"
        case sizeBytes = "size_bytes"
    }
}

private struct OutboundFileChunkMessage: Encodable {
    var action = VibeDropAction.incomingFileChunk.rawValue
    var transferId: String
    var chunkBase64: String

    enum CodingKeys: String, CodingKey {
        case action
        case transferId = "transfer_id"
        case chunkBase64 = "chunk_base64"
    }
}

private struct OutboundFileCompleteMessage: Encodable {
    var action = VibeDropAction.incomingFileComplete.rawValue
    var transferId: String

    enum CodingKeys: String, CodingKey {
        case action
        case transferId = "transfer_id"
    }
}

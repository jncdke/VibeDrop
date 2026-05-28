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
        try sendPreparedFile(
            fileURL: fileURL,
            fileName: safeFileName(fileURL.lastPathComponent),
            mimeType: mimeTypeForFileName(fileURL.lastPathComponent),
            isArchive: false,
            historySession: historySession(for: [fileURL], sessionId: "native-mac-\(idGenerator())"),
            localPathForHistory: fileURL.path,
            peer: peer
        )
    }

    public func sendURLs(
        _ urls: [URL],
        to peer: ConnectedPeer
    ) throws -> [MacOutboundFileTransferReport] {
        let normalizedURLs = urls.filter { FileManager.default.fileExists(atPath: $0.path) }
        guard !normalizedURLs.isEmpty else {
            throw MacOutboundFileTransferError.noFiles
        }

        if normalizedURLs.count == 1, try isRegularFile(normalizedURLs[0]) {
            return [try sendFile(at: normalizedURLs[0], to: peer)]
        }

        if shouldSplitGalleryMedia(normalizedURLs) {
            return try normalizedURLs.map { try sendFile(at: $0, to: peer) }
        }

        let sessionId = "native-mac-\(idGenerator())"
        let session = historySession(for: normalizedURLs, sessionId: sessionId)
        let archive = try prepareArchive(for: normalizedURLs, sessionId: sessionId)
        defer { try? FileManager.default.removeItem(at: archive.cleanupDirectory) }
        return [
            try sendPreparedFile(
                fileURL: archive.archiveURL,
                fileName: archive.fileName,
                mimeType: "application/zip",
                isArchive: true,
                historySession: session,
                localPathForHistory: archive.archiveURL.path,
                peer: peer
            )
        ]
    }

    private func sendPreparedFile(
        fileURL: URL,
        fileName: String,
        mimeType: String,
        isArchive: Bool,
        historySession: OutboundHistorySessionMessage,
        localPathForHistory: String,
        peer: ConnectedPeer
    ) throws -> MacOutboundFileTransferReport {
        let metadata = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        guard let type = metadata[.type] as? FileAttributeType, type == .typeRegular else {
            throw MacOutboundFileTransferError.notRegularFile
        }
        let sizeBytes = (metadata[.size] as? NSNumber)?.int64Value ?? 0
        let transferId = historySession.sessionId
        transferRegistry.register(transferId)

        do {
            try send(historySession, to: peer.sessionId)
            try send(
                OutboundFileStartMessage(
                    transferId: transferId,
                    fileName: fileName,
                    mimeType: mimeType,
                    sizeBytes: sizeBytes,
                    isArchive: isArchive,
                    historySessionId: historySession.sessionId,
                    historyItemIndex: 0,
                    historyItemCount: historySession.itemCount
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
            recordHistory(
                report: report,
                localPath: localPathForHistory,
                peer: peer,
                historySession: historySession
            )
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
            recordHistory(
                report: report,
                localPath: localPathForHistory,
                peer: peer,
                historySession: historySession
            )
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
        localPath: String,
        peer: ConnectedPeer,
        historySession: OutboundHistorySessionMessage
    ) {
        guard let historyDatabase else { return }
        let entryId = "native-mac:\(idGenerator())"
        let item = HistoryItem(
            id: "\(entryId):item:0",
            kind: kindFromMime(report.mimeType),
            fileName: report.fileName,
            mimeType: report.mimeType,
            sizeBytes: report.sizeBytes,
            localPath: localPath,
            savedPath: report.savedPath,
            thumbnailDataUrl: nil,
            status: report.status,
            error: report.error
        )
        let entry = HistoryEntry(
            id: entryId,
            timestamp: now(),
            direction: "desktop_to_mobile",
            kind: historySession.kind,
            status: report.status,
            text: historySession.text,
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
            itemCount: historySession.itemCount,
            saveTarget: historySession.saveTarget,
            items: [item]
        )
        try? historyDatabase.insert(entry, rawJSON: report.error)
    }

    private func historySession(
        for urls: [URL],
        sessionId: String
    ) -> OutboundHistorySessionMessage {
        let items = urls.map { url in
            let fileName = safeFileName(url.lastPathComponent)
            let mimeType = mimeTypeForURL(url)
            return OutboundHistoryItemMessage(
                kind: kindFromMime(mimeType),
                fileName: fileName,
                mimeType: mimeType,
                filePath: url.path,
                status: "pending"
            )
        }
        let summary = summary(for: items)
        let mediaCount = items.filter { $0.kind == "image" || $0.kind == "video" }.count
        let saveTarget = mediaCount == items.count && !items.isEmpty ? "gallery" : "download"
        return OutboundHistorySessionMessage(
            sessionId: sessionId,
            timestamp: ISO8601DateFormatter().string(from: now()),
            kind: summary.kind,
            text: summary.text,
            itemCount: max(1, items.count),
            saveTarget: saveTarget,
            items: items
        )
    }

    private func summary(for items: [OutboundHistoryItemMessage]) -> (kind: String, text: String) {
        guard items.count != 1 else {
            let item = items[0]
            return (item.kind, "[\(labelFromKind(item.kind))] \(item.fileName)")
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

    private func prepareArchive(
        for urls: [URL],
        sessionId: String
    ) throws -> PreparedArchive {
        let fileManager = FileManager.default
        let root = fileManager.temporaryDirectory
            .appendingPathComponent("vibedrop-outbound-\(sessionId)", isDirectory: true)
        let staging = root.appendingPathComponent("payload", isDirectory: true)
        try fileManager.createDirectory(at: staging, withIntermediateDirectories: true)

        for url in urls {
            let target = uniqueURL(in: staging, preferredName: safeFileName(url.lastPathComponent))
            try fileManager.copyItem(at: url, to: target)
        }

        let archiveName = archiveFileName(for: urls)
        let archiveURL = root.appendingPathComponent(archiveName)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        process.currentDirectoryURL = staging
        process.arguments = ["-c", "-k", ".", archiveURL.path]
        let stderr = Pipe()
        process.standardError = stderr
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let data = stderr.fileHandleForReading.readDataToEndOfFile()
            let message = String(decoding: data, as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            throw MacOutboundFileTransferError.archiveFailed(
                message.isEmpty ? "ditto 归档失败" : message
            )
        }
        return PreparedArchive(
            archiveURL: archiveURL,
            fileName: archiveName,
            cleanupDirectory: root
        )
    }

    private func archiveFileName(for urls: [URL]) -> String {
        if urls.count == 1 {
            let base = safeFileName(urls[0].deletingPathExtension().lastPathComponent)
            return "\(base).zip"
        }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "VibeDrop-\(formatter.string(from: now())).zip"
    }

    private func uniqueURL(in directory: URL, preferredName: String) -> URL {
        var candidate = directory.appendingPathComponent(preferredName)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let base = (preferredName as NSString).deletingPathExtension
        let ext = (preferredName as NSString).pathExtension
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(base)-\(index)" : "\(base)-\(index).\(ext)"
            candidate = directory.appendingPathComponent(name)
            index += 1
        }
        return candidate
    }

    private func shouldSplitGalleryMedia(_ urls: [URL]) -> Bool {
        guard urls.count > 1 else { return false }
        return urls.allSatisfy { url in
            (try? isRegularFile(url)) == true && isGalleryMedia(mimeTypeForURL(url))
        }
    }

    private func isRegularFile(_ url: URL) throws -> Bool {
        let metadata = try FileManager.default.attributesOfItem(atPath: url.path)
        return (metadata[.type] as? FileAttributeType) == .typeRegular
    }

    private func mimeTypeForURL(_ url: URL) -> String {
        guard (try? isRegularFile(url)) == true else { return "application/octet-stream" }
        return mimeTypeForFileName(url.lastPathComponent)
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

    private func isGalleryMedia(_ mimeType: String) -> Bool {
        mimeType.hasPrefix("image/") || mimeType.hasPrefix("video/")
    }
}

public enum MacOutboundFileTransferError: LocalizedError, Equatable {
    case noFiles
    case notRegularFile
    case archiveFailed(String)

    public var errorDescription: String? {
        switch self {
        case .noFiles:
            return "没有可发送的文件"
        case .notRegularFile:
            return "当前入口只支持普通文件；多文件和文件夹请走批量发送入口"
        case let .archiveFailed(message):
            return "文件夹/多文件打包失败：\(message)"
        }
    }
}

private struct PreparedArchive {
    var archiveURL: URL
    var fileName: String
    var cleanupDirectory: URL
}

private struct OutboundHistorySessionMessage: Encodable {
    var action = VibeDropAction.incomingHistorySessionStart.rawValue
    var sessionId: String
    var timestamp: String
    var kind: String
    var text: String
    var itemCount: Int
    var saveTarget: String
    var items: [OutboundHistoryItemMessage]

    enum CodingKeys: String, CodingKey {
        case action
        case sessionId = "session_id"
        case timestamp
        case kind
        case text
        case itemCount = "item_count"
        case saveTarget = "save_target"
        case items
    }
}

private struct OutboundHistoryItemMessage: Encodable {
    var kind: String
    var fileName: String
    var mimeType: String
    var filePath: String
    var status: String

    enum CodingKeys: String, CodingKey {
        case kind
        case fileName = "file_name"
        case mimeType = "mime_type"
        case filePath = "file_path"
        case status
    }
}

private struct OutboundFileStartMessage: Encodable {
    var action = VibeDropAction.incomingFileStart.rawValue
    var transferId: String
    var fileName: String
    var mimeType: String
    var sizeBytes: Int64
    var isArchive: Bool
    var historySessionId: String?
    var historyItemIndex: Int?
    var historyItemCount: Int?

    enum CodingKeys: String, CodingKey {
        case action
        case transferId = "transfer_id"
        case fileName = "file_name"
        case mimeType = "mime_type"
        case sizeBytes = "size_bytes"
        case isArchive = "is_archive"
        case historySessionId = "history_session_id"
        case historyItemIndex = "history_item_index"
        case historyItemCount = "history_item_count"
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

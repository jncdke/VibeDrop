import CryptoKit
import Foundation
import VibeDropNativeCore

public struct LegacyMacHistoryImportReport: Equatable, Sendable {
    public var parsed: Int
    public var imported: Int
    public var skippedDuplicates: Int
    public var failed: Int
    public var errors: [String]

    public init(
        parsed: Int = 0,
        imported: Int = 0,
        skippedDuplicates: Int = 0,
        failed: Int = 0,
        errors: [String] = []
    ) {
        self.parsed = parsed
        self.imported = imported
        self.skippedDuplicates = skippedDuplicates
        self.failed = failed
        self.errors = errors
    }
}

public struct LegacyMacHistoryImporter: Sendable {
    public var receiver: DeviceIdentity

    public init(receiver: DeviceIdentity) {
        self.receiver = receiver
    }

    public func entry(fromJSONLine jsonLine: String, lineNumber: Int) throws -> HistoryEntry {
        let data = Data(jsonLine.utf8)
        let legacy = try JSONDecoder().decode(LegacyMacHistoryRecord.self, from: data)
        let timestamp = parseTimestamp(legacy.timestamp ?? legacy.ts) ?? Date(timeIntervalSince1970: 0)
        let kind = normalizeKind(legacy.kind, fallback: legacy)
        let status = legacy.status?.isEmpty == false ? legacy.status! : "success"
        let direction = legacy.direction?.isEmpty == false ? legacy.direction! : "mobile_to_desktop"
        let entryId = "legacy-mac:\(sha256(jsonLine))"
        let sender = makeSender(from: legacy)
        let items = makeItems(from: legacy, kind: kind, entryId: entryId)
        return HistoryEntry(
            id: entryId,
            timestamp: timestamp,
            direction: direction,
            kind: kind,
            status: status,
            text: legacy.text,
            sender: sender,
            receiver: receiver,
            sessionId: legacy.sessionId,
            itemCount: legacy.itemCount ?? (items.isEmpty ? nil : items.count),
            saveTarget: legacy.saveTarget,
            items: items
        )
    }

    private func makeSender(from legacy: LegacyMacHistoryRecord) -> DeviceIdentity? {
        let id = legacy.clientId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = legacy.clientName?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id, !id.isEmpty {
            return DeviceIdentity(deviceId: id, displayName: name?.isEmpty == false ? name! : id)
        }
        if let name, !name.isEmpty {
            return DeviceIdentity(deviceId: "legacy-sender:\(slug(name))", displayName: name)
        }
        return DeviceIdentity(deviceId: "legacy-sender:unknown", displayName: "未知发送端")
    }

    private func makeItems(
        from legacy: LegacyMacHistoryRecord,
        kind: String,
        entryId: String
    ) -> [HistoryItem] {
        if let legacyItems = legacy.items, !legacyItems.isEmpty {
            return legacyItems.enumerated().map { index, item in
                HistoryItem(
                    id: "\(entryId):item:\(index)",
                    kind: normalizeKind(item.kind, fallback: legacy),
                    fileName: item.fileName,
                    mimeType: item.mimeType,
                    sizeBytes: item.sizeBytes,
                    localPath: item.filePath,
                    savedPath: item.savedPath ?? item.filePath,
                    thumbnailDataUrl: item.thumbnailDataUrl,
                    status: item.status,
                    error: item.error
                )
            }
        }

        let fileName = legacy.fileName
        let localPath = legacy.filePath ?? legacy.imagePath
        let hasMediaOrFile = fileName != nil || localPath != nil || legacy.thumbnailDataUrl != nil
        guard hasMediaOrFile else { return [] }
        return [
            HistoryItem(
                id: "\(entryId):item:0",
                kind: kind,
                fileName: fileName,
                mimeType: legacy.mimeType,
                sizeBytes: legacy.sizeBytes,
                localPath: localPath,
                savedPath: localPath,
                thumbnailDataUrl: legacy.thumbnailDataUrl,
                status: legacy.status,
                error: nil
            )
        ]
    }

    private func normalizeKind(_ rawKind: String?, fallback: LegacyMacHistoryRecord) -> String {
        let value = rawKind?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let value, !value.isEmpty {
            if value == "clipboard_image" { return "image" }
            return value
        }
        if fallback.imagePath != nil || fallback.thumbnailDataUrl != nil { return "image" }
        if fallback.filePath != nil || fallback.fileName != nil { return "file" }
        return "text"
    }

    private func parseTimestamp(_ value: String?) -> Date? {
        guard let value, !value.isEmpty else { return nil }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: value) { return date }
        return ISO8601DateFormatter().date(from: value)
    }

    private func sha256(_ value: String) -> String {
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func slug(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        return value.unicodeScalars.map { scalar in
            allowed.contains(scalar) ? String(scalar) : "_"
        }.joined().trimmingCharacters(in: CharacterSet(charactersIn: "_")).lowercased()
    }
}

struct LegacyMacHistoryRecord: Decodable {
    var timestamp: String?
    var ts: String?
    var text: String?
    var clientIp: String?
    var clientId: String?
    var clientName: String?
    var kind: String?
    var fileName: String?
    var imagePath: String?
    var thumbnailDataUrl: String?
    var filePath: String?
    var mimeType: String?
    var sizeBytes: Int64?
    var direction: String?
    var status: String?
    var sessionId: String?
    var itemCount: Int?
    var saveTarget: String?
    var items: [LegacyHistoryTransferItem]?

    enum CodingKeys: String, CodingKey {
        case timestamp
        case ts
        case text
        case clientIp = "client_ip"
        case clientId = "client_id"
        case clientName = "client_name"
        case kind
        case fileName = "file_name"
        case imagePath = "image_path"
        case thumbnailDataUrl = "thumbnail_data_url"
        case filePath = "file_path"
        case mimeType = "mime_type"
        case sizeBytes = "size_bytes"
        case direction
        case status
        case sessionId = "session_id"
        case itemCount = "item_count"
        case saveTarget = "save_target"
        case items
    }
}

struct LegacyHistoryTransferItem: Decodable {
    var kind: String?
    var fileName: String?
    var mimeType: String?
    var thumbnailDataUrl: String?
    var filePath: String?
    var savedPath: String?
    var sizeBytes: Int64?
    var status: String?
    var error: String?

    enum CodingKeys: String, CodingKey {
        case kind
        case fileName = "file_name"
        case mimeType = "mime_type"
        case thumbnailDataUrl = "thumbnail_data_url"
        case filePath = "file_path"
        case savedPath = "saved_path"
        case sizeBytes = "size_bytes"
        case status
        case error
    }
}

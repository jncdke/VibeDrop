import Foundation
import GRDB
import VibeDropNativeCore

public final class MacHistoryDatabase: @unchecked Sendable {
    private let queue: DatabaseQueue
    private let legacyAppendURL: URL?
    private let legacyAppendLock = NSLock()

    public init(path: String, legacyAppendURL: URL? = nil) throws {
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        self.legacyAppendURL = legacyAppendURL
        self.queue = try DatabaseQueue(path: path, configuration: configuration)
        try migrate()
    }

    public convenience init(url: URL, legacyAppendURL: URL? = nil) throws {
        try self.init(path: url.path, legacyAppendURL: legacyAppendURL)
    }

    public func insert(
        _ entry: HistoryEntry,
        rawJSON: String? = nil,
        appendLegacyJSONL: Bool = true
    ) throws {
        try queue.write { db in
            try insert(entry, rawJSON: rawJSON, db: db)
        }
        if appendLegacyJSONL {
            try? self.appendLegacyJSONL(entry)
        }
    }

    public func containsEntry(id: String) throws -> Bool {
        try queue.read { db in
            try Bool.fetchOne(
                db,
                sql: "SELECT EXISTS(SELECT 1 FROM history_entries WHERE id = ?)",
                arguments: [id]
            ) ?? false
        }
    }

    public func countEntries() throws -> Int {
        try queue.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM history_entries") ?? 0
        }
    }

    public func fetchRecent(limit: Int = 100) throws -> [HistoryEntry] {
        try queue.read { db in
            let rows = try Row.fetchAll(
                db,
                sql: """
                SELECT *
                FROM history_entries
                ORDER BY timestamp_ms DESC, created_at_ms DESC
                LIMIT ?
                """,
                arguments: [limit]
            )
            return try mapHistoryEntries(rows: rows, db: db)
        }
    }

    public func fetchEntry(id: String) throws -> HistoryEntry? {
        try queue.read { db in
            guard let row = try Row.fetchOne(
                db,
                sql: "SELECT * FROM history_entries WHERE id = ?",
                arguments: [id]
            ) else {
                return nil
            }
            return try mapHistoryEntries(rows: [row], db: db).first
        }
    }

    public func fetchAll() throws -> [HistoryEntry] {
        try queue.read { db in
            let rows = try Row.fetchAll(
                db,
                sql: """
                SELECT *
                FROM history_entries
                ORDER BY timestamp_ms DESC, created_at_ms DESC
                """
            )
            return try mapHistoryEntries(rows: rows, db: db)
        }
    }

    @discardableResult
    public func importLegacyJsonl(
        at url: URL,
        receiver: DeviceIdentity
    ) throws -> LegacyMacHistoryImportReport {
        let importer = LegacyMacHistoryImporter(receiver: receiver)
        var report = LegacyMacHistoryImportReport()
        guard FileManager.default.fileExists(atPath: url.path) else {
            return report
        }

        let data = try Data(contentsOf: url)
        let raw = String(decoding: data, as: UTF8.self)
        for (index, line) in raw.split(whereSeparator: \.isNewline).enumerated() {
            let jsonLine = String(line).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !jsonLine.isEmpty else { continue }
            report.parsed += 1
            do {
                let entry = try importer.entry(fromJSONLine: jsonLine, lineNumber: index + 1)
                if try containsEntry(id: entry.id) {
                    report.skippedDuplicates += 1
                    continue
                }
                try insert(entry, rawJSON: jsonLine, appendLegacyJSONL: false)
                report.imported += 1
            } catch {
                report.failed += 1
                report.errors.append("line \(index + 1): \(error.localizedDescription)")
            }
        }
        return report
    }

    private func migrate() throws {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("create native history tables") { db in
            try db.create(table: "history_entries", ifNotExists: true) { table in
                table.column("id", .text).primaryKey()
                table.column("timestamp_ms", .integer).notNull().indexed()
                table.column("direction", .text).notNull().indexed()
                table.column("kind", .text).notNull().indexed()
                table.column("status", .text).notNull().indexed()
                table.column("text", .text)
                table.column("sender_device_id", .text).indexed()
                table.column("sender_name", .text)
                table.column("receiver_device_id", .text).indexed()
                table.column("receiver_name", .text)
                table.column("session_id", .text).indexed()
                table.column("item_count", .integer)
                table.column("save_target", .text)
                table.column("raw_json", .text)
                table.column("created_at_ms", .integer).notNull()
            }

            try db.create(table: "history_items", ifNotExists: true) { table in
                table.column("id", .text).primaryKey()
                table.column("entry_id", .text)
                    .notNull()
                    .indexed()
                    .references("history_entries", onDelete: .cascade)
                table.column("item_index", .integer).notNull()
                table.column("kind", .text).notNull().indexed()
                table.column("file_name", .text)
                table.column("mime_type", .text)
                table.column("size_bytes", .integer)
                table.column("local_path", .text)
                table.column("saved_path", .text)
                table.column("thumbnail_data_url", .text)
                table.column("status", .text)
                table.column("error", .text)
            }

            try db.create(index: "idx_history_entries_time_kind", on: "history_entries", columns: ["timestamp_ms", "kind"], ifNotExists: true)
            try db.create(index: "idx_history_entries_sender_time", on: "history_entries", columns: ["sender_device_id", "timestamp_ms"], ifNotExists: true)
            try db.create(index: "idx_history_entries_receiver_time", on: "history_entries", columns: ["receiver_device_id", "timestamp_ms"], ifNotExists: true)
        }
        migrator.registerMigration("add device identity metadata") { db in
            try db.alter(table: "history_entries") { table in
                table.add(column: "sender_base_device_id", .text)
                table.add(column: "sender_role", .text)
                table.add(column: "sender_host", .text)
                table.add(column: "sender_ip", .text)
                table.add(column: "sender_port", .integer)
                table.add(column: "receiver_base_device_id", .text)
                table.add(column: "receiver_role", .text)
                table.add(column: "receiver_host", .text)
                table.add(column: "receiver_ip", .text)
                table.add(column: "receiver_port", .integer)
            }
            try db.create(index: "idx_history_entries_sender_base_time", on: "history_entries", columns: ["sender_base_device_id", "timestamp_ms"], ifNotExists: true)
            try db.create(index: "idx_history_entries_receiver_base_time", on: "history_entries", columns: ["receiver_base_device_id", "timestamp_ms"], ifNotExists: true)
        }
        try migrator.migrate(queue)
    }

    private func insert(_ entry: HistoryEntry, rawJSON: String?, db: Database) throws {
        let timestampMs = milliseconds(entry.timestamp)
        let createdAtMs = milliseconds(Date())
        try db.execute(
            sql: """
            INSERT OR REPLACE INTO history_entries (
              id, timestamp_ms, direction, kind, status, text,
              sender_device_id, sender_name, receiver_device_id, receiver_name,
              sender_base_device_id, sender_role, sender_host, sender_ip, sender_port,
              receiver_base_device_id, receiver_role, receiver_host, receiver_ip, receiver_port,
              session_id, item_count, save_target, raw_json, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            arguments: [
                entry.id,
                timestampMs,
                entry.direction,
                entry.kind,
                entry.status,
                entry.text,
                entry.sender?.deviceId,
                entry.sender?.displayName,
                entry.receiver?.deviceId,
                entry.receiver?.displayName,
                entry.sender?.baseDeviceId,
                entry.sender?.role,
                entry.sender?.host,
                entry.sender?.ip,
                entry.sender?.port,
                entry.receiver?.baseDeviceId,
                entry.receiver?.role,
                entry.receiver?.host,
                entry.receiver?.ip,
                entry.receiver?.port,
                entry.sessionId,
                entry.itemCount,
                entry.saveTarget,
                rawJSON,
                createdAtMs
            ]
        )

        try db.execute(sql: "DELETE FROM history_items WHERE entry_id = ?", arguments: [entry.id])
        for (index, item) in entry.items.enumerated() {
            try db.execute(
                sql: """
                INSERT INTO history_items (
                  id, entry_id, item_index, kind, file_name, mime_type, size_bytes,
                  local_path, saved_path, thumbnail_data_url, status, error
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                arguments: [
                    item.id,
                    entry.id,
                    index,
                    item.kind,
                    item.fileName,
                    item.mimeType,
                    item.sizeBytes,
                    item.localPath,
                    item.savedPath,
                    item.thumbnailDataUrl,
                    item.status,
                    item.error
                ]
            )
        }
    }

    private func mapHistoryEntry(row: Row, items: [HistoryItem]) -> HistoryEntry {
        let timestampMs: Int64 = row["timestamp_ms"]
        let senderId: String? = row["sender_device_id"]
        let senderName: String? = row["sender_name"]
        let receiverId: String? = row["receiver_device_id"]
        let receiverName: String? = row["receiver_name"]
        return HistoryEntry(
            id: row["id"],
            timestamp: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000.0),
            direction: row["direction"],
            kind: row["kind"],
            status: row["status"],
            text: row["text"],
            sender: device(
                id: senderId,
                name: senderName,
                baseDeviceId: row["sender_base_device_id"],
                role: row["sender_role"],
                host: row["sender_host"],
                ip: row["sender_ip"],
                port: row["sender_port"]
            ),
            receiver: device(
                id: receiverId,
                name: receiverName,
                baseDeviceId: row["receiver_base_device_id"],
                role: row["receiver_role"],
                host: row["receiver_host"],
                ip: row["receiver_ip"],
                port: row["receiver_port"]
            ),
            sessionId: row["session_id"],
            itemCount: row["item_count"],
            saveTarget: row["save_target"],
            items: items
        )
    }

    private func mapHistoryEntries(rows: [Row], db: Database) throws -> [HistoryEntry] {
        let entryIds: [String] = rows.map { row in row["id"] }
        guard !entryIds.isEmpty else { return [] }
        let placeholders = Array(repeating: "?", count: entryIds.count).joined(separator: ", ")
        let itemRows = try Row.fetchAll(
            db,
            sql: """
            SELECT *
            FROM history_items
            WHERE entry_id IN (\(placeholders))
            ORDER BY item_index ASC
            """,
            arguments: StatementArguments(entryIds)
        )
        var itemsByEntryId: [String: [HistoryItem]] = [:]
        for itemRow in itemRows {
            let entryId: String = itemRow["entry_id"]
            itemsByEntryId[entryId, default: []].append(mapHistoryItem(row: itemRow))
        }
        return rows.map { row in
            let entryId: String = row["id"]
            return mapHistoryEntry(row: row, items: itemsByEntryId[entryId] ?? [])
        }
    }

    private func mapHistoryItem(row: Row) -> HistoryItem {
        HistoryItem(
            id: row["id"],
            kind: row["kind"],
            fileName: row["file_name"],
            mimeType: row["mime_type"],
            sizeBytes: row["size_bytes"],
            localPath: row["local_path"],
            savedPath: row["saved_path"],
            thumbnailDataUrl: row["thumbnail_data_url"],
            status: row["status"],
            error: row["error"]
        )
    }

    private func device(
        id: String?,
        name: String?,
        baseDeviceId: String?,
        role: String?,
        host: String?,
        ip: String?,
        port: Int?
    ) -> DeviceIdentity? {
        guard let id, !id.isEmpty else { return nil }
        return DeviceIdentity(
            deviceId: id,
            baseDeviceId: baseDeviceId?.isEmpty == false ? baseDeviceId : nil,
            displayName: name?.isEmpty == false ? name! : id,
            role: role?.isEmpty == false ? role : nil,
            host: host?.isEmpty == false ? host : nil,
            ip: ip?.isEmpty == false ? ip : nil,
            port: port
        )
    }

    private func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000.0).rounded())
    }

    private func appendLegacyJSONL(_ entry: HistoryEntry) throws {
        guard let legacyAppendURL else { return }
        let data = try LegacyMacHistoryJSONLRecord(entry: entry).jsonLineData()
        legacyAppendLock.lock()
        defer { legacyAppendLock.unlock() }
        let parent = legacyAppendURL.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: legacyAppendURL.path) {
            FileManager.default.createFile(atPath: legacyAppendURL.path, contents: nil)
        }
        let handle = try FileHandle(forWritingTo: legacyAppendURL)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
    }
}

private struct LegacyMacHistoryJSONLRecord: Encodable {
    var id: String
    var timestamp: String
    var text: String
    var clientIp: String
    var clientId: String?
    var clientBaseDeviceId: String?
    var clientName: String?
    var clientRole: String?
    var clientHost: String?
    var clientPort: Int?
    var kind: String?
    var fileName: String?
    var imagePath: String?
    var thumbnailDataUrl: String?
    var filePath: String?
    var savedPath: String?
    var mimeType: String?
    var sizeBytes: Int64?
    var direction: String?
    var status: String?
    var sessionId: String?
    var itemCount: Int?
    var saveTarget: String?
    var targetDeviceName: String?
    var targetBaseDeviceId: String?
    var targetRole: String?
    var targetServerId: String?
    var targetIp: String?
    var targetPort: Int?
    var hostname: String?
    var items: [LegacyMacHistoryJSONLItem]?

    init(entry: HistoryEntry) {
        let mobile = entry.direction == "desktop_to_mobile" ? entry.receiver : entry.sender
        let desktop = entry.direction == "desktop_to_mobile" ? entry.sender : entry.receiver
        let firstItem = entry.items.first
        let firstPath = firstItem?.savedPath ?? firstItem?.localPath
        id = entry.id
        timestamp = Self.timestampFormatter.string(from: entry.timestamp)
        text = entry.text ?? ""
        clientIp = mobile?.ip ?? ""
        clientId = mobile?.deviceId
        clientBaseDeviceId = mobile?.baseDeviceId
        clientName = mobile?.displayName
        clientRole = mobile?.role
        clientHost = mobile?.host
        clientPort = mobile?.port
        kind = entry.kind
        fileName = firstItem?.fileName
        imagePath = firstItem?.kind == "image" ? firstPath : nil
        thumbnailDataUrl = firstItem?.thumbnailDataUrl
        filePath = firstItem?.kind == "image" ? nil : firstPath
        savedPath = firstItem?.savedPath
        mimeType = firstItem?.mimeType
        sizeBytes = firstItem?.sizeBytes
        direction = entry.direction
        status = entry.status
        sessionId = entry.sessionId
        itemCount = entry.itemCount
        saveTarget = entry.saveTarget
        targetDeviceName = desktop?.displayName
        targetBaseDeviceId = desktop?.baseDeviceId
        targetRole = desktop?.role
        targetServerId = desktop?.deviceId
        targetIp = desktop?.ip
        targetPort = desktop?.port
        hostname = desktop?.host ?? desktop?.displayName
        items = entry.items.isEmpty ? nil : entry.items.enumerated().map { index, item in
            LegacyMacHistoryJSONLItem(index: index, item: item)
        }
    }

    enum CodingKeys: String, CodingKey {
        case id
        case timestamp
        case text
        case clientIp = "client_ip"
        case clientId = "client_id"
        case clientBaseDeviceId = "client_base_device_id"
        case clientName = "client_name"
        case clientRole = "client_role"
        case clientHost = "client_host"
        case clientPort = "client_port"
        case kind
        case fileName = "file_name"
        case imagePath = "image_path"
        case thumbnailDataUrl = "thumbnail_data_url"
        case filePath = "file_path"
        case savedPath = "saved_path"
        case mimeType = "mime_type"
        case sizeBytes = "size_bytes"
        case direction
        case status
        case sessionId = "session_id"
        case itemCount = "item_count"
        case saveTarget = "save_target"
        case targetDeviceName
        case targetBaseDeviceId
        case targetRole
        case targetServerId
        case targetIp
        case targetPort
        case hostname
        case items
    }

    func jsonLineData() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        var data = try encoder.encode(self)
        data.append(0x0A)
        return data
    }

    private static let timestampFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}

private struct LegacyMacHistoryJSONLItem: Encodable {
    var kind: String
    var fileName: String?
    var mimeType: String?
    var thumbnailDataUrl: String?
    var filePath: String?
    var savedPath: String?
    var sizeBytes: Int64?
    var status: String?
    var error: String?

    init(index _: Int, item: HistoryItem) {
        kind = item.kind
        fileName = item.fileName
        mimeType = item.mimeType
        thumbnailDataUrl = item.thumbnailDataUrl
        filePath = item.localPath ?? item.savedPath
        savedPath = item.savedPath
        sizeBytes = item.sizeBytes
        status = item.status
        error = item.error
    }

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

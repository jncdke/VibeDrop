import Foundation
import GRDB
import VibeDropNativeCore

public final class MacHistoryDatabase: @unchecked Sendable {
    private let queue: DatabaseQueue

    public init(path: String) throws {
        var configuration = Configuration()
        configuration.foreignKeysEnabled = true
        self.queue = try DatabaseQueue(path: path, configuration: configuration)
        try migrate()
    }

    public convenience init(url: URL) throws {
        try self.init(path: url.path)
    }

    public func insert(_ entry: HistoryEntry, rawJSON: String? = nil) throws {
        try queue.write { db in
            try insert(entry, rawJSON: rawJSON, db: db)
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
            return try rows.map { row in
                let entryId: String = row["id"]
                let itemRows = try Row.fetchAll(
                    db,
                    sql: """
                    SELECT *
                    FROM history_items
                    WHERE entry_id = ?
                    ORDER BY item_index ASC
                    """,
                    arguments: [entryId]
                )
                let items = itemRows.map(mapHistoryItem(row:))
                return mapHistoryEntry(row: row, items: items)
            }
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
                try insert(entry, rawJSON: jsonLine)
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
              session_id, item_count, save_target, raw_json, created_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            sender: device(id: senderId, name: senderName),
            receiver: device(id: receiverId, name: receiverName),
            sessionId: row["session_id"],
            itemCount: row["item_count"],
            saveTarget: row["save_target"],
            items: items
        )
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

    private func device(id: String?, name: String?) -> DeviceIdentity? {
        guard let id, !id.isEmpty else { return nil }
        return DeviceIdentity(
            deviceId: id,
            displayName: name?.isEmpty == false ? name! : id
        )
    }

    private func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000.0).rounded())
    }
}

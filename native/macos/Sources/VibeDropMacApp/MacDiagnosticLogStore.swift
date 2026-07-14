import Foundation
import VibeDropMacRuntime
import VibeDropMacServer

struct MacDiagnosticLogEvent: Identifiable, Equatable {
    var id = UUID()
    var timestamp: Date
    var scope: String
    var event: String
    var detail: [String: String]

    var label: String {
        "\(Self.displayFormatter.string(from: timestamp)) · \(scope) · \(event)"
    }

    var detailText: String {
        guard !detail.isEmpty else { return "{}" }
        return detail
            .sorted { $0.key < $1.key }
            .map { "\($0.key)=\($0.value)" }
            .joined(separator: " · ")
    }

    private static let displayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "MM/dd HH:mm:ss"
        return formatter
    }()
}

final class MacDiagnosticLogStore {
    private let fileURL: URL
    private let lock = NSLock()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(fileManager: FileManager = .default) throws {
        let directory = try MacRuntimePaths.applicationSupportDirectory(fileManager: fileManager)
            .appendingPathComponent("diagnostics", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("events.jsonl")
        encoder.dateEncodingStrategy = .iso8601
        decoder.dateDecodingStrategy = .iso8601
    }

    func append(scope: String, event: String, detail: [String: String] = [:]) {
        let record = Record(
            timestamp: Date(),
            scope: String(scope.prefix(64)),
            event: String(event.prefix(96)),
            detail: detail.mapValues { String($0.prefix(300)) }
        )
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? encoder.encode(record),
              let line = String(data: data, encoding: .utf8) else { return }
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            FileManager.default.createFile(atPath: fileURL.path, contents: nil)
        }
        if let handle = try? FileHandle(forWritingTo: fileURL) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: Data((line + "\n").utf8))
        }
        trimIfNeeded()
    }

    func recent(limit: Int = 80) -> [MacDiagnosticLogEvent] {
        lock.lock()
        defer { lock.unlock() }
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return [] }
        return text
            .split(separator: "\n")
            .suffix(limit)
            .compactMap { line -> MacDiagnosticLogEvent? in
                guard let data = String(line).data(using: .utf8),
                      let record = try? decoder.decode(Record.self, from: data) else { return nil }
                return MacDiagnosticLogEvent(
                    timestamp: record.timestamp,
                    scope: record.scope,
                    event: record.event,
                    detail: record.detail
                )
            }
            .reversed()
    }

    func exportSnapshot(
        configuration: MacServerConfiguration?,
        serviceStatus: String,
        serviceError: String?,
        isAccessibilityTrusted: Bool,
        launchAtLoginStatus: String,
        connectedClients: [MacConnectedClientSnapshot],
        pendingPairRequests: [PairRequestInfo],
        recentHistoryCount: Int
    ) throws -> URL {
        let events = recent(limit: 300).reversed().map { event in
            EventExport(
                timestamp: event.timestamp,
                scope: event.scope,
                event: event.event,
                detail: event.detail
            )
        }
        let snapshot = SnapshotExport(
            schemaVersion: 1,
            app: "VibeDrop Native macOS",
            exportedAt: Date(),
            serviceStatus: serviceStatus,
            serviceError: serviceError,
            configuration: configuration.map {
                ConfigurationExport(
                    hostname: $0.hostname,
                    ip: $0.ip,
                    port: $0.port,
                    serverId: $0.serverId
                )
            },
            isAccessibilityTrusted: isAccessibilityTrusted,
            launchAtLoginStatus: launchAtLoginStatus,
            connectedClients: connectedClients.map {
                ClientExport(
                    sessionId: $0.peer.sessionId,
                    deviceId: $0.peer.deviceId,
                    deviceName: $0.peer.deviceName,
                    role: $0.peer.deviceRole,
                    canReceiveFiles: $0.peer.canReceiveFiles
                )
            },
            pendingPairRequestCount: pendingPairRequests.count,
            recentHistoryCount: recentHistoryCount,
            events: events
        )
        let exportEncoder = JSONEncoder()
        exportEncoder.dateEncodingStrategy = .iso8601
        exportEncoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try exportEncoder.encode(snapshot)
        let fileName = "vibedrop_macos_diagnostics_\(Self.fileStamp()).json"
        let directory = try FileManager.default.url(
            for: .downloadsDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let url = directory.appendingPathComponent(fileName)
        try data.write(to: url, options: .atomic)
        return url
    }

    private func trimIfNeeded() {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let size = attributes[.size] as? NSNumber,
              size.intValue > 512 * 1024,
              let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
        let lines = text.split(separator: "\n").suffix(500).joined(separator: "\n") + "\n"
        try? lines.write(to: fileURL, atomically: true, encoding: .utf8)
    }

    private static func fileStamp() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return formatter.string(from: Date())
    }

    private struct Record: Codable {
        var timestamp: Date
        var scope: String
        var event: String
        var detail: [String: String]
    }

    private struct SnapshotExport: Codable {
        var schemaVersion: Int
        var app: String
        var exportedAt: Date
        var serviceStatus: String
        var serviceError: String?
        var configuration: ConfigurationExport?
        var isAccessibilityTrusted: Bool
        var launchAtLoginStatus: String
        var connectedClients: [ClientExport]
        var pendingPairRequestCount: Int
        var recentHistoryCount: Int
        var events: [EventExport]
    }

    private struct ConfigurationExport: Codable {
        var hostname: String
        var ip: String
        var port: Int
        var serverId: String
    }

    private struct ClientExport: Codable {
        var sessionId: UInt64
        var deviceId: String
        var deviceName: String
        var role: String
        var canReceiveFiles: Bool
    }

    private struct EventExport: Codable {
        var timestamp: Date
        var scope: String
        var event: String
        var detail: [String: String]
    }
}

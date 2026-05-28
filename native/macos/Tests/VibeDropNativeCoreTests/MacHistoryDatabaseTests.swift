import XCTest
@testable import VibeDropMacStorage
import VibeDropNativeCore

final class MacHistoryDatabaseTests: XCTestCase {
    func testImportLegacyJsonlPreservesSenderReceiverAndItems() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-mac-db-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let jsonl = directory.appendingPathComponent("history.jsonl")
        let lines = [
            """
            {"timestamp":"2026-05-28T12:00:00.123+08:00","text":"测试文本","client_ip":"ws-client","client_id":"client_android_demo","client_name":"一加 Ace 5","kind":"text","status":"success"}
            """,
            """
            {"timestamp":"2026-05-28T12:01:00.000+08:00","text":"[图片] demo.png","client_ip":"ws-client","client_id":"client_android_demo","client_name":"一加 Ace 5","kind":"image","file_name":"demo.png","image_path":"/Users/overlord/.vibedrop/received-images/demo.png","thumbnail_data_url":"data:image/png;base64,abc","status":"success"}
            """
        ].joined(separator: "\n")
        try lines.write(to: jsonl, atomically: true, encoding: .utf8)

        let receiver = DeviceIdentity(
            deviceId: "desktop_demo",
            displayName: "overlorddeMacBook-Air-4.local"
        )
        let database = try MacHistoryDatabase(url: directory.appendingPathComponent("vibedrop.sqlite"))

        let report = try database.importLegacyJsonl(at: jsonl, receiver: receiver)
        XCTAssertEqual(report.parsed, 2)
        XCTAssertEqual(report.imported, 2)
        XCTAssertEqual(report.failed, 0)
        XCTAssertEqual(try database.countEntries(), 2)

        let duplicateReport = try database.importLegacyJsonl(at: jsonl, receiver: receiver)
        XCTAssertEqual(duplicateReport.imported, 0)
        XCTAssertEqual(duplicateReport.skippedDuplicates, 2)
        XCTAssertEqual(try database.countEntries(), 2)

        let recent = try database.fetchRecent(limit: 10)
        XCTAssertEqual(recent.first?.kind, "image")
        XCTAssertEqual(recent.first?.sender?.displayName, "一加 Ace 5")
        XCTAssertEqual(recent.first?.receiver?.deviceId, "desktop_demo")
        XCTAssertEqual(recent.first?.items.first?.fileName, "demo.png")
        XCTAssertEqual(recent.first?.items.first?.thumbnailDataUrl, "data:image/png;base64,abc")

        let all = try database.fetchAll()
        XCTAssertEqual(all.map(\.kind), ["image", "text"])
        XCTAssertEqual(all.first?.items.count, 1)
    }

    func testInsertAppendsLegacyJsonlForHomeVaultCompatibility() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-mac-db-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let legacyJsonl = directory.appendingPathComponent("history.jsonl")
        let database = try MacHistoryDatabase(
            url: directory.appendingPathComponent("vibedrop.sqlite"),
            legacyAppendURL: legacyJsonl
        )
        let entry = HistoryEntry(
            id: "native-mac:compat-test",
            timestamp: Date(timeIntervalSince1970: 1_779_970_496.123),
            direction: "mobile_to_desktop",
            kind: "image",
            status: "success",
            text: "[图片] demo.png",
            sender: DeviceIdentity(
                deviceId: "client_android_demo",
                baseDeviceId: "android_base_demo",
                displayName: "一加 Ace 5",
                role: "mobile",
                ip: "192.168.3.88"
            ),
            receiver: DeviceIdentity(
                deviceId: "desktop_demo",
                displayName: "overlorddeMacBook-Air-4.local",
                role: "desktop",
                host: "overlorddeMacBook-Air-4.local",
                ip: "192.168.3.66",
                port: 8765
            ),
            sessionId: "native-batch-demo",
            itemCount: 1,
            saveTarget: "desktop_inbox",
            items: [
                HistoryItem(
                    id: "native-mac:compat-test:item:0",
                    kind: "image",
                    fileName: "demo.png",
                    mimeType: "image/png",
                    sizeBytes: 123_456,
                    localPath: "/Users/overlord/.vibedrop/incoming-downloads/demo.png",
                    savedPath: "/Users/overlord/Downloads/VibeDrop 收件箱/demo.png",
                    thumbnailDataUrl: "data:image/png;base64,abc",
                    status: "success"
                )
            ]
        )

        try database.insert(entry)

        let raw = try String(contentsOf: legacyJsonl, encoding: .utf8)
        let lines = raw.split(whereSeparator: \.isNewline)
        XCTAssertEqual(lines.count, 1)
        let data = Data(String(lines[0]).utf8)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(object["id"] as? String, "native-mac:compat-test")
        XCTAssertEqual(object["kind"] as? String, "image")
        XCTAssertEqual(object["client_id"] as? String, "client_android_demo")
        XCTAssertEqual(object["client_name"] as? String, "一加 Ace 5")
        XCTAssertEqual(object["targetServerId"] as? String, "desktop_demo")
        XCTAssertEqual(object["targetDeviceName"] as? String, "overlorddeMacBook-Air-4.local")
        XCTAssertEqual(object["image_path"] as? String, "/Users/overlord/Downloads/VibeDrop 收件箱/demo.png")
        XCTAssertEqual(object["thumbnail_data_url"] as? String, "data:image/png;base64,abc")

        let items = try XCTUnwrap(object["items"] as? [[String: Any]])
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?["file_name"] as? String, "demo.png")
        XCTAssertEqual(items.first?["saved_path"] as? String, "/Users/overlord/Downloads/VibeDrop 收件箱/demo.png")
    }

    func testInsertCanSkipLegacyJsonlAppendForIncompleteAggregateRows() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-mac-db-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let legacyJsonl = directory.appendingPathComponent("history.jsonl")
        let database = try MacHistoryDatabase(
            url: directory.appendingPathComponent("vibedrop.sqlite"),
            legacyAppendURL: legacyJsonl
        )
        try database.insert(
            HistoryEntry(
                id: "native-mac-inbound:pending-batch",
                timestamp: Date(),
                direction: "mobile_to_desktop",
                kind: "file",
                status: "pending",
                text: "[文件] 1 / 2",
                itemCount: 2,
                items: [
                    HistoryItem(id: "native-mac-inbound:pending-batch:item:0", kind: "file", status: "pending"),
                    HistoryItem(id: "native-mac-inbound:pending-batch:item:1", kind: "file", status: "pending")
                ]
            ),
            appendLegacyJSONL: false
        )

        XCTAssertEqual(try database.countEntries(), 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: legacyJsonl.path))
    }
}

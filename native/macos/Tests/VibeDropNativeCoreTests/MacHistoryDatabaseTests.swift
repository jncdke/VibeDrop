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
    }
}

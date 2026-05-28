import XCTest
@testable import VibeDropNativeCore

final class VibeDropMessageTests: XCTestCase {
    func testDecodeProtocolV1ActionFixtures() throws {
        let expectedActions: [(String, VibeDropAction)] = [
            ("auth-primary.json", .auth),
            ("auth-clipboard-sync.json", .auth),
            ("clipboard.json", .clipboard),
            ("enter.json", .enter),
            ("image-clipboard.json", .imageClipboard),
            ("incoming-history-session-start.json", .incomingHistorySessionStart),
            ("incoming-file-start.json", .incomingFileStart),
            ("incoming-file-chunk.json", .incomingFileChunk),
            ("incoming-file-complete.json", .incomingFileComplete),
            ("incoming-file-saved.json", .incomingFileSaved),
            ("incoming-file-error.json", .incomingFileError),
            ("ping.json", .ping),
            ("pong.json", .pong),
            ("type.json", .type),
            ("type-enter.json", .typeEnter)
        ]

        for (fileName, expectedAction) in expectedActions {
            let message = try JSONDecoder().decode(
                VibeDropMessage.self,
                from: protocolFixtureData("messages/\(fileName)")
            )
            XCTAssertEqual(message.action, expectedAction, fileName)
        }
    }

    func testDecodeProtocolV1FileAndSessionFields() throws {
        let start = try JSONDecoder().decode(
            VibeDropMessage.self,
            from: protocolFixtureData("messages/incoming-file-start.json")
        )
        XCTAssertEqual(start.transferId, "transfer_demo_001")
        XCTAssertEqual(start.fileName, "demo.txt")
        XCTAssertEqual(start.mimeType, "text/plain")
        XCTAssertEqual(start.sizeBytes, 24)

        let historySession = try JSONDecoder().decode(
            VibeDropMessage.self,
            from: protocolFixtureData("messages/incoming-history-session-start.json")
        )
        XCTAssertEqual(historySession.sessionId, "session_demo_001")
        XCTAssertEqual(historySession.itemCount, 2)
        XCTAssertEqual(historySession.saveTarget, "gallery")
    }

    func testDecodeAuthFixtureShape() throws {
        let message = try JSONDecoder().decode(
            VibeDropMessage.self,
            from: protocolFixtureData("messages/auth-primary.json")
        )

        XCTAssertEqual(message.action, .auth)
        XCTAssertEqual(message.pin, "1234")
        XCTAssertEqual(message.deviceId, "client_android_demo")
        XCTAssertEqual(message.deviceName, "一加 Ace 5")
        XCTAssertEqual(message.canReceiveFiles, true)
        XCTAssertEqual(message.receivesClipboard, false)
        XCTAssertEqual(message.deviceRole, "primary")
    }

    func testEncodeTypeEnterUsesV1SnakeCaseAction() throws {
        let message = VibeDropMessage(
            action: .typeEnter,
            text: "测试文本"
        )

        let data = try JSONEncoder().encode(message)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertEqual(object?["action"] as? String, "type_enter")
        XCTAssertEqual(object?["text"] as? String, "测试文本")
    }

    func testConnectionSnapshotCanSendOnlyWhenConnected() {
        XCTAssertTrue(ConnectionSnapshot(status: .connected).canSend)
        XCTAssertFalse(ConnectionSnapshot(status: .connecting).canSend)
        XCTAssertFalse(ConnectionSnapshot(status: .failed).canSend)
    }

    private func protocolFixtureData(_ relativePath: String) throws -> Data {
        let root = try repositoryRoot()
        return try Data(contentsOf: root.appendingPathComponent("docs/protocol-v1-fixtures/\(relativePath)"))
    }

    private func repositoryRoot() throws -> URL {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fileManager = FileManager.default
        while current.path != "/" {
            let fixtureDirectory = current.appendingPathComponent("docs/protocol-v1-fixtures")
            if fileManager.fileExists(atPath: fixtureDirectory.path) {
                return current
            }
            current.deleteLastPathComponent()
        }
        throw NSError(domain: "VibeDropMessageTests", code: 1, userInfo: [
            NSLocalizedDescriptionKey: "Cannot locate docs/protocol-v1-fixtures from \(#filePath)"
        ])
    }
}

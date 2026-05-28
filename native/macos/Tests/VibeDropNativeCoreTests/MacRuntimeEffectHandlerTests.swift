import Foundation
import XCTest
import VibeDropMacRuntime
import VibeDropMacServer
import VibeDropMacStorage
import VibeDropNativeCore

final class MacRuntimeEffectHandlerTests: XCTestCase {
    func testTypeEnterUsesInputControllerAndRecordsHistory() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-runtime-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let database = try MacHistoryDatabase(url: directory.appendingPathComponent("history.sqlite"))
        let input = RecordingInputController()
        let configuration = MacServerConfiguration(
            serverId: "desktop_runtime_test",
            pin: "1234",
            hostname: "runtime-test.local",
            ip: "127.0.0.1",
            port: 9001
        )
        let runtime = MacRuntimeEffectHandler(
            configuration: configuration,
            historyDatabase: database,
            inputController: input,
            idGenerator: { "fixed-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )
        let peer = ConnectedPeer(
            sessionId: 1,
            deviceId: "client_runtime_test",
            baseDeviceId: "client_runtime_test",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: false,
            deviceRole: "primary"
        )

        let outbound = runtime.handle(.typeTextAndEnter("测试文本", peer: peer))

        XCTAssertEqual(input.events, [.type("测试文本"), .enter])
        XCTAssertEqual(outbound, [.status(MacServerStatusEnvelope(status: "ok"))])
        let recent = try database.fetchRecent(limit: 1)
        XCTAssertEqual(recent.first?.id, "native-mac:fixed-id")
        XCTAssertEqual(recent.first?.text, "测试文本")
        XCTAssertEqual(recent.first?.status, "success")
        XCTAssertEqual(recent.first?.saveTarget, "type_enter")
        XCTAssertEqual(recent.first?.sender?.displayName, "一加 Ace 5")
        XCTAssertEqual(recent.first?.receiver?.deviceId, "desktop_runtime_test")
    }

    func testInputFailureReturnsErrorAndRecordsFailedHistory() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-runtime-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let database = try MacHistoryDatabase(url: directory.appendingPathComponent("history.sqlite"))
        let input = RecordingInputController(error: MacInputError.accessibilityPermissionMissing)
        let runtime = MacRuntimeEffectHandler(
            configuration: MacServerConfiguration(
                serverId: "desktop_runtime_test",
                pin: "1234",
                hostname: "runtime-test.local",
                ip: "127.0.0.1"
            ),
            historyDatabase: database,
            inputController: input,
            idGenerator: { "failed-id" },
            now: { Date(timeIntervalSince1970: 1_774_800_000) }
        )
        let peer = ConnectedPeer(
            sessionId: 1,
            deviceId: "client_runtime_test",
            baseDeviceId: "client_runtime_test",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: false,
            deviceRole: "primary"
        )

        let outbound = runtime.handle(.typeText("测试文本", peer: peer))

        guard case let .status(envelope) = outbound.first else {
            return XCTFail("missing status")
        }
        XCTAssertEqual(envelope.status, "error")
        XCTAssertEqual(envelope.error, "缺少 macOS 辅助功能权限，无法模拟键盘输入")
        XCTAssertEqual(try database.fetchRecent(limit: 1).first?.status, "failed")
    }
}

private final class RecordingInputController: MacInputControlling, @unchecked Sendable {
    enum Event: Equatable {
        case type(String)
        case enter
    }

    var events: [Event] = []
    let error: Error?

    init(error: Error? = nil) {
        self.error = error
    }

    var isAccessibilityTrusted: Bool {
        error == nil
    }

    func typeText(_ text: String) throws {
        if let error { throw error }
        events.append(.type(text))
    }

    func pressEnter() throws {
        if let error { throw error }
        events.append(.enter)
    }
}

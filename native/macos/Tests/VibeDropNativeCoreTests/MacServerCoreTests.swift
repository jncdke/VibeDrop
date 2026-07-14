import XCTest
import VibeDropNativeCore
@testable import VibeDropMacServer

final class MacServerCoreTests: XCTestCase {
    private let configuration = MacServerConfiguration(
        serverId: "desktop_demo",
        pin: "1234",
        hostname: "overlorddeMacBook-Air-4.local",
        ip: "192.168.3.10",
        port: 9001
    )

    func testDiscoverResponseUsesV1SnakeCaseShape() throws {
        let response = DiscoverResponse(configuration: configuration, advertisedIP: "192.168.3.20")
        let data = try JSONEncoder().encode(response)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]

        XCTAssertEqual(object?["kind"] as? String, "desktop")
        XCTAssertEqual(object?["server_id"] as? String, "desktop_demo")
        XCTAssertEqual(object?["hostname"] as? String, "overlorddeMacBook-Air-4.local")
        XCTAssertEqual(object?["ip"] as? String, "192.168.3.20")
        XCTAssertEqual(object?["port"] as? Int, 9001)
        XCTAssertEqual(object?["protocol_version"] as? Int, 1)
    }

    func testDiscoverResponseMatchesProtocolFixtureKind() throws {
        let fixtureConfiguration = MacServerConfiguration(
            serverId: "desktop_demo_macbook",
            pin: "1234",
            hostname: "overlorddeMacBook-Air-4.local",
            ip: "192.168.3.10",
            port: 9001
        )
        let response = DiscoverResponse(configuration: fixtureConfiguration)
        let data = try JSONEncoder().encode(response)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let fixtureObject = try JSONSerialization.jsonObject(
            with: protocolFixtureData("messages/discover-response.json")
        ) as? [String: Any]

        XCTAssertEqual(object?["kind"] as? String, fixtureObject?["kind"] as? String)
        XCTAssertEqual(object?["server_id"] as? String, fixtureObject?["server_id"] as? String)
        XCTAssertEqual(object?["hostname"] as? String, fixtureObject?["hostname"] as? String)
        XCTAssertEqual(object?["ip"] as? String, fixtureObject?["ip"] as? String)
        XCTAssertEqual(object?["port"] as? Int, fixtureObject?["port"] as? Int)
        XCTAssertEqual(object?["protocol_version"] as? Int, fixtureObject?["protocol_version"] as? Int)
    }

    func testPairRequestCanBeApprovedAndReturnsPin() throws {
        let manager = PairRequestManager(ttlSeconds: 180)
        let now = Date(timeIntervalSince1970: 1_774_800_000)
        let accepted = try manager.requestPairing(
            clientId: "client_android_demo",
            clientName: "一加 Ace 5",
            configuration: configuration,
            now: now
        )

        XCTAssertEqual(accepted.hostname, configuration.hostname)
        XCTAssertEqual(manager.pendingRequests(now: now).count, 1)
        try manager.approve(accepted.requestId, now: now)

        let status = manager.status(
            requestId: accepted.requestId,
            configuration: configuration,
            advertisedIP: "192.168.3.20",
            now: now
        )
        XCTAssertEqual(status.status, "approved")
        XCTAssertEqual(status.serverId, "desktop_demo")
        XCTAssertEqual(status.pin, "1234")
        XCTAssertEqual(status.ip, "192.168.3.20")
    }

    func testWebSocketSessionAuthAndTypeEnterEffect() throws {
        let session = MacWebSocketSession(sessionId: 7, configuration: configuration)
        let auth = VibeDropMessage(
            action: .auth,
            pin: "1234",
            deviceId: "client_android_demo",
            baseDeviceId: "client_android_demo",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: false,
            deviceRole: "primary"
        )

        let authResult = session.handle(auth)
        XCTAssertEqual(authResult.effects.count, 1)
        XCTAssertEqual(session.authenticatedPeer?.deviceName, "一加 Ace 5")

        guard case let .status(envelope) = authResult.outbound.first else {
            return XCTFail("missing auth status")
        }
        XCTAssertEqual(envelope.status, "ok")
        XCTAssertEqual(envelope.serverId, "desktop_demo")

        let sendResult = session.handle(VibeDropMessage(action: .typeEnter, text: "测试文本"))
        XCTAssertEqual(sendResult.outbound, [])
        XCTAssertEqual(sendResult.effects, [
            .typeTextAndEnter("测试文本", peer: session.authenticatedPeer!)
        ])
    }

    func testWebSocketSessionRejectsUnauthenticatedSend() {
        let session = MacWebSocketSession(sessionId: 7, configuration: configuration)
        let result = session.handle(VibeDropMessage(action: .type, text: "测试文本"))

        XCTAssertEqual(result.effects, [])
        guard case let .status(envelope) = result.outbound.first else {
            return XCTFail("missing error status")
        }
        XCTAssertEqual(envelope.status, "error")
        XCTAssertEqual(envelope.error, "未认证")
    }

    func testPingReturnsPongWithoutAuth() throws {
        let session = MacWebSocketSession(sessionId: 7, configuration: configuration)
        let result = session.handle(VibeDropMessage(action: .ping))
        XCTAssertEqual(result.outbound, [.action(.pong)])

        let data = try result.outbound[0].jsonData()
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertEqual(object?["action"] as? String, "pong")
    }

    private func protocolFixtureData(_ relativePath: String) throws -> Data {
        var current = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let fileManager = FileManager.default
        while current.path != "/" {
            let fixtureDirectory = current.appendingPathComponent("docs/protocol-v1-fixtures")
            if fileManager.fileExists(atPath: fixtureDirectory.path) {
                return try Data(contentsOf: current.appendingPathComponent("docs/protocol-v1-fixtures/\(relativePath)"))
            }
            current.deleteLastPathComponent()
        }
        throw NSError(domain: "MacServerCoreTests", code: 1, userInfo: [
            NSLocalizedDescriptionKey: "Cannot locate docs/protocol-v1-fixtures from \(#filePath)"
        ])
    }
}

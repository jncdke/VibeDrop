import Foundation
import XCTest
import VibeDropNativeCore
@testable import VibeDropMacServer

final class MacServerTransportTests: XCTestCase {
    func testShareExtensionPathRequestRequiresConnectedFileReceiver() async throws {
        let configuration = MacServerConfiguration(
            serverId: "desktop_share_test",
            pin: "2468",
            hostname: "share-test.local",
            ip: "127.0.0.1",
            port: 0
        )
        let server = VibeDropMacServer(configuration: configuration, threadCount: 1)
        try server.start(host: "127.0.0.1", enableUDPDiscovery: false)
        defer { try? server.stop() }

        let temporaryDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-share-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: temporaryDirectory) }
        let sharedFileURL = temporaryDirectory.appendingPathComponent("demo.txt")
        try Data("demo".utf8).write(to: sharedFileURL)

        let port = try XCTUnwrap(server.boundPort)
        let shareURL = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/share-extension/paths"))
        var request = URLRequest(url: shareURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "paths": [sharedFileURL.path],
            "source": "finder-share-extension"
        ])

        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 409)
        let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertEqual(object?["error"] as? String, "当前没有支持接收文件的手机在线设备")
    }

    func testHTTPDiscoverAndWebSocketAuthPingOverLocalhost() async throws {
        let configuration = MacServerConfiguration(
            serverId: "desktop_transport_test",
            pin: "2468",
            hostname: "transport-test.local",
            ip: "127.0.0.1",
            port: 0
        )
        let server = VibeDropMacServer(configuration: configuration, threadCount: 1)
        try server.start(host: "127.0.0.1", enableUDPDiscovery: false)
        defer { try? server.stop() }

        let port = try XCTUnwrap(server.boundPort)
        let discoverURL = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/discover"))
        let (discoverData, discoverResponse) = try await URLSession.shared.data(from: discoverURL)
        XCTAssertEqual((discoverResponse as? HTTPURLResponse)?.statusCode, 200)
        let discover = try JSONDecoder().decode(DiscoverResponse.self, from: discoverData)
        XCTAssertEqual(discover.kind, "vibedrop_desktop")
        XCTAssertEqual(discover.serverId, "desktop_transport_test")
        XCTAssertEqual(discover.hostname, "transport-test.local")

        let pairURL = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/pair/request"))
        var pairRequest = URLRequest(url: pairURL)
        pairRequest.httpMethod = "POST"
        pairRequest.setValue("application/json", forHTTPHeaderField: "content-type")
        pairRequest.httpBody = Data(#"{"client_id":"client_transport_test","client_name":"一加 Ace 5"}"#.utf8)
        let (pairData, pairResponse) = try await URLSession.shared.data(for: pairRequest)
        XCTAssertEqual((pairResponse as? HTTPURLResponse)?.statusCode, 200)
        let accepted = try JSONDecoder().decode(PairRequestAccepted.self, from: pairData)
        XCTAssertFalse(accepted.requestId.isEmpty)
        try server.pairManager.approve(accepted.requestId)

        let pairStatusURL = try XCTUnwrap(URL(string: "http://127.0.0.1:\(port)/pair/status/\(accepted.requestId)"))
        let (statusData, statusResponse) = try await URLSession.shared.data(from: pairStatusURL)
        XCTAssertEqual((statusResponse as? HTTPURLResponse)?.statusCode, 200)
        let status = try JSONDecoder().decode(PairRequestStatusResponse.self, from: statusData)
        XCTAssertEqual(status.status, "approved")
        XCTAssertEqual(status.pin, "2468")
        XCTAssertEqual(status.serverId, "desktop_transport_test")

        let webSocketURL = try XCTUnwrap(URL(string: "ws://127.0.0.1:\(port)/ws"))
        let task = URLSession.shared.webSocketTask(with: webSocketURL)
        task.resume()
        defer { task.cancel(with: .goingAway, reason: nil) }

        let auth = VibeDropMessage(
            action: .auth,
            pin: "2468",
            deviceId: "client_transport_test",
            baseDeviceId: "client_transport_test",
            deviceName: "一加 Ace 5",
            canReceiveFiles: true,
            receivesClipboard: true,
            deviceRole: "primary"
        )
        try await task.send(.string(String(data: JSONEncoder().encode(auth), encoding: .utf8)!))
        let authReply = try await receiveString(task)
        let authObject = try JSONSerialization.jsonObject(with: Data(authReply.utf8)) as? [String: Any]
        XCTAssertEqual(authObject?["status"] as? String, "ok")
        XCTAssertEqual(authObject?["server_id"] as? String, "desktop_transport_test")
        try await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(server.connectedClientSnapshots.count, 1)
        XCTAssertEqual(server.connectedClientSnapshots.first?.peer.deviceName, "一加 Ace 5")
        XCTAssertEqual(server.connectedClientSnapshots.first?.peer.canReceiveFiles, true)
        try server.broadcastClipboardText("hello clipboard")
        let clipboardReply = try await receiveString(task)
        let clipboardObject = try JSONSerialization.jsonObject(with: Data(clipboardReply.utf8)) as? [String: Any]
        XCTAssertEqual(clipboardObject?["action"] as? String, "clipboard")
        XCTAssertEqual(clipboardObject?["text"] as? String, "hello clipboard")

        try await task.send(.string(#"{"action":"ping"}"#))
        let pongReply = try await receiveString(task)
        let pongObject = try JSONSerialization.jsonObject(with: Data(pongReply.utf8)) as? [String: Any]
        XCTAssertEqual(pongObject?["action"] as? String, "pong")
    }

    private func receiveString(_ task: URLSessionWebSocketTask) async throws -> String {
        let message = try await task.receive()
        switch message {
        case let .string(value):
            return value
        case let .data(data):
            return String(decoding: data, as: UTF8.self)
        @unknown default:
            throw NSError(domain: "MacServerTransportTests", code: 1)
        }
    }
}

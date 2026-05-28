import Foundation
import VibeDropNativeCore

public struct ConnectedPeer: Codable, Equatable, Sendable {
    public var sessionId: UInt64
    public var deviceId: String
    public var baseDeviceId: String
    public var deviceName: String
    public var canReceiveFiles: Bool
    public var receivesClipboard: Bool
    public var deviceRole: String

    public init(
        sessionId: UInt64,
        deviceId: String,
        baseDeviceId: String,
        deviceName: String,
        canReceiveFiles: Bool,
        receivesClipboard: Bool,
        deviceRole: String
    ) {
        self.sessionId = sessionId
        self.deviceId = deviceId
        self.baseDeviceId = baseDeviceId
        self.deviceName = deviceName
        self.canReceiveFiles = canReceiveFiles
        self.receivesClipboard = receivesClipboard
        self.deviceRole = deviceRole
    }
}

public struct MacServerStatusEnvelope: Codable, Equatable, Sendable {
    public var status: String
    public var hostname: String?
    public var serverId: String?
    public var error: String?

    public init(
        status: String,
        hostname: String? = nil,
        serverId: String? = nil,
        error: String? = nil
    ) {
        self.status = status
        self.hostname = hostname
        self.serverId = serverId
        self.error = error
    }

    enum CodingKeys: String, CodingKey {
        case status
        case hostname
        case serverId = "server_id"
        case error
    }
}

public enum MacServerOutbound: Equatable, Sendable {
    case status(MacServerStatusEnvelope)
    case action(VibeDropAction)

    public func jsonData() throws -> Data {
        switch self {
        case let .status(envelope):
            return try JSONEncoder().encode(envelope)
        case let .action(action):
            return try JSONEncoder().encode(["action": action.rawValue])
        }
    }
}

public enum MacServerEffect: Equatable, Sendable {
    case authenticated(ConnectedPeer)
    case typeText(String, peer: ConnectedPeer)
    case typeTextAndEnter(String, peer: ConnectedPeer)
    case pressEnter(peer: ConnectedPeer)
    case imageClipboard(VibeDropMessage, peer: ConnectedPeer)
    case legacyFileDownload(VibeDropMessage, peer: ConnectedPeer)
    case incomingFileStart(VibeDropMessage, peer: ConnectedPeer)
    case incomingFileChunk(VibeDropMessage, peer: ConnectedPeer)
    case incomingFileComplete(VibeDropMessage, peer: ConnectedPeer)
    case incomingFileSaved(transferId: String, savedPath: String?, peer: ConnectedPeer)
    case incomingFileError(transferId: String, error: String?, peer: ConnectedPeer)
}

public struct MacWebSocketResult: Equatable, Sendable {
    public var outbound: [MacServerOutbound]
    public var effects: [MacServerEffect]

    public init(outbound: [MacServerOutbound] = [], effects: [MacServerEffect] = []) {
        self.outbound = outbound
        self.effects = effects
    }
}

public final class MacWebSocketSession: @unchecked Sendable {
    public let sessionId: UInt64
    private let configuration: MacServerConfiguration
    private var peer: ConnectedPeer?

    public init(sessionId: UInt64, configuration: MacServerConfiguration) {
        self.sessionId = sessionId
        self.configuration = configuration
    }

    public var authenticatedPeer: ConnectedPeer? {
        peer
    }

    public func handle(_ message: VibeDropMessage) -> MacWebSocketResult {
        switch message.action {
        case .auth:
            return handleAuth(message)
        case .ping:
            return MacWebSocketResult(outbound: [.action(.pong)])
        case .pong:
            return MacWebSocketResult()
        default:
            guard let peer else {
                return MacWebSocketResult(outbound: [.status(.error("未认证"))])
            }
            return handleAuthenticated(message, peer: peer)
        }
    }

    private func handleAuth(_ message: VibeDropMessage) -> MacWebSocketResult {
        guard message.pin == configuration.pin else {
            return MacWebSocketResult(outbound: [.status(.error("PIN 码错误"))])
        }
        let deviceId = nonEmpty(message.deviceId) ?? "client-\(sessionId)"
        let deviceName = nonEmpty(message.deviceName) ?? "手机 \(sessionId)"
        let baseDeviceId = nonEmpty(message.baseDeviceId) ?? deviceId
        let connectedPeer = ConnectedPeer(
            sessionId: sessionId,
            deviceId: deviceId,
            baseDeviceId: baseDeviceId,
            deviceName: deviceName,
            canReceiveFiles: message.canReceiveFiles ?? false,
            receivesClipboard: message.receivesClipboard ?? false,
            deviceRole: nonEmpty(message.deviceRole) ?? "primary"
        )
        peer = connectedPeer
        return MacWebSocketResult(
            outbound: [
                .status(
                    MacServerStatusEnvelope(
                        status: "ok",
                        hostname: configuration.hostname,
                        serverId: configuration.serverId
                    )
                )
            ],
            effects: [.authenticated(connectedPeer)]
        )
    }

    private func handleAuthenticated(
        _ message: VibeDropMessage,
        peer: ConnectedPeer
    ) -> MacWebSocketResult {
        switch message.action {
        case .type:
            guard let text = nonEmpty(message.text) else {
                return MacWebSocketResult(outbound: [.status(.error("缺少文本"))])
            }
            return MacWebSocketResult(effects: [.typeText(text, peer: peer)])
        case .typeEnter:
            guard let text = nonEmpty(message.text) else {
                return MacWebSocketResult(outbound: [.status(.error("缺少文本"))])
            }
            return MacWebSocketResult(effects: [.typeTextAndEnter(text, peer: peer)])
        case .enter:
            return MacWebSocketResult(effects: [.pressEnter(peer: peer)])
        case .imageClipboard:
            guard nonEmpty(message.imageBase64) != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少图片数据"))])
            }
            return MacWebSocketResult(effects: [.imageClipboard(message, peer: peer)])
        case .fileDownload:
            guard nonEmpty(message.fileBase64) != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少文件数据"))])
            }
            return MacWebSocketResult(effects: [.legacyFileDownload(message, peer: peer)])
        case .incomingFileStart:
            guard nonEmpty(message.transferId) != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少传输标识"))])
            }
            guard message.sizeBytes != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少文件大小"))])
            }
            return MacWebSocketResult(effects: [.incomingFileStart(message, peer: peer)])
        case .incomingFileChunk:
            guard nonEmpty(message.transferId) != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少传输标识"))])
            }
            guard nonEmpty(message.chunkBase64) != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少文件分块数据"))])
            }
            return MacWebSocketResult(effects: [.incomingFileChunk(message, peer: peer)])
        case .incomingFileComplete:
            guard nonEmpty(message.transferId) != nil else {
                return MacWebSocketResult(outbound: [.status(.error("缺少传输标识"))])
            }
            return MacWebSocketResult(effects: [.incomingFileComplete(message, peer: peer)])
        case .incomingFileSaved:
            guard let transferId = nonEmpty(message.transferId) else {
                return MacWebSocketResult(outbound: [.status(.error("缺少传输标识"))])
            }
            return MacWebSocketResult(effects: [
                .incomingFileSaved(transferId: transferId, savedPath: message.savedPath, peer: peer)
            ])
        case .incomingFileError:
            guard let transferId = nonEmpty(message.transferId) else {
                return MacWebSocketResult(outbound: [.status(.error("缺少传输标识"))])
            }
            return MacWebSocketResult(effects: [
                .incomingFileError(transferId: transferId, error: message.error, peer: peer)
            ])
        case .incomingHistorySessionStart:
            return MacWebSocketResult()
        case .auth, .ping, .pong, .clipboard:
            return MacWebSocketResult()
        }
    }

    private func nonEmpty(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        return value
    }
}

private extension MacServerStatusEnvelope {
    static func error(_ message: String) -> MacServerStatusEnvelope {
        MacServerStatusEnvelope(status: "error", error: message)
    }
}

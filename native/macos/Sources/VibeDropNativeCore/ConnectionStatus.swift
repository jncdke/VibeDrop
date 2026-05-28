import Foundation

public enum ConnectionStatus: String, Codable, Sendable {
    case idle
    case connecting
    case authenticating
    case connected
    case reconnecting
    case failed
    case disabled
}

public struct ConnectionSnapshot: Codable, Equatable, Sendable {
    public var status: ConnectionStatus
    public var lastError: String?
    public var reconnectAttempt: Int

    public init(
        status: ConnectionStatus,
        lastError: String? = nil,
        reconnectAttempt: Int = 0
    ) {
        self.status = status
        self.lastError = lastError
        self.reconnectAttempt = reconnectAttempt
    }

    public var canSend: Bool {
        status == .connected
    }
}

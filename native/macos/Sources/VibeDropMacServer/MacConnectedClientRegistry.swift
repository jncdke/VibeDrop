import Foundation
import NIOCore
import NIOWebSocket

public struct MacConnectedClientSnapshot: Equatable, Sendable {
    public var peer: ConnectedPeer
    public var connectedAt: Date

    public init(peer: ConnectedPeer, connectedAt: Date) {
        self.peer = peer
        self.connectedAt = connectedAt
    }
}

final class MacConnectedClientRegistry: @unchecked Sendable {
    private struct Client {
        var peer: ConnectedPeer
        var channel: Channel
        var connectedAt: Date
    }

    private let lock = NSLock()
    private var clients: [UInt64: Client] = [:]
    private let now: @Sendable () -> Date

    init(now: @escaping @Sendable () -> Date = { Date() }) {
        self.now = now
    }

    func register(peer: ConnectedPeer, channel: Channel) {
        lock.lock()
        clients[peer.sessionId] = Client(peer: peer, channel: channel, connectedAt: now())
        lock.unlock()
    }

    func unregister(sessionId: UInt64) {
        lock.lock()
        clients.removeValue(forKey: sessionId)
        lock.unlock()
    }

    func snapshots() -> [MacConnectedClientSnapshot] {
        lock.lock()
        let values = clients.values
            .map { MacConnectedClientSnapshot(peer: $0.peer, connectedAt: $0.connectedAt) }
            .sorted { lhs, rhs in
                if lhs.peer.deviceRole != rhs.peer.deviceRole {
                    return lhs.peer.deviceRole < rhs.peer.deviceRole
                }
                return lhs.connectedAt < rhs.connectedAt
            }
        lock.unlock()
        return values
    }

    func hasFileReceiver() -> Bool {
        lock.lock()
        let hasReceiver = clients.values.contains { $0.peer.canReceiveFiles }
        lock.unlock()
        return hasReceiver
    }

    func send(data: Data, toSessionId sessionId: UInt64) throws {
        lock.lock()
        let channel = clients[sessionId]?.channel
        lock.unlock()

        guard let channel else {
            throw MacConnectedClientRegistryError.clientNotConnected
        }

        channel.eventLoop.execute {
            var buffer = channel.allocator.buffer(capacity: data.count)
            buffer.writeBytes(data)
            let frame = WebSocketFrame(fin: true, opcode: .text, data: buffer)
            channel.writeAndFlush(frame, promise: nil)
        }
    }

    func broadcast(data: Data, where shouldSend: @Sendable (ConnectedPeer) -> Bool) {
        lock.lock()
        let channels = clients.values
            .filter { shouldSend($0.peer) }
            .map(\.channel)
        lock.unlock()

        for channel in channels {
            channel.eventLoop.execute {
                var buffer = channel.allocator.buffer(capacity: data.count)
                buffer.writeBytes(data)
                let frame = WebSocketFrame(fin: true, opcode: .text, data: buffer)
                channel.writeAndFlush(frame, promise: nil)
            }
        }
    }
}

public enum MacConnectedClientRegistryError: LocalizedError, Equatable {
    case clientNotConnected

    public var errorDescription: String? {
        switch self {
        case .clientNotConnected:
            return "手机连接已断开"
        }
    }
}

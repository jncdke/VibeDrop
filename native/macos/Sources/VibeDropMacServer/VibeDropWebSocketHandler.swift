import Foundation
import NIOCore
import NIOWebSocket
import VibeDropNativeCore

final class VibeDropWebSocketHandler: ChannelInboundHandler {
    typealias InboundIn = WebSocketFrame
    typealias OutboundOut = WebSocketFrame

    private let session: MacWebSocketSession
    private let effectHandler: MacServerEffectHandler
    private let connectedClients: MacConnectedClientRegistry

    init(
        configuration: MacServerConfiguration,
        effectHandler: @escaping MacServerEffectHandler,
        connectedClients: MacConnectedClientRegistry
    ) {
        self.session = MacWebSocketSession(
            sessionId: MacWebSocketSessionID.next(),
            configuration: configuration
        )
        self.effectHandler = effectHandler
        self.connectedClients = connectedClients
    }

    func channelInactive(context: ChannelHandlerContext) {
        connectedClients.unregister(sessionId: session.sessionId)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let frame = unwrapInboundIn(data)
        switch frame.opcode {
        case .connectionClose:
            context.close(promise: nil)
        case .ping:
            var payload = frame.unmaskedData
            writeFrame(context: context, opcode: .pong, data: &payload)
        case .text:
            handleText(context: context, frame: frame)
        default:
            break
        }
    }

    private func handleText(context: ChannelHandlerContext, frame: WebSocketFrame) {
        var payload = frame.unmaskedData
        guard let text = payload.readString(length: payload.readableBytes) else { return }
        do {
            let message = try JSONDecoder().decode(VibeDropMessage.self, from: Data(text.utf8))
            let result = session.handle(message)
            try send(outbound: result.outbound, context: context)
            for effect in result.effects {
                if case let .authenticated(peer) = effect {
                    connectedClients.register(peer: peer, channel: context.channel)
                }
                try send(outbound: effectHandler(effect), context: context)
            }
        } catch {
            let outbound = MacServerOutbound.status(
                MacServerStatusEnvelope(
                    status: "error",
                    error: "无效的 JSON 格式"
                )
            )
            try? send(outbound: [outbound], context: context)
        }
    }

    private func send(outbound: [MacServerOutbound], context: ChannelHandlerContext) throws {
        for item in outbound {
            let data = try item.jsonData()
            var buffer = context.channel.allocator.buffer(capacity: data.count)
            buffer.writeBytes(data)
            writeFrame(context: context, opcode: .text, data: &buffer)
        }
    }

    private func writeFrame(
        context: ChannelHandlerContext,
        opcode: WebSocketOpcode,
        data: inout ByteBuffer
    ) {
        let frame = WebSocketFrame(fin: true, opcode: opcode, data: data)
        context.writeAndFlush(wrapOutboundOut(frame), promise: nil)
    }
}

private enum MacWebSocketSessionID {
    private static let lock = NSLock()
    private static var last: UInt64 = 0

    static func next() -> UInt64 {
        lock.lock()
        defer { lock.unlock() }
        let timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        let value = max(timestamp, last + 1)
        last = value
        return value
    }
}

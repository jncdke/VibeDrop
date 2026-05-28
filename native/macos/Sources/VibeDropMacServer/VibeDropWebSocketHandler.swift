import Foundation
import NIOCore
import NIOWebSocket
import VibeDropNativeCore

final class VibeDropWebSocketHandler: ChannelInboundHandler {
    typealias InboundIn = WebSocketFrame
    typealias OutboundOut = WebSocketFrame

    private let session: MacWebSocketSession
    private let effectHandler: MacServerEffectHandler

    init(
        configuration: MacServerConfiguration,
        effectHandler: @escaping MacServerEffectHandler
    ) {
        self.session = MacWebSocketSession(
            sessionId: UInt64(Date().timeIntervalSince1970 * 1000),
            configuration: configuration
        )
        self.effectHandler = effectHandler
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

import Foundation
import NIOCore

final class VibeDropUDPDiscoveryHandler: ChannelInboundHandler {
    typealias InboundIn = AddressedEnvelope<ByteBuffer>
    typealias OutboundOut = AddressedEnvelope<ByteBuffer>

    private let configuration: MacServerConfiguration

    init(configuration: MacServerConfiguration) {
        self.configuration = configuration
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let envelope = unwrapInboundIn(data)
        var buffer = envelope.data
        guard let raw = buffer.readString(length: buffer.readableBytes),
              let probe = try? JSONDecoder().decode(DiscoverProbe.self, from: Data(raw.utf8)),
              probe.kind == "discover_probe",
              probe.protocolVersion <= configuration.protocolVersion else {
            return
        }

        let response = DiscoverResponse(configuration: configuration)
        guard let responseData = try? JSONEncoder().encode(response) else { return }
        var responseBuffer = context.channel.allocator.buffer(capacity: responseData.count)
        responseBuffer.writeBytes(responseData)
        let responseEnvelope = AddressedEnvelope(
            remoteAddress: envelope.remoteAddress,
            data: responseBuffer
        )
        context.writeAndFlush(wrapOutboundOut(responseEnvelope), promise: nil)
    }
}

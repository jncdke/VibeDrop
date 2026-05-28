import Foundation
import NIOCore
import NIOHTTP1

final class VibeDropHTTPHandler: ChannelInboundHandler, RemovableChannelHandler {
    static let handlerName = "VibeDropHTTPHandler"

    typealias InboundIn = HTTPServerRequestPart
    typealias OutboundOut = HTTPServerResponsePart

    private let configuration: MacServerConfiguration
    private let pairManager: PairRequestManager
    private var requestHead: HTTPRequestHead?
    private var requestBody: ByteBuffer?

    init(configuration: MacServerConfiguration, pairManager: PairRequestManager) {
        self.configuration = configuration
        self.pairManager = pairManager
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        switch unwrapInboundIn(data) {
        case let .head(head):
            requestHead = head
            requestBody = context.channel.allocator.buffer(capacity: 0)
        case var .body(buffer):
            requestBody?.writeBuffer(&buffer)
        case .end:
            handleRequest(context: context)
            requestHead = nil
            requestBody = nil
        }
    }

    private func handleRequest(context: ChannelHandlerContext) {
        guard let head = requestHead else {
            sendJSON(context: context, status: .badRequest, payload: ["error": "缺少请求头"])
            return
        }

        let path = head.uri.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? head.uri
        switch (head.method, path) {
        case (.GET, "/discover"):
            let response = DiscoverResponse(configuration: configuration)
            sendEncodable(context: context, response)
        case (.POST, "/pair/request"):
            handlePairRequest(context: context)
        case (.GET, let value) where value.hasPrefix("/pair/status/"):
            let requestId = String(value.dropFirst("/pair/status/".count))
            let response = pairManager.status(requestId: requestId, configuration: configuration)
            sendEncodable(context: context, response)
        default:
            sendJSON(context: context, status: .notFound, payload: ["error": "not_found"])
        }
    }

    private func handlePairRequest(context: ChannelHandlerContext) {
        do {
            var body = requestBody ?? context.channel.allocator.buffer(capacity: 0)
            let bytes = body.readBytes(length: body.readableBytes) ?? []
            let data = Data(bytes)
            let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let clientId = object?["client_id"] as? String ?? ""
            let clientName = object?["client_name"] as? String ?? ""
            let accepted = try pairManager.requestPairing(
                clientId: clientId,
                clientName: clientName,
                configuration: configuration
            )
            sendEncodable(context: context, accepted)
        } catch {
            sendJSON(
                context: context,
                status: .badRequest,
                payload: ["error": error.localizedDescription]
            )
        }
    }

    private func sendEncodable<T: Encodable>(
        context: ChannelHandlerContext,
        _ value: T,
        status: HTTPResponseStatus = .ok
    ) {
        do {
            let data = try JSONEncoder.vibeDropServer.encode(value)
            sendData(context: context, status: status, data: data)
        } catch {
            sendJSON(context: context, status: .internalServerError, payload: ["error": error.localizedDescription])
        }
    }

    private func sendJSON(
        context: ChannelHandlerContext,
        status: HTTPResponseStatus,
        payload: [String: String]
    ) {
        let data = (try? JSONSerialization.data(withJSONObject: payload)) ?? Data("{}".utf8)
        sendData(context: context, status: status, data: data)
    }

    private func sendData(
        context: ChannelHandlerContext,
        status: HTTPResponseStatus,
        data: Data
    ) {
        var headers = HTTPHeaders()
        headers.add(name: "content-type", value: "application/json; charset=utf-8")
        headers.add(name: "content-length", value: "\(data.count)")
        headers.add(name: "connection", value: "close")
        let head = HTTPResponseHead(version: .http1_1, status: status, headers: headers)
        var buffer = context.channel.allocator.buffer(capacity: data.count)
        buffer.writeBytes(data)
        context.write(wrapOutboundOut(.head(head)), promise: nil)
        context.write(wrapOutboundOut(.body(.byteBuffer(buffer))), promise: nil)
        context.writeAndFlush(wrapOutboundOut(.end(nil))).whenComplete { _ in
            context.close(promise: nil)
        }
    }
}

private extension JSONEncoder {
    static var vibeDropServer: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

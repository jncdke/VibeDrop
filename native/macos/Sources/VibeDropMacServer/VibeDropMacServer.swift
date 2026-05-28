import Foundation
import NIOCore
import NIOHTTP1
import NIOPosix
import NIOWebSocket

public final class VibeDropMacServer: @unchecked Sendable {
    public let configuration: MacServerConfiguration
    public let pairManager: PairRequestManager

    private let group: MultiThreadedEventLoopGroup
    private let effectHandler: MacServerEffectHandler
    private let connectedClients: MacConnectedClientRegistry
    private var channel: Channel?
    private var udpChannel: Channel?

    public init(
        configuration: MacServerConfiguration,
        pairManager: PairRequestManager = PairRequestManager(),
        effectHandler: @escaping MacServerEffectHandler = MacServerDefaultEffectHandler.preview,
        threadCount: Int = System.coreCount
    ) {
        self.configuration = configuration
        self.pairManager = pairManager
        self.effectHandler = effectHandler
        self.connectedClients = MacConnectedClientRegistry()
        self.group = MultiThreadedEventLoopGroup(numberOfThreads: max(1, threadCount))
    }

    public var boundPort: Int? {
        channel?.localAddress?.port
    }

    public var connectedClientSnapshots: [MacConnectedClientSnapshot] {
        connectedClients.snapshots()
    }

    public func send(data: Data, toSessionId sessionId: UInt64) throws {
        try connectedClients.send(data: data, toSessionId: sessionId)
    }

    public func broadcastClipboardText(_ text: String) throws {
        let data = try JSONEncoder().encode(ClipboardBroadcastMessage(text: text))
        connectedClients.broadcast(data: data) { peer in
            peer.receivesClipboard
        }
    }

    public func start(host: String = "0.0.0.0", enableUDPDiscovery: Bool = true) throws {
        guard channel == nil else { return }

        let configuration = self.configuration
        let pairManager = self.pairManager
        let effectHandler = self.effectHandler
        let connectedClients = self.connectedClients
        let bootstrap = ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.backlog, value: 256)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelInitializer { channel in
                let webSocketUpgrader = NIOWebSocketServerUpgrader(
                    shouldUpgrade: { channel, head in
                        guard head.uri == "/ws" else {
                            return channel.eventLoop.makeFailedFuture(MacServerTransportError.unsupportedWebSocketPath)
                        }
                        return channel.eventLoop.makeSucceededFuture([:])
                    },
                    upgradePipelineHandler: { channel, _ in
                        let handler = VibeDropWebSocketHandler(
                            configuration: configuration,
                            effectHandler: effectHandler,
                            connectedClients: connectedClients
                        )
                        return channel.pipeline.removeHandler(name: VibeDropHTTPHandler.handlerName)
                            .flatMap {
                                channel.pipeline.addHandler(handler)
                            }
                            .flatMapError { _ in
                                channel.pipeline.addHandler(handler)
                            }
                    }
                )
                let upgradeConfig: NIOHTTPServerUpgradeConfiguration = (
                    upgraders: [webSocketUpgrader],
                    completionHandler: { _ in }
                )
                return channel.pipeline.configureHTTPServerPipeline(
                    withServerUpgrade: upgradeConfig,
                    withErrorHandling: true
                ).flatMap {
                    channel.pipeline.addHandler(
                        VibeDropHTTPHandler(
                            configuration: configuration,
                            pairManager: pairManager
                        ),
                        name: VibeDropHTTPHandler.handlerName
                    )
                }
            }
            .childChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelOption(ChannelOptions.maxMessagesPerRead, value: 16)

        channel = try bootstrap.bind(host: host, port: configuration.port).wait()
        if enableUDPDiscovery {
            udpChannel = try DatagramBootstrap(group: group)
                .channelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
                .channelInitializer { channel in
                    channel.pipeline.addHandler(
                        VibeDropUDPDiscoveryHandler(configuration: configuration)
                    )
                }
                .bind(host: host, port: configuration.port)
                .wait()
        }
    }

    public func stop() throws {
        try udpChannel?.close().wait()
        udpChannel = nil
        try channel?.close().wait()
        channel = nil
        try group.syncShutdownGracefully()
    }
}

enum MacServerTransportError: Error {
    case unsupportedWebSocketPath
}

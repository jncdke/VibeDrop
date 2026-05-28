import Foundation

public struct DiscoverProbe: Codable, Equatable, Sendable {
    public var kind: String
    public var protocolVersion: Int

    public init(kind: String = "discover_probe", protocolVersion: Int = 1) {
        self.kind = kind
        self.protocolVersion = protocolVersion
    }

    enum CodingKeys: String, CodingKey {
        case kind
        case protocolVersion = "protocol_version"
    }
}

public struct DiscoverResponse: Codable, Equatable, Sendable {
    public var kind: String
    public var serverId: String
    public var hostname: String
    public var ip: String
    public var port: Int
    public var protocolVersion: Int

    public init(
        configuration: MacServerConfiguration,
        advertisedIP: String? = nil
    ) {
        self.kind = "vibedrop_desktop"
        self.serverId = configuration.serverId
        self.hostname = configuration.hostname
        self.ip = advertisedIP ?? configuration.ip
        self.port = configuration.port
        self.protocolVersion = configuration.protocolVersion
    }

    enum CodingKeys: String, CodingKey {
        case kind
        case serverId = "server_id"
        case hostname
        case ip
        case port
        case protocolVersion = "protocol_version"
    }
}

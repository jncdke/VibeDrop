import Foundation

public struct MacServerConfiguration: Codable, Equatable, Sendable {
    public var serverId: String
    public var pin: String
    public var hostname: String
    public var ip: String
    public var port: Int
    public var protocolVersion: Int

    public init(
        serverId: String,
        pin: String,
        hostname: String,
        ip: String,
        port: Int = 9001,
        protocolVersion: Int = 1
    ) {
        self.serverId = serverId
        self.pin = pin
        self.hostname = hostname
        self.ip = ip
        self.port = port
        self.protocolVersion = protocolVersion
    }
}

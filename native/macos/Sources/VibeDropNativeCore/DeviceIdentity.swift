import Foundation

public struct DeviceIdentity: Codable, Equatable, Sendable {
    public var deviceId: String
    public var baseDeviceId: String?
    public var displayName: String
    public var role: String?
    public var host: String?
    public var ip: String?
    public var port: Int?

    public init(
        deviceId: String,
        baseDeviceId: String? = nil,
        displayName: String,
        role: String? = nil,
        host: String? = nil,
        ip: String? = nil,
        port: Int? = nil
    ) {
        self.deviceId = deviceId
        self.baseDeviceId = baseDeviceId
        self.displayName = displayName
        self.role = role
        self.host = host
        self.ip = ip
        self.port = port
    }
}

import Foundation

public struct VibeDropMessage: Codable, Equatable, Sendable {
    public var action: VibeDropAction
    public var pin: String?
    public var deviceId: String?
    public var baseDeviceId: String?
    public var deviceName: String?
    public var canReceiveFiles: Bool?
    public var receivesClipboard: Bool?
    public var deviceRole: String?
    public var text: String?
    public var fileName: String?
    public var mimeType: String?
    public var imageBase64: String?
    public var fileBase64: String?
    public var transferId: String?
    public var chunkBase64: String?
    public var sizeBytes: Int64?
    public var savedPath: String?
    public var error: String?
    public var sessionId: String?
    public var itemCount: Int?
    public var saveTarget: String?

    public init(
        action: VibeDropAction,
        pin: String? = nil,
        deviceId: String? = nil,
        baseDeviceId: String? = nil,
        deviceName: String? = nil,
        canReceiveFiles: Bool? = nil,
        receivesClipboard: Bool? = nil,
        deviceRole: String? = nil,
        text: String? = nil,
        fileName: String? = nil,
        mimeType: String? = nil,
        imageBase64: String? = nil,
        fileBase64: String? = nil,
        transferId: String? = nil,
        chunkBase64: String? = nil,
        sizeBytes: Int64? = nil,
        savedPath: String? = nil,
        error: String? = nil,
        sessionId: String? = nil,
        itemCount: Int? = nil,
        saveTarget: String? = nil
    ) {
        self.action = action
        self.pin = pin
        self.deviceId = deviceId
        self.baseDeviceId = baseDeviceId
        self.deviceName = deviceName
        self.canReceiveFiles = canReceiveFiles
        self.receivesClipboard = receivesClipboard
        self.deviceRole = deviceRole
        self.text = text
        self.fileName = fileName
        self.mimeType = mimeType
        self.imageBase64 = imageBase64
        self.fileBase64 = fileBase64
        self.transferId = transferId
        self.chunkBase64 = chunkBase64
        self.sizeBytes = sizeBytes
        self.savedPath = savedPath
        self.error = error
        self.sessionId = sessionId
        self.itemCount = itemCount
        self.saveTarget = saveTarget
    }

    enum CodingKeys: String, CodingKey {
        case action
        case pin
        case deviceId = "device_id"
        case baseDeviceId = "base_device_id"
        case deviceName = "device_name"
        case canReceiveFiles = "can_receive_files"
        case receivesClipboard = "receives_clipboard"
        case deviceRole = "device_role"
        case text
        case fileName = "file_name"
        case mimeType = "mime_type"
        case imageBase64 = "image_base64"
        case fileBase64 = "file_base64"
        case transferId = "transfer_id"
        case chunkBase64 = "chunk_base64"
        case sizeBytes = "size_bytes"
        case savedPath = "saved_path"
        case error
        case sessionId = "session_id"
        case itemCount = "item_count"
        case saveTarget = "save_target"
    }
}

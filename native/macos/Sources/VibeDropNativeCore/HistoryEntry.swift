import Foundation

public struct HistoryEntry: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var timestamp: Date
    public var direction: String
    public var kind: String
    public var status: String
    public var text: String?
    public var sender: DeviceIdentity?
    public var receiver: DeviceIdentity?
    public var sessionId: String?
    public var itemCount: Int?
    public var saveTarget: String?
    public var items: [HistoryItem]

    public init(
        id: String,
        timestamp: Date,
        direction: String,
        kind: String,
        status: String,
        text: String? = nil,
        sender: DeviceIdentity? = nil,
        receiver: DeviceIdentity? = nil,
        sessionId: String? = nil,
        itemCount: Int? = nil,
        saveTarget: String? = nil,
        items: [HistoryItem] = []
    ) {
        self.id = id
        self.timestamp = timestamp
        self.direction = direction
        self.kind = kind
        self.status = status
        self.text = text
        self.sender = sender
        self.receiver = receiver
        self.sessionId = sessionId
        self.itemCount = itemCount
        self.saveTarget = saveTarget
        self.items = items
    }
}

public struct HistoryItem: Codable, Equatable, Identifiable, Sendable {
    public var id: String
    public var kind: String
    public var fileName: String?
    public var mimeType: String?
    public var sizeBytes: Int64?
    public var localPath: String?
    public var savedPath: String?
    public var thumbnailDataUrl: String?
    public var status: String?
    public var error: String?

    public init(
        id: String,
        kind: String,
        fileName: String? = nil,
        mimeType: String? = nil,
        sizeBytes: Int64? = nil,
        localPath: String? = nil,
        savedPath: String? = nil,
        thumbnailDataUrl: String? = nil,
        status: String? = nil,
        error: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.fileName = fileName
        self.mimeType = mimeType
        self.sizeBytes = sizeBytes
        self.localPath = localPath
        self.savedPath = savedPath
        self.thumbnailDataUrl = thumbnailDataUrl
        self.status = status
        self.error = error
    }
}

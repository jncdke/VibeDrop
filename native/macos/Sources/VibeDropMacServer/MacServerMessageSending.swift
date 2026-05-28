import Foundation

public protocol MacServerMessageSending: AnyObject, Sendable {
    func sendJSONData(_ data: Data, to sessionId: UInt64) throws
}

public protocol MacServerClipboardBroadcasting: AnyObject, Sendable {
    func broadcastClipboardText(_ text: String) throws
}

extension VibeDropMacServer: MacServerMessageSending {
    public func sendJSONData(_ data: Data, to sessionId: UInt64) throws {
        try send(data: data, toSessionId: sessionId)
    }
}

extension VibeDropMacServer: MacServerClipboardBroadcasting {}

struct ClipboardBroadcastMessage: Encodable {
    var action = "clipboard"
    var text: String
}

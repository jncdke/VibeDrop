import AppKit
import Foundation

public protocol MacImageClipboardControlling: Sendable {
    func writeImage(data: Data, mimeType: String?) throws
}

public enum MacImageClipboardError: LocalizedError, Equatable {
    case invalidImageData
    case pasteboardWriteFailed

    public var errorDescription: String? {
        switch self {
        case .invalidImageData:
            return "无法读取图片数据"
        case .pasteboardWriteFailed:
            return "无法写入图片到剪贴板"
        }
    }
}

public final class MacImageClipboardService: MacImageClipboardControlling, @unchecked Sendable {
    public init() {}

    public func writeImage(data: Data, mimeType: String?) throws {
        guard let image = NSImage(data: data) else {
            throw MacImageClipboardError.invalidImageData
        }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        if !pasteboard.writeObjects([image]) {
            throw MacImageClipboardError.pasteboardWriteFailed
        }
    }
}

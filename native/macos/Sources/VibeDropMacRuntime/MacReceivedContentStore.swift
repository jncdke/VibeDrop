import Foundation
import ImageIO
import UniformTypeIdentifiers

public struct MacSavedImage: Equatable, Sendable {
    public var fileName: String
    public var filePath: String
    public var thumbnailDataURL: String?
    public var sizeBytes: Int64
}

public struct MacSavedFile: Equatable, Sendable {
    public var fileName: String
    public var filePath: String
    public var mimeType: String
    public var sizeBytes: Int64
}

public enum MacReceivedContentError: LocalizedError, Equatable {
    case missingTransferId
    case missingChunk
    case transferNotFound
    case invalidBase64
    case sizeMismatch(expected: Int64, actual: Int64)
    case fileOperationFailed(String)

    public var errorDescription: String? {
        switch self {
        case .missingTransferId:
            return "缺少传输标识"
        case .missingChunk:
            return "缺少文件分块数据"
        case .transferNotFound:
            return "接收中的临时文件不存在"
        case .invalidBase64:
            return "文件数据解码失败"
        case let .sizeMismatch(expected, actual):
            return "文件大小校验失败：预期 \(expected) 字节，实际 \(actual) 字节"
        case let .fileOperationFailed(message):
            return message
        }
    }
}

public final class MacReceivedContentStore: @unchecked Sendable {
    private struct TransferState {
        var transferId: String
        var fileName: String
        var mimeType: String
        var sizeBytes: Int64
        var partURL: URL
    }

    private let receivedImagesDirectory: URL
    private let desktopInboxDirectory: URL
    private let incomingTransfersDirectory: URL
    private let fileManager: FileManager
    private var transfers: [String: TransferState] = [:]
    private let lock = NSLock()

    public init(
        receivedImagesDirectory: URL? = nil,
        desktopInboxDirectory: URL? = nil,
        incomingTransfersDirectory: URL? = nil,
        fileManager: FileManager = .default
    ) throws {
        self.fileManager = fileManager
        self.receivedImagesDirectory = try receivedImagesDirectory
            ?? MacRuntimePaths.receivedImagesDirectory(fileManager: fileManager)
        self.desktopInboxDirectory = try desktopInboxDirectory
            ?? MacRuntimePaths.desktopInboxDirectory(fileManager: fileManager)
        self.incomingTransfersDirectory = try incomingTransfersDirectory
            ?? MacRuntimePaths.incomingTransfersDirectory(fileManager: fileManager)
        try createDirectories()
    }

    public func saveImage(
        imageBase64: String,
        fileName: String?,
        mimeType: String?
    ) throws -> (MacSavedImage, Data) {
        let bytes = try decodeBase64(imageBase64)
        let preferredName = preferredImageName(fileName: fileName, mimeType: mimeType)
        let destination = uniqueURL(in: receivedImagesDirectory, fileName: preferredName)
        do {
            try bytes.write(to: destination, options: .atomic)
        } catch {
            throw MacReceivedContentError.fileOperationFailed("无法保存原图: \(error.localizedDescription)")
        }
        let saved = MacSavedImage(
            fileName: destination.lastPathComponent,
            filePath: destination.path,
            thumbnailDataURL: thumbnailDataURL(from: bytes),
            sizeBytes: Int64(bytes.count)
        )
        return (saved, bytes)
    }

    public func saveLegacyFile(
        fileBase64: String,
        fileName: String?,
        mimeType: String?
    ) throws -> MacSavedFile {
        let bytes = try decodeBase64(fileBase64)
        let destination = uniqueURL(
            in: desktopInboxDirectory,
            fileName: sanitizeFileName(fileName, fallback: "file.bin")
        )
        do {
            try bytes.write(to: destination, options: .atomic)
        } catch {
            throw MacReceivedContentError.fileOperationFailed("无法保存文件: \(error.localizedDescription)")
        }
        return MacSavedFile(
            fileName: destination.lastPathComponent,
            filePath: destination.path,
            mimeType: mimeType?.isEmpty == false ? mimeType! : "application/octet-stream",
            sizeBytes: Int64(bytes.count)
        )
    }

    public func beginIncomingFile(
        transferId: String?,
        fileName: String?,
        mimeType: String?,
        sizeBytes: Int64?
    ) throws {
        guard let transferId = transferId?.trimmingCharacters(in: .whitespacesAndNewlines), !transferId.isEmpty else {
            throw MacReceivedContentError.missingTransferId
        }
        try createDirectories()
        let partURL = incomingTransfersDirectory
            .appendingPathComponent("\(sanitizeTransferId(transferId)).part")
        fileManager.createFile(atPath: partURL.path, contents: Data())
        let state = TransferState(
            transferId: transferId,
            fileName: sanitizeFileName(fileName, fallback: "file.bin"),
            mimeType: mimeType?.isEmpty == false ? mimeType! : "application/octet-stream",
            sizeBytes: sizeBytes ?? 0,
            partURL: partURL
        )
        lock.lock()
        transfers[transferId] = state
        lock.unlock()
    }

    public func appendIncomingFileChunk(
        transferId: String?,
        chunkBase64: String?
    ) throws {
        guard let transferId = transferId?.trimmingCharacters(in: .whitespacesAndNewlines), !transferId.isEmpty else {
            throw MacReceivedContentError.missingTransferId
        }
        guard let chunkBase64, !chunkBase64.isEmpty else {
            throw MacReceivedContentError.missingChunk
        }
        let state = try transferState(transferId)
        let bytes = try decodeBase64(chunkBase64)
        do {
            let handle = try FileHandle(forWritingTo: state.partURL)
            defer { try? handle.close() }
            try handle.seekToEnd()
            try handle.write(contentsOf: bytes)
        } catch {
            throw MacReceivedContentError.fileOperationFailed("写入接收临时文件失败: \(error.localizedDescription)")
        }
    }

    public func finishIncomingFile(transferId: String?) throws -> MacSavedFile {
        guard let transferId = transferId?.trimmingCharacters(in: .whitespacesAndNewlines), !transferId.isEmpty else {
            throw MacReceivedContentError.missingTransferId
        }
        let state = try transferState(transferId)
        let actualSize = try fileSize(state.partURL)
        if state.sizeBytes > 0 && actualSize != state.sizeBytes {
            cancelIncomingFile(transferId: transferId)
            throw MacReceivedContentError.sizeMismatch(expected: state.sizeBytes, actual: actualSize)
        }
        let destination = uniqueURL(in: desktopInboxDirectory, fileName: state.fileName)
        do {
            try moveFileWithFallback(from: state.partURL, to: destination)
        } catch let error as MacReceivedContentError {
            throw error
        } catch {
            throw MacReceivedContentError.fileOperationFailed("无法移动文件: \(error.localizedDescription)")
        }
        lock.lock()
        transfers.removeValue(forKey: transferId)
        lock.unlock()
        return MacSavedFile(
            fileName: destination.lastPathComponent,
            filePath: destination.path,
            mimeType: state.mimeType,
            sizeBytes: actualSize
        )
    }

    public func cancelIncomingFile(transferId: String?) {
        guard let transferId, !transferId.isEmpty else { return }
        lock.lock()
        let state = transfers.removeValue(forKey: transferId)
        lock.unlock()
        if let state {
            try? fileManager.removeItem(at: state.partURL)
        }
    }

    private func transferState(_ transferId: String) throws -> TransferState {
        lock.lock()
        let state = transfers[transferId]
        lock.unlock()
        guard let state, fileManager.fileExists(atPath: state.partURL.path) else {
            throw MacReceivedContentError.transferNotFound
        }
        return state
    }

    private func createDirectories() throws {
        do {
            try fileManager.createDirectory(at: receivedImagesDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: desktopInboxDirectory, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: incomingTransfersDirectory, withIntermediateDirectories: true)
        } catch {
            throw MacReceivedContentError.fileOperationFailed("无法创建接收目录: \(error.localizedDescription)")
        }
    }

    private func decodeBase64(_ value: String) throws -> Data {
        let trimmed = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: ",")
            .last ?? value
        guard let data = Data(base64Encoded: trimmed, options: [.ignoreUnknownCharacters]) else {
            throw MacReceivedContentError.invalidBase64
        }
        return data
    }

    private func preferredImageName(fileName: String?, mimeType: String?) -> String {
        let sanitized = sanitizeFileName(fileName, fallback: "")
        if !sanitized.isEmpty && sanitized != "file.bin" {
            return sanitized
        }
        let ext = imageExtension(mimeType: mimeType)
        return "image-\(Int(Date().timeIntervalSince1970 * 1000)).\(ext)"
    }

    private func imageExtension(mimeType: String?) -> String {
        switch mimeType?.lowercased() {
        case "image/jpeg", "image/jpg":
            return "jpg"
        case "image/webp":
            return "webp"
        case "image/gif":
            return "gif"
        default:
            return "png"
        }
    }

    private func thumbnailDataURL(from imageData: Data) -> String? {
        guard let source = CGImageSourceCreateWithData(imageData as CFData, nil) else {
            return nil
        }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 220
        ] as CFDictionary
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else {
            return nil
        }
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output,
            UTType.png.identifier as CFString,
            1,
            nil
        ) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else {
            return nil
        }
        return "data:image/png;base64,\((output as Data).base64EncodedString())"
    }

    private func sanitizeFileName(_ value: String?, fallback: String) -> String {
        let source = value?
            .split(separator: "/")
            .last?
            .split(separator: "\\")
            .last
            .map(String.init) ?? fallback
        let cleaned = source.map { character in
            character == ":" || character.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
                ? "_"
                : String(character)
        }.joined().trimmingCharacters(in: .whitespacesAndNewlines)
        return cleaned.isEmpty ? fallback : cleaned
    }

    private func sanitizeTransferId(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let cleaned = value.unicodeScalars.map { scalar in
            allowed.contains(scalar) ? String(scalar) : "_"
        }.joined()
        return cleaned.isEmpty ? "transfer" : cleaned
    }

    private func uniqueURL(in directory: URL, fileName: String) -> URL {
        let safeName = sanitizeFileName(fileName, fallback: "file.bin")
        var candidate = directory.appendingPathComponent(safeName)
        if !fileManager.fileExists(atPath: candidate.path) {
            return candidate
        }
        let ext = candidate.pathExtension
        let base = candidate.deletingPathExtension().lastPathComponent
        var index = 1
        while fileManager.fileExists(atPath: candidate.path) {
            let nextName = ext.isEmpty ? "\(base)-\(index)" : "\(base)-\(index).\(ext)"
            candidate = directory.appendingPathComponent(nextName)
            index += 1
        }
        return candidate
    }

    private func fileSize(_ url: URL) throws -> Int64 {
        let values = try url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values.fileSize ?? 0)
    }

    private func moveFileWithFallback(from source: URL, to destination: URL) throws {
        do {
            try fileManager.moveItem(at: source, to: destination)
        } catch {
            do {
                try fileManager.copyItem(at: source, to: destination)
                try fileManager.removeItem(at: source)
            } catch {
                throw MacReceivedContentError.fileOperationFailed("无法移动文件: \(error.localizedDescription)")
            }
        }
    }
}

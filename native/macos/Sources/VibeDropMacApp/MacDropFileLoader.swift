import AppKit
import Foundation
import UniformTypeIdentifiers

struct MacLoadedDropPayload {
    var urls: [URL]
    var cleanupDirectories: [URL]
}

enum MacDropLoadError: LocalizedError {
    case message(String)

    var errorDescription: String? {
        switch self {
        case let .message(message):
            return message
        }
    }
}

enum MacDropFileLoader {
    static let typeIdentifiers = [
        UTType.fileURL.identifier,
        UTType.image.identifier,
        UTType.movie.identifier,
        UTType.data.identifier,
        "com.apple.photos.object-reference.asset",
        "com.apple.pasteboard.promised-file-url",
        "com.apple.pasteboard.promised-file-content-type"
    ]

    static func load(
        from providers: [NSItemProvider],
        completion: @escaping (Result<MacLoadedDropPayload, MacDropLoadError>) -> Void
    ) {
        let stagingDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("vibedrop-native-drop-\(UUID().uuidString)", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
        } catch {
            completion(.failure(.message("无法创建拖拽暂存目录：\(error.localizedDescription)")))
            return
        }

        var urls: [URL] = []
        var firstError: String?
        var containsPhotosReference = false
        let lock = NSLock()
        let group = DispatchGroup()

        func appendURL(_ url: URL) {
            lock.lock()
            defer { lock.unlock() }
            urls.append(url)
        }

        func recordError(_ message: String) {
            lock.lock()
            defer { lock.unlock() }
            if firstError == nil {
                firstError = message
            }
        }

        for provider in providers {
            if provider.registeredTypeIdentifiers.contains("com.apple.photos.object-reference.asset") {
                containsPhotosReference = true
            }
            if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, error in
                    defer { group.leave() }
                    if let error {
                        recordError(error.localizedDescription)
                        return
                    }
                    if let data = item as? Data,
                       let url = URL(dataRepresentation: data, relativeTo: nil) {
                        appendURL(url)
                    } else if let url = item as? URL {
                        appendURL(url)
                    }
                }
                continue
            }

            guard let typeIdentifier = preferredFileRepresentationType(for: provider) else {
                continue
            }
            group.enter()
            provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { fileURL, error in
                defer { group.leave() }
                if let error {
                    recordError(error.localizedDescription)
                    return
                }
                guard let fileURL else {
                    recordError("拖拽内容没有提供可读取文件")
                    return
                }
                do {
                    let copied = try copyDropRepresentation(
                        from: fileURL,
                        provider: provider,
                        typeIdentifier: typeIdentifier,
                        into: stagingDirectory
                    )
                    appendURL(copied)
                } catch {
                    recordError(error.localizedDescription)
                }
            }
        }

        group.notify(queue: .main) {
            lock.lock()
            let loadedURLs = urls
            let errorMessage = firstError
            lock.unlock()

            if !loadedURLs.isEmpty {
                completion(.success(MacLoadedDropPayload(urls: loadedURLs, cleanupDirectories: [stagingDirectory])))
                return
            }
            if containsPhotosReference {
                exportPhotosSelection(into: stagingDirectory) { result in
                    switch result {
                    case let .success(exportedURLs):
                        completion(.success(MacLoadedDropPayload(urls: exportedURLs, cleanupDirectories: [stagingDirectory])))
                    case let .failure(error):
                        try? FileManager.default.removeItem(at: stagingDirectory)
                        completion(.failure(error))
                    }
                }
                return
            }
            try? FileManager.default.removeItem(at: stagingDirectory)
            completion(.failure(.message(errorMessage ?? "拖入的内容不是可发送文件")))
        }
    }

    private static func preferredFileRepresentationType(for provider: NSItemProvider) -> String? {
        let ignoredTypes = Set([
            "com.apple.photos.object-reference.asset",
            "com.apple.pasteboard.promised-file-url",
            "com.apple.pasteboard.promised-file-content-type"
        ])
        let registeredTypes = provider.registeredTypeIdentifiers.filter { !ignoredTypes.contains($0) }
        if let image = registeredTypes.first(where: { UTType($0)?.conforms(to: .image) == true }) {
            return image
        }
        if let movie = registeredTypes.first(where: { UTType($0)?.conforms(to: .movie) == true }) {
            return movie
        }
        if let data = registeredTypes.first(where: { UTType($0)?.conforms(to: .data) == true }) {
            return data
        }
        return nil
    }

    private static func copyDropRepresentation(
        from sourceURL: URL,
        provider: NSItemProvider,
        typeIdentifier: String,
        into directory: URL
    ) throws -> URL {
        var preferredName = provider.suggestedName ?? sourceURL.lastPathComponent
        if (preferredName as NSString).pathExtension.isEmpty,
           let ext = sourceURL.pathExtension.isEmpty ? UTType(typeIdentifier)?.preferredFilenameExtension : sourceURL.pathExtension {
            preferredName += ".\(ext)"
        }
        let destination = uniqueDropURL(in: directory, preferredName: safeDropFileName(preferredName))
        try FileManager.default.copyItem(at: sourceURL, to: destination)
        return destination
    }

    private static func uniqueDropURL(in directory: URL, preferredName: String) -> URL {
        var candidate = directory.appendingPathComponent(preferredName)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }
        let base = (preferredName as NSString).deletingPathExtension
        let ext = (preferredName as NSString).pathExtension
        var index = 1
        while FileManager.default.fileExists(atPath: candidate.path) {
            let name = ext.isEmpty ? "\(base)-\(index)" : "\(base)-\(index).\(ext)"
            candidate = directory.appendingPathComponent(name)
            index += 1
        }
        return candidate
    }

    private static func safeDropFileName(_ value: String) -> String {
        let fallback = "VibeDrop-\(Int(Date().timeIntervalSince1970))"
        let sanitized = value
            .split(separator: "/", omittingEmptySubsequences: false)
            .last
            .map(String.init)?
            .split(separator: "\\", omittingEmptySubsequences: false)
            .last
            .map(String.init) ?? fallback
        let cleaned = sanitized.map { character in
            character.isASCII && (character == ":" || character.unicodeScalars.contains { CharacterSet.controlCharacters.contains($0) })
                ? "_"
                : String(character)
        }.joined()
        let trimmed = cleaned.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? fallback : trimmed
    }

    private static func exportPhotosSelection(
        into directory: URL,
        completion: @escaping (Result<[URL], MacDropLoadError>) -> Void
    ) {
        DispatchQueue.global(qos: .userInitiated).async {
            let result: Result<[URL], MacDropLoadError>
            do {
                let exported = try runPhotosSelectionExport(into: directory)
                result = .success(exported)
            } catch {
                result = .failure(.message((error as? LocalizedError)?.errorDescription ?? error.localizedDescription))
            }
            DispatchQueue.main.async {
                completion(result)
            }
        }
    }

    private static func runPhotosSelectionExport(into directory: URL) throws -> [URL] {
        let escapedPath = directory.path
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        let script = """
        tell application "Photos"
            set selectedItems to selection
            if (count of selectedItems) is 0 then error "Photos 当前没有选中任何项目"
            export selectedItems to POSIX file "\(escapedPath)" with using originals
        end tell
        """
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
        process.arguments = ["-e", script]
        let stderr = Pipe()
        let stdout = Pipe()
        process.standardError = stderr
        process.standardOutput = stdout
        try process.run()
        process.waitUntilExit()

        if process.terminationStatus != 0 {
            let stderrText = String(
                decoding: stderr.fileHandleForReading.readDataToEndOfFile(),
                as: UTF8.self
            ).trimmingCharacters(in: .whitespacesAndNewlines)
            let stdoutText = String(
                decoding: stdout.fileHandleForReading.readDataToEndOfFile(),
                as: UTF8.self
            ).trimmingCharacters(in: .whitespacesAndNewlines)
            let message = stderrText.isEmpty ? stdoutText : stderrText
            if message.contains("Not authorized to send Apple events") {
                throw MacDropLoadError.message("VibeDrop 还没有获得控制 Photos 的权限，请在“系统设置 -> 隐私与安全性 -> 自动化”里允许后再试一次")
            }
            throw MacDropLoadError.message(message.isEmpty ? "无法从 Photos 导出当前拖拽项目" : "无法从 Photos 导出当前拖拽项目：\(message)")
        }

        let exportedURLs = recursiveFileURLs(in: directory)
        guard !exportedURLs.isEmpty else {
            throw MacDropLoadError.message("Photos 没有导出任何可发送文件")
        }
        return exportedURLs
    }

    private static func recursiveFileURLs(in directory: URL) -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else {
            return []
        }
        return enumerator.compactMap { item in
            guard let url = item as? URL else { return nil }
            let values = try? url.resourceValues(forKeys: [.isRegularFileKey])
            return values?.isRegularFile == true ? url : nil
        }.sorted { $0.path < $1.path }
    }
}

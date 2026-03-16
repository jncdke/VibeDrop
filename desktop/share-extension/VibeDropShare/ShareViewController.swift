import Cocoa
import Social

final class ShareViewController: SLComposeServiceViewController {
    private let localShareEndpoint = URL(string: "http://127.0.0.1:9001/share-extension/paths")!
    private let fallbackTypeIdentifiers = [
        "public.file-url",
        "com.apple.file-url",
        "public.item",
        "public.content",
        "public.data",
        "public.image"
    ]

    private var discoveredFileURLs: [URL] = []
    private var attachmentsLoading = false
    private var isSubmittingShare = false
    private var loadFailureMessage: String?
    private var lastStatusText = ""
    private var autoSubmissionStarted = false

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "发送到 VibeDrop"
        updateStatusText("正在读取 Finder 选中的文件…")
        textView.isEditable = false
        loadSharedItems()
    }

    override func isContentValid() -> Bool {
        if isSubmittingShare {
            updateStatusText("正在提交到 VibeDrop…")
            return false
        }

        if attachmentsLoading {
            updateStatusText("正在读取 Finder 选中的文件…")
            return false
        }

        if let loadFailureMessage, discoveredFileURLs.isEmpty {
            updateStatusText(loadFailureMessage)
            return false
        }

        if discoveredFileURLs.isEmpty {
            updateStatusText("当前没有可发送的文件或文件夹")
            return false
        }

        let itemCount = discoveredFileURLs.count
        let statusText = itemCount == 1
            ? "将发送 1 个项目到当前已连接手机"
            : "将发送 \(itemCount) 个项目到当前已连接手机"
        updateStatusText(statusText)
        attemptAutoSubmitIfReady()
        return true
    }

    override func didSelectPost() {
        submitShareRequest()
    }

    private func submitShareRequest() {
        guard !isSubmittingShare else {
            return
        }
        guard !discoveredFileURLs.isEmpty else {
            completeWithError("没有可发送的文件或文件夹。")
            return
        }

        isSubmittingShare = true
        validateContent()

        let payload: [String: Any] = [
            "paths": discoveredFileURLs.map(\.path),
            "source": "finder-share-extension",
        ]

        let body: Data
        do {
            body = try JSONSerialization.data(withJSONObject: payload, options: [])
        } catch {
            completeWithError("生成共享请求失败：\(error.localizedDescription)")
            return
        }

        var request = URLRequest(url: localShareEndpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self else {
                    return
                }

                self.isSubmittingShare = false

                if let error {
                    self.updateStatusText(self.messageForTransportError(error))
                    self.completeWithError(self.messageForTransportError(error))
                    return
                }

                let httpResponse = response as? HTTPURLResponse
                guard let httpResponse else {
                    self.updateStatusText("发送失败：桌面端没有返回可识别结果。")
                    self.completeWithError("发送失败：桌面端没有返回可识别结果。")
                    return
                }

                guard (200...299).contains(httpResponse.statusCode) else {
                    let message = self.messageFromResponse(data, statusCode: httpResponse.statusCode)
                    self.updateStatusText(message)
                    self.completeWithError(message)
                    return
                }

                self.updateStatusText("已交给 VibeDrop 发送，正在传到手机…")
                self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            }
        }.resume()
    }

    private func loadSharedItems() {
        attachmentsLoading = true

        let extensionItems = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let attachments = extensionItems.flatMap { $0.attachments ?? [] }
        guard !attachments.isEmpty else {
            attachmentsLoading = false
            loadFailureMessage = "Finder 没有传入可识别的共享项目。"
            validateContent()
            return
        }

        let lock = NSLock()
        let group = DispatchGroup()
        var collectedURLs: [URL] = []
        var sawProvider = false
        var firstError: String?

        for provider in attachments {
            let candidateTypeIdentifiers = Self.candidateTypeIdentifiers(for: provider, fallbacks: fallbackTypeIdentifiers)
            guard !candidateTypeIdentifiers.isEmpty else {
                continue
            }

            sawProvider = true
            group.enter()
            Self.loadBestFileURL(from: provider, typeIdentifiers: candidateTypeIdentifiers) { url, message in
                defer { group.leave() }

                if let url {
                    lock.lock()
                    collectedURLs.append(url)
                    lock.unlock()
                } else {
                    lock.lock()
                    if firstError == nil {
                        firstError = message ?? "无法读取 Finder 共享的文件路径"
                    }
                    lock.unlock()
                }
            }
        }

        guard sawProvider else {
            attachmentsLoading = false
            loadFailureMessage = "Finder 当前没有把文件路径传给 VibeDrop。"
            validateContent()
            return
        }

        group.notify(queue: .main) {
            self.attachmentsLoading = false
            self.discoveredFileURLs = Self.uniqueFileURLs(from: collectedURLs)
            self.loadFailureMessage = self.discoveredFileURLs.isEmpty ? (firstError ?? "没有读取到有效文件路径。") : nil
            self.validateContent()
            self.attemptAutoSubmitIfReady()
        }
    }

    private func completeWithError(_ message: String) {
        let error = NSError(
            domain: "com.vibedrop.desktop.share",
            code: 1,
            userInfo: [NSLocalizedDescriptionKey: message]
        )
        extensionContext?.cancelRequest(withError: error)
    }

    private func messageForTransportError(_ error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .cannotConnectToHost, .cannotFindHost, .networkConnectionLost, .timedOut:
                return "无法连接到 VibeDrop 桌面端。请先打开 Mac 上的 VibeDrop，并确保至少有一台手机在线。"
            default:
                break
            }
        }

        return "发送失败：\(error.localizedDescription)"
    }

    private func messageFromResponse(_ data: Data?, statusCode: Int) -> String {
        if let data,
           let json = try? JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
           let message = json["error"] as? String,
           !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return message
        }

        return "发送失败（状态码 \(statusCode)）。"
    }

    private static func extractFileURL(from item: NSSecureCoding?) -> URL? {
        if let url = item as? URL {
            return url.standardizedFileURL
        }
        if let url = item as? NSURL {
            return (url as URL).standardizedFileURL
        }
        if let path = item as? NSString {
            return URL(fileURLWithPath: path as String)
        }
        return nil
    }

    private static func candidateTypeIdentifiers(for provider: NSItemProvider, fallbacks: [String]) -> [String] {
        var ordered: [String] = []
        var seen = Set<String>()

        for identifier in provider.registeredTypeIdentifiers + fallbacks {
            let normalized = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
            if normalized.isEmpty {
                continue
            }
            if seen.insert(normalized).inserted {
                ordered.append(normalized)
            }
        }

        return ordered
    }

    private static func loadBestFileURL(
        from provider: NSItemProvider,
        typeIdentifiers: [String],
        index: Int = 0,
        completion: @escaping (URL?, String?) -> Void
    ) {
        if index >= typeIdentifiers.count {
            completion(nil, "Finder 共享的数据类型当前还没被 VibeDrop 识别。")
            return
        }

        let identifier = typeIdentifiers[index]
        guard provider.hasItemConformingToTypeIdentifier(identifier) else {
            loadBestFileURL(from: provider, typeIdentifiers: typeIdentifiers, index: index + 1, completion: completion)
            return
        }

        provider.loadInPlaceFileRepresentation(forTypeIdentifier: identifier) { url, _, error in
            if let url {
                completion(url.standardizedFileURL, nil)
                return
            }

            provider.loadFileRepresentation(forTypeIdentifier: identifier) { tempURL, error2 in
                if let tempURL {
                    completion(tempURL.standardizedFileURL, nil)
                    return
                }

                provider.loadItem(forTypeIdentifier: identifier, options: nil) { item, error3 in
                    if let url = extractFileURL(from: item) {
                        completion(url, nil)
                        return
                    }

                    let message = error3?.localizedDescription
                        ?? error2?.localizedDescription
                        ?? error?.localizedDescription
                        ?? "无法读取 Finder 共享的文件路径"
                    loadBestFileURL(from: provider, typeIdentifiers: typeIdentifiers, index: index + 1) { url, nextMessage in
                        completion(url, nextMessage ?? message)
                    }
                }
            }
        }
    }

    private func updateStatusText(_ text: String) {
        placeholder = text
        if lastStatusText != text {
            textView.string = text
            lastStatusText = text
        }
    }

    private func attemptAutoSubmitIfReady() {
        guard !autoSubmissionStarted else {
            return
        }
        guard !attachmentsLoading else {
            return
        }
        guard loadFailureMessage == nil else {
            return
        }
        guard !discoveredFileURLs.isEmpty else {
            return
        }

        autoSubmissionStarted = true
        submitShareRequest()
    }

    private static func uniqueFileURLs(from urls: [URL]) -> [URL] {
        var seen = Set<String>()
        var result: [URL] = []

        for url in urls {
            let standardized = url.standardizedFileURL
            if seen.insert(standardized.path).inserted {
                result.append(standardized)
            }
        }

        return result
    }
}

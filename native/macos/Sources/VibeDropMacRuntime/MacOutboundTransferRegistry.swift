import Foundation

public enum MacOutboundTransferResult: Equatable, Sendable {
    case saved(path: String?)
    case failed(String)
    case timedOut
}

public final class MacOutboundTransferRegistry: @unchecked Sendable {
    private final class Pending {
        let semaphore = DispatchSemaphore(value: 0)
        var result: MacOutboundTransferResult?
    }

    private let lock = NSLock()
    private var pending: [String: Pending] = [:]

    public init() {}

    public func register(_ transferId: String) {
        lock.lock()
        pending[transferId] = Pending()
        lock.unlock()
    }

    public func wait(
        transferId: String,
        timeoutSeconds: TimeInterval
    ) -> MacOutboundTransferResult {
        lock.lock()
        let item = pending[transferId]
        lock.unlock()

        guard let item else {
            return .failed("传输等待状态不存在")
        }

        let timeout = DispatchTime.now() + timeoutSeconds
        if item.semaphore.wait(timeout: timeout) == .timedOut {
            remove(transferId)
            return .timedOut
        }

        lock.lock()
        let result = item.result ?? .failed("手机端确认回执异常")
        pending.removeValue(forKey: transferId)
        lock.unlock()
        return result
    }

    public func resolveSaved(transferId: String, savedPath: String?) {
        resolve(transferId: transferId, result: .saved(path: savedPath))
    }

    public func resolveFailed(transferId: String, error: String?) {
        resolve(transferId: transferId, result: .failed(error?.isEmpty == false ? error! : "手机端保存失败"))
    }

    private func resolve(transferId: String, result: MacOutboundTransferResult) {
        lock.lock()
        let item = pending[transferId]
        item?.result = result
        lock.unlock()
        item?.semaphore.signal()
    }

    private func remove(_ transferId: String) {
        lock.lock()
        pending.removeValue(forKey: transferId)
        lock.unlock()
    }
}

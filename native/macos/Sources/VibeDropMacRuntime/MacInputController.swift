import Foundation

public protocol MacInputControlling: Sendable {
    var isAccessibilityTrusted: Bool { get }
    func typeText(_ text: String) throws
    func pressEnter() throws
}

public enum MacInputError: LocalizedError, Equatable {
    case accessibilityPermissionMissing
    case eventCreationFailed

    public var errorDescription: String? {
        switch self {
        case .accessibilityPermissionMissing:
            return "缺少 macOS 辅助功能权限，无法模拟键盘输入"
        case .eventCreationFailed:
            return "无法创建键盘事件"
        }
    }
}

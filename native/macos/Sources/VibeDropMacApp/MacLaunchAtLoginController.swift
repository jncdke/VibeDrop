import Foundation
import ServiceManagement

enum MacLaunchAtLoginController {
    static var isEnabled: Bool {
        SMAppService.mainApp.status == .enabled
    }

    static var statusText: String {
        switch SMAppService.mainApp.status {
        case .enabled:
            return "已开启"
        case .notRegistered:
            return "未开启"
        case .requiresApproval:
            return "需要在系统设置中批准"
        case .notFound:
            return "当前运行方式不支持"
        @unknown default:
            return "未知状态"
        }
    }

    static func setEnabled(_ enabled: Bool) throws {
        let service = SMAppService.mainApp
        if enabled {
            if service.status != .enabled {
                try service.register()
            }
        } else if service.status == .enabled || service.status == .requiresApproval {
            try service.unregister()
        }
    }

    static func openSystemSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }
}

import Foundation
import VibeDropNativeCore

public extension MacServerOutbound {
    static func actionAck(_ action: VibeDropAction, transferId: String? = nil) -> MacServerOutbound {
        var payload: [String: String] = ["action": action.rawValue]
        if let transferId {
            payload["transfer_id"] = transferId
        }
        return .json(payload)
    }

    static func json(_ object: [String: String]) -> MacServerOutbound {
        .rawJSON(object)
    }
}

import Foundation
import VibeDropNativeCore

public typealias MacServerEffectHandler = @Sendable (MacServerEffect) -> [MacServerOutbound]

public enum MacServerDefaultEffectHandler {
    public static let preview: MacServerEffectHandler = { effect in
        switch effect {
        case .authenticated:
            return []
        case .typeText, .typeTextAndEnter, .pressEnter, .imageClipboard, .legacyFileDownload, .incomingFileStart:
            return [.status(MacServerStatusEnvelope(status: "ok"))]
        case .incomingFileChunk:
            return []
        case .incomingFileComplete:
            return [
                .status(
                    MacServerStatusEnvelope(
                        status: "error",
                        error: "原生 macOS 预览服务还未接入文件落盘"
                    )
                )
            ]
        case let .incomingFileSaved(transferId, _, _):
            return [.actionAck(.incomingFileSaved, transferId: transferId)]
        case let .incomingFileError(transferId, error, _):
            return [
                .json(
                    [
                        "action": VibeDropAction.incomingFileError.rawValue,
                        "transfer_id": transferId,
                        "error": error ?? "手机端保存失败"
                    ]
                )
            ]
        }
    }
}

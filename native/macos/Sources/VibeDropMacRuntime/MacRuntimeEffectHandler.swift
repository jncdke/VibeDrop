import Foundation
import VibeDropMacServer
import VibeDropMacStorage
import VibeDropNativeCore

public final class MacRuntimeEffectHandler: @unchecked Sendable {
    private let configuration: MacServerConfiguration
    private let historyDatabase: MacHistoryDatabase?
    private let inputController: MacInputControlling
    private let idGenerator: @Sendable () -> String
    private let now: @Sendable () -> Date

    public init(
        configuration: MacServerConfiguration,
        historyDatabase: MacHistoryDatabase?,
        inputController: MacInputControlling = MacKeyboardInputService(),
        idGenerator: @escaping @Sendable () -> String = { UUID().uuidString },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.configuration = configuration
        self.historyDatabase = historyDatabase
        self.inputController = inputController
        self.idGenerator = idGenerator
        self.now = now
    }

    public var handler: MacServerEffectHandler {
        { [weak self] effect in
            self?.handle(effect) ?? MacServerDefaultEffectHandler.preview(effect)
        }
    }

    public func handle(_ effect: MacServerEffect) -> [MacServerOutbound] {
        switch effect {
        case .authenticated:
            return []
        case let .typeText(text, peer):
            return performTextInput(text: text, pressEnter: false, peer: peer)
        case let .typeTextAndEnter(text, peer):
            return performTextInput(text: text, pressEnter: true, peer: peer)
        case let .pressEnter(peer):
            do {
                try inputController.pressEnter()
                return [.status(MacServerStatusEnvelope(status: "ok"))]
            } catch {
                recordTextHistory(
                    text: nil,
                    pressEnter: true,
                    peer: peer,
                    status: "failed",
                    error: error
                )
                return [.status(.runtimeError(error))]
            }
        case .imageClipboard, .legacyFileDownload, .incomingFileStart, .incomingFileChunk, .incomingFileComplete, .incomingFileSaved, .incomingFileError:
            return MacServerDefaultEffectHandler.preview(effect)
        }
    }

    private func performTextInput(
        text: String,
        pressEnter: Bool,
        peer: ConnectedPeer
    ) -> [MacServerOutbound] {
        do {
            try inputController.typeText(text)
            if pressEnter {
                try inputController.pressEnter()
            }
            recordTextHistory(
                text: text,
                pressEnter: pressEnter,
                peer: peer,
                status: "success",
                error: nil
            )
            return [.status(MacServerStatusEnvelope(status: "ok"))]
        } catch {
            recordTextHistory(
                text: text,
                pressEnter: pressEnter,
                peer: peer,
                status: "failed",
                error: error
            )
            return [.status(.runtimeError(error))]
        }
    }

    private func recordTextHistory(
        text: String?,
        pressEnter: Bool,
        peer: ConnectedPeer,
        status: String,
        error: Error?
    ) {
        guard let historyDatabase else { return }
        let entry = HistoryEntry(
            id: "native-mac:\(idGenerator())",
            timestamp: now(),
            direction: "mobile_to_desktop",
            kind: "text",
            status: status,
            text: text,
            sender: DeviceIdentity(
                deviceId: peer.deviceId,
                baseDeviceId: peer.baseDeviceId,
                displayName: peer.deviceName,
                role: peer.deviceRole
            ),
            receiver: DeviceIdentity(
                deviceId: configuration.serverId,
                displayName: configuration.hostname,
                role: "desktop",
                ip: configuration.ip,
                port: configuration.port
            ),
            saveTarget: pressEnter ? "type_enter" : "type"
        )
        try? historyDatabase.insert(entry, rawJSON: error.map { String(describing: $0) })
    }
}

private extension MacServerStatusEnvelope {
    static func runtimeError(_ error: Error) -> MacServerStatusEnvelope {
        MacServerStatusEnvelope(
            status: "error",
            error: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        )
    }
}

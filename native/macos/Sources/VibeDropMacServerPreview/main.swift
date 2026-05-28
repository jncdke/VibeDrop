import Foundation
import VibeDropMacServer

let port = Int(ProcessInfo.processInfo.environment["VIBEDROP_PORT"] ?? "") ?? 9001
let configuration = MacServerConfiguration(
    serverId: ProcessInfo.processInfo.environment["VIBEDROP_SERVER_ID"] ?? "native-desktop-preview",
    pin: ProcessInfo.processInfo.environment["VOICEDROP_PIN"] ?? "1234",
    hostname: ProcessInfo.processInfo.environment["VIBEDROP_HOSTNAME"] ?? Host.current().localizedName ?? "VibeDrop Native Mac",
    ip: ProcessInfo.processInfo.environment["VIBEDROP_IP"] ?? "127.0.0.1",
    port: port
)

let server = VibeDropMacServer(configuration: configuration) { effect in
    switch effect {
    case let .typeText(text, peer):
        print("type from \(peer.deviceName): \(text)")
    case let .typeTextAndEnter(text, peer):
        print("type_enter from \(peer.deviceName): \(text)")
    case let .pressEnter(peer):
        print("enter from \(peer.deviceName)")
    default:
        print("effect: \(effect)")
    }
    return MacServerDefaultEffectHandler.preview(effect)
}

try server.start()
print("VibeDrop native macOS preview server listening on 0.0.0.0:\(server.boundPort ?? port)")
RunLoop.main.run()

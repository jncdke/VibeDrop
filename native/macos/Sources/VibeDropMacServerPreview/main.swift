import Foundation
import VibeDropMacRuntime
import VibeDropMacServer
import VibeDropMacStorage

let port = Int(ProcessInfo.processInfo.environment["VIBEDROP_PORT"] ?? "") ?? 9001
let configuration = MacServerConfiguration(
    serverId: ProcessInfo.processInfo.environment["VIBEDROP_SERVER_ID"] ?? "native-desktop-preview",
    pin: ProcessInfo.processInfo.environment["VOICEDROP_PIN"] ?? "1234",
    hostname: ProcessInfo.processInfo.environment["VIBEDROP_HOSTNAME"] ?? Host.current().localizedName ?? "VibeDrop Native Mac",
    ip: ProcessInfo.processInfo.environment["VIBEDROP_IP"] ?? "127.0.0.1",
    port: port
)

if !MacKeyboardInputService.requestAccessibilityTrust(prompt: true) {
    print("warning: Accessibility permission is not trusted yet; text input requests will return an error until permission is granted.")
}

let databaseURL = try MacRuntimePaths.defaultDatabaseURL()
let historyDatabase = try MacHistoryDatabase(url: databaseURL)
let runtime = MacRuntimeEffectHandler(
    configuration: configuration,
    historyDatabase: historyDatabase
)
let server = VibeDropMacServer(
    configuration: configuration,
    effectHandler: runtime.handler
)

try server.start()
print("VibeDrop native macOS preview server listening on 0.0.0.0:\(server.boundPort ?? port)")
print("History database: \(databaseURL.path)")
RunLoop.main.run()

import Foundation
import VibeDropMacRuntime
import VibeDropMacServer
import VibeDropMacStorage

let configuration = try MacRuntimeConfigurationFactory.load()

if !MacKeyboardInputService.requestAccessibilityTrust(prompt: true) {
    print("warning: Accessibility permission is not trusted yet; text input requests will return an error until permission is granted.")
}

let databaseURL = try MacRuntimePaths.defaultDatabaseURL()
let historyDatabase = try MacHistoryDatabase(url: databaseURL)
let runtime = MacRuntimeEffectHandler(
    configuration: configuration,
    historyDatabase: historyDatabase,
    contentStore: try MacReceivedContentStore()
)
let server = VibeDropMacServer(
    configuration: configuration,
    effectHandler: runtime.handler
)
let clipboardBroadcastService = MacClipboardBroadcastService(broadcaster: server)

try server.start()
clipboardBroadcastService.start()
print("VibeDrop native macOS preview server listening on 0.0.0.0:\(server.boundPort ?? configuration.port)")
print("History database: \(databaseURL.path)")
RunLoop.main.run()

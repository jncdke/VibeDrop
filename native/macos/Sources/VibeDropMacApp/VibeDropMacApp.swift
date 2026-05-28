import AppKit
import SwiftUI

@main
struct VibeDropMacApp: App {
    @StateObject private var model = MacNativeAppModel()

    var body: some Scene {
        WindowGroup("VibeDrop") {
            MacContentView()
                .environmentObject(model)
        }
        .windowStyle(.titleBar)

        MenuBarExtra("VibeDrop", systemImage: "drop.fill") {
            Button("打开 VibeDrop") {
                NSApp.activate(ignoringOtherApps: true)
            }
            Divider()
            Button("复制地址 \(model.addressText)") {
                model.copyAddress()
            }
            Button("复制 PIN \(model.pinText)") {
                model.copyPin()
            }
            Divider()
            Text(model.serviceStatus)
            if let error = model.serviceError {
                Text(error)
            }
            Divider()
            Button("退出") {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}

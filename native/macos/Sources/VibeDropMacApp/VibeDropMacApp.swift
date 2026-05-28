import AppKit
import SwiftUI

@main
struct VibeDropMacApp: App {
    @Environment(\.openWindow) private var openWindow
    @StateObject private var model = MacNativeAppModel()

    var body: some Scene {
        WindowGroup("VibeDrop", id: "main") {
            MacContentView()
                .environmentObject(model)
        }
        .windowStyle(.titleBar)

        MenuBarExtra("VibeDrop", systemImage: "drop.fill") {
            Text("VibeDrop")
            Text("地址 \(model.addressText)")
            Text("PIN \(model.pinText)")
            Divider()
            Button("复制地址") {
                model.copyAddress()
            }
            Button("复制 PIN") {
                model.copyPin()
            }
            Divider()
            Button("打开 VibeDrop") {
                openWindow(id: "main")
                NSApp.activate(ignoringOtherApps: true)
            }
            Divider()
            Text(model.serviceStatus)
            if let error = model.serviceError {
                Text(error)
            }
            Divider()
            Button("退出 VibeDrop") {
                NSApplication.shared.terminate(nil)
            }
        }
    }
}

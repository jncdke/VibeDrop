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

        MenuBarExtra {
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
            Toggle("开机启动", isOn: Binding(
                get: { model.launchAtLoginEnabled },
                set: { model.setLaunchAtLoginEnabled($0) }
            ))
            Text("开机启动 \(model.launchAtLoginStatus)")
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
        } label: {
            if let image = VibeDropTrayImage.load() {
                Image(nsImage: image)
            } else {
                Image(systemName: "drop.fill")
            }
        }
    }
}

private enum VibeDropTrayImage {
    static func load() -> NSImage? {
        guard let url = Bundle.module.url(forResource: "VibeDropTrayIcon", withExtension: "png"),
              let image = NSImage(contentsOf: url) else {
            return nil
        }
        image.isTemplate = true
        image.size = NSSize(width: 18, height: 18)
        return image
    }
}

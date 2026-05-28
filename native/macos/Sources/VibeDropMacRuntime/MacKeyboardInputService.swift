import ApplicationServices
import Foundation

public final class MacKeyboardInputService: MacInputControlling, @unchecked Sendable {
    private let eventSource: CGEventSource?

    public init() {
        self.eventSource = CGEventSource(stateID: .hidSystemState)
    }

    public var isAccessibilityTrusted: Bool {
        AXIsProcessTrusted()
    }

    @discardableResult
    public static func requestAccessibilityTrust(prompt: Bool = true) -> Bool {
        let options = [
            kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: prompt
        ] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    public func typeText(_ text: String) throws {
        try ensureTrusted()
        guard let eventSource else {
            throw MacInputError.eventCreationFailed
        }
        for character in text {
            let units = Array(String(character).utf16)
            try units.withUnsafeBufferPointer { pointer in
                guard let baseAddress = pointer.baseAddress,
                      let keyDown = CGEvent(
                        keyboardEventSource: eventSource,
                        virtualKey: 0,
                        keyDown: true
                      ),
                      let keyUp = CGEvent(
                        keyboardEventSource: eventSource,
                        virtualKey: 0,
                        keyDown: false
                      ) else {
                    throw MacInputError.eventCreationFailed
                }
                keyDown.keyboardSetUnicodeString(
                    stringLength: units.count,
                    unicodeString: baseAddress
                )
                keyUp.keyboardSetUnicodeString(
                    stringLength: units.count,
                    unicodeString: baseAddress
                )
                keyDown.post(tap: .cghidEventTap)
                keyUp.post(tap: .cghidEventTap)
            }
        }
    }

    public func pressEnter() throws {
        try ensureTrusted()
        guard let eventSource,
              let keyDown = CGEvent(
                keyboardEventSource: eventSource,
                virtualKey: MacVirtualKey.returnKey,
                keyDown: true
              ),
              let keyUp = CGEvent(
                keyboardEventSource: eventSource,
                virtualKey: MacVirtualKey.returnKey,
                keyDown: false
              ) else {
            throw MacInputError.eventCreationFailed
        }
        keyDown.post(tap: .cghidEventTap)
        keyUp.post(tap: .cghidEventTap)
    }

    private func ensureTrusted() throws {
        if !isAccessibilityTrusted {
            throw MacInputError.accessibilityPermissionMissing
        }
    }
}

private enum MacVirtualKey {
    static let returnKey: CGKeyCode = 0x24
}

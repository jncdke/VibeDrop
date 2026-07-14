import AppKit
import Foundation
import VibeDropMacServer

public final class MacClipboardBroadcastService: @unchecked Sendable {
    private let broadcaster: MacServerClipboardBroadcasting
    private let intervalSeconds: TimeInterval
    private let queue = DispatchQueue(label: "VibeDrop.MacClipboardBroadcastService")
    private var timer: DispatchSourceTimer?
    private var lastText: String?

    public init(
        broadcaster: MacServerClipboardBroadcasting,
        intervalSeconds: TimeInterval = 0.5
    ) {
        self.broadcaster = broadcaster
        self.intervalSeconds = intervalSeconds
    }

    public func start() {
        queue.async {
            guard self.timer == nil else { return }
            self.lastText = self.readClipboardText()
            let timer = DispatchSource.makeTimerSource(queue: self.queue)
            timer.schedule(deadline: .now() + self.intervalSeconds, repeating: self.intervalSeconds)
            timer.setEventHandler { [weak self] in
                self?.poll()
            }
            self.timer = timer
            timer.resume()
        }
    }

    public func stop() {
        queue.async {
            self.timer?.cancel()
            self.timer = nil
        }
    }

    private func poll() {
        let text = readClipboardText()
        guard let text,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              text != lastText else {
            return
        }
        lastText = text
        try? broadcaster.broadcastClipboardText(text)
    }

    private func readClipboardText() -> String? {
        DispatchQueue.main.sync {
            NSPasteboard.general.string(forType: .string)
        }
    }
}

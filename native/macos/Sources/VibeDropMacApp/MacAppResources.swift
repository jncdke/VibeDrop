import AppKit
import Foundation

func loadMacAppImageResource(_ name: String) -> NSImage? {
    if let url = Bundle.main.url(forResource: name, withExtension: "png"),
       let image = NSImage(contentsOf: url) {
        return image
    }
    if let url = Bundle.module.url(forResource: name, withExtension: "png") {
        return NSImage(contentsOf: url)
    }
    return nil
}

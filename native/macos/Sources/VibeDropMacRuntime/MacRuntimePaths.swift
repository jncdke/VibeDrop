import Foundation

public enum MacRuntimePaths {
    public static let desktopInboxDirectoryName = "VibeDrop 收件箱"

    public static func applicationSupportDirectory(
        fileManager: FileManager = .default
    ) throws -> URL {
        let base = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = base.appendingPathComponent("VibeDrop", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    public static func defaultDatabaseURL(
        fileManager: FileManager = .default
    ) throws -> URL {
        try applicationSupportDirectory(fileManager: fileManager)
            .appendingPathComponent("vibedrop.sqlite")
    }

    public static func legacyVibeDropDirectory(
        fileManager: FileManager = .default
    ) throws -> URL {
        let home = fileManager.homeDirectoryForCurrentUser
        let directory = home.appendingPathComponent(".vibedrop", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    public static func legacyHistoryJSONLURL(
        fileManager: FileManager = .default
    ) throws -> URL {
        try legacyVibeDropDirectory(fileManager: fileManager)
            .appendingPathComponent("history.jsonl")
    }

    public static func receivedImagesDirectory(
        fileManager: FileManager = .default
    ) throws -> URL {
        let directory = try legacyVibeDropDirectory(fileManager: fileManager)
            .appendingPathComponent("received-images", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    public static func incomingTransfersDirectory(
        fileManager: FileManager = .default
    ) throws -> URL {
        let directory = try legacyVibeDropDirectory(fileManager: fileManager)
            .appendingPathComponent("incoming-downloads", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    public static func desktopInboxDirectory(
        fileManager: FileManager = .default
    ) throws -> URL {
        let downloads = try fileManager.url(
            for: .downloadsDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = downloads.appendingPathComponent(desktopInboxDirectoryName, isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

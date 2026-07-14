import Darwin
import Foundation
import VibeDropMacServer

public enum MacRuntimeConfigurationFactory {
    public static func load(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        fileManager: FileManager = .default
    ) throws -> MacServerConfiguration {
        let legacyDirectory = try MacRuntimePaths.legacyVibeDropDirectory(fileManager: fileManager)
        let port = Int(environment["VIBEDROP_PORT"] ?? "") ?? 9001
        let hostname = environment["VIBEDROP_HOSTNAME"]
            ?? Host.current().localizedName
            ?? Host.current().name
            ?? "VibeDrop Mac"
        let serverId = try value(
            environmentKey: "VIBEDROP_SERVER_ID",
            fileName: "server-id",
            fallback: "native-desktop-\(UUID().uuidString)",
            environment: environment,
            directory: legacyDirectory,
            fileManager: fileManager
        )
        let pin = try value(
            environmentKey: "VOICEDROP_PIN",
            fileName: "pin",
            fallback: String(format: "%04d", Int.random(in: 1000...9999)),
            environment: environment,
            directory: legacyDirectory,
            fileManager: fileManager
        )
        let ip = environment["VIBEDROP_IP"] ?? MacRuntimeNetwork.localIPv4Address() ?? "127.0.0.1"
        return MacServerConfiguration(
            serverId: serverId,
            pin: pin,
            hostname: hostname,
            ip: ip,
            port: port
        )
    }

    private static func value(
        environmentKey: String,
        fileName: String,
        fallback: String,
        environment: [String: String],
        directory: URL,
        fileManager: FileManager
    ) throws -> String {
        if let value = environment[environmentKey]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !value.isEmpty {
            return value
        }

        let url = directory.appendingPathComponent(fileName)
        if fileManager.fileExists(atPath: url.path) {
            let value = try String(contentsOf: url, encoding: .utf8)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !value.isEmpty {
                return value
            }
        }

        try fallback.write(to: url, atomically: true, encoding: .utf8)
        return fallback
    }
}

public enum MacRuntimeNetwork {
    public static func localIPv4Address() -> String? {
        var pointer: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&pointer) == 0, let first = pointer else {
            return nil
        }
        defer { freeifaddrs(pointer) }

        var fallback: String?
        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let item = current?.pointee {
            defer { current = item.ifa_next }
            guard let addressPointer = item.ifa_addr,
                  addressPointer.pointee.sa_family == UInt8(AF_INET),
                  let name = String(validatingUTF8: item.ifa_name),
                  !name.hasPrefix("lo"),
                  !name.hasPrefix("utun"),
                  !name.hasPrefix("tun"),
                  !name.hasPrefix("ipsec"),
                  !name.hasPrefix("bridge"),
                  !name.hasPrefix("awdl"),
                  !name.hasPrefix("llw") else {
                continue
            }
            var address = addressPointer.pointee
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                &address,
                socklen_t(address.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }
            let value = String(cString: host)
            if name == "en0" || name == "en1" {
                return value
            }
            fallback = fallback ?? value
        }
        return fallback
    }
}

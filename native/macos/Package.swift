// swift-tools-version: 5.10

import PackageDescription

let package = Package(
    name: "VibeDropNativeMac",
    platforms: [
        .macOS(.v13)
    ],
    products: [
        .library(
            name: "VibeDropNativeCore",
            targets: ["VibeDropNativeCore"]
        ),
        .library(
            name: "VibeDropMacStorage",
            targets: ["VibeDropMacStorage"]
        ),
        .library(
            name: "VibeDropMacServer",
            targets: ["VibeDropMacServer"]
        ),
        .executable(
            name: "VibeDropMacServerPreview",
            targets: ["VibeDropMacServerPreview"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "6.0.0"),
        .package(url: "https://github.com/apple/swift-nio.git", from: "2.70.0")
    ],
    targets: [
        .target(name: "VibeDropNativeCore"),
        .target(
            name: "VibeDropMacStorage",
            dependencies: [
                "VibeDropNativeCore",
                .product(name: "GRDB", package: "GRDB.swift")
            ]
        ),
        .target(
            name: "VibeDropMacServer",
            dependencies: [
                "VibeDropNativeCore",
                .product(name: "NIOCore", package: "swift-nio"),
                .product(name: "NIOHTTP1", package: "swift-nio"),
                .product(name: "NIOPosix", package: "swift-nio"),
                .product(name: "NIOWebSocket", package: "swift-nio")
            ]
        ),
        .executableTarget(
            name: "VibeDropMacServerPreview",
            dependencies: ["VibeDropMacServer"]
        ),
        .testTarget(
            name: "VibeDropNativeCoreTests",
            dependencies: [
                "VibeDropNativeCore",
                "VibeDropMacStorage",
                "VibeDropMacServer"
            ]
        )
    ]
)

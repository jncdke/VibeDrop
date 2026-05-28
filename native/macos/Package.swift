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
        .library(
            name: "VibeDropMacRuntime",
            targets: ["VibeDropMacRuntime"]
        ),
        .executable(
            name: "VibeDropMacServerPreview",
            targets: ["VibeDropMacServerPreview"]
        ),
        .executable(
            name: "VibeDropMacApp",
            targets: ["VibeDropMacApp"]
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
        .target(
            name: "VibeDropMacRuntime",
            dependencies: [
                "VibeDropNativeCore",
                "VibeDropMacServer",
                "VibeDropMacStorage"
            ]
        ),
        .executableTarget(
            name: "VibeDropMacServerPreview",
            dependencies: [
                "VibeDropMacRuntime",
                "VibeDropMacServer"
            ]
        ),
        .executableTarget(
            name: "VibeDropMacApp",
            dependencies: [
                "VibeDropMacRuntime",
                "VibeDropMacServer",
                "VibeDropMacStorage",
                "VibeDropNativeCore"
            ],
            resources: [
                .process("Resources")
            ]
        ),
        .testTarget(
            name: "VibeDropNativeCoreTests",
            dependencies: [
                "VibeDropNativeCore",
                "VibeDropMacStorage",
                "VibeDropMacServer",
                "VibeDropMacRuntime"
            ]
        )
    ]
)

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
        )
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "6.0.0")
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
            dependencies: ["VibeDropNativeCore"]
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

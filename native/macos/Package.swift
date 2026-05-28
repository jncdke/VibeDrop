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
        )
    ],
    targets: [
        .target(name: "VibeDropNativeCore"),
        .testTarget(
            name: "VibeDropNativeCoreTests",
            dependencies: ["VibeDropNativeCore"]
        )
    ]
)

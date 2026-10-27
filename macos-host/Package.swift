// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HyperScreenHost",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .executable(name: "HyperScreenHost", targets: ["HyperScreenHost"]),
        .library(name: "HyperScreenCoreBridge", targets: ["HyperScreenCoreBridge"])
    ],
    targets: [
        .target(
            name: "CHyperScreenCore",
            path: "Sources/CHyperScreenCore",
            publicHeadersPath: "include",
            cxxSettings: [
                .headerSearchPath("include"),
                .unsafeFlags([
                    "-std=c++20",
                    "-O3",
                    "-I/Users/asiddeenmuhammad/Projects/hyperscreen/core/include"
                ])
            ]
        ),
        .target(
            name: "CMoreCoreGraphics",
            path: "Sources/CMoreCoreGraphics",
            publicHeadersPath: "include",
            linkerSettings: [
                .linkedFramework("CoreGraphics"),
                .linkedFramework("Foundation"),
                .linkedFramework("IOKit"),
                .linkedFramework("IOUSBHost")
            ]
        ),
        .target(
            name: "HyperScreenCoreBridge",
            dependencies: ["CHyperScreenCore"],
            path: "Sources/HyperScreenCoreBridge"
        ),
        .executableTarget(
            name: "HyperScreenHost",
            dependencies: [
                "CHyperScreenCore",
                "CMoreCoreGraphics",
                "HyperScreenCoreBridge"
            ],
            path: "Sources/HyperScreenHost",
            linkerSettings: [
                .linkedFramework("AppKit"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("CoreVideo"),
                .linkedFramework("IOSurface"),
                .linkedFramework("IOKit"),
                .linkedFramework("IOUSBHost"),
                .linkedFramework("Network"),
                .linkedFramework("ScreenCaptureKit"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("AudioToolbox")
            ]
        ),
        .testTarget(
            name: "HyperScreenHostTests",
            dependencies: ["HyperScreenCoreBridge", "CHyperScreenCore"],
            path: "Tests/HyperScreenHostTests"
        )
    ],
    cxxLanguageStandard: .cxx20
)

import Foundation
import CoreGraphics
import CoreMedia
import CoreVideo
import IOSurface
import ScreenCaptureKit
import CMoreCoreGraphics

/// Configuration for a negotiated Android tablet Virtual Display
public struct VirtualDisplayConfig: Sendable, Equatable {
    public var name: String
    public var pixelWidth: Int
    public var pixelHeight: Int
    public var physicalWidthMM: Int
    public var physicalHeightMM: Int
    public var refreshRateHz: Double
    public var hiDPI: Bool
    public var preferYUV444: Bool
    public var preferAV1: Bool
    public var rotation: Int // 0 (Standard), 90 (Portrait Right), 270 (Portrait Left)

    public var isPortrait: Bool {
        rotation == 90 || rotation == 270
    }

    public var effectivePixelWidth: Int {
        isPortrait ? pixelHeight : pixelWidth
    }

    public var effectivePixelHeight: Int {
        isPortrait ? pixelWidth : pixelHeight
    }

    public init(
        name: String = "HyperScreen Pro Display",
        pixelWidth: Int = 1920,
        pixelHeight: Int = 1200,
        physicalWidthMM: Int = 254,
        physicalHeightMM: Int = 159,
        refreshRateHz: Double = 90.0,
        hiDPI: Bool = true,
        preferYUV444: Bool = false,
        preferAV1: Bool = false,
        rotation: Int = 0
    ) {
        self.name = name
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.physicalWidthMM = physicalWidthMM
        self.physicalHeightMM = physicalHeightMM
        self.refreshRateHz = refreshRateHz
        self.hiDPI = hiDPI
        self.preferYUV444 = preferYUV444
        self.preferAV1 = preferAV1
        self.rotation = rotation
    }
}

/// Manages the lifecycle of a native macOS `CGVirtualDisplay` with HiDPI (`@2x`) modes and 60-144Hz refresh rates.
@MainActor
public final class VirtualDisplayManager {
    private var virtualDisplay: CGVirtualDisplay?
    public private(set) var activeDisplayID: CGDirectDisplayID = kCGNullDirectDisplay
    public private(set) var currentConfig: VirtualDisplayConfig?

    public init() {}

    /// Spawns or reconfigures a hardware-backed macOS Virtual Display matching the connected Android device's panel.
    public func createOrUpdateDisplay(config: VirtualDisplayConfig) throws -> CGDirectDisplayID {
        if let existing = virtualDisplay, currentConfig == config {
            return existing.displayID
        }

        destroyDisplay()

        let isPortrait = config.isPortrait
        let effectiveW = config.effectivePixelWidth
        let effectiveH = config.effectivePixelHeight

        let descriptor = CGVirtualDisplayDescriptor()
        descriptor.name = config.name
        descriptor.maxPixelsWide = UInt32(effectiveW)
        descriptor.maxPixelsHigh = UInt32(effectiveH)
        descriptor.sizeInMillimeters = CGSize(
            width: CGFloat(max(isPortrait ? config.physicalHeightMM : config.physicalWidthMM, 120)),
            height: CGFloat(max(isPortrait ? config.physicalWidthMM : config.physicalHeightMM, 80))
        )
        descriptor.vendorID = 0x4853 // 'HS'
        descriptor.productID = 0x0001
        descriptor.serialNumber = 0x20261025
        descriptor.setDispatchQueue(DispatchQueue.main)

        // P3 / sRGB primaries
        descriptor.redPrimary = CGPoint(x: 0.680, y: 0.320)
        descriptor.greenPrimary = CGPoint(x: 0.265, y: 0.690)
        descriptor.bluePrimary = CGPoint(x: 0.150, y: 0.060)
        descriptor.whitePoint = CGPoint(x: 0.3127, y: 0.3290)

        guard let display = CGVirtualDisplay(descriptor: descriptor) else {
            throw NSError(
                domain: "HyperScreen.VirtualDisplay",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Failed to instantiate CGVirtualDisplay"]
            )
        }

        let settings = CGVirtualDisplaySettings()
        settings.hiDPI = config.hiDPI ? 1 : 0
        settings.rotation = UInt32(config.rotation)

        var modes: [CGVirtualDisplayMode] = []
        let refreshRates: [Double] = Array(Set([config.refreshRateHz, 120.0, 90.0, 60.0])).sorted(by: >)

        for hz in refreshRates {
            // Native 1x pixel mode
            modes.append(
                CGVirtualDisplayMode(
                    width: UInt(effectiveW),
                    height: UInt(effectiveH),
                    refreshRate: CGFloat(hz)
                )
            )
            // Logical @2x Retina (HiDPI) mode so macOS renders razor-sharp @2x UI assets
            if config.hiDPI {
                modes.append(
                    CGVirtualDisplayMode(
                        width: UInt(effectiveW / 2),
                        height: UInt(effectiveH / 2),
                        refreshRate: CGFloat(hz)
                    )
                )
            }
        }

        settings.modes = modes
        guard display.apply(settings) else {
            throw NSError(
                domain: "HyperScreen.VirtualDisplay",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "Failed to apply CGVirtualDisplaySettings"]
            )
        }

        self.virtualDisplay = display
        self.activeDisplayID = display.displayID
        self.currentConfig = config
        return display.displayID
    }

    public func destroyDisplay() {
        virtualDisplay = nil
        activeDisplayID = kCGNullDirectDisplay
        currentConfig = nil
    }
}

/// Callback interface for zero-copy captured video frames, Text-Sharp idle refinement triggers, and Opus audio.
public protocol SCStreamEngineDelegate: AnyObject, Sendable {
    func streamEngineDidCaptureVideoFrame(
        _ sampleBuffer: CMSampleBuffer,
        captureTimestampUs: UInt64,
        dirtyRectsCount: Int,
        isIdleRefinementFrame: Bool
    )
    func streamEngineDidCaptureAudioSample(_ sampleBuffer: CMSampleBuffer, captureTimestampUs: UInt64)
}

/// Zero-copy `ScreenCaptureKit` engine with `queueDepth = 2`, dirty-rect idle detection,
/// automatic **100ms Text-Sharp Refinement Trigger**, and 0 fps static-screen power saving.
public final class SCStreamEngine: NSObject, SCStreamOutput, SCStreamDelegate, @unchecked Sendable {
    private var stream: SCStream?
    private let videoQueue = DispatchQueue(label: "dev.hyperscreen.capture.video", qos: .userInteractive)
    private let audioQueue = DispatchQueue(label: "dev.hyperscreen.capture.audio", qos: .userInteractive)

    public weak var delegate: SCStreamEngineDelegate?

    private var lastMotionSampleBuffer: CMSampleBuffer?
    private var lastEmittedTimeUs: UInt64 = 0
    private var idleRefinementTimer: DispatchSourceTimer?
    private var hasEmittedIdleRefinement = false
    private let idleRefinementDelayNs: UInt64 = 100_000_000 // 100ms after last dirty-rect motion

    /// Immediately re-emits the most recent captured display frame so a newly connected
    /// USB-C or Wireless tablet receives an instant IDR keyframe even on a 100% static desktop.
    public func triggerImmediateRefreshFrame() {
        videoQueue.async { [weak self] in
            guard let self, let lastBuffer = self.lastMotionSampleBuffer else { return }
            let nowUs = DispatchTime.now().uptimeNanoseconds / 1_000
            self.lastEmittedTimeUs = nowUs
            self.delegate?.streamEngineDidCaptureVideoFrame(
                lastBuffer,
                captureTimestampUs: nowUs,
                dirtyRectsCount: 1,
                isIdleRefinementFrame: true
            )
        }
    }

    public func startCapture(
        displayID: CGDirectDisplayID,
        config: VirtualDisplayConfig,
        captureAudio: Bool = false
    ) async throws {
        // 1. Check TCC Screen Recording authorization first via CGPreflightScreenCaptureAccess()
        //    so we never spam the macOS TCC popup if a stale CDHash is in System Settings.
        if !CGPreflightScreenCaptureAccess() {
            _ = CGRequestScreenCaptureAccess()
            throw NSError(
                domain: "HyperScreen.SCStream",
                code: -403,
                userInfo: [
                    NSLocalizedDescriptionKey: "Screen Recording permission required (or needs App Restart after enabling in System Settings)."
                ]
            )
        }

        // 2. Give WindowServer up to 500ms to register the newly created CGVirtualDisplay in SCShareableContent
        var targetDisplay: SCDisplay?
        for _ in 0..<10 {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            if let matched = content.displays.first(where: { $0.displayID == displayID }) {
                targetDisplay = matched
                break
            }
            if content.displays.count == 1, let fallback = content.displays.first {
                targetDisplay = fallback
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }

        guard let scDisplay = targetDisplay else {
            throw NSError(
                domain: "HyperScreen.SCStream",
                code: -10,
                userInfo: [NSLocalizedDescriptionKey: "Target display \(displayID) not found in SCShareableContent"]
            )
        }

        let filter = SCContentFilter(display: scDisplay, excludingWindows: [])
        let streamConfig = SCStreamConfiguration()
        streamConfig.width = config.effectivePixelWidth
        streamConfig.height = config.effectivePixelHeight
        streamConfig.minimumFrameInterval = CMTime(
            value: 1,
            timescale: CMTimeScale(max(Int32(config.refreshRateHz.rounded()), 30))
        )

        // Strict queueDepth = 2 for sub-8ms glass-to-glass pipeline without surface starvation
        streamConfig.queueDepth = 2

        // Render the native macOS cursor (arrow, I-beam, resize, pointer hand) directly on the virtual display
        streamConfig.showsCursor = true

        // Align pixel format directly with VideoToolbox hardware surface expectations for zero-copy IOSurface handoff
        if config.preferYUV444 {
            // Full RGB 4:4:4 pixel format for Pro Text sharpness
            streamConfig.pixelFormat = kCVPixelFormatType_32BGRA
        } else {
            streamConfig.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        }
        streamConfig.colorSpaceName = CGColorSpace.displayP3

        if captureAudio {
            streamConfig.capturesAudio = true
            streamConfig.sampleRate = 48_000
            streamConfig.channelCount = 2
            streamConfig.excludesCurrentProcessAudio = true
        }

        let newStream = SCStream(filter: filter, configuration: streamConfig, delegate: self)
        try newStream.addStreamOutput(self, type: .screen, sampleHandlerQueue: videoQueue)
        if captureAudio {
            try newStream.addStreamOutput(self, type: .audio, sampleHandlerQueue: audioQueue)
        }

        try await newStream.startCapture()
        self.stream = newStream
    }

    public func stopCapture() async {
        idleRefinementTimer?.cancel()
        idleRefinementTimer = nil
        lastMotionSampleBuffer = nil
        if let active = stream {
            try? await active.stopCapture()
        }
        stream = nil
    }

    public func stream(
        _ stream: SCStream,
        didOutputSampleBuffer sampleBuffer: CMSampleBuffer,
        of outputType: SCStreamOutputType
    ) {
        guard CMSampleBufferIsValid(sampleBuffer) else { return }
        let nowUs = DispatchTime.now().uptimeNanoseconds / 1_000

        switch outputType {
        case .screen:
            handleScreenSampleBuffer(sampleBuffer, timestampUs: nowUs)
        case .audio:
            delegate?.streamEngineDidCaptureAudioSample(sampleBuffer, captureTimestampUs: nowUs)
        case .microphone:
            break
        @unknown default:
            break
        }
    }

    private func handleScreenSampleBuffer(_ sampleBuffer: CMSampleBuffer, timestampUs: UInt64) {
        // Inspect SCStreamFrameInfo to skip duplicate/idle frames (0 fps static desktop optimization)
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
              let frameInfo = attachments.first,
              let statusRaw = frameInfo[.status] as? Int,
              let status = SCFrameStatus(rawValue: statusRaw) else {
            return
        }

        guard status == .complete else {
            // Frame is `.idle` / `.blank` — emit a 3fps keepalive refresh if we have a cached frame so newly connected tablets never see a black screen
            if timestampUs - lastEmittedTimeUs >= 350_000, let lastBuffer = lastMotionSampleBuffer {
                lastEmittedTimeUs = timestampUs
                delegate?.streamEngineDidCaptureVideoFrame(
                    lastBuffer,
                    captureTimestampUs: timestampUs,
                    dirtyRectsCount: 1,
                    isIdleRefinementFrame: true
                )
            }
            return
        }

        let dirtyRects = (frameInfo[.dirtyRects] as? [NSDictionary]) ?? []
        if dirtyRects.isEmpty && hasEmittedIdleRefinement && (timestampUs - lastEmittedTimeUs < 350_000) {
            return
        }

        // Verify the CVPixelBuffer is IOSurface-backed for strict zero-copy unified memory sharing
        guard let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer),
              CVPixelBufferGetIOSurface(imageBuffer) != nil else {
            return
        }

        hasEmittedIdleRefinement = false
        lastMotionSampleBuffer = sampleBuffer
        lastEmittedTimeUs = timestampUs

        delegate?.streamEngineDidCaptureVideoFrame(
            sampleBuffer,
            captureTimestampUs: timestampUs,
            dirtyRectsCount: max(dirtyRects.count, 1),
            isIdleRefinementFrame: false
        )

        // Schedule the 100ms "Text-Sharp" Idle Refinement Frame trigger
        scheduleIdleRefinementTrigger()
    }

    private func scheduleIdleRefinementTrigger() {
        idleRefinementTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: videoQueue)
        timer.schedule(deadline: .now() + .nanoseconds(Int(idleRefinementDelayNs)))
        timer.setEventHandler { [weak self] in
            guard let self,
                  !self.hasEmittedIdleRefinement,
                  let lastBuffer = self.lastMotionSampleBuffer else {
                return
            }
            self.hasEmittedIdleRefinement = true
            let refineTsUs = DispatchTime.now().uptimeNanoseconds / 1_000
            self.delegate?.streamEngineDidCaptureVideoFrame(
                lastBuffer,
                captureTimestampUs: refineTsUs,
                dirtyRectsCount: 0,
                isIdleRefinementFrame: true
            )
        }
        idleRefinementTimer = timer
        timer.resume()
    }
}

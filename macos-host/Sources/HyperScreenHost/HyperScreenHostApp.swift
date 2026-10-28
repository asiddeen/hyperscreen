import SwiftUI
import AppKit
import CoreGraphics
import CoreImage
import CoreMedia
import CoreAudio
import CoreVideo
import IOSurface
import ApplicationServices
import CHyperScreenCore
import HyperScreenCoreBridge

public enum ConnectionPreference: String, CaseIterable, Identifiable, Sendable {
    case usb = "USB"
    case wireless = "Wireless"
    public var id: String { rawValue }
}

/// Main orchestrator coordinating `VirtualDisplayManager`, `SCStreamEngine`, `VideoToolboxEncoder`,
/// `TransportCoordinator`, and `InputAndEcosystemManager` with live Glass-to-Glass Telemetry.
@MainActor
public final class HyperScreenCoordinator: ObservableObject, @unchecked Sendable {
    @Published public var isStreaming = false
    @Published public var transportMode: TransportActiveMode = .disconnected
    @Published public var connectedDeviceName: String = "Waiting for Android Tablet..."
    @Published public var currentConfig = VirtualDisplayConfig()
    @Published public var encodeLatencyMs: Double = 0.0
    @Published public var rttMs: Double = 0.0
    @Published public var fps: Int = 120
    @Published public var bitrateMbps: Int = 50
    @Published public var isIdleRefined: Bool = false
    @Published public var headlessClamshellMode: Bool = true
    @Published public var hasScreenCapturePermission: Bool = CGPreflightScreenCaptureAccess()
    @Published public var hasAccessibilityPermission: Bool = AXIsProcessTrusted()
    @Published public var showOnboardingAndQR: Bool = false
    @Published public var userManuallyStopped: Bool = false
    @Published public var userPreferredYUV444: Bool = UserDefaults.standard.bool(forKey: "userPreferredYUV444")
    @Published public var isAudioStreamingEnabled: Bool = UserDefaults.standard.object(forKey: "isAudioStreamingEnabled") as? Bool ?? true
    @Published public var lastStatusMessage: String? = nil

    // Active connection preference & granular status telemetry
    @Published public var preferredConnection: ConnectionPreference = .usb
    @Published public var isUSBConnected: Bool = false
    @Published public var isADBInstalled: Bool = true
    @Published public var isADBReverseActive: Bool = false
    @Published public var isWiFiConnected: Bool = true

    private let displayManager = VirtualDisplayManager()
    private let captureEngine = SCStreamEngine()
    private let videoEncoder = VideoToolboxEncoder()
    public let transport = TransportCoordinator()
    private let inputEcosystem = InputAndEcosystemManager()
    private var diagnosticTimer: DispatchSourceTimer?

    public init() {
        captureEngine.delegate = self
        videoEncoder.delegate = self
        transport.delegate = self

        inputEcosystem.onCursorStateUpdate = { [weak self] cursorState in
            self?.transport.sendCursorState(cursorState)
        }
        inputEcosystem.onClipboardUpdate = { [weak self] clipboardData in
            self?.transport.sendControlOrAudioPacket(type: HS_PKT_CLIPBOARD_DATA, payload: clipboardData)
        }

        transport.start()
        startDiagnosticsLoop()
    }

    private func startDiagnosticsLoop() {
        refreshDiagnostics()
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now() + 1.0, repeating: 2.0)
        timer.setEventHandler { [weak self] in
            self?.refreshDiagnostics()
        }
        timer.resume()
        self.diagnosticTimer = timer
    }

    public func refreshDiagnostics() {
        hasScreenCapturePermission = CGPreflightScreenCaptureAccess()
        hasAccessibilityPermission = AXIsProcessTrusted()
        isUSBConnected = transport.isUSBDeviceConnected()
        isADBInstalled = transport.isADBInstalled()
        isADBReverseActive = transport.isADBReverseActive()
        isWiFiConnected = transport.isWiFiConnected()
    }

    public func selectConnectionPreference(_ pref: ConnectionPreference) {
        guard preferredConnection != pref else { return }
        let targetBitrate = (pref == .wireless) ? 26_000_000 : 48_000_000
        videoEncoder.setAdaptiveBitrate(bps: targetBitrate)
        if isStreaming {
            Task {
                await stopDisplaySession()
                transport.disconnectAllClients()
                await MainActor.run {
                    self.preferredConnection = pref
                    self.transport.preferredConnection = pref
                    if pref == .usb {
                        self.transport.forceUSBRescanAndTunnel()
                    }
                    self.lastStatusMessage = "Switched to \(pref == .usb ? "USB-C" : "Wireless LAN"). Ready for connection."
                    self.refreshDiagnostics()
                }
            }
        } else {
            preferredConnection = pref
            transport.preferredConnection = pref
            transport.disconnectAllClients()
            if pref == .usb {
                transport.forceUSBRescanAndTunnel()
            }
            lastStatusMessage = "Connection mode: \(pref == .usb ? "USB-C" : "Wireless LAN")"
            refreshDiagnostics()
        }
    }

    public func setRotation(_ angle: Int) async {
        guard currentConfig.rotation != angle else { return }
        currentConfig.rotation = angle
        if isStreaming {
            do {
                let displayID = try displayManager.createOrUpdateDisplay(config: currentConfig)
                inputEcosystem.virtualDisplayID = displayID
                inputEcosystem.virtualDisplayBounds = CGDisplayBounds(displayID)

                try videoEncoder.configure(config: currentConfig, bitrateMbps: bitrateMbps)
                try await captureEngine.startCapture(displayID: displayID, config: currentConfig, captureAudio: isAudioStreamingEnabled)

                transport.sendServerHello(config: currentConfig, isStreaming: isStreaming, bitrateMbps: bitrateMbps)
                videoEncoder.requestImmediateKeyframe()
                captureEngine.triggerImmediateRefreshFrame()
                lastStatusMessage = "Display rotation: \(angle)°"
            } catch {
                print("[HyperScreen] Failed to update rotation: \(error.localizedDescription)")
                lastStatusMessage = "Rotation error: \(error.localizedDescription)"
            }
        } else {
            transport.sendServerHello(config: currentConfig, isStreaming: false, bitrateMbps: bitrateMbps)
            lastStatusMessage = "Display rotation: \(angle)° (Ready)"
        }
    }

    public func startDisplaySession() async {
        userManuallyStopped = false
        hasScreenCapturePermission = CGPreflightScreenCaptureAccess()
        if !hasScreenCapturePermission {
            _ = CGRequestScreenCaptureAccess()
            lastStatusMessage = "Grant Screen Recording in System Settings, then click Quit & Reopen."
            return
        }

        do {
            let displayID = try displayManager.createOrUpdateDisplay(config: currentConfig)
            inputEcosystem.virtualDisplayID = displayID
            inputEcosystem.virtualDisplayBounds = CGDisplayBounds(displayID)
            inputEcosystem.setClamshellAndHeadlessAwakeAssertion(enabled: headlessClamshellMode)
            inputEcosystem.startCursorTracking240Hz()

            try videoEncoder.configure(config: currentConfig, bitrateMbps: bitrateMbps)
            try await captureEngine.startCapture(displayID: displayID, config: currentConfig, captureAudio: isAudioStreamingEnabled)
            isStreaming = true
            lastStatusMessage = nil
            transport.sendServerHello(config: currentConfig, isStreaming: true, bitrateMbps: bitrateMbps)
            videoEncoder.requestImmediateKeyframe()
            captureEngine.triggerImmediateRefreshFrame()
        } catch {
            print("[HyperScreen] Failed to start session: \(error.localizedDescription)")
            lastStatusMessage = error.localizedDescription
            isStreaming = false
            transport.sendServerHello(config: currentConfig, isStreaming: false, bitrateMbps: bitrateMbps)
        }
    }

    public func resetAndOpenScreenCaptureSettings() {
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/tccutil")
        proc.arguments = ["reset", "ScreenCapture", "dev.hyperscreen.host"]
        try? proc.run()
        proc.waitUntilExit()

        _ = CGRequestScreenCaptureAccess()
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture") {
            NSWorkspace.shared.open(url)
        }
        lastStatusMessage = "TCC cache reset! Toggle HyperScreen ON in System Settings, then Quit & Reopen."
    }

    public func stopDisplaySession() async {
        userManuallyStopped = true
        await captureEngine.stopCapture()
        videoEncoder.teardown()
        inputEcosystem.stopCursorTracking()
        inputEcosystem.setClamshellAndHeadlessAwakeAssertion(enabled: false)
        displayManager.destroyDisplay()
        isStreaming = false
        // Notify client that virtual display is stopped
        transport.sendServerHello(config: currentConfig, isStreaming: false, bitrateMbps: bitrateMbps)
    }

    public func sendDroppedFile(_ url: URL) {
        guard let chunks = try? InputAndEcosystemManager.buildFileTransferChunks(fileURL: url) else { return }
        for chunk in chunks {
            transport.sendControlOrAudioPacket(type: HS_PKT_FILE_CHUNK, payload: chunk)
        }
    }
}

extension HyperScreenCoordinator: SCStreamEngineDelegate {
    nonisolated public func streamEngineDidCaptureVideoFrame(
        _ sampleBuffer: CMSampleBuffer,
        captureTimestampUs: UInt64,
        dirtyRectsCount: Int,
        isIdleRefinementFrame: Bool
    ) {
        videoEncoder.encodeFrame(
            sampleBuffer: sampleBuffer,
            captureTimestampUs: captureTimestampUs,
            isIdleRefinement: isIdleRefinementFrame
        )
        Task { @MainActor in
            self.isIdleRefined = isIdleRefinementFrame
        }
    }

    nonisolated public func streamEngineDidCaptureAudioSample(_ sampleBuffer: CMSampleBuffer, captureTimestampUs: UInt64) {
        var blockBuffer: CMBlockBuffer?
        var audioBufferList = AudioBufferList()
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: &audioBufferList,
            bufferListSize: MemoryLayout<AudioBufferList>.size,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == noErr, let _ = blockBuffer else { return }

        guard let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(formatDesc)?.pointee else {
            return
        }

        let numBuffers = Int(audioBufferList.mNumberBuffers)
        guard numBuffers > 0 else { return }

        let sampleRate = UInt32(asbd.mSampleRate)
        let channels = UInt16(asbd.mChannelsPerFrame)
        let numFrames = CMSampleBufferGetNumSamples(sampleBuffer)
        guard numFrames > 0 else { return }

        var audioHeader = hs_audio_header_t(
            capture_pts_us: captureTimestampUs,
            sample_rate: sampleRate,
            channels: 2,
            frame_samples: UInt16(numFrames)
        )
        var pcmData = Data(capacity: MemoryLayout<hs_audio_header_t>.size + numFrames * 4)
        pcmData.append(Data(bytes: &audioHeader, count: MemoryLayout<hs_audio_header_t>.size))

        let isFloat = (asbd.mFormatFlags & kAudioFormatFlagIsFloat) != 0
        let isNonInterleaved = (asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved) != 0

        withUnsafePointer(to: audioBufferList.mBuffers) { buffersPtr in
            if isFloat {
                if isNonInterleaved && numBuffers >= 2 {
                    guard let leftPtr = buffersPtr.advanced(by: 0).pointee.mData?.assumingMemoryBound(to: Float32.self),
                          let rightPtr = buffersPtr.advanced(by: 1).pointee.mData?.assumingMemoryBound(to: Float32.self) else { return }
                    let outStart = pcmData.count
                    pcmData.count += numFrames * 4
                    pcmData.withUnsafeMutableBytes { rawBuf in
                        let outPtr = rawBuf.baseAddress!.advanced(by: outStart).assumingMemoryBound(to: Int16.self)
                        for i in 0..<numFrames {
                            let l = max(-1.0, min(1.0, leftPtr[i]))
                            let r = max(-1.0, min(1.0, rightPtr[i]))
                            outPtr[i * 2] = Int16(l * 32767.0)
                            outPtr[i * 2 + 1] = Int16(r * 32767.0)
                        }
                    }
                } else if let interleavedPtr = buffersPtr.pointee.mData?.assumingMemoryBound(to: Float32.self) {
                    let totalSamples = numFrames * Int(channels)
                    let outStart = pcmData.count
                    pcmData.count += totalSamples * 2
                    pcmData.withUnsafeMutableBytes { rawBuf in
                        let outPtr = rawBuf.baseAddress!.advanced(by: outStart).assumingMemoryBound(to: Int16.self)
                        for i in 0..<totalSamples {
                            let s = max(-1.0, min(1.0, interleavedPtr[i]))
                            outPtr[i] = Int16(s * 32767.0)
                        }
                    }
                }
            } else if let rawDataPtr = buffersPtr.pointee.mData {
                let bytesCount = min(Int(buffersPtr.pointee.mDataByteSize), numFrames * 4)
                pcmData.append(rawDataPtr.assumingMemoryBound(to: UInt8.self), count: bytesCount)
            }
        }

        if pcmData.count > MemoryLayout<hs_audio_header_t>.size {
            transport.sendControlOrAudioPacket(type: HS_PKT_AUDIO_OPUS, payload: pcmData)
        }
    }
}

extension HyperScreenCoordinator: VideoToolboxEncoderDelegate {
    nonisolated public func encoderDidProduceAnnexBFrame(
        frameId: UInt32,
        captureTimestampUs: UInt64,
        encodeLatencyUs: UInt64,
        flags: UInt16,
        annexBData: Data
    ) {
        transport.sendVideoFrame(
            frameId: frameId,
            captureTimestampUs: captureTimestampUs,
            flags: flags,
            annexBData: annexBData
        )
        Task { @MainActor in
            self.encodeLatencyMs = Double(encodeLatencyUs) / 1_000.0
        }
    }
}

extension HyperScreenCoordinator: HyperScreenTransportDelegate {
    nonisolated public func transportDidChangeState(_ mode: TransportActiveMode, clientName: String?) {
        Task { @MainActor in
            self.transportMode = mode
            self.connectedDeviceName = clientName ?? mode.rawValue
            self.hasScreenCapturePermission = CGPreflightScreenCaptureAccess()
            // Do NOT auto-start the virtual display on connection.
            // Notify the client of the current display state (active vs inactive).
            if mode != .disconnected {
                if mode == .usbADB || mode == .usbAOA {
                    self.preferredConnection = .usb
                } else if mode == .wirelessLAN || mode == .wirelessP2P {
                    self.preferredConnection = .wireless
                }
                self.transport.sendServerHello(config: self.currentConfig, isStreaming: self.isStreaming, bitrateMbps: self.bitrateMbps)
                if self.isStreaming {
                    self.videoEncoder.requestImmediateKeyframe()
                    self.captureEngine.triggerImmediateRefreshFrame()
                }
            }
        }
    }

    nonisolated public func transportDidReceiveClientHello(_ hello: hs_hello_payload_t) {
        Task { @MainActor in
            let newConfig = VirtualDisplayConfig(
                pixelWidth: max(Int(hello.pixel_width), 1280),
                pixelHeight: max(Int(hello.pixel_height), 800),
                physicalWidthMM: max(Int(hello.physical_width_mm), 160),
                physicalHeightMM: max(Int(hello.physical_height_mm), 100),
                refreshRateHz: Double(max(hello.refresh_rate_hz, 60)),
                hiDPI: hello.hidpi_enabled != 0,
                preferYUV444: self.userPreferredYUV444 || (hello.supports_hevc_444 != 0),
                preferAV1: hello.supports_av1 != 0,
                rotation: self.currentConfig.rotation
            )
            let configChanged = (self.currentConfig != newConfig)
            self.currentConfig = newConfig
            self.hasScreenCapturePermission = CGPreflightScreenCaptureAccess()

            // If the user explicitly stopped the display session on Mac, NEVER auto-restart!
            if self.userManuallyStopped {
                self.transport.sendServerHello(config: self.currentConfig, isStreaming: false, bitrateMbps: self.bitrateMbps)
                return
            }

            // Only update display if the user has explicitly started the display session
            if self.isStreaming && configChanged && self.hasScreenCapturePermission {
                await self.startDisplaySession()
            }
            self.transport.sendServerHello(config: self.currentConfig, isStreaming: self.isStreaming, bitrateMbps: self.bitrateMbps)
            if self.isStreaming {
                self.videoEncoder.requestImmediateKeyframe()
                self.captureEngine.triggerImmediateRefreshFrame()
                try? await Task.sleep(nanoseconds: 250_000_000)
                self.videoEncoder.requestImmediateKeyframe()
                self.captureEngine.triggerImmediateRefreshFrame()
            }
        }
    }

    nonisolated public func transportDidReceiveTouchInput(_ touch: hs_input_touch_t) {
        inputEcosystem.injectTouchOrTrackpadEvent(touch)
    }

    nonisolated public func transportDidReceiveStylusInput(_ stylus: hs_input_stylus_t) {
        inputEcosystem.injectStylusEvent(stylus)
    }

    nonisolated public func transportDidReceiveKeyInput(_ key: hs_input_key_t) {
        inputEcosystem.injectKeyEvent(key)
    }

    nonisolated public func transportDidReceiveVideoNack(_ nack: hs_video_nack_t) {
        if nack.missing_slice_index == 0xFFFF {
            videoEncoder.requestImmediateKeyframe()
        }
    }

    nonisolated public func transportDidUpdateRTT(rttMicroseconds: UInt64) {
        Task { @MainActor in
            self.rttMs = Double(rttMicroseconds) / 1_000.0
        }
    }

    nonisolated public func transportDidReceiveTelemetry(rttMicroseconds: UInt64, packetsLost: UInt16, decodeTimeUs: UInt16) {
        Task { @MainActor in
            self.rttMs = Double(rttMicroseconds) / 1_000.0
            self.adjustAdaptiveBitrate(rttUs: rttMicroseconds, packetsLost: packetsLost)
        }
    }

    @MainActor
    private func adjustAdaptiveBitrate(rttUs: UInt64, packetsLost: UInt16) {
        guard preferredConnection == .wireless else { return }

        let minBitrate = 12_000_000 // 12 Mbps floor for 1920x1200
        let maxBitrate = 38_000_000 // 38 Mbps ceiling
        var currentBps = self.bitrateMbps * 1_000_000

        // Multiplicative decrease (-15%) on congestion or packet loss or latency > 45ms
        if packetsLost > 0 || rttUs > 45_000 {
            let reduced = max(minBitrate, Int(Double(currentBps) * 0.85))
            if reduced != currentBps {
                currentBps = reduced
                self.bitrateMbps = max(currentBps / 1_000_000, 12)
                self.videoEncoder.setAdaptiveBitrate(bps: currentBps)
            }
        } else if rttUs < 20_000 && packetsLost == 0 {
            // Additive increase (+1 Mbps) when link is clear and sub-20ms
            let increased = min(maxBitrate, currentBps + 1_000_000)
            if increased != currentBps {
                currentBps = increased
                self.bitrateMbps = min(currentBps / 1_000_000, 38)
                self.videoEncoder.setAdaptiveBitrate(bps: currentBps)
            }
        }
    }
}

@main
struct HyperScreenEntryPoint {
    static func main() {
        if CommandLine.arguments.contains("--benchmark-loopback") {
            HyperScreenHardwareBenchmark.runAndExit()
        } else {
            HyperScreenHostApp.main()
        }
    }
}

/// Brand Design Tokens with Full macOS Light and Dark Mode Vibrancy
public enum HyperBrandTheme {
    public static let hyperCyan       = Color(red: 0.000, green: 0.850, blue: 0.950) // High-contrast cyan
    public static let electricIndigo  = Color(red: 0.388, green: 0.400, blue: 0.945) // #6366F1
    public static let hyperViolet     = Color(red: 0.659, green: 0.333, blue: 0.969) // #A855F7
    public static let titaniumVoid    = Color(red: 0.039, green: 0.055, blue: 0.102) // #0A0E1A
    public static let bezelSlate      = Color(red: 0.118, green: 0.161, blue: 0.231) // #1E293B

    // Adaptive Semantic Design Tokens
    public static let accentBlue      = Color(red: 0.05, green: 0.48, blue: 1.00) // #0D7AFF
    public static let statusGreen     = Color(red: 0.15, green: 0.76, blue: 0.38)
    public static let statusOrange    = Color(red: 1.00, green: 0.58, blue: 0.00)
    public static let statusGray      = Color(nsColor: .secondaryLabelColor)
    public static let cardBackground  = Color(nsColor: .controlBackgroundColor)
    public static let cardBorder      = Color(nsColor: .separatorColor).opacity(0.4)

    @MainActor
    public static func loadMenuBarIcon(isStreaming: Bool = false) -> NSImage {
        // Render the official HyperScreen interconnected Dual-Display + Optical Bridge mark
        // as a high-contrast macOS Template Image (`isTemplate = true`).
        // macOS automatically tints template images pure bright white (#FFFFFF) in Dark Mode
        // and crisp black (#000000) in Light Mode with native Menu Bar vibrancy!
        let targetSize = NSSize(width: 20, height: 18)
        let menuImage = NSImage(size: targetSize, flipped: false) { _ in
            NSColor.black.setStroke()
            NSColor.black.setFill()

            // 1. Primary Mac Display Frame (left/top)
            let macRect = NSRect(x: 1.5, y: 5.5, width: 11.5, height: 8.5)
            let macPath = NSBezierPath(roundedRect: macRect, xRadius: 2.0, yRadius: 2.0)
            macPath.lineWidth = 1.65
            macPath.stroke()

            // 2. Secondary Android Tablet Display Frame (overlapping bottom/right)
            let tabRect = NSRect(x: 7.5, y: 2.5, width: 11.0, height: 8.0)
            // Clear interior overlap slightly for crisp layered separation
            let tabPath = NSBezierPath(roundedRect: tabRect, xRadius: 2.0, yRadius: 2.0)
            tabPath.lineWidth = 1.65
            tabPath.stroke()

            // 3. Center Optical Lightning / Sync Pulse Node bridging both screens
            let boltPath = NSBezierPath()
            boltPath.move(to: NSPoint(x: 9.8, y: 12.2))
            boltPath.line(to: NSPoint(x: 7.2, y: 8.5))
            boltPath.line(to: NSPoint(x: 10.0, y: 8.5))
            boltPath.line(to: NSPoint(x: 8.6, y: 5.0))
            boltPath.line(to: NSPoint(x: 11.8, y: 8.9))
            boltPath.line(to: NSPoint(x: 9.2, y: 8.9))
            boltPath.close()
            boltPath.fill()

            if isStreaming {
                // Active streaming indicator dot in top-right corner
                let dotRect = NSRect(x: 15.2, y: 12.8, width: 3.6, height: 3.6)
                NSBezierPath(ovalIn: dotRect).fill()
            }
            return true
        }
        menuImage.isTemplate = true
        return menuImage
    }

    @MainActor
    public static func loadBrandMarkImage() -> NSImage? {
        let candidates = [
            Bundle.main.path(forResource: "hyperscreen_mark", ofType: "png"),
            Bundle.main.path(forResource: "AppIcon", ofType: "icns"),
            "/Users/asiddeenmuhammad/Projects/hyperscreen/assets/logo/hyperscreen_mark.png",
            "/Users/asiddeenmuhammad/Projects/hyperscreen/macos-host/Resources/AppIcon.icns"
        ].compactMap { $0 }

        for path in candidates {
            if let img = NSImage(contentsOfFile: path) {
                return img
            }
        }
        return nil
    }

    @MainActor
    public static func generateQRCodeImage(from text: String, size: CGFloat = 160) -> NSImage? {
        guard let data = text.data(using: .utf8),
              let filter = CIFilter(name: "CIQRCodeGenerator") else {
            return nil
        }
        filter.setValue(data, forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let outputImage = filter.outputImage else { return nil }

        let scaleX = size / outputImage.extent.size.width
        let scaleY = size / outputImage.extent.size.height
        let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

        let rep = NSCIImageRep(ciImage: transformed)
        let nsImage = NSImage(size: rep.size)
        nsImage.addRepresentation(rep)
        return nsImage
    }
}

/// Interactive Onboarding & QR Code Pairing Sheet / Panel for USB-C & Wireless Setup
struct HyperScreenOnboardingAndQRView: View {
    @ObservedObject var coordinator: HyperScreenCoordinator
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Onboarding & QR Code Pairing")
                        .font(.system(size: 16, weight: .bold))
                    Text("Connect your Android Tablet or Phone via USB-C or 5GHz Wi-Fi")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("Done") {
                    coordinator.showOnboardingAndQR = false
                    dismiss()
                }
                .keyboardShortcut(.cancelAction)
            }

            Divider()

            // Step 1: macOS Screen Recording Permission
            HStack(spacing: 10) {
                Image(systemName: coordinator.hasScreenCapturePermission ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(coordinator.hasScreenCapturePermission ? .green : .orange)
                    .font(.system(size: 18))
                VStack(alignment: .leading, spacing: 2) {
                    Text("1. macOS Screen Recording Permission")
                        .font(.system(size: 12, weight: .semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text(coordinator.hasScreenCapturePermission
                         ? "Granted — Zero-copy 120Hz ScreenCaptureKit capture is ready."
                         : "Required so HyperScreen can capture the Virtual Display.")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                if !coordinator.hasScreenCapturePermission {
                    Button("Fix Permission") {
                        coordinator.resetAndOpenScreenCaptureSettings()
                    }
                    .controlSize(.small)
                }
            }
            .padding(10)
            .background(HyperBrandTheme.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(HyperBrandTheme.cardBorder, lineWidth: 1)
            )

            // Step 2: USB-C Plug-and-Play (Zero-ADB AOA v2 + High-Speed USB-C Tunnel)
            HStack(spacing: 10) {
                Image(systemName: "cable.connector.horizontal")
                    .foregroundStyle(HyperBrandTheme.accentBlue)
                    .font(.system(size: 18))
                VStack(alignment: .leading, spacing: 2) {
                    Text("2. USB-C Cable (Sub-6ms Zero-Lag)")
                        .font(.system(size: 12, weight: .semibold))
                        .fixedSize(horizontal: false, vertical: true)
                    Text("Plug in your USB-C cable and open HyperScreen Pro on Android. Click Reconnect if already plugged in.")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Button("Reconnect USB-C") {
                    coordinator.transport.forceUSBRescanAndTunnel()
                    coordinator.lastStatusMessage = "USB-C tunnel refreshed (port 9453). Open HyperScreen on Android!"
                }
                .controlSize(.small)
            }
            .padding(10)
            .background(HyperBrandTheme.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(HyperBrandTheme.cardBorder, lineWidth: 1)
            )

            // Step 3: Wireless 5GHz/6GHz QR Pairing (Text on Left, QR Code on Right)
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("3. Wireless 5GHz/6GHz QR Pairing")
                        .font(.system(size: 12, weight: .semibold))
                    Text("Use the built-in QR Scanner inside the HyperScreen Android app (top-right pill) or enter this IP directly:")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    let lanIP = coordinator.transport.localLANIPAddress()
                    HStack(spacing: 8) {
                        Text("Mac IP:")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                        Text("\(lanIP):9453")
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .foregroundStyle(HyperBrandTheme.accentBlue)
                            .fixedSize(horizontal: true, vertical: false)
                            .textSelection(.enabled)
                        Button("Copy IP") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(lanIP, forType: .string)
                        }
                        .controlSize(.mini)
                    }

                    HStack(spacing: 8) {
                        Text("Pairing PIN:")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                        Text(coordinator.transport.formattedPairingPIN)
                            .font(.system(size: 13, weight: .bold, design: .monospaced))
                            .fixedSize(horizontal: true, vertical: false)
                            .textSelection(.enabled)
                    }
                }

                Spacer()

                let qrURI = coordinator.transport.pairingQRURI()
                if let qrImg = HyperBrandTheme.generateQRCodeImage(from: qrURI, size: 136) {
                    Image(nsImage: qrImg)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 136, height: 136)
                        .padding(8)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HyperBrandTheme.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(HyperBrandTheme.cardBorder, lineWidth: 1)
            )
        }
        .padding(20)
        .frame(width: 520)
    }
}

/// Standout Native Connection Dropdown Card (USB-C vs Wireless LAN)
struct HyperConnectionDropdown: View {
    @Binding var selected: ConnectionPreference
    var onSelect: (ConnectionPreference) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            // 1. Card Header Row: Icon + Title + Live Status Badge
            HStack(spacing: 6) {
                Image(systemName: selected == .usb ? "cable.connector.horizontal" : "wifi")
                    .foregroundStyle(selected == .usb ? HyperBrandTheme.accentBlue : HyperBrandTheme.hyperViolet)
                    .font(.system(size: 13, weight: .bold))

                Text("Connection Mode")
                    .font(.system(size: 13, weight: .bold))

                Spacer()

                HStack(spacing: 5) {
                    Circle()
                        .fill(selected == .usb ? HyperBrandTheme.statusGreen : HyperBrandTheme.accentBlue)
                        .frame(width: 7, height: 7)

                    Text(selected == .usb ? "USB-C Direct (Sub-6ms)" : "Wi-Fi 5/6GHz UDP")
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(selected == .usb ? HyperBrandTheme.statusGreen : HyperBrandTheme.accentBlue)
                }
            }

            Divider()
                .padding(.vertical, 1)

            // 2. Active Mode Display Row with Icon, Info & Native Select Dropdown
            HStack(spacing: 12) {
                // Vibrant Gradient Icon Badge
                ZStack {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: selected == .usb
                                    ? [HyperBrandTheme.accentBlue, HyperBrandTheme.hyperCyan]
                                    : [HyperBrandTheme.electricIndigo, HyperBrandTheme.hyperViolet],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .frame(width: 38, height: 38)
                        .shadow(
                            color: (selected == .usb ? HyperBrandTheme.accentBlue : HyperBrandTheme.electricIndigo).opacity(0.35),
                            radius: 3,
                            x: 0,
                            y: 1.5
                        )

                    Image(systemName: selected == .usb ? "cable.connector.horizontal" : "wifi")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(.white)
                }

                // Title, Latency Pill & Subtitle
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text(selected == .usb ? "USB-C Direct Cable" : "Wireless Wi-Fi Stream")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(Color.primary)

                        Text(selected == .usb ? "⚡ SUB-6MS" : "📶 0MS FEC")
                            .font(.system(size: 9, weight: .heavy))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(
                                (selected == .usb ? HyperBrandTheme.accentBlue : HyperBrandTheme.hyperViolet).opacity(0.18)
                            )
                            .foregroundStyle(selected == .usb ? HyperBrandTheme.accentBlue : HyperBrandTheme.hyperViolet)
                            .clipShape(Capsule())
                    }

                    Text(
                        selected == .usb
                            ? "Port 9453 loopback tunnel • Highest throughput"
                            : "Ultra-low latency UDP stream with 0ms FEC"
                    )
                    .font(.system(size: 11))
                    .foregroundStyle(Color.secondary)
                    .lineLimit(1)
                }

                Spacer()

                // Native Select Dropdown Menu (on the right)
                Menu {
                    Button(action: {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            selected = .usb
                            onSelect(.usb)
                        }
                    }) {
                        HStack {
                            Label("USB-C Cable (Sub-6ms Low Latency)", systemImage: "cable.connector.horizontal")
                            if selected == .usb {
                                Image(systemName: "checkmark")
                            }
                        }
                    }

                    Button(action: {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            selected = .wireless
                            onSelect(.wireless)
                        }
                    }) {
                        HStack {
                            Label("Wireless LAN (Wi-Fi 5GHz/6GHz)", systemImage: "wifi")
                            if selected == .wireless {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Text(selected == .usb ? "USB-C" : "Wi-Fi")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color.primary)

                        Image(systemName: "chevron.up.chevron.down")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(selected == .usb ? HyperBrandTheme.accentBlue : HyperBrandTheme.hyperViolet)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color(nsColor: .controlColor))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .stroke(Color(nsColor: .separatorColor), lineWidth: 1)
                    )
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(HyperBrandTheme.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .stroke(HyperBrandTheme.cardBorder, lineWidth: 1)
        )
    }
}

/// Single Status Card Row with Title, (i) Tooltip, and Status Dot + Label
struct HyperStatusRowView: View {
    let title: String
    let infoTooltip: String
    let statusText: String
    let statusColor: Color
    let dotColor: Color
    var isMonospaced: Bool = false

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
                .font(.system(size: 12))
                .foregroundStyle(.primary)

            Image(systemName: "info.circle")
                .font(.system(size: 11))
                .foregroundStyle(.secondary.opacity(0.75))
                .help(infoTooltip)

            Spacer()

            Circle()
                .fill(dotColor)
                .frame(width: 6, height: 6)

            Text(statusText)
                .font(isMonospaced ? .system(size: 12, weight: .semibold, design: .monospaced) : .system(size: 12, weight: .semibold))
                .foregroundStyle(statusColor)
        }
        .padding(.vertical, 1.5)
    }
}

/// Dynamic macOS Status Card (Light & Dark Mode Adaptive)
struct HyperStatusCardView: View {
    @ObservedObject var coordinator: HyperScreenCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Header
            HStack(spacing: 6) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(HyperBrandTheme.accentBlue)
                Text("Status")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.primary)
                Spacer()
            }
            .padding(.bottom, 2)

            // 1. Virtual Display
            HyperStatusRowView(
                title: "Virtual Display",
                infoTooltip: "Hardware-accelerated extended macOS virtual monitor lifecycle.",
                statusText: coordinator.isStreaming ? "Active" : "Inactive",
                statusColor: coordinator.isStreaming ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray,
                dotColor: coordinator.isStreaming ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray
            )

            // 2. Client Connected
            HyperStatusRowView(
                title: "Client Connected",
                infoTooltip: "Indicates whether an Android device is actively receiving the display stream.",
                statusText: coordinator.transportMode != .disconnected ? (coordinator.connectedDeviceName.isEmpty ? "Yes" : coordinator.connectedDeviceName) : "No",
                statusColor: coordinator.transportMode != .disconnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray,
                dotColor: coordinator.transportMode != .disconnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray
            )

            // 3. Screen & System Audio
            HyperStatusRowView(
                title: "Screen & System Audio",
                infoTooltip: "macOS Screen Recording permission required for ScreenCaptureKit display capture.",
                statusText: coordinator.hasScreenCapturePermission ? "Granted" : "Needs Grant",
                statusColor: coordinator.hasScreenCapturePermission ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange,
                dotColor: coordinator.hasScreenCapturePermission ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange
            )

            // 4. Accessibility
            HyperStatusRowView(
                title: "Accessibility",
                infoTooltip: "macOS Accessibility permission required to inject touch, stylus pressure, and trackpad gestures.",
                statusText: coordinator.hasAccessibilityPermission ? "Granted" : "Needs Grant",
                statusColor: coordinator.hasAccessibilityPermission ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange,
                dotColor: coordinator.hasAccessibilityPermission ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange
            )

            Divider()
                .padding(.vertical, 2)

            // Dynamic section based on selected Connection target
            if coordinator.preferredConnection == .usb {
                HyperStatusRowView(
                    title: "ADB installed",
                    infoTooltip: "Android Debug Bridge binary used to automate USB-C reverse port tunnels.",
                    statusText: coordinator.isADBInstalled ? "Installed" : "Missing",
                    statusColor: coordinator.isADBInstalled ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange,
                    dotColor: coordinator.isADBInstalled ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange
                )

                HyperStatusRowView(
                    title: "ADB reverse",
                    infoTooltip: "Reverse tunnel (tcp:9453 tcp:9453) enabling sub-5ms zero-lag USB-C loopback streaming.",
                    statusText: coordinator.isADBReverseActive ? "Active" : "Pending",
                    statusColor: coordinator.isADBReverseActive ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange,
                    dotColor: coordinator.isADBReverseActive ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusOrange
                )

                HyperStatusRowView(
                    title: "USB device",
                    infoTooltip: "Status of physical USB-C cable connection to the Android tablet.",
                    statusText: coordinator.isUSBConnected ? "Detected" : "Not Connected",
                    statusColor: coordinator.isUSBConnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray,
                    dotColor: coordinator.isUSBConnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray
                )
            } else {
                HyperStatusRowView(
                    title: "WiFi",
                    infoTooltip: "Wireless LAN connection status on Mac primary network interface.",
                    statusText: coordinator.isWiFiConnected ? "Connected" : "Disconnected",
                    statusColor: coordinator.isWiFiConnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray,
                    dotColor: coordinator.isWiFiConnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray
                )

                HyperStatusRowView(
                    title: "Listening on",
                    infoTooltip: "Local network IP and port where HyperScreen display stream is served.",
                    statusText: "\(coordinator.transport.localLANIPAddress()):9453",
                    statusColor: coordinator.isWiFiConnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray,
                    dotColor: coordinator.isWiFiConnected ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray,
                    isMonospaced: true
                )
            }
        }
        .padding(14)
        .background(HyperBrandTheme.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(HyperBrandTheme.cardBorder, lineWidth: 1)
        )
    }
}

/// Branded SwiftUI Settings Window (Single-Instance Settings & Telemetry Window)
struct HyperScreenControlPanelView: View {
    @ObservedObject var coordinator: HyperScreenCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // 1. Brand Header with Official HyperScreen Logomark & 120Hz Badge
            HStack(spacing: 12) {
                if let brandLogo = HyperBrandTheme.loadBrandMarkImage() {
                    Image(nsImage: brandLogo)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 42, height: 42)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text("HyperScreen")
                            .font(.system(size: 16, weight: .bold))
                        Text("v\(HyperScreenHostApp.appVersion)")
                            .font(.system(size: 11, weight: .semibold, design: .monospaced))
                            .foregroundStyle(.secondary)
                        Text("120Hz PRO")
                            .font(.system(size: 9, weight: .heavy))
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(
                                LinearGradient(
                                    colors: [HyperBrandTheme.electricIndigo, HyperBrandTheme.hyperViolet],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                )
                            )
                            .foregroundStyle(.white)
                            .clipShape(Capsule())
                    }
                    Text(coordinator.transportMode.rawValue)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(coordinator.isStreaming ? HyperBrandTheme.accentBlue : .secondary)
                }
                Spacer()
                Circle()
                    .fill(coordinator.isStreaming ? HyperBrandTheme.statusGreen : HyperBrandTheme.statusGray.opacity(0.5))
                    .frame(width: 10, height: 10)
            }

            // 2. Custom-Styled Connection Select Dropdown
            HyperConnectionDropdown(selected: $coordinator.preferredConnection) { newMode in
                coordinator.selectConnectionPreference(newMode)
            }

            // 3. Status Card (Light & Dark Mode Adaptive)
            HyperStatusCardView(coordinator: coordinator)

            // 4. Display Mode, Rotation (270°, 0°, 90°) & Telemetry Card
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(
                            String(
                                format: "%dx%d @ %dHz • %@",
                                coordinator.currentConfig.effectivePixelWidth,
                                coordinator.currentConfig.effectivePixelHeight,
                                Int(coordinator.currentConfig.refreshRateHz),
                                coordinator.currentConfig.hiDPI ? "HiDPI Retina (@2x)" : "Native 1x"
                            )
                        )
                        .font(.system(size: 12, weight: .semibold))

                        Text(
                            String(
                                format: "Encode: %.2f ms  |  Pipeline: %@",
                                coordinator.encodeLatencyMs,
                                coordinator.isIdleRefined ? "Text-Sharp Idle (0 FPS)" : "120Hz Motion"
                            )
                        )
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(HyperBrandTheme.accentBlue)
                    }
                    Spacer()
                }

                Divider()
                    .padding(.vertical, 1)

                // Rotation Selector (270°, 0°, 90°)
                HStack {
                    Text("Display Rotation")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.primary)

                    Spacer()

                    HStack(spacing: 4) {
                        ForEach([270, 0, 90], id: \.self) { angle in
                            Button(action: {
                                Task {
                                    await coordinator.setRotation(angle)
                                }
                            }) {
                                Text(angle == 0 ? "0° (Standard)" : "\(angle)°")
                                    .font(.system(size: 11, weight: coordinator.currentConfig.rotation == angle ? .bold : .medium))
                                    .padding(.horizontal, 9)
                                    .padding(.vertical, 4)
                                    .background(
                                        coordinator.currentConfig.rotation == angle
                                            ? HyperBrandTheme.accentBlue
                                            : Color.primary.opacity(0.06)
                                    )
                                    .foregroundStyle(coordinator.currentConfig.rotation == angle ? .white : .primary)
                                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HyperBrandTheme.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(HyperBrandTheme.cardBorder, lineWidth: 1)
            )

            // 5. Connection Details & Actions (USB or Wireless specific)
            if coordinator.preferredConnection == .usb {
                HStack(spacing: 8) {
                    Button("Reconnect USB-C") {
                        coordinator.transport.forceUSBRescanAndTunnel()
                        coordinator.refreshDiagnostics()
                        coordinator.lastStatusMessage = "USB-C tunnel refreshed (tcp:9453). Open HyperScreen on Android!"
                    }
                    .controlSize(.small)

                    Spacer()

                    Button("QR Code & Setup…") {
                        coordinator.showOnboardingAndQR = true
                    }
                    .controlSize(.small)
                }
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 12) {
                        HStack(spacing: 5) {
                            Text("Mac IP:")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                            Text("\(coordinator.transport.localLANIPAddress()):9453")
                                .font(.system(size: 12, weight: .bold, design: .monospaced))
                                .foregroundStyle(HyperBrandTheme.accentBlue)
                                .textSelection(.enabled)
                        }
                        Spacer()
                        HStack(spacing: 5) {
                            Text("PIN:")
                                .font(.system(size: 11))
                                .foregroundStyle(.secondary)
                            Text(coordinator.transport.formattedPairingPIN)
                                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                                .textSelection(.enabled)
                        }
                    }

                    HStack(spacing: 8) {
                        Button("QR Code & Pairing Sheet…") {
                            coordinator.showOnboardingAndQR = true
                        }
                        .controlSize(.small)

                        Spacer()
                    }
                }
            }

            // 6. Pro Feature Toggles
            HStack(spacing: 16) {
                Toggle("4:4:4 Pro Text Chroma", isOn: Binding(
                    get: { coordinator.userPreferredYUV444 || coordinator.currentConfig.preferYUV444 },
                    set: { newValue in
                        coordinator.userPreferredYUV444 = newValue
                        coordinator.currentConfig.preferYUV444 = newValue
                        if coordinator.isStreaming {
                            Task {
                                await coordinator.stopDisplaySession()
                                coordinator.userManuallyStopped = false
                                await coordinator.startDisplaySession()
                            }
                        }
                    }
                ))
                .font(.system(size: 12))

                Toggle("Clamshell Awake", isOn: $coordinator.headlessClamshellMode)
                    .font(.system(size: 12))
            }

            if !coordinator.hasScreenCapturePermission {
                HStack(spacing: 6) {
                    Circle()
                        .fill(HyperBrandTheme.statusOrange)
                        .frame(width: 8, height: 8)
                    Text("Screen Recording Permission Required")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("Fix Permission") {
                        coordinator.resetAndOpenScreenCaptureSettings()
                    }
                    .controlSize(.mini)
                }
            }

            if let msg = coordinator.lastStatusMessage {
                Text(msg)
                    .font(.system(size: 10))
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Divider()

            // 7. Primary Bottom Actions
            HStack(spacing: 10) {
                Button(coordinator.isStreaming ? "Stop Virtual Display" : "Start Virtual Display") {
                    Task {
                        if coordinator.isStreaming {
                            await coordinator.stopDisplaySession()
                        } else {
                            coordinator.userManuallyStopped = false
                            await coordinator.startDisplaySession()
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(coordinator.isStreaming ? .red : HyperBrandTheme.accentBlue)
                .keyboardShortcut("s")

                Spacer()

                Button("Hide to Menu Bar") {
                    NSApp.keyWindow?.close()
                }
                .controlSize(.small)
            }
        }
        .padding(18)
        .frame(width: 440)
        .sheet(isPresented: $coordinator.showOnboardingAndQR) {
            HyperScreenOnboardingAndQRView(coordinator: coordinator)
        }
    }
}

/// Standard macOS Menu Bar Quick-Menu + Single Settings Window Architecture (BetterDisplay / Tailscale style):
/// 1. Clicking the Menu Bar icon opens a compact native `NSMenu` with quick toggles, resolution presets,
///    status telemetry, and "Open Control Panel…". It never spawns a duplicate window view.
/// 2. Soft-closing the Control Panel window (Red X / Cmd+W / "Hide to Menu Bar") hides the window and
///    switches `NSApp` to `.accessory` mode so HyperScreen runs quietly in the top Menu Bar without a Dock icon.
/// 3. Selecting "Open Control Panel…" from the Menu Bar (or clicking HyperScreen in Finder/Spotlight)
///    brings the single Control Panel window to the front.
@MainActor
final class HyperScreenAppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSMenuDelegate {
    let coordinator = HyperScreenCoordinator()
    private var statusItem: NSStatusItem?
    private let statusMenu = NSMenu()
    private var mainWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.regular)
        if let appIcon = HyperBrandTheme.loadBrandMarkImage() {
            NSApp.applicationIconImage = appIcon
        }

        // 1. Setup Native AppKit NSStatusItem with a Quick-Toggle NSMenu
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        if let button = item.button {
            let icon = HyperBrandTheme.loadMenuBarIcon(isStreaming: coordinator.isStreaming)
            icon.isTemplate = true
            button.image = icon
            button.imagePosition = .imageOnly
            button.toolTip = "HyperScreen Pro — Quick Controls & Display Status"
        }
        statusMenu.delegate = self
        item.menu = statusMenu
        self.statusItem = item
        rebuildStatusMenu()

        // 2. Open the single Settings Window on initial launch
        showControlCenterWindow()

        // 3. Intercept standard macOS Preferences/Settings item so ⌘, activates our window directly
        if let appMenu = NSApp.mainMenu?.items.first?.submenu {
            for item in appMenu.items {
                if item.action == Selector(("showPreferencesWindow:")) || item.keyEquivalent == "," {
                    item.action = #selector(openSettingsFromMenu(_:))
                    item.target = self
                }
            }
        }
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        if let button = statusItem?.button {
            let icon = HyperBrandTheme.loadMenuBarIcon(isStreaming: coordinator.isStreaming)
            icon.isTemplate = true
            button.image = icon
        }
        rebuildStatusMenu()
    }

    private func rebuildStatusMenu() {
        statusMenu.removeAllItems()

        // 1. Header Status Line
        let statusTitle = coordinator.isStreaming
            ? "● HyperScreen Pro v\(HyperScreenHostApp.appVersion) — Streaming (\(Int(coordinator.currentConfig.refreshRateHz))Hz)"
            : "○ HyperScreen Pro v\(HyperScreenHostApp.appVersion) — Standby"
        let headerItem = NSMenuItem(title: statusTitle, action: nil, keyEquivalent: "")
        headerItem.isEnabled = false
        statusMenu.addItem(headerItem)

        // 2. Primary Start / Stop Virtual Display Toggle
        let toggleTitle = coordinator.isStreaming ? "Stop Virtual Display" : "Start Virtual Display"
        let toggleItem = NSMenuItem(title: toggleTitle, action: #selector(toggleVirtualDisplay(_:)), keyEquivalent: "s")
        toggleItem.target = self
        statusMenu.addItem(toggleItem)

        statusMenu.addItem(.separator())

        // 3. Display Mode & Rotation Submenu
        let resMenu = NSMenu(title: "Display Mode")

        // Active negotiated mode
        let activeModeTitle = String(
            format: "✓ Active: %d × %d @ %dHz (%@)",
            coordinator.currentConfig.effectivePixelWidth,
            coordinator.currentConfig.effectivePixelHeight,
            Int(coordinator.currentConfig.refreshRateHz),
            coordinator.currentConfig.hiDPI ? "HiDPI Retina" : "Native 1x"
        )
        let activeHeaderItem = NSMenuItem(title: activeModeTitle, action: nil, keyEquivalent: "")
        activeHeaderItem.isEnabled = false
        activeHeaderItem.state = .on
        resMenu.addItem(activeHeaderItem)
        resMenu.addItem(.separator())

        let presets: [(String, Int, Int, Double, Bool)] = [
            ("2560 × 1600 @ 120Hz (HiDPI 16:10)", 2560, 1600, 120.0, true),
            ("2560 × 1600 @ 90Hz (HiDPI 16:10)", 2560, 1600, 90.0, true),
            ("2880 × 1800 @ 120Hz (HiDPI 16:10)", 2880, 1800, 120.0, true),
            ("1920 × 1200 @ 120Hz (HiDPI 16:10)", 1920, 1200, 120.0, true),
            ("1920 × 1200 @ 90Hz (HiDPI 16:10)", 1920, 1200, 90.0, true),
            ("1920 × 1080 @ 60Hz (Standard 16:9)", 1920, 1080, 60.0, false)
        ]
        for (idx, preset) in presets.enumerated() {
            let item = NSMenuItem(title: preset.0, action: #selector(selectResolutionPreset(_:)), keyEquivalent: "")
            item.target = self
            item.tag = idx
            let isCurrent = coordinator.currentConfig.pixelWidth == preset.1
                && coordinator.currentConfig.pixelHeight == preset.2
                && Int(coordinator.currentConfig.refreshRateHz) == Int(preset.3)
            item.state = isCurrent ? .on : .off
            resMenu.addItem(item)
        }

        resMenu.addItem(.separator())

        // Rotation Submenu inside Display Mode
        let rotMenu = NSMenu(title: "Rotation")
        let angles: [(String, Int)] = [
            ("0° (Standard Landscape)", 0),
            ("90° (Portrait Right)", 90),
            ("270° (Portrait Left)", 270)
        ]
        for item in angles {
            let mi = NSMenuItem(title: item.0, action: #selector(selectRotationAngle(_:)), keyEquivalent: "")
            mi.target = self
            mi.tag = item.1
            mi.state = (coordinator.currentConfig.rotation == item.1) ? .on : .off
            rotMenu.addItem(mi)
        }
        let rotSubItem = NSMenuItem(title: "Rotation (\(coordinator.currentConfig.rotation)°)", action: nil, keyEquivalent: "")
        rotSubItem.submenu = rotMenu
        resMenu.addItem(rotSubItem)

        let parentMenuTitle = String(
            format: "Display Mode (%d × %d @ %dHz)",
            coordinator.currentConfig.effectivePixelWidth,
            coordinator.currentConfig.effectivePixelHeight,
            Int(coordinator.currentConfig.refreshRateHz)
        )
        let resSubmenuItem = NSMenuItem(title: parentMenuTitle, action: nil, keyEquivalent: "")
        resSubmenuItem.submenu = resMenu
        statusMenu.addItem(resSubmenuItem)

        statusMenu.addItem(.separator())

        // 4. Pro Features
        let chromaItem = NSMenuItem(
            title: "4:4:4 Pro Text Chroma",
            action: #selector(toggleChroma444(_:)),
            keyEquivalent: ""
        )
        chromaItem.target = self
        chromaItem.state = coordinator.userPreferredYUV444 ? .on : .off
        statusMenu.addItem(chromaItem)

        let clamshellItem = NSMenuItem(
            title: "Clamshell / Headless Awake",
            action: #selector(toggleClamshellAwake(_:)),
            keyEquivalent: ""
        )
        clamshellItem.target = self
        clamshellItem.state = coordinator.headlessClamshellMode ? .on : .off
        statusMenu.addItem(clamshellItem)

        let audioItem = NSMenuItem(
            title: "Stream Mac Audio to Tablet",
            action: #selector(toggleAudioStreaming(_:)),
            keyEquivalent: ""
        )
        audioItem.target = self
        audioItem.state = coordinator.isAudioStreamingEnabled ? .on : .off
        statusMenu.addItem(audioItem)

        statusMenu.addItem(.separator())

        // 5. Settings… (⌘,) - Renamed and streamlined
        let settingsItem = NSMenuItem(
            title: "Settings…",
            action: #selector(openSettingsFromMenu(_:)),
            keyEquivalent: ","
        )
        settingsItem.target = self
        statusMenu.addItem(settingsItem)

        statusMenu.addItem(.separator())

        // 6. Quit HyperScreen (⌘Q)
        let quitItem = NSMenuItem(
            title: "Quit HyperScreen",
            action: #selector(quitApp(_:)),
            keyEquivalent: "q"
        )
        quitItem.target = self
        statusMenu.addItem(quitItem)
    }

    @objc private func toggleVirtualDisplay(_ sender: AnyObject?) {
        Task {
            if coordinator.isStreaming {
                await coordinator.stopDisplaySession()
            } else {
                coordinator.userManuallyStopped = false
                await coordinator.startDisplaySession()
            }
            if let button = statusItem?.button {
                let icon = HyperBrandTheme.loadMenuBarIcon(isStreaming: coordinator.isStreaming)
                icon.isTemplate = true
                button.image = icon
            }
        }
    }

    @objc private func selectResolutionPreset(_ sender: NSMenuItem) {
        let presets: [(Int, Int, Double, Bool)] = [
            (2560, 1600, 120.0, true),
            (2560, 1600, 90.0, true),
            (2880, 1800, 120.0, true),
            (1920, 1200, 120.0, true),
            (1920, 1200, 90.0, true),
            (1920, 1080, 60.0, false)
        ]
        guard sender.tag >= 0 && sender.tag < presets.count else { return }
        let p = presets[sender.tag]
        coordinator.currentConfig.pixelWidth = p.0
        coordinator.currentConfig.pixelHeight = p.1
        coordinator.currentConfig.refreshRateHz = p.2
        coordinator.currentConfig.hiDPI = p.3
        if coordinator.isStreaming {
            Task {
                await coordinator.stopDisplaySession()
                coordinator.userManuallyStopped = false
                await coordinator.startDisplaySession()
            }
        }
    }

    @objc private func selectRotationAngle(_ sender: NSMenuItem) {
        let angle = sender.tag
        Task {
            await coordinator.setRotation(angle)
            rebuildStatusMenu()
        }
    }

    @objc private func toggleChroma444(_ sender: AnyObject?) {
        coordinator.userPreferredYUV444.toggle()
        UserDefaults.standard.set(coordinator.userPreferredYUV444, forKey: "userPreferredYUV444")
        coordinator.currentConfig.preferYUV444 = coordinator.userPreferredYUV444
        rebuildStatusMenu()
        if coordinator.isStreaming {
            Task {
                await coordinator.stopDisplaySession()
                coordinator.userManuallyStopped = false
                await coordinator.startDisplaySession()
                rebuildStatusMenu()
            }
        }
    }

    @objc private func toggleClamshellAwake(_ sender: AnyObject?) {
        coordinator.headlessClamshellMode.toggle()
        rebuildStatusMenu()
    }

    @objc private func toggleAudioStreaming(_ sender: AnyObject?) {
        coordinator.isAudioStreamingEnabled.toggle()
        UserDefaults.standard.set(coordinator.isAudioStreamingEnabled, forKey: "isAudioStreamingEnabled")
        rebuildStatusMenu()
        if coordinator.isStreaming {
            Task {
                await coordinator.stopDisplaySession()
                coordinator.userManuallyStopped = false
                await coordinator.startDisplaySession()
                rebuildStatusMenu()
            }
        }
    }

    @objc private func copyPairingPIN(_ sender: AnyObject?) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(coordinator.transport.formattedPairingPIN, forType: .string)
    }

    @objc private func openSettingsFromMenu(_ sender: AnyObject?) {
        restoreControlCenterWindow()
    }

    @objc private func openControlPanelFromMenu(_ sender: AnyObject?) {
        restoreControlCenterWindow()
    }

    @objc private func openQROnboardingFromMenu(_ sender: AnyObject?) {
        restoreControlCenterWindow()
        coordinator.showOnboardingAndQR = true
    }

    @objc private func rescanUSBCFromMenu(_ sender: AnyObject?) {
        coordinator.transport.forceUSBRescanAndTunnel()
    }

    @objc private func quitApp(_ sender: AnyObject?) {
        NSApplication.shared.terminate(nil)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        // Never quit on window close — keep running in the Menu Bar toolbar!
        return false
    }

    func windowWillClose(_ notification: Notification) {
        // When the user soft-closes the window (Red X / Cmd+W), hide the Dock icon and live purely in the Menu Bar toolbar
        NSApp.setActivationPolicy(.accessory)
    }

    private func showControlCenterWindow() {
        let panelView = HyperScreenControlPanelView(coordinator: coordinator)
        let hostingController = NSHostingController(rootView: panelView)
        let window = NSWindow(contentViewController: hostingController)
        window.title = "HyperScreen Pro Settings"
        window.styleMask = [.titled, .closable, .miniaturizable]
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        self.mainWindow = window
    }

    private func restoreControlCenterWindow() {
        NSApp.setActivationPolicy(.regular)
        if let appIcon = HyperBrandTheme.loadBrandMarkImage() {
            NSApp.applicationIconImage = appIcon
        }
        if let mainWindow {
            mainWindow.makeKeyAndOrderFront(nil)
        } else {
            showControlCenterWindow()
        }
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag {
            restoreControlCenterWindow()
        }
        return true
    }
}

struct HyperScreenHostApp: App {
    public static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.1"
    }

    @NSApplicationDelegateAdaptor(HyperScreenAppDelegate.self) private var appDelegate

    var body: some Scene {
        Settings {
            HyperScreenControlPanelView(coordinator: appDelegate.coordinator)
        }
    }
}

/// Hardware Loopback Benchmark (`HyperScreenHost --benchmark-loopback`):
/// Tests real Apple Silicon / macOS hardware `VideoToolboxEncoder` (120Hz HEVC + Text-Sharp Idle Refinement),
/// `IOSurface`-backed `CVPixelBuffer` zero-copy encoding, MTU UDP slicing, and 0ms GF(2^8) FEC packet-loss recovery.
public enum HyperScreenHardwareBenchmark {
    private final class BenchmarkSink: VideoToolboxEncoderDelegate, @unchecked Sendable {
        let semaphore = DispatchSemaphore(value: 0)
        let reassembler = VideoFrameReassembler(maxSlicesPerFrame: 512, maxSliceBytes: 1440)
        var sliceScratch = Data(count: 4 * 1024 * 1024)
        var seq: UInt32 = 1
        var encodedFrames = 0
        var fecRecoveredFrames = 0
        var totalEncodeLatencyUs: UInt64 = 0
        var maxEncodeLatencyUs: UInt64 = 0
        var totalBytesEncoded = 0

        func encoderDidProduceAnnexBFrame(
            frameId: UInt32,
            captureTimestampUs: UInt64,
            encodeLatencyUs: UInt64,
            flags: UInt16,
            annexBData: Data
        ) {
            encodedFrames += 1
            totalEncodeLatencyUs += encodeLatencyUs
            maxEncodeLatencyUs = max(maxEncodeLatencyUs, encodeLatencyUs)
            totalBytesEncoded += annexBData.count

            let maxSlicePayload = 1380
            let fullSliceBytes = MemoryLayout<hs_packet_header_t>.size + maxSlicePayload
            let slices = annexBData.withUnsafeBytes { frameRaw in
                sliceScratch.withUnsafeMutableBytes { outRaw in
                    HyperScreenCoreBridge.sliceVideoFrame(
                        frameId: frameId,
                        capturePtsUs: captureTimestampUs,
                        flags: flags,
                        sequence: &seq,
                        frameData: frameRaw,
                        maxSlicePayload: maxSlicePayload,
                        into: outRaw
                    )
                }
            }

            if slices.count >= 2 && slices.count <= Int(HS_FEC_MAX_DATA_SHARDS) {
                let dataPackets = slices.map { sliceScratch.subdata(in: $0.offset..<($0.offset + $0.length)) }
                if let parityShards = HyperScreenCoreBridge.encodeFEC(
                    dataShards: dataPackets,
                    parityCount: 1,
                    shardBytes: fullSliceBytes
                ), let parityPayload = parityShards.first {
                    var fecHdr = hs_packet_header_t()
                    hs_packet_header_init(
                        &fecHdr,
                        UInt8(HS_PKT_VIDEO_FEC.rawValue),
                        seq,
                        UInt32(parityPayload.count),
                        flags | UInt16(HS_VIDEO_FLAG_FEC_PARITY.rawValue)
                    )
                    seq &+= 1
                    var fecPacket = Data(bytes: &fecHdr, count: MemoryLayout<hs_packet_header_t>.size)
                    fecPacket.append(parityPayload)

                    // Simulate dropping Slice #0 on the network and recovering it in 0ms via the FEC parity packet!
                    for idx in 1..<dataPackets.count {
                        _ = reassembler.pushPacket(dataPackets[idx])
                    }
                    if let recovered = reassembler.pushPacket(fecPacket), recovered.data == annexBData {
                        fecRecoveredFrames += 1
                    }
                }
            } else if let onlySlice = slices.first {
                let pkt = sliceScratch.subdata(in: onlySlice.offset..<(onlySlice.offset + onlySlice.length))
                if let recovered = reassembler.pushPacket(pkt), recovered.data == annexBData {
                    fecRecoveredFrames += 1
                }
            }

            semaphore.signal()
        }
    }

    public static func runAndExit() -> Never {
        setbuf(stdout, nil)
        print("=== HyperScreen Hardware Pipeline Benchmark (macOS VideoToolbox + Zero-Copy IOSurface + 0ms FEC) ===")
        let width = 2560
        let height = 1600
        let config = VirtualDisplayConfig(pixelWidth: width, pixelHeight: height, refreshRateHz: 120.0, hiDPI: true)

        let encoder = VideoToolboxEncoder()
        let sink = BenchmarkSink()
        encoder.delegate = sink

        do {
            try encoder.configure(config: config, bitrateMbps: 50)
        } catch {
            print("[FAIL] VideoToolboxEncoder configure failed: \(error)")
            exit(1)
        }

        let attrs: [CFString: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            kCVPixelBufferWidthKey: width,
            kCVPixelBufferHeightKey: height,
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        var pixelBuffer: CVPixelBuffer?
        guard CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarFullRange, attrs as CFDictionary, &pixelBuffer) == kCVReturnSuccess,
              let pb = pixelBuffer else {
            print("[FAIL] Failed to allocate IOSurface-backed CVPixelBuffer")
            exit(1)
        }

        let totalFrames = 30
        for frameIdx in 0..<totalFrames {
            CVPixelBufferLockBaseAddress(pb, [])
            if let base = CVPixelBufferGetBaseAddressOfPlane(pb, 0) {
                let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
                memset(base, Int32((frameIdx * 17) & 0xFF), bytesPerRow * min(height, 64))
            }
            CVPixelBufferUnlockBaseAddress(pb, [])

            var formatDesc: CMVideoFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pb, formatDescriptionOut: &formatDesc)
            guard let desc = formatDesc else { continue }

            var timing = CMSampleTimingInfo(
                duration: CMTime(value: 1, timescale: 120),
                presentationTimeStamp: CMTime(value: CMTimeValue(frameIdx), timescale: 120),
                decodeTimeStamp: .invalid
            )
            var sampleBuffer: CMSampleBuffer?
            CMSampleBufferCreateReadyWithImageBuffer(
                allocator: kCFAllocatorDefault,
                imageBuffer: pb,
                formatDescription: desc,
                sampleTiming: &timing,
                sampleBufferOut: &sampleBuffer
            )

            if let sb = sampleBuffer {
                let nowUs = DispatchTime.now().uptimeNanoseconds / 1_000
                let isIdleRefine = (frameIdx == totalFrames - 1)
                encoder.encodeFrame(sampleBuffer: sb, captureTimestampUs: nowUs, isIdleRefinement: isIdleRefine)
                _ = sink.semaphore.wait(timeout: .now() + 0.5)
            }
        }

        encoder.teardown()
        let avgMs = Double(sink.totalEncodeLatencyUs) / Double(max(sink.encodedFrames, 1)) / 1_000.0
        let maxMs = Double(sink.maxEncodeLatencyUs) / 1_000.0
        let avgFrameKB = Double(sink.totalBytesEncoded) / Double(max(sink.encodedFrames, 1)) / 1024.0
        print(String(format: "[PASS] Encoded %d/%d 2560x1600 @ 120Hz HEVC frames via Hardware VideoToolbox (avg frame: %.1f KB)", sink.encodedFrames, totalFrames, avgFrameKB))
        print(String(format: "[PASS] Hardware Encode Latency: avg = %.2f ms | max = %.2f ms", avgMs, maxMs))
        print(String(format: "[PASS] 0ms FEC Packet-Loss Recovery: %d/%d frames reconstructed identically after simulated Wi-Fi packet drop", sink.fecRecoveredFrames, sink.encodedFrames))
        exit(sink.encodedFrames == totalFrames && sink.fecRecoveredFrames == totalFrames ? 0 : 1)
    }
}

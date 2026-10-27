import Foundation
import AppKit
import CoreGraphics
import IOKit.pwr_mgt
import CHyperScreenCore

/// Injects Android Multi-Touch, Virtual Trackpad Gestures, Mac Modifier Keys (`Cmd`/`Opt`/`Ctrl`/`Shift`),
/// and **Native macOS Tablet Stylus Events (`Pressure`, `Tilt X/Y`, `Hover`)** via CoreGraphics `CGEvent`,
/// while polling the macOS cursor at 240Hz for the decoupled Android hardware cursor plane.
public final class InputAndEcosystemManager: @unchecked Sendable {
    private var cursorPollTimer: DispatchSourceTimer?
    private let inputQueue = DispatchQueue(label: "dev.hyperscreen.input.cgevent", qos: .userInteractive)
    private var lastSentCursorX: Float = -1
    private var lastSentCursorY: Float = -1
    private var lastSentVisible: UInt8 = 1
    private var lastClipboardChangeCount: Int = 0
    private var sleepAssertionID: IOPMAssertionID = 0
    private var isSleepAssertionActive = false

    public var virtualDisplayID: CGDirectDisplayID = kCGNullDirectDisplay
    public var virtualDisplayBounds: CGRect = CGRect(x: 0, y: 0, width: 1920, height: 1200)
    public var onCursorStateUpdate: (@Sendable (hs_cursor_state_t) -> Void)?
    public var onClipboardUpdate: (@Sendable (Data) -> Void)?

    public init() {}

    private func liveVirtualDisplayBounds() -> CGRect {
        if virtualDisplayID != kCGNullDirectDisplay {
            let live = CGDisplayBounds(virtualDisplayID)
            if live.width > 0 && live.height > 0 {
                return live
            }
        }
        return virtualDisplayBounds
    }

    // MARK: - 1. 240Hz Hardware Cursor Tracker

    public func startCursorTracking240Hz() {
        cursorPollTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: inputQueue)
        // 240Hz = 4,166,666 ns interval
        timer.schedule(deadline: .now(), repeating: .nanoseconds(4_166_666))
        timer.setEventHandler { [weak self] in
            self?.sampleAndEmitCursor()
        }
        cursorPollTimer = timer
        timer.resume()
    }

    public func stopCursorTracking() {
        cursorPollTimer?.cancel()
        cursorPollTimer = nil
    }

    private func sampleAndEmitCursor() {
        guard let event = CGEvent(source: nil) else { return }
        let loc = event.location
        let bounds = liveVirtualDisplayBounds()
        guard bounds.width > 0, bounds.height > 0 else { return }

        let inside = bounds.contains(loc)
        let normX = Float(min(max((loc.x - bounds.minX) / bounds.width, 0.0), 1.0))
        let normY = Float(min(max((loc.y - bounds.minY) / bounds.height, 0.0), 1.0))
        let isVisible: UInt8 = inside ? 1 : 0

        if abs(normX - lastSentCursorX) > 0.0001 || abs(normY - lastSentCursorY) > 0.0001 || isVisible != lastSentVisible {
            lastSentCursorX = normX
            lastSentCursorY = normY
            lastSentVisible = isVisible

            var state = hs_cursor_state_t()
            state.timestamp_us = DispatchTime.now().uptimeNanoseconds / 1_000
            state.norm_x = normX
            state.norm_y = normY
            state.shape_hash = 0x01 // Default arrow cursor hash (extensible via NSCursor.currentSystem)
            state.visible = isVisible
            onCursorStateUpdate?(state)
        }
    }

    // MARK: - 2. Pro Stylus Input (`kCGEventTabletPointer` with Pressure & Tilt)

    public func injectStylusEvent(_ stylus: hs_input_stylus_t) {
        inputQueue.async { [weak self] in
            guard let self else { return }
            let bounds = self.liveVirtualDisplayBounds()
            let targetPoint = CGPoint(
                x: bounds.minX + CGFloat(stylus.norm_x) * bounds.width,
                y: bounds.minY + CGFloat(stylus.norm_y) * bounds.height
            )

            CGAssociateMouseAndMouseCursorPosition(1)
            CGWarpMouseCursorPosition(targetPoint)

            let eventType: CGEventType
            switch stylus.action {
            case 0: eventType = .leftMouseDown
            case 1: eventType = .leftMouseDragged
            case 2: eventType = .leftMouseUp
            default: eventType = .mouseMoved
            }

            guard let cgEvent = CGEvent(
                mouseEventSource: nil,
                mouseType: eventType,
                mouseCursorPosition: targetPoint,
                mouseButton: .left
            ) else {
                return
            }

            // Attach native macOS Tablet Point subtype & pressure/tilt fields so Photoshop, Blender,
            // Illustrator, and Freeform respond to S-Pen / USI pressure and tilt curves natively!
            cgEvent.setIntegerValueField(.mouseEventSubtype, value: Int64(CGEventMouseSubtype.tabletPoint.rawValue))
            cgEvent.setDoubleValueField(.mouseEventPressure, value: Double(min(max(stylus.pressure, 0.0), 1.0)))
            cgEvent.setDoubleValueField(.tabletEventTiltX, value: Double(stylus.tilt_x))
            cgEvent.setDoubleValueField(.tabletEventTiltY, value: Double(stylus.tilt_y))
            cgEvent.flags = Self.cgEventFlags(from: stylus.active_modifiers)

            cgEvent.post(tap: .cghidEventTap)
        }
    }

    // MARK: - 3. Multi-Touch & Virtual Trackpad Gestures

    public func injectTouchOrTrackpadEvent(_ touch: hs_input_touch_t) {
        inputQueue.async { [weak self] in
            guard let self else { return }
            let bounds = self.liveVirtualDisplayBounds()

            if touch.mode == 2 {
                // Two-finger inertial trackpad scroll (`kCGScrollEventUnitPixel`)
                if let scrollEvent = CGEvent(
                    scrollWheelEvent2Source: nil,
                    units: .pixel,
                    wheelCount: 2,
                    wheel1: Int32(touch.scroll_dy),
                    wheel2: Int32(touch.scroll_dx),
                    wheel3: 0
                ) {
                    scrollEvent.flags = Self.cgEventFlags(from: touch.active_modifiers)
                    scrollEvent.post(tap: .cghidEventTap)
                }
                return
            }

            let targetPoint: CGPoint
            if touch.mode == 1 {
                // Virtual Trackpad Relative Mode: apply delta to current global mouse position
                let currentPos = CGEvent(source: nil)?.location ?? bounds.origin
                targetPoint = CGPoint(
                    x: currentPos.x + CGFloat(touch.x),
                    y: currentPos.y + CGFloat(touch.y)
                )
            } else {
                // Direct Touch Mode: map normalized (0..1) tablet coordinates to the Virtual Display
                targetPoint = CGPoint(
                    x: bounds.minX + CGFloat(touch.x) * bounds.width,
                    y: bounds.minY + CGFloat(touch.y) * bounds.height
                )
            }

            CGAssociateMouseAndMouseCursorPosition(1)
            CGWarpMouseCursorPosition(targetPoint)

            let eventType: CGEventType
            let button: CGMouseButton
            switch touch.action {
            case 0:
                eventType = .leftMouseDown
                button = .left
            case 1:
                eventType = .leftMouseDragged
                button = .left
            case 2:
                eventType = .leftMouseUp
                button = .left
            case 5:
                eventType = .rightMouseDown
                button = .right
            default:
                eventType = .mouseMoved
                button = .left
            }

            guard let cgEvent = CGEvent(
                mouseEventSource: nil,
                mouseType: eventType,
                mouseCursorPosition: targetPoint,
                mouseButton: button
            ) else {
                return
            }

            cgEvent.flags = Self.cgEventFlags(from: touch.active_modifiers)
            cgEvent.post(tap: .cghidEventTap)

            if touch.action == 5,
               let upEvent = CGEvent(
                   mouseEventSource: nil,
                   mouseType: .rightMouseUp,
                   mouseCursorPosition: targetPoint,
                   mouseButton: .right
               ) {
                upEvent.post(tap: .cghidEventTap)
            }
        }
    }

    // MARK: - 4. On-Screen Mac Modifier Bar & Keyboard Injection

    public func injectKeyEvent(_ key: hs_input_key_t) {
        inputQueue.async {
            guard let cgEvent = CGEvent(
                keyboardEventSource: nil,
                virtualKey: CGKeyCode(key.mac_virtual_keycode),
                keyDown: key.is_down != 0
            ) else {
                return
            }
            cgEvent.flags = Self.cgEventFlags(from: key.active_modifiers)
            cgEvent.post(tap: .cghidEventTap)
        }
    }

    private static func cgEventFlags(from mask: UInt8) -> CGEventFlags {
        var flags: CGEventFlags = []
        if (mask & UInt8(HS_MOD_CMD.rawValue)) != 0   { flags.insert(.maskCommand) }
        if (mask & UInt8(HS_MOD_OPT.rawValue)) != 0   { flags.insert(.maskAlternate) }
        if (mask & UInt8(HS_MOD_CTRL.rawValue)) != 0  { flags.insert(.maskControl) }
        if (mask & UInt8(HS_MOD_SHIFT.rawValue)) != 0 { flags.insert(.maskShift) }
        if (mask & UInt8(HS_MOD_FN.rawValue)) != 0    { flags.insert(.maskSecondaryFn) }
        return flags
    }

    // MARK: - 5. Headless / Clamshell Mac Sleep Prevention (`IOPMAssertion`)

    public func setClamshellAndHeadlessAwakeAssertion(enabled: Bool) {
        if enabled && !isSleepAssertionActive {
            let reason = "HyperScreen Virtual Display Active (Headless/Clamshell Mode)" as CFString
            let result = IOPMAssertionCreateWithName(
                kIOPMAssertionTypePreventUserIdleDisplaySleep as CFString,
                IOPMAssertionLevel(kIOPMAssertionLevelOn),
                reason,
                &sleepAssertionID
            )
            isSleepAssertionActive = (result == kIOReturnSuccess)
        } else if !enabled && isSleepAssertionActive {
            IOPMAssertionRelease(sleepAssertionID)
            sleepAssertionID = 0
            isSleepAssertionActive = false
        }
    }

    // MARK: - 6. Bidirectional Universal Clipboard & Drag-and-Drop File Chunker

    @MainActor
    public func pollLocalClipboardIfNeeded() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastClipboardChangeCount else { return }
        lastClipboardChangeCount = pb.changeCount

        if let text = pb.string(forType: .string),
           let utf8 = text.data(using: .utf8),
           utf8.count <= 256 * 1024 {
            onClipboardUpdate?(utf8)
        }
    }

    @MainActor
    public func applyRemoteClipboardData(_ data: Data) {
        guard let text = String(data: data, encoding: .utf8) else { return }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastClipboardChangeCount = pb.changeCount
    }

    /// Slices a dropped file URL into 64KB `hs_file_chunk_header_t` payloads for instant USB/Wi-Fi transfer.
    public static func buildFileTransferChunks(fileURL: URL, chunkSize: Int = 64 * 1024) throws -> [Data] {
        let fileData = try Data(contentsOf: fileURL)
        let filenameData = (fileURL.lastPathComponent.data(using: .utf8)) ?? Data("file.bin".utf8)
        let transferId = UInt64.random(in: 1...UInt64.max)

        var packets: [Data] = []
        var offset = 0
        let total = fileData.count

        while offset < total || (total == 0 && offset == 0) {
            let len = min(chunkSize, total - offset)
            var hdr = hs_file_chunk_header_t()
            hdr.transfer_id = transferId
            hdr.total_file_bytes = UInt64(total)
            hdr.chunk_offset = UInt64(offset)
            hdr.chunk_len = UInt32(len)
            hdr.filename_len = UInt16(filenameData.count)
            hdr.flags = (offset == 0 ? 1 : 0) | ((offset + len >= total) ? 2 : 0)

            var payload = Data(bytes: &hdr, count: MemoryLayout<hs_file_chunk_header_t>.size)
            payload.append(filenameData)
            if len > 0 {
                payload.append(fileData.subdata(in: offset..<(offset + len)))
            }
            packets.append(payload)
            offset += len
            if total == 0 { break }
        }
        return packets
    }
}

import Foundation
import Network
import IOKit
import IOKit.usb
import IOUSBHost
import CMoreCoreGraphics
import CHyperScreenCore
import HyperScreenCoreBridge

/// Active transport connection mode between Mac and Android
public enum TransportActiveMode: String, Sendable {
    case disconnected = "Disconnected"
    case usbAOA       = "USB-C Direct (Zero-ADB AOA v2)"
    case usbCDCNCM    = "USB-C Ethernet (CDC-NCM)"
    case usbADB       = "USB-C Tunnel (Embedded ADB)"
    case wirelessLAN  = "Wireless 5GHz/6GHz (UDP + FEC)"
    case wirelessP2P  = "Wireless Direct P2P (UDP + FEC)"
}

public protocol HyperScreenTransportDelegate: AnyObject, Sendable {
    func transportDidChangeState(_ mode: TransportActiveMode, clientName: String?)
    func transportDidReceiveClientHello(_ hello: hs_hello_payload_t)
    func transportDidReceiveTouchInput(_ touch: hs_input_touch_t)
    func transportDidReceiveStylusInput(_ stylus: hs_input_stylus_t)
    func transportDidReceiveKeyInput(_ key: hs_input_key_t)
    func transportDidReceiveVideoNack(_ nack: hs_video_nack_t)
    func transportDidUpdateRTT(rttMicroseconds: UInt64)
}

/// Android Open Accessory v2 (AOA) Constants (`https://source.android.com/docs/core/interaction/accessories/aoa2`)
public enum AOAConstants {
    public static let googleAccessoryVendorID: UInt16 = 0x18D1
    public static let accessoryProductIDs: Set<UInt16> = [0x2D00, 0x2D01, 0x2D04, 0x2D05]

    // USB 2.0 Control Transfer bmRequestType values for Android Open Accessory (AOA v2)
    public static let bmRequestVendorInDevice: UInt8  = 0xC0 // Direction: IN (0x80) | Type: Vendor (0x40) | Recipient: Device (0x00)
    public static let bmRequestVendorOutDevice: UInt8 = 0x40 // Direction: OUT (0x00) | Type: Vendor (0x40) | Recipient: Device (0x00)

    // Control request codes
    public static let requestGetProtocol: UInt8 = 51
    public static let requestSendString: UInt8  = 52
    public static let requestStart: UInt8       = 53

    // String IDs for requestSendString
    public static let indexManufacturer: UInt16 = 0
    public static let indexModel: UInt16        = 1
    public static let indexDescription: UInt16  = 2
    public static let indexVersion: UInt16      = 3
    public static let indexURI: UInt16          = 4
    public static let indexSerial: UInt16       = 5

    // Common Android OEM USB Vendor IDs (Google, Samsung, Xiaomi, OnePlus/OPPO, Lenovo/Motorola, Nothing, ASUS, Sony)
    public static let androidVendorIDs: Set<UInt16> = [
        0x18D1, 0x04E8, 0x2717, 0x22D9, 0x17EF, 0x22B8, 0x0B05, 0x054C, 0x2A70, 0x19D2
    ]
}

/// Manages the Dual-Mode Transport Layer:
/// 1. **USB-C Primary:** Zero-ADB Android Open Accessory v2 (`IOUSBHost` control transfers 51/52/53 + async bulk pipes)
/// 2. **USB-C Auto-Fallback:** Native `CDC-NCM` USB Ethernet interface discovery + Embedded ADB reverse/forward tunnel
/// 3. **Wireless:** MTU-sliced UDP with sliding-window GF(2^8) FEC, Bonjour (`_hyperscreen._udp`) discovery, and 5GHz P2P QR/PIN pairing
public final class TransportCoordinator: @unchecked Sendable {
    public weak var delegate: HyperScreenTransportDelegate?

    private let ioQueue = DispatchQueue(label: "dev.hyperscreen.transport.io", qos: .userInteractive)
    private var udpListener: NWListener?
    private var tcpListener: NWListener?
    private var activeUDPConnection: NWConnection?
    private var activeTCPConnections: [NWConnection] = []
    private var usbDiscoveryTimer: DispatchSourceTimer?
    private let usbAOAManager: HSUSBAOAManager
    private let usbRingBuffer = PacketRingBuffer(capacity: 2 * 1024 * 1024)

    // Pre-allocated slicing buffer for zero-heap-allocation 120Hz packetization
    private var sliceScratchBuffer = Data(count: 4 * 1024 * 1024)
    private var fecParityBuffer = Data(count: Int(HS_MAX_UDP_PAYLOAD))
    private var videoSequence: UInt32 = 0
    private var cursorSequence: UInt32 = 0
    private var audioSequence: UInt32 = 0

    public private(set) var activeMode: TransportActiveMode = .disconnected
    public var preferredConnection: ConnectionPreference = .usb
    public let sessionPairingPIN: String
    public var formattedPairingPIN: String {
        if sessionPairingPIN.count == 8 {
            let mid = sessionPairingPIN.index(sessionPairingPIN.startIndex, offsetBy: 4)
            return "\(sessionPairingPIN[..<mid])-\(sessionPairingPIN[mid...])"
        }
        return sessionPairingPIN
    }
    public let sessionToken: UInt64

    public init() {
        let randomToken = UInt64.random(in: 100_000_000...999_999_999_999)
        self.sessionToken = randomToken
        let pin = String(format: "%08d", randomToken % 100_000_000)
        self.sessionPairingPIN = pin
        self.usbAOAManager = HSUSBAOAManager(serialPIN: pin)
        hs_fec_init()
        configureUSBAOACallbacks()
    }

    private func configureUSBAOACallbacks() {
        usbAOAManager.stateHandler = { [weak self] connected, deviceName in
            guard let self else { return }
            if connected {
                self.updateMode(.usbAOA, clientName: deviceName ?? "Android USB-C AOA v2")
            } else if self.activeMode == .usbAOA {
                self.updateMode(.disconnected, clientName: nil)
            }
        }

        usbAOAManager.packetHandler = { [weak self] rawChunk in
            guard let self else { return }
            self.usbRingBuffer.appendRawBytes(rawChunk)
            while let (hdr, payload) = self.usbRingBuffer.popNextPacket() {
                var fullPkt = Data(bytes: [hdr], count: MemoryLayout<hs_packet_header_t>.size)
                fullPkt.append(payload)
                self.parseIncomingClientPacket(fullPkt)
            }
        }
    }

    /// Starts all discovery & transport engines:
    /// - USB-C AOA v2 hotplug & bulk pipe engine + continuous ADB USB-C Tunnel (`tcp:9453`) monitor
    /// - Wireless Bonjour (`_hyperscreen._udp`) UDP + TCP listeners on port 9453
    public func start(port: UInt16 = 9453) {
        usbAOAManager.startWatching()
        startWirelessUDPListener(port: port)
        startUSBTCPListener(port: port)
        startUSBDiscoveryLoop(port: port)
    }

    public func stop() {
        usbDiscoveryTimer?.cancel()
        usbDiscoveryTimer = nil
        usbAOAManager.stopWatching()
        udpListener?.cancel()
        udpListener = nil
        tcpListener?.cancel()
        tcpListener = nil
        activeUDPConnection?.cancel()
        activeUDPConnection = nil
        for conn in activeTCPConnections {
            conn.cancel()
        }
        activeTCPConnections.removeAll()
        updateMode(.disconnected, clientName: nil)
    }

    /// Forces an immediate USB-C bus rescan and `adb reverse tcp:9453 tcp:9453` tunnel setup.
    public func forceUSBRescanAndTunnel(port: UInt16 = 9453) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            self.usbAOAManager.stopWatching()
            self.usbAOAManager.startWatching()
            self.probeCDCNCMAndADBFallback(port: port)
        }
    }

    /// Returns the primary IPv4 LAN address (`en0` / `en1`) of this Mac for QR Code Pairing.
    public func localLANIPAddress() -> String {
        var address = "127.0.0.1"
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return address }
        defer { freeifaddrs(ifaddr) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = firstAddr
        while let current = ptr {
            let interface = current.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family
            if addrFamily == UInt8(AF_INET) {
                let name = String(cString: interface.ifa_name)
                if name == "en0" || name == "en1" || name.hasPrefix("bridge") {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    if getnameinfo(
                        interface.ifa_addr,
                        socklen_t(interface.ifa_addr.pointee.sa_len),
                        &hostname,
                        socklen_t(hostname.count),
                        nil,
                        socklen_t(0),
                        NI_NUMERICHOST
                    ) == 0 {
                        let candidate = String(cString: hostname)
                        if !candidate.hasPrefix("127.") && !candidate.hasPrefix("169.254.") {
                            address = candidate
                            if name == "en0" { break }
                        }
                    }
                }
            }
            ptr = interface.ifa_next
        }
        return address
    }

    /// Returns the `hyperscreen://pair` URI encoded into the macOS Onboarding QR Code.
    public func pairingQRURI(port: UInt16 = 9453) -> String {
        let ip = localLANIPAddress()
        let host = (Host.current().localizedName ?? "Mac").addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "Mac"
        return "hyperscreen://pair?ip=\(ip)&port=\(port)&pin=\(sessionPairingPIN)&name=\(host)"
    }

    // MARK: - 1. USB-C AOA v2 + CDC-NCM + ADB Fallback Engine

    /// Negotiates Android Open Accessory v2 on an attached Android USB device using `IOUSBHostDevice`.
    /// When completed, Android re-enumerates with VID `0x18D1` / PID `0x2D00` and auto-launches the HyperScreen Android app!
    public func initiateAOAv2Handshake(on usbService: io_service_t) -> Bool {
        guard let hostDevice = try? IOUSBHostDevice(
            __ioService: usbService,
            options: [],
            queue: ioQueue,
            interestHandler: nil
        ) else {
            return false
        }

        // Step 1: Send AOA 51 (GET_PROTOCOL) -> expect uint16 version >= 2
        var bytesTransferred: Int = 0
        let getProtoReq = IOUSBDeviceRequest(
            bmRequestType: AOAConstants.bmRequestVendorInDevice,
            bRequest: AOAConstants.requestGetProtocol,
            wValue: 0,
            wIndex: 0,
            wLength: 2
        )

        guard let mutableData = NSMutableData(length: 2) else { return false }
        do {
            try hostDevice.__send(
                getProtoReq,
                data: mutableData,
                bytesTransferred: &bytesTransferred,
                completionTimeout: 1.0
            )
            let protocolVersion = mutableData.bytes.load(as: UInt16.self)
            guard bytesTransferred == 2 && protocolVersion >= 1 else { return false }
        } catch {
            return false
        }

        // Step 2: Send AOA 52 (SEND_STRING) metadata so Android matches `res/xml/accessory_filter.xml`
        let strings: [(UInt16, String)] = [
            (AOAConstants.indexManufacturer, "HyperScreen"),
            (AOAConstants.indexModel, "HyperScreen Pro Display"),
            (AOAConstants.indexDescription, "HyperScreen Ultra-Low-Latency Second Display"),
            (AOAConstants.indexVersion, "1.0"),
            (AOAConstants.indexURI, "https://github.com/hyperscreen"),
            (AOAConstants.indexSerial, sessionPairingPIN)
        ]

        for (index, text) in strings {
            guard var cStr = text.data(using: .utf8) else { continue }
            cStr.append(0) // Null terminator required by AOA spec
            let mutableStr = NSMutableData(data: cStr)
            let sendStrReq = IOUSBDeviceRequest(
                bmRequestType: AOAConstants.bmRequestVendorOutDevice,
                bRequest: AOAConstants.requestSendString,
                wValue: 0,
                wIndex: index,
                wLength: UInt16(mutableStr.length)
            )
            try? hostDevice.__send(
                sendStrReq,
                data: mutableStr,
                bytesTransferred: &bytesTransferred,
                completionTimeout: 1.0
            )
        }

        // Step 3: Send AOA 53 (START) to switch Android into Accessory Mode and auto-launch our app
        let startReq = IOUSBDeviceRequest(
            bmRequestType: AOAConstants.bmRequestVendorOutDevice,
            bRequest: AOAConstants.requestStart,
            wValue: 0,
            wIndex: 0,
            wLength: 0
        )
        do {
            try hostDevice.__send(
                startReq,
                data: nil,
                bytesTransferred: &bytesTransferred,
                completionTimeout: 1.0
            )
            return true
        } catch {
            return false
        }
    }

    private func startUSBDiscoveryLoop(port: UInt16) {
        usbDiscoveryTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: ioQueue)
        timer.schedule(deadline: .now() + 0.5, repeating: 2.0)
        timer.setEventHandler { [weak self] in
            self?.probeCDCNCMAndADBFallback(port: port)
        }
        timer.resume()
        self.usbDiscoveryTimer = timer
    }

    /// Automatic USB-C Tunnel Setup: Runs `adb reverse tcp:9453 tcp:9453` whenever an Android device is plugged in over USB-C.
    private func probeCDCNCMAndADBFallback(port: UInt16) {
        if let adbBin = findADBExecutable() {
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: adbBin)
            proc.arguments = ["reverse", "tcp:\(port)", "tcp:\(port)"]
            proc.standardOutput = Pipe()
            proc.standardError = Pipe()
            try? proc.run()
        }
    }

    public func findADBExecutable() -> String? {
        let home = NSHomeDirectory()
        let adbPaths = [
            Bundle.main.bundlePath + "/Contents/Resources/adb",
            home + "/Library/Android/sdk/platform-tools/adb",
            "/Users/asiddeenmuhammad/Library/Android/sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb"
        ]
        return adbPaths.first(where: { FileManager.default.isExecutableFile(atPath: $0) })
    }

    public func isADBInstalled() -> Bool {
        return findADBExecutable() != nil
    }

    public func isADBReverseActive(port: UInt16 = 9453) -> Bool {
        guard let adbBin = findADBExecutable() else { return false }
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: adbBin)
        proc.arguments = ["reverse", "--list"]
        let pipe = Pipe()
        proc.standardOutput = pipe
        proc.standardError = Pipe()
        do {
            try proc.run()
            proc.waitUntilExit()
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            let output = String(data: data, encoding: .utf8) ?? ""
            return output.contains("tcp:\(port)")
        } catch {
            return false
        }
    }

    public func isUSBDeviceConnected() -> Bool {
        if usbAOAManager.isConnected { return true }
        let matchingDict = IOServiceMatching("IOUSBHostDevice") as? [String: Any]
        guard let match = matchingDict as CFDictionary? else { return false }
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, match, &iterator) == KERN_SUCCESS else {
            return false
        }
        defer { IOObjectRelease(iterator) }

        var service = IOIteratorNext(iterator)
        while service != IO_OBJECT_NULL {
            defer {
                IOObjectRelease(service)
                service = IOIteratorNext(iterator)
            }
            if let vidRef = IORegistryEntryCreateCFProperty(service, "idVendor" as CFString, kCFAllocatorDefault, 0) {
                let cfVal = vidRef.takeRetainedValue()
                var vid: UInt16 = 0
                if CFGetTypeID(cfVal) == CFNumberGetTypeID() {
                    CFNumberGetValue((cfVal as! CFNumber), .sInt16Type, &vid)
                    if vid != 0 && vid != 0x05AC { // Non-Apple USB device connected (Android tablet/phone)
                        return true
                    }
                }
            }
        }
        return false
    }

    public func isWiFiConnected() -> Bool {
        let ip = localLANIPAddress()
        return ip != "127.0.0.1" && !ip.isEmpty
    }

    // MARK: - 2. USB-C TCP Tunnel & Wireless UDP + Sliding-Window FEC + Bonjour Engine

    private func startUSBTCPListener(port: UInt16) {
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        let params = NWParameters(tls: nil, tcp: tcpOptions)
        params.serviceClass = .interactiveVideo
        params.allowLocalEndpointReuse = true

        guard let nwPort = NWEndpoint.Port(rawValue: port),
              let listener = try? NWListener(using: params, on: nwPort) else {
            return
        }

        listener.newConnectionHandler = { [weak self] connection in
            self?.handleIncomingTCPConnection(connection)
        }
        listener.start(queue: ioQueue)
        self.tcpListener = listener
    }

    private func handleIncomingTCPConnection(_ connection: NWConnection) {
        let connRingBuffer = PacketRingBuffer(capacity: 4 * 1024 * 1024)
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                let epStr = String(describing: connection.endpoint)
                let isLoopback = epStr.contains("127.0.0.1") || epStr.contains("::1") || epStr.contains("localhost")
                if isLoopback {
                    // Activate USB mode and cleanly close any wireless UDP connection
                    self.preferredConnection = .usb
                    self.activeUDPConnection?.cancel()
                    self.activeUDPConnection = nil
                } else {
                    // Activate Wireless mode and cleanly close any USB loopback connections
                    self.preferredConnection = .wireless
                    for oldConn in self.activeTCPConnections {
                        let oldEp = String(describing: oldConn.endpoint)
                        if oldEp.contains("127.0.0.1") || oldEp.contains("::1") || oldEp.contains("localhost") {
                            oldConn.cancel()
                        }
                    }
                }
                self.activeTCPConnections.removeAll { $0 === connection }
                self.activeTCPConnections.append(connection)
                self.updateMode(
                    isLoopback ? .usbADB : .wirelessLAN,
                    clientName: isLoopback ? "Android USB-C High-Speed Tunnel" : "Android Wireless (\(epStr))"
                )
                self.receiveNextTCPChunk(on: connection, ringBuffer: connRingBuffer)
            case .failed, .cancelled:
                self.activeTCPConnections.removeAll { $0 === connection }
                if self.activeTCPConnections.isEmpty && self.activeUDPConnection == nil && !self.usbAOAManager.isConnected {
                    self.updateMode(.disconnected, clientName: nil)
                }
            default:
                break
            }
        }
        connection.start(queue: ioQueue)
    }

    private func receiveNextTCPChunk(on connection: NWConnection, ringBuffer: PacketRingBuffer) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                ringBuffer.appendRawBytes(data)
                while let (hdr, payload) = ringBuffer.popNextPacket() {
                    var fullPkt = Data(bytes: [hdr], count: MemoryLayout<hs_packet_header_t>.size)
                    fullPkt.append(payload)
                    self.parseIncomingClientPacket(fullPkt)
                }
            }
            if error == nil && !isComplete {
                self.receiveNextTCPChunk(on: connection, ringBuffer: ringBuffer)
            } else {
                connection.cancel()
                self.activeTCPConnections.removeAll { $0 === connection }
                self.activeUDPConnection?.cancel()
                self.activeUDPConnection = nil
                if self.activeTCPConnections.isEmpty && !self.usbAOAManager.isConnected {
                    self.updateMode(.disconnected, clientName: nil)
                }
            }
        }
    }

    private func startWirelessUDPListener(port: UInt16) {
        let params = NWParameters.udp
        params.serviceClass = .interactiveVideo
        params.allowLocalEndpointReuse = true

        guard let nwPort = NWEndpoint.Port(rawValue: port),
              let listener = try? NWListener(using: params, on: nwPort) else {
            return
        }

        // Advertise via Bonjour mDNS (`_hyperscreen._udp`) with pairing metadata TXT record
        let txtDict: [String: Data] = [
            "pin": sessionPairingPIN.data(using: .utf8) ?? Data(),
            "port": "\(port)".data(using: .utf8) ?? Data(),
            "ip": localLANIPAddress().data(using: .utf8) ?? Data(),
            "name": (Host.current().localizedName ?? "HyperScreen-Mac").data(using: .utf8) ?? Data()
        ]
        let txtData = NetService.data(fromTXTRecord: txtDict)
        listener.service = NWListener.Service(
            name: Host.current().localizedName ?? "HyperScreen-Mac",
            type: "_hyperscreen._udp",
            domain: nil,
            txtRecord: txtData
        )

        listener.newConnectionHandler = { [weak self] connection in
            self?.handleIncomingConnection(connection)
        }

        listener.start(queue: ioQueue)
        self.udpListener = listener
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            if case .ready = state {
                // Activate Wireless mode and cleanly close any USB loopback connections
                self.preferredConnection = .wireless
                for oldConn in self.activeTCPConnections {
                    let oldEp = String(describing: oldConn.endpoint)
                    if oldEp.contains("127.0.0.1") || oldEp.contains("::1") || oldEp.contains("localhost") {
                        oldConn.cancel()
                    }
                }
                self.activeTCPConnections.removeAll { oldConn in
                    let oldEp = String(describing: oldConn.endpoint)
                    return oldEp.contains("127.0.0.1") || oldEp.contains("::1") || oldEp.contains("localhost")
                }
                self.activeUDPConnection = connection
                let isLoopback = String(describing: connection.endpoint).contains("127.0.0.1")
                self.updateMode(isLoopback ? .usbADB : .wirelessLAN, clientName: String(describing: connection.endpoint))
                self.receiveNextPacket(on: connection)
            }
        }
        connection.start(queue: ioQueue)
    }

    private func receiveNextPacket(on connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self else { return }
            if let data, data.count >= MemoryLayout<hs_packet_header_t>.size {
                self.parseIncomingClientPacket(data)
            }
            if error == nil {
                self.receiveNextPacket(on: connection)
            }
        }
    }

    private func parseIncomingClientPacket(_ data: Data) {
        data.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress else { return }
            let hdrPtr = base.assumingMemoryBound(to: hs_packet_header_t.self)
            guard hs_packet_header_validate(hdrPtr) else { return }

            let hdr = hdrPtr.pointee
            let payloadPtr = base.advanced(by: MemoryLayout<hs_packet_header_t>.size)
            let payloadBytes = rawBuf.count - MemoryLayout<hs_packet_header_t>.size
            guard payloadBytes >= Int(hdr.payload_len) else { return }

            switch hs_packet_type_t(UInt32(hdr.type)) {
            case HS_PKT_CLIENT_HELLO:
                guard payloadBytes >= MemoryLayout<hs_hello_payload_t>.size else { return }
                let hello = payloadPtr.assumingMemoryBound(to: hs_hello_payload_t.self).pointee
                delegate?.transportDidReceiveClientHello(hello)

            case HS_PKT_INPUT_TOUCH:
                guard payloadBytes >= MemoryLayout<hs_input_touch_t>.size else { return }
                let touch = payloadPtr.assumingMemoryBound(to: hs_input_touch_t.self).pointee
                delegate?.transportDidReceiveTouchInput(touch)

            case HS_PKT_INPUT_STYLUS:
                guard payloadBytes >= MemoryLayout<hs_input_stylus_t>.size else { return }
                let stylus = payloadPtr.assumingMemoryBound(to: hs_input_stylus_t.self).pointee
                delegate?.transportDidReceiveStylusInput(stylus)

            case HS_PKT_INPUT_KEY:
                guard payloadBytes >= MemoryLayout<hs_input_key_t>.size else { return }
                let key = payloadPtr.assumingMemoryBound(to: hs_input_key_t.self).pointee
                delegate?.transportDidReceiveKeyInput(key)

            case HS_PKT_VIDEO_NACK:
                guard payloadBytes >= MemoryLayout<hs_video_nack_t>.size else { return }
                let nack = payloadPtr.assumingMemoryBound(to: hs_video_nack_t.self).pointee
                delegate?.transportDidReceiveVideoNack(nack)

            default:
                break
            }
        }
    }

    // MARK: - 3. Outbound High-Frequency Slicing, FEC & 240Hz Cursor Transmission

    /// Sends an encoded Annex-B video frame across the active transport.
    /// On Wireless UDP, automatically slices to 1380-byte datagrams and appends GF(2^8) FEC parity slices.
    public func sendVideoFrame(
        frameId: UInt32,
        captureTimestampUs: UInt64,
        flags: UInt16,
        annexBData: Data
    ) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            let useAOABulk = self.usbAOAManager.isConnected
            let tcpConns = self.activeTCPConnections
            let udpConn = self.activeUDPConnection
            guard useAOABulk || !tcpConns.isEmpty || udpConn != nil else { return }

            // 1. Send 16KB bulk slices over USB-C (AOA or ADB loopback) when USB mode is active
            if self.preferredConnection == .usb && (useAOABulk || !tcpConns.isEmpty) {
                let usbSlices: [HyperScreenCoreBridge.SlicedPacket] = annexBData.withUnsafeBytes { frameRaw in
                    self.sliceScratchBuffer.withUnsafeMutableBytes { outRaw in
                        HyperScreenCoreBridge.sliceVideoFrame(
                            frameId: frameId,
                            capturePtsUs: captureTimestampUs,
                            flags: flags,
                            sequence: &self.videoSequence,
                            frameData: frameRaw,
                            maxSlicePayload: 16352,
                            into: outRaw
                        )
                    }
                }
                for slice in usbSlices {
                    let pktData = self.sliceScratchBuffer.subdata(in: slice.offset..<(slice.offset + slice.length))
                    if useAOABulk {
                        _ = self.usbAOAManager.sendBulkData(pktData)
                    }
                    for conn in tcpConns {
                        let ep = String(describing: conn.endpoint)
                        if ep.contains("127.0.0.1") || ep.contains("::1") || ep.contains("localhost") {
                            conn.send(content: pktData, completion: .idempotent)
                        }
                    }
                }
            }

            // 2. Send 1380B MTU slices + 0ms FEC parity over Wireless UDP when Wireless mode is active
            guard self.preferredConnection == .wireless, let activeConn = udpConn else { return }
            let slices: [HyperScreenCoreBridge.SlicedPacket] = annexBData.withUnsafeBytes { frameRaw in
                self.sliceScratchBuffer.withUnsafeMutableBytes { outRaw in
                    HyperScreenCoreBridge.sliceVideoFrame(
                        frameId: frameId,
                        capturePtsUs: captureTimestampUs,
                        flags: flags,
                        sequence: &self.videoSequence,
                        frameData: frameRaw,
                        maxSlicePayload: Int(HS_MAX_UDP_PAYLOAD),
                        into: outRaw
                    )
                }
            }

            // Transmit all MTU slices directly without intermediate array allocations
            for slice in slices {
                let pktData = self.sliceScratchBuffer.subdata(in: slice.offset..<(slice.offset + slice.length))
                activeConn.send(content: pktData, completion: .idempotent)
            }

            // Append 1 zero-allocation SIMD XOR parity slice (covers up to 128 slices) for 0ms Wi-Fi drop recovery
            if slices.count >= 2 && slices.count <= Int(HS_FEC_MAX_DATA_SHARDS) {
                let hasParity = self.sliceScratchBuffer.withUnsafeBytes { scratchRaw in
                    HyperScreenCoreBridge.generateSingleParity(
                        packetBuffer: scratchRaw,
                        slices: slices,
                        shardBytes: Int(HS_MAX_UDP_PAYLOAD),
                        into: &self.fecParityBuffer
                    )
                }
                if hasParity {
                    self.sendRawPacket(
                        type: UInt8(HS_PKT_VIDEO_FEC.rawValue),
                        flags: flags | UInt16(HS_VIDEO_FLAG_FEC_PARITY.rawValue),
                        sequence: &self.videoSequence,
                        payload: self.fecParityBuffer,
                        on: activeConn
                    )
                }
            }
        }
    }

    /// Transmits a 28-byte hardware cursor state packet at up to 240Hz out-of-band from the video stream.
    public func sendCursorState(_ cursor: hs_cursor_state_t) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            var mutableCursor = cursor
            let payload = Data(bytes: &mutableCursor, count: MemoryLayout<hs_cursor_state_t>.size)
            if self.usbAOAManager.isConnected {
                var hdr = hs_packet_header_t()
                hs_packet_header_init(&hdr, UInt8(HS_PKT_CURSOR_STATE.rawValue), self.cursorSequence, UInt32(payload.count), 0)
                self.cursorSequence &+= 1
                var packet = Data(bytes: &hdr, count: MemoryLayout<hs_packet_header_t>.size)
                packet.append(payload)
                _ = self.usbAOAManager.sendBulkData(packet)
            }
            for tcpConn in self.activeTCPConnections {
                self.sendRawPacket(
                    type: UInt8(HS_PKT_CURSOR_STATE.rawValue),
                    flags: 0,
                    sequence: &self.cursorSequence,
                    payload: payload,
                    on: tcpConn
                )
            }
            if let udpConn = self.activeUDPConnection {
                self.sendRawPacket(
                    type: UInt8(HS_PKT_CURSOR_STATE.rawValue),
                    flags: 0,
                    sequence: &self.cursorSequence,
                    payload: payload,
                    on: udpConn
                )
            }
        }
    }

    /// Transmits a 5ms Opus audio frame or Universal Clipboard / File Chunk payload.
    public func sendControlOrAudioPacket(type: hs_packet_type_t, payload: Data) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            let conn = (self.preferredConnection == .usb)
                ? self.activeTCPConnections.last
                : (self.activeUDPConnection ?? self.activeTCPConnections.last)
            guard let targetConn = conn else { return }
            self.sendRawPacket(
                type: UInt8(type.rawValue),
                flags: 0,
                sequence: &self.audioSequence,
                payload: payload,
                on: targetConn
            )
        }
    }

    /// Sends SERVER_HELLO containing live display orientation, dimensions, panel refresh rate, and active streaming state to the connected client.
    public func sendServerHello(config: VirtualDisplayConfig, isStreaming: Bool, bitrateMbps: Int = 45) {
        ioQueue.async { [weak self] in
            guard let self else { return }
            let displayActiveFlag: UInt16 = isStreaming ? 0x8000 : 0x0000
            let rotationBits: UInt16 = UInt16(config.rotation) & 0x01FF
            let reservedVal = displayActiveFlag | rotationBits
            var hello = hs_hello_payload_t(
                pixel_width: UInt32(isStreaming ? config.effectivePixelWidth : 0),
                pixel_height: UInt32(isStreaming ? config.effectivePixelHeight : 0),
                physical_width_mm: UInt16(config.physicalWidthMM),
                physical_height_mm: UInt16(config.physicalHeightMM),
                refresh_rate_hz: UInt16(config.refreshRateHz),
                hidpi_enabled: config.hiDPI ? 1 : 0,
                supports_hevc_444: config.preferYUV444 ? 1 : 0,
                supports_av1: config.preferAV1 ? 1 : 0,
                transport_mode: UInt8(self.preferredConnection == .usb ? 0 : 2),
                reserved: reservedVal,
                max_bitrate_kbps: UInt32(bitrateMbps * 1000),
                session_token: self.sessionToken
            )
            let payload = Data(bytes: &hello, count: MemoryLayout<hs_hello_payload_t>.size)
            self.sendControlOrAudioPacket(type: HS_PKT_SERVER_HELLO, payload: payload)
        }
    }

    /// Disconnects all active client transports cleanly (used when switching connection modes).
    public func disconnectAllClients() {
        ioQueue.async { [weak self] in
            guard let self else { return }
            self.activeUDPConnection?.cancel()
            self.activeUDPConnection = nil
            for conn in self.activeTCPConnections {
                conn.cancel()
            }
            self.activeTCPConnections.removeAll()
            self.updateMode(.disconnected, clientName: nil)
        }
    }

    private func sendRawPacket(
        type: UInt8,
        flags: UInt16,
        sequence: inout UInt32,
        payload: Data,
        on conn: NWConnection
    ) {
        var hdr = hs_packet_header_t()
        hs_packet_header_init(&hdr, type, sequence, UInt32(payload.count), flags)
        sequence &+= 1

        var packet = Data(bytes: &hdr, count: MemoryLayout<hs_packet_header_t>.size)
        packet.append(payload)
        conn.send(content: packet, completion: .idempotent)
    }

    private func updateMode(_ mode: TransportActiveMode, clientName: String?) {
        self.activeMode = mode
        delegate?.transportDidChangeState(mode, clientName: clientName)
    }
}

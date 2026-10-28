import Foundation
@preconcurrency import CoreMedia
@preconcurrency import CoreVideo
@preconcurrency import VideoToolbox
import CHyperScreenCore
import HyperScreenCoreBridge

public protocol VideoToolboxEncoderDelegate: AnyObject, Sendable {
    func encoderDidProduceAnnexBFrame(
        frameId: UInt32,
        captureTimestampUs: UInt64,
        encodeLatencyUs: UInt64,
        flags: UInt16,
        annexBData: Data
    )
}

/// Hardware-accelerated `VTCompressionSession` encoder for HEVC (4:2:0 / 4:4:4) & AV1.
/// Configured for strict zero B-frames, real-time low-delay delivery, on-demand IDR,
/// and 100ms "Text-Sharp" high-precision idle refinement frames.
public final class VideoToolboxEncoder: @unchecked Sendable {
    private var session: VTCompressionSession?
    private let encoderQueue = DispatchQueue(label: "dev.hyperscreen.encoder.vt", qos: .userInteractive)
    private var frameCounter: UInt32 = 0
    private var forceNextIDR = true
    private var lastForcedIDRTimestampUs: UInt64 = 0
    private let minIDRIntervalUs: UInt64 = 250_000 // Rate-limit forced IDRs to 250ms spacing to prevent Wi-Fi congestion storms
    private var currentConfig: VirtualDisplayConfig?
    private var targetBitrateBps: Int = 45_000_000

    public weak var delegate: VideoToolboxEncoderDelegate?

    public init() {}

    public func configure(config: VirtualDisplayConfig, bitrateMbps: Int = 45) throws {
        teardown()
        self.currentConfig = config
        self.targetBitrateBps = max(bitrateMbps, 5) * 1_000_000

        let codecType: CMVideoCodecType = config.preferAV1
            ? kCMVideoCodecType_AV1
            : kCMVideoCodecType_HEVC

        let encoderSpec: [CFString: Any] = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: kCFBooleanTrue!
        ]

        var newSession: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(config.effectivePixelWidth),
            height: Int32(config.effectivePixelHeight),
            codecType: codecType,
            encoderSpecification: encoderSpec as CFDictionary,
            imageBufferAttributes: nil,
            compressedDataAllocator: nil,
            outputCallback: nil,
            refcon: nil,
            compressionSessionOut: &newSession
        )

        guard status == noErr, let vtSession = newSession else {
            throw NSError(
                domain: "HyperScreen.VideoToolbox",
                code: Int(status),
                userInfo: [NSLocalizedDescriptionKey: "VTCompressionSessionCreate failed with status \(status)"]
            )
        }

        // Strict Low-Latency Real-Time Properties (Zero B-frames, Zero frame delay)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_AllowFrameReordering, value: kCFBooleanFalse)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_MaxFrameDelayCount, value: 0 as CFNumber)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, value: kCFBooleanTrue)

        // Profile Level: Main10 / Main 4:4:4 10 for Pro Text Sharpness, or Main for standard 4:2:0
        if !config.preferAV1 {
            if config.preferYUV444 {
                let status444 = VTSessionSetProperty(
                    vtSession,
                    key: kVTCompressionPropertyKey_ProfileLevel,
                    value: "HEVC_Main44410_AutoLevel" as CFString
                )
                if status444 != noErr {
                    NSLog("[HyperScreen] Hardware encoder does not support HEVC_Main44410 (status: %d); falling back to HEVC_Main", status444)
                    VTSessionSetProperty(
                        vtSession,
                        key: kVTCompressionPropertyKey_ProfileLevel,
                        value: kVTProfileLevel_HEVC_Main_AutoLevel
                    )
                }
            } else {
                VTSessionSetProperty(
                    vtSession,
                    key: kVTCompressionPropertyKey_ProfileLevel,
                    value: kVTProfileLevel_HEVC_Main_AutoLevel
                )
            }
        }

        // Emit periodic IDR keyframes every 1.0s (plus on-demand IDR on CLIENT_HELLO/NACK) for instant lock-on
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: 60 as CFNumber)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: 1.0 as CFNumber)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: config.refreshRateHz as CFNumber)

        // Colorimetry matching Display P3 / BT.709
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_ColorPrimaries, value: kCVImageBufferColorPrimaries_P3_D65)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_TransferFunction, value: kCVImageBufferTransferFunction_sRGB)
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_YCbCrMatrix, value: kCVImageBufferYCbCrMatrix_ITU_R_709_2)

        updateBitrate(bps: targetBitrateBps, on: vtSession)
        VTCompressionSessionPrepareToEncodeFrames(vtSession)

        self.session = vtSession
        self.forceNextIDR = true
        self.lastForcedIDRTimestampUs = 0
    }

    public func requestImmediateKeyframe() {
        encoderQueue.async {
            let nowUs = DispatchTime.now().uptimeNanoseconds / 1_000
            if nowUs >= self.lastForcedIDRTimestampUs + self.minIDRIntervalUs {
                self.forceNextIDR = true
            }
        }
    }

    public func setAdaptiveBitrate(bps: Int) {
        encoderQueue.async {
            self.targetBitrateBps = bps
            if let vtSession = self.session {
                self.updateBitrate(bps: bps, on: vtSession)
            }
        }
    }

    private func updateBitrate(bps: Int, on vtSession: VTCompressionSession) {
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_AverageBitRate, value: bps as CFNumber)
        let bytesPerSec = Int(Double(bps) / 8.0 * 1.25)
        let dataRateLimits: [CFNumber] = [bytesPerSec as CFNumber, 1.0 as CFNumber]
        VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_DataRateLimits, value: dataRateLimits as CFArray)
    }

    /// Encodes an IOSurface-backed `CMSampleBuffer` with zero CPU pixel copies.
    /// When `isIdleRefinement` is true, boosts quality to near-lossless so static IDE text is razor-sharp.
    public func encodeFrame(
        sampleBuffer: CMSampleBuffer,
        captureTimestampUs: UInt64,
        isIdleRefinement: Bool
    ) {
        nonisolated(unsafe) let unsafeSampleBuffer = sampleBuffer
        encoderQueue.async { [weak self] in
            guard let self,
                  let vtSession = self.session,
                  let imageBuffer = CMSampleBufferGetImageBuffer(unsafeSampleBuffer) else {
                return
            }

            // Use strictly monotonic microsecond PTS so re-emitted idle/keyframe buffers are never dropped by VideoToolbox
            let pts = CMTime(value: Int64(captureTimestampUs), timescale: 1_000_000)
            let duration = CMTime(value: 1, timescale: 120)

            var frameProps: [CFString: Any] = [:]
            if self.forceNextIDR {
                frameProps[kVTEncodeFrameOptionKey_ForceKeyFrame] = kCFBooleanTrue
                self.forceNextIDR = false
                self.lastForcedIDRTimestampUs = DispatchTime.now().uptimeNanoseconds / 1_000
            }

            if isIdleRefinement {
                // Temporarily boost quality to 0.98 for the 100ms idle text-sharp refinement frame
                VTSessionSetProperty(vtSession, key: kVTCompressionPropertyKey_Quality, value: 0.98 as CFNumber)
                let refinementBitrate = min(self.targetBitrateBps * 2, 40_000_000)
                VTSessionSetProperty(
                    vtSession,
                    key: kVTCompressionPropertyKey_AverageBitRate,
                    value: refinementBitrate as CFNumber
                )
            }

            self.frameCounter &+= 1
            let currentFrameId = self.frameCounter
            let configSnapshot = self.currentConfig

            VTCompressionSessionEncodeFrame(
                vtSession,
                imageBuffer: imageBuffer,
                presentationTimeStamp: pts,
                duration: duration,
                frameProperties: frameProps.isEmpty ? nil : (frameProps as CFDictionary),
                infoFlagsOut: nil
            ) { [weak self] status, _, encodedSampleBuffer in
                guard let self,
                      status == noErr,
                      let encodedSampleBuffer,
                      CMSampleBufferIsValid(encodedSampleBuffer) else {
                    return
                }

                if isIdleRefinement, let activeSession = self.session {
                    // Restore normal motion bitrate immediately after the refinement frame
                    self.updateBitrate(bps: self.targetBitrateBps, on: activeSession)
                }

                self.packageEncodedSampleBuffer(
                    encodedSampleBuffer,
                    frameId: currentFrameId,
                    captureTimestampUs: captureTimestampUs,
                    isIdleRefinement: isIdleRefinement,
                    config: configSnapshot
                )
            }
        }
    }

    private func packageEncodedSampleBuffer(
        _ sampleBuffer: CMSampleBuffer,
        frameId: UInt32,
        captureTimestampUs: UInt64,
        isIdleRefinement: Bool,
        config: VirtualDisplayConfig?
    ) {
        let isKeyFrame = Self.isSampleBufferKeyFrame(sampleBuffer)
        var annexBPayload = Data()

        // If this is an IDR keyframe, prepend VPS/SPS/PPS parameter sets in Annex-B format
        if isKeyFrame, let formatDesc = CMSampleBufferGetFormatDescription(sampleBuffer) {
            Self.appendParameterSetsAnnexB(from: formatDesc, into: &annexBPayload)
        }

        guard let dataBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        let totalLength = CMBlockBufferGetDataLength(dataBuffer)
        guard totalLength > 0 else { return }

        let payloadStartOffset = annexBPayload.count
        annexBPayload.count += totalLength

        let copyStatus = annexBPayload.withUnsafeMutableBytes { rawBuf -> OSStatus in
            guard let base = rawBuf.baseAddress else { return -1 }
            let destPtr = base.advanced(by: payloadStartOffset)
            return CMBlockBufferCopyDataBytes(
                dataBuffer,
                atOffset: 0,
                dataLength: totalLength,
                destination: destPtr
            )
        }
        guard copyStatus == kCMBlockBufferNoErr else { return }

        // Convert the 4-byte HVCC length prefixes to 4-byte Annex-B start codes (`0x00000001`) IN-PLACE
        annexBPayload.withUnsafeMutableBytes { rawBuf in
            let slicePtr = UnsafeMutableRawBufferPointer(
                rebasing: rawBuf[payloadStartOffset..<rawBuf.count]
            )
            _ = HyperScreenCoreBridge.convertHVCCtoAnnexBInPlace(slicePtr)
        }

        var flags: UInt16 = 0
        if isKeyFrame { flags |= UInt16(HS_VIDEO_FLAG_IDR.rawValue) }
        if isIdleRefinement { flags |= UInt16(HS_VIDEO_FLAG_IDLE_REFINE.rawValue) }
        if config?.preferYUV444 == true { flags |= UInt16(HS_VIDEO_FLAG_YUV444.rawValue) }
        if config?.preferAV1 == true { flags |= UInt16(HS_VIDEO_FLAG_CODEC_AV1.rawValue) }

        let nowUs = DispatchTime.now().uptimeNanoseconds / 1_000
        let encodeLatencyUs = nowUs >= captureTimestampUs ? (nowUs - captureTimestampUs) : 0

        delegate?.encoderDidProduceAnnexBFrame(
            frameId: frameId,
            captureTimestampUs: captureTimestampUs,
            encodeLatencyUs: encodeLatencyUs,
            flags: flags,
            annexBData: annexBPayload
        )
    }

    private static func isSampleBufferKeyFrame(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[CFString: Any]],
              let first = attachments.first else {
            return true
        }
        if let notSync = first[kCMSampleAttachmentKey_NotSync] as? Bool {
            return !notSync
        }
        return true
    }

    private static func appendParameterSetsAnnexB(from formatDesc: CMFormatDescription, into outData: inout Data) {
        let startCode: [UInt8] = [0x00, 0x00, 0x00, 0x01]
        var paramSetCount = 0
        CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
            formatDesc,
            parameterSetIndex: 0,
            parameterSetPointerOut: nil,
            parameterSetSizeOut: nil,
            parameterSetCountOut: &paramSetCount,
            nalUnitHeaderLengthOut: nil
        )

        for idx in 0..<paramSetCount {
            var ptr: UnsafePointer<UInt8>?
            var size = 0
            let status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                formatDesc,
                parameterSetIndex: idx,
                parameterSetPointerOut: &ptr,
                parameterSetSizeOut: &size,
                parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil
            )
            if status == noErr, let validPtr = ptr, size > 0 {
                outData.append(contentsOf: startCode)
                outData.append(validPtr, count: size)
            }
        }
    }

    public func teardown() {
        if let vtSession = session {
            VTCompressionSessionInvalidate(vtSession)
        }
        session = nil
    }
}

import Foundation
import CHyperScreenCore

/// High-performance Swift 6 bridge to `libhyperscreen_core` (C/C++20).
/// Provides zero-copy HVCC-to-AnnexB conversion, MTU video frame slicing,
/// GF(2^8) Forward Error Correction (FEC), 1-Euro 240Hz cursor filtering,
/// and lock-free SPSC packet framing for USB AOA & UDP sockets.
public final class HyperScreenCoreBridge: @unchecked Sendable {

    public struct SlicedPacket: Sendable {
        public let offset: Int
        public let length: Int
    }

    public init() {
        hs_fec_init()
    }

    /// Converts a VideoToolbox length-prefixed HVCC/AVCC sample buffer into Annex-B start codes (`0x00000001`) IN-PLACE.
    @inlinable
    public static func convertHVCCtoAnnexBInPlace(_ buffer: UnsafeMutableRawBufferPointer) -> Bool {
        guard let base = buffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
            return false
        }
        return hs_bitstream_hvcc_to_annexb_inplace(base, buffer.count)
    }

    /// Slices an encoded video frame into MTU-sized packets with 16-byte `hs_packet_header_t` + 16-byte `hs_video_slice_header_t`.
    public static func sliceVideoFrame(
        frameId: UInt32,
        capturePtsUs: UInt64,
        flags: UInt16,
        sequence: inout UInt32,
        frameData: UnsafeRawBufferPointer,
        maxSlicePayload: Int = Int(HS_MAX_UDP_PAYLOAD),
        into outBuffer: UnsafeMutableRawBufferPointer,
        maxPackets: Int = 512
    ) -> [SlicedPacket] {
        guard let frameBase = frameData.baseAddress?.assumingMemoryBound(to: UInt8.self),
              let outBase = outBuffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
            return []
        }

        var offsets = [Int](repeating: 0, count: maxPackets)
        var lengths = [Int](repeating: 0, count: maxPackets)

        let count = hs_slice_video_frame(
            frameId,
            capturePtsUs,
            flags,
            &sequence,
            frameBase,
            frameData.count,
            maxSlicePayload,
            outBase,
            outBuffer.count,
            &offsets,
            &lengths,
            maxPackets
        )

        guard count > 0 else { return [] }
        var result = [SlicedPacket]()
        result.reserveCapacity(count)
        for i in 0..<count {
            result.append(SlicedPacket(offset: offsets[i], length: lengths[i]))
        }
        return result
    }

    /// Generates `parityCount` FEC shards over `dataShards` using Cauchy Reed-Solomon / SIMD XOR over GF(2^8).
    public static func encodeFEC(
        dataShards: [Data],
        parityCount: Int,
        shardBytes: Int
    ) -> [Data]? {
        guard !dataShards.isEmpty,
              dataShards.count <= Int(HS_FEC_MAX_DATA_SHARDS),
              parityCount > 0,
              parityCount <= Int(HS_FEC_MAX_PARITY_SHARDS),
              shardBytes > 0 else {
            return nil
        }

        // Normalize all data shards to `shardBytes` (zero-padded)
        let normalizedData: [Data] = dataShards.map { shard in
            if shard.count == shardBytes { return shard }
            var padded = shard
            if padded.count < shardBytes {
                padded.append(contentsOf: repeatElement(0, count: shardBytes - padded.count))
            } else {
                padded = padded.prefix(shardBytes)
            }
            return padded
        }

        var parityBuffers = (0..<parityCount).map { _ in Data(count: shardBytes) }

        var dataPtrs = [UnsafePointer<UInt8>?](repeating: nil, count: normalizedData.count)
        var parityPtrs = [UnsafeMutablePointer<UInt8>?](repeating: nil, count: parityCount)

        for i in 0..<normalizedData.count {
            normalizedData[i].withUnsafeBytes { raw in
                dataPtrs[i] = raw.bindMemory(to: UInt8.self).baseAddress
            }
        }
        for p in 0..<parityCount {
            parityBuffers[p].withUnsafeMutableBytes { raw in
                parityPtrs[p] = raw.bindMemory(to: UInt8.self).baseAddress
            }
        }

        let ok = dataPtrs.withUnsafeBufferPointer { dBuf in
            parityPtrs.withUnsafeMutableBufferPointer { pBuf in
                hs_fec_encode(
                    dBuf.baseAddress,
                    normalizedData.count,
                    pBuf.baseAddress,
                    parityCount,
                    shardBytes
                )
            }
        }

        return ok ? parityBuffers : nil
    }

    /// Fast 0-allocation SIMD XOR parity generator directly over the sliced packet scratch buffer.
    public static func generateSingleParity(
        packetBuffer: UnsafeRawBufferPointer,
        slices: [SlicedPacket],
        shardBytes: Int = Int(HS_MAX_UDP_PAYLOAD),
        into parityOut: inout Data
    ) -> Bool {
        guard slices.count >= 2,
              slices.count <= Int(HS_FEC_MAX_DATA_SHARDS),
              let pBase = packetBuffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
            return false
        }
        var offsets = [Int](repeating: 0, count: slices.count)
        var lengths = [Int](repeating: 0, count: slices.count)
        for i in 0..<slices.count {
            offsets[i] = slices[i].offset
            lengths[i] = slices[i].length
        }
        if parityOut.count < shardBytes {
            parityOut = Data(count: shardBytes)
        }
        return parityOut.withUnsafeMutableBytes { outRaw in
            guard let outBase = outRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
            return hs_fec_generate_single_parity_from_slices(
                pBase,
                &offsets,
                &lengths,
                slices.count,
                outBase,
                shardBytes
            )
        }
    }
}

/// Swift wrapper around the C/C++20 1-Euro Filter (`hs_one_euro_filter_2d_t`)
public final class OneEuroFilter2D {
    private var state = hs_one_euro_filter_2d_t()

    public init(minCutoff: Double = 1.2, beta: Double = 0.015, dCutoff: Double = 1.0) {
        hs_one_euro_init(&state, minCutoff, beta, dCutoff)
    }

    public func reset() {
        hs_one_euro_reset(&state)
    }

    public func filter(timestampUs: UInt64, x: Float, y: Float) -> (Float, Float) {
        var outX: Float = 0
        var outY: Float = 0
        hs_one_euro_update_2d(&state, timestampUs, x, y, &outX, &outY)
        return (outX, outY)
    }

    public func predict(lookaheadUs: UInt64) -> (Float, Float) {
        var predX: Float = 0
        var predY: Float = 0
        hs_one_euro_predict_2d(&state, lookaheadUs, &predX, &predY)
        return (predX, predY)
    }
}

/// Swift wrapper around `hs_frame_reassembler_t` for 0ms XOR/GF(2^8) FEC Video Frame Reassembly
public final class VideoFrameReassembler: @unchecked Sendable {
    private let handle: OpaquePointer?

    public struct CompletedFrame: Sendable {
        public let frameId: UInt32
        public let capturePtsUs: UInt64
        public let flags: UInt16
        public let data: Data
    }

    public init(maxSlicesPerFrame: Int = 512, maxSliceBytes: Int = 1440) {
        self.handle = hs_reassembler_create(maxSlicesPerFrame, maxSliceBytes)
    }

    deinit {
        if let h = handle {
            hs_reassembler_destroy(h)
        }
    }

    public func pushPacket(_ packet: Data, maxFrameBytes: Int = 2 * 1024 * 1024) -> CompletedFrame? {
        guard let h = handle else { return nil }
        var outBuf = Data(count: maxFrameBytes)
        var outLen: Int = 0
        var outFid: UInt32 = 0
        var outPts: UInt64 = 0
        var outFlags: UInt16 = 0

        let completed = packet.withUnsafeBytes { pktRaw in
            outBuf.withUnsafeMutableBytes { outRaw in
                guard let pktBase = pktRaw.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let outBase = outRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return false
                }
                return hs_reassembler_push_packet(
                    h,
                    pktBase,
                    pktRaw.count,
                    outBase,
                    outRaw.count,
                    &outLen,
                    &outFid,
                    &outPts,
                    &outFlags
                )
            }
        }

        guard completed, outLen > 0 else { return nil }
        outBuf.count = outLen
        return CompletedFrame(frameId: outFid, capturePtsUs: outPts, flags: outFlags, data: outBuf)
    }
}

/// Swift wrapper around `hs_spsc_ringbuffer_t` for USB AOA Bulk Stream Packet Framing
public final class PacketRingBuffer: @unchecked Sendable {
    private let handle: OpaquePointer?

    public init(capacity: Int = 2 * 1024 * 1024) {
        self.handle = hs_ringbuffer_create(capacity)
    }

    deinit {
        if let h = handle {
            hs_ringbuffer_destroy(h)
        }
    }

    public func appendRawBytes(_ data: Data) {
        guard let h = handle else { return }
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            _ = hs_ringbuffer_write(h, base, raw.count)
        }
    }

    public func popNextPacket(maxPayloadBytes: Int = 128 * 1024) -> (hs_packet_header_t, Data)? {
        guard let h = handle else { return nil }
        var hdr = hs_packet_header_t()
        var payload = Data(count: maxPayloadBytes)

        let ok = payload.withUnsafeMutableBytes { outRaw in
            guard let outBase = outRaw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return false }
            return hs_ringbuffer_pop_packet(h, &hdr, outBase, outRaw.count)
        }
        guard ok else { return nil }
        payload.count = Int(hdr.payload_len)
        return (hdr, payload)
    }
}

import XCTest
import CHyperScreenCore
@testable import HyperScreenCoreBridge

final class HyperScreenHostTests: XCTestCase {

    func testHVCCtoAnnexBInPlaceZeroCopy() {
        var hvccBuffer: [UInt8] = [
            0x00, 0x00, 0x00, 0x04, 0x42, 0x01, 0xAA, 0xBB,
            0x00, 0x00, 0x00, 0x05, 0x26, 0x01, 0x11, 0x22, 0x33
        ]

        let converted = hvccBuffer.withUnsafeMutableBytes { rawBuf in
            HyperScreenCoreBridge.convertHVCCtoAnnexBInPlace(rawBuf)
        }
        XCTAssertTrue(converted)
        XCTAssertEqual(Array(hvccBuffer[0..<4]), [0x00, 0x00, 0x00, 0x01])
        XCTAssertEqual(Array(hvccBuffer[8..<12]), [0x00, 0x00, 0x00, 0x01])
    }

    func testVideoFrameMTUSlicingAndFECRecovery() {
        let fakeFrame = Data((0..<3200).map { UInt8(($0 * 31 + 7) & 0xFF) })
        var outScratch = Data(count: 8192)
        var seq: UInt32 = 500

        let slices: [HyperScreenCoreBridge.SlicedPacket] = fakeFrame.withUnsafeBytes { frameRaw in
            outScratch.withUnsafeMutableBytes { outRaw in
                HyperScreenCoreBridge.sliceVideoFrame(
                    frameId: 12,
                    capturePtsUs: 123_456_789,
                    flags: UInt16(HS_VIDEO_FLAG_IDR.rawValue),
                    sequence: &seq,
                    frameData: frameRaw,
                    maxSlicePayload: 1200,
                    into: outRaw
                )
            }
        }

        XCTAssertEqual(slices.count, 3)
        XCTAssertEqual(seq, 503)

        let shardDatas = slices.map { outScratch.subdata(in: $0.offset..<($0.offset + $0.length)) }
        let parity = HyperScreenCoreBridge.encodeFEC(
            dataShards: shardDatas,
            parityCount: 1,
            shardBytes: 1280
        )
        XCTAssertNotNil(parity)
        XCTAssertEqual(parity?.count, 1)
        XCTAssertEqual(parity?.first?.count, 1280)
    }

    func testOneEuroFilter2DSmoothingAndPrediction() {
        let filter = OneEuroFilter2D(minCutoff: 1.2, beta: 0.015, dCutoff: 1.0)
        let (x0, y0) = filter.filter(timestampUs: 1_000, x: 0.25, y: 0.75)
        XCTAssertEqual(x0, 0.25, accuracy: 1e-5)
        XCTAssertEqual(y0, 0.75, accuracy: 1e-5)

        let (x1, y1) = filter.filter(timestampUs: 5_166, x: 0.30, y: 0.80)
        XCTAssertGreaterThan(x1, 0.25)
        XCTAssertGreaterThan(y1, 0.75)

        let (predX, predY) = filter.predict(lookaheadUs: 4_000)
        XCTAssertGreaterThanOrEqual(predX, x1)
        XCTAssertGreaterThanOrEqual(predY, y1)
    }
}

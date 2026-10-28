#include "hs_protocol.h"
#include "hs_fec.h"
#include "hs_bitstream.h"
#include "hs_filter.h"
#include "hs_ringbuffer.h"

#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <vector>
#include <math.h>

static void test_protocol_header_and_slicing() {
    static_assert(sizeof(hs_packet_header_t) == 16, "hs_packet_header_t must be 16 bytes");
    static_assert(sizeof(hs_video_slice_header_t) == 16, "hs_video_slice_header_t must be 16 bytes");
    static_assert(sizeof(hs_telemetry_ping_t) == 16, "hs_telemetry_ping_t must be 16 bytes");

    hs_packet_header_t hdr{};
    hs_packet_header_init(&hdr, HS_PKT_VIDEO_SLICE, 42, 1024, HS_VIDEO_FLAG_IDR);
    assert(hs_packet_header_validate(&hdr));

    /* Corrupt 1 bit and ensure CRC-16 rejects it */
    hdr.payload_len ^= 0x01;
    assert(!hs_packet_header_validate(&hdr));

    /* Test Telemetry Ping/Pong packet build and validation */
    uint8_t telem_buf[32]{};
    size_t telem_len = hs_build_telemetry_packet(
        HS_PKT_PING_TELEMETRY,
        77,
        123456789ULL,
        999,
        2,
        3400,
        telem_buf,
        sizeof(telem_buf)
    );
    assert(telem_len == 32);
    const auto *t_hdr = reinterpret_cast<const hs_packet_header_t *>(telem_buf);
    assert(hs_packet_header_validate(t_hdr));
    assert(t_hdr->type == HS_PKT_PING_TELEMETRY);
    assert(t_hdr->sequence == 77);
    assert(t_hdr->payload_len == 16);
    const auto *t_payload = reinterpret_cast<const hs_telemetry_ping_t *>(telem_buf + 16);
    assert(t_payload->host_timestamp_us == 123456789ULL);
    assert(t_payload->last_decoded_frame_id == 999);
    assert(t_payload->packets_lost_recent == 2);
    assert(t_payload->decode_time_us == 3400);

    /* Test MTU video frame slicing */
    std::vector<uint8_t> fake_frame(3500);
    for (size_t i = 0; i < fake_frame.size(); ++i) {
        fake_frame[i] = static_cast<uint8_t>((i * 37 + 13) & 0xFF);
    }

    uint32_t seq = 100;
    std::vector<uint8_t> packet_buf(8192);
    size_t offsets[16]{};
    size_t lengths[16]{};

    const size_t slices = hs_slice_video_frame(
        7,
        987654321ULL,
        HS_VIDEO_FLAG_IDR | HS_VIDEO_FLAG_IDLE_REFINE,
        &seq,
        fake_frame.data(),
        fake_frame.size(),
        1200, /* max payload per packet */
        packet_buf.data(),
        packet_buf.size(),
        offsets,
        lengths,
        16
    );
    assert(slices == 3);
    assert(seq == 103);

    /* Reassemble slices and verify byte-for-byte identity */
    std::vector<uint8_t> reassembled;
    for (size_t s = 0; s < slices; ++s) {
        const auto *pkt_hdr = reinterpret_cast<const hs_packet_header_t *>(packet_buf.data() + offsets[s]);
        assert(hs_packet_header_validate(pkt_hdr));
        const auto *sl_hdr = reinterpret_cast<const hs_video_slice_header_t *>(
            packet_buf.data() + offsets[s] + sizeof(hs_packet_header_t)
        );
        assert(sl_hdr->frame_id == 7);
        assert(sl_hdr->slice_index == s);
        assert(sl_hdr->total_slices == slices);
        assert(sl_hdr->capture_pts_us == 987654321ULL);

        const uint8_t *chunk = packet_buf.data() + offsets[s] + sizeof(hs_packet_header_t) + sizeof(hs_video_slice_header_t);
        const size_t chunk_len = pkt_hdr->payload_len - sizeof(hs_video_slice_header_t);
        reassembled.insert(reassembled.end(), chunk, chunk + chunk_len);
    }
    assert(reassembled == fake_frame);

    /* Test hs_frame_reassembler with simulated Wi-Fi packet loss of Slice #1 healed in 0ms by FEC! */
    constexpr size_t kMaxSliceBytes = 1400;
    std::vector<std::vector<uint8_t>> padded_slices(slices, std::vector<uint8_t>(kMaxSliceBytes, 0));
    const uint8_t *data_ptrs[3];
    for (size_t s = 0; s < slices; ++s) {
        memcpy(padded_slices[s].data(), packet_buf.data() + offsets[s], lengths[s]);
        data_ptrs[s] = padded_slices[s].data();
    }
    std::vector<uint8_t> parity_shard(kMaxSliceBytes, 0);
    uint8_t *parity_ptrs[1] = {parity_shard.data()};
    assert(hs_fec_encode(data_ptrs, slices, parity_ptrs, 1, kMaxSliceBytes));

    std::vector<uint8_t> fec_pkt(sizeof(hs_packet_header_t) + kMaxSliceBytes);
    hs_packet_header_init(
        reinterpret_cast<hs_packet_header_t *>(fec_pkt.data()),
        HS_PKT_VIDEO_FEC,
        seq++,
        static_cast<uint32_t>(kMaxSliceBytes),
        HS_VIDEO_FLAG_FEC_PARITY
    );
    memcpy(fec_pkt.data() + sizeof(hs_packet_header_t), parity_shard.data(), kMaxSliceBytes);

    hs_frame_reassembler_t *reasm = hs_reassembler_create(32, kMaxSliceBytes);
    assert(reasm != nullptr);

    std::vector<uint8_t> out_frame(8192);
    size_t out_len = 0;
    uint32_t out_fid = 0;
    uint64_t out_pts = 0;
    uint16_t out_flags = 0;

    /* Feed Slice #0 (drop Slice #1!), feed Slice #2, then feed FEC parity packet */
    assert(!hs_reassembler_push_packet(
        reasm, packet_buf.data() + offsets[0], lengths[0],
        out_frame.data(), out_frame.size(), &out_len, &out_fid, &out_pts, &out_flags
    ));
    assert(!hs_reassembler_push_packet(
        reasm, packet_buf.data() + offsets[2], lengths[2],
        out_frame.data(), out_frame.size(), &out_len, &out_fid, &out_pts, &out_flags
    ));
    /* Feeding FEC parity packet should immediately reconstruct missing Slice #1 and output the full frame! */
    assert(hs_reassembler_push_packet(
        reasm, fec_pkt.data(), fec_pkt.size(),
        out_frame.data(), out_frame.size(), &out_len, &out_fid, &out_pts, &out_flags
    ));
    assert(out_len == fake_frame.size());
    assert(out_fid == 7);
    assert(memcmp(out_frame.data(), fake_frame.data(), fake_frame.size()) == 0);

    hs_reassembler_destroy(reasm);
    printf("[PASS] test_protocol_header_and_slicing (+ 0ms FEC Frame Reassembler)\n");
}

static void test_fec_single_and_multi_loss_recovery() {
    constexpr size_t kDataShards = 6;
    constexpr size_t kParityShards = 2;
    constexpr size_t kTotalShards = kDataShards + kParityShards;
    constexpr size_t kShardBytes = 1024;

    std::vector<std::vector<uint8_t>> originals(kDataShards, std::vector<uint8_t>(kShardBytes));
    std::vector<std::vector<uint8_t>> working(kTotalShards, std::vector<uint8_t>(kShardBytes));

    const uint8_t *data_ptrs[kDataShards];
    uint8_t *parity_ptrs[kParityShards];
    uint8_t *all_ptrs[kTotalShards];

    for (size_t i = 0; i < kDataShards; ++i) {
        for (size_t b = 0; b < kShardBytes; ++b) {
            originals[i][b] = static_cast<uint8_t>((i * 53 + b * 19 + 7) & 0xFF);
        }
        working[i] = originals[i];
        data_ptrs[i] = working[i].data();
        all_ptrs[i] = working[i].data();
    }
    for (size_t p = 0; p < kParityShards; ++p) {
        parity_ptrs[p] = working[kDataShards + p].data();
        all_ptrs[kDataShards + p] = working[kDataShards + p].data();
    }

    assert(hs_fec_encode(data_ptrs, kDataShards, parity_ptrs, kParityShards, kShardBytes));

    /* Case 1: Drop 1 shard (index 2) and recover via fast XOR parity 0 */
    memset(working[2].data(), 0, kShardBytes);
    assert(hs_fec_reconstruct_single_xor(all_ptrs, kDataShards, parity_ptrs[0], 2, kShardBytes));
    assert(working[2] == originals[2]);

    /* Case 2: Drop 2 data shards simultaneously (indices 1 and 4) and recover via GF(2^8) Cauchy matrix */
    memset(working[1].data(), 0, kShardBytes);
    memset(working[4].data(), 0, kShardBytes);
    bool present[kTotalShards] = {true, false, true, true, false, true, true, true};

    assert(hs_fec_reconstruct_gf256(all_ptrs, present, kDataShards, kParityShards, kShardBytes));
    assert(working[1] == originals[1]);
    assert(working[4] == originals[4]);
    printf("[PASS] test_fec_single_and_multi_loss_recovery\n");
}

static void test_bitstream_hvcc_and_annexb() {
    /* Construct HVCC buffer with 2 NALUs: [len=4][HEVC SPS type 33][len=5][HEVC IDR type 19] */
    uint8_t hvcc[] = {
        0x00, 0x00, 0x00, 0x04,  0x42, 0x01, 0xAA, 0xBB,
        0x00, 0x00, 0x00, 0x05,  0x26, 0x01, 0x11, 0x22, 0x33
    };
    assert(hs_bitstream_hvcc_to_annexb_inplace(hvcc, sizeof(hvcc)));

    hs_nalu_view_t nalus[4]{};
    const size_t count = hs_bitstream_scan_annexb(hvcc, sizeof(hvcc), HS_CODEC_HEVC, nalus, 4);
    assert(count == 2);
    assert(nalus[0].nalu_type == 33);
    assert(nalus[0].is_parameter_set);
    assert(!nalus[0].is_idr);
    assert(nalus[1].nalu_type == 19);
    assert(nalus[1].is_idr);
    printf("[PASS] test_bitstream_hvcc_and_annexb\n");
}

static void test_one_euro_filter_and_ringbuffer_resync() {
    hs_one_euro_filter_2d_t filter{};
    hs_one_euro_init(&filter, 1.2, 0.015, 1.0);

    float out_x = 0.0f, out_y = 0.0f;
    hs_one_euro_update_2d(&filter, 1000, 0.5f, 0.5f, &out_x, &out_y);
    assert(fabsf(out_x - 0.5f) < 1e-5f);
    hs_one_euro_update_2d(&filter, 5166, 0.52f, 0.48f, &out_x, &out_y);
    assert(out_x > 0.5f && out_x <= 0.52f);

    /* Test SPSC RingBuffer with simulated leading USB bulk noise before valid packet */
    hs_spsc_ringbuffer_t *rb = hs_ringbuffer_create(4096);
    assert(rb != nullptr);

    const uint8_t garbage[] = {0xDE, 0xAD, 0xBE, 0xEF, 0x00};
    hs_ringbuffer_write(rb, garbage, sizeof(garbage));

    hs_cursor_state_t cur_in{};
    cur_in.timestamp_us = 123456ULL;
    cur_in.norm_x = 0.25f;
    cur_in.norm_y = 0.75f;
    cur_in.shape_hash = 0xCAFEBABEULL;
    cur_in.visible = 1;

    hs_packet_header_t pkt_hdr{};
    hs_packet_header_init(&pkt_hdr, HS_PKT_CURSOR_STATE, 9, sizeof(cur_in), 0);

    hs_ringbuffer_write(rb, reinterpret_cast<const uint8_t *>(&pkt_hdr), sizeof(pkt_hdr));
    hs_ringbuffer_write(rb, reinterpret_cast<const uint8_t *>(&cur_in), sizeof(cur_in));

    hs_packet_header_t out_hdr{};
    hs_cursor_state_t cur_out{};
    assert(hs_ringbuffer_pop_packet(rb, &out_hdr, reinterpret_cast<uint8_t *>(&cur_out), sizeof(cur_out)));
    assert(out_hdr.type == HS_PKT_CURSOR_STATE);
    assert(out_hdr.sequence == 9);
    assert(cur_out.shape_hash == 0xCAFEBABEULL);
    assert(fabsf(cur_out.norm_x - 0.25f) < 1e-6f);

    hs_ringbuffer_destroy(rb);
    printf("[PASS] test_one_euro_filter_and_ringbuffer_resync\n");
}

int main() {
    test_protocol_header_and_slicing();
    test_fec_single_and_multi_loss_recovery();
    test_bitstream_hvcc_and_annexb();
    test_one_euro_filter_and_ringbuffer_resync();
    printf("All HyperScreen C/C++20 Core tests passed!\n");
    return 0;
}

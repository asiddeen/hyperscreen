#include "hs_protocol.h"
#include "hs_fec.h"
#include <string.h>
#include <vector>

uint16_t hs_crc16_ccitt(const void *data, size_t len) {
    const uint8_t *bytes = static_cast<const uint8_t *>(data);
    uint16_t crc = 0xFFFFU;
    for (size_t i = 0; i < len; ++i) {
        crc ^= static_cast<uint16_t>(bytes[i]) << 8;
        for (int b = 0; b < 8; ++b) {
            if (crc & 0x8000U) {
                crc = static_cast<uint16_t>((crc << 1) ^ 0x1021U);
            } else {
                crc = static_cast<uint16_t>(crc << 1);
            }
        }
    }
    return crc;
}

void hs_packet_header_init(
    hs_packet_header_t *hdr,
    uint8_t type,
    uint32_t sequence,
    uint32_t payload_len,
    uint16_t flags
) {
    if (!hdr) return;
    hdr->magic         = HS_PROTOCOL_MAGIC;
    hdr->version       = HS_PROTOCOL_VERSION;
    hdr->type          = type;
    hdr->sequence      = sequence;
    hdr->payload_len   = payload_len;
    hdr->flags         = flags;
    hdr->header_crc16  = 0;
    hdr->header_crc16  = hs_crc16_ccitt(hdr, offsetof(hs_packet_header_t, header_crc16));
}

bool hs_packet_header_validate(const hs_packet_header_t *hdr) {
    if (!hdr) return false;
    if (hdr->magic != HS_PROTOCOL_MAGIC || hdr->version != HS_PROTOCOL_VERSION) {
        return false;
    }
    const uint16_t expected = hs_crc16_ccitt(hdr, offsetof(hs_packet_header_t, header_crc16));
    return hdr->header_crc16 == expected;
}

size_t hs_slice_video_frame(
    uint32_t frame_id,
    uint64_t capture_pts_us,
    uint16_t video_flags,
    uint32_t *io_sequence,
    const uint8_t *frame_data,
    size_t frame_len,
    size_t max_slice_payload,
    uint8_t *out_packet_buffer,
    size_t out_buffer_capacity,
    size_t *out_packet_offsets,
    size_t *out_packet_lengths,
    size_t max_packets
) {
    if (!io_sequence || !frame_data || frame_len == 0 || !out_packet_buffer ||
        !out_packet_offsets || !out_packet_lengths) {
        return 0;
    }

    const size_t overhead = sizeof(hs_packet_header_t) + sizeof(hs_video_slice_header_t);
    if (max_slice_payload <= sizeof(hs_video_slice_header_t)) {
        return 0;
    }
    const size_t max_chunk_data = max_slice_payload - sizeof(hs_video_slice_header_t);
    const size_t total_slices = (frame_len + max_chunk_data - 1) / max_chunk_data;
    if (total_slices == 0 || total_slices > max_packets || total_slices > 0xFFFFU) {
        return 0;
    }

    size_t write_cursor = 0;
    size_t read_cursor = 0;

    for (size_t idx = 0; idx < total_slices; ++idx) {
        const size_t remaining = frame_len - read_cursor;
        const size_t chunk_len = remaining < max_chunk_data ? remaining : max_chunk_data;
        const size_t packet_len = overhead + chunk_len;

        if (write_cursor + packet_len > out_buffer_capacity) {
            return 0;
        }

        uint8_t *pkt_ptr = out_packet_buffer + write_cursor;
        hs_packet_header_t *pkt_hdr = reinterpret_cast<hs_packet_header_t *>(pkt_ptr);
        hs_video_slice_header_t *slice_hdr = reinterpret_cast<hs_video_slice_header_t *>(
            pkt_ptr + sizeof(hs_packet_header_t)
        );
        uint8_t *payload_ptr = pkt_ptr + overhead;

        slice_hdr->frame_id       = frame_id;
        slice_hdr->slice_index    = static_cast<uint16_t>(idx);
        slice_hdr->total_slices   = static_cast<uint16_t>(total_slices);
        slice_hdr->capture_pts_us = capture_pts_us;

        memcpy(payload_ptr, frame_data + read_cursor, chunk_len);

        hs_packet_header_init(
            pkt_hdr,
            HS_PKT_VIDEO_SLICE,
            (*io_sequence)++,
            static_cast<uint32_t>(sizeof(hs_video_slice_header_t) + chunk_len),
            video_flags
        );

        out_packet_offsets[idx] = write_cursor;
        out_packet_lengths[idx] = packet_len;

        write_cursor += packet_len;
        read_cursor  += chunk_len;
    }

    return total_slices;
}

size_t hs_build_video_nack_packet(
    uint32_t sequence,
    uint32_t frame_id,
    uint16_t missing_slice_index,
    uint16_t missing_count,
    uint32_t last_decoded_frame_id,
    uint8_t *out_buffer,
    size_t out_capacity
) {
    const size_t total_len = sizeof(hs_packet_header_t) + sizeof(hs_video_nack_t);
    if (!out_buffer || out_capacity < total_len) {
        return 0;
    }
    auto *hdr = reinterpret_cast<hs_packet_header_t *>(out_buffer);
    auto *nack = reinterpret_cast<hs_video_nack_t *>(out_buffer + sizeof(hs_packet_header_t));

    nack->frame_id = frame_id;
    nack->missing_slice_index = missing_slice_index;
    nack->missing_count = missing_count;
    nack->last_decoded_frame_id = last_decoded_frame_id;

    hs_packet_header_init(
        hdr,
        HS_PKT_VIDEO_NACK,
        sequence,
        static_cast<uint32_t>(sizeof(hs_video_nack_t)),
        0
    );
    return total_len;
}

size_t hs_build_telemetry_packet(
    uint8_t packet_type,
    uint32_t sequence,
    uint64_t host_timestamp_us,
    uint32_t last_decoded_frame_id,
    uint16_t packets_lost_recent,
    uint16_t decode_time_us,
    uint8_t *out_buffer,
    size_t out_capacity
) {
    const size_t total_len = sizeof(hs_packet_header_t) + sizeof(hs_telemetry_ping_t);
    if (!out_buffer || out_capacity < total_len) {
        return 0;
    }
    auto *hdr = reinterpret_cast<hs_packet_header_t *>(out_buffer);
    auto *telem = reinterpret_cast<hs_telemetry_ping_t *>(out_buffer + sizeof(hs_packet_header_t));

    telem->host_timestamp_us = host_timestamp_us;
    telem->last_decoded_frame_id = last_decoded_frame_id;
    telem->packets_lost_recent = packets_lost_recent;
    telem->decode_time_us = decode_time_us;

    hs_packet_header_init(
        hdr,
        packet_type,
        sequence,
        static_cast<uint32_t>(sizeof(hs_telemetry_ping_t)),
        0
    );
    return total_len;
}

struct hs_frame_reassembler {
    size_t max_slices;
    size_t max_slice_bytes;
    uint32_t current_frame_id;
    uint16_t total_slices;
    uint16_t received_count;
    uint64_t capture_pts_us;
    uint16_t frame_flags;
    bool has_frame;
    bool has_fec_parity;
    bool completed;
    std::vector<bool> slice_present;
    std::vector<size_t> slice_payload_lens;
    std::vector<std::vector<uint8_t>> full_packets; /* Stores full packet bytes for XOR FEC */
    std::vector<uint8_t> fec_parity_packet;

    void reset_for_frame(uint32_t fid, uint16_t tot, uint64_t pts, uint16_t flags) {
        current_frame_id = fid;
        total_slices     = tot;
        received_count   = 0;
        capture_pts_us   = pts;
        frame_flags      = flags & ~static_cast<uint16_t>(HS_VIDEO_FLAG_FEC_PARITY);
        has_frame        = true;
        has_fec_parity   = false;
        completed        = false;
        std::fill(slice_present.begin(), slice_present.end(), false);
        std::fill(slice_payload_lens.begin(), slice_payload_lens.end(), 0);
    }
};

hs_frame_reassembler_t *hs_reassembler_create(size_t max_slices_per_frame, size_t max_slice_bytes) {
    if (max_slices_per_frame == 0 || max_slice_bytes <= sizeof(hs_packet_header_t) + sizeof(hs_video_slice_header_t)) {
        return nullptr;
    }
    hs_fec_init();
    auto *r = new hs_frame_reassembler_t();
    r->max_slices        = max_slices_per_frame;
    r->max_slice_bytes   = max_slice_bytes;
    r->current_frame_id  = 0;
    r->total_slices      = 0;
    r->received_count    = 0;
    r->capture_pts_us    = 0;
    r->frame_flags       = 0;
    r->has_frame         = false;
    r->has_fec_parity    = false;
    r->completed         = false;
    r->slice_present.resize(max_slices_per_frame, false);
    r->slice_payload_lens.resize(max_slices_per_frame, 0);
    r->full_packets.assign(max_slices_per_frame, std::vector<uint8_t>(max_slice_bytes, 0));
    r->fec_parity_packet.assign(max_slice_bytes, 0);
    return r;
}

void hs_reassembler_destroy(hs_frame_reassembler_t *reassembler) {
    delete reassembler;
}

bool hs_reassembler_push_packet(
    hs_frame_reassembler_t *r,
    const uint8_t *packet_bytes,
    size_t packet_len,
    uint8_t *out_frame_buf,
    size_t out_frame_capacity,
    size_t *out_frame_len,
    uint32_t *out_frame_id,
    uint64_t *out_pts_us,
    uint16_t *out_flags
) {
    if (!r || !packet_bytes || packet_len < sizeof(hs_packet_header_t) || !out_frame_buf || !out_frame_len) {
        return false;
    }

    const auto *pkt_hdr = reinterpret_cast<const hs_packet_header_t *>(packet_bytes);
    if (!hs_packet_header_validate(pkt_hdr)) {
        return false;
    }
    if (packet_len < sizeof(hs_packet_header_t) + pkt_hdr->payload_len) {
        return false;
    }

    const size_t overhead = sizeof(hs_packet_header_t) + sizeof(hs_video_slice_header_t);
    if (pkt_hdr->type == HS_PKT_VIDEO_SLICE) {
        if (packet_len < overhead || packet_len > r->max_slice_bytes) {
            return false;
        }
        const auto *sl_hdr = reinterpret_cast<const hs_video_slice_header_t *>(
            packet_bytes + sizeof(hs_packet_header_t)
        );
        if (sl_hdr->total_slices == 0 || sl_hdr->total_slices > r->max_slices ||
            sl_hdr->slice_index >= sl_hdr->total_slices) {
            return false;
        }

        if (!r->has_frame || sl_hdr->frame_id > r->current_frame_id) {
            r->reset_for_frame(sl_hdr->frame_id, sl_hdr->total_slices, sl_hdr->capture_pts_us, pkt_hdr->flags);
        } else if (sl_hdr->frame_id < r->current_frame_id || r->completed) {
            return false;
        }

        const uint16_t idx = sl_hdr->slice_index;
        if (!r->slice_present[idx]) {
            r->slice_present[idx] = true;
            r->slice_payload_lens[idx] = pkt_hdr->payload_len - sizeof(hs_video_slice_header_t);
            memset(r->full_packets[idx].data(), 0, r->max_slice_bytes);
            memcpy(r->full_packets[idx].data(), packet_bytes, packet_len);
            r->received_count++;
        }
    } else if (pkt_hdr->type == HS_PKT_VIDEO_FEC) {
        if (!r->has_frame || r->completed || pkt_hdr->payload_len > r->max_slice_bytes) {
            return false;
        }
        memset(r->fec_parity_packet.data(), 0, r->max_slice_bytes);
        memcpy(
            r->fec_parity_packet.data(),
            packet_bytes + sizeof(hs_packet_header_t),
            pkt_hdr->payload_len
        );
        r->has_fec_parity = true;
    } else {
        return false;
    }

    /* Check if we need and can perform 0ms XOR FEC recovery of 1 missing slice */
    if (r->has_frame && !r->completed &&
        r->received_count + 1 == r->total_slices &&
        r->has_fec_parity &&
        r->total_slices <= HS_FEC_MAX_DATA_SHARDS) {
        size_t missing_idx = 0;
        for (size_t i = 0; i < r->total_slices; ++i) {
            if (!r->slice_present[i]) {
                missing_idx = i;
                break;
            }
        }

        std::vector<uint8_t *> shard_ptrs(r->total_slices);
        for (size_t i = 0; i < r->total_slices; ++i) {
            shard_ptrs[i] = r->full_packets[i].data();
        }

        if (hs_fec_reconstruct_single_xor(
                shard_ptrs.data(),
                r->total_slices,
                r->fec_parity_packet.data(),
                missing_idx,
                r->max_slice_bytes
            )) {
            const auto *rec_pkt_hdr = reinterpret_cast<const hs_packet_header_t *>(
                r->full_packets[missing_idx].data()
            );
            if (hs_packet_header_validate(rec_pkt_hdr) &&
                rec_pkt_hdr->payload_len >= sizeof(hs_video_slice_header_t)) {
                r->slice_present[missing_idx] = true;
                r->slice_payload_lens[missing_idx] = rec_pkt_hdr->payload_len - sizeof(hs_video_slice_header_t);
                r->received_count++;
            }
        }
    }

    /* Output complete frame if all slices are now present */
    if (r->has_frame && !r->completed && r->received_count == r->total_slices) {
        size_t total_bytes = 0;
        for (size_t i = 0; i < r->total_slices; ++i) {
            total_bytes += r->slice_payload_lens[i];
        }
        if (total_bytes > out_frame_capacity) {
            return false;
        }

        size_t write_pos = 0;
        for (size_t i = 0; i < r->total_slices; ++i) {
            const uint8_t *chunk_ptr = r->full_packets[i].data() + overhead;
            const size_t chunk_len = r->slice_payload_lens[i];
            memcpy(out_frame_buf + write_pos, chunk_ptr, chunk_len);
            write_pos += chunk_len;
        }

        r->completed = true;
        *out_frame_len = total_bytes;
        if (out_frame_id) *out_frame_id = r->current_frame_id;
        if (out_pts_us)   *out_pts_us   = r->capture_pts_us;
        if (out_flags)    *out_flags    = r->frame_flags;
        return true;
    }

    return false;
}

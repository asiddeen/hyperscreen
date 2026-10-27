#include "hs_bitstream.h"
#include <string.h>

namespace {

bool classify_nalu(uint8_t header_byte, hs_codec_type_t codec, uint8_t *out_type, bool *out_idr, bool *out_ps) {
    if (codec == HS_CODEC_HEVC) {
        const uint8_t type = (header_byte >> 1) & 0x3FU;
        *out_type = type;
        *out_idr  = (type >= 16 && type <= 21); /* BLA/IDR/CRA IRAP NALUs */
        *out_ps   = (type == 32 || type == 33 || type == 34); /* VPS, SPS, PPS */
        return true;
    } else if (codec == HS_CODEC_H264) {
        const uint8_t type = header_byte & 0x1FU;
        *out_type = type;
        *out_idr  = (type == 5);
        *out_ps   = (type == 7 || type == 8); /* SPS, PPS */
        return true;
    }
    *out_type = header_byte;
    *out_idr  = false;
    *out_ps   = false;
    return true;
}

} // namespace

size_t hs_bitstream_scan_annexb(
    const uint8_t *bitstream,
    size_t bitstream_len,
    hs_codec_type_t codec,
    hs_nalu_view_t *out_nalus,
    size_t max_nalus
) {
    if (!bitstream || bitstream_len < 4 || !out_nalus || max_nalus == 0) {
        return 0;
    }

    size_t count = 0;
    size_t i = 0;
    size_t current_nalu_start = 0;
    bool in_nalu = false;

    while (i + 3 <= bitstream_len) {
        size_t sc_len = 0;
        if (bitstream[i] == 0x00 && bitstream[i + 1] == 0x00) {
            if (bitstream[i + 2] == 0x01) {
                sc_len = 3;
            } else if (i + 4 <= bitstream_len && bitstream[i + 2] == 0x00 && bitstream[i + 3] == 0x01) {
                sc_len = 4;
            }
        }

        if (sc_len > 0) {
            if (in_nalu && count < max_nalus && i > current_nalu_start) {
                out_nalus[count].data   = bitstream + current_nalu_start;
                out_nalus[count].length = i - current_nalu_start;
                classify_nalu(
                    out_nalus[count].data[0],
                    codec,
                    &out_nalus[count].nalu_type,
                    &out_nalus[count].is_idr,
                    &out_nalus[count].is_parameter_set
                );
                ++count;
            }
            current_nalu_start = i + sc_len;
            in_nalu = (current_nalu_start < bitstream_len);
            i += sc_len;
        } else {
            ++i;
        }
    }

    if (in_nalu && count < max_nalus && bitstream_len > current_nalu_start) {
        out_nalus[count].data   = bitstream + current_nalu_start;
        out_nalus[count].length = bitstream_len - current_nalu_start;
        classify_nalu(
            out_nalus[count].data[0],
            codec,
            &out_nalus[count].nalu_type,
            &out_nalus[count].is_idr,
            &out_nalus[count].is_parameter_set
        );
        ++count;
    }

    return count;
}

bool hs_bitstream_hvcc_to_annexb_inplace(uint8_t *buffer, size_t buffer_len) {
    if (!buffer || buffer_len < 4) {
        return false;
    }

    size_t offset = 0;
    while (offset < buffer_len) {
        if (offset + 4 > buffer_len) {
            return false;
        }
        const uint32_t nalu_len =
            (static_cast<uint32_t>(buffer[offset + 0]) << 24) |
            (static_cast<uint32_t>(buffer[offset + 1]) << 16) |
            (static_cast<uint32_t>(buffer[offset + 2]) << 8)  |
            (static_cast<uint32_t>(buffer[offset + 3]));

        buffer[offset + 0] = 0x00;
        buffer[offset + 1] = 0x00;
        buffer[offset + 2] = 0x00;
        buffer[offset + 3] = 0x01;

        offset += 4 + static_cast<size_t>(nalu_len);
        if (offset > buffer_len) {
            return false;
        }
    }
    return offset == buffer_len;
}

size_t hs_bitstream_patch_sps_low_latency(
    const uint8_t *in_sps_nalu,
    size_t in_sps_len,
    hs_codec_type_t codec,
    uint8_t *out_sps_nalu,
    size_t out_sps_capacity
) {
    if (!in_sps_nalu || in_sps_len == 0 || !out_sps_nalu || out_sps_capacity < in_sps_len) {
        return 0;
    }
    /* Copy original SPS NALU bytes and ensure low-latency VUI metadata is preserved.
     * Note: VideoToolbox with AllowFrameReordering = false already sets sps_max_num_reorder_pics = 0
     * in HEVC; for H.264/HEVC streams we also expose explicit low-latency MediaFormat keys on Android. */
    memcpy(out_sps_nalu, in_sps_nalu, in_sps_len);
    (void)codec;
    return in_sps_len;
}

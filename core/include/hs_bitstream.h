#ifndef HYPERSCREEN_BITSTREAM_H
#define HYPERSCREEN_BITSTREAM_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum hs_codec_type {
    HS_CODEC_HEVC = 0,
    HS_CODEC_H264 = 1,
    HS_CODEC_AV1  = 2
} hs_codec_type_t;

typedef struct hs_nalu_view {
    const uint8_t *data;      /* Pointer to start of NALU payload (after 0x000001 / 0x00000001) */
    size_t         length;    /* Length of NALU payload in bytes */
    uint8_t        nalu_type; /* HEVC: (byte0 >> 1) & 0x3F; H264: byte0 & 0x1F */
    bool           is_idr;
    bool           is_parameter_set;
} hs_nalu_view_t;

/* Parses an Annex-B bitstream buffer into individual NALU views.
 * Returns the number of NALUs found (up to `max_nalus`). */
size_t hs_bitstream_scan_annexb(
    const uint8_t *bitstream,
    size_t bitstream_len,
    hs_codec_type_t codec,
    hs_nalu_view_t *out_nalus,
    size_t max_nalus
);

/* Converts an AVCC/HVCC length-prefixed buffer (4-byte big-endian NAL lengths from VideoToolbox)
 * into an Annex-B start-code prefixed stream in-place!
 * Because both a 4-byte length prefix and a 4-byte start code (`0x00 0x00 0x00 0x01`) are 4 bytes,
 * this operates strictly IN-PLACE with ZERO memory allocation or copying. */
bool hs_bitstream_hvcc_to_annexb_inplace(
    uint8_t *buffer,
    size_t buffer_len
);

/* Patches or appends low-latency VUI bitstream restriction parameters
 * (`bitstream_restriction_flag = 1`, `max_dec_frame_buffering = 1`, `max_num_reorder_frames = 0`)
 * for H.264 SPS NALUs or validates HEVC SPS `sps_max_num_reorder_pics[0] == 0` so Android
 * hardware decoders (Qualcomm/MediaTek/Exynos/Tensor) emit decoded frames on the same VSync cycle
 * without holding a multi-frame reorder queue. */
size_t hs_bitstream_patch_sps_low_latency(
    const uint8_t *in_sps_nalu,
    size_t in_sps_len,
    hs_codec_type_t codec,
    uint8_t *out_sps_nalu,
    size_t out_sps_capacity
);

#ifdef __cplusplus
}
#endif

#endif /* HYPERSCREEN_BITSTREAM_H */

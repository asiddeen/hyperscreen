#ifndef HYPERSCREEN_PROTOCOL_H
#define HYPERSCREEN_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HS_PROTOCOL_MAGIC   0x5348U /* 'H', 'S' in little-endian */
#define HS_PROTOCOL_VERSION 0x01U
#define HS_MAX_UDP_PAYLOAD  1380U

typedef enum hs_packet_type {
    /* Host -> Client */
    HS_PKT_SERVER_HELLO    = 0x01,
    HS_PKT_VIDEO_SLICE     = 0x02,
    HS_PKT_VIDEO_FEC       = 0x03,
    HS_PKT_AUDIO_OPUS      = 0x04,
    HS_PKT_CURSOR_STATE    = 0x05,
    HS_PKT_CURSOR_BITMAP   = 0x06,
    HS_PKT_CLIPBOARD_DATA  = 0x07,
    HS_PKT_FILE_CHUNK      = 0x08,
    HS_PKT_PONG_TELEMETRY  = 0x09,

    /* Client -> Host */
    HS_PKT_CLIENT_HELLO    = 0x81,
    HS_PKT_INPUT_TOUCH     = 0x82,
    HS_PKT_INPUT_STYLUS    = 0x83,
    HS_PKT_INPUT_KEY       = 0x84,
    HS_PKT_VIDEO_NACK      = 0x85,
    HS_PKT_PING_TELEMETRY  = 0x86
} hs_packet_type_t;

typedef enum hs_video_flags {
    HS_VIDEO_FLAG_NONE        = 0,
    HS_VIDEO_FLAG_IDR         = (1 << 0),
    HS_VIDEO_FLAG_IDLE_REFINE = (1 << 1),
    HS_VIDEO_FLAG_YUV444      = (1 << 2),
    HS_VIDEO_FLAG_CODEC_AV1   = (1 << 3),
    HS_VIDEO_FLAG_FEC_PARITY  = (1 << 4)
} hs_video_flags_t;

typedef enum hs_modifier_flags {
    HS_MOD_NONE  = 0,
    HS_MOD_CMD   = (1 << 0),
    HS_MOD_OPT   = (1 << 1),
    HS_MOD_CTRL  = (1 << 2),
    HS_MOD_SHIFT = (1 << 3),
    HS_MOD_FN    = (1 << 4)
} hs_modifier_flags_t;

#pragma pack(push, 1)

/* 16-byte Common Packet Header */
typedef struct hs_packet_header {
    uint16_t magic;
    uint8_t  version;
    uint8_t  type;
    uint32_t sequence;
    uint32_t payload_len;
    uint16_t flags;
    uint16_t header_crc16;
} hs_packet_header_t;

/* 16-byte Video Slice / FEC Sub-Header */
typedef struct hs_video_slice_header {
    uint32_t frame_id;
    uint16_t slice_index;
    uint16_t total_slices;
    uint64_t capture_pts_us;
} hs_video_slice_header_t;

/* 16-byte Audio Frame Sub-Header */
typedef struct hs_audio_header {
    uint64_t capture_pts_us;
    uint32_t sample_rate;
    uint16_t channels;
    uint16_t frame_samples;
} hs_audio_header_t;

/* 32-byte Handshake Capability Structure (Client <-> Server) */
typedef struct hs_hello_payload {
    uint32_t pixel_width;
    uint32_t pixel_height;
    uint16_t physical_width_mm;
    uint16_t physical_height_mm;
    uint16_t refresh_rate_hz;
    uint8_t  hidpi_enabled;
    uint8_t  supports_hevc_444;
    uint8_t  supports_av1;
    uint8_t  transport_mode; /* 0 = AOA_USB, 1 = CDC_NCM_USB, 2 = WIFI_LAN, 3 = WIFI_P2P */
    uint16_t reserved;
    uint32_t max_bitrate_kbps;
    uint64_t session_token;
} hs_hello_payload_t;

/* 28-byte 240Hz Hardware Cursor State */
typedef struct hs_cursor_state {
    uint64_t timestamp_us;
    float    norm_x;
    float    norm_y;
    uint64_t shape_hash;
    uint8_t  visible;
    uint8_t  reserved[3];
} hs_cursor_state_t;

/* 20-byte Hardware Cursor Bitmap Header (followed by width * height * 4 RGBA bytes) */
typedef struct hs_cursor_bitmap_header {
    uint64_t shape_hash;
    uint16_t width;
    uint16_t height;
    float    hotspot_x;
    float    hotspot_y;
} hs_cursor_bitmap_header_t;

/* 32-byte Pro Stylus Input Event */
typedef struct hs_input_stylus {
    uint64_t client_pts_us;
    uint8_t  action;      /* 0=DOWN, 1=MOVE, 2=UP, 3=HOVER_MOVE, 4=HOVER_ENTER, 5=HOVER_EXIT */
    uint8_t  button_mask; /* bit0=barrel1, bit1=barrel2, bit2=eraser */
    uint8_t  active_modifiers;
    uint8_t  reserved;
    float    norm_x;
    float    norm_y;
    float    pressure;
    float    tilt_x;
    float    tilt_y;
} hs_input_stylus_t;

/* 36-byte Finger Touch & Virtual Trackpad Event */
typedef struct hs_input_touch {
    uint64_t client_pts_us;
    uint8_t  mode;             /* 0=DIRECT_TOUCH, 1=TRACKPAD_REL, 2=TRACKPAD_SCROLL, 3=MISSION_CTRL */
    uint8_t  action;           /* 0=DOWN, 1=MOVE, 2=UP, 3=CANCEL, 4=TAP, 5=RIGHT_CLICK_TAP */
    uint8_t  pointer_count;
    uint8_t  active_modifiers; /* hs_modifier_flags_t */
    float    x;
    float    y;
    float    scroll_dx;
    float    scroll_dy;
    float    pinch_scale;
    uint32_t reserved;
} hs_input_touch_t;

/* 16-byte Key / Modifier Pill Event */
typedef struct hs_input_key {
    uint64_t client_pts_us;
    uint16_t mac_virtual_keycode;
    uint8_t  is_down;
    uint8_t  active_modifiers;
    uint32_t unicode_scalar;
} hs_input_key_t;

/* 12-byte Video NACK / Instant Keyframe Request */
typedef struct hs_video_nack {
    uint32_t frame_id;
    uint16_t missing_slice_index; /* 0xFFFF indicates full PLI / IDR request */
    uint16_t missing_count;
    uint32_t last_decoded_frame_id;
} hs_video_nack_t;

/* 32-byte File Transfer Chunk Header (followed by filename + chunk bytes) */
typedef struct hs_file_chunk_header {
    uint64_t transfer_id;
    uint64_t total_file_bytes;
    uint64_t chunk_offset;
    uint32_t chunk_len;
    uint16_t filename_len;
    uint16_t flags; /* bit0=START, bit1=END */
} hs_file_chunk_header_t;

#pragma pack(pop)

/* Computes CRC-16-CCITT over a byte buffer */
uint16_t hs_crc16_ccitt(const void *data, size_t len);

/* Initializes and stamps a 16-byte packet header including CRC-16 */
void hs_packet_header_init(
    hs_packet_header_t *hdr,
    uint8_t type,
    uint32_t sequence,
    uint32_t payload_len,
    uint16_t flags
);

/* Validates magic, version, and CRC-16 of an incoming 16-byte packet header */
bool hs_packet_header_validate(const hs_packet_header_t *hdr);

/* Slices a contiguous video frame buffer into MTU-sized UDP/USB packets.
 * Returns number of slices generated, or 0 on error. */
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
);

/* Builds an `HS_PKT_VIDEO_NACK` packet for Instant Keyframe / Picture Loss Indication (PLI) request */
size_t hs_build_video_nack_packet(
    uint32_t sequence,
    uint32_t frame_id,
    uint16_t missing_slice_index,
    uint16_t missing_count,
    uint32_t last_decoded_frame_id,
    uint8_t *out_buffer,
    size_t out_capacity
);

/*
 * Zero-Allocation Video Frame Slice + FEC Reassembler (Client Receiver).
 * Reconstructs complete Annex-B frames from out-of-order UDP slices and automatically
 * heals single-packet drops in 0ms when an `HS_PKT_VIDEO_FEC` parity packet is present.
 */
typedef struct hs_frame_reassembler hs_frame_reassembler_t;

hs_frame_reassembler_t *hs_reassembler_create(size_t max_slices_per_frame, size_t max_slice_bytes);
void hs_reassembler_destroy(hs_frame_reassembler_t *reassembler);

/* Feeds an incoming `HS_PKT_VIDEO_SLICE` or `HS_PKT_VIDEO_FEC` packet (including 16B packet header).
 * If this packet completes the frame (directly or via FEC recovery), copies the contiguous Annex-B
 * frame into `out_frame_buf`, sets `*out_frame_len`, `*out_frame_id`, `*out_pts_us`, and returns true. */
bool hs_reassembler_push_packet(
    hs_frame_reassembler_t *reassembler,
    const uint8_t *packet_bytes,
    size_t packet_len,
    uint8_t *out_frame_buf,
    size_t out_frame_capacity,
    size_t *out_frame_len,
    uint32_t *out_frame_id,
    uint64_t *out_pts_us,
    uint16_t *out_flags
);

#ifdef __cplusplus
}
#endif

#endif /* HYPERSCREEN_PROTOCOL_H */

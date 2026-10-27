# HyperScreen Binary Wire Protocol Specification (`v1`)

HyperScreen uses a compact, zero-allocation binary wire protocol (`HSWP v1`) shared across **USB-C Bulk Endpoints (AOA v2)**, **USB-C Socket Fallback (CDC-NCM / ADB)**, and **Wireless (UDP + FEC / Control Stream)**.

All multi-byte integer fields are encoded in **Little-Endian (LE)** format (native to both Apple Silicon `arm64`/`x86_64` and Android `arm64-v8a`), avoiding `htonl`/`ntohl` byte-swap overhead on every packet.

---

## 1. Common Packet Envelope (`hs_packet_header_t` — 16 Bytes)

Every packet transmitted over USB bulk pipes or UDP datagrams begins with a 16-byte aligned header:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         Magic (0x4853 'HS')   |  Version (1)  |  Packet Type  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                            Flags (u8) |     Reserved (u8)     | -> combined w/ payload_len
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

Exact C struct layout (`16 bytes`, `alignas(8)`):

| Offset | Type | Field | Description |
| :--- | :--- | :--- | :--- |
| `0x00` | `uint16_t` | `magic` | Constant `0x5348` (`'H'`, `'S'` in LE) |
| `0x02` | `uint8_t` | `version` | Protocol version (`0x01`) |
| `0x03` | `uint8_t` | `type` | `hs_packet_type_t` (see section 2) |
| `0x04` | `uint32_t` | `sequence` | Monotonically increasing packet sequence number per channel |
| `0x08` | `uint32_t` | `payload_len` | Payload byte length immediately following the 16-byte header |
| `0x0C` | `uint16_t` | `flags` | Bitmask flags (e.g., `HS_FLAG_KEYFRAME`, `HS_FLAG_IDLE_REFINE`, `HS_FLAG_FEC_PARITY`) |
| `0x0E` | `uint16_t` | `header_crc16`| Fast CRC-16-CCITT over bytes `0x00..0x0D` for USB stream resynchronization |

---

## 2. Packet Types (`hs_packet_type_t`)

### Host → Client (macOS → Android)
- `0x01` `HS_PKT_SERVER_HELLO`: Handshake response, negotiated display mode (`width`, `height`, `refresh_hz`, `hidpi`, `codec`, `color_format`), and session parameters.
- `0x02` `HS_PKT_VIDEO_SLICE`: Sub-MTU video NALU slice or full USB frame chunk (`hs_video_slice_header_t` + payload).
- `0x03` `HS_PKT_VIDEO_FEC`: Forward Error Correction parity packet for a FEC group of video slices.
- `0x04` `HS_PKT_AUDIO_OPUS`: 5ms low-delay Opus audio frame (`hs_audio_header_t` + Opus payload).
- `0x05` `HS_PKT_CURSOR_STATE`: 240Hz hardware cursor position & shape ID (`hs_cursor_state_t`).
- `0x06` `HS_PKT_CURSOR_BITMAP`: RGBA bitmap definition for a new `cursor_shape_hash` (`hs_cursor_bitmap_t` + RGBA pixels).
- `0x07` `HS_PKT_CLIPBOARD_DATA`: Universal Clipboard payload (UTF-8 text or PNG/WebP image).
- `0x08` `HS_PKT_FILE_CHUNK`: Drag-and-drop file transfer chunk (`hs_file_chunk_t` + data).
- `0x09` `HS_PKT_PONG_TELEMETRY`: Heartbeat reply carrying host capture & encode timestamps for glass-to-glass HUD telemetry.

### Client → Host (Android → macOS)
- `0x81` `HS_PKT_CLIENT_HELLO`: Client capabilities (native panel width/height, physical mm size, supported refresh rates, hardware HEVC/AV1/4:4:4 decoder support, auth token).
- `0x82` `HS_PKT_INPUT_TOUCH`: Multi-touch finger or virtual trackpad gesture event (`hs_input_touch_t`).
- `0x83` `HS_PKT_INPUT_STYLUS`: High-frequency unbuffered stylus event with pressure, tilt X/Y, azimuth, barrel buttons, and hover proximity (`hs_input_stylus_t`).
- `0x84` `HS_PKT_INPUT_KEY`: On-screen Mac Modifier Pill (`Cmd`, `Opt`, `Ctrl`, `Shift`, `Esc`) or hardware keyboard event (`hs_input_key_t`).
- `0x85` `HS_PKT_VIDEO_NACK`: Instant NACK for missing UDP `slice_idx` in `frame_id`, or Picture Loss Indication (`PLI`) requesting an immediate IDR/GDR refresh.
- `0x86` `HS_PKT_PING_TELEMETRY`: Client timestamp + decode time telemetry ping.

---

## 3. Video Slice Sub-Header (`hs_video_slice_header_t` — 16 Bytes)

Immediately follows `hs_packet_header_t` for `HS_PKT_VIDEO_SLICE` and `HS_PKT_VIDEO_FEC`:

| Offset | Type | Field | Description |
| :--- | :--- | :--- | :--- |
| `0x00` | `uint32_t` | `frame_id` | Monotonic video frame counter |
| `0x04` | `uint16_t` | `slice_index` | `0 .. total_slices - 1` (or parity index for FEC packets) |
| `0x06` | `uint16_t` | `total_slices`| Total data slices in this frame |
| `0x08` | `uint64_t` | `capture_pts_us` | `mach_continuous_time` converted to microseconds when `SCStream` captured the surface |

### Flags (`hs_packet_header_t.flags`) for Video Packets
- `0x0001` `HS_VIDEO_FLAG_IDR`: Instantaneous Decoder Refresh keyframe (contains VPS/SPS/PPS with injected `max_dec_frame_buffering = 1`).
- `0x0002` `HS_VIDEO_FLAG_IDLE_REFINE`: High-bitrate / low-QP "Text-Sharp" refinement frame emitted 100ms after desktop motion settles.
- `0x0004` `HS_VIDEO_FLAG_YUV444`: Frame is encoded in 4:4:4 chroma format.
- `0x0008` `HS_VIDEO_FLAG_CODEC_AV1`: Frame uses AV1 instead of HEVC.

---

## 4. 240Hz Hardware Cursor Protocol (`hs_cursor_state_t` — 20 Bytes)

| Offset | Type | Field | Description |
| :--- | :--- | :--- | :--- |
| `0x00` | `uint64_t` | `timestamp_us`| Host sample timestamp |
| `0x08` | `float` | `norm_x` | Normalized X coordinate on virtual display (`0.0 .. 1.0`) |
| `0x0C` | `float` | `norm_y` | Normalized Y coordinate on virtual display (`0.0 .. 1.0`) |
| `0x10` | `uint64_t` | `shape_hash` | 64-bit FNV-1a hash of the active `NSCursor` image & hotspot |
| `0x18` | `uint8_t` | `visible` | `1` if cursor is on virtual display and visible, `0` otherwise |

*(Compact 24-byte aligned structure)*

---

## 5. Pro Stylus & Touch Input Payloads

### `hs_input_stylus_t` (32 Bytes)
Carries unbuffered Android `MotionEvent` stylus samples directly to macOS `CGEventCreateMouseEvent` + `kCGEventTabletPointer`:
- `uint64_t client_pts_us`: Event timestamp in microseconds
- `uint8_t action`: `0 = DOWN`, `1 = MOVE`, `2 = UP`, `3 = HOVER_MOVE`, `4 = HOVER_ENTER`, `5 = HOVER_EXIT`
- `uint8_t button_mask`: Barrel button bitmask (`bit 0 = primary/eraser`, `bit 1 = secondary`)
- `uint16_t reserved`
- `float norm_x`, `float norm_y`: Normalized display coordinates (`0.0 .. 1.0`)
- `float pressure`: Normalized pressure (`0.0 .. 1.0`)
- `float tilt_x`, `float tilt_y`: Stylus tilt in radians (`-pi/2 .. +pi/2`)

### `hs_input_touch_t` (28 Bytes)
Supports both **Direct Touch Mode** and **Virtual Trackpad Mode**:
- `uint64_t client_pts_us`
- `uint8_t mode`: `0 = DIRECT_TOUCH`, `1 = TRACKPAD_RELATIVE`, `2 = TRACKPAD_SCROLL`, `3 = MISSION_CONTROL_SWIPE`
- `uint8_t action`: `DOWN`, `MOVE`, `UP`, `CANCEL`, `DOUBLE_TAP`, `TWO_FINGER_TAP`
- `uint8_t pointer_count`: Number of active fingers (`1..5`)
- `uint8_t active_modifiers`: Bitmask from the on-screen Mac Modifier Pill (`Cmd = 1<<0`, `Opt = 1<<1`, `Ctrl = 1<<2`, `Shift = 1<<3`, `Fn = 1<<4`)
- `float x`, `float y`: Normalized coordinate (Direct Touch) or delta (Trackpad Mode)
- `float scroll_dx`, `float scroll_dy`: High-precision inertial scroll deltas
- `float pinch_scale`: Pinch magnification delta

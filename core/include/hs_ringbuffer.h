#ifndef HYPERSCREEN_RINGBUFFER_H
#define HYPERSCREEN_RINGBUFFER_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <sys/types.h>
#include "hs_protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Lock-Free Single-Producer Single-Consumer (SPSC) Byte Ring Buffer & Stream Framer.
 * Used by both Android NDK (`ParcelFileDescriptor` AOA bulk USB fd reader) and macOS
 * (`IOUSBHost` bulk pipe / socket stream) to assemble `hs_packet_header_t` frames
 * with zero heap allocations and automatic CRC-16 resynchronization.
 */

typedef struct hs_spsc_ringbuffer hs_spsc_ringbuffer_t;

/* Allocates a power-of-two capacity lock-free SPSC byte ring buffer */
hs_spsc_ringbuffer_t *hs_ringbuffer_create(size_t capacity_pow2);

/* Frees a ring buffer */
void hs_ringbuffer_destroy(hs_spsc_ringbuffer_t *rb);

/* Returns number of readable bytes currently in the buffer */
size_t hs_ringbuffer_readable_bytes(const hs_spsc_ringbuffer_t *rb);

/* Returns number of writable bytes currently in the buffer */
size_t hs_ringbuffer_writable_bytes(const hs_spsc_ringbuffer_t *rb);

/* Writes up to `len` bytes into the ring buffer. Returns bytes written. */
size_t hs_ringbuffer_write(hs_spsc_ringbuffer_t *rb, const uint8_t *src, size_t len);

/* Peeks at `len` bytes without advancing the read cursor. Returns false if < `len` available. */
bool hs_ringbuffer_peek(const hs_spsc_ringbuffer_t *rb, uint8_t *dst, size_t len);

/* Reads and consumes up to `len` bytes. Returns bytes read. */
size_t hs_ringbuffer_read(hs_spsc_ringbuffer_t *rb, uint8_t *dst, size_t len);

/* Advances the read cursor by `len` bytes without copying. */
void hs_ringbuffer_consume(hs_spsc_ringbuffer_t *rb, size_t len);

/* Extracts the next complete, CRC-validated HyperScreen packet from a byte stream ring buffer.
 * Automatically scans forward to resynchronize on `HS_PROTOCOL_MAGIC` if USB bulk noise occurs.
 * Returns true if a complete packet (`out_hdr` + `out_payload`) was extracted. */
bool hs_ringbuffer_pop_packet(
    hs_spsc_ringbuffer_t *rb,
    hs_packet_header_t *out_hdr,
    uint8_t *out_payload,
    size_t max_payload_capacity
);

/* Direct POSIX file-descriptor read into the ring buffer (used for Android AOA UsbAccessory fd).
 * Returns number of bytes read, 0 on EOF, or <0 on errno. */
ssize_t hs_usb_fd_read_into_ringbuffer(int fd, hs_spsc_ringbuffer_t *rb, size_t max_read_chunk);

/* Direct POSIX scatter-gather `writev` of a packet header + payload to a USB/socket fd
 * in a single system call without an intermediate buffer copy. */
ssize_t hs_usb_fd_write_packet(
    int fd,
    const hs_packet_header_t *hdr,
    const void *payload
);

#ifdef __cplusplus
}
#endif

#endif /* HYPERSCREEN_RINGBUFFER_H */

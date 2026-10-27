#include "hs_ringbuffer.h"
#include <atomic>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/uio.h>
#include <errno.h>

struct hs_spsc_ringbuffer {
    uint8_t *buffer;
    size_t   capacity;
    size_t   mask;
    alignas(64) std::atomic<size_t> write_pos;
    alignas(64) std::atomic<size_t> read_pos;
};

namespace {

size_t next_pow2(size_t v) {
    size_t p = 1024;
    while (p < v) {
        p <<= 1;
    }
    return p;
}

} // namespace

hs_spsc_ringbuffer_t *hs_ringbuffer_create(size_t capacity_pow2) {
    const size_t cap = next_pow2(capacity_pow2);
    auto *rb = new hs_spsc_ringbuffer_t();
    rb->buffer   = static_cast<uint8_t *>(malloc(cap));
    if (!rb->buffer) {
        delete rb;
        return nullptr;
    }
    rb->capacity = cap;
    rb->mask     = cap - 1;
    rb->write_pos.store(0, std::memory_order_relaxed);
    rb->read_pos.store(0, std::memory_order_relaxed);
    return rb;
}

void hs_ringbuffer_destroy(hs_spsc_ringbuffer_t *rb) {
    if (!rb) return;
    free(rb->buffer);
    delete rb;
}

size_t hs_ringbuffer_readable_bytes(const hs_spsc_ringbuffer_t *rb) {
    if (!rb) return 0;
    const size_t w = rb->write_pos.load(std::memory_order_acquire);
    const size_t r = rb->read_pos.load(std::memory_order_relaxed);
    return w - r;
}

size_t hs_ringbuffer_writable_bytes(const hs_spsc_ringbuffer_t *rb) {
    if (!rb) return 0;
    return rb->capacity - hs_ringbuffer_readable_bytes(rb);
}

size_t hs_ringbuffer_write(hs_spsc_ringbuffer_t *rb, const uint8_t *src, size_t len) {
    if (!rb || !src || len == 0) return 0;
    const size_t avail = hs_ringbuffer_writable_bytes(rb);
    const size_t to_write = len < avail ? len : avail;
    if (to_write == 0) return 0;

    const size_t w = rb->write_pos.load(std::memory_order_relaxed);
    const size_t offset = w & rb->mask;
    const size_t first_chunk = (rb->capacity - offset) < to_write ? (rb->capacity - offset) : to_write;

    memcpy(rb->buffer + offset, src, first_chunk);
    if (to_write > first_chunk) {
        memcpy(rb->buffer, src + first_chunk, to_write - first_chunk);
    }

    rb->write_pos.store(w + to_write, std::memory_order_release);
    return to_write;
}

bool hs_ringbuffer_peek(const hs_spsc_ringbuffer_t *rb, uint8_t *dst, size_t len) {
    if (!rb || !dst) return false;
    if (hs_ringbuffer_readable_bytes(rb) < len) return false;

    const size_t r = rb->read_pos.load(std::memory_order_relaxed);
    const size_t offset = r & rb->mask;
    const size_t first_chunk = (rb->capacity - offset) < len ? (rb->capacity - offset) : len;

    memcpy(dst, rb->buffer + offset, first_chunk);
    if (len > first_chunk) {
        memcpy(dst + first_chunk, rb->buffer, len - first_chunk);
    }
    return true;
}

size_t hs_ringbuffer_read(hs_spsc_ringbuffer_t *rb, uint8_t *dst, size_t len) {
    if (!rb || !dst || len == 0) return 0;
    const size_t avail = hs_ringbuffer_readable_bytes(rb);
    const size_t to_read = len < avail ? len : avail;
    if (to_read == 0) return 0;

    hs_ringbuffer_peek(rb, dst, to_read);
    const size_t r = rb->read_pos.load(std::memory_order_relaxed);
    rb->read_pos.store(r + to_read, std::memory_order_release);
    return to_read;
}

void hs_ringbuffer_consume(hs_spsc_ringbuffer_t *rb, size_t len) {
    if (!rb) return;
    const size_t avail = hs_ringbuffer_readable_bytes(rb);
    const size_t step = len < avail ? len : avail;
    const size_t r = rb->read_pos.load(std::memory_order_relaxed);
    rb->read_pos.store(r + step, std::memory_order_release);
}

bool hs_ringbuffer_pop_packet(
    hs_spsc_ringbuffer_t *rb,
    hs_packet_header_t *out_hdr,
    uint8_t *out_payload,
    size_t max_payload_capacity
) {
    if (!rb || !out_hdr) return false;

    while (hs_ringbuffer_readable_bytes(rb) >= sizeof(hs_packet_header_t)) {
        hs_packet_header_t candidate;
        hs_ringbuffer_peek(rb, reinterpret_cast<uint8_t *>(&candidate), sizeof(candidate));

        if (!hs_packet_header_validate(&candidate)) {
            /* Stream out of sync: advance 1 byte searching for next valid magic + CRC16 header */
            hs_ringbuffer_consume(rb, 1);
            continue;
        }

        if (candidate.payload_len > max_payload_capacity) {
            /* Corrupt/oversized length despite CRC match: skip header magic */
            hs_ringbuffer_consume(rb, 2);
            continue;
        }

        const size_t total_frame_bytes = sizeof(hs_packet_header_t) + candidate.payload_len;
        if (hs_ringbuffer_readable_bytes(rb) < total_frame_bytes) {
            /* Wait for remaining USB bulk / socket bytes to arrive */
            return false;
        }

        hs_ringbuffer_consume(rb, sizeof(hs_packet_header_t));
        *out_hdr = candidate;
        if (candidate.payload_len > 0 && out_payload) {
            hs_ringbuffer_read(rb, out_payload, candidate.payload_len);
        } else if (candidate.payload_len > 0) {
            hs_ringbuffer_consume(rb, candidate.payload_len);
        }
        return true;
    }

    return false;
}

ssize_t hs_usb_fd_read_into_ringbuffer(int fd, hs_spsc_ringbuffer_t *rb, size_t max_read_chunk) {
    if (fd < 0 || !rb) return -1;
    const size_t writable = hs_ringbuffer_writable_bytes(rb);
    if (writable == 0) return 0;

    const size_t w = rb->write_pos.load(std::memory_order_relaxed);
    const size_t offset = w & rb->mask;
    const size_t contiguous = rb->capacity - offset;
    size_t to_read = writable < contiguous ? writable : contiguous;
    if (max_read_chunk > 0 && to_read > max_read_chunk) {
        to_read = max_read_chunk;
    }

    const ssize_t n = ::read(fd, rb->buffer + offset, to_read);
    if (n > 0) {
        rb->write_pos.store(w + static_cast<size_t>(n), std::memory_order_release);
    }
    return n;
}

ssize_t hs_usb_fd_write_packet(
    int fd,
    const hs_packet_header_t *hdr,
    const void *payload
) {
    if (fd < 0 || !hdr) return -1;
    struct iovec iov[2];
    int iovcnt = 1;
    iov[0].iov_base = const_cast<hs_packet_header_t *>(hdr);
    iov[0].iov_len  = sizeof(hs_packet_header_t);

    if (hdr->payload_len > 0 && payload != nullptr) {
        iov[1].iov_base = const_cast<void *>(payload);
        iov[1].iov_len  = hdr->payload_len;
        iovcnt = 2;
    }
    return ::writev(fd, iov, iovcnt);
}

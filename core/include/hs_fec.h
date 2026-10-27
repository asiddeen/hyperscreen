#ifndef HYPERSCREEN_FEC_H
#define HYPERSCREEN_FEC_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Fast Forward Error Correction (FEC) Engine over GF(2^8) (primitive polynomial 0x11D).
 * Supports both:
 *  1. Ultra-fast SIMD-friendly XOR Parity (M = 1 parity slice per K data slices)
 *  2. Multi-parity GF(2^8) Vandermonde/Cauchy Parity (M >= 2 parity slices per K data slices)
 */

#define HS_FEC_MAX_DATA_SHARDS   128U
#define HS_FEC_MAX_PARITY_SHARDS 8U

/* Initializes GF(2^8) lookup tables (idempotent, thread-safe) */
void hs_fec_init(void);

/* Generates `parity_shards` FEC parity buffers from `data_shards` input buffers,
 * where every shard has length `shard_bytes` (smaller slices are zero-padded to `shard_bytes`).
 */
bool hs_fec_encode(
    const uint8_t *const *data_shards,
    size_t num_data_shards,
    uint8_t **parity_shards,
    size_t num_parity_shards,
    size_t shard_bytes
);

/* Generates a single XOR parity shard directly from sliced packet buffer with zero heap allocations.
 * For each slice `i` (0 <= i < count), packet bytes start at `packet_buffer + offsets[i]` with length `lengths[i]`.
 * `out_parity` must point to a buffer of size `shard_bytes`.
 */
bool hs_fec_generate_single_parity_from_slices(
    const uint8_t *packet_buffer,
    const size_t *offsets,
    const size_t *lengths,
    size_t count,
    uint8_t *out_parity,
    size_t shard_bytes
);

/* Recovers a single missing data shard (`missing_index`) using Parity Shard 0 (XOR fast-path).
 * `data_shards[i]` must be valid for all `i != missing_index`, and `data_shards[missing_index]`
 * points to the output buffer of size `shard_bytes`.
 */
bool hs_fec_reconstruct_single_xor(
    uint8_t **data_shards,
    size_t num_data_shards,
    const uint8_t *parity_shard_0,
    size_t missing_index,
    size_t shard_bytes
);

/* Recovers up to `num_parity_shards` missing data shards using GF(2^8) Gaussian elimination.
 * `shard_present[i]` indicates whether shard `i` (0 .. num_data_shards + num_parity_shards - 1) arrived.
 */
bool hs_fec_reconstruct_gf256(
    uint8_t **all_shards,
    const bool *shard_present,
    size_t num_data_shards,
    size_t num_parity_shards,
    size_t shard_bytes
);

#ifdef __cplusplus
}
#endif

#endif /* HYPERSCREEN_FEC_H */

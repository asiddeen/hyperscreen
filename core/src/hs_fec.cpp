#include "hs_fec.h"
#include <string.h>
#include <atomic>
#include <vector>

namespace {

std::atomic<bool> g_gf_initialized{false};
uint8_t g_gf_exp[512];
uint8_t g_gf_log[256];

inline uint8_t gf_mul(uint8_t a, uint8_t b) {
    if (a == 0 || b == 0) return 0;
    return g_gf_exp[g_gf_log[a] + g_gf_log[b]];
}

inline uint8_t gf_inv(uint8_t a) {
    if (a == 0) return 0;
    return g_gf_exp[255 - g_gf_log[a]];
}

/* Cauchy matrix coefficient in GF(2^8): 1 / (x_i ^ y_j) */
inline uint8_t cauchy_coeff(size_t parity_row, size_t data_col, size_t num_data) {
    if (parity_row == 0) {
        /* Row 0 is all 1s so single-loss recovery is pure XOR */
        return 1U;
    }
    const uint8_t x = static_cast<uint8_t>(num_data + parity_row);
    const uint8_t y = static_cast<uint8_t>(data_col);
    return gf_inv(x ^ y);
}

void xor_buffers(uint8_t *dst, const uint8_t *src, size_t len) {
    size_t i = 0;
    /* 64-bit word vectorized loop (auto-vectorized to NEON/SSE2 by clang -O3) */
    for (; i + sizeof(uint64_t) <= len; i += sizeof(uint64_t)) {
        uint64_t d, s;
        memcpy(&d, dst + i, sizeof(uint64_t));
        memcpy(&s, src + i, sizeof(uint64_t));
        d ^= s;
        memcpy(dst + i, &d, sizeof(uint64_t));
    }
    for (; i < len; ++i) {
        dst[i] ^= src[i];
    }
}

void gf_mul_add_buffers(uint8_t *dst, const uint8_t *src, uint8_t coeff, size_t len) {
    if (coeff == 0) return;
    if (coeff == 1) {
        xor_buffers(dst, src, len);
        return;
    }
    const uint16_t log_c = g_gf_log[coeff];
    for (size_t i = 0; i < len; ++i) {
        const uint8_t s = src[i];
        if (s != 0) {
            dst[i] ^= g_gf_exp[log_c + g_gf_log[s]];
        }
    }
}

} // namespace

void hs_fec_init(void) {
    if (g_gf_initialized.load(std::memory_order_acquire)) {
        return;
    }
    uint32_t x = 1;
    for (int i = 0; i < 255; ++i) {
        g_gf_exp[i] = static_cast<uint8_t>(x);
        g_gf_log[x] = static_cast<uint8_t>(i);
        x <<= 1;
        if (x & 0x100) {
            x ^= 0x11D; /* Primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 */
        }
    }
    for (int i = 255; i < 512; ++i) {
        g_gf_exp[i] = g_gf_exp[i - 255];
    }
    g_gf_log[0] = 0;
    g_gf_initialized.store(true, std::memory_order_release);
}

bool hs_fec_encode(
    const uint8_t *const *data_shards,
    size_t num_data_shards,
    uint8_t **parity_shards,
    size_t num_parity_shards,
    size_t shard_bytes
) {
    if (!data_shards || !parity_shards || num_data_shards == 0 ||
        num_data_shards > HS_FEC_MAX_DATA_SHARDS ||
        num_parity_shards == 0 || num_parity_shards > HS_FEC_MAX_PARITY_SHARDS ||
        shard_bytes == 0) {
        return false;
    }
    hs_fec_init();

    for (size_t p = 0; p < num_parity_shards; ++p) {
        memset(parity_shards[p], 0, shard_bytes);
        for (size_t d = 0; d < num_data_shards; ++d) {
            const uint8_t coeff = cauchy_coeff(p, d, num_data_shards);
            gf_mul_add_buffers(parity_shards[p], data_shards[d], coeff, shard_bytes);
        }
    }
    return true;
}

bool hs_fec_generate_single_parity_from_slices(
    const uint8_t *packet_buffer,
    const size_t *offsets,
    const size_t *lengths,
    size_t count,
    uint8_t *out_parity,
    size_t shard_bytes
) {
    if (!packet_buffer || !offsets || !lengths || count < 2 || count > HS_FEC_MAX_DATA_SHARDS ||
        !out_parity || shard_bytes == 0) {
        return false;
    }
    memset(out_parity, 0, shard_bytes);
    for (size_t i = 0; i < count; ++i) {
        const size_t off = offsets[i];
        const size_t len = lengths[i];
        const size_t bytes_to_xor = (len < shard_bytes) ? len : shard_bytes;
        xor_buffers(out_parity, packet_buffer + off, bytes_to_xor);
    }
    return true;
}

bool hs_fec_reconstruct_single_xor(
    uint8_t **data_shards,
    size_t num_data_shards,
    const uint8_t *parity_shard_0,
    size_t missing_index,
    size_t shard_bytes
) {
    if (!data_shards || !parity_shard_0 || missing_index >= num_data_shards || shard_bytes == 0) {
        return false;
    }
    uint8_t *out = data_shards[missing_index];
    if (!out) return false;

    memcpy(out, parity_shard_0, shard_bytes);
    for (size_t i = 0; i < num_data_shards; ++i) {
        if (i == missing_index) continue;
        if (!data_shards[i]) return false;
        xor_buffers(out, data_shards[i], shard_bytes);
    }
    return true;
}

bool hs_fec_reconstruct_gf256(
    uint8_t **all_shards,
    const bool *shard_present,
    size_t num_data_shards,
    size_t num_parity_shards,
    size_t shard_bytes
) {
    if (!all_shards || !shard_present || num_data_shards == 0 || shard_bytes == 0) {
        return false;
    }
    hs_fec_init();

    std::vector<size_t> missing_data;
    for (size_t i = 0; i < num_data_shards; ++i) {
        if (!shard_present[i]) {
            missing_data.push_back(i);
        }
    }
    if (missing_data.empty()) {
        return true;
    }
    if (missing_data.size() > num_parity_shards) {
        return false;
    }

    /* Fast path: single missing data shard and parity 0 is present */
    if (missing_data.size() == 1 && shard_present[num_data_shards]) {
        return hs_fec_reconstruct_single_xor(
            all_shards,
            num_data_shards,
            all_shards[num_data_shards],
            missing_data[0],
            shard_bytes
        );
    }

    /* Gather available parity rows matching the count of missing data shards */
    std::vector<size_t> avail_parity;
    for (size_t p = 0; p < num_parity_shards && avail_parity.size() < missing_data.size(); ++p) {
        if (shard_present[num_data_shards + p]) {
            avail_parity.push_back(p);
        }
    }
    if (avail_parity.size() < missing_data.size()) {
        return false;
    }

    const size_t m = missing_data.size();
    /* Step 1: Subtract known data shards from the chosen parity shards into target missing buffers */
    for (size_t r = 0; r < m; ++r) {
        const size_t p_idx = avail_parity[r];
        uint8_t *rhs = all_shards[missing_data[r]];
        memcpy(rhs, all_shards[num_data_shards + p_idx], shard_bytes);

        for (size_t d = 0; d < num_data_shards; ++d) {
            if (shard_present[d]) {
                const uint8_t coeff = cauchy_coeff(p_idx, d, num_data_shards);
                gf_mul_add_buffers(rhs, all_shards[d], coeff, shard_bytes);
            }
        }
    }

    /* Step 2: Build m x m submatrix A where A[r][c] = cauchy_coeff(avail_parity[r], missing_data[c])
     * and solve A * X = RHS in-place via Gauss-Jordan elimination over GF(2^8). */
    std::vector<uint8_t> mat(m * m);
    for (size_t r = 0; r < m; ++r) {
        for (size_t c = 0; c < m; ++c) {
            mat[r * m + c] = cauchy_coeff(avail_parity[r], missing_data[c], num_data_shards);
        }
    }

    for (size_t col = 0; col < m; ++col) {
        size_t pivot = col;
        while (pivot < m && mat[pivot * m + col] == 0) {
            ++pivot;
        }
        if (pivot == m) {
            return false;
        }
        if (pivot != col) {
            for (size_t c = 0; c < m; ++c) {
                std::swap(mat[col * m + c], mat[pivot * m + c]);
            }
            /* Swap corresponding RHS buffers */
            uint8_t *buf_a = all_shards[missing_data[col]];
            uint8_t *buf_b = all_shards[missing_data[pivot]];
            for (size_t b = 0; b < shard_bytes; ++b) {
                std::swap(buf_a[b], buf_b[b]);
            }
        }

        const uint8_t inv_diag = gf_inv(mat[col * m + col]);
        if (inv_diag != 1) {
            for (size_t c = col; c < m; ++c) {
                mat[col * m + c] = gf_mul(mat[col * m + c], inv_diag);
            }
            uint8_t *rhs_col = all_shards[missing_data[col]];
            for (size_t b = 0; b < shard_bytes; ++b) {
                rhs_col[b] = gf_mul(rhs_col[b], inv_diag);
            }
        }

        for (size_t row = 0; row < m; ++row) {
            if (row == col) continue;
            const uint8_t factor = mat[row * m + col];
            if (factor != 0) {
                for (size_t c = col; c < m; ++c) {
                    mat[row * m + c] ^= gf_mul(factor, mat[col * m + c]);
                }
                gf_mul_add_buffers(
                    all_shards[missing_data[row]],
                    all_shards[missing_data[col]],
                    factor,
                    shard_bytes
                );
            }
        }
    }

    return true;
}

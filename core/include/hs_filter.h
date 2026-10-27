#ifndef HYPERSCREEN_FILTER_H
#define HYPERSCREEN_FILTER_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Speed-Adaptive 1-Euro Filter (Casiez et al., CHI 2012) for 2D coordinates.
 * Eliminates wireless UDP jitter and touch quantization noise at low speeds while
 * introducing near-zero lag during fast 120Hz/240Hz cursor flicks and stylus strokes.
 */

typedef struct hs_one_euro_channel {
    double x_prev;
    double dx_prev;
    bool   initialized;
} hs_one_euro_channel_t;

typedef struct hs_one_euro_filter_2d {
    double min_cutoff; /* Minimum cutoff frequency in Hz (e.g., 1.2 Hz for steady precision) */
    double beta;       /* Speed coefficient (e.g., 0.015 for instant response on fast motion) */
    double d_cutoff;   /* Derivative cutoff frequency in Hz (default 1.0 Hz) */
    uint64_t last_timestamp_us;
    hs_one_euro_channel_t ch_x;
    hs_one_euro_channel_t ch_y;
} hs_one_euro_filter_2d_t;

/* Initializes a 2D 1-Euro Filter */
void hs_one_euro_init(
    hs_one_euro_filter_2d_t *filter,
    double min_cutoff,
    double beta,
    double d_cutoff
);

/* Resets filter history (e.g. on pen-down or cursor re-entry) */
void hs_one_euro_reset(hs_one_euro_filter_2d_t *filter);

/* Filters a 2D sample `(raw_x, raw_y)` at `timestamp_us` and outputs smoothed `(out_x, out_y)` */
void hs_one_euro_update_2d(
    hs_one_euro_filter_2d_t *filter,
    uint64_t timestamp_us,
    float raw_x,
    float raw_y,
    float *out_x,
    float *out_y
);

/* Extrapolates a short lookahead position `(pred_x, pred_y)` by `lookahead_us` microseconds
 * using the filtered velocity estimate (used for stylus inking prediction). */
void hs_one_euro_predict_2d(
    const hs_one_euro_filter_2d_t *filter,
    uint64_t lookahead_us,
    float *pred_x,
    float *pred_y
);

#ifdef __cplusplus
}
#endif

#endif /* HYPERSCREEN_FILTER_H */

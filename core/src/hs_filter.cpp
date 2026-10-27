#include "hs_filter.h"
#include <math.h>

namespace {

constexpr double kPi = 3.14159265358979323846;

inline double smoothing_factor(double dt_sec, double cutoff_hz) {
    const double r = 2.0 * kPi * cutoff_hz * dt_sec;
    return r / (r + 1.0);
}

inline double filter_1d(hs_one_euro_channel_t *ch, double x, double dt_sec, double min_cutoff, double beta, double d_cutoff) {
    if (!ch->initialized || dt_sec <= 0.0 || dt_sec > 0.5) {
        ch->x_prev      = x;
        ch->dx_prev     = 0.0;
        ch->initialized = true;
        return x;
    }

    const double dx = (x - ch->x_prev) / dt_sec;
    const double a_d = smoothing_factor(dt_sec, d_cutoff);
    const double edx = a_d * dx + (1.0 - a_d) * ch->dx_prev;
    ch->dx_prev = edx;

    const double cutoff = min_cutoff + beta * fabs(edx);
    const double a = smoothing_factor(dt_sec, cutoff);
    const double x_hat = a * x + (1.0 - a) * ch->x_prev;
    ch->x_prev = x_hat;
    return x_hat;
}

} // namespace

void hs_one_euro_init(
    hs_one_euro_filter_2d_t *filter,
    double min_cutoff,
    double beta,
    double d_cutoff
) {
    if (!filter) return;
    filter->min_cutoff        = min_cutoff > 0.0 ? min_cutoff : 1.2;
    filter->beta              = beta >= 0.0 ? beta : 0.015;
    filter->d_cutoff          = d_cutoff > 0.0 ? d_cutoff : 1.0;
    filter->last_timestamp_us = 0;
    hs_one_euro_reset(filter);
}

void hs_one_euro_reset(hs_one_euro_filter_2d_t *filter) {
    if (!filter) return;
    filter->last_timestamp_us = 0;
    filter->ch_x.x_prev       = 0.0;
    filter->ch_x.dx_prev      = 0.0;
    filter->ch_x.initialized  = false;
    filter->ch_y.x_prev       = 0.0;
    filter->ch_y.dx_prev      = 0.0;
    filter->ch_y.initialized  = false;
}

void hs_one_euro_update_2d(
    hs_one_euro_filter_2d_t *filter,
    uint64_t timestamp_us,
    float raw_x,
    float raw_y,
    float *out_x,
    float *out_y
) {
    if (!filter || !out_x || !out_y) return;

    double dt_sec = 0.0;
    if (filter->last_timestamp_us > 0 && timestamp_us > filter->last_timestamp_us) {
        dt_sec = static_cast<double>(timestamp_us - filter->last_timestamp_us) * 1e-6;
    }
    filter->last_timestamp_us = timestamp_us;

    *out_x = static_cast<float>(filter_1d(&filter->ch_x, raw_x, dt_sec, filter->min_cutoff, filter->beta, filter->d_cutoff));
    *out_y = static_cast<float>(filter_1d(&filter->ch_y, raw_y, dt_sec, filter->min_cutoff, filter->beta, filter->d_cutoff));
}

void hs_one_euro_predict_2d(
    const hs_one_euro_filter_2d_t *filter,
    uint64_t lookahead_us,
    float *pred_x,
    float *pred_y
) {
    if (!filter || !pred_x || !pred_y) return;
    const double dt_sec = static_cast<double>(lookahead_us) * 1e-6;
    *pred_x = static_cast<float>(filter->ch_x.x_prev + filter->ch_x.dx_prev * dt_sec);
    *pred_y = static_cast<float>(filter->ch_y.x_prev + filter->ch_y.dx_prev * dt_sec);
}

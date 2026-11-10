// WSOLA time stretcher for key lock ("master tempo"): changes tempo without changing pitch.
// Header only, no allocation, cheap enough for budget phones (~6 M multiply-adds per second per deck).
//
// The caller supplies `fetch(start, count, l, r)` which fills stereo float frames of the (stem-summed) source
// at the file's own sample rate, zero outside the track. Output is produced in hops of HS frames into a ring
// and is read back by the caller at a constant rate (file rate -> device rate conversion only).
#pragma once
#include <cstdint>
#include <cmath>
#include <cstring>
#include <algorithm>

struct Stretch {
    static constexpr int N = 1024;            // segment length (about 23 ms at 44.1 kHz)
    static constexpr int HS = N / 2;          // synthesis hop (50 % overlap, Hann: sums to exactly 1)
    static constexpr int DELTA = 160;         // waveform search radius around the nominal position (about 3.6 ms)
    static constexpr int RING = 8192;         // power of two
    static constexpr int NBLK = 32;           // per-block position bookkeeping
    static constexpr int LEN = N + 2 * DELTA;

    float win[N];
    float pend[2][HS];
    float ring[2][RING];
    float buf[2][LEN];
    float tl[HS], tr[HS];
    float tgt[HS];
    float cand[LEN];
    double blkPos[NBLK];
    float blkAlpha[NBLK];
    int64_t wr = 0;                           // frames produced so far (always a multiple of HS)
    double rd = 0;                            // read position in produced frames
    double inPos = 0;                         // nominal analysis position (source frames)
    int64_t prevStart = -1;

    Stretch() {
        for (int i = 0; i < N; i++) win[i] = 0.5f - 0.5f * cosf(2.f * 3.14159265358979f * (float)i / (float)N);
        reset(0);
    }

    void reset(double pos) {
        inPos = pos; prevStart = -1; wr = 0; rd = 0;
        memset(pend, 0, sizeof pend);
        memset(ring, 0, sizeof ring);
        for (int i = 0; i < NBLK; i++) { blkPos[i] = pos; blkAlpha[i] = 1.f; }
    }

    // Source position (frames) of the audio that is being read right now.
    double posAt() const {
        int64_t b = (int64_t)rd / HS;
        if (b < 0) b = 0;
        int k = (int)(b % NBLK);
        return blkPos[k] + (rd - (double)(b * HS)) * blkAlpha[k];
    }

    bool needsHop() const { return wr - (int64_t)rd < 2; }

    inline void read(float &l, float &r) const {
        int64_t i0 = (int64_t)rd;
        float f = (float)(rd - (double)i0);
        int a = (int)(i0 & (RING - 1)), b = (int)((i0 + 1) & (RING - 1));
        l = ring[0][a] + (ring[0][b] - ring[0][a]) * f;
        r = ring[1][a] + (ring[1][b] - ring[1][a]) * f;
    }

    // Produces HS more output frames. alpha = tempo ratio (source frames consumed per output frame).
    template <class Fetch>
    void hop(float alpha, Fetch &&fetch) {
        const int64_t nom = (int64_t)llround(inPos);
        int64_t start;
        const float *sl, *sr;
        if (prevStart < 0) {
            start = nom;
            fetch(start, N, buf[0], buf[1]);
            sl = buf[0]; sr = buf[1];
        } else {
            const int64_t lo = nom - DELTA;
            fetch(lo, LEN, buf[0], buf[1]);
            fetch(prevStart + HS, HS, tl, tr);       // natural continuation of the previous segment
            for (int i = 0; i < HS; i++) tgt[i] = 0.5f * (tl[i] + tr[i]);
            for (int i = 0; i < LEN; i++) cand[i] = 0.5f * (buf[0][i] + buf[1][i]);
            // coarse search (every other candidate and sample), then refine +-1
            int best = DELTA; float bestScore = -1e30f;
            auto score = [&](int d, int step) {
                float c = 0, e = 1e-3f;
                const float *p = cand + d;
                for (int i = 0; i < HS; i += step) { c += p[i] * tgt[i]; e += p[i] * p[i]; }
                return c / sqrtf(e);
            };
            for (int d = 0; d <= 2 * DELTA; d += 2) {
                float s = score(d, 2);
                if (s > bestScore) { bestScore = s; best = d; }
            }
            int fb = best; float fs = -1e30f;
            for (int d = std::max(0, best - 1); d <= std::min(2 * DELTA, best + 1); d++) {
                float s = score(d, 1);
                if (s > fs) { fs = s; fb = d; }
            }
            start = lo + fb;
            sl = buf[0] + fb; sr = buf[1] + fb;
        }
        for (int i = 0; i < HS; i++) {
            int idx = (int)((wr + i) & (RING - 1));
            ring[0][idx] = pend[0][i] + sl[i] * win[i];
            ring[1][idx] = pend[1][i] + sr[i] * win[i];
        }
        for (int i = 0; i < HS; i++) {
            pend[0][i] = sl[i + HS] * win[i + HS];
            pend[1][i] = sr[i + HS] * win[i + HS];
        }
        int k = (int)((wr / HS) % NBLK);
        blkPos[k] = (double)start; blkAlpha[k] = alpha;
        wr += HS;
        prevStart = start;
        inPos += (double)HS * (double)alpha;
    }
};

// DjApp audio engine: 2 decks, 4 stems each, 32-pad sampler, per-deck DJ filter / phaser / reverb,
// loops, jog (scratch), master soft-clip, WAV recorder.
#include <jni.h>
#include "stretch.h"
#include <oboe/Oboe.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>
#include <memory>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace {

constexpr int CHUNK = 256;
constexpr float kPi = 3.14159265358979f;

inline int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch()).count();
}

inline float softClip(float x) {
    float a = fabsf(x);
    if (a <= 0.9f) return x;
    float y = 0.9f + 0.1f * tanhf((a - 0.9f) * 10.f);
    return x < 0 ? -y : y;
}

// ---------------------------------------------------------------- DJ filter
struct Biquad {
    float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
    float z1[2] = {0, 0}, z2[2] = {0, 0};
    void set(bool low, float fc, float q, float fs) {
        fc = std::max(20.f, std::min(fc, fs * 0.45f));
        float w = 2 * kPi * fc / fs, c = cosf(w), al = sinf(w) / (2 * q), a0 = 1 + al;
        if (low) { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; }
        else     { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; }
        b2 = b0; a1 = -2 * c / a0; a2 = (1 - al) / a0;
    }
    inline float p(int ch, float x) {
        x += 1e-18f;  // anti-denormal
        float y = b0 * x + z1[ch];
        z1[ch] = b1 * x - a1 * y + z2[ch];
        z2[ch] = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0; }
};

// One knob: left of centre = low-pass sweeping down, right = high-pass sweeping up. 24 dB/oct.
struct DjFilter {
    Biquad s1, s2;
    float cur = 0;
    bool lastLow = true;
    void process(float *l, float *r, int m, float target, float fs) {
        cur += (target - cur) * 0.25f;
        if (fabsf(cur) < 0.02f) { s1.reset(); s2.reset(); return; }
        bool low = cur < 0;
        if (low != lastLow) { s1.reset(); s2.reset(); lastLow = low; }
        float a = fabsf(cur);
        float fc = low ? 20000.f * powf(0.004f, a) : 20.f * powf(400.f, a);
        s1.set(low, fc, 0.54f, fs);
        s2.set(low, fc, 1.31f, fs);
        for (int i = 0; i < m; i++) {
            l[i] = s2.p(0, s1.p(0, l[i]));
            r[i] = s2.p(1, s1.p(1, r[i]));
        }
    }
};

// ---------------------------------------------------------------- Phaser
// 6 all-pass stages swept by a sine LFO (L/R 90 degrees apart for width), with feedback.
struct Phaser {
    float z[2][6] = {};
    float last[2] = {0, 0};
    float lfo = 0;
    void reset() { memset(z, 0, sizeof z); last[0] = last[1] = 0; }
    void process(float *l, float *r, int m, float amt, float rate, float fs) {
        if (amt < 0.005f) { reset(); return; }
        const float mixW = 0.5f * amt, fb = 0.55f;
        for (int i = 0; i < m; i++) {
            lfo += rate / fs; if (lfo >= 1.f) lfo -= 1.f;
            for (int ch = 0; ch < 2; ch++) {
                float ph = lfo + (ch ? 0.25f : 0.f); if (ph >= 1.f) ph -= 1.f;
                float s = 0.5f - 0.5f * cosf(2 * kPi * ph);
                float f = 250.f * powf(12.f, s);                 // 250 Hz .. 3 kHz
                float t = tanf(kPi * f / fs);
                float a = (t - 1.f) / (t + 1.f);
                float x = ch ? r[i] : l[i];
                float y = x + fb * last[ch];
                for (int st = 0; st < 6; st++) {
                    float o = a * y + z[ch][st];
                    z[ch][st] = y - a * o;
                    y = o;
                }
                last[ch] = y;
                (ch ? r[i] : l[i]) = x * (1.f - mixW) + y * mixW;
            }
        }
    }
};

// ---------------------------------------------------------------- Reverb (Freeverb)
struct Reverb {
    static constexpr int NC = 8, NA = 4;
    std::vector<float> cb[2][NC], ab[2][NA];
    int ci[2][NC] = {}, ai[2][NA] = {};
    float cs[2][NC] = {};
    bool cleared = true;

    void init(float fs) {
        static const int ct[NC] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int at[NA] = {556, 441, 341, 225};
        float sc = fs / 44100.f;
        for (int ch = 0; ch < 2; ch++) {
            int spread = ch ? 23 : 0;
            for (int i = 0; i < NC; i++) cb[ch][i].assign(std::max(1, (int)((ct[i] + spread) * sc)), 0.f);
            for (int i = 0; i < NA; i++) ab[ch][i].assign(std::max(1, (int)((at[i] + spread) * sc)), 0.f);
        }
        clear();
    }
    void clear() {
        for (int ch = 0; ch < 2; ch++) {
            for (int i = 0; i < NC; i++) { std::fill(cb[ch][i].begin(), cb[ch][i].end(), 0.f); ci[ch][i] = 0; cs[ch][i] = 0; }
            for (int i = 0; i < NA; i++) { std::fill(ab[ch][i].begin(), ab[ch][i].end(), 0.f); ai[ch][i] = 0; }
        }
        cleared = true;
    }
    void process(float *l, float *r, int m, float wet, float room) {
        if (wet < 0.002f) { if (!cleared) clear(); return; }
        cleared = false;
        const float fb = room * 0.28f + 0.7f, damp = 0.35f;
        const float dry = 1.f - 0.3f * wet, g = wet * 1.8f;
        for (int i = 0; i < m; i++) {
            float in = (l[i] + r[i]) * 0.015f + 1e-18f;
            float o[2] = {0, 0};
            for (int ch = 0; ch < 2; ch++) {
                for (int c = 0; c < NC; c++) {
                    std::vector<float> &b = cb[ch][c];
                    int &ix = ci[ch][c];
                    float y = b[ix];
                    cs[ch][c] = y * (1 - damp) + cs[ch][c] * damp;
                    b[ix] = in + cs[ch][c] * fb;
                    if (++ix >= (int)b.size()) ix = 0;
                    o[ch] += y;
                }
                for (int a = 0; a < NA; a++) {
                    std::vector<float> &b = ab[ch][a];
                    int &ix = ai[ch][a];
                    float bo = b[ix], t = o[ch];
                    o[ch] = -t + bo;
                    b[ix] = t + bo * 0.5f;
                    if (++ix >= (int)b.size()) ix = 0;
                }
            }
            l[i] = l[i] * dry + o[0] * g;
            r[i] = r[i] * dry + o[1] * g;
        }
    }
};

// ---------------------------------------------------------------- 3-band EQ (bass / mid / treble), -26 .. +12 dB
struct Eq {
    struct Bq { float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1[2] = {0, 0}, z2[2] = {0, 0}; };
    Bq bq[3];
    float cur[3] = {0, 0, 0};              // smoothed gain in dB (smoothing avoids zipper noise while a fader moves)
    float applied[3] = {1e9f, 1e9f, 1e9f};
    static void coefs(Bq &q, int band, float db, float sr) {
        const float A = powf(10.f, db / 40.f);
        const float f = band == 0 ? 110.f : band == 1 ? 1000.f : 5000.f;
        const float w0 = 2.f * 3.14159265f * f / sr, cs = cosf(w0), sn = sinf(w0);
        float b0, b1, b2, a0, a1, a2;
        if (band == 1) {                                   // peaking, Q 0.7
            const float al = sn / (2.f * 0.7f);
            b0 = 1 + al * A; b1 = -2 * cs; b2 = 1 - al * A; a0 = 1 + al / A; a1 = -2 * cs; a2 = 1 - al / A;
        } else {
            const float al = sn / 2.f * 1.41421356f, t = 2.f * sqrtf(A) * al;
            if (band == 0) {                               // low shelf
                b0 = A * ((A + 1) - (A - 1) * cs + t); b1 = 2 * A * ((A - 1) - (A + 1) * cs); b2 = A * ((A + 1) - (A - 1) * cs - t);
                a0 = (A + 1) + (A - 1) * cs + t; a1 = -2 * ((A - 1) + (A + 1) * cs); a2 = (A + 1) + (A - 1) * cs - t;
            } else {                                       // high shelf
                b0 = A * ((A + 1) + (A - 1) * cs + t); b1 = -2 * A * ((A - 1) + (A + 1) * cs); b2 = A * ((A + 1) + (A - 1) * cs - t);
                a0 = (A + 1) - (A - 1) * cs + t; a1 = 2 * ((A - 1) - (A + 1) * cs); a2 = (A + 1) - (A - 1) * cs - t;
            }
        }
        q.b0 = b0 / a0; q.b1 = b1 / a0; q.b2 = b2 / a0; q.a1 = a1 / a0; q.a2 = a2 / a0;
    }
    void process(float *l, float *r, int m, const float target[3], float sr) {
        for (int b = 0; b < 3; b++) {
            cur[b] += (target[b] - cur[b]) * 0.35f;
            if (fabsf(target[b] - cur[b]) < 0.01f) cur[b] = target[b];
            if (fabsf(cur[b]) < 0.05f) {                   // flat: bypass, no CPU
                bq[b].z1[0] = bq[b].z1[1] = bq[b].z2[0] = bq[b].z2[1] = 0; applied[b] = 1e9f; continue;
            }
            if (fabsf(cur[b] - applied[b]) > 0.01f) { coefs(bq[b], b, cur[b], sr); applied[b] = cur[b]; }
            Bq &q = bq[b];
            for (int i = 0; i < m; i++) {
                float x = l[i], y = q.b0 * x + q.z1[0];
                q.z1[0] = q.b1 * x - q.a1 * y + q.z2[0]; q.z2[0] = q.b2 * x - q.a2 * y; l[i] = y;
                x = r[i]; y = q.b0 * x + q.z1[1];
                q.z1[1] = q.b1 * x - q.a1 * y + q.z2[1]; q.z2[1] = q.b2 * x - q.a2 * y; r[i] = y;
            }
        }
    }
};

// ---------------------------------------------------------------- Deck
struct Deck {
    std::mutex lock;                       // audio thread only try_locks it
    const int16_t *stem[4] = {nullptr, nullptr, nullptr, nullptr};
    size_t mapBytes[4] = {0, 0, 0, 0};
    int nStems = 0;                        // 1 = full mix, 4 = stems
    int64_t frames = 0;
    int fileRate = 44100;
    double pos = 0;                        // audio thread only (use seekReq from outside)
    float rate = 0;                        // current smoothed playback rate (1 = normal)
    float stemCur[4] = {1, 1, 1, 1};
    float lastGain = 0;

    std::atomic<bool> playing{false}, jogActive{false}, loopOn{false};
    std::atomic<float> volume{1.f}, tempo{1.f}, jogRate{0.f};
    std::atomic<float> filter{0.f}, revWet{0.f}, revRoom{0.6f}, phAmt{0.f}, phRate{0.5f};
    std::atomic<float> stemVol[4];
    std::atomic<double> posOut{0}, seekReq{-1.0}, loopIn{0}, loopOut{0};
    std::atomic<int64_t> jogStamp{0};
    std::atomic<bool> keylock{false};
    std::atomic<double> jogTarget{0};      // scratch: track position (seconds) the platter is held at
    Stretch st; bool stActive = false;     // key-lock stretcher (audio thread only)
    float gcur[4] = {1, 1, 1, 1};

    std::atomic<float> eqDb[3];
    Eq eq;
    DjFilter filt; Phaser phaser; Reverb reverb;
    Deck() { for (auto &v : stemVol) v = 1.f; for (auto &e : eqDb) e = 0.f; }
};

// ---------------------------------------------------------------- Sampler voice (one per pad)
constexpr int NSAMPLES = 32;
struct Voice {
    std::mutex lock;                       // audio thread only try_locks it
    const int16_t *data = nullptr;
    size_t bytes = 0;
    int64_t frames = 0;
    int rate = 44100;
    double pos = 0;
    float env = 0;
    bool gate = false, active = false;     // gate = key held / sound should sound; active = still rendering (release tail)
    std::atomic<int> cmd{0};               // 1 = trigger, 2 = stop
    std::atomic<bool> loop{false}, playingOut{false};
};

// ---------------------------------------------------------------- Recorder
struct Recorder {
    std::atomic<bool> active{false}, running{false};
    std::vector<float> ring;
    std::atomic<size_t> head{0}, tail{0};
    std::thread th;
    FILE *f = nullptr;
    uint32_t dataBytes = 0;

    static void w32(FILE *f, uint32_t v) { fwrite(&v, 4, 1, f); }
    static void w16(FILE *f, uint16_t v) { fwrite(&v, 2, 1, f); }
    void header(int rate) {
        fwrite("RIFF", 1, 4, f); w32(f, 36); fwrite("WAVEfmt ", 1, 8, f);
        w32(f, 16); w16(f, 1); w16(f, 2); w32(f, rate); w32(f, rate * 4); w16(f, 4); w16(f, 16);
        fwrite("data", 1, 4, f); w32(f, 0);
    }
    bool start(const char *path, int rate) {
        if (active.load()) return false;
        f = fopen(path, "wb");
        if (!f) return false;
        header(rate);
        dataBytes = 0;
        ring.assign(1 << 19, 0.f);
        head = 0; tail = 0;
        running = true;
        th = std::thread(&Recorder::run, this);
        active.store(true, std::memory_order_release);
        return true;
    }
    void push(const float *in, int frames) {      // audio thread
        if (!active.load(std::memory_order_acquire)) return;
        size_t cap = ring.size(), h = head.load(std::memory_order_relaxed);
        size_t t = tail.load(std::memory_order_acquire), need = (size_t)frames * 2;
        if (cap - (h - t) < need) return;         // writer too slow: drop
        for (size_t i = 0; i < need; i++) ring[(h + i) & (cap - 1)] = in[i];
        head.store(h + need, std::memory_order_release);
    }
    void run() {
        std::vector<int16_t> tmp(8192);
        size_t cap = ring.size();
        while (true) {
            size_t h = head.load(std::memory_order_acquire), t = tail.load(std::memory_order_relaxed);
            size_t avail = h - t;
            if (avail == 0) {
                if (!running.load()) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(15));
                continue;
            }
            size_t cnt = std::min(avail, tmp.size());
            for (size_t i = 0; i < cnt; i++) {
                float v = ring[(t + i) & (cap - 1)];
                v = std::max(-1.f, std::min(1.f, v));
                tmp[i] = (int16_t)(v * 32767.f);
            }
            fwrite(tmp.data(), 2, cnt, f);
            dataBytes += (uint32_t)(cnt * 2);
            tail.store(t + cnt, std::memory_order_release);
        }
    }
    void stop() {
        if (!active.load()) return;
        active.store(false, std::memory_order_release);
        running = false;
        if (th.joinable()) th.join();
        fseek(f, 4, SEEK_SET); w32(f, 36 + dataBytes);
        fseek(f, 40, SEEK_SET); w32(f, dataBytes);
        fclose(f); f = nullptr;
    }
};

// ---------------------------------------------------------------- Engine
class Engine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Deck decks[2];
    std::atomic<float> crossfader{0.5f}, level{0.f};
    Voice voices[NSAMPLES];
    std::atomic<float> sampVol{0.8f}, masterGain{1.f};
    std::atomic<int> xcurve{0};            // 0 smooth (constant power), 1 sharp (cut), 2 linear
    std::shared_ptr<oboe::AudioStream> stream;
    int32_t lastXrun = 0;
    float srate = 44100.f;
    bool wanted = false;
    int audioMode = 0;                     // 0 fast, 1 balanced (budget phones), 2 safe (biggest buffer)
    Recorder rec;

    bool openStream(oboe::SharingMode mode) {
        oboe::AudioStreamBuilder b;
        b.setDirection(oboe::Direction::Output)
         ->setPerformanceMode(audioMode == 2 ? oboe::PerformanceMode::None : oboe::PerformanceMode::LowLatency)
         ->setSharingMode(mode)
         ->setFormat(oboe::AudioFormat::Float)
         ->setChannelCount(2)
         ->setUsage(oboe::Usage::Media)
         ->setDataCallback(std::shared_ptr<oboe::AudioStreamDataCallback>(this, [](oboe::AudioStreamDataCallback *) {}))
         ->setErrorCallback(std::shared_ptr<oboe::AudioStreamErrorCallback>(this, [](oboe::AudioStreamErrorCallback *) {}));
        return b.openStream(stream) == oboe::Result::OK;
    }

    bool start() {
        wanted = true;
        if (stream) return true;
        const bool ok = (audioMode == 0) ? (openStream(oboe::SharingMode::Exclusive) || openStream(oboe::SharingMode::Shared))
                                         : openStream(oboe::SharingMode::Shared);
        if (!ok) return false;
        srate = (float)stream->getSampleRate();
        for (auto &d : decks) { d.reverb.init(srate); d.phaser.reset(); d.filt.s1.reset(); d.filt.s2.reset(); }
        stream->setBufferSizeInFrames(stream->getFramesPerBurst() * (audioMode == 0 ? 3 : audioMode == 1 ? 6 : 16));
        lastXrun = 0;
        return stream->requestStart() == oboe::Result::OK;
    }
    bool setMode(int m) {
        audioMode = m;
        if (!stream) return true;
        stream->stop(); stream->close(); stream.reset();
        return start();
    }
    void stop() {
        wanted = false;
        rec.stop();
        if (stream) { stream->stop(); stream->close(); stream.reset(); }
    }
    // Headphones unplugged / route changed: reopen on the new device.
    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override {
        stream.reset();
        if (wanted) start();
    }

    void renderDeck(Deck &k, float xgain, int m, float *o) {
        float bl[CHUNK], br[CHUNK];
        memset(bl, 0, sizeof(float) * m);
        memset(br, 0, sizeof(float) * m);

        std::unique_lock<std::mutex> g(k.lock, std::try_to_lock);
        bool have = g.owns_lock() && k.frames >= 2 && k.nStems > 0;

        if (have) {
            double sr = k.seekReq.exchange(-1.0);
            if (sr >= 0) { k.pos = std::min(sr * k.fileRate, (double)(k.frames - 2)); k.stActive = false; }

            bool jog = k.jogActive.load();
            if (jog && nowMs() - k.jogStamp.load() > 1500) { jog = false; k.jogActive = false; }   // UI went away: let go
            const float tempo = k.tempo.load();
            const float target = jog ? 0.f : (k.playing.load() ? tempo : 0.f);
            const float dtl = fabsf(tempo - 1.f);
            const bool stretch = k.keylock.load() && !jog && (k.stActive ? dtl > 0.0015f : dtl > 0.004f);
            const float coef = 0.0015f;

            float s0[4], s1[4];
            for (int i = 0; i < 4; i++) { s0[i] = k.stemCur[i]; s1[i] = k.stemVol[i].load(); k.stemCur[i] = s1[i]; }

            const bool lo = k.loopOn.load();
            const double li = k.loopIn.load(), lout = k.loopOut.load();
            const double step0 = (double)k.fileRate / srate;
            const float inv = 1.f / 32768.f;

            if (stretch) {
                // ---- key lock: WSOLA on the stem-summed signal, tempo changes without pitch change
                if (!k.stActive) { k.st.reset(k.pos); k.stActive = true; for (int i = 0; i < 4; i++) k.gcur[i] = s1[i]; }
                auto fetch = [&](int64_t st0, int n, float *l, float *r) {
                    for (int j = 0; j < n; j++) { l[j] = 0.f; r[j] = 0.f; }
                    const int64_t a0 = std::max<int64_t>(st0, 0), b0 = std::min<int64_t>(st0 + n, k.frames);
                    if (b0 <= a0) return;
                    for (int si = 0; si < k.nStems; si++) {
                        const float g = k.gcur[si] * inv;
                        if (g < 0.0005f * inv) continue;
                        const int16_t *a = k.stem[si];
                        for (int64_t p = a0; p < b0; p++) { l[p - st0] += g * a[2 * p]; r[p - st0] += g * a[2 * p + 1]; }
                    }
                };
                for (int i = 0; i < m; i++) {
                    k.rate += (target - k.rate) * coef;
                    if (fabsf(k.rate) < 1e-4f && target == 0.f) { k.rate = 0; k.stActive = false; continue; }
                    if (k.st.inPos >= (double)(k.frames - 2) && k.rate > 0) {
                        k.playing = false; k.rate = 0; k.stActive = false; k.pos = (double)(k.frames - 2); break;
                    }
                    while (k.st.needsHop()) {
                        for (int si = 0; si < 4; si++) k.gcur[si] += (k.stemVol[si].load() - k.gcur[si]) * 0.5f;
                        k.st.hop(std::max(0.f, k.rate), fetch);
                        if (lo && lout > li && k.st.inPos >= lout) k.st.inPos -= (lout - li);
                    }
                    k.st.read(bl[i], br[i]);
                    k.st.rd += step0;
                }
                if (k.stActive) k.pos = std::max(0.0, k.st.posAt());
            } else {
                k.stActive = false;
                // Scratch: the platter position is the master. The deck follows it with a short smoothing time,
                // so finger jitter between touch events never shows up as zipper noise, and holding still = silence.
                const double jogTgt = k.jogTarget.load() * k.fileRate;
                const double jogA = 1.0 / (0.018 * (double)srate);
                for (int i = 0; i < m; i++) {
                    float ga = 1.f;
                    if (jog) {
                        float want = (float)(((jogTgt - k.pos) * jogA) / step0);
                        want = std::max(-24.f, std::min(24.f, want));
                        k.rate += (want - k.rate) * 0.05f;
                        ga = std::min(1.f, fabsf(k.rate) * 6.f);
                    } else {
                        k.rate += (target - k.rate) * coef;
                        if (fabsf(k.rate) < 1e-4f && target == 0.f) { k.rate = 0; continue; }
                    }
                    if (k.pos < 0) { k.pos = 0; if (k.rate < 0) k.rate = 0; }
                    int64_t p = (int64_t)k.pos;
                    if (p + 1 >= k.frames) {
                        if (k.rate > 0) { k.playing = false; k.rate = 0; k.pos = (double)(k.frames - 2); }
                        continue;
                    }
                    float f = (float)(k.pos - (double)p), l = 0, r = 0;
                    float t = (float)(i + 1) / (float)m;
                    for (int si = 0; si < k.nStems; si++) {
                        float gs = s0[si] + (s1[si] - s0[si]) * t;
                        if (gs < 0.0005f) continue;
                        const int16_t *a = &k.stem[si][p * 2], *c = a + 2;
                        l += gs * (a[0] + (c[0] - a[0]) * f);
                        r += gs * (a[1] + (c[1] - a[1]) * f);
                    }
                    bl[i] = l * inv * ga; br[i] = r * inv * ga;
                    k.pos += (double)k.rate * step0;
                    if (lo && k.rate > 0 && lout > li && k.pos >= lout) k.pos -= (lout - li);
                }
            }
            k.posOut = k.pos / k.fileRate;
        }

        // Effects always run (so reverb tails ring out after pause).
        { const float eg[3] = {k.eqDb[0].load(), k.eqDb[1].load(), k.eqDb[2].load()}; k.eq.process(bl, br, m, eg, srate); }
        k.filt.process(bl, br, m, k.filter.load(), srate);
        k.phaser.process(bl, br, m, k.phAmt.load(), k.phRate.load(), srate);
        k.reverb.process(bl, br, m, k.revWet.load(), k.revRoom.load());

        float tg = k.volume.load() * xgain, lg = k.lastGain;
        for (int i = 0; i < m; i++) {
            float gain = lg + (tg - lg) * (float)(i + 1) / (float)m;
            o[2 * i] += bl[i] * gain;
            o[2 * i + 1] += br[i] * gain;
        }
        k.lastGain = tg;
    }

    void renderSampler(int m, float *o) {
        const float sv = sampVol.load();
        const float inv = 1.f / 32768.f;
        for (auto &v : voices) {
            std::unique_lock<std::mutex> g(v.lock, std::try_to_lock);
            if (!g.owns_lock() || !v.data || v.frames < 2) continue;
            int c = v.cmd.exchange(0);
            if (c == 1) { v.pos = 0; v.active = true; v.gate = true; }
            else if (c == 2) v.gate = false;
            if (!v.active) { v.playingOut.store(false); continue; }
            const bool lp = v.loop.load();
            const double step = (double)v.rate / srate;
            for (int i = 0; i < m; i++) {
                if (v.gate) v.env = std::min(1.f, v.env + 1.f / 48.f);
                else { v.env -= 1.f / 480.f; if (v.env <= 0.f) { v.env = 0; v.active = false; break; } }
                int64_t p = (int64_t)v.pos;
                if (p + 1 >= v.frames) {
                    if (lp) { v.pos = 0; p = 0; }
                    else { v.active = false; v.gate = false; v.env = 0; break; }
                }
                float f = (float)(v.pos - (double)p);
                const int16_t *a = &v.data[p * 2], *b = a + 2;
                float gn = v.env * sv * inv;
                o[2 * i]     += gn * (a[0] + (b[0] - a[0]) * f);
                o[2 * i + 1] += gn * (a[1] + (b[1] - a[1]) * f);
                v.pos += step;
            }
            v.playingOut.store(v.active);
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t n) override {
        auto xr = s->getXRunCount();
        if (xr && xr.value() > lastXrun) {   // grow buffer on underrun (weak CPUs)
            lastXrun = xr.value();
            s->setBufferSizeInFrames(s->getBufferSizeInFrames() + s->getFramesPerBurst());
        }
        float *out = static_cast<float *>(data);
        memset(out, 0, sizeof(float) * 2 * n);
        float x = crossfader.load();
        float xg[2];
        switch (xcurve.load()) {
            case 1:  xg[0] = std::min(1.f, (1.f - x) * 8.f); xg[1] = std::min(1.f, x * 8.f); break;
            case 2:  xg[0] = 1.f - x; xg[1] = x; break;
            default: xg[0] = cosf(x * kPi / 2); xg[1] = sinf(x * kPi / 2); break;
        }
        const float mg = masterGain.load();
        float peak = 0;
        int done = 0;
        while (done < n) {
            int m = std::min(CHUNK, n - done);
            float *o = out + 2 * done;
            renderDeck(decks[0], xg[0], m, o);
            renderDeck(decks[1], xg[1], m, o);
            renderSampler(m, o);
            for (int i = 0; i < 2 * m; i++) { o[i] = softClip(o[i] * mg); peak = std::max(peak, fabsf(o[i])); }
            done += m;
        }
        level.store(std::max(peak, level.load() * 0.95f));
        rec.push(out, n);
        return oboe::DataCallbackResult::Continue;
    }
};

Engine engine;

bool mapFile(const char *path, const int16_t *&out, size_t &bytes) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < 8) { close(fd); return false; }
    void *m = mmap(nullptr, st.st_size, PROT_READ, MAP_PRIVATE | MAP_POPULATE, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return false;
    out = (const int16_t *)m; bytes = st.st_size;
    return true;
}

// Mapping happens outside the lock; only the pointer swap is locked so audio never stalls.
void swapIn(Deck &k, const int16_t *p[4], size_t b[4], int n, int rate, bool resetPos) {
    int64_t frames = 0;
    if (n > 0) { frames = (int64_t)(b[0] / 4); for (int i = 1; i < n; i++) frames = std::min(frames, (int64_t)(b[i] / 4)); }
    std::lock_guard<std::mutex> g(k.lock);
    for (int i = 0; i < 4; i++) if (k.stem[i]) munmap((void *)k.stem[i], k.mapBytes[i]);
    for (int i = 0; i < 4; i++) { k.stem[i] = i < n ? p[i] : nullptr; k.mapBytes[i] = i < n ? b[i] : 0; }
    k.nStems = n; k.frames = frames;
    if (rate > 0) k.fileRate = rate;
    if (resetPos) {
        k.pos = 0; k.rate = 0; k.posOut = 0; k.seekReq = -1.0; k.loopOn = false; k.stActive = false;
        for (auto &v : k.stemVol) v = 1.f;
        for (auto &v : k.stemCur) v = 1.f;
    } else if (k.pos >= frames - 2) k.pos = std::max<int64_t>(0, frames - 2);
}

}  // namespace

#define JNI(name) Java_com_example_djapp_NativeEngine_##name

extern "C" {
JNIEXPORT jboolean JNICALL JNI(start)(JNIEnv *, jobject) { return engine.start(); }
JNIEXPORT void JNICALL JNI(stop)(JNIEnv *, jobject) { engine.stop(); }

JNIEXPORT void JNICALL JNI(loadDeck)(JNIEnv *env, jobject, jint d, jstring path, jint rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    const char *s = env->GetStringUTFChars(path, nullptr);
    int n = mapFile(s, p[0], b[0]) ? 1 : 0;
    env->ReleaseStringUTFChars(path, s);
    swapIn(k, p, b, n, rate, true);
}

static bool mapStems(JNIEnv *env, jobjectArray paths, const int16_t *p[4], size_t b[4]) {
    bool ok = env->GetArrayLength(paths) == 4;
    for (int i = 0; i < 4 && ok; i++) {
        jstring js = (jstring)env->GetObjectArrayElement(paths, i);
        const char *s = env->GetStringUTFChars(js, nullptr);
        ok = mapFile(s, p[i], b[i]);
        env->ReleaseStringUTFChars(js, s);
        env->DeleteLocalRef(js);
    }
    if (!ok) for (int i = 0; i < 4; i++) if (p[i]) { munmap((void *)p[i], b[i]); p[i] = nullptr; }
    return ok;
}

JNIEXPORT void JNICALL JNI(setStems)(JNIEnv *env, jobject, jint d, jobjectArray paths) {
    Deck &k = engine.decks[d];
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!mapStems(env, paths, p, b)) return;
    swapIn(k, p, b, 4, 0, false);   // keeps position and keeps playing
}

// Loads a prepared track: the 4 stems are the track (no separate full-mix file, no waiting).
JNIEXPORT jboolean JNICALL JNI(loadDeckStems)(JNIEnv *env, jobject, jint d, jobjectArray paths, jint rate) {
    Deck &k = engine.decks[d];
    k.playing = false; k.loopOn = false; k.jogActive = false;
    const int16_t *p[4] = {nullptr, nullptr, nullptr, nullptr}; size_t b[4] = {0, 0, 0, 0};
    if (!mapStems(env, paths, p, b)) return JNI_FALSE;
    swapIn(k, p, b, 4, rate, true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(setStemVolume)(JNIEnv *, jobject, jint d, jint s, jfloat v) { engine.decks[d].stemVol[s] = v; }
JNIEXPORT void JNICALL JNI(setPlaying)(JNIEnv *, jobject, jint d, jboolean p) { engine.decks[d].playing = p; }
JNIEXPORT jboolean JNICALL JNI(isPlaying)(JNIEnv *, jobject, jint d) { return engine.decks[d].playing.load(); }
JNIEXPORT void JNICALL JNI(setVolume)(JNIEnv *, jobject, jint d, jfloat v) { engine.decks[d].volume = v; }
JNIEXPORT void JNICALL JNI(setTempo)(JNIEnv *, jobject, jint d, jfloat t) { engine.decks[d].tempo = t; }
JNIEXPORT void JNICALL JNI(setCrossfader)(JNIEnv *, jobject, jfloat x) { engine.crossfader = x; }
JNIEXPORT jdouble JNICALL JNI(getPosition)(JNIEnv *, jobject, jint d) { return engine.decks[d].posOut.load(); }
JNIEXPORT jdouble JNICALL JNI(getDuration)(JNIEnv *, jobject, jint d) {
    Deck &k = engine.decks[d];
    return k.fileRate > 0 ? (double)k.frames / k.fileRate : 0.0;
}
JNIEXPORT void JNICALL JNI(seek)(JNIEnv *, jobject, jint d, jdouble sec) { engine.decks[d].seekReq = std::max(0.0, sec); }
JNIEXPORT jfloat JNICALL JNI(getLevel)(JNIEnv *, jobject) { return engine.level.load(); }

// Jog / scratch: while active the deck follows the platter position `targetSec` (forwards, backwards, held = silence).
JNIEXPORT void JNICALL JNI(setJog)(JNIEnv *, jobject, jint d, jboolean active, jdouble targetSec) {
    Deck &k = engine.decks[d];
    k.jogTarget = targetSec; k.jogStamp = nowMs(); k.jogActive = active;
}
JNIEXPORT void JNICALL JNI(setKeylock)(JNIEnv *, jobject, jint d, jboolean on) { engine.decks[d].keylock = on; }
JNIEXPORT jboolean JNICALL JNI(setAudioMode)(JNIEnv *, jobject, jint m) { return engine.setMode(m); }

// Loop points are in seconds of the track (not affected by tempo).
JNIEXPORT void JNICALL JNI(setLoop)(JNIEnv *, jobject, jint d, jdouble s, jdouble e, jboolean on) {
    Deck &k = engine.decks[d];
    k.loopOn = false;
    k.loopIn = s * k.fileRate; k.loopOut = e * k.fileRate;
    k.loopOn = (on && e > s);
}

// Effects (per deck)
JNIEXPORT void JNICALL JNI(setEq)(JNIEnv *, jobject, jint d, jint band, jfloat db) {
    if (band >= 0 && band < 3) engine.decks[d].eqDb[band] = std::max(-26.f, std::min(12.f, db));
}
JNIEXPORT void JNICALL JNI(setFilter)(JNIEnv *, jobject, jint d, jfloat v) { engine.decks[d].filter = v; }
JNIEXPORT void JNICALL JNI(setReverb)(JNIEnv *, jobject, jint d, jfloat wet, jfloat room) {
    engine.decks[d].revWet = wet; engine.decks[d].revRoom = room;
}
JNIEXPORT void JNICALL JNI(setPhaser)(JNIEnv *, jobject, jint d, jfloat amt, jfloat rateHz) {
    engine.decks[d].phAmt = amt; engine.decks[d].phRate = rateHz;
}

// Master recording to a 16-bit stereo WAV
JNIEXPORT jboolean JNICALL JNI(startRecording)(JNIEnv *env, jobject, jstring path) {
    const char *s = env->GetStringUTFChars(path, nullptr);
    bool ok = engine.rec.start(s, (int)engine.srate);
    env->ReleaseStringUTFChars(path, s);
    return ok;
}
JNIEXPORT void JNICALL JNI(stopRecording)(JNIEnv *, jobject) { engine.rec.stop(); }

// ---- Sampler (32 pad slots = 4 banks x 8) ----
JNIEXPORT jboolean JNICALL JNI(loadSample)(JNIEnv *env, jobject, jint slot, jstring path, jint rate) {
    if (slot < 0 || slot >= NSAMPLES) return JNI_FALSE;
    const int16_t *p = nullptr; size_t b = 0;
    const char *s = env->GetStringUTFChars(path, nullptr);
    bool ok = mapFile(s, p, b);
    env->ReleaseStringUTFChars(path, s);
    if (!ok) return JNI_FALSE;
    Voice &v = engine.voices[slot];
    std::lock_guard<std::mutex> g(v.lock);
    if (v.data) munmap((void *)v.data, v.bytes);
    v.data = p; v.bytes = b; v.frames = (int64_t)(b / 4); v.rate = rate > 0 ? rate : 44100;
    v.pos = 0; v.env = 0; v.active = false; v.gate = false; v.cmd = 0; v.playingOut = false;
    return JNI_TRUE;
}
JNIEXPORT void JNICALL JNI(clearSample)(JNIEnv *, jobject, jint slot) {
    if (slot < 0 || slot >= NSAMPLES) return;
    Voice &v = engine.voices[slot];
    std::lock_guard<std::mutex> g(v.lock);
    if (v.data) munmap((void *)v.data, v.bytes);
    v.data = nullptr; v.bytes = 0; v.frames = 0; v.active = false; v.gate = false; v.cmd = 0; v.playingOut = false;
}
JNIEXPORT void JNICALL JNI(triggerSample)(JNIEnv *, jobject, jint slot, jboolean loop) {
    if (slot < 0 || slot >= NSAMPLES) return;
    Voice &v = engine.voices[slot];
    v.loop = loop; v.cmd = 1; v.playingOut = true;
}
JNIEXPORT void JNICALL JNI(stopSample)(JNIEnv *, jobject, jint slot) {
    if (slot < 0 || slot >= NSAMPLES) return;
    engine.voices[slot].cmd = 2;
}
JNIEXPORT jboolean JNICALL JNI(isSamplePlaying)(JNIEnv *, jobject, jint slot) {
    return (slot >= 0 && slot < NSAMPLES) ? engine.voices[slot].playingOut.load() : JNI_FALSE;
}
JNIEXPORT void JNICALL JNI(setSampleVolume)(JNIEnv *, jobject, jfloat v) { engine.sampVol = v; }
JNIEXPORT void JNICALL JNI(setMasterGain)(JNIEnv *, jobject, jfloat v) { engine.masterGain = v; }
JNIEXPORT void JNICALL JNI(setCrossfaderCurve)(JNIEnv *, jobject, jint c) { engine.xcurve = c; }
}

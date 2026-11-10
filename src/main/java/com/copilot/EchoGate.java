package com.copilot;

import java.util.Map;
import java.util.TreeMap;

/**
 * Detects when the microphone is just re-hearing what the system audio is playing (speakers instead of headphones).
 * Both sources are reduced to a loudness-over-time curve (one value per 50 ms); if the mic's curve matches the
 * system's curve at some small delay, the mic chunk is an echo and is not worth transcribing. This works on the
 * audio itself, so it also catches echoes that Whisper would transcribe into text that looks nothing like the
 * original (e.g. Hindi heard by the English-only model). If you talk over the other side the curves stop
 * matching and your voice is transcribed as usual.
 */
public final class EchoGate {
    private static final int FRAME = 800;                  // 50 ms at 16 kHz
    private static final long FRAME_MS = 50;
    private static final long KEEP_MS = 20_000;
    private static final int MAX_LAG_FRAMES = 10;          // the mic may trail the system audio by up to 0.5 s
    private static final int MIN_PAIRS = 30;               // at least 1.5 s of overlap to judge
    private static final double SYSTEM_AUDIBLE = 0.008;    // average level below which there is nothing to echo
    private static final double MATCH = 0.55;              // correlation that counts as "the same sound"

    private final TreeMap<Long, Float> system = new TreeMap<>(); // frame start time (ms, multiple of 50) -> level

    /** Records a chunk of system audio that ended at {@code endMs}. */
    public synchronized void observeSystem(float[] samples, long endMs) {
        int n = samples.length / FRAME;
        for (int i = 0; i < n; i++) system.put(frameTime(endMs - (long) (n - i) * FRAME_MS), level(samples, i));
        system.headMap(endMs - KEEP_MS).clear();
    }

    /** True if this mic chunk (ending at {@code micEndMs}) is mostly an echo of recently observed system audio. */
    public synchronized boolean isEcho(float[] mic, long micEndMs) {
        int n = mic.length / FRAME;
        if (n < MIN_PAIRS) return false;
        float[] m = new float[n];
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            m[i] = level(mic, i);
            t[i] = frameTime(micEndMs - (long) (n - i) * FRAME_MS);
        }
        double best = -1;
        for (int lag = 0; lag <= MAX_LAG_FRAMES; lag++) { // mic frame at time t matches system frame at t - lag
            best = Math.max(best, correlation(m, t, lag));
        }
        return best >= MATCH;
    }

    private double correlation(float[] m, long[] t, int lag) {
        double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0, sysLevel = 0;
        int pairs = 0;
        for (int i = 0; i < m.length; i++) {
            Float y = system.get(t[i] - lag * FRAME_MS);
            if (y == null) continue;
            double x = m[i];
            sx += x; sy += y; sxx += x * x; syy += (double) y * y; sxy += x * y; sysLevel += y;
            pairs++;
        }
        if (pairs < MIN_PAIRS || sysLevel / pairs < SYSTEM_AUDIBLE) return -1;
        double cov = sxy - sx * sy / pairs, vx = sxx - sx * sx / pairs, vy = syy - sy * sy / pairs;
        return vx <= 1e-12 || vy <= 1e-12 ? -1 : cov / Math.sqrt(vx * vy);
    }

    private static long frameTime(long ms) { return Math.round(ms / (double) FRAME_MS) * FRAME_MS; }

    private static float level(float[] s, int frame) {
        double sum = 0;
        for (int i = frame * FRAME, end = i + FRAME; i < end; i++) sum += s[i] * s[i];
        return (float) Math.sqrt(sum / FRAME);
    }
}

package com.copilot;

/** Kaldi-style 80-bin log-mel filterbank features (what the speaker-embedding model expects), with mean normalisation. */
final class Fbank {
    private static final int SAMPLE_RATE = 16000, FRAME = 400, SHIFT = 160, NFFT = 512, BINS = 80;
    private static final double LOW_HZ = 20, HIGH_HZ = 8000, PREEMPH = 0.97, EPS = 1.1920929e-7;
    private static final double[] WINDOW = new double[FRAME];
    private static final double[][] MEL = melBank();

    static {
        for (int i = 0; i < FRAME; i++) WINDOW[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1)); // hamming
    }

    private Fbank() {}

    private static double mel(double hz) { return 1127.0 * Math.log(1.0 + hz / 700.0); }

    // Triangular filters equally spaced on the mel scale, over the FFT bins 0..NFFT/2.
    private static double[][] melBank() {
        double melLow = mel(LOW_HZ), melHigh = mel(HIGH_HZ), step = (melHigh - melLow) / (BINS + 1);
        double[][] bank = new double[BINS][NFFT / 2];
        for (int b = 0; b < BINS; b++) {
            double left = melLow + b * step, center = left + step, right = center + step;
            for (int k = 0; k < NFFT / 2; k++) {
                double m = mel((double) k * SAMPLE_RATE / NFFT);
                if (m > left && m < center) bank[b][k] = (m - left) / (center - left);
                else if (m >= center && m < right) bank[b][k] = (right - m) / (right - center);
            }
        }
        return bank;
    }

    /** @param samples 16 kHz mono in [-1, 1]; @return [frames][80], or null if the audio is too short for one frame */
    static float[][] compute(float[] samples) {
        int n = samples.length;
        if (n < FRAME) return null;
        int frames = 1 + (n - FRAME) / SHIFT;
        float[][] out = new float[frames][BINS];
        double[] re = new double[NFFT], im = new double[NFFT], x = new double[FRAME];
        for (int f = 0; f < frames; f++) {
            double mean = 0;
            for (int i = 0; i < FRAME; i++) { x[i] = samples[f * SHIFT + i] * 32768.0; mean += x[i]; }
            mean /= FRAME;
            for (int i = 0; i < FRAME; i++) x[i] -= mean;                          // remove DC offset
            for (int i = FRAME - 1; i > 0; i--) x[i] -= PREEMPH * x[i - 1];         // pre-emphasis
            x[0] -= PREEMPH * x[0];
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            for (int i = 0; i < FRAME; i++) re[i] = x[i] * WINDOW[i];
            fft(re, im);
            for (int b = 0; b < BINS; b++) {
                double e = 0;
                for (int k = 0; k < NFFT / 2; k++) if (MEL[b][k] != 0) e += MEL[b][k] * (re[k] * re[k] + im[k] * im[k]);
                out[f][b] = (float) Math.log(Math.max(e, EPS));
            }
        }
        for (int b = 0; b < BINS; b++) {                                            // per-utterance mean normalisation
            double m = 0;
            for (float[] row : out) m += row[b];
            m /= frames;
            for (float[] row : out) row[b] -= (float) m;
        }
        return out;
    }

    // In-place radix-2 FFT (length must be a power of two).
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }
}

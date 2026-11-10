package com.copilot;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tells voices apart on the system audio: each audio chunk gets a voice embedding (WeSpeaker ResNet34, run
 * locally with ONNX Runtime) and is matched to the nearest known voice, or starts a new one. This is online
 * clustering, so it is approximate: similar voices can merge and one voice can occasionally split.
 */
public final class SpeakerId {
    private static final String MODEL_FILE = "wespeaker_en_voxceleb_resnet34.onnx";
    private static final String MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/" + MODEL_FILE;
    private static final Path MODEL_PATH = AppDirs.path(".models", MODEL_FILE);
    private static final int MIN_SAMPLES = 16000;       // under one second the embedding is unreliable
    private static final double MIN_RMS = 0.004;        // near-silence carries no voice
    private static final int MAX_SPEAKERS = 6;

    private final double threshold;                      // cosine similarity needed to call two chunks the same voice
    private final List<float[]> centroids = new ArrayList<>(); // running mean of unit embeddings per voice
    private final List<Integer> counts = new ArrayList<>();
    private OrtEnvironment env;
    private OrtSession session;

    public SpeakerId(double threshold) { this.threshold = threshold; }

    public SpeakerId() { this(Double.parseDouble(Config.get("SPEAKER_THRESHOLD", "0.60"))); }

    /** True once the model file is on disk. */
    public static boolean modelReady() { return Files.exists(MODEL_PATH); }

    public synchronized void load() throws IOException {
        if (session != null) return;
        if (!Files.exists(MODEL_PATH)) Downloads.fetch(MODEL_URL, MODEL_PATH);
        try {
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(MODEL_PATH.toString());
        } catch (Exception e) {
            throw new IOException("Could not load the speaker model: " + e.getMessage(), e);
        }
    }

    /** Number of distinct voices heard so far. */
    public synchronized int speakers() { return centroids.size(); }

    public synchronized void reset() { centroids.clear(); counts.clear(); }

    /** @return voice index (0-based) for this chunk, or -1 if the chunk is too short/quiet to tell */
    public synchronized int identify(float[] samples) throws IOException {
        if (samples.length < MIN_SAMPLES || rms(samples) < MIN_RMS) return -1;
        load();
        float[] e = embed(samples);
        if (e == null) return -1;
        int best = -1;
        double bestSim = -1;
        for (int i = 0; i < centroids.size(); i++) {
            double sim = cosine(e, centroids.get(i));
            if (sim > bestSim) { bestSim = sim; best = i; }
        }
        if (best < 0 || (bestSim < threshold && centroids.size() < MAX_SPEAKERS)) {
            centroids.add(e.clone());
            counts.add(1);
            return centroids.size() - 1;
        }
        float[] c = centroids.get(best);
        int n = counts.get(best);
        for (int i = 0; i < c.length; i++) c[i] = (c[i] * n + e[i]) / (n + 1);
        counts.set(best, n + 1);
        return best;
    }

    // Unit-length 256-dim voice embedding for the chunk.
    float[] embed(float[] samples) throws IOException {
        float[][] feats = Fbank.compute(samples);
        if (feats == null) return null;
        try (OnnxTensor input = OnnxTensor.createTensor(env, new float[][][] {feats});
             OrtSession.Result result = session.run(Map.of("feats", input))) {
            float[] emb = ((float[][]) result.get(0).getValue())[0].clone();
            double norm = 0;
            for (float v : emb) norm += v * v;
            norm = Math.sqrt(norm);
            for (int i = 0; i < emb.length; i++) emb[i] /= (float) norm;
            return emb;
        } catch (Exception ex) {
            throw new IOException("Speaker model failed: " + ex.getMessage(), ex);
        }
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }

    private static double rms(float[] s) {
        double sum = 0;
        for (float v : s) sum += v * v;
        return Math.sqrt(sum / s.length);
    }
}
